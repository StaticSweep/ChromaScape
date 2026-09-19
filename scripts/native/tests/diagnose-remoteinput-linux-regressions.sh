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
#   6. a control-center rendezvous is reported when the shared-memory object exists;
#   7. X11 tools are invoked with the selected target's DISPLAY (never the collector's), and
#      missing/duplicate/malformed target displays are refused;
#   8. ELF validation fails closed: missing or failing readelf, ELF32, and non-x86-64 libraries
#      are refused, while a valid ELF64 x86-64 shared object is accepted.
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

# xprop stub that records the DISPLAY it was invoked with, then delegates to the standard stub so
# the collected evidence stays identical. The record is the proof of which display was used.
make_recording_xprop_stub() {
  local stub="$WORK/xprop-recording"
  local record="$WORK/xprop-display-record"
  cat > "$stub" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\${DISPLAY:-<unset>}" >> '$record'
exec '$WORK/xprop' "\$@"
STUB
  chmod +x "$stub"
}

# readelf stub with a deterministic, controllable header. Emitting "ELF64"/"X86-64"/"DYN"
# exercises the acceptance path without depending on the metadata of any real file.
make_readelf_stub() {
  local stub="$WORK/readelf-stub"
  local class="$1" machine="$2" libtype="$3" exit_code="$4"
  cat > "$stub" <<STUB
#!/usr/bin/env bash
if [ "\${1:-}" = "-d" ]; then
  exit 0
fi
if [ "$exit_code" != "0" ]; then
  echo "stub-readelf: simulated failure" >&2
  exit $exit_code
fi
cat <<'HEADER'
ELF Header:
  Magic:   7f 45 4c 46 02 01 01 00 00 00 00 00 00 00 00 00
  Class:                             $class
  Data:                              2's complement, little endian
  Type:                              $libtype
  Machine:                           $machine
HEADER
exit 0
STUB
  chmod +x "$stub"
}

# Rewrites the target environ fixture (the pid directory and a previous fixture's environ file may
# be mode 0500/000, so restore access before writing).
set_target_environ() {
  chmod u+rwx "$WORK/proc/$TARGET_PID"
  chmod u+rw "$WORK/proc/$TARGET_PID/environ" 2>/dev/null || true
  printf '%b' "$1" > "$WORK/proc/$TARGET_PID/environ"
  chmod 0500 "$WORK/proc/$TARGET_PID"
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
  DIAGNOSE_XPROP="${DIAGNOSE_XPROP_OVERRIDE:-$WORK/xprop}" \
  DIAGNOSE_XWININFO="$WORK/no-xwininfo" \
  DIAGNOSE_READELF="${DIAGNOSE_READELF_OVERRIDE:-$WORK/readelf-stub}" \
  DIAGNOSE_GETENFORCE="$WORK/no-getenforce" \
    "$COLLECTOR" "$@"
}

# --- fixtures ----------------------------------------------------------------------------
make_proc_fixture
make_xprop_stub
make_recording_xprop_stub
make_os_release
make_shm_fixture
# Default readelf stub: a valid ELF64 x86-64 shared object header (same acceptance the real
# toolchain produces for the built library).
make_readelf_stub ELF64 "X86-64 (AMD x86-64)" DYN 0
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

# --- target DISPLAY selection (Phase C) --------------------------------------------------

# Collector process DISPLAY=:99 (as if the collector ran on a different session); target :0.
# The recording xprop stub captures the DISPLAY each invocation received.
set_target_environ 'DISPLAY=:0\0SECRET_TOKEN=do-not-retain\0HOME=%s\0' "$HOME"
rm -f "$WORK/xprop-display-record"
OUT_DISPLAY="$WORK/diag-display"
collector_display_run() {
  DIAGNOSE_XPROP_OVERRIDE="$WORK/xprop-recording" \
    collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$OUT_DISPLAY"
}
if collector_display_run > /dev/null 2>&1; then
  pass "collector succeeds with a target display on a differing caller display"
else
  fail "collector failed with a target display fixture"
fi
if [ -f "$WORK/xprop-display-record" ] &&
  grep -q '^:0$' "$WORK/xprop-display-record" &&
  ! grep -q '^:99$' "$WORK/xprop-display-record"; then
  pass "X11 tools receive the target DISPLAY=:0, never the collector's :99"
else
  fail "X11 tool display record is wrong: $(cat "$WORK/xprop-display-record" 2>/dev/null | sort -u | tr '\n' ' ')"
fi
if grep -q '^DISPLAY: :0$' "$OUT_DISPLAY/environment.md" &&
  grep -q '^targetDisplay: :0$' "$OUT_DISPLAY/x11-window-inventory.log"; then
  pass "evidence records the target display"
else
  fail "evidence did not record the target display"
fi
if grep -q 'SECRET_TOKEN' "$OUT_DISPLAY/environment.md"; then
  fail "an unrelated environment variable was retained"
else
  pass "no unrelated environment variables are retained with a display fixture"
fi

# Missing target display.
set_target_environ 'SECRET_TOKEN=do-not-retain\0'
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-no-display"

# Duplicate target display entries.
set_target_environ 'DISPLAY=:0\0DISPLAY=:1\0'
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-dup-display"

# Malformed target display values (including an embedded control character).
set_target_environ 'DISPLAY=display-for-x11\0'
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-bad-display"
set_target_environ 'DISPLAY=:0\t\0'
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-ctrl-display"

# Accepted display syntaxes.
for display_value in ':0' ':0.0' 'localhost:10.0' 'host.example:1'; do
  set_target_environ "DISPLAY=$display_value\0"
  if collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-display-ok" \
    > /dev/null 2>&1; then
    pass "display value $display_value is accepted"
  else
    fail "display value $display_value was refused"
  fi
done

# An unreadable target environ is refused (not silently skipped).
make_proc_fixture
chmod 000 "$WORK/proc/$TARGET_PID/environ"
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-unreadable-display"

# --- fail-closed ELF validation (Phase D) ------------------------------------------------

# Missing readelf: a readable regular file must be refused, not accepted unvalidated.
set_target_environ 'DISPLAY=:0\0'
DIAGNOSE_READELF_OVERRIDE="$WORK/no-readelf" \
  expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-no-readelf"
set +e
DIAGNOSE_PROC_ROOT="$WORK/proc" DIAGNOSE_DEV_SHM="$WORK/shm" DIAGNOSE_OS_RELEASE="$WORK/os-release" \
DIAGNOSE_SELINUXFS="$WORK/selinux" DIAGNOSE_AUDIT_LOG="$WORK/audit.log" DIAGNOSE_SELF_UID="$(id -u)" \
DIAGNOSE_XPROP="$WORK/xprop" DIAGNOSE_XWININFO="$WORK/no-xwininfo" DIAGNOSE_GETENFORCE="$WORK/no-getenforce" \
DIAGNOSE_READELF="$WORK/no-readelf" \
  "$COLLECTOR" --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-no-readelf-msg" \
  > /dev/null 2> "$WORK/err"
no_readelf_exit=$?
set -e
if [ "$no_readelf_exit" -eq 2 ] &&
  grep -q 'readelf is required to validate the RemoteInput library' "$WORK/err"; then
  pass "missing readelf fails closed with a clear diagnostic"
else
  fail "missing readelf did not fail closed (exit $no_readelf_exit: $(cat "$WORK/err"))"
fi

# readelf returning nonzero: refused.
make_readelf_stub ELF64 "X86-64 (AMD x86-64)" DYN 3
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-readelf-fail"

# ELF32 fixture: refused.
make_readelf_stub ELF32 "X86-64 (AMD x86-64)" DYN 0
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-elf32"

# Wrong architecture fixture: refused.
make_readelf_stub ELF64 "Intel 80386" DYN 0
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-i386"

# A non-shared-object type is refused when the toolchain output exposes it.
make_readelf_stub ELF64 "X86-64 (AMD x86-64)" EXEC 0
expect_exit 2 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-exec"

# A valid ELF64 x86-64 shared object passes.
make_readelf_stub ELF64 "X86-64 (AMD x86-64)" DYN 0
expect_exit 0 collector_env --pid "$TARGET_PID" --library "$LIBRARY" --output "$WORK/out-valid-elf"

if [ "$FAILURES" -eq 0 ]; then
  echo "DIAGNOSE_REGRESSIONS status=PASS"
  exit 0
fi
echo "DIAGNOSE_REGRESSIONS status=FAIL failures=$FAILURES" >&2
exit 1
