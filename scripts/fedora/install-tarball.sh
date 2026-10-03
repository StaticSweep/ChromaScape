#!/usr/bin/env bash
set -euo pipefail

# FEDORA-01 non-root installer for the ChromaScape Linux tarball. Ships inside the
# tarball as tools/install.sh; the same file is run from the checkout by the
# packaging regressions. It never uses sudo, never writes outside the requested
# prefix and bin directory, and never overwrites a running release: each version
# is installed under its own release directory and "current" is flipped last.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"

name="$FEDORAPKG_NAME_DEFAULT"
prefix="${HOME:+$HOME/.local/opt/chromascape}"
bin_dir="${HOME:+$HOME/.local/bin}"
version=""
tarball=""
rollback=""
dry_run=false

usage() {
    cat <<'EOF'
Usage: install.sh --tarball <archive> [options]

Options:
  --tarball <archive>  The ChromaScape Linux .tar.gz to install (required).
  --prefix <dir>       Install root (default: ~/.local/opt/chromascape).
  --bin-dir <dir>      Directory for the 'chromascape' shim (default: ~/.local/bin).
  --name <name>        Command name (default: chromascape).
  --version <version>  Override the version recorded in the archive manifest.
  --rollback <version> Flip 'current' to an already-installed release and exit.
  --dry-run            Print the resolved layout without changing anything.
  -h, --help           Show this help.

Exit codes: 0 success, 2 refused/blocked, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --tarball) tarball="${2:-}"; shift 2 ;;
        --prefix) prefix="${2:-}"; shift 2 ;;
        --bin-dir) bin_dir="${2:-}"; shift 2 ;;
        --name) name="${2:-}"; shift 2 ;;
        --version) version="${2:-}"; shift 2 ;;
        --rollback) rollback="${2:-}"; shift 2 ;;
        --dry-run) dry_run=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

if [[ -n "$rollback" ]]; then
    if [[ -z "$prefix" ]]; then
        fedorapkg_fail PREFIX_REQUIRED "a rollback needs an absolute --prefix"
        exit 2
    fi
    fedorapkg_rollback "$prefix" "$rollback"
    exit $?
fi

if [[ -z "$tarball" ]]; then
    usage >&2
    exit 64
fi
if [[ ! -r "$tarball" ]]; then
    fedorapkg_fail TARBALL_MISSING "archive is absent or unreadable: $tarball"
    exit 2
fi
if [[ "$prefix" != /* || "$bin_dir" != /* ]]; then
    fedorapkg_fail PREFIX_INVALID "--prefix and --bin-dir must be absolute paths"
    exit 2
fi

# Refuse to install over a prefix that carries a different application state.
state_file="$(fedorapkg_state_file "$prefix")"
if [[ -f "$state_file" ]]; then
    recorded_name="$(fedorapkg_manifest_field "$state_file" name)"
    if [[ -n "$recorded_name" && "$recorded_name" != "$name" ]]; then
        fedorapkg_fail PREFIX_OCCUPIED "prefix records application '$recorded_name', not '$name'"
        exit 2
    fi
fi

staging="$(mktemp -d "${TMPDIR:-/tmp}/chromascape-install.XXXXXX")"
cleanup() { rm -rf "$staging"; }
trap cleanup EXIT

tar -xzf "$tarball" -C "$staging"
top="$(find "$staging" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [[ -z "$top" || ! -f "$top/$FEDORAPKG_MANIFEST" ]]; then
    fedorapkg_fail TARBALL_LAYOUT "archive has no single top-level directory with $FEDORAPKG_MANIFEST"
    exit 2
fi

if [[ -z "$version" ]]; then
    version="$(fedorapkg_manifest_field "$top/$FEDORAPKG_MANIFEST" version)"
fi
if [[ -z "$version" ]]; then
    fedorapkg_fail VERSION_UNKNOWN "could not determine the package version"
    exit 2
fi

if [[ "$dry_run" == true ]]; then
    printf 'FEDORAPKG level=INFO code=DRY_RUN message=prefix=%s bin=%s version=%s source=%s\n' \
        "$prefix" "$bin_dir" "$version" "$top"
    exit 0
fi

fedorapkg_install_release "$prefix" "$bin_dir" "$name" "$version" "$top"
fedorapkg_emit PASS INSTALL_COMPLETE "installed $name $version; run '$(fedorapkg_bin_shim "$bin_dir" "$name")'"
