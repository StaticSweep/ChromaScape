#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
FEDORA_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
PROJECT_DIR="$(cd "$FEDORA_DIR/../.." && pwd -P)"
# shellcheck source=../lib/preflight.sh
source "$FEDORA_DIR/lib/preflight.sh"

ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
PASS_COUNT=0

pass() {
    PASS_COUNT=$((PASS_COUNT + 1))
    printf 'PASS %s\n' "$1"
}

fail() {
    printf 'FAIL %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local text="$1" expected="$2" name="$3"
    [[ "$text" == *"$expected"* ]] || fail "$name (missing $expected)"
    pass "$name"
}

write_status() {
    local root="$1" pid="$2" ppid="$3" cap="${4:-0000000000000000}"
    mkdir -p "$root/$pid/ns" "$root/$pid/root"
    printf 'Name:\tfixture\nPPid:\t%s\nCapEff:\t%s\nNoNewPrivs:\t0\nSeccomp:\t0\n' \
        "$ppid" "$cap" > "$root/$pid/status"
    printf 'java\n' > "$root/$pid/comm"
}

write_target() {
    local root="$1" pid="$2" marker="${3:-net.runelite.client.RuneLite}"
    write_status "$root" "$pid" 1
    printf 'java\0-cp\0client.jar\0%s\0' "$marker" > "$root/$pid/cmdline"
}

make_ready_fixture() {
    local base="$1" proc
    proc="$base/proc"
    mkdir -p "$proc/sys/kernel/random" "$base/micro6" "$base/jdk/bin"
    printf 'fixture-boot\n' > "$proc/sys/kernel/random/boot_id"
    printf 'ID=fedora\n' > "$base/os-release"
    printf '0\n' > "$base/ptrace_scope"
    cat > "$base/jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
printf 'openjdk version "17.0.99"\n' >&2
EOF
    chmod +x "$base/jdk/bin/java"
    write_status "$proc" 900 1
    printf 'java\0net.runelite.client.RuneLite\0' > "$proc/900/cmdline"
    write_target "$proc" 100
    ln -s 'pid:[4242]' "$proc/900/ns/pid"
    ln -s 'pid:[4242]' "$proc/100/ns/pid"
}

run_fixture() {
    local base="$1"
    CHROMASCAPE_FEDORA_PROC_ROOT="$base/proc" \
    CHROMASCAPE_FEDORA_OS_RELEASE="$base/os-release" \
    CHROMASCAPE_FEDORA_PTRACE_SCOPE="$base/ptrace_scope" \
    CHROMASCAPE_FEDORA_SELF_PID=900 \
    CHROMASCAPE_FEDORA_REQUIRE_TARGET=true \
    CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=false \
    BUILDER_GOAL_MICRO6_PROJECT_DIR="$base/micro6" \
    JAVA_HOME="$base/jdk" \
        sc1667_run_preflight "$PROJECT_DIR" 2>&1
}

# The local environment parser must treat command syntax as inert data and preserve
# an existing environment value without ever printing either value.
ENV_FIXTURE="$ROOT/env.local"
printf 'CHROMASCAPE_TEST_LITERAL=$(touch %s/pwned)\nSERVER_PORT=9999\nBASH_ENV=%s/payload\n' \
    "$ROOT" "$ROOT" > "$ENV_FIXTURE"
SERVER_PORT=8090
export SERVER_PORT
ENV_OUTPUT="$ROOT/env-output"
sc1667_load_env_file "$ENV_FIXTURE" > "$ENV_OUTPUT"
env_output="$(< "$ENV_OUTPUT")"
[[ ! -e "$ROOT/pwned" ]] || fail "environment parser executed file content"
[[ "$CHROMASCAPE_TEST_LITERAL" == '$''(touch '"$ROOT"'/pwned)' ]] || fail "environment parser changed literal data"
[[ "$SERVER_PORT" == 8090 ]] || fail "environment parser replaced existing value"
[[ -z "${BASH_ENV:-}" ]] || fail "environment parser accepted a shell control variable"
assert_contains "$env_output" 'code=CONFIG_LOADED' 'environment parser is non-executing and precedence-safe'
assert_contains "$env_output" 'code=CONFIG_KEY_UNSUPPORTED' 'environment parser rejects shell control variables'
SC1667_ERRORS=0

# Duplicate keys are rejected regardless of value agreement. Each case runs the parser in
# a subshell so the assertions can inspect the resulting variable, the error count, and the
# diagnostics without leaking state between cases. Values are deliberately recognisable
# secrets-shaped strings so the output can be proven not to contain them.
run_duplicate_case() {
    local fixture="$1" key="$2"
    # The parser must run in the same shell as the counter it increments, so its output
    # is captured through a file rather than a command substitution.
    bash -c '
        source "$1"
        unset "$3"
        sc1667_load_env_file "$2" > "$2.out"
        printf "%s\n---\nerrors=%s\nvalue=%s\n" "$(< "$2.out")" "$SC1667_ERRORS" "${!3:-<unset>}"
    ' bash "$FEDORA_DIR/lib/preflight.sh" "$fixture" "$key"
}

assert_secret_free() {
    local text="$1" name="$2"
    shift 2
    local secret
    for secret in "$@"; do
        [[ "$text" != *"$secret"* ]] || fail "$name (printed a configuration value)"
    done
    pass "$name"
}

DUP_EQUAL="$ROOT/env.dup-equal"
printf 'CHROMASCAPE_DUP_A=secret-equal-1\nSERVER_PORT=8090\nCHROMASCAPE_DUP_A=secret-equal-1\n' > "$DUP_EQUAL"
dup_equal_output="$(run_duplicate_case "$DUP_EQUAL" CHROMASCAPE_DUP_A)"
assert_contains "$dup_equal_output" 'code=CONFIG_DUPLICATE_KEY' 'duplicate key with equal values is rejected'
assert_contains "$dup_equal_output" 'line 3 repeats key CHROMASCAPE_DUP_A first defined on line 1' 'duplicate diagnostic names both lines'
assert_contains "$dup_equal_output" 'errors=1' 'duplicate equal values count as a configuration error'
assert_secret_free "${dup_equal_output%%---*}" 'duplicate equal-value diagnostics print no values' 'secret-equal-1'

DUP_CONFLICT="$ROOT/env.dup-conflict"
printf 'CHROMASCAPE_DUP_B=secret-first-2\nCHROMASCAPE_DUP_B=secret-second-2\n' > "$DUP_CONFLICT"
dup_conflict_output="$(run_duplicate_case "$DUP_CONFLICT" CHROMASCAPE_DUP_B)"
assert_contains "$dup_conflict_output" 'code=CONFIG_DUPLICATE_KEY' 'duplicate key with conflicting values is rejected'
assert_contains "$dup_conflict_output" 'value=secret-first-2' 'later conflicting value is never applied'
assert_secret_free "${dup_conflict_output%%---*}" 'duplicate conflicting diagnostics print no values' 'secret-first-2' 'secret-second-2'

DUP_EMPTY="$ROOT/env.dup-empty"
printf 'CHROMASCAPE_DUP_C=\nCHROMASCAPE_DUP_C=secret-later-3\n' > "$DUP_EMPTY"
dup_empty_output="$(run_duplicate_case "$DUP_EMPTY" CHROMASCAPE_DUP_C)"
assert_contains "$dup_empty_output" 'code=CONFIG_DUPLICATE_KEY' 'duplicate key with empty then non-empty value is rejected'
[[ "$dup_empty_output" != *'value=secret-later-3'* ]] || fail "empty-then-non-empty duplicate applied the later value"
pass 'empty-then-non-empty duplicate never applies the later value'
assert_secret_free "${dup_empty_output%%---*}" 'duplicate empty-first diagnostics print no values' 'secret-later-3'

DUP_QUOTED="$ROOT/env.dup-quoted"
printf 'CHROMASCAPE_DUP_D="secret-quoted-4"\nCHROMASCAPE_DUP_D=secret-quoted-4\n' > "$DUP_QUOTED"
dup_quoted_output="$(run_duplicate_case "$DUP_QUOTED" CHROMASCAPE_DUP_D)"
assert_contains "$dup_quoted_output" 'code=CONFIG_DUPLICATE_KEY' 'quoted and unquoted duplicate is rejected'
assert_contains "$dup_quoted_output" 'errors=1' 'quoted/unquoted duplicate counts exactly once'
assert_secret_free "${dup_quoted_output%%---*}" 'duplicate quoted diagnostics print no values' 'secret-quoted-4'

# External environment precedence is unchanged by duplicate detection: an exported
# non-empty variable still wins over the file even when the file also repeats the key.
DUP_PRECEDENCE="$ROOT/env.dup-precedence"
printf 'CHROMASCAPE_DUP_E=file-1\nCHROMASCAPE_DUP_E=file-2\n' > "$DUP_PRECEDENCE"
dup_precedence_value="$(
    bash -c '
        source "$1"
        export CHROMASCAPE_DUP_E=environment-wins
        sc1667_load_env_file "$2" >/dev/null
        printf "%s" "$CHROMASCAPE_DUP_E"
    ' bash "$FEDORA_DIR/lib/preflight.sh" "$DUP_PRECEDENCE"
)"
[[ "$dup_precedence_value" == environment-wins ]] || fail "duplicate detection changed environment precedence"
pass 'exported environment still overrides a file with duplicate keys'

# A duplicate in .env.fedora.local must stop the wrapper before any command runs.
DUP_PROJECT="$ROOT/dup-project"
mkdir -p "$DUP_PROJECT/scripts"
cp -a "$FEDORA_DIR" "$DUP_PROJECT/scripts/fedora"
printf 'CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=false\nCHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=true\n' \
    > "$DUP_PROJECT/.env.fedora.local"
set +e
dup_startup_output="$("$DUP_PROJECT/scripts/fedora/chromascape-fedora.sh" status 2>&1)"
dup_startup_exit=$?
set -e
[[ "$dup_startup_exit" -eq 2 ]] || fail "wrapper started with a duplicate key in .env.fedora.local (exit $dup_startup_exit)"
assert_contains "$dup_startup_output" 'code=CONFIG_DUPLICATE_KEY' 'duplicate machine-local key fails startup closed'
[[ "$dup_startup_output" != *'SC1667_LIFECYCLE'* ]] || fail "wrapper reached a lifecycle command despite a parser failure"
pass 'parser failure prevents any lifecycle command'

defaults_output="$(
    bash -c '
        source "$1"
        export SERVER_PORT=9999
        unset LEEUWBOT_STREAM_URL
        set_fedora_runtime_defaults
        printf "%s|%s" "$SERVER_PORT" "$LEEUWBOT_STREAM_URL"
    ' bash "$FEDORA_DIR/chromascape-fedora.sh"
)"
[[ "$defaults_output" == '9999|ws://127.0.0.1:8084/ws/screens/agent' ]] || \
    fail "Fedora defaults did not preserve an explicit value and isolate an unset endpoint"
pass 'Fedora runtime defaults preserve overrides and isolate unset endpoints'

READY="$ROOT/ready"
make_ready_fixture "$READY"
ready_output="$(run_fixture "$READY")" || fail "ready fixture was rejected"
assert_contains "$ready_output" 'SC1667_PREFLIGHT status=READY' 'host-native target is ready'
assert_contains "$ready_output" 'code=PID_NAMESPACE_MATCH' 'matching PID namespace is reported'
self_filtered="$(sc1667_discover_targets "$READY/proc" "$READY/micro6" 900)"
[[ "$self_filtered" == 100 ]] || fail "preflight process was not excluded from discovery"
pass 'preflight process is excluded from target discovery'

MISMATCH="$ROOT/mismatch"
cp -a "$READY" "$MISMATCH"
rm "$MISMATCH/proc/100/ns/pid"
ln -s 'pid:[9999]' "$MISMATCH/proc/100/ns/pid"
set +e
mismatch_output="$(run_fixture "$MISMATCH")"
mismatch_exit=$?
set -e
[[ "$mismatch_exit" -eq 2 ]] || fail "namespace mismatch did not fail"
assert_contains "$mismatch_output" 'code=PID_NAMESPACE_MISMATCH' 'PID namespace mismatch fails closed'

SANDBOX="$ROOT/sandbox"
cp -a "$READY" "$SANDBOX"
touch "$SANDBOX/proc/100/root/.flatpak-info"
set +e
sandbox_output="$(run_fixture "$SANDBOX")"
sandbox_exit=$?
set -e
[[ "$sandbox_exit" -eq 2 ]] || fail "Flatpak fixture did not fail"
assert_contains "$sandbox_output" 'code=SANDBOX_RESTRICTED' 'Flatpak boundary fails closed'

PTRACE="$ROOT/ptrace"
cp -a "$READY" "$PTRACE"
printf '1\n' > "$PTRACE/ptrace_scope"
set +e
ptrace_output="$(run_fixture "$PTRACE")"
ptrace_exit=$?
set -e
[[ "$ptrace_exit" -eq 2 ]] || fail "restrictive ptrace fixture did not fail"
assert_contains "$ptrace_output" 'code=PTRACE_POLICY_RESTRICTED' 'restrictive ptrace policy is diagnostic only and blocks'

AMBIGUOUS="$ROOT/ambiguous"
cp -a "$READY" "$AMBIGUOUS"
write_target "$AMBIGUOUS/proc" 200
ln -s 'pid:[4242]' "$AMBIGUOUS/proc/200/ns/pid"
set +e
ambiguous_output="$(run_fixture "$AMBIGUOUS")"
ambiguous_exit=$?
set -e
[[ "$ambiguous_exit" -eq 2 ]] || fail "ambiguous fixture did not fail"
assert_contains "$ambiguous_output" 'code=TARGET_AMBIGUOUS' 'multiple targets require explicit selection'

explicit_output="$(
    CHROMASCAPE_FEDORA_TARGET_PID=200 run_fixture "$AMBIGUOUS"
)" || fail "explicit target fixture was rejected"
assert_contains "$explicit_output" 'code=TARGET_SELECTED message=explicit eligible target pid=200' 'explicit target resolves ambiguity'

EXCLUDED="$ROOT/excluded"
make_ready_fixture "$EXCLUDED"
printf 'java\0org.gradle.launcher.daemon\0net.runelite.client.RuneLite\0' > "$EXCLUDED/proc/100/cmdline"
set +e
excluded_output="$(run_fixture "$EXCLUDED")"
excluded_exit=$?
set -e
[[ "$excluded_exit" -eq 2 ]] || fail "Gradle daemon marker remained eligible"
assert_contains "$excluded_output" 'code=TARGET_MISSING' 'Gradle daemon marker is excluded'

NATIVE="$ROOT/native"
mkdir -p "$NATIVE/app/native/linux-x86_64" "$NATIVE/bin"
printf 'elf fixture\n' > "$NATIVE/app/native/linux-x86_64/libRemoteInput.so"
printf '{}\n' > "$NATIVE/app/native/linux-x86_64/remoteinput-manifest.json"
cat > "$NATIVE/bin/python3" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$NATIVE/bin/python3"
SC1667_ERRORS=0
native_output="$(PATH="$NATIVE/bin:$PATH" sc1667_check_native_pair "$PROJECT_DIR" "$NATIVE/app" '')"
assert_contains "$native_output" 'code=NATIVE_PAIR_VERIFIED' 'configured native pair is delegated to the manifest verifier'
SC1667_ERRORS=0
set +e
missing_native_output="$(sc1667_check_native_pair "$PROJECT_DIR" "$NATIVE/missing" '')"
set -e
assert_contains "$missing_native_output" 'code=NATIVE_LIBRARY_MISSING' 'missing native pair fails closed'

# Lifecycle commands act only on the recorded pid/start-time/boot identity and its
# process group. The fixture state root never overlaps the real checkout state.
LIFECYCLE_STATE="$ROOT/lifecycle-state"
mkdir -p "$LIFECYCLE_STATE"
setsid sleep 60 &
owned_pid=$!
owned_ticks="$(sc1667_read_start_ticks /proc "$owned_pid")"
owned_boot="$(< /proc/sys/kernel/random/boot_id)"
printf 'pid=%s\nstart_ticks=%s\nboot_id=%s\n' \
    "$owned_pid" "$owned_ticks" "$owned_boot" > "$LIFECYCLE_STATE/chromascape.state"
status_output="$(CHROMASCAPE_FEDORA_STATE_DIR="$LIFECYCLE_STATE" "$FEDORA_DIR/chromascape-fedora.sh" status)"
assert_contains "$status_output" 'status=RUNNING' 'owned process identity reports running'
stop_output="$(CHROMASCAPE_FEDORA_STATE_DIR="$LIFECYCLE_STATE" CHROMASCAPE_FEDORA_STOP_TIMEOUT_SECONDS=3 \
    "$FEDORA_DIR/chromascape-fedora.sh" stop)"
assert_contains "$stop_output" 'message=owned-instance-released' 'owned process group stops cleanly'
if kill -0 "$owned_pid" 2>/dev/null; then
    fail "owned lifecycle process survived stop"
fi

setsid sleep 60 &
unrelated_pid=$!
unrelated_boot="$(< /proc/sys/kernel/random/boot_id)"
printf 'pid=%s\nstart_ticks=1\nboot_id=%s\n' \
    "$unrelated_pid" "$unrelated_boot" > "$LIFECYCLE_STATE/chromascape.state"
stale_output="$(CHROMASCAPE_FEDORA_STATE_DIR="$LIFECYCLE_STATE" "$FEDORA_DIR/chromascape-fedora.sh" stop)"
assert_contains "$stale_output" 'message=stale-identity-not-signalled' 'stale identity is never signalled'
kill -0 "$unrelated_pid" 2>/dev/null || fail "stale identity stop killed an unrelated process"
[[ -r "$LIFECYCLE_STATE/chromascape.state.stale" ]] || fail "stale identity evidence was not preserved"
kill -TERM -- "-$unrelated_pid"
wait "$unrelated_pid" 2>/dev/null || true

exec 8> "$LIFECYCLE_STATE/lifecycle.lock"
flock -n 8
set +e
lock_output="$(CHROMASCAPE_FEDORA_STATE_DIR="$LIFECYCLE_STATE" "$FEDORA_DIR/chromascape-fedora.sh" status 2>&1)"
lock_exit=$?
set -e
[[ "$lock_exit" -eq 2 ]] || fail "concurrent lifecycle command was not refused"
assert_contains "$lock_output" 'message=another-lifecycle-command-is-running' 'lifecycle commands are serialized'
flock -u 8

grep -Fq '9>&- >> "$LOG_FILE"' "$FEDORA_DIR/chromascape-fedora.sh" || \
    fail "bootRun child would inherit the lifecycle lock descriptor"
pass 'bootRun child cannot retain the lifecycle lock descriptor'

printf 'SC1667_REGRESSIONS status=PASS cases=%d\n' "$PASS_COUNT"
