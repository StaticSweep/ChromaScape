#!/usr/bin/env bash
#
# SC-1668 bounded, read-only RemoteInput attachment diagnostic collector (Linux x86_64).
#
# Purpose
#   Collect the external evidence needed to identify which RemoteInput attachment stage failed
#   for one selected target process, without changing any host or target state.
#
# Guarantees
#   * Read-only: it never writes to /proc, /dev/shm, SELinux, ptrace policy, namespaces, firewall,
#     or file permissions. It never attaches a debugger and never dumps process memory.
#   * Bounded: it never dumps the complete target environment; only DISPLAY is extracted, and only
#     from the selected target. AVC/log excerpts are line-bounded.
#   * Redacted: the invoking user's home directory and the repository root are replaced with
#     placeholders in every retained file.
#   * Fail-closed: an invalid pid, library, or output directory is refused before anything runs.
#
# Usage
#   scripts/native/diagnose-remoteinput-linux.sh \
#       --pid <selected-pid> --library <absolute-libRemoteInput.so> --output <diagnostic-dir> \
#       [--log <chromascape boot log>]
#
# Exit codes: 0 on success, 2 on usage/validation error, non-zero on collection failure.
#
# Environment overrides (tests and non-standard hosts only):
#   DIAGNOSE_PROC_ROOT, DIAGNOSE_DEV_SHM, DIAGNOSE_OS_RELEASE, DIAGNOSE_SELINUXFS,
#   DIAGNOSE_AUDIT_LOG, DIAGNOSE_SELF_UID, DIAGNOSE_XPROP, DIAGNOSE_XWININFO, DIAGNOSE_READELF,
#   DIAGNOSE_LDD, DIAGNOSE_GETENFORCE

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"

PROC_ROOT="${DIAGNOSE_PROC_ROOT:-/proc}"
DEV_SHM_DIR="${DIAGNOSE_DEV_SHM:-/dev/shm}"
OS_RELEASE_FILE="${DIAGNOSE_OS_RELEASE:-/etc/os-release}"
SELINUXFS_DIR="${DIAGNOSE_SELINUXFS:-/sys/fs/selinux}"
AUDIT_LOG_FILE="${DIAGNOSE_AUDIT_LOG:-/var/log/audit/audit.log}"
SELF_UID="${DIAGNOSE_SELF_UID:-$(id -u)}"
XPROP_BIN="${DIAGNOSE_XPROP:-xprop}"
XWININFO_BIN="${DIAGNOSE_XWININFO:-xwininfo}"
READELF_BIN="${DIAGNOSE_READELF:-readelf}"
LDD_BIN="${DIAGNOSE_LDD:-ldd}"
GETENFORCE_BIN="${DIAGNOSE_GETENFORCE:-getenforce}"

TARGET_PID=""
LIBRARY=""
OUTPUT_DIR=""
LOG_FILE=""

usage() {
  cat <<'USAGE'
Usage:
  scripts/native/diagnose-remoteinput-linux.sh \
      --pid <selected-pid> --library <absolute-libRemoteInput.so> --output <diagnostic-dir> \
      [--log <chromascape boot log>]

Read-only, bounded RemoteInput attachment diagnostics. No host security setting is changed.
USAGE
}

die() {
  printf 'DIAGNOSE_ERROR %s\n' "$1" >&2
  exit 2
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --pid) shift; [ "$#" -gt 0 ] || die "--pid requires a value"; TARGET_PID="$1"; shift ;;
      --pid=*) TARGET_PID="${1#--pid=}"; shift ;;
      --library) shift; [ "$#" -gt 0 ] || die "--library requires a value"; LIBRARY="$1"; shift ;;
      --library=*) LIBRARY="${1#--library=}"; shift ;;
      --output) shift; [ "$#" -gt 0 ] || die "--output requires a value"; OUTPUT_DIR="$1"; shift ;;
      --output=*) OUTPUT_DIR="${1#--output=}"; shift ;;
      --log) shift; [ "$#" -gt 0 ] || die "--log requires a value"; LOG_FILE="$1"; shift ;;
      --log=*) LOG_FILE="${1#--log=}"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "unexpected argument '$1'" ;;
    esac
  done
  [ -n "$TARGET_PID" ] || die "--pid is required"
  [ -n "$LIBRARY" ] || die "--library is required"
  [ -n "$OUTPUT_DIR" ] || die "--output is required"
}

validate_pid() {
  [[ "$TARGET_PID" =~ ^[0-9]+$ ]] || die "--pid must be a positive integer"
  [ "$TARGET_PID" -gt 0 ] || die "--pid must be a positive integer"
  [ -d "$PROC_ROOT/$TARGET_PID" ] || die "pid $TARGET_PID does not exist under $PROC_ROOT"
  local owner
  owner="$(stat -c '%u' "$PROC_ROOT/$TARGET_PID" 2>/dev/null || true)"
  [ "$owner" = "$SELF_UID" ] ||
    die "pid $TARGET_PID is not owned by the current user (uid $SELF_UID)"
}

validate_library() {
  [ -f "$LIBRARY" ] || die "--library must be a regular file"
  [ -r "$LIBRARY" ] || die "--library must be readable"
  if command -v "$READELF_BIN" >/dev/null 2>&1; then
    local header class machine
    header="$("$READELF_BIN" -h "$LIBRARY" 2>/dev/null || true)"
    class="$(printf '%s\n' "$header" | awk '/Class:/ {print $2; exit}')"
    machine="$(printf '%s\n' "$header" | awk -F: '/Machine:/ {print $2; exit}' | sed 's/^ *//')"
    [[ "$class" == *ELF64* ]] || die "--library is not an ELF64 object"
    [[ "$machine" == *X86-64* || "$machine" == *x86-64* ]] ||
      die "--library is not an x86-64 object"
  fi
}

validate_output() {
  case "$OUTPUT_DIR" in
    /*) : ;;
    *) die "--output must be an absolute path" ;;
  esac
  [ "$OUTPUT_DIR" != "/" ] || die "--output must not be the filesystem root"
  local normalized
  normalized="$(readlink -m -- "$OUTPUT_DIR")"
  case "$normalized" in
    /|/home|/root|/proc|/sys|/dev|/etc|/usr|/var|"$REPO_ROOT")
      die "--output must be a narrow directory, not $normalized"
      ;;
  esac
  case "$REPO_ROOT" in
    "$normalized"|"$normalized"/*)
      die "--output must not be the repository root or an ancestor of it"
      ;;
  esac
  mkdir -p -- "$normalized"
  [ -d "$normalized" ] || die "--output could not be created"
}

redact_value() {
  local value="$1"
  value="${value//$HOME/<home>}"
  value="${value//$REPO_ROOT/<repo>}"
  printf '%s' "$value"
}

redact_file() {
  local file="$1"
  [ -f "$file" ] || return 0
  local tmp="$file.redacted.$$"
  sed -e "s#${HOME//#/\\#}#<home>#g" -e "s#${REPO_ROOT//#/\\#}#<repo>#g" "$file" > "$tmp"
  mv -f -- "$tmp" "$file"
}

write_file() {
  local name="$1"
  shift
  local path="$OUTPUT_DIR/$name"
  "$@" > "$path" 2>&1 || true
  redact_file "$path"
}

collect_environment() {
  {
    echo "# RemoteInput attachment environment"
    echo
    echo "## Host"
    if [ -r "$OS_RELEASE_FILE" ]; then
      grep -E '^(NAME|VERSION|ID|VERSION_ID)=' "$OS_RELEASE_FILE" || true
    fi
    echo "kernel: $(uname -r)"
    echo "arch: $(uname -m)"
    echo
    echo "## Target"
    echo "pid: $TARGET_PID"
    local exe base
    exe="$(readlink "$PROC_ROOT/$TARGET_PID/exe" 2>/dev/null || true)"
    base="$(basename -- "${exe:-unknown}")"
    echo "targetExecutableBasename: $(redact_value "$base")"
    local cmd0
    cmd0="$(tr '\0' '\n' < "$PROC_ROOT/$TARGET_PID/cmdline" 2>/dev/null | head -n 1 || true)"
    echo "targetCommandBasename: $(redact_value "$(basename -- "${cmd0:-unknown}")")"
    local display
    display="$(tr '\0' '\n' < "$PROC_ROOT/$TARGET_PID/environ" 2>/dev/null | grep -m1 '^DISPLAY=' || true)"
    echo "DISPLAY: ${display#DISPLAY=}"
    echo
    echo "## Library"
    echo "library: $(redact_value "$LIBRARY")"
    if command -v "$READELF_BIN" >/dev/null 2>&1; then
      "$READELF_BIN" -h "$LIBRARY" 2>/dev/null | grep -E 'Class:|Machine:|Type:' || true
    fi
    echo
    echo "## SELinux"
    collect_selinux_mode
  } > "$OUTPUT_DIR/environment.md"
  redact_file "$OUTPUT_DIR/environment.md"
}

collect_selinux_mode() {
  local mode="unknown"
  if command -v "$GETENFORCE_BIN" >/dev/null 2>&1; then
    mode="$("$GETENFORCE_BIN" 2>/dev/null || echo unknown)"
  elif [ -r "$SELINUXFS_DIR/enforce" ]; then
    case "$(cat "$SELINUXFS_DIR/enforce" 2>/dev/null)" in
      1) mode="Enforcing" ;;
      0) mode="Permissive" ;;
      *) mode="unknown" ;;
    esac
  fi
  echo "mode: $mode"
}

collect_maps() {
  local maps="$PROC_ROOT/$TARGET_PID/maps"
  [ -r "$maps" ] || { echo "target maps are unreadable" > "$OUTPUT_DIR/target-maps.log"; return 0; }
  grep -E 'libRemoteInput\.so|libjvm\.so|libawt\.so|libawt_xawt\.so|libX11|libGL|libxcb|libXext' \
    "$maps" > "$OUTPUT_DIR/target-maps.log" 2>/dev/null || true
  redact_file "$OUTPUT_DIR/target-maps.log"
}

collect_dev_shm() {
  {
    echo "# RemoteInput shared-memory objects (current state)"
    if [ -d "$DEV_SHM_DIR" ]; then
      ls -1 "$DEV_SHM_DIR" 2>/dev/null | grep -E "RemoteInput_.*${TARGET_PID}|RemoteInput_${TARGET_PID}" || echo "(none)"
    else
      echo "(dev/shm unavailable)"
    fi
  } > "$OUTPUT_DIR/dev-shm.log"
  redact_file "$OUTPUT_DIR/dev-shm.log"
}

collect_avcs() {
  {
    echo "# Recent SELinux AVCs relevant to the target (bounded)"
    if [ -r "$AUDIT_LOG_FILE" ]; then
      grep -E "avc:.*denied.*(pid=$TARGET_PID|libRemoteInput)" "$AUDIT_LOG_FILE" 2>/dev/null | tail -n 40 ||
        echo "(no matching AVCs)"
    else
      echo "(audit log is unreadable; no AVC evidence collected)"
    fi
  } > "$OUTPUT_DIR/selinux-avc.log"
  redact_file "$OUTPUT_DIR/selinux-avc.log"
}

collect_library() {
  write_file library-readelf-header.log "$READELF_BIN" -h "$LIBRARY"
  write_file library-readelf-dynamic.log "$READELF_BIN" -d "$LIBRARY"
  write_file library-ldd.log "$LDD_BIN" "$LIBRARY"
}

collect_x11_windows() {
  local out="$OUTPUT_DIR/x11-window-inventory.log"
  {
    echo "# X11 window inventory (DISPLAY only; no environment dump)"
    if ! command -v "$XPROP_BIN" >/dev/null 2>&1; then
      echo "(xprop unavailable)"
      return 0
    fi
    local clients
    clients="$("$XPROP_BIN" -root _NET_CLIENT_LIST 2>/dev/null || true)"
    echo "netClientList: $clients"
    local ids
    ids="$(printf '%s\n' "$clients" | grep -oE '0x[0-9a-fA-F]+' || true)"
    [ -n "$ids" ] || { echo "(no _NET_CLIENT_LIST entries)"; return 0; }
    local id
    while IFS= read -r id; do
      [ -n "$id" ] || continue
      echo
      echo "window $id"
      "$XPROP_BIN" -id "$id" _NET_WM_PID WM_CLASS _NET_WM_NAME WM_NAME _NET_WM_STATE 2>/dev/null || true
      if command -v "$XWININFO_BIN" >/dev/null 2>&1; then
        "$XWININFO_BIN" -id "$id" 2>/dev/null | grep -E 'Map State|Override Redirect State|Width|Height' || true
      fi
    done <<< "$ids"
  } > "$out"
  redact_file "$out"
}

collect_timeline() {
  local out="$OUTPUT_DIR/attachment-timeline.log"
  if [ -n "$LOG_FILE" ] && [ -r "$LOG_FILE" ]; then
    grep -E 'RemoteInput attachment|RemoteInput injection|INJECTION_TIMEOUT|EIOS_' "$LOG_FILE" 2>/dev/null |
      tail -n 200 > "$out" || true
  else
    echo "(no ChromaScape boot log supplied; timeline not collected)" > "$out"
  fi
  redact_file "$out"
}

stage_summary() {
  local summary="$OUTPUT_DIR/stage-summary.json"
  local library_mapped="false" jvm_mapped="false" awt_mapped="false" xawt_mapped="false"
  local shm_present="false" target_windows="false" recognized_window="false"
  if [ -s "$OUTPUT_DIR/target-maps.log" ]; then
    grep -q 'libRemoteInput\.so' "$OUTPUT_DIR/target-maps.log" && library_mapped="true"
    grep -q 'libjvm\.so' "$OUTPUT_DIR/target-maps.log" && jvm_mapped="true"
    grep -q 'libawt\.so' "$OUTPUT_DIR/target-maps.log" && awt_mapped="true"
    grep -q 'libawt_xawt\.so' "$OUTPUT_DIR/target-maps.log" && xawt_mapped="true"
  fi
  grep -qE "RemoteInput_${TARGET_PID}" "$OUTPUT_DIR/dev-shm.log" 2>/dev/null && shm_present="true"
  if [ -s "$OUTPUT_DIR/x11-window-inventory.log" ]; then
    grep -q "_NET_WM_PID(CARDINAL) = ${TARGET_PID}$" "$OUTPUT_DIR/x11-window-inventory.log" &&
      target_windows="true"
    grep -qE 'net-runelite-client-RuneLite|jagexappletviewer' "$OUTPUT_DIR/x11-window-inventory.log" &&
      recognized_window="true"
  fi
  local first_failing="unknown"
  if [ "$library_mapped" != "true" ]; then
    first_failing="library_not_mapped"
  elif [ "$jvm_mapped" != "true" ]; then
    first_failing="jvm_not_found"
  elif [ "$xawt_mapped" != "true" ]; then
    first_failing="libawt_xawt_not_loaded"
  elif [ "$target_windows" != "true" ]; then
    first_failing="no_target_owned_window"
  elif [ "$recognized_window" != "true" ]; then
    first_failing="window_class_not_recognized"
  elif [ "$shm_present" != "true" ]; then
    first_failing="control_center_rendezvous_absent"
  else
    first_failing="rendezvous_present"
  fi
  cat > "$summary" <<EOF
{
  "schemaVersion": 1,
  "pid": $TARGET_PID,
  "libraryMapped": $library_mapped,
  "jvmMapped": $jvm_mapped,
  "libawtMapped": $awt_mapped,
  "libawtXawtMapped": $xawt_mapped,
  "targetOwnedWindowsPresent": $target_windows,
  "recognizedWindowClassPresent": $recognized_window,
  "controlCenterRendezvousPresent": $shm_present,
  "firstLikelyFailingStage": "$first_failing"
}
EOF
  redact_file "$summary"
}

run_diagnostics() {
  mkdir -p -- "$OUTPUT_DIR"
  collect_environment
  collect_maps
  collect_dev_shm
  collect_avcs
  collect_library
  collect_x11_windows
  collect_timeline
  stage_summary
  printf 'DIAGNOSE status=OK output=%s\n' "$(redact_value "$OUTPUT_DIR")"
}

main() {
  parse_args "$@"
  validate_pid
  validate_library
  validate_output
  run_diagnostics
}

if [ "${BASH_SOURCE[0]}" == "$0" ]; then
  main "$@"
fi
