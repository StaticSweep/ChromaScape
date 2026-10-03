#!/usr/bin/env bash
#
# SC-1662 distribution-exclusion regression.
#
# Proves that assembling the Linux distribution cannot ship any RemoteInput payload — and that a
# stale payload left in the output root by an earlier experiment cannot survive assembly.
#
# The regression drives `assembleLinuxDistributionRegression`: the SAME task class as the production
# `assembleLinuxDistribution`, wired to an isolated output root under
# build/sc1662/distribution-exclusion. The real `build/dist/linux-x86_64` and every real native
# artifact under `build/dist/native` and `build/native/remoteinput` are never read or written by
# this harness.
#
# Before assembly the isolated root is pre-seeded with the stale payload a previous run could have
# left behind:
#
#   native/linux-x86_64/libRemoteInput.so
#   native/linux-x86_64/remoteinput-manifest.json
#   licenses/RemoteInput-LICENSE
#   licenses/RemoteInput-thirdparty/
#   application.jar
#   launcher
#
# After assembly it asserts:
#   - every seeded RemoteInput path is gone;
#   - no libRemoteInput.so, remoteinput-manifest.json, RemoteInput licence or RemoteInput
#     third-party directory exists anywhere under the distribution;
#   - application.jar, an executable launcher and licenses/ChromaScape-LICENSE exist;
#   - the launcher names no RemoteInput override or artifact;
#   - the application jar contains no native library, manifest, vendored RemoteInput source or
#     RemoteInput licence; and
#   - the resulting tree is exactly:
#       application.jar
#       launcher
#       licenses/
#       licenses/ChromaScape-LICENSE
#
# Exit: 0 when every assertion passed, 1 otherwise.
#
# Environment: requires a Java 17 JVM (JAVA_HOME or `java` on PATH), Python 3 with unittest, and a
# `unzip`-compatible listing tool. Use the same launcher environment as the native build:
#
#   JAVA_HOME=/home/<user>/.jdks/corretto-17.0.20.1 \
#   GRADLE_USER_HOME=<repo>/.gradle-home \
#   scripts/native/distribution-exclusion-regressions.sh

set -eu

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
GRADLEW="$REPO_ROOT/gradlew"

# Everything below reads and writes only the isolated regression root. The real distribution
# (build/dist/linux-x86_64) and the real native outputs (build/dist/native,
# build/native/remoteinput) are never touched.
REGRESSION_ROOT="$REPO_ROOT/build/sc1662/distribution-exclusion"
DIST="$REGRESSION_ROOT"

FAILURES=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

seed_file() {
	mkdir -p -- "$(dirname -- "$1")"
	printf '%s\n' "stale sc1662 payload marker for $1" > "$1"
}

echo "== seeding the isolated output root with a stale RemoteInput payload =="
rm -rf -- "$REGRESSION_ROOT"
mkdir -p -- "$DIST"
seed_file "$DIST/native/linux-x86_64/libRemoteInput.so"
seed_file "$DIST/native/linux-x86_64/remoteinput-manifest.json"
seed_file "$DIST/licenses/RemoteInput-LICENSE"
mkdir -p "$DIST/licenses/RemoteInput-thirdparty"
seed_file "$DIST/licenses/RemoteInput-thirdparty/README.md"
seed_file "$DIST/application.jar"
seed_file "$DIST/launcher"

echo "== assembling the distribution into the isolated root =="
"$GRADLEW" --console=plain -p "$REPO_ROOT" assembleLinuxDistributionRegression

echo "== assertions =="
OLD_CWD="$(pwd)"
cd -- "$DIST"

# 1. Every seeded RemoteInput path is gone.
for seeded in \
	"native/linux-x86_64/libRemoteInput.so" \
	"native/linux-x86_64/remoteinput-manifest.json" \
	"licenses/RemoteInput-LICENSE" \
	"licenses/RemoteInput-thirdparty"; do
	if [ -e "$seeded" ]; then
		fail "seeded path survived assembly: $seeded"
	else
		pass "seeded path removed: $seeded"
	fi
done

# 2. No RemoteInput library, manifest, licence or third-party tree anywhere under the distribution.
hits="$(find . -name 'libRemoteInput.so' -o -name 'remoteinput-manifest.json' \
	-o -name 'RemoteInput-LICENSE' -o -name 'RemoteInput-thirdparty' | sed 's/^\.\///')"
if [ -n "$hits" ]; then
	fail "RemoteInput payload present after assembly:"$'\n'"$hits"
else
	pass "no libRemoteInput.so, manifest, RemoteInput licence or third-party directory anywhere"
fi

# 3. Required distribution entries.
if [ -s "application.jar" ]; then
	pass "application.jar exists and is non-empty"
else
	fail "application.jar is missing or empty"
fi
if [ -x "launcher" ]; then
	pass "launcher exists and is executable"
else
	fail "launcher is missing or not executable"
fi

# Static launcher checks only — shell syntax and permissions. The harness must never start the
# application: assembly installs the real boot jar into this root, so executing the launcher here
# would launch the actual ChromaScape application with unrelated side effects. `sh -n` parses the
# script without running it, and the executable-bit check above proves the permission bit.
if sh -n launcher; then
	pass "launcher passes shell syntax check (sh -n)"
else
	fail "launcher fails shell syntax check (sh -n)"
fi
if [ -s "licenses/ChromaScape-LICENSE" ]; then
	pass "licenses/ChromaScape-LICENSE exists and is non-empty"
else
	fail "licenses/ChromaScape-LICENSE is missing or empty"
fi

# 4. The launcher names no RemoteInput override and no RemoteInput artifact.
if grep -q -e 'chromascape\.remoteinput\.library' -e 'libRemoteInput\.so' launcher; then
	fail "launcher references RemoteInput"
else
	pass "launcher contains no chromascape.remoteinput.library or libRemoteInput.so reference"
fi

# 5. The application jar carries no RemoteInput payload: no native library, no manifest, no
#    vendored source tree and no RemoteInput licence entry.
if command -v unzip >/dev/null 2>&1; then
	JAR_HITS="$(unzip -l application.jar | awk '{print $4}' | grep -E \
		'libRemoteInput\.so$|remoteinput-manifest\.json$|third-party/RemoteInput|RemoteInput-LICENSE' \
		|| true)"
	if [ -n "$JAR_HITS" ]; then
		fail "application.jar contains RemoteInput payload:"$'\n'"$JAR_HITS"
	else
		pass "application.jar contains no native library, manifest, vendored source or licence"
	fi
else
	# No unzip available: fall back to a byte scan for the unambiguous vendored tree prefix and the
	# manifest filename. A zip stores entry names as plain bytes.
	if command -v python3 >/dev/null 2>&1 && python3 - application.jar <<'PY'
import sys
with open(sys.argv[1], "rb") as handle:
    data = handle.read()
sys.exit(1 if (b"remoteinput-manifest.json" in data or b"third-party/RemoteInput/" in data) else 0)
PY
	then
		pass "application.jar byte-scan found no RemoteInput manifest or vendored tree (unzip unavailable)"
	else
		fail "application.jar byte-scan matched a RemoteInput payload marker (unzip unavailable)"
	fi
fi

# 6. The allowed distribution tree is exactly:
#      application.jar
#      launcher
#      licenses/
#      licenses/ChromaScape-LICENSE
ACTUAL_TREE="$(find . -mindepth 1 | sed -e 's/^\.\///' -e '/\/$/d' | sort)"
EXPECTED_TREE="application.jar
launcher
licenses
licenses/ChromaScape-LICENSE"
if [ "$ACTUAL_TREE" = "$EXPECTED_TREE" ]; then
	pass "distribution tree is exactly the allowed set"
else
	fail "distribution tree deviates from the allowed set"
	echo "--- expected ---"
	echo "$EXPECTED_TREE"
	echo "--- actual ---"
	echo "$ACTUAL_TREE"
fi

cd -- "$OLD_CWD"

if [ "$FAILURES" -eq 0 ]; then
	echo "distribution-exclusion regression: ALL CHECKS PASSED"
	exit 0
fi
echo "distribution-exclusion regression: $FAILURES check(s) FAILED"
exit 1
