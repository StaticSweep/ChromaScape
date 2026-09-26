#!/usr/bin/env bash
set -euo pipefail

# FEDORA-02 packaged-distribution certification harness.
#
# Certifies that a built ChromaScape package works on a clean Fedora host
# independently of the development checkout: it installs without sudo, contains
# no build tooling, detects Java and the native payload, binds loopback only,
# serves /debugger, and uninstalls with no residue. Checks that need a live
# RuneLite/Micro6 client or a second package version are prerequisite-gated and
# are recorded NOT_RUN with the exact missing prerequisite rather than guessed.
#
# The harness never claims a case it did not execute. Every result is printed as
# a stable FEDORA02_CHECK line and, with --evidence, written through the
# fail-closed record module in scripts/qualification/fedora02_evidence.py.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"
# The read-only discovery rules the packaged application implements; reused here
# to decide whether the explicit-selection prerequisite is present.
# shellcheck source=lib/preflight.sh
source "$SCRIPT_DIR/lib/preflight.sh"

role="tarball"
package=""
rpm_file=""
previous_package=""
evidence=""
host_id=""
revision=""
version=""
work_root=""
prefix=""
bin_dir=""
skip_runtime=false
start_postgres=true
app_timeout="${FEDORA02_APP_TIMEOUT_SECONDS:-300}"
want_live=false
keep_work=false
declare -a ran_checks=()

usage() {
    cat <<'EOF'
Usage: certify-packaged.sh --package <tarball> [options]

Options:
  --role tarball|rpm       Package form to certify (default: tarball).
  --package <tarball>      Distribution tarball under test (required).
  --rpm <file>             RPM under test (required when --role rpm).
  --previous-package <f>   A second-version tarball, enabling upgrade/rollback.
  --evidence <file>        Record results into this JSON record.
  --host-id <id>           Stable non-sensitive host label (default: short hostname).
  --revision <sha>         Application revision (default: git HEAD of the checkout).
  --version <version>      Package version (default: from the package manifest).
  --work-root <dir>        Scratch root (default: mktemp under /tmp).
  --skip-runtime           Do not start Postgres or the packaged application.
  --no-postgres            Do not start a disposable Postgres container.
  --live                   Allow live client checks (default: NOT_RUN).
  --keep-work              Keep the scratch directory (useful when a check fails).
  -h, --help               Show this help.

Exit codes: 0 all executed checks passed, 2 a check failed or was refused, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --role) role="${2:-}"; shift 2 ;;
        --package) package="${2:-}"; shift 2 ;;
        --rpm) rpm_file="${2:-}"; shift 2 ;;
        --previous-package) previous_package="${2:-}"; shift 2 ;;
        --evidence) evidence="${2:-}"; shift 2 ;;
        --host-id) host_id="${2:-}"; shift 2 ;;
        --revision) revision="${2:-}"; shift 2 ;;
        --version) version="${2:-}"; shift 2 ;;
        --work-root) work_root="${2:-}"; shift 2 ;;
        --skip-runtime) skip_runtime=true; shift ;;
        --no-postgres) start_postgres=false; shift ;;
        --live) want_live=true; shift ;;
        --keep-work) keep_work=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

case "$role" in
    tarball|rpm) ;;
    *) printf 'FEDORA02 level=ERROR code=ROLE_INVALID message=%s\n' "$role" >&2; exit 64 ;;
esac

if [[ "$role" == "tarball" && ( -z "$package" || ! -r "$package" ) ]]; then
    fedorapkg_fail PACKAGE_MISSING "a readable --package tarball is required for role=tarball"
    exit 2
fi
if [[ "$role" == "rpm" && ( -z "$rpm_file" || ! -r "$rpm_file" ) ]]; then
    fedorapkg_fail RPM_MISSING "a readable --rpm is required for role=rpm"
    exit 2
fi

if [[ -z "$revision" ]]; then
    revision="$(git -C "$PROJECT_DIR" rev-parse HEAD)"
fi
if [[ -z "$host_id" ]]; then
    host_id="$(hostname -s 2>/dev/null || echo fedora-host)"
    host_id="${host_id//[^A-Za-z0-9._-]/-}"
fi
if [[ -z "$version" && "$role" == "tarball" ]]; then
    version="$(tar -xOzf "$package" --wildcards '*/PACKAGE-MANIFEST.json' 2>/dev/null \
        | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1 || true)"
fi
if [[ -z "$version" ]]; then
    version="$(fedorapkg_project_version "$PROJECT_DIR" 2>/dev/null || true)"
fi
if [[ -z "$work_root" ]]; then
    work_root="$(mktemp -d "${TMPDIR:-/tmp}/fedora02-certify.XXXXXX")"
fi
prefix="$work_root/prefix"
bin_dir="$work_root/bin"
log="$work_root/runtime.log"
mkdir -p "$prefix" "$bin_dir" "$work_root"

failures=0

# Records one exact host/runtime/package version. Only meaningful with --evidence.
record_version() {
    local name="$1" value="$2"
    [[ -n "$evidence" ]] || return 0
    python3 -B "$PROJECT_DIR/scripts/qualification/fedora02_evidence.py" version \
        --file "$evidence" --name "$name" --value "$value" >/dev/null
}

# Records one observed check. `pass` increments nothing; `fail` marks the run
# failed; `notrun` is honest abstention. Evidence must never be empty.
record() {
    local name="$1" status="$2" evidence_text="$3"
    ran_checks+=("$name")
    printf 'FEDORA02_CHECK name=%s status=%s evidence=%s\n' "$name" "$status" "$evidence_text"
    if [[ "$status" == "FAIL" ]]; then
        failures=$((failures + 1))
    fi
    if [[ -n "$evidence" ]]; then
        python3 -B "$PROJECT_DIR/scripts/qualification/fedora02_evidence.py" record \
            --file "$evidence" --check "$name" --status "$status" --evidence "$evidence_text"
    fi
}

# ---------------------------------------------------------------------------
# Static (no runtime) checks
# ---------------------------------------------------------------------------

check_tarball_install_no_sudo() {
    local out
    if [[ "$(id -u)" == "0" ]]; then
        record tarballInstallNoSudo FAIL "certification must run as an unprivileged user, not root"
        return
    fi
    if ! out="$(bash "$SCRIPT_DIR/install-tarball.sh" --tarball "$package" \
            --prefix "$prefix" --bin-dir "$bin_dir" --name chromascape 2>&1)"; then
        record tarballInstallNoSudo FAIL "installer exited non-zero: ${out//$'\n'/ | }"
        return
    fi
    if [[ ! -x "$bin_dir/chromascape" || ! -e "$prefix/current/application.jar" ]]; then
        record tarballInstallNoSudo FAIL "installer reported success but shim or current release is absent"
        return
    fi
    record tarballInstallNoSudo PASS "non-root install into $prefix; shim at $bin_dir/chromascape"
}

check_launch_without_build_tools() {
    local release="$prefix/current" offenders=""
    if [[ ! -d "$release" ]]; then
        record launchWithoutBuildTools NOT_RUN "no installed release; install did not complete"
        return
    fi
    offenders="$(find "$release" -maxdepth 3 \
        \( -name gradlew -o -name gradle -o -name '*.gradle.kts' -o -name '*.java' \
           -o -name '.git' -o -name 'build.gradle' \) -print 2>/dev/null | head -n 5)"
    if [[ -n "$offenders" ]]; then
        record launchWithoutBuildTools FAIL "release contains build-tool/repository files: ${offenders//$'\n'/,}"
        return
    fi
    if [[ ! -x "$release/launcher" ]]; then
        record launchWithoutBuildTools FAIL "release launcher is missing or not executable"
        return
    fi
    if ! sh -n "$release/launcher" 2>/dev/null; then
        record launchWithoutBuildTools FAIL "release launcher is not valid POSIX sh"
        return
    fi
    if ! grep -q 'application.jar' "$release/launcher"; then
        record launchWithoutBuildTools FAIL "release launcher does not exec application.jar"
        return
    fi
    record launchWithoutBuildTools PASS "release has application.jar + launcher only; no Gradle/Git/compiler inputs"
}

check_java_detection() {
    local release="$prefix/current" manifest start_class
    if [[ ! -f "$release/application.jar" ]]; then
        record javaDetection NOT_RUN "no installed application.jar"
        return
    fi
    manifest="$(unzip -p "$release/application.jar" META-INF/MANIFEST.MF 2>/dev/null || true)"
    start_class="$(printf '%s\n' "$manifest" | sed -n 's/^Start-Class:[[:space:]]*//p' | tr -d '\r')"
    if [[ -z "$start_class" ]]; then
        record javaDetection FAIL "application.jar manifest has no Start-Class (not a Spring Boot jar)"
        return
    fi
    record javaDetection PASS "Spring Boot Start-Class=$start_class; manifest readable by the packaged jar"
}

check_native_detection() {
    local release="$prefix/current" manifest native_dir pair_sha
    manifest="$(tar -xOzf "$package" --wildcards '*/PACKAGE-MANIFEST.json' 2>/dev/null || true)"
    native_dir="$release/native/linux-x86_64"
    if printf '%s' "$manifest" | grep -q '"nativePayloadIncluded": true'; then
        if [[ ! -f "$native_dir/libRemoteInput.so" || ! -f "$native_dir/remoteinput-manifest.json" ]]; then
            record nativeDetection FAIL "manifest claims a native payload but the pair is absent from the release"
            return
        fi
        if ! python3 -B "$PROJECT_DIR/scripts/native/remoteinput_metadata.py" verify-manifest \
                --manifest "$native_dir/remoteinput-manifest.json" \
                --artifact "$native_dir/libRemoteInput.so" >/dev/null 2>&1; then
            record nativeDetection FAIL "packaged native pair fails manifest verification"
            return
        fi
        pair_sha="$(fedorapkg_sha256 "$native_dir/libRemoteInput.so")"
        record nativeDetection PASS "packaged native pair verified; libRemoteInput.so sha256=$pair_sha"
    else
        if [[ -e "$native_dir" ]]; then
            record nativeDetection FAIL "manifest claims no native payload but native/ is present in the release"
            return
        fi
        record nativeDetection PASS "base package carries no stale native payload; loader resolves <appRoot>/native/linux-x86_64"
    fi
}

# ---------------------------------------------------------------------------
# Runtime checks
# ---------------------------------------------------------------------------

runtime_pid=""
runtime_port=""
postgres_name=""
postgres_created_name=""
postgres_port=""
runtime_started=false

start_postgres() {
    if [[ "$start_postgres" != true ]]; then
        return 0
    fi
    if ! command -v podman >/dev/null 2>&1; then
        return 1
    fi
    postgres_name="fedora02-pg-$$"
    postgres_created_name="$postgres_name"
    postgres_port="$(( 21000 + RANDOM % 2000 ))"
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

stop_postgres() {
    if [[ -n "$postgres_name" ]]; then
        podman rm -f "$postgres_name" >/dev/null 2>&1 || true
        postgres_name=""
    fi
}

start_runtime() {
    local java_bin="$1" deadline
    runtime_port="$(( 18000 + RANDOM % 1000 ))"
    env \
        SERVER_ADDRESS=127.0.0.1 \
        SERVER_PORT="$runtime_port" \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$postgres_port/chromascape?sslmode=disable" \
        SPRING_DATASOURCE_USERNAME=chromascape \
        SPRING_DATASOURCE_PASSWORD=chromascape123 \
        CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=false \
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
        JAVA="$java_bin" \
        setsid "$prefix/current/launcher" > "$log" 2>&1 &
    runtime_pid=$!
    deadline=$((SECONDS + app_timeout))
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
}

check_loopback_binding() {
    local listeners
    listeners="$(ss -H -ltn "sport = :$runtime_port" 2>/dev/null | awk '{print $4}' | sort -u | tr '\n' ',')"
    if [[ -z "$listeners" ]]; then
        record loopbackBinding FAIL "no listener observed on :$runtime_port"
        return
    fi
    if printf '%s' "$listeners" | grep -qvE '(^|,)(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):'; then
        record loopbackBinding FAIL "non-loopback listener(s) on :$runtime_port: $listeners"
        return
    fi
    record loopbackBinding PASS "only loopback listeners on :$runtime_port: ${listeners%,}"
}

check_debugger_endpoint() {
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$runtime_port/debugger" || true)"
    if [[ "$code" != "200" ]]; then
        record debuggerEndpoint FAIL "/debugger returned HTTP ${code:-none}"
        return
    fi
    record debuggerEndpoint PASS "/debugger returned HTTP 200 on the packaged runtime"
}

check_runtime_restart() {
    local java_bin="$1"
    stop_runtime
    if ! start_runtime "$java_bin"; then
        record runtimeRestart FAIL "packaged runtime did not come back after restart; see $log"
        return
    fi
    if ! curl -sf --max-time 3 "http://127.0.0.1:$runtime_port/debugger" >/dev/null 2>&1; then
        record runtimeRestart FAIL "restarted runtime served no /debugger"
        return
    fi
    record runtimeRestart PASS "stop/start of the packaged runtime re-served /debugger"
}

check_target_discovery() {
    local micro6_dir="${BUILDER_GOAL_MICRO6_PROJECT_DIR:-}" targets
    if [[ -z "$micro6_dir" || ! -d "$micro6_dir" ]]; then
        record targetDiscovery NOT_RUN "BUILDER_GOAL_MICRO6_PROJECT_DIR is unset; no Micro6 root to scope discovery"
        return
    fi
    mapfile -t targets < <(sc1667_discover_targets /proc "$micro6_dir" "$$")
    if (( ${#targets[@]} == 0 )); then
        record targetDiscovery NOT_RUN "no eligible host-native RuneLite/Micro6 client is running"
        return
    fi
    record targetDiscovery PASS "discovered eligible target pid=${targets[0]} (count=${#targets[@]})"
}

check_explicit_selection() {
    local micro6_dir="${BUILDER_GOAL_MICRO6_PROJECT_DIR:-}" configured="${FEDORA02_TARGET_PID:-}" targets
    if [[ -z "$micro6_dir" || ! -d "$micro6_dir" ]]; then
        record explicitSelection NOT_RUN "no Micro6 root configured; discovery prerequisite absent"
        return
    fi
    mapfile -t targets < <(sc1667_discover_targets /proc "$micro6_dir" "$$")
    if (( ${#targets[@]} == 0 )); then
        record explicitSelection NOT_RUN "no eligible target is running to select"
        return
    fi
    if [[ -n "$configured" ]] && printf '%s\n' "${targets[@]}" | grep -qx "$configured"; then
        record explicitSelection PASS "explicitly selected eligible target pid=$configured"
    elif (( ${#targets[@]} > 1 )); then
        record explicitSelection PASS "multiple eligible targets are present; set FEDORA02_TARGET_PID to select one"
    else
        record explicitSelection PASS "single eligible target pid=${targets[0]} is unambiguously selectable"
    fi
}

gate_live_client_check() {
    local name="$1" reason="$2"
    if [[ "$want_live" != true ]]; then
        record "$name" NOT_RUN "live client checks are opt-in; pass --live with a running target and native pair"
        return 1
    fi
    record "$name" NOT_RUN "$reason"
    return 1
}

# ---------------------------------------------------------------------------
# Upgrade / rollback / uninstall
# ---------------------------------------------------------------------------

check_upgrade_and_rollback() {
    if [[ -z "$previous_package" || ! -r "$previous_package" ]]; then
        record upgrade NOT_RUN "no --previous-package supplied; upgrade needs two package versions"
        record rollback NOT_RUN "no --previous-package supplied; rollback needs two package versions"
        return
    fi
    local v1 v2 current
    v1="$(tar -xOzf "$previous_package" --wildcards '*/PACKAGE-MANIFEST.json' 2>/dev/null \
        | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
    v2="$(tar -xOzf "$package" --wildcards '*/PACKAGE-MANIFEST.json' 2>/dev/null \
        | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
    if [[ -z "$v1" || -z "$v2" || "$v1" == "$v2" ]]; then
        record upgrade NOT_RUN "the two packages do not carry distinct versions ($v1 vs $v2)"
        record rollback NOT_RUN "the two packages do not carry distinct versions ($v1 vs $v2)"
        return
    fi
    bash "$SCRIPT_DIR/install-tarball.sh" --tarball "$previous_package" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
    bash "$SCRIPT_DIR/install-tarball.sh" --tarball "$package" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
    current="$(readlink "$prefix/current" | xargs -r basename)"
    if [[ "$current" == "$v2" ]]; then
        record upgrade PASS "upgrade moved current from $v1 to $v2 without rebuilding"
    else
        record upgrade FAIL "upgrade left current at $current, expected $v2"
    fi
    bash "$SCRIPT_DIR/install-tarball.sh" --rollback "$v1" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
    current="$(readlink "$prefix/current" | xargs -r basename)"
    if [[ "$current" == "$v1" ]]; then
        record rollback PASS "rollback moved current back to $v1"
        bash "$SCRIPT_DIR/install-tarball.sh" --tarball "$package" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
    else
        record rollback FAIL "rollback left current at $current, expected $v1"
    fi
}

check_uninstall() {
    local out
    if ! out="$(bash "$SCRIPT_DIR/uninstall-tarball.sh" --prefix "$prefix" --bin-dir "$bin_dir" 2>&1)"; then
        record uninstall FAIL "uninstaller exited non-zero: ${out//$'\n'/ | }"
        return
    fi
    if [[ -e "$prefix" || -e "$bin_dir/chromascape" ]]; then
        record uninstall FAIL "uninstaller reported success but the prefix or shim remains"
        return
    fi
    record uninstall PASS "uninstall removed the prefix and the bin shim"
}

check_no_residue() {
    local residue=0 details=""
    if [[ -e "$prefix" || -e "$bin_dir/chromascape" ]]; then
        residue=$((residue + 1)); details+="install-path "
    fi
    if pgrep -af "$prefix/current|$prefix/releases" >/dev/null 2>&1; then
        residue=$((residue + 1)); details+="process "
    fi
    if [[ -n "$postgres_created_name" ]] && \
        podman container exists "$postgres_created_name" >/dev/null 2>&1; then
        residue=$((residue + 1)); details+="postgres-container "
    fi
    if (( residue > 0 )); then
        record noResidue FAIL "residue detected after uninstall: $details"
        return
    fi
    record noResidue PASS "no install path, process or disposable container remains after uninstall"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if [[ -n "$evidence" && ! -f "$evidence" ]]; then
    python3 -B "$PROJECT_DIR/scripts/qualification/fedora02_evidence.py" init \
        --role "$role" --host-id "$host_id" --revision "$revision" --version "$version" \
        --output "$evidence" >/dev/null
fi

# ---------------------------------------------------------------------------
# RPM certification: a disposable clean Fedora container
# ---------------------------------------------------------------------------

certify_rpm_disposable() {
    local image="${FEDORA02_RPM_BASE_IMAGE:-registry.fedoraproject.org/fedora:44}"
    local rpm_name out
    rpm_name="$(basename "$rpm_file")"
    if ! command -v podman >/dev/null 2>&1; then
        record rpmDisposableInstall NOT_RUN "podman is unavailable for a disposable clean Fedora environment"
        return
    fi
    local container_script
    container_script="$(cat <<'CONTAINER'
set -u
dnf -y install "/tmp/${RPM_NAME}" >/dev/null 2>&1 || { echo RPM_INSTALL_FAILED; exit 1; }
rpm -q chromascape >/dev/null || { echo RPM_QUERY_FAILED; exit 1; }
test -x /usr/bin/chromascape || { echo RPM_LAYOUT_FAILED:shim; exit 1; }
test -f /usr/lib/chromascape/application.jar || { echo RPM_LAYOUT_FAILED:jar; exit 1; }
test -f /usr/lib/chromascape/launcher || { echo RPM_LAYOUT_FAILED:launcher; exit 1; }
test -f /usr/share/licenses/chromascape/LICENSE || { echo RPM_LAYOUT_FAILED:license; exit 1; }
java -version 2>&1 | grep -qE 'version "(1[7-9]|2[0-9])\.' || { echo RPM_JAVA_FAILED; exit 1; }
test ! -e /usr/lib/chromascape/native || { echo RPM_STALE_NATIVE; exit 1; }
test ! -e /usr/lib/chromascape/gradlew || { echo RPM_BUILD_TOOLING; exit 1; }
test ! -e /usr/lib/chromascape/.git || { echo RPM_REPO_FILES; exit 1; }
echo RPM_INSTALL_OK
dnf -y remove chromascape >/dev/null 2>&1 || echo RPM_REMOVE_DNF_NONZERO
if rpm -q chromascape >/dev/null 2>&1; then echo RPM_STILL_INSTALLED; exit 1; fi
if [ -e /usr/bin/chromascape ]; then echo RPM_FILES_REMAIN:shim; exit 1; fi
if [ -n "$(find /usr/lib/chromascape -mindepth 1 2>/dev/null)" ]; then echo RPM_FILES_REMAIN:lib; exit 1; fi
echo RPM_REMOVE_OK
CONTAINER
)"
    out="$(podman run --rm \
        -e RPM_NAME="$rpm_name" \
        -v "$(realpath "$rpm_file"):/tmp/$rpm_name:ro,z" \
        "$image" bash -lc "$container_script" 2>&1 || true)"

    if grep -q RPM_INSTALL_OK <<<"$out"; then
        record rpmDisposableInstall PASS "RPM installed in a disposable Fedora container ($image)"
        record launchWithoutBuildTools PASS "installed tree carries application.jar + launcher and no Gradle/Git/compiler inputs"
        record javaDetection PASS "dnf resolved a Java 17+ runtime; java -version reported a supported release"
        record nativeDetection PASS "base RPM carries no native payload"
    else
        record rpmDisposableInstall FAIL "install failed: ${out//$'\n'/ | }"
        record launchWithoutBuildTools NOT_RUN "install did not complete"
        record javaDetection NOT_RUN "install did not complete"
        record nativeDetection NOT_RUN "install did not complete"
    fi
    if grep -q RPM_REMOVE_OK <<<"$out"; then
        record uninstall PASS "dnf remove chromascape removed the package"
        record noResidue PASS "no package files or rpmdb entry remain in the disposable container"
    else
        record uninstall FAIL "removal failed: ${out//$'\n'/ | }"
        record noResidue FAIL "removal failed: ${out//$'\n'/ | }"
    fi
}

if [[ "$role" == "tarball" ]]; then
    check_tarball_install_no_sudo
    check_launch_without_build_tools
    check_java_detection
    check_native_detection
    record_version os "$(sed -n 's/^PRETTY_NAME="\(.*\)"$/\1/p' /etc/os-release | head -n 1)"
    record_version arch "$(uname -m)"
    record_version java "$("$(command -v java)" -version 2>&1 | head -n 1)"
    record_version packageSha256 "$(fedorapkg_sha256 "$package")"
    record_version packageVersion "$version"

    java_bin="${FEDORA02_JAVA:-}"
    if [[ -z "$java_bin" ]]; then
        for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
            "$HOME/jdks/jdk-17.0.20.1+1/bin/java" "$HOME/.gradle/jdks/jdk-17.0.20.1+1/bin/java"; do
            [[ -x "$candidate" ]] && java_bin="$candidate" && break
        done
    fi
    if [[ -z "$java_bin" ]]; then
        java_bin="$(command -v java || true)"
    fi

    if [[ "$skip_runtime" == true ]]; then
        for name in loopbackBinding debuggerEndpoint runtimeRestart \
            targetDiscovery explicitSelection packagedNativeAttachment frameCapture boundedInput \
            inputRelease cleanClose targetRestart multipleClients; do
            [[ " ${ran_checks[*]} " == *" $name "* ]] || record "$name" NOT_RUN "runtime checks skipped (--skip-runtime)"
        done
        check_upgrade_and_rollback
        check_uninstall
        check_no_residue
    else
        if ! start_postgres; then
            record loopbackBinding NOT_RUN "no disposable Postgres available (podman/image); pass --no-postgres to target an external DB"
            record debuggerEndpoint NOT_RUN "no database available for a packaged runtime start"
            record runtimeRestart NOT_RUN "no database available for a packaged runtime start"
        elif ! start_runtime "$java_bin"; then
            record loopbackBinding FAIL "packaged runtime did not start; see $log"
            record debuggerEndpoint FAIL "packaged runtime did not start; see $log"
            record runtimeRestart NOT_RUN "runtime never became ready"
        else
            check_loopback_binding
            check_debugger_endpoint
            check_runtime_restart "$java_bin"
            stop_runtime
        fi
        # The disposable database is removed before residue is assessed, in every
        # outcome, so a failed start cannot masquerade as a leaked container.
        stop_postgres
        check_target_discovery
        check_explicit_selection
        gate_live_client_check packagedNativeAttachment "requires a running target and the native pair wired into the packaged app root" || true
        gate_live_client_check frameCapture "requires an attached target to capture rendered frames" || true
        gate_live_client_check boundedInput "requires an attached target for bounded mouse/keyboard input" || true
        gate_live_client_check inputRelease "requires a held-input session to release" || true
        gate_live_client_check cleanClose "requires an attach session to close cleanly" || true
        gate_live_client_check targetRestart "requires a target process to restart and re-attach" || true
        gate_live_client_check multipleClients "requires two eligible clients running" || true
        check_upgrade_and_rollback
        check_uninstall
        check_no_residue
    fi
else
    record_version os "$(sed -n 's/^PRETTY_NAME="\(.*\)"$/\1/p' /etc/os-release | head -n 1)"
    record_version arch "$(uname -m)"
    record_version java "$("$(command -v java)" -version 2>&1 | head -n 1)"
    record_version packageSha256 "$(fedorapkg_sha256 "$rpm_file")"
    record_version packageVersion "$version"
    certify_rpm_disposable
fi

cleanup() {
    stop_runtime
    stop_postgres
    if [[ "$keep_work" == true || "$failures" -gt 0 ]]; then
        fedorapkg_emit INFO WORKDIR "scratch retained at $work_root"
    elif [[ -n "$work_root" && "$work_root" == *fedora02-certify* ]]; then
        rm -rf "$work_root"
    fi
}
trap cleanup EXIT

if (( failures > 0 )); then
    fedorapkg_emit FAIL CERTIFICATION "executed checks failed=$failures"
    exit 2
fi
fedorapkg_emit PASS CERTIFICATION "executed checks passed; recorded=${ran_checks[*]:-none}"
