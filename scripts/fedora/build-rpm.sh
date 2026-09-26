#!/usr/bin/env bash
set -euo pipefail

# FEDORA-01 RPM builder. Wraps rpmbuild for an already-built distribution
# tarball (the same tarball FEDORA-02 certifies) so the RPM is a thin
# system-installation view of one vetted payload rather than a second build.
#
# rpmbuild is not assumed on the host. When it is absent, the script falls back
# to a rootless podman Fedora container (no sudo, no host package changes). If
# neither is available the script fails closed with an actionable message; it
# never silently skips the RPM and never claims a package it did not produce.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"

tarball=""
version=""
output_dir="$PROJECT_DIR/build/packages"
image="${FEDORA_RPM_IMAGE:-registry.fedoraproject.org/fedora:44}"
engine="${FEDORA_CONTAINER_ENGINE:-podman}"

usage() {
    cat <<'EOF'
Usage: build-rpm.sh --tarball <archive> [options]

Options:
  --tarball <archive>   Distribution tarball to wrap (required).
  --version <version>   Package version (default: derived from build.gradle.kts).
  --output-dir <dir>    Where the .rpm is written (default: build/packages).
  --image <ref>         Container image for the rpmbuild fallback
                        (default: $FEDORA_RPM_IMAGE or fedora:44).
  -h, --help            Show this help.

Exit codes: 0 success, 2 refused/blocked, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --tarball) tarball="${2:-}"; shift 2 ;;
        --version) version="${2:-}"; shift 2 ;;
        --output-dir) output_dir="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

if [[ -z "$tarball" || ! -r "$tarball" ]]; then
    fedorapkg_fail TARBALL_MISSING "a readable --tarball is required"
    exit 2
fi
if [[ -z "$version" ]]; then
    version="$(fedorapkg_project_version "$PROJECT_DIR")"
fi
rpm_version="$(fedorapkg_rpm_version "$version")"
top="$FEDORAPKG_NAME_DEFAULT-$version-linux-x86_64"
tarball_name="$(basename "$tarball")"

native_define=()
if tar -xzf "$tarball" -O "$top/$FEDORAPKG_MANIFEST" 2>/dev/null | grep -q '"nativePayloadIncluded": true'; then
    native_define+=(--define "with_native_payload 1")
fi

mkdir -p "$output_dir"
work="$(mktemp -d "${TMPDIR:-/tmp}/chromascape-rpm.XXXXXX")"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT
mkdir -p "$work/SOURCES" "$work/SPECS" "$work/RPMS" "$work/BUILD" "$work/BUILDROOT"
cp "$tarball" "$work/SOURCES/$tarball_name"

sed -e "s/@RPMVERSION@/$rpm_version/g" \
    -e "s/@TOP@/$top/g" \
    -e "s/@TARBALL@/$tarball_name/g" \
    "$PROJECT_DIR/packaging/fedora/chromascape.spec.in" > "$work/SPECS/chromascape.spec"

run_rpmbuild() {
    rpmbuild -bb \
        --define "_topdir $work" \
        --define "_sourcedir $work/SOURCES" \
        --define "_rpmdir $work/RPMS" \
        "${native_define[@]}" \
        "$work/SPECS/chromascape.spec"
}

if command -v rpmbuild >/dev/null 2>&1; then
    run_rpmbuild
elif command -v "$engine" >/dev/null 2>&1; then
    fedorapkg_emit INFO RPM_VIA_CONTAINER "rpmbuild absent; using $engine image $image"
    # Build the in-container command as a single quoted string. A naive
    # `printf '%q ' "${array[@]}"` expands to a literal '' argument for an empty
    # array, which rpmbuild then reads as a source path; every argument is
    # therefore quoted individually and only present arguments are appended.
    inner="dnf -y install rpm-build >/dev/null && rpmbuild"
    build_args=(-bb --define "_topdir /work" --define "_sourcedir /work/SOURCES" --define "_rpmdir /work/RPMS")
    build_args+=("${native_define[@]}")
    build_args+=(/work/SPECS/chromascape.spec)
    for argument in "${build_args[@]}"; do
        inner+=" $(printf '%q' "$argument")"
    done
    "$engine" run --rm -v "$work:/work:z" -w /work "$image" bash -lc "$inner"
else
    fedorapkg_fail RPM_TOOLCHAIN_MISSING \
        "neither rpmbuild nor $engine is available; install rpm-build or a container engine"
    exit 2
fi

built="$(find "$work/RPMS" -name '*.rpm' -type f | head -n 1)"
if [[ -z "$built" ]]; then
    fedorapkg_fail RPM_BUILD_FAILED "rpmbuild produced no .rpm under $work/RPMS"
    exit 2
fi
cp "$built" "$output_dir/"
rpm_file="$output_dir/$(basename "$built")"
fedorapkg_sha256 "$rpm_file" > "$rpm_file.sha256"
fedorapkg_emit PASS RPM_BUILT "rpm=$rpm_file sha256=$(cat "$rpm_file.sha256")"
