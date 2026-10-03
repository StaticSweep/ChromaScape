#!/usr/bin/env bash
set -euo pipefail

# FEDORA-01 Linux distribution packager.
#
# Turns an already-assembled Gradle distribution (build/dist/linux-x86_64, which
# contains application.jar, launcher and licenses/ChromaScape-LICENSE) into a
# versioned, self-describing tarball. The tarball ships the non-root installer,
# the uninstaller and a machine-readable PACKAGE-MANIFEST.json so FEDORA-02 can
# verify the payload independently of the checkout that built it.
#
# The RemoteInput native pair is deliberately opt-in: it is copied only with
# --native-payload, and that flag is refused unless the two-factor licence gate
# recorded in third-party/RemoteInput/PROVENANCE.md is closed. A base package
# never contains an unlicensed binary.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
# shellcheck source=lib/packaging.sh
source "$SCRIPT_DIR/lib/packaging.sh"

name="$FEDORAPKG_NAME_DEFAULT"
version=""
output_dir="$PROJECT_DIR/build/packages"
dist_dir="$PROJECT_DIR/build/dist/linux-x86_64"
native_dir="$PROJECT_DIR/build/dist/native/linux-x86_64"
native_payload=false
verify_only=""

usage() {
    cat <<'EOF'
Usage: package-distribution.sh [options]

Options:
  --project-dir <dir>   Checkout root (default: the script's repository).
  --version <version>   Package version (default: derived from build.gradle.kts).
  --output-dir <dir>    Where the tarball and sidecar are written (default: build/packages).
  --dist-dir <dir>      Assembled distribution root (default: build/dist/linux-x86_64).
  --native-payload      Include the staged RemoteInput pair (requires the licence gate closed).
  --verify <tarball>    Verify an existing tarball's layout and exit.
  -h, --help            Show this help.

Exit codes: 0 success, 2 refused/blocked, 64 usage.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        --project-dir) PROJECT_DIR="${2:-}"; shift 2 ;;
        --version) version="${2:-}"; shift 2 ;;
        --output-dir) output_dir="${2:-}"; shift 2 ;;
        --dist-dir) dist_dir="${2:-}"; shift 2 ;;
        --native-payload) native_payload=true; shift ;;
        --verify) verify_only="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done

if [[ -n "$verify_only" ]]; then
    if [[ -z "$version" ]]; then
        version="$(fedorapkg_project_version "$PROJECT_DIR")"
    fi
    top="$(fedorapkg_tarball_name "$name" "$version" | sed 's/\.tar\.gz$//')"
    fedorapkg_verify_tarball "$verify_only" "$top"
    exit $?
fi

if [[ ! -f "$dist_dir/application.jar" || ! -f "$dist_dir/launcher" ]]; then
    fedorapkg_fail DISTRIBUTION_MISSING \
        "assembled distribution is incomplete at $dist_dir; run './gradlew assembleLinuxDistribution' first"
    exit 2
fi

if [[ -z "$version" ]]; then
    version="$(fedorapkg_project_version "$PROJECT_DIR")"
fi
rpm_version="$(fedorapkg_rpm_version "$version")"
top="$name-$version-linux-x86_64"
tarball="$output_dir/$(fedorapkg_tarball_name "$name" "$version")"

if [[ "$native_payload" == true ]]; then
    if [[ ! -f "$PROJECT_DIR/third-party/RemoteInput/PROVENANCE.md" ]]; then
        fedorapkg_fail LICENCE_GATE_UNRESOLVED "third-party/RemoteInput/PROVENANCE.md is missing"
        exit 2
    fi
    marker_count="$(grep -cE '^top-level-licence-resolution:[[:space:]]*(resolved|unresolved)[[:space:]]*$' \
        "$PROJECT_DIR/third-party/RemoteInput/PROVENANCE.md" || true)"
    marker="$(sed -n 's/^top-level-licence-resolution:[[:space:]]*\(resolved\|unresolved\).*/\1/p' \
        "$PROJECT_DIR/third-party/RemoteInput/PROVENANCE.md" | head -n 1)"
    licence="$PROJECT_DIR/third-party/RemoteInput/LICENSE"
    if [[ "$marker_count" != "1" || "$marker" != "resolved" || ! -s "$licence" ]]; then
        fedorapkg_fail LICENCE_GATE_UNRESOLVED \
            "cannot ship a RemoteInput payload: the licence gate is not closed (marker=${marker:-missing}, licence=$licence)"
        exit 2
    fi
    if [[ ! -f "$native_dir/libRemoteInput.so" || ! -f "$native_dir/remoteinput-manifest.json" ]]; then
        fedorapkg_fail NATIVE_PAYLOAD_MISSING \
            "no staged RemoteInput pair at $native_dir; run './gradlew stageRemoteInputLinux' first"
        exit 2
    fi
fi

stage_parent="$(mktemp -d "${TMPDIR:-/tmp}/chromascape-package.XXXXXX")"
cleanup() { rm -rf "$stage_parent"; }
trap cleanup EXIT

stage="$stage_parent/$top"
mkdir -p "$stage/licenses" "$stage/tools/lib"
cp -a "$dist_dir/." "$stage/"
cp "$SCRIPT_DIR/install-tarball.sh" "$stage/tools/install.sh"
cp "$SCRIPT_DIR/uninstall-tarball.sh" "$stage/tools/uninstall.sh"
cp "$SCRIPT_DIR/lib/packaging.sh" "$stage/tools/lib/packaging.sh"
chmod 0755 "$stage/tools/install.sh" "$stage/tools/uninstall.sh"
chmod 0644 "$stage/tools/lib/packaging.sh"

# The distribution assembly already guarantees application.jar and launcher exist;
# assert them again here because packaging is the last gate before distribution.
for required in application.jar launcher licenses/ChromaScape-LICENSE; do
    if [[ ! -e "$stage/$required" ]]; then
        fedorapkg_fail DISTRIBUTION_MISSING "assembled distribution lacks $required"
        exit 2
    fi
done

native_included=false
if [[ "$native_payload" == true ]]; then
    mkdir -p "$stage/native/linux-x86_64"
    # Resolve the staging symlink so the tarball carries real bytes, not a link
    # into a checkout-relative .versions directory.
    pair_library="$(realpath "$native_dir/libRemoteInput.so")"
    pair_manifest="$(realpath "$native_dir/remoteinput-manifest.json")"
    cp -a "$pair_library" "$stage/native/linux-x86_64/libRemoteInput.so"
    cp -a "$pair_manifest" "$stage/native/linux-x86_64/remoteinput-manifest.json"
    python3 -B "$PROJECT_DIR/scripts/native/remoteinput_metadata.py" verify-manifest \
        --manifest "$stage/native/linux-x86_64/remoteinput-manifest.json" \
        --artifact "$stage/native/linux-x86_64/libRemoteInput.so" >/dev/null
    native_included=true
fi

jar_sha="$(fedorapkg_sha256 "$stage/application.jar")"
manifest_tmp="$stage/$FEDORAPKG_MANIFEST.tmp"
cat > "$manifest_tmp" <<EOF
{
  "schemaVersion": $FEDORAPKG_SCHEMA_VERSION,
  "name": "$name",
  "version": "$version",
  "rpmVersion": "$rpm_version",
  "arch": "linux-x86_64",
  "applicationJarSha256": "$jar_sha",
  "nativePayloadIncluded": $native_included,
  "requiredMembers": [
$(fedorapkg_required_members | sed 's/.*/    "&",/' | sed '$ s/,$//')
  ]
}
EOF
mv -f "$manifest_tmp" "$stage/$FEDORAPKG_MANIFEST"

mkdir -p "$output_dir"
rm -f "$tarball" "$tarball.sha256"
# Deterministic archive: sorted members, epoch mtimes, numeric ownership. Two
# package builds of the same distribution therefore agree byte-for-byte, which is
# what lets FEDORA-02 cite a content hash as package identity. gzip -1 keeps the
# archive quick to produce for an already-compressed Boot jar while remaining
# deterministic (the archive content, not the compression ratio, is the identity).
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner --format=gnu \
    --use-compress-program='gzip -1 -n' -cf "$tarball" -C "$stage_parent" "$top"
fedorapkg_sha256 "$tarball" > "$tarball.sha256"
fedorapkg_verify_tarball "$tarball" "$top"
fedorapkg_emit PASS PACKAGE_BUILT "tarball=$tarball sha256=$(cat "$tarball.sha256") native=$native_included"
