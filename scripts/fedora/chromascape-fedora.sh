#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=lib/preflight.sh
source "$SCRIPT_DIR/lib/preflight.sh"

STATE_DIR=""
STATE_FILE=""
LOG_FILE=""

set_default_environment() {
    local key="$1" value="$2"
    if [[ -z "${!key:-}" ]]; then
        printf -v "$key" '%s' "$value"
        export "$key"
    fi
}

set_fedora_runtime_defaults() {
    set_default_environment SERVER_ADDRESS 127.0.0.1
    set_default_environment SERVER_PORT 8090
    # The development profile is the only profile under which the bounded qualification harness
    # bean may exist. Default it here so a Fedora run is reproducible, but never overwrite an
    # operator-supplied profile (set_default_environment only fills an unset/empty value), so a
    # production or explicit profile is preserved exactly.
    set_default_environment SPRING_PROFILES_ACTIVE local-development
    # The harness stays disabled unless an operator explicitly opts in; both defaults are
    # documentation-equivalent to application.yml and are preserved when the operator sets them.
    set_default_environment CHROMASCAPE_QUALIFICATION_ENABLED false
    set_default_environment CHROMASCAPE_QUALIFICATION_CAPTURE_DIR .chromascape/qualification/captures
    set_default_environment LEEUWBOT_STREAM_URL ws://127.0.0.1:8084/ws/screens/agent
    set_default_environment LEEUWBOT_STREAM_HOST_ID fedora-host-native
    set_default_environment BUILDER_GOAL_LAUNCHER_BASE_URL http://127.0.0.1:8084
    set_default_environment BUILDER_GOAL_ACCOUNT_CATALOG_BASE_URL http://127.0.0.1:8092
    set_default_environment CHROMASCAPE_PATCHLOOP_MICRO6_EXECUTOR_BASE_URL http://127.0.0.1:8098
    set_default_environment MICRO6_EXECUTOR_BASE_URL http://127.0.0.1:8098
    set_default_environment GAMEPLAY_BOOTSTRAP_MICRO6_URL http://127.0.0.1:8098
    set_default_environment LEEUWBOT_CANDIDATE_REGISTRY_URL http://127.0.0.1:8081
    # The MCP bridge is an analysis sidecar whose scripts/mcp-bridge payload is not
    # part of a Linux checkout; leaving auto-start on aborts Fedora startup with a
    # BridgeConfigException. Keep it off by default and let an operator opt back in.
    set_default_environment MCP_AUTO_START false
}

usage() {
    cat <<'EOF'
Usage: scripts/fedora/chromascape-fedora.sh COMMAND [-- Gradle arguments]

Commands:
  preflight  Run read-only Fedora/native/client attachment diagnostics (default).
  start      Require a passing preflight, start an owned bootRun process group, and wait for health.
  stop       Gracefully stop only the identity recorded by this wrapper.
  restart    Stop the recorded instance, then start a new one.
  status     Report whether the recorded instance identity is still running.

The wrapper reads .env.fedora.local without executing it. It never changes ptrace,
seccomp, SELinux, namespaces, firewall rules, or other host security settings.
EOF
}

read_state() {
    STATE_PID=""
    STATE_START_TICKS=""
    STATE_BOOT_ID=""
    [[ -r "$STATE_FILE" ]] || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            pid) STATE_PID="$value" ;;
            start_ticks) STATE_START_TICKS="$value" ;;
            boot_id) STATE_BOOT_ID="$value" ;;
        esac
    done < "$STATE_FILE"
    [[ "$STATE_PID" =~ ^[0-9]+$ && "$STATE_START_TICKS" =~ ^[0-9]+$ && -n "$STATE_BOOT_ID" ]]
}

state_is_live() {
    local current_ticks current_boot
    read_state || return 1
    kill -0 "$STATE_PID" 2>/dev/null || return 1
    current_ticks="$(sc1667_read_start_ticks /proc "$STATE_PID" 2>/dev/null || true)"
    current_boot=""
    if [[ -r /proc/sys/kernel/random/boot_id ]]; then
        IFS= read -r current_boot < /proc/sys/kernel/random/boot_id || true
    fi
    [[ "$current_ticks" == "$STATE_START_TICKS" && "$current_boot" == "$STATE_BOOT_ID" ]]
}

stop_owned() {
    local timeout="${CHROMASCAPE_FEDORA_STOP_TIMEOUT_SECONDS:-30}" deadline pgid stale_state
    if [[ ! "$timeout" =~ ^[0-9]+$ || "$timeout" -lt 1 ]]; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=invalid-stop-timeout\n' >&2
        return 2
    fi
    if ! state_is_live; then
        if [[ -e "$STATE_FILE" ]]; then
            stale_state="$STATE_FILE.stale"
            mv -f -- "$STATE_FILE" "$stale_state"
            printf 'SC1667_LIFECYCLE status=STOPPED message=stale-identity-not-signalled state=%s\n' \
                "$stale_state"
        else
            printf 'SC1667_LIFECYCLE status=STOPPED message=no-owned-instance\n'
        fi
        return 0
    fi
    pgid="$(ps -o pgid= -p "$STATE_PID" | tr -d '[:space:]')"
    if [[ "$pgid" != "$STATE_PID" ]]; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=recorded-process-is-not-its-group-leader\n' >&2
        return 2
    fi
    kill -TERM -- "-$STATE_PID"
    deadline=$((SECONDS + timeout))
    while state_is_live && (( SECONDS < deadline )); do
        sleep 1
    done
    if state_is_live; then
        kill -KILL -- "-$STATE_PID"
        sleep 1
    fi
    if state_is_live; then
        printf 'SC1667_LIFECYCLE status=BLOCKED message=owned-instance-did-not-stop\n' >&2
        return 2
    fi
    if [[ -e "$STATE_FILE" ]]; then
        mv -f -- "$STATE_FILE" "$STATE_FILE.stale"
    fi
    printf 'SC1667_LIFECYCLE status=STOPPED message=owned-instance-released\n'
}

start_owned() {
    local startup_timeout="${CHROMASCAPE_FEDORA_STARTUP_TIMEOUT_SECONDS:-900}"
    local server_address="${SERVER_ADDRESS:-127.0.0.1}" server_port="${SERVER_PORT:-8090}" health_host
    local deadline pid start_ticks boot_id state_tmp
    if state_is_live; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=owned-instance-already-running pid=%s\n' "$STATE_PID" >&2
        return 2
    fi
    if [[ ! "$server_port" =~ ^[0-9]+$ || "$server_port" -lt 1 || "$server_port" -gt 65535 ]]; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=invalid-server-port\n' >&2
        return 2
    fi
    if [[ ! "$startup_timeout" =~ ^[0-9]+$ || "$startup_timeout" -lt 1 ]]; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=invalid-startup-timeout\n' >&2
        return 2
    fi
    if ss -H -ltn "sport = :$server_port" | grep -q .; then
        printf 'SC1667_LIFECYCLE status=REFUSED message=configured-port-already-listening port=%s\n' \
            "$server_port" >&2
        return 2
    fi
    health_host="$server_address"
    [[ "$health_host" == "0.0.0.0" || "$health_host" == "::" ]] && health_host="127.0.0.1"
    rm -f "$STATE_FILE"
    sc1667_run_preflight "$PROJECT_DIR"
    mkdir -p "$STATE_DIR"
    : > "$LOG_FILE"
    # The lifecycle lock belongs to this short-lived wrapper only. Close fd 9 in the child so
    # status/stop/restart can acquire the lock after start returns.
    setsid bash "$PROJECT_DIR/gradlew" --no-daemon bootRun "$@" 9>&- >> "$LOG_FILE" 2>&1 &
    pid=$!
    trap 'kill -TERM -- "-'"$pid"'" 2>/dev/null || true' HUP INT TERM
    start_ticks="$(sc1667_read_start_ticks /proc "$pid" 2>/dev/null || true)"
    boot_id=""
    if [[ -r /proc/sys/kernel/random/boot_id ]]; then
        IFS= read -r boot_id < /proc/sys/kernel/random/boot_id || true
    fi
    state_tmp="$STATE_FILE.tmp.$$"
    if [[ ! "$start_ticks" =~ ^[0-9]+$ || -z "$boot_id" ]]; then
        kill -TERM -- "-$pid" 2>/dev/null || true
        trap - HUP INT TERM
        printf 'SC1667_LIFECYCLE status=FAILED message=could-not-record-process-identity\n' >&2
        return 2
    fi
    if ! {
        printf 'pid=%s\n' "$pid"
        printf 'start_ticks=%s\n' "$start_ticks"
        printf 'boot_id=%s\n' "$boot_id"
    } > "$state_tmp" || ! mv -f -- "$state_tmp" "$STATE_FILE"; then
        kill -TERM -- "-$pid" 2>/dev/null || true
        rm -f -- "$state_tmp"
        trap - HUP INT TERM
        printf 'SC1667_LIFECYCLE status=FAILED message=could-not-publish-process-identity\n' >&2
        return 2
    fi
    trap - HUP INT TERM
    deadline=$((SECONDS + startup_timeout))
    while (( SECONDS < deadline )); do
        if ! state_is_live; then
            printf 'SC1667_LIFECYCLE status=FAILED message=bootRun-exited log=%s\n' "$LOG_FILE" >&2
            rm -f "$STATE_FILE"
            return 2
        fi
        if command -v curl >/dev/null 2>&1 && \
            curl --silent --show-error --fail --max-time 3 \
                "http://$health_host:$server_port/debugger" >/dev/null 2>&1; then
            printf 'SC1667_LIFECYCLE status=READY pid=%s endpoint=http://%s:%s/debugger log=%s\n' \
                "$pid" "$health_host" "$server_port" "$LOG_FILE"
            return 0
        fi
        sleep 2
    done
    printf 'SC1667_LIFECYCLE status=FAILED message=startup-timeout log=%s\n' "$LOG_FILE" >&2
    stop_owned || true
    return 2
}

main() {
    local command="${1:-preflight}"
    if (( $# > 0 )); then shift; fi
    [[ "${1:-}" == "--" ]] && shift
    case "$command" in
        -h|--help|help) usage; return 0 ;;
    esac
    sc1667_load_env_file "$PROJECT_DIR/.env.fedora.local"
    (( SC1667_ERRORS == 0 )) || return 2
    set_fedora_runtime_defaults
    case "$command" in
        preflight) sc1667_run_preflight "$PROJECT_DIR" ;;
        start|stop|restart|status)
            STATE_DIR="${CHROMASCAPE_FEDORA_STATE_DIR:-$PROJECT_DIR/.chromascape/fedora}"
            if [[ "$STATE_DIR" != /* ]]; then
                printf 'SC1667_LIFECYCLE status=REFUSED message=state-directory-must-be-absolute\n' >&2
                return 2
            fi
            STATE_FILE="$STATE_DIR/chromascape.state"
            LOG_FILE="$STATE_DIR/chromascape-bootrun.log"
            mkdir -p "$STATE_DIR"
            exec 9> "$STATE_DIR/lifecycle.lock"
            if ! flock -n 9; then
                printf 'SC1667_LIFECYCLE status=REFUSED message=another-lifecycle-command-is-running\n' >&2
                return 2
            fi
            case "$command" in
                start) start_owned "$@" ;;
                stop) stop_owned ;;
                restart) stop_owned && start_owned "$@" ;;
                status)
                    if state_is_live; then
                        printf 'SC1667_LIFECYCLE status=RUNNING pid=%s log=%s\n' "$STATE_PID" "$LOG_FILE"
                    else
                        printf 'SC1667_LIFECYCLE status=STOPPED\n'
                        return 1
                    fi
                    ;;
            esac
            ;;
        *) usage >&2; return 64 ;;
    esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
