#!/usr/bin/env bash
set -euo pipefail

# FEDORA-01 non-root uninstaller for the ChromaScape Linux tarball. Ships inside
# the tarball as tools/uninstall.sh. It removes only the recorded install layout
# and then proves the prefix, the bin shim and every release are gone, so a
# certification run can assert that no package file was left behind.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"

name="$FEDORAPKG_NAME_DEFAULT"
prefix="${HOME:+$HOME/.local/opt/chromascape}"
bin_dir="${HOME:+$HOME/.local/bin}"

usage() {
    cat <<'EOF'
Usage: uninstall.sh [options]

Options:
  --prefix <dir>   Install root to remove (default: ~/.local/opt/chromascape).
  --bin-dir <dir>  Directory holding the command shim (default: ~/.local/bin).
  --name <name>    Command name (default: chromascape).
  -h, --help       Show this help.

Exit codes: 0 success, 2 refused/blocked, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --prefix) prefix="${2:-}"; shift 2 ;;
        --bin-dir) bin_dir="${2:-}"; shift 2 ;;
        --name) name="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

if [[ "$prefix" != /* || "$bin_dir" != /* ]]; then
    fedorapkg_fail PREFIX_INVALID "--prefix and --bin-dir must be absolute paths"
    exit 2
fi

fedorapkg_uninstall "$prefix" "$bin_dir" "$name"

leftovers=0
if [[ -e "$prefix" ]]; then
    fedorapkg_emit ERROR UNINSTALL_LEFTOVER "install prefix still exists: $prefix"
    leftovers=$((leftovers + 1))
fi
shim="$(fedorapkg_bin_shim "$bin_dir" "$name")"
if [[ -e "$shim" ]]; then
    fedorapkg_emit ERROR UNINSTALL_LEFTOVER "bin shim still exists: $shim"
    leftovers=$((leftovers + 1))
fi
if (( leftovers > 0 )); then
    exit 2
fi
fedorapkg_emit PASS UNINSTALL_COMPLETE "removed $name from $prefix and $bin_dir"
