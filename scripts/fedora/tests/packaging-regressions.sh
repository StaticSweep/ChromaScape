#!/usr/bin/env bash
set -euo pipefail

# FEDORA-01/02 fixture regressions. They build synthetic distributions and drive
# the real packaging, installer, uninstaller and certification scripts against
# them, so the shipping code path is exercised without a Gradle build, a native
# toolchain, a client or a database. They never touch build/dist, build/packages
# or a real host install: every path is under a private temporary root.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
FEDORA_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
PROJECT_DIR="$(cd "$FEDORA_DIR/../.." && pwd -P)"
# shellcheck source=../lib/packaging.sh
source "$FEDORA_DIR/lib/packaging.sh"

passed=0
failed=0

ok() { passed=$((passed + 1)); printf 'PASS: %s\n' "$1"; }
bad() { failed=$((failed + 1)); printf 'FAIL: %s\n' "$1"; }

assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [[ "$expected" == "$actual" ]]; then ok "$label"; else bad "$label (expected '$expected', got '$actual')"; fi
}

assert_file() {
    if [[ -e "$1" ]]; then ok "$2"; else bad "$2 (missing $1)"; fi
}

assert_no_file() {
    if [[ ! -e "$1" ]]; then ok "$2"; else bad "$2 (present $1)"; fi
}

assert_contains() {
    if printf '%s' "$2" | grep -qF -- "$1"; then ok "$3"; else bad "$3 (missing '$1' in: ${2//$'\n'/ | })"; fi
}

work="$(mktemp -d "${TMPDIR:-/tmp}/fedora-packaging-regressions.XXXXXX")"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

fixture_project="$work/project"
fixture_dist="$work/dist/linux-x86_64"
out_a="$work/out-a"
out_b="$work/out-b"
out_v3="$work/out-v3"
prefix="$work/install/prefix"
bin_dir="$work/install/bin"
mkdir -p "$fixture_dist/licenses" "$fixture_project"

cat > "$fixture_project/build.gradle.kts" <<'EOF'
group = "com.chromascape"
version = "0.2.0-SNAPSHOT"
EOF

make_jar() {
    python3 - "$1" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr(
        "META-INF/MANIFEST.MF",
        "Manifest-Version: 1.0\r\nStart-Class: com.chromascape.web.ChromaScapeApplication\r\n"
        "Spring-Boot-Version: 3.5.3\r\n\r\n",
    )
    archive.writestr("BOOT-INF/classes/com/chromascape/Application.class", "")
PY
}

make_jar "$fixture_dist/application.jar"
printf 'resolved terms\n' > "$fixture_dist/licenses/ChromaScape-LICENSE"
cat > "$fixture_dist/launcher" <<'EOF'
#!/usr/bin/env sh
set -eu
APP_ROOT="$(cd -- "$(dirname -- "$0")" && pwd -P)"
exec "${JAVA:-java}" -jar "$APP_ROOT/application.jar" "$@"
EOF
chmod 0755 "$fixture_dist/launcher"

build_tarball() {
    local output="$1" version="$2"
    bash "$FEDORA_DIR/package-distribution.sh" \
        --project-dir "$fixture_project" \
        --dist-dir "$fixture_dist" \
        --output-dir "$output" \
        --version "$version"
}

echo "== shell syntax =="
for script in "$FEDORA_DIR"/*.sh "$FEDORA_DIR"/lib/*.sh "$FEDORA_DIR"/tests/*.sh; do
    if bash -n "$script" 2>/dev/null; then ok "syntax $(basename "$script")"; else bad "syntax $(basename "$script")"; fi
done

echo "== rpm spec contract =="
spec="$(cat "$PROJECT_DIR/packaging/fedora/chromascape.spec.in")"
assert_contains '@RPMVERSION@' "$spec" "spec carries a version placeholder"
assert_contains '%{_bindir}/chromascape' "$spec" "spec installs the command shim"
assert_contains 'BuildArch:      x86_64' "$spec" "spec targets x86_64"
assert_contains 'Requires:       java-headless >= 17' "$spec" "spec requires a Java 17+ runtime"

echo "== tarball build and layout =="
build_output="$(build_tarball "$out_a" 0.2.0)"
top="chromascape-0.2.0-linux-x86_64"
tarball="$out_a/$top.tar.gz"
assert_file "$tarball" "tarball built"
assert_file "$tarball.sha256" "tarball sidecar written"
assert_contains "code=PACKAGE_BUILT" "$build_output" "builder reported success"
if bash "$FEDORA_DIR/package-distribution.sh" --project-dir "$fixture_project" --verify "$tarball" >/dev/null; then
    ok "tarball verify accepts the built archive"
else
    bad "tarball verify rejects the built archive"
fi
members="$(tar -tzf "$tarball" | sed 's:/$::')"
assert_contains "$top/application.jar" "$members" "tarball contains application.jar"
assert_contains "$top/tools/install.sh" "$members" "tarball contains the installer"
assert_contains "$top/tools/uninstall.sh" "$members" "tarball contains the uninstaller"
manifest="$(tar -xOzf "$tarball" "$top/PACKAGE-MANIFEST.json")"
assert_contains '"version": "0.2.0"' "$manifest" "manifest records the version"
assert_contains '"nativePayloadIncluded": false' "$manifest" "base package excludes the native payload"

echo "== deterministic rebuild =="
build_tarball "$out_b" 0.2.0 >/dev/null
if cmp -s "$tarball" "$out_b/$top.tar.gz"; then ok "two builds are byte-identical"; else bad "two builds differ"; fi

echo "== non-root install =="
bash "$FEDORA_DIR/install-tarball.sh" --tarball "$tarball" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
assert_eq "releases/0.2.0" "$(readlink "$prefix/current")" "current points at the installed release"
assert_file "$bin_dir/chromascape" "command shim installed"
assert_file "$prefix/current/application.jar" "release contains application.jar"
assert_no_file "$prefix/current/native" "no stale native payload in the base release"

echo "== shim invokes the packaged jar =="
stub="$work/java-stub"
cat > "$stub" <<'EOF'
#!/usr/bin/env sh
printf 'STUB_JAVA %s\n' "$*"
EOF
chmod 0755 "$stub"
shim_output="$(JAVA="$stub" "$bin_dir/chromascape" --debugger)"
assert_contains "-jar" "$shim_output" "shim passes -jar to the runtime"
assert_contains "application.jar" "$shim_output" "shim runs the packaged release jar"
assert_contains "0.2.0" "$shim_output" "shim resolves through the installed release"

echo "== upgrade and rollback =="
build_tarball "$out_v3" 0.3.0 >/dev/null
bash "$FEDORA_DIR/install-tarball.sh" --tarball "$out_v3/chromascape-0.3.0-linux-x86_64.tar.gz" \
    --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
assert_eq "releases/0.3.0" "$(readlink "$prefix/current")" "upgrade flips current to the new release"
bash "$FEDORA_DIR/install-tarball.sh" --rollback 0.2.0 --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
assert_eq "releases/0.2.0" "$(readlink "$prefix/current")" "rollback flips current to the prior release"

echo "== uninstall leaves nothing =="
bash "$FEDORA_DIR/uninstall-tarball.sh" --prefix "$prefix" --bin-dir "$bin_dir" >/dev/null
assert_no_file "$prefix" "install prefix removed"
assert_no_file "$bin_dir/chromascape" "command shim removed"

echo "== licence gate refuses an unlicensed native payload =="
set +e
gate_output="$(bash "$FEDORA_DIR/package-distribution.sh" --project-dir "$fixture_project" \
    --dist-dir "$fixture_dist" --output-dir "$out_a" --version 0.2.0 --native-payload 2>&1)"
gate_status=$?
set -e
assert_eq "2" "$gate_status" "native payload is refused while the licence gate is unresolved"
assert_contains "LICENCE_GATE_UNRESOLVED" "$gate_output" "refusal names the licence gate"

echo "== verify rejects a tampered archive =="
bad_tarball="$work/broken.tar.gz"
mkdir -p "$work/broken/chromascape-0.2.0-linux-x86_64"
printf 'x' > "$work/broken/chromascape-0.2.0-linux-x86_64/application.jar"
tar -czf "$bad_tarball" -C "$work/broken" chromascape-0.2.0-linux-x86_64
set +e
bash "$FEDORA_DIR/package-distribution.sh" --project-dir "$fixture_project" --verify "$bad_tarball" >/dev/null 2>&1
verify_status=$?
set -e
assert_eq "2" "$verify_status" "verify rejects an incomplete archive"

echo "== certification harness (static path) =="
cert_out="$(bash "$FEDORA_DIR/certify-packaged.sh" --package "$tarball" --skip-runtime --no-postgres 2>&1)" || true
assert_contains "name=tarballInstallNoSudo status=PASS" "$cert_out" "certification installs without sudo"
assert_contains "name=noResidue status=PASS" "$cert_out" "certification proves no residue"
assert_contains "name=packagedNativeAttachment status=NOT_RUN" "$cert_out" "live client checks abstain honestly"
assert_no_file "$prefix" "certification cleaned its own install after uninstall"

echo "== certification evidence record =="
evidence_file="$work/fedora02-tarball.json"
bash "$FEDORA_DIR/certify-packaged.sh" --package "$tarball" --skip-runtime --no-postgres \
    --evidence "$evidence_file" --host-id fixture-host --revision "$(printf 'a%.0s' {1..40})" >/dev/null
assert_file "$evidence_file" "evidence record written"
record_story="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["story"])' "$evidence_file")"
assert_eq "FEDORA-02" "$record_story" "evidence record carries the story identity"
record_status="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["checks"]["tarballInstallNoSudo"]["status"])' "$evidence_file")"
assert_eq "PASS" "$record_status" "evidence record carries the observed install result"

printf '\nFEDORA_PACKAGING_REGRESSIONS passed=%d failed=%d\n' "$passed" "$failed"
(( failed == 0 ))
