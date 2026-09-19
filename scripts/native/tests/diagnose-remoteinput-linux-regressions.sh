#!/usr/bin/env bash
#
# SC-1668 bounded RemoteInput diagnostic collector regressions.
#
# Exercises `scripts/native/diagnose-remoteinput-linux.sh` against synthetic /proc, /dev/shm, X11,
# and OS fixtures. It never touches the real /proc, /dev/shm, or a real target, and it proves:
#   1. every invalid invocation is refused with exit code 2 before collection;
#   2. the collector is read-only against the target proc fixture;
#   3. the retained environment extracts only DISPLAY (no unrelated variables);
#   4. usernames and repository paths are redacted from every retained file;
#   5. the machine-readable stage summary is produced and identifies the failing stage;
#   6. a control-center rendezvous is reported when the shared-memory object exists.
#
# Exit: 0 when every case passed, 1 otherwise.

set -u

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
COLLECTOR="$REPO_ROOT/scripts/native/diagnose-remoteinput-linux.sh"

WORK="$(mktemp -d)"
trap 'chmod -R u+rwX "$WORK" 2>/dev/null || true; rm -rf -- "$WORK"' EXIT

FAILURES=0
TARGET_PID=12345
LIBRARY="/bin/ls"

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

expect_exit() { # $1 = expected exit, rest = command
  local expected="$1"
  shift
  set +e
  "$@" > "$WORK/out" 2> "$WORK/err"
  local actual=$?
  set -e
  if [ "$actual" -eq "$expected" ]; then
    pass "exit $expected: $*"
  else
    fail "expected exit $expected but got $actual: $* ($(cat "$WORK/err"))"
  fi
}

make_proc_fixture() {
  local proc="$WORK/proc"
  chmod -R u+rwX "$proc" 2>/dev/null || true
  rm -rf -- "$proc"
  mkdir -p "$proc/$TARGET_PID"
  printf 'java\0-cp\0%s/client.jar\0net.runelite.client.RuneLite\0' "$HOME" \
    > "$proc/$TARGET_PID/cmdline"
  printf 'DISPLAY=:0\0SECRET_TOKEN=do-not-retain\0HOME=%s\0' "$HOME" \
    > "$proc/$TARGET_PID/environ"
  printf '7f0000000000-7f0000100000 r-xp 00000000 08:01 1 %s/libRemoteInput.so\n' "$REPO_ROOT" \
    > "$proc/$TARGET_PID/maps"
  printf '7f0000200000-7f0000900000 r-xp 00000000 08:01 2 /usr/lib/jvm/libjvm.so\n' \
    >> "$proc/$TARGET_PID/maps"
  printf '7f0000900000-7f0000a00000 r-xp 00000000 08:01 3 /usr/lib/libawt.so\n' \
    >> "$proc/$TARGET_PID/maps"
  printf '7f0000a00000-7f0000b00000 r-xp 00000000 08:01 4 /usr/lib/libawt_xawt.so\n' \
    >> "$proc/$TARGET_PID/maps"
  chmod 0500 "$proc/$TARGET_PID"
}

make_xprop_stub() {
  local stub="$WORK/xprop"
  cat > "$stub" <<'STUB'
#!/usr/bin/env bash
if [ "${1:-}" = "-root" ]; then
  printf '_NET_CLIENT_LIST(WINDOW): window id # 0x1a00001, 0x1a00002\n'
  exit 0
fi
if [ "${1:-}" = "-id" ]; then
  id="$2"
  shift 2
  case "$id" in
    0x1a00001)
      printf '_NET_WM_PID(CARDINAL) = 12345\n'
      printf 'WM_CLASS(STRING) = "runelite", "net-runelite-client-RuneLite"\n'
      printf '_NET_WM_NAME(UTF8_STRING) = "RuneLite"\n'
      ;;
    0x1a00002)
      printf '_NET_WM_PID(CARDINAL) = 99999\n'
      printf 'WM_CLASS(STRING) = "other", "Other"\n'
      ;;
  esac
  exit 0
fi
exit 0
STUB
  chmod +x "$stub"
}

make_os_release() {
  printf 'NAME="Fedora Linux"\nVERSION="44 (Workstation Edition)"\nID=fedora\nVERSION_ID=44\n' \
    > "$WORK/os-release"
}

make_shm_fixture() {
  rm -rf -- "$WORK/shm"
  mkdir -p "$WORK/shm"
}

collector_env() {
  DIAGNOSE_PROC_ROOT="$WORK/proc" \
  DIAGNOSE_DEV_SHM="$WORK/shm" \
  DIAGNOSE_OS_RELEASE="$WORK/os-release" \
  DIAGNOSE_SELINUXFS="$WORK/selinux" \
  DIAGNOSE_AUDIT_LOG="$WORK/audit.log" \
  DIAGNOSE_SELF_UID="$(id -u)" \
  DIAGNOSE_XPROP="$WORK/xprop" \
  DIAGNOSE_XWININFO="$WORK/no-xwininfo" \
  DIAGNOSE_GETENFORCE="$WORK/no-getenforce" \
    "$COLLECTOR" "$@"
}

# --- fixtures ----------------------------------------------------------------------------
make_proc_fixture
make_xprop_stub
make_os_release
make_shm_fixture
printf 'type=AVC msg=audit(1): avc: denied { ptrace } for pid=%s\n' "$TARGET_PID" > "$WORK/audit.log"

# --- validation --------------------------------------------------------------------------
expect_exit 2 "$COLLECTOR" --library "$LIBRARY" --output "$WORK/out-missing-pid"
expect_exit 2 "$COLLECTOR" --pid "$TARGET_PID" --output "$WORK/out-missing-library"
expect_exit 2 "$COLLECTOR" --pid "$TARGET_PID" --library "$LIBRARY"
expect_exit 2 collector_env --pid abc --library "$LIBRARY" --output "$WORK/out-bad-pid"
expect_exit 2 collector_env --pid 99999999 --library "$LIBRARY" --output "$WORK/out-no-pid"
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$WORK/not-a-file" --output "$WORK/out-bad-lib"
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output relative-dir
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output /
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$REPO_ROOT"
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$(dirname "$REPO_ROOT")"

# A pid owned by a different uid is refused: force the comparison to a foreign uid.
set +e
DIAGNOSE_PROC_ROOT="$WORK/proc" DIAGNOSE_SELF_UID=999999 \
  "$COLLECTOR" --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-foreign" \
  > "$WORK/out" 2> "$WORK/err"
foreign_exit=$?
set -e
if [ "$foreign_exit" -eq 2 ] && grep -q 'not owned by the current user' "$WORK/err"; then
  pass "foreign-uid pid is refused"
else
  fail "foreign-uid pid was not refused (exit $foreign_exit)"
fi

# --- collection, redaction, privacy, stage summary ---------------------------------------
before_hash="$(find "$WORK/proc" -type f -exec sha256sum {} + | sort | sha256sum)"

OUT="$WORK/diag"
if collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$OUT" \
  > "$WORK/collector.out" 2>&1; then
  pass "collector succeeds on a valid fixture"
else
  fail "collector failed on a valid fixture: $(cat "$WORK/collector.out")"
fi

after_hash="$(find "$WORK/proc" -type f -exec sha256sum {} + | sort | sha256sum)"
if [ "$before_hash" = "$after_hash" ]; then
  pass "collector is read-only against the target proc fixture"
else
  fail "collector modified the target proc fixture"
fi

for expected in environment.md target-maps.log dev-shm.log selinux-avc.log \
  library-readelf-header.log library-readelf-dynamic.log library-ldd.log \
  x11-window-inventory.log attachment-timeline.log stage-summary.json; do
  if [ -f "$OUT/$expected" ]; then
    pass "collected $expected"
  else
    fail "missing collected file $expected"
  fi
done

if grep -q 'DISPLAY: :0' "$OUT/environment.md"; then
  pass "DISPLAY is retained"
else
  fail "DISPLAY was not retained"
fi
if grep -q 'SECRET_TOKEN' "$OUT/environment.md"; then
  fail "an unrelated environment variable was retained"
else
  pass "no unrelated environment variables are retained"
fi

leaked=false
if grep -rqF "$HOME" "$OUT"; then leaked=true; fi
if grep -rqF "$REPO_ROOT" "$OUT"; then leaked=true; fi
if [ "$leaked" = true ]; then
  fail "a retained file leaked the home directory or repository root"
else
  pass "home directory and repository root are redacted"
fi
if grep -rq '<repo>' "$OUT"; then
  pass "redaction placeholder is present"
else
  fail "redaction placeholder is absent"
fi

if grep -q '"controlCenterRendezvousPresent": false' "$OUT/stage-summary.json" &&
  grep -q '"firstLikelyFailingStage": "control_center_rendezvous_absent"' "$OUT/stage-summary.json"; then
  pass "stage summary identifies the missing control-center rendezvous"
else
  fail "stage summary did not identify the missing rendezvous: $(cat "$OUT/stage-summary.json")"
fi

make_proc_fixture
: > "$WORK/shm/RemoteInput_$TARGET_PID"
OUT2="$WORK/diag-rendezvous"
if collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$OUT2" > /dev/null 2>&1 &&
  grep -q '"controlCenterRendezvousPresent": true' "$OUT2/stage-summary.json"; then
  pass "stage summary reports a present rendezvous"
else
  fail "stage summary did not report the present rendezvous"
fi

if [ "$FAILURES" -eq 0 ]; then
  echo "DIAGNOSE_REGRESSIONS status=PASS"
  exit 0
fi
echo "DIAGNOSE_REGRESSIONS status=FAIL failures=$FAILURES" >&2
exit 1
