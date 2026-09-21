#!/usr/bin/env bash
set -euo pipefail

# FEDORA-03 live recording and debugger qualification harness.
#
# Installs the packaged tarball into a private prefix without sudo, wires the
# locally built RemoteInput pair into the installed release, starts the packaged
# runtime as the desktop user with the qualification controller enabled, and
# drives the live matrix against a real host-native RuneLite/Micro6 client:
# explicit selection, attachment, bounded capture, manual recording, debugger
# live view, bounded input, release and clean close.
#
# Every result is printed as a stable FEDORA03_CHECK line and, with --evidence,
# written through the fail-closed record module in
# scripts/qualification/fedora03_evidence.py. A case that cannot run is recorded
# NOT_RUN with the exact missing prerequisite; the release gate refuses an
# incomplete matrix.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"
# shellcheck source=lib/preflight.sh
source "$SCRIPT_DIR/lib/preflight.sh"

package=""
native_pair_dir=""
evidence=""
host_id=""
revision=""
work_root=""
keep_work=false
app_timeout="${FEDORA03_APP_TIMEOUT_SECONDS:-300}"
target_pid="${FEDORA03_TARGET_PID:-}"

prefix=""
bin_dir=""
log=""
recordings_dir=""
captures_dir=""
runtime_port=""
runtime_pid=""
runtime_started=false
postgres_name=""
postgres_port=""
session_open=false
selected_window_id=""
selected_pid=""
selected_client="NOT_RECORDED"
recording_id=""
recording_file=""
debug_session_id=""
declare -a ran_checks=()
failures=0

usage() {
    cat <<'EOF'
Usage: certify-live-recording-debugger.sh --package <tarball> --native-pair-dir <dir> [options]

Options:
  --package <tarball>        Distribution tarball under test (required).
  --native-pair-dir <dir>    Directory holding libRemoteInput.so and
                             remoteinput-manifest.json (required).
  --evidence <file>          Record results into this JSON record.
  --host-id <id>             Stable non-sensitive host label (default: short hostname).
  --revision <sha>           Application revision (default: git HEAD of the checkout).
  --target-pid <pid>         Explicit client pid; required when several are eligible.
  --work-root <dir>          Scratch root (default: mktemp under /tmp).
  --app-timeout <seconds>    Packaged runtime readiness timeout (default: 300).
  --keep-work                Keep the scratch directory (useful when a check fails).
  -h, --help                 Show this help.

Exit codes: 0 all executed checks passed, 2 a check failed or was refused, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --package) package="${2:-}"; shift 2 ;;
        --native-pair-dir) native_pair_dir="${2:-}"; shift 2 ;;
        --evidence) evidence="${2:-}"; shift 2 ;;
        --host-id) host_id="${2:-}"; shift 2 ;;
        --revision) revision="${2:-}"; shift 2 ;;
        --target-pid) target_pid="${2:-}"; shift 2 ;;
        --work-root) work_root="${2:-}"; shift 2 ;;
        --app-timeout) app_timeout="${2:-}"; shift 2 ;;
        --keep-work) keep_work=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

if [[ -z "$package" || ! -r "$package" ]]; then
    fedorapkg_fail PACKAGE_MISSING "a readable --package tarball is required"
    exit 2
fi
if [[ -z "$native_pair_dir" || ! -r "$native_pair_dir/libRemoteInput.so" \
        || ! -r "$native_pair_dir/remoteinput-manifest.json" ]]; then
    fedorapkg_fail NATIVE_PAIR_MISSING "a --native-pair-dir with libRemoteInput.so and remoteinput-manifest.json is required"
    exit 2
fi
if [[ "$(id -u)" == "0" ]]; then
    fedorapkg_fail NOT_UNPRIVILEGED "live qualification must run as the unprivileged desktop user, not root"
    exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
    fedorapkg_fail TOOL_MISSING "jq is required to parse qualification API responses"
    exit 2
fi
if ! python3 -B "$PROJECT_DIR/scripts/native/remoteinput_metadata.py" verify-manifest \
        --manifest "$native_pair_dir/remoteinput-manifest.json" \
        --artifact "$native_pair_dir/libRemoteInput.so" >/dev/null 2>&1; then
    fedorapkg_fail NATIVE_PAIR_INVALID "the supplied native pair fails manifest verification"
    exit 2
fi

if [[ -z "$revision" ]]; then
    revision="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
fi
if [[ -z "$host_id" ]]; then
    host_id="$(hostname -s 2>/dev/null || echo fedora-host)"
    host_id="${host_id//[^A-Za-z0-9._-]/-}"
fi
if [[ -z "$work_root" ]]; then
    work_root="$(mktemp -d "${TMPDIR:-/tmp}/fedora03-live.XXXXXX")"
fi
prefix="$work_root/prefix"
bin_dir="$work_root/bin"
log="$work_root/runtime.log"
recordings_dir="$work_root/recordings"
captures_dir="$work_root/captures"
mkdir -p "$prefix" "$bin_dir" "$recordings_dir" "$captures_dir"

# ---------------------------------------------------------------------------
# Evidence plumbing
# ---------------------------------------------------------------------------

record_version() {
    local name="$1" value="$2"
    [[ -n "$evidence" && -n "$value" ]] || return 0
    python3 -B "$PROJECT_DIR/scripts/qualification/fedora03_evidence.py" version \
        --file "$evidence" --name "$name" --value "$value" >/dev/null
}

record() {
    local name="$1" status="$2" evidence_text="$3"
    ran_checks+=("$name")
    printf 'FEDORA03_CHECK name=%s status=%s evidence=%s\n' "$name" "$status" "$evidence_text"
    if [[ "$status" == "FAIL" ]]; then
        failures=$((failures + 1))
    fi
    if [[ -n "$evidence" ]]; then
        python3 -B "$PROJECT_DIR/scripts/qualification/fedora03_evidence.py" record \
            --file "$evidence" --check "$name" --status "$status" --evidence "$evidence_text"
    fi
}

skip_remaining() {
    local reason="$1"
    shift
    local name
    for name in "$@"; do
        if [[ " ${ran_checks[*]} " != *" $name "* ]]; then
            record "$name" NOT_RUN "$reason"
        fi
    done
}

all_live_checks() {
    printf '%s\n' \
        liveTargetDiscovery liveExplicitSelection qualificationAttach qualificationCapture \
        recordingStartStop recordingArtifact recordingSessionFinalized recordingDownload \
        recordingSourceBinding debuggerEndpoint debuggerViewportFrames debuggerSourceBinding \
        debuggerTickSession debuggerRecordingLink sessionExportVideo boundedInput inputRelease \
        cleanClose runtimeRestart noResidue
}

# ---------------------------------------------------------------------------
# Runtime lifecycle
# ---------------------------------------------------------------------------

stop_postgres() {
    if [[ -n "$postgres_name" ]]; then
        podman rm -f "$postgres_name" >/dev/null 2>&1 || true
        postgres_name=""
    fi
}

stop_runtime() {
    if [[ -n "$runtime_pid" ]] && kill -0 "$runtime_pid" 2>/dev/null; then
        kill -TERM -- "-$runtime_pid" 2>/dev/null || kill -TERM "$runtime_pid" 2>/dev/null || true
        local deadline=$((SECONDS + 30))
        while kill -0 "$runtime_pid" 2>/dev/null && (( SECONDS < deadline )); do
            sleep 1
        done
        kill -KILL -- "-$runtime_pid" 2>/dev/null || true
    fi
    runtime_pid=""
    runtime_started=false
    session_open=false
}

cleanup() {
    local status=$?
    if [[ "$session_open" == true ]] && [[ -n "$runtime_port" ]]; then
        curl -s -o /dev/null -X POST --max-time 5 \
            "http://127.0.0.1:$runtime_port/api/qualification/session/close" 2>/dev/null || true
    fi
    stop_runtime
    stop_postgres
    if [[ "$keep_work" != true ]]; then
        rm -rf "$work_root"
    else
        printf 'FEDORA03_NOTE kept work root %s\n' "$work_root"
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

start_postgres() {
    if ! command -v podman >/dev/null 2>&1; then
        return 1
    fi
    postgres_name="fedora03-pg-$$"
    postgres_port="$(( 22000 + RANDOM % 2000 ))"
    if ! podman run -d --rm --name "$postgres_name" \
            -p "127.0.0.1:$postgres_port:5432" \
            -e POSTGRES_USER=chromascape -e POSTGRES_PASSWORD=chromascape123 \
            -e POSTGRES_DB=chromascape postgres:16-alpine >/dev/null 2>&1; then
        postgres_name=""
        return 1
    fi
    local deadline=$((SECONDS + 60))
    while (( SECONDS < deadline )); do
        if podman exec "$postgres_name" pg_isready -U chromascape >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    stop_postgres
    return 1
}

start_runtime() {
    runtime_port="$(( 19000 + RANDOM % 1000 ))"
    env \
        SERVER_ADDRESS=127.0.0.1 \
        SERVER_PORT="$runtime_port" \
        SPRING_PROFILES_ACTIVE=local-development \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$postgres_port/chromascape?sslmode=disable" \
        SPRING_DATASOURCE_USERNAME=chromascape \
        SPRING_DATASOURCE_PASSWORD=chromascape123 \
        CHROMASCAPE_QUALIFICATION_ENABLED=true \
        CHROMASCAPE_QUALIFICATION_CAPTURE_DIR="$captures_dir" \
        CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=true \
        CHROMASCAPE_REMOTEINPUT_APPLICATION_ROOT="$prefix/current" \
        RECORDING_OUTPUT_DIR="$recordings_dir" \
        ANALYSIS_MCP_AUTO_START=false \
        STABILIZATION_AUTH_TOKEN=certification-read-token \
        STABILIZATION_AUTH_WRITE_TOKEN=certification-write-token \
        CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64="MCowBQYDK2VwAyEAEiSjIlIP5v4JrCJoFPRhA6/lti3jb/bm+9cpyqEi7Oo=" \
        CHROMASCAPE_SOURCE_PUBLIC_KEY_ID=certification-source-key \
        CHROMASCAPE_SOURCE_ENVIRONMENT=certification \
        CHROMASCAPE_SOURCE_ACCESS_METADATA_TOKEN=certification-metadata-token \
        CHROMASCAPE_SOURCE_ACCESS_CONTENT_TOKEN=certification-content-token \
        MICRO6_SOURCE_TRANSPORT_BASE_URL=http://127.0.0.1:8098 \
        MICRO6_SOURCE_TRANSPORT_AUTH_TOKEN=certification-transport-token \
        MICRO6_SOURCE_TRANSPORT_ACTOR=certification-host \
        LEEUWBOT_ENVIRONMENT=certification \
        setsid "$prefix/current/launcher" > "$log" 2>&1 &
    runtime_pid=$!
    local deadline=$((SECONDS + app_timeout))
    while (( SECONDS < deadline )); do
        if ! kill -0 "$runtime_pid" 2>/dev/null; then
            runtime_pid=""
            return 1
        fi
        if curl -sf --max-time 3 "http://127.0.0.1:$runtime_port/debugger" >/dev/null 2>&1; then
            runtime_started=true
            return 0
        fi
        sleep 2
    done
    return 1
}

api_url() { printf 'http://127.0.0.1:%s%s' "$runtime_port" "$1"; }

api_get() {
    curl -sS --max-time 20 -H 'Accept: application/json' "$(api_url "$1")"
}

api_post() {
    local path="$1" body="${2:-}"
    if [[ -n "$body" ]]; then
        curl -sS --max-time 30 -X POST -H 'Content-Type: application/json' -d "$body" "$(api_url "$path")"
    else
        curl -sS --max-time 30 -X POST "$(api_url "$path")"
    fi
}

api_code() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$(api_url "$1")"
}

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

check_debugger_endpoint() {
    local code
    code="$(api_code /debugger)"
    if [[ "$code" != "200" ]]; then
        record debuggerEndpoint FAIL "/debugger returned HTTP $code on the installed tree"
        return 1
    fi
    record debuggerEndpoint PASS "/debugger returned HTTP 200 on the installed tree"
    return 0
}

check_target_discovery() {
    local micro6_dir="${BUILDER_GOAL_MICRO6_PROJECT_DIR:-}" candidates count pids
    if [[ -z "$micro6_dir" || ! -d "$micro6_dir" ]]; then
        record liveTargetDiscovery NOT_RUN "BUILDER_GOAL_MICRO6_PROJECT_DIR is unset; no Micro6 root to scope discovery"
        return 1
    fi
    candidates="$(api_get /api/windows 2>/dev/null || echo '[]')"
    count="$(jq 'length' <<<"$candidates" 2>/dev/null || echo 0)"
    if [[ "$count" == "0" || "$count" == "null" ]]; then
        record liveTargetDiscovery NOT_RUN "no eligible RuneLite/Micro6 window is visible to the packaged runtime"
        return 1
    fi
    pids="$(jq -r '[.[].pid] | unique | map(tostring) | join(",")' <<<"$candidates")"
    record liveTargetDiscovery PASS "packaged runtime discovered $count candidate window(s); pids=$pids"
    return 0
}

check_explicit_selection() {
    local candidates candidate_count choice response
    candidates="$(api_get /api/windows 2>/dev/null || echo '[]')"
    candidate_count="$(jq 'length' <<<"$candidates" 2>/dev/null || echo 0)"
    if [[ -n "$target_pid" ]]; then
        choice="$(jq -r --arg pid "$target_pid" '.[] | select((.pid|tostring) == $pid) | .id' <<<"$candidates" | head -n 1)"
        if [[ -z "$choice" ]]; then
            record liveExplicitSelection FAIL "explicit --target-pid $target_pid is not an eligible visible candidate; refusing to fall back"
            return 1
        fi
    elif (( candidate_count == 1 )); then
        choice="$(jq -r '.[0].id' <<<"$candidates")"
    elif (( candidate_count == 0 )); then
        record liveExplicitSelection NOT_RUN "no candidate window is available to select"
        return 1
    else
        record liveExplicitSelection FAIL "multiple eligible clients are present and --target-pid was not supplied; refusing automatic selection"
        return 1
    fi
    response="$(api_post /api/windows/select "$(jq -cn --arg id "$choice" '{id:$id}')")"
    if [[ "$(jq -r '.status // empty' <<<"$response")" != "selected" ]]; then
        record liveExplicitSelection FAIL "window selection API did not confirm selection: $response"
        return 1
    fi
    selected_window_id="$choice"
    selected_pid="$(jq -r --arg id "$choice" '.[] | select(.id == $id) | .pid' <<<"$candidates" | head -n 1)"
    local cmdline client_root
    cmdline="$(tr '\0' ' ' < "/proc/$selected_pid/cmdline" 2>/dev/null || true)"
    client_root="$(tr ' ' '\n' <<<"$cmdline" | grep -m 1 -E '/runelite-client/(build/classes|src)/' || true)"
    if [[ -n "$client_root" ]]; then
        client_root="${client_root%%/runelite-client/*}"
        selected_client="net.runelite.client.RuneLite@$(basename "$client_root")"
    else
        selected_client="net.runelite.client.RuneLite@pid-$selected_pid"
    fi
    record liveExplicitSelection PASS "selected window $choice for pid $selected_pid ($selected_client)"
    return 0
}

check_qualification_attach() {
    local response pid start_time boot_id
    response="$(api_post /api/qualification/session/open)"
    if [[ "$(jq -r '.sessionOpen // false' <<<"$response" 2>/dev/null)" != "true" ]]; then
        if [[ "$(jq -r '.error // empty' <<<"$response" 2>/dev/null)" == "SESSION_ALREADY_OPEN" ]]; then
            response="$(api_get /api/qualification/session)"
        else
            record qualificationAttach FAIL "qualification session did not open: $response"
            return 1
        fi
    fi
    if [[ "$(jq -r '.sessionOpen // false' <<<"$response" 2>/dev/null)" != "true" ]]; then
        record qualificationAttach FAIL "qualification session reports not open: $response"
        return 1
    fi
    session_open=true
    pid="$(jq -r '.pid // empty' <<<"$response")"
    start_time="$(jq -r '.processStartTime // empty' <<<"$response")"
    boot_id="$(jq -r '.bootIdPresent // false' <<<"$response")"
    if [[ -n "$selected_pid" && "$pid" != "$selected_pid" ]]; then
        record qualificationAttach FAIL "attached pid $pid does not match selected pid $selected_pid"
        return 1
    fi
    if [[ -z "$start_time" || "$boot_id" != "true" ]]; then
        record qualificationAttach FAIL "session identity fence is incomplete (startTime=${start_time:-missing}, bootId=$boot_id)"
        return 1
    fi
    record qualificationAttach PASS "attached selected pid=$pid with process start time and boot id fence"
    return 0
}

check_qualification_capture() {
    local response width height non_uniform png
    response="$(api_post /api/qualification/capture)"
    width="$(jq -r '.width // 0' <<<"$response" 2>/dev/null || echo 0)"
    height="$(jq -r '.height // 0' <<<"$response" 2>/dev/null || echo 0)"
    non_uniform="$(jq -r '.hasNonUniformPixels // false' <<<"$response" 2>/dev/null || echo false)"
    png="$(jq -r '.pngFile // empty' <<<"$response" 2>/dev/null || true)"
    if (( width <= 0 || height <= 0 )); then
        record qualificationCapture FAIL "bounded capture returned no usable frame: $response"
        return 1
    fi
    if [[ "$non_uniform" != "true" ]]; then
        local content_sha
        content_sha="$(jq -r '.contentSha256 // "unknown"' <<<"$response" 2>/dev/null || echo unknown)"
        record qualificationCapture FAIL "captured ${width}x${height} frame is uniform; target frame source is not producing rendered pixels (png=$png sha256=$content_sha)"
        return 1
    fi
    if [[ -z "$png" || ! -s "$captures_dir/$png" ]]; then
        record qualificationCapture FAIL "capture reported pixels but no PNG was written: $response"
        return 1
    fi
    record qualificationCapture PASS "captured ${width}x${height} rendered frame; png=$png"
    return 0
}

check_recording() {
    local start response metrics frames mismatch binding stop file session_status download_dir downloaded probed video_line duration_line video_dims
    start="$(api_post /api/recordings/start '{"accountName":"fedora03","scriptName":"fedora03-live-qualification"}')"
    if [[ "$(jq -r '.status // empty' <<<"$start")" != "recording" ]]; then
        record recordingStartStop FAIL "recording start failed: $start"
        return 1
    fi
    recording_id="$(jq -r '.recordingId // empty' <<<"$start")"
    local deadline=$((SECONDS + 45))
    frames=0
    metrics='{}'
    while (( SECONDS < deadline )); do
        metrics="$(api_get /api/recordings/metrics)"
        frames="$(jq -r '.framesRecorded // 0' <<<"$metrics" 2>/dev/null || echo 0)"
        if (( frames > 0 )); then
            break
        fi
        sleep 2
    done
    mismatch="$(jq -r '.sourceMismatchFrames // 0' <<<"$metrics" 2>/dev/null || echo 0)"
    binding="$(jq -r '.sourceBinding // "null"' <<<"$metrics" 2>/dev/null || echo null)"
    if [[ "$(jq -r '.isRecording // false' <<<"$metrics" 2>/dev/null)" != "true" ]]; then
        record recordingStartStop FAIL "recording reported started but metrics do not show an active recording: $metrics"
        return 1
    fi
    stop="$(api_post /api/recordings/stop)"
    if [[ "$(jq -r '.status // empty' <<<"$stop")" != "stopped" ]]; then
        record recordingStartStop FAIL "recording stop failed: $stop"
        return 1
    fi
    recording_id="$(jq -r '.recordingId // empty' <<<"$stop")"
    recording_file="$(jq -r '.filePath // empty' <<<"$stop")"
    local observed_frames
    observed_frames="$(jq -r '.frameCount // 0' <<<"$stop")"
    if [[ -z "$recording_id" || -z "$recording_file" ]]; then
        record recordingStartStop FAIL "stop response carried no recording identity or path: $stop"
        return 1
    fi
    record recordingStartStop PASS "start/stop cycle produced $recording_id with $observed_frames frame(s)"
    if [[ "$mismatch" != "0" ]]; then
        record recordingSourceBinding FAIL "recording observed $mismatch source-mismatch frame(s) (binding=$binding)"
    else
        record recordingSourceBinding PASS "recorded source binding=$binding; sourceMismatchFrames=0 over $frames frame(s)"
    fi
    file="$recording_file"
    [[ -f "$file" ]] || file="$recordings_dir/$(basename "$recording_file")"
    if [[ ! -s "$file" ]]; then
        record recordingArtifact FAIL "recorded MP4 is missing or empty at $recording_file"
        return 1
    fi
    probed="$(ffmpeg -hide_banner -i "$file" 2>&1 || true)"
    video_line="$(grep -m 1 'Stream #.*Video:' <<<"$probed" || true)"
    duration_line="$(grep -m 1 'Duration:' <<<"$probed" || true)"
    video_dims="$(grep -oE '[0-9]{2,5}x[0-9]{2,5}' <<<"$video_line" | head -n 1)"
    if [[ -z "$video_line" ]]; then
        record recordingArtifact FAIL "recorded file $(basename "$file") carries no video stream"
        return 1
    fi
    if [[ -z "$video_dims" ]]; then
        record recordingArtifact FAIL "recorded video stream reports no frame dimensions: $video_line"
        return 1
    fi
    if [[ "$duration_line" == *"Duration: 00:00:00.0"* || "$duration_line" == *"Duration: N/A"* ]]; then
        record recordingArtifact FAIL "recorded video carries no measurable duration: $duration_line"
        return 1
    fi
    # The host ffmpeg on Fedora 44 ships only libopenh264, which cannot decode the
    # libopenh264-encoded stream; the container metadata is therefore the strongest
    # decoder-independent observation available and is recorded verbatim.
    record recordingArtifact PASS "MP4 $(basename "$file") carries a video stream ($video_dims) with $duration_line"
    session_status="$(api_get /api/recordings/sessions \
        | jq -r --arg id "$recording_id" '.[] | select(.recordingId == $id) | .status' | head -n 1)"
    if [[ "$session_status" != "FINALIZED" ]]; then
        record recordingSessionFinalized FAIL "recording_sessions row for $recording_id is ${session_status:-absent}"
        return 1
    fi
    record recordingSessionFinalized PASS "recording_sessions row for $recording_id is FINALIZED"
    download_dir="$(mktemp -d "$work_root/download.XXXXXX")"
    downloaded="$download_dir/$(basename "$file")"
    if ! curl -sS --max-time 60 -o "$downloaded" "$(api_url "/api/recordings/download/$(basename "$file")")"; then
        record recordingDownload FAIL "download endpoint refused $(basename "$file")"
        return 1
    fi
    if [[ "$(fedorapkg_sha256 "$downloaded")" != "$(fedorapkg_sha256 "$file")" ]]; then
        record recordingDownload FAIL "downloaded MP4 differs from the recorded artifact"
        return 1
    fi
    record recordingDownload PASS "download endpoint returned a byte-identical MP4 ($(fedorapkg_sha256 "$file"))"
    return 0
}
check_debugger_viewport() {
    local pipeline binding probe probe_frames probe_bytes avg_bytes probe_file probe_pid probe_status
    # The viewport pipeline only runs while a subscriber is connected: the binding is cleared
    # when the last subscriber leaves. Sample the pipeline while the probe holds the session
    # open, not after it closes.
    probe_file="$(mktemp "$work_root/ws-probe.XXXXXX")"
    python3 -B "$PROJECT_DIR/scripts/qualification/ws_probe.py" \
        --url "ws://127.0.0.1:$runtime_port/ws/viewport" --seconds 8 --require-binary 1 \
        > "$probe_file" 2>&1 &
    probe_pid=$!
    sleep 3
    pipeline="$(api_get /api/debug/viewport/pipeline)"
    binding="$(jq -r '.capture.sourceBinding // "null"' <<<"$pipeline" 2>/dev/null || echo null)"
    wait "$probe_pid" || true
    probe="$(cat "$probe_file")"
    if [[ "$binding" != "null" && -n "$selected_pid" && "$binding" == *"$selected_pid"* ]]; then
        record debuggerSourceBinding PASS "viewport pipeline source binding=$binding matches selected pid $selected_pid while streaming"
    else
        record debuggerSourceBinding FAIL "viewport pipeline source binding=$binding does not match selected pid ${selected_pid:-none} while streaming"
    fi
    probe_frames="$(sed -n 's/.*"binaryFrames": \([0-9]*\).*/\1/p' <<<"$probe" | head -n 1)"
    probe_bytes="$(sed -n 's/.*"binaryBytes": \([0-9]*\).*/\1/p' <<<"$probe" | head -n 1)"
    if [[ -z "$probe_frames" || "$probe_frames" == "0" ]]; then
        record debuggerViewportFrames FAIL "no binary frames arrived on /ws/viewport within the bounded window: $probe"
    else
        avg_bytes="$(( probe_bytes / probe_frames ))"
        record debuggerViewportFrames PASS "/ws/viewport streamed $probe_frames binary frame(s) in 8 s (${probe_bytes} bytes total, ${avg_bytes} bytes/frame average)"
    fi
    return 0
}

check_debugger_tick_session() {
    local active link export_dir export_file
    active="$(api_get /api/debug/sessions/active)"
    if [[ -z "$active" || "$active" == "null" || "$active" == "[]" ]]; then
        record debuggerTickSession NOT_RUN "client is attached but has not opened a debugger tick session (no Micro6 SESSION_START)"
        record debuggerRecordingLink NOT_RUN "no debugger tick session to link a recording to"
        record sessionExportVideo NOT_RUN "no debugger tick session to export"
        return 0
    fi
    debug_session_id="$(jq -r 'if type == "array" then (.[0].sessionId // .[0].id) else (.sessionId // .id) end' <<<"$active" 2>/dev/null || true)"
    if [[ -z "$debug_session_id" || "$debug_session_id" == "null" ]]; then
        record debuggerTickSession NOT_RUN "debugger session endpoint returned no usable session identity"
        record debuggerRecordingLink NOT_RUN "no debugger tick session to link a recording to"
        record sessionExportVideo NOT_RUN "no debugger tick session to export"
        return 0
    fi
    record debuggerTickSession PASS "debugger tick session $debug_session_id is active for the attached client"
    link="$(api_get "/api/debug/replay/recording-by-session?sessionId=$debug_session_id")"
    if [[ "$(jq -r '.recordingId // empty' <<<"$link" 2>/dev/null)" == "$recording_id" ]]; then
        record debuggerRecordingLink PASS "session $debug_session_id resolves recording $recording_id without repair"
    elif [[ -z "$(jq -r '.recordingId // empty' <<<"$link" 2>/dev/null)" ]]; then
        record debuggerRecordingLink NOT_RUN "tick session $debug_session_id has no recording link (recording was started through /api/recordings, not the debug replay path)"
    else
        record debuggerRecordingLink FAIL "tick session resolves a different recording: $link"
    fi
    export_dir="$(mktemp -d "$work_root/export.XXXXXX")"
    export_file="$export_dir/session.zip"
    if curl -sS --max-time 90 -X POST -o "$export_file" "$(api_url "/api/debug/export/$debug_session_id")" \
            && [[ -s "$export_file" ]] && unzip -l "$export_file" 2>/dev/null | grep -q 'video.mp4'; then
        record sessionExportVideo PASS "session export $debug_session_id contains video.mp4"
    else
        record sessionExportVideo NOT_RUN "session export for $debug_session_id carried no video.mp4 (no linked recording for this session)"
    fi
    return 0
}

check_input_and_close() {
    local session bounds center_x center_y move_response key_response held close final
    session="$(api_get /api/qualification/session)"
    bounds="$(jq -c '.targetBounds // empty' <<<"$session" 2>/dev/null || true)"
    if [[ -z "$bounds" || "$bounds" == "null" ]]; then
        record boundedInput FAIL "qualification session reports no target bounds"
        record inputRelease NOT_RUN "no attached session to verify held input"
        record cleanClose NOT_RUN "no attached session to close"
        return 1
    fi
    center_x="$(jq -r '((.x // 0) + (.width // 0) / 2) | floor' <<<"$bounds")"
    center_y="$(jq -r '((.y // 0) + (.height // 0) / 2) | floor' <<<"$bounds")"
    move_response="$(api_post /api/qualification/mouse/move "$(jq -cn --argjson x "$center_x" --argjson y "$center_y" '{x:$x,y:$y}')")"
    if [[ "$(jq -r '.status // empty' <<<"$move_response")" != "moved" ]]; then
        record boundedInput FAIL "bounded mouse move was rejected: $move_response"
        record inputRelease NOT_RUN "bounded input did not run"
        record cleanClose NOT_RUN "bounded input did not run"
        return 1
    fi
    api_post /api/qualification/mouse/click '{"button":"LEFT"}' >/dev/null
    key_response="$(api_post /api/qualification/key/press '{"keyCode":16,"holdMillis":120}')"
    if [[ "$(jq -r '.status // empty' <<<"$key_response")" != "pressed" ]]; then
        record boundedInput FAIL "bounded key press was rejected: $key_response"
        record inputRelease NOT_RUN "bounded input did not complete"
        record cleanClose NOT_RUN "bounded input did not complete"
        return 1
    fi
    record boundedInput PASS "bounded mouse move/click and 120 ms SHIFT press executed inside target bounds"
    held="$(api_get '/api/qualification/input/held?keyCode=16&button=LEFT')"
    if [[ "$(jq -r '.keyHeld // false' <<<"$held" 2>/dev/null)" == "true" \
            || "$(jq -r '.mouseHeld // false' <<<"$held" 2>/dev/null)" == "true" ]]; then
        record inputRelease FAIL "held input remains after release: $held"
    else
        record inputRelease PASS "no held key or button remains after bounded input"
    fi
    close="$(api_post /api/qualification/session/close)"
    if grep -q 'SESSION_POISONED' <<<"$close"; then
        record cleanClose FAIL "close reported SESSION_POISONED (restart required)"
        return 1
    fi
    final="$(api_get /api/qualification/session)"
    if [[ "$(jq -r '.sessionOpen // false' <<<"$final")" == "true" ]]; then
        record cleanClose FAIL "session still reports open after close: $final"
        return 1
    fi
    if [[ "$(jq -r '.restartRequired // false' <<<"$final")" == "true" ]]; then
        record cleanClose FAIL "close left a poisoned identity requiring restart"
        return 1
    fi
    session_open=false
    record cleanClose PASS "session closed cleanly; identity not poisoned"
    return 0
}

check_runtime_restart() {
    stop_runtime
    if ! start_runtime; then
        record runtimeRestart FAIL "packaged runtime did not come back after restart; see $log"
        return 1
    fi
    local code
    code="$(api_code /debugger)"
    if [[ "$code" != "200" ]]; then
        record runtimeRestart FAIL "restarted runtime served HTTP $code on /debugger"
        return 1
    fi
    record runtimeRestart PASS "stop/start of the packaged runtime re-served /debugger"
    return 0
}

check_no_residue() {
    local residue=""
    # The check owns its own teardown so the observation is about what this run left behind,
    # not about processes that were still intentionally running when it was measured.
    stop_runtime
    stop_postgres
    bash "$SCRIPT_DIR/uninstall-tarball.sh" --prefix "$prefix" --bin-dir "$bin_dir" --name chromascape >/dev/null 2>&1 || true
    if pgrep -f -- "$prefix/current" >/dev/null 2>&1; then
        residue="a packaged ChromaScape JVM remains"
    fi
    if [[ -n "$postgres_port" ]] && ss -H -ltn "sport = :$postgres_port" 2>/dev/null | grep -q .; then
        residue="${residue:+$residue, }disposable Postgres port $postgres_port is still listening"
    fi
    if [[ -e "$prefix/current" || -e "$bin_dir/chromascape" ]]; then
        residue="${residue:+$residue, }install path still present"
    fi
    if [[ -n "$residue" ]]; then
        record noResidue FAIL "residue observed: $residue"
        return 1
    fi
    record noResidue PASS "no managed process, container port, or install path remains"
    return 0
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    if [[ -n "$evidence" ]]; then
        python3 -B "$PROJECT_DIR/scripts/qualification/fedora03_evidence.py" init \
            --host-id "$host_id" --revision "$revision" --output "$evidence" --force >/dev/null
    fi
    local manifest_tmp manifest_file
    manifest_tmp="$(mktemp -d "$work_root/manifest.XXXXXX")"
    tar -xzf "$package" -C "$manifest_tmp" --wildcards '*/PACKAGE-MANIFEST.json' 2>/dev/null || true
    manifest_file="$(find "$manifest_tmp" -name PACKAGE-MANIFEST.json | head -n 1 || true)"
    record_version os "$(sed -n 's/^PRETTY_NAME="\(.*\)"$/\1/p' /etc/os-release | head -n 1)"
    record_version arch "$(uname -m)"
    record_version java "$(java -version 2>&1 | head -n 1)"
    record_version packageRevision "$revision"
    record_version packageSha256 "$(fedorapkg_sha256 "$package")"
    if [[ -n "$manifest_file" ]]; then
        record_version packageManifestSha256 "$(fedorapkg_sha256 "$manifest_file")"
    fi
    local lib_sha manifest_sha pair_identity
    lib_sha="$(fedorapkg_sha256 "$native_pair_dir/libRemoteInput.so")"
    manifest_sha="$(fedorapkg_sha256 "$native_pair_dir/remoteinput-manifest.json")"
    pair_identity="$(python3 -c 'import hashlib,sys;print(hashlib.sha256((sys.argv[1] + "\0" + sys.argv[2]).encode("ascii")).hexdigest())' "$lib_sha" "$manifest_sha")"
    record_version nativePairSha256 "$pair_identity"
    record_version nativeLibrarySha256 "$lib_sha"
    record_version nativeManifestSha256 "$manifest_sha"
    record_version nativePatchIdentity "$(jq -r '.localPatchIdentity' "$native_pair_dir/remoteinput-manifest.json")"

    if ! bash "$SCRIPT_DIR/install-tarball.sh" --tarball "$package" --prefix "$prefix" \
            --bin-dir "$bin_dir" --name chromascape >/dev/null 2>&1; then
        skip_remaining "packaged install failed; live matrix not started" \
            $(all_live_checks)
        return 1
    fi
    local native_dir="$prefix/current/native/linux-x86_64"
    mkdir -p "$native_dir"
    cp "$native_pair_dir/libRemoteInput.so" "$native_dir/libRemoteInput.so"
    cp "$native_pair_dir/remoteinput-manifest.json" "$native_dir/remoteinput-manifest.json"
    chmod 0644 "$native_dir/libRemoteInput.so" "$native_dir/remoteinput-manifest.json"

    if ! start_postgres; then
        skip_remaining "no disposable Postgres (podman/image unavailable); live runtime not started" \
            $(all_live_checks)
        return 1
    fi
    if ! start_runtime; then
        skip_remaining "packaged runtime failed to start; see $log" \
            $(all_live_checks)
        return 1
    fi

    check_debugger_endpoint || true
    check_target_discovery || true
    if check_explicit_selection; then
        if check_qualification_attach; then
            check_qualification_capture || true
            check_recording || true
            check_debugger_viewport || true
            check_debugger_tick_session || true
            check_input_and_close || true
        else
            skip_remaining "attachment did not open a session" \
                qualificationCapture recordingStartStop recordingArtifact recordingSessionFinalized \
                recordingDownload recordingSourceBinding debuggerViewportFrames debuggerSourceBinding \
                debuggerTickSession debuggerRecordingLink sessionExportVideo boundedInput inputRelease \
                cleanClose
        fi
    else
        skip_remaining "explicit target selection did not complete" \
            qualificationAttach qualificationCapture recordingStartStop recordingArtifact \
            recordingSessionFinalized recordingDownload recordingSourceBinding debuggerViewportFrames \
            debuggerSourceBinding debuggerTickSession debuggerRecordingLink sessionExportVideo \
            boundedInput inputRelease cleanClose
    fi
    record_version client "$selected_client"
    check_runtime_restart || true
    check_no_residue || true

    if [[ -n "$evidence" ]]; then
        python3 -B "$PROJECT_DIR/scripts/qualification/fedora03_evidence.py" verify "$evidence" || true
    fi
    if (( failures > 0 )); then
        printf 'FEDORA03 level=FAIL code=QUALIFICATION failures=%d\n' "$failures" >&2
        return 1
    fi
    printf 'FEDORA03 level=PASS code=QUALIFICATION checks=%d\n' "${#ran_checks[@]}"
    return 0
}

main "$@"
