#!/usr/bin/env bash

# SC-1667 Fedora host-native preflight library. This file is sourced by the
# lifecycle wrapper and the fixture regression harness; it performs no work on source.

SC1667_ERRORS=0
SC1667_WARNINGS=0
SC1667_SELECTED_PID=""

sc1667_emit() {
    local level="$1" code="$2" message="$3"
    printf '%s code=%s message=%s\n' "$level" "$code" "$message"
    case "$level" in
        ERROR) SC1667_ERRORS=$((SC1667_ERRORS + 1)) ;;
        WARN) SC1667_WARNINGS=$((SC1667_WARNINGS + 1)) ;;
    esac
}

sc1667_trim_quotes() {
    local value="$1"
    if [[ ${#value} -ge 2 ]]; then
        if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
            value="${value:1:${#value}-2}"
        fi
    fi
    printf '%s' "$value"
}

sc1667_allowed_env_key() {
    case "$1" in
        JAVA_HOME|GRADLE_USER_HOME|SERVER_ADDRESS|SERVER_PORT|STATIC_API_TOKEN|ANTHROPIC_API_KEY|OPENAI_API_KEY|BUILDER_GOAL_*|CHROMASCAPE_*|SPRING_*|LEEUWBOT_*|GAMEPLAY_*|AI_*|MICRO6_*|SC967_*|CANDIDATE_*|RECORDING_*) return 0 ;;
        *) return 1 ;;
    esac
}

# Loads simple KEY=VALUE assignments without eval/source. Existing non-empty
# environment variables win; values and file contents are never printed.
sc1667_load_env_file() {
    local file="$1" line key value line_number=0
    [[ -f "$file" ]] || return 0
    while IFS= read -r line || [[ -n "$line" ]]; do
        line_number=$((line_number + 1))
        line="${line%$'\r'}"
        [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
        if [[ "$line" != *"="* ]]; then
            sc1667_emit ERROR CONFIG_INVALID "environment file line $line_number has no equals sign"
            continue
        fi
        key="${line%%=*}"
        value="${line#*=}"
        key="${key#${key%%[![:space:]]*}}"
        key="${key%${key##*[![:space:]]}}"
        if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
            sc1667_emit ERROR CONFIG_INVALID "environment file line $line_number has an invalid key"
            continue
        fi
        if ! sc1667_allowed_env_key "$key"; then
            sc1667_emit ERROR CONFIG_KEY_UNSUPPORTED "environment file line $line_number uses a key outside the application allowlist"
            continue
        fi
        if [[ ( "$value" == \"* && "$value" != *\" ) ||
              ( "$value" == \'* && "$value" != *\' ) ||
              ( "$value" != \"* && "$value" == *\" ) ||
              ( "$value" != \'* && "$value" == *\' ) ]]; then
            sc1667_emit ERROR CONFIG_INVALID "environment file line $line_number has unmatched quotes"
            continue
        fi
        value="$(sc1667_trim_quotes "$value")"
        if [[ -z "${!key:-}" ]]; then
            printf -v "$key" '%s' "$value"
            export "$key"
        fi
    done < "$file"
    sc1667_emit INFO CONFIG_LOADED "loaded machine-local Fedora configuration keys"
}

sc1667_bool() {
    case "${1,,}" in
        true|1|yes|on) return 0 ;;
        false|0|no|off|'') return 1 ;;
        *) return 2 ;;
    esac
}

sc1667_java_major() {
    local java_bin="$1" version
    version="$($java_bin -version 2>&1 | sed -n '1{s/.*version "\([^"]*\)".*/\1/p;q;}')"
    [[ -n "$version" ]] || return 1
    if [[ "$version" == 1.* ]]; then
        printf '%s' "${version#1.}" | cut -d. -f1
    else
        printf '%s' "$version" | cut -d. -f1
    fi
}

sc1667_require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        sc1667_emit ERROR TOOL_MISSING "$command_name is required by the Fedora lifecycle wrapper"
        return 1
    fi
    return 0
}

sc1667_read_start_ticks() {
    local proc_root="$1" pid="$2" stat rest
    [[ -r "$proc_root/$pid/stat" ]] || return 1
    IFS= read -r stat < "$proc_root/$pid/stat" || return 1
    rest="${stat##*) }"
    # shellcheck disable=SC2086
    set -- $rest
    [[ $# -ge 20 ]] || return 1
    printf '%s' "${20}"
}

sc1667_is_eligible_target() {
    local proc_root="$1" pid="$2" micro6_dir="$3" bytes command_line
    [[ -r "$proc_root/$pid/cmdline" ]] || return 1
    bytes="$(wc -c < "$proc_root/$pid/cmdline" 2>/dev/null)" || return 1
    [[ "$bytes" =~ ^[0-9]+$ && "$bytes" -gt 0 && "$bytes" -le 65536 ]] || return 1
    command_line="$(LC_ALL=C tr '\0' ' ' < "$proc_root/$pid/cmdline")"
    case "$command_line" in
        *org.gradle.launcher.daemon*|*gradle-daemon-main*|*GradleDaemon*|*gradle-worker*|*remote-launcher*) return 1 ;;
    esac
    [[ "$command_line" == *net.runelite.client.RuneLite* ]] && return 0
    [[ -n "$micro6_dir" && "$command_line" == *runBuilderGoal* && "$command_line" == *"$micro6_dir"* ]]
}

sc1667_discover_targets() {
    local proc_root="$1" micro6_dir="$2" self_pid="${3:-}" entry pid
    local -a found=()
    for entry in "$proc_root"/[0-9]*; do
        [[ -d "$entry" ]] || continue
        pid="${entry##*/}"
        [[ "$pid" =~ ^[0-9]+$ ]] || continue
        [[ -n "$self_pid" && "$pid" == "$self_pid" ]] && continue
        if sc1667_is_eligible_target "$proc_root" "$pid" "$micro6_dir"; then
            found+=("$pid")
        fi
    done
    if (( ${#found[@]} > 0 )); then
        printf '%s\n' "${found[@]}" | sort -n
    fi
}

sc1667_has_cap_sys_ptrace() {
    local status_file="$1" cap_eff
    cap_eff="$(awk '/^CapEff:/ {print $2; exit}' "$status_file" 2>/dev/null)"
    [[ "$cap_eff" =~ ^[0-9A-Fa-f]+$ ]] || return 1
    (( (16#$cap_eff & (1 << 19)) != 0 ))
}

sc1667_check_sandbox() {
    local proc_root="$1" pid="$2" current="$pid" depth=0 comm parent no_new_privs seccomp
    if [[ -f "$proc_root/$pid/root/.flatpak-info" ]]; then
        sc1667_emit ERROR SANDBOX_RESTRICTED "selected target is inside Flatpak isolation; use a host-native RuneLite/Micro6 launch"
        return
    fi
    while [[ "$current" =~ ^[0-9]+$ && "$current" -gt 1 && "$depth" -lt 32 ]]; do
        comm=""
        if [[ -r "$proc_root/$current/comm" ]]; then
            IFS= read -r comm < "$proc_root/$current/comm" || true
        fi
        case "$comm" in
            bwrap|flatpak|flatpak-session-helper)
                sc1667_emit ERROR SANDBOX_RESTRICTED "selected target has a sandbox ancestor; use the documented host-native launch path"
                return
                ;;
        esac
        parent="$(awk '/^PPid:/ {print $2; exit}' "$proc_root/$current/status" 2>/dev/null)"
        [[ "$parent" =~ ^[0-9]+$ && "$parent" != "$current" ]] || break
        current="$parent"
        depth=$((depth + 1))
    done
    sc1667_emit PASS SANDBOX_HOST_NATIVE "selected target has no observed Flatpak/bwrap boundary"
    no_new_privs="$(awk '/^NoNewPrivs:/ {print $2; exit}' "$proc_root/$pid/status" 2>/dev/null)"
    seccomp="$(awk '/^Seccomp:/ {print $2; exit}' "$proc_root/$pid/status" 2>/dev/null)"
    if [[ "$no_new_privs" == "1" ]]; then
        sc1667_emit WARN NO_NEW_PRIVILEGES "selected target has no_new_privs enabled; inspect its launcher if native injection fails"
    fi
    if [[ "$seccomp" =~ ^[1-9][0-9]*$ ]]; then
        sc1667_emit WARN SECCOMP_ACTIVE "selected target has seccomp mode $seccomp; inspect its launcher policy if native injection fails"
    fi
}

sc1667_check_selinux() {
    local state
    if ! command -v getenforce >/dev/null 2>&1; then
        sc1667_emit WARN SELINUX_STATUS_UNKNOWN "getenforce is unavailable; SELinux state was not changed"
        return
    fi
    state="$(getenforce 2>/dev/null || true)"
    case "$state" in
        Enforcing)
            sc1667_emit INFO SELINUX_ENFORCING "SELinux is enforcing; inspect AVC denials if attachment fails; preflight made no policy change"
            ;;
        Permissive|Disabled)
            sc1667_emit PASS SELINUX_OBSERVED "SELinux reports $state; preflight made no policy change"
            ;;
        *)
            sc1667_emit WARN SELINUX_STATUS_UNKNOWN "SELinux status is unavailable; preflight made no policy change"
            ;;
    esac
}

sc1667_check_target_boundaries() {
    local proc_root="$1" pid="$2" self_pid="$3" self_ns target_ns self_uid target_uid
    self_ns="$(readlink "$proc_root/$self_pid/ns/pid" 2>/dev/null || true)"
    target_ns="$(readlink "$proc_root/$pid/ns/pid" 2>/dev/null || true)"
    if [[ -z "$self_ns" || -z "$target_ns" ]]; then
        sc1667_emit ERROR PID_NAMESPACE_UNREADABLE "cannot compare ChromaScape and target PID namespaces"
    elif [[ "$self_ns" != "$target_ns" ]]; then
        sc1667_emit ERROR PID_NAMESPACE_MISMATCH "selected target is in a different PID namespace; launch both processes host-native in the same namespace"
    else
        sc1667_emit PASS PID_NAMESPACE_MATCH "selected target shares the ChromaScape PID namespace"
    fi

    self_uid="$(stat -c '%u' "$proc_root/$self_pid" 2>/dev/null || true)"
    target_uid="$(stat -c '%u' "$proc_root/$pid" 2>/dev/null || true)"
    if [[ -z "$self_uid" || -z "$target_uid" || "$self_uid" != "$target_uid" ]]; then
        sc1667_emit ERROR TARGET_USER_MISMATCH "selected target is not owned by the ChromaScape user"
    else
        sc1667_emit PASS TARGET_USER_MATCH "selected target is owned by the ChromaScape user"
    fi
    sc1667_check_sandbox "$proc_root" "$pid"
}

sc1667_check_ptrace() {
    local proc_root="$1" self_pid="$2" scope_file="$3" scope
    if [[ ! -r "$scope_file" ]]; then
        sc1667_emit WARN PTRACE_POLICY_UNKNOWN "kernel ptrace policy is unreadable; attachment may still be refused"
        return
    fi
    IFS= read -r scope < "$scope_file"
    if [[ "$scope" == "0" ]]; then
        sc1667_emit PASS PTRACE_POLICY_READY "kernel ptrace_scope permits same-user attachment"
    elif sc1667_has_cap_sys_ptrace "$proc_root/$self_pid/status"; then
        sc1667_emit PASS PTRACE_CAPABILITY_PRESENT "ChromaScape has CAP_SYS_PTRACE for the restrictive kernel policy"
    else
        sc1667_emit ERROR PTRACE_POLICY_RESTRICTED "kernel ptrace_scope=$scope and ChromaScape lacks CAP_SYS_PTRACE; review host policy manually"
    fi
}

sc1667_check_native_pair() {
    local project_dir="$1" app_root="$2" library="$3" artifact manifest helper
    artifact="$library"
    if [[ -z "$artifact" && -n "$app_root" ]]; then
        artifact="$app_root/native/linux-x86_64/libRemoteInput.so"
    fi
    if [[ -z "$artifact" || "$artifact" != /* ]]; then
        sc1667_emit ERROR NATIVE_PATH_INVALID "enabled attachment requires an absolute library path or application root"
        return
    fi
    manifest="$(dirname "$artifact")/remoteinput-manifest.json"
    helper="$project_dir/scripts/native/remoteinput_metadata.py"
    if [[ ! -r "$artifact" ]]; then
        sc1667_emit ERROR NATIVE_LIBRARY_MISSING "configured RemoteInput library is absent or unreadable"
        return
    fi
    if [[ ! -r "$manifest" ]]; then
        sc1667_emit ERROR NATIVE_MANIFEST_MISSING "the library sibling remoteinput-manifest.json is absent or unreadable"
        return
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        sc1667_emit ERROR PYTHON_MISSING "python3 is required to verify the native artifact manifest"
        return
    fi
    if python3 -B "$helper" verify-manifest --manifest "$manifest" --artifact "$artifact" >/dev/null; then
        sc1667_emit PASS NATIVE_PAIR_VERIFIED "RemoteInput library and manifest identity match"
    else
        sc1667_emit ERROR NATIVE_PAIR_INVALID "RemoteInput library and manifest verification failed"
    fi
}

sc1667_run_preflight() {
    local project_dir="$1"
    local proc_root="${CHROMASCAPE_FEDORA_PROC_ROOT:-/proc}"
    local os_release="${CHROMASCAPE_FEDORA_OS_RELEASE:-/etc/os-release}"
    local ptrace_scope="${CHROMASCAPE_FEDORA_PTRACE_SCOPE:-/proc/sys/kernel/yama/ptrace_scope}"
    local self_pid="${CHROMASCAPE_FEDORA_SELF_PID:-$$}"
    local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java" java_major os_id arch tools_ready=true
    local micro6_dir="${BUILDER_GOAL_MICRO6_PROJECT_DIR:-}"
    local require_target="${CHROMASCAPE_FEDORA_REQUIRE_TARGET:-true}"
    local configured_pid="${CHROMASCAPE_FEDORA_TARGET_PID:-}"
    local attachment="${CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED:-false}"
    local -a targets=()

    SC1667_ERRORS=0
    SC1667_WARNINGS=0
    SC1667_SELECTED_PID=""

    if [[ "$(uname -s)" != "Linux" ]]; then
        sc1667_emit ERROR HOST_UNSUPPORTED "SC-1667 supports Linux hosts only"
    fi
    os_id="$(awk -F= '$1 == "ID" {gsub(/^\"|\"$/, "", $2); print $2; exit}' "$os_release" 2>/dev/null)"
    if [[ "$os_id" == "fedora" ]]; then
        sc1667_emit PASS FEDORA_HOST "host reports Fedora"
    else
        sc1667_emit ERROR FEDORA_REQUIRED "host does not report Fedora"
    fi
    arch="$(uname -m)"
    if [[ "$arch" == "x86_64" || "$arch" == "amd64" ]]; then
        sc1667_emit PASS ARCH_SUPPORTED "host architecture is x86-64"
    else
        sc1667_emit ERROR ARCH_UNSUPPORTED "RemoteInput requires Linux x86-64"
    fi

    if [[ ! -x "$java_bin" ]]; then
        java_bin="$(command -v java || true)"
    fi
    java_major="$(sc1667_java_major "$java_bin" 2>/dev/null || true)"
    if [[ "$java_major" == "17" ]]; then
        sc1667_emit PASS JAVA_SUPPORTED "Java 17 is available"
    else
        sc1667_emit ERROR JAVA_UNSUPPORTED "this Gradle build requires JAVA_HOME to select JDK 17"
    fi

    if [[ "$project_dir" != /* || ! -r "$project_dir/gradlew" ]]; then
        sc1667_emit ERROR PROJECT_INVALID "project root must be absolute and contain readable gradlew"
    else
        sc1667_emit PASS PROJECT_READY "project root and Gradle wrapper are ready"
    fi
    for required_command in bash setsid flock ps ss curl awk sed grep sort tr stat wc readlink realpath; do
        sc1667_require_command "$required_command" || tools_ready=false
    done
    if [[ "$tools_ready" == true ]]; then
        sc1667_emit PASS TOOLS_AVAILABLE "required Fedora lifecycle tools are available"
    fi
    if [[ -z "$micro6_dir" || "$micro6_dir" != /* || ! -d "$micro6_dir" ]]; then
        sc1667_emit ERROR MICRO6_PROJECT_INVALID "BUILDER_GOAL_MICRO6_PROJECT_DIR must name an absolute existing directory"
    else
        micro6_dir="$(realpath --canonicalize-missing --no-symlinks "$micro6_dir")"
        sc1667_emit PASS MICRO6_PROJECT_READY "Micro6 project root is available"
    fi

    if sc1667_bool "$attachment"; then
        sc1667_emit INFO ATTACHMENT_ENABLED "RemoteInput attachment is enabled and will be checked"
        sc1667_check_native_pair "$project_dir" "${CHROMASCAPE_REMOTEINPUT_APPLICATION_ROOT:-}" "${CHROMASCAPE_REMOTEINPUT_LIBRARY:-}"
    else
        case $? in
            1) sc1667_emit WARN ATTACHMENT_DISABLED "RemoteInput attachment is disabled; discovery remains read-only" ;;
            *) sc1667_emit ERROR CONFIG_INVALID "CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED is not a boolean" ;;
        esac
    fi

    mapfile -t targets < <(sc1667_discover_targets "$proc_root" "$micro6_dir" "$self_pid")
    if [[ -n "$configured_pid" ]]; then
        if [[ ! "$configured_pid" =~ ^[0-9]+$ ]]; then
            sc1667_emit ERROR TARGET_PID_INVALID "CHROMASCAPE_FEDORA_TARGET_PID must be numeric"
        elif printf '%s\n' "${targets[@]}" | grep -qx "$configured_pid"; then
            SC1667_SELECTED_PID="$configured_pid"
            sc1667_emit PASS TARGET_SELECTED "explicit eligible target pid=$configured_pid"
        else
            sc1667_emit ERROR TARGET_NOT_ELIGIBLE "configured target pid is absent or ineligible"
        fi
    elif (( ${#targets[@]} == 1 )); then
        SC1667_SELECTED_PID="${targets[0]}"
        sc1667_emit PASS TARGET_SELECTED "one eligible local target pid=${targets[0]}"
    elif (( ${#targets[@]} > 1 )); then
        sc1667_emit ERROR TARGET_AMBIGUOUS "multiple eligible targets found; set CHROMASCAPE_FEDORA_TARGET_PID explicitly"
    elif sc1667_bool "$require_target"; then
        sc1667_emit ERROR TARGET_MISSING "no eligible host-native RuneLite/Micro6 client was found"
    else
        case $? in
            1) sc1667_emit WARN TARGET_MISSING "no eligible client is running; rerun preflight after launch" ;;
            *) sc1667_emit ERROR CONFIG_INVALID "CHROMASCAPE_FEDORA_REQUIRE_TARGET is not a boolean" ;;
        esac
    fi

    if [[ -n "$SC1667_SELECTED_PID" ]]; then
        sc1667_check_target_boundaries "$proc_root" "$SC1667_SELECTED_PID" "$self_pid"
        sc1667_check_ptrace "$proc_root" "$self_pid" "$ptrace_scope"
    fi
    sc1667_check_selinux

    if (( SC1667_ERRORS == 0 )); then
        printf 'SC1667_PREFLIGHT status=READY errors=0 warnings=%d targetPid=%s\n' \
            "$SC1667_WARNINGS" "${SC1667_SELECTED_PID:-none}"
        return 0
    fi
    printf 'SC1667_PREFLIGHT status=BLOCKED errors=%d warnings=%d targetPid=%s\n' \
        "$SC1667_ERRORS" "$SC1667_WARNINGS" "${SC1667_SELECTED_PID:-none}"
    return 2
}
