#!/usr/bin/env bash
set -euo pipefail

# FEDORA-03 live qualification fixture regressions.
#
# The live matrix itself needs a packaged install, a running RuneLite/Micro6
# client, a database and a native pair, so it is executed on the qualification
# host by scripts/fedora/certify-live-recording-debugger.sh. These regressions
# exercise every part that can run without those prerequisites: the fail-closed
# evidence contract through its real CLI, the dependency-free WebSocket probe
# against a closed port, and the harness's own argument refusals.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
FEDORA_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
PROJECT_DIR="$(cd "$FEDORA_DIR/../.." && pwd -P)"
EVIDENCE="$PROJECT_DIR/scripts/qualification/fedora03_evidence.py"
WS_PROBE="$PROJECT_DIR/scripts/qualification/ws_probe.py"
HARNESS="$FEDORA_DIR/certify-live-recording-debugger.sh"

passed=0
failed=0

ok() { passed=$((passed + 1)); printf 'PASS: %s\n' "$1"; }
bad() { failed=$((failed + 1)); printf 'FAIL: %s\n' "$1"; }

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [[ "$expected" == "$actual" ]]; then ok "$label"; else bad "$label (expected '$expected', got '$actual')"; fi
}

assert_contains() {
    if printf '%s' "$2" | grep -qF -- "$1"; then ok "$3"; else bad "$3 (missing '$1' in: ${2//$'\n'/ | })"; fi
}

assert_exit() {
    local expected="$1" actual="$2" label="$3"
    if [[ "$expected" == "$actual" ]]; then ok "$label"; else bad "$label (expected exit $expected, got $actual)"; fi
}

work="$(mktemp -d "${TMPDIR:-/tmp}/fedora03-live-regressions.XXXXXX")"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

revision="$(git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null || printf '0%.0s' {1..40})"
record="$work/evidence.json"

if bash -n "$HARNESS"; then ok "harness parses (bash -n)"; else bad "harness has a syntax error"; fi
if python3 -B -m py_compile "$EVIDENCE" "$WS_PROBE"; then ok "python helpers compile"; else bad "python helpers do not compile"; fi

# --- Evidence contract through the real CLI ---------------------------------

init_out="$(python3 -B "$EVIDENCE" init --host-id fedora-fixture --revision "$revision" --output "$record" 2>&1)"
assert_contains "status=INITIALIZED" "$init_out" "fresh record is initialized fail-closed"

partial_out="$(python3 -B "$EVIDENCE" verify "$record" 2>&1)"
assert_contains "status=VALID" "$partial_out" "partial record is an honest VALID matrix"

set +e
complete_out="$(python3 -B "$EVIDENCE" verify --require-complete "$record" 2>&1)"
complete_status=$?
set -e
assert_exit 2 "$complete_status" "release gate refuses a partial record"
assert_contains "required PASS" "$complete_out" "refusal names the unexecuted checks"

set +e
unknown_out="$(python3 -B "$EVIDENCE" record --file "$record" --check recordingInvented \
    --status PASS --evidence "fabricated" 2>&1)"
unknown_status=$?
set -e
assert_exit 2 "$unknown_status" "unknown check name is refused"
assert_contains "not defined for FEDORA-03" "$unknown_out" "refusal names the unknown check"

python3 -B "$EVIDENCE" version --file "$record" --name os --value "Fedora Linux 44" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name arch --value "x86_64" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name java --value "openjdk 25.0.4.1" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name client --value "microbot-1.8.9.3.jar" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name packageRevision --value "$revision" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name packageSha256 --value "$(printf 'b%.0s' {1..64})" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name packageManifestSha256 --value "$(printf 'c%.0s' {1..64})" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name nativePairSha256 --value "$(printf 'd%.0s' {1..64})" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name nativeLibrarySha256 --value "$(printf 'e%.0s' {1..64})" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name nativeManifestSha256 --value "$(printf 'f%.0s' {1..64})" >/dev/null
python3 -B "$EVIDENCE" version --file "$record" --name nativePatchIdentity --value "sha256:$(printf '1%.0s' {1..64})" >/dev/null
while IFS= read -r check; do
    python3 -B "$EVIDENCE" record --file "$record" --check "$check" --status PASS \
        --evidence "fixture observation for $check" >/dev/null
done < <(python3 -c '
import sys
sys.path.insert(0, sys.argv[1])
from scripts.qualification import fedora03_evidence
print("\n".join(fedora03_evidence.CHECKS))
' "$PROJECT_DIR")

complete_out="$(python3 -B "$EVIDENCE" verify --require-complete "$record" 2>&1)"
assert_contains "status=COMPLETE" "$complete_out" "release gate accepts a fully executed matrix"

# --- WebSocket probe --------------------------------------------------------

closed_port="$(( 23000 + RANDOM % 1000 ))"
set +e
probe_out="$(python3 -B "$WS_PROBE" --url "ws://127.0.0.1:$closed_port/ws/viewport" --seconds 1 --timeout 1 2>&1)"
probe_status=$?
set -e
assert_exit 2 "$probe_status" "probe exits 2 when the endpoint is closed"
assert_contains "status=FAILED" "$probe_out" "probe reports FAILED, never a silent zero"

# --- Harness argument refusals ----------------------------------------------

package_fixture="$work/package.tar.gz"
tar -czf "$package_fixture" -T /dev/null

set +e
missing_out="$(bash "$HARNESS" 2>&1)"
missing_status=$?
set -e
assert_exit 2 "$missing_status" "harness refuses to run without a package"
assert_contains "PACKAGE_MISSING" "$missing_out" "refusal names the missing package"

set +e
usage_out="$(bash "$HARNESS" --bogus-option 2>&1)"
usage_status=$?
set -e
assert_exit 64 "$usage_status" "harness exits 64 on an unknown option"
assert_contains "Usage:" "$usage_out" "harness prints usage on an unknown option"

empty_pair="$work/empty-pair"
mkdir -p "$empty_pair"
set +e
pair_out="$(bash "$HARNESS" --package "$package_fixture" --native-pair-dir "$empty_pair" 2>&1)"
pair_status=$?
set -e
assert_exit 2 "$pair_status" "harness refuses a missing native pair"
assert_contains "NATIVE_PAIR_MISSING" "$pair_out" "refusal names the missing native pair"

bogus_pair="$work/bogus-pair"
mkdir -p "$bogus_pair"
printf 'not a library' > "$bogus_pair/libRemoteInput.so"
printf '{}' > "$bogus_pair/remoteinput-manifest.json"
set +e
invalid_out="$(bash "$HARNESS" --package "$package_fixture" --native-pair-dir "$bogus_pair" 2>&1)"
invalid_status=$?
set -e
assert_exit 2 "$invalid_status" "harness refuses an invalid native pair"
assert_contains "NATIVE_PAIR_INVALID" "$invalid_out" "refusal names the invalid native pair"

printf '\nFEDORA03_REGRESSIONS passed=%d failed=%d\n' "$passed" "$failed"
if (( failed > 0 )); then
    exit 1
fi
