#!/usr/bin/env bash
#
# SC-1662 staging regressions.
#
# Exercises the real staging implementation (the `StageRemoteInputLinuxTask` class) with synthetic
# fixture pairs, so the shipping Kotlin stager is tested without a C++ toolchain. It drives the
# dedicated `stageRemoteInputLinuxRegression` task, which is the same class wired to an isolated
# root: the pair is read from and published under `build/sc1662/staging-regressions`, never the real
# `build/native/remoteinput` or `build/dist/native`. A regression run therefore cannot overwrite a
# real artifact, prune real payloads, or leave a synthetic publish point behind.
#
# The shell stager (`stage_artifact` in build-remoteinput-linux.sh) is covered by the companion
# `staging-shell-regressions.sh`.
#
# Cases:
#   1. pair identity binds the artifact *and* the manifest: a manifest-only change publishes a
#      distinct payload directory, and the payload directory is not the artifact hash.
#   2. an existing payload that validates against its own manifest but is not the expected pair is
#      refused (the P1 review finding: a modified but internally valid pair reused under another
#      pair's directory name).
#   3. the immediate predecessor is retained by publication history: republishing an older pair
#      keeps the payload it displaced rather than pruning it by timestamp.
#   4. a real directory at the publish point (the legacy layout) is refused, and the directory
#      survives so no publish point is lost.
#
# Exit: 0 when every case passed, 1 otherwise.
#
# Environment: requires Python 3 and a Java 17 JVM. Use the same launcher environment as the native
# build, for example:
#
#   JAVA_HOME=/home/<user>/.jdks/corretto-17.0.20.1 \
#   GRADLE_USER_HOME=<repo>/ChromaScape/.gradle-home \
#   scripts/native/staging-regressions.sh

set -u

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
HELPER="$REPO_ROOT/scripts/native/remoteinput_metadata.py"
FIXTURES="$REPO_ROOT/scripts/native/tests/staging_regression_fixtures.py"
GRADLEW="$REPO_ROOT/gradlew"

# Everything below reads and writes only the isolated regression root. The real build output under
# build/native/remoteinput and build/dist/native is never touched, so a regression run cannot
# overwrite a real artifact, prune real payloads, or leave a synthetic publish point behind.
REGRESSION_ROOT="$REPO_ROOT/build/sc1662/staging-regressions"
BUILD_PAIR_DIR="$REGRESSION_ROOT/native/linux-x86_64"
STAGE_PARENT="$REGRESSION_ROOT/dist"
STAGE_DIR="$STAGE_PARENT/linux-x86_64"
VERSIONS_DIR="$STAGE_PARENT/.versions"

WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT

FAILURES=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

pair_identity() { # $1 = artifact, $2 = manifest
  printf '%s\0%s' "$(sha256_of "$1")" "$(sha256_of "$2")" | sha256sum | awk '{print $1}'
}

published_identity() {
  [ -L "$STAGE_DIR" ] || return 1
  basename "$(readlink "$STAGE_DIR")"
}

reset_stage() { rm -rf -- "$REGRESSION_ROOT"; }

make_pair() { # $1 = outdir, $2 = seed, $3 = generatedAtUtc -> sets PAIR_ARTIFACT / PAIR_MANIFEST
  local out
  out="$(python3 -B "$FIXTURES" --helper "$HELPER" --outdir "$1" \
    --seed "$2" --generated-at "$3")" || {
    echo "fixture generation failed"
    exit 1
  }
  PAIR_ARTIFACT="$(printf '%s\n' "$out" | awk '/^ARTIFACT /{print $2}')"
  PAIR_MANIFEST="$(printf '%s\n' "$out" | awk '/^MANIFEST /{print $2}')"
}

place_pair() { # $1 = artifact, $2 = manifest
  mkdir -p -- "$BUILD_PAIR_DIR"
  install -m 0644 "$1" "$BUILD_PAIR_DIR/libRemoteInput.so"
  install -m 0644 "$2" "$BUILD_PAIR_DIR/remoteinput-manifest.json"
}

run_stage() { # $1 = label -> writes $WORK/stage.out, echoes it indented, returns the exit code
  echo "== $1 =="
  ( cd "$REPO_ROOT" && "$GRADLEW" --offline --console=plain \
      stageRemoteInputLinuxRegression ) >"$WORK/stage.out" 2>&1
  local rc=$?
  sed 's/^/   /' "$WORK/stage.out"
  echo "   exit=$rc"
  return $rc
}

stage_output_has() { grep -qF -- "$1" "$WORK/stage.out"; }

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must name a Java 17 JDK (the Gradle Kotlin DSL does not configure on newer JVMs)." >&2
  exit 2
fi
if [ ! -x "$GRADLEW" ]; then
  echo "Gradle wrapper not found at $GRADLEW" >&2
  exit 2
fi

echo "# SC-1662 staging regressions"
echo "# revision: $(cd "$REPO_ROOT" && git rev-parse HEAD 2>/dev/null || echo unknown)"
echo

# --- Case 1: pair identity binds artifact AND manifest -----------------------------------------
reset_stage
make_pair "$WORK/c1a" 11 "2026-09-15T00:00:01Z"; place_pair "$PAIR_ARTIFACT" "$PAIR_MANIFEST"
if run_stage "case 1: publish pair (manifest v1)"; then
  d1="$(published_identity)" || d1=""
  echo "   payload=$d1"
else
  d1=""; fail "case 1: publishing the first pair failed"
fi

make_pair "$WORK/c1b" 11 "2026-09-15T00:00:02Z"; place_pair "$PAIR_ARTIFACT" "$PAIR_MANIFEST"
artifact_sha="$(sha256_of "$PAIR_ARTIFACT")"
if run_stage "case 1: same artifact, different manifest (v2)"; then
  d2="$(published_identity)" || d2=""
  echo "   payload=$d2"
else
  d2=""; fail "case 1: publishing the second pair failed"
fi

echo "   artifact-sha256=$artifact_sha"
if [ -n "$d1" ] && [ -n "$d2" ] && [ "$d1" != "$d2" ]; then
  pass "case 1: a manifest-only change produces a distinct payload identity"
else
  fail "case 1: expected two distinct payload identities, got '$d1' and '$d2'"
fi
if [ -n "$d1" ] && [ "$d1" != "$artifact_sha" ]; then
  pass "case 1: the payload identity is the pair, not the artifact hash"
else
  fail "case 1: payload identity '$d1' equals the artifact hash"
fi

# --- Case 2: existing payload with a mismatched identity is refused -----------------------------
reset_stage
make_pair "$WORK/c2x" 21 "2026-09-15T00:00:03Z"
x_artifact="$PAIR_ARTIFACT"; x_manifest="$PAIR_MANIFEST"
expected="$(pair_identity "$x_artifact" "$x_manifest")"
place_pair "$x_artifact" "$x_manifest"
if run_stage "case 2: publish pair X"; then
  pass "case 2: setup publish succeeded"
else
  fail "case 2: setup publish failed"
fi
observed="$(published_identity || true)"
if [ "$observed" = "$expected" ]; then
  pass "case 2: publish point names the pair identity"
else
  fail "case 2: publish point '$observed' != expected identity '$expected'"
fi

# Poison .versions/<expected>/ with a *different* pair that is internally valid.
make_pair "$WORK/c2y" 22 "2026-09-15T00:00:04Z"
install -m 0644 "$PAIR_ARTIFACT" "$VERSIONS_DIR/$expected/libRemoteInput.so"
install -m 0644 "$PAIR_MANIFEST" "$VERSIONS_DIR/$expected/remoteinput-manifest.json"
place_pair "$x_artifact" "$x_manifest"
if run_stage "case 2: re-stage X while .versions/X holds a different valid pair"; then
  fail "case 2: a mismatched existing payload was accepted"
else
  if stage_output_has "not the expected pair"; then
    pass "case 2: the mismatched existing payload was refused by identity"
  else
    fail "case 2: staging failed but not for the pair-identity reason"
  fi
fi

# --- Case 3: the immediate predecessor is retained ----------------------------------------------
reset_stage
make_pair "$WORK/c3a" 31 "2026-09-15T00:00:05Z"; a_artifact="$PAIR_ARTIFACT"; a_manifest="$PAIR_MANIFEST"
place_pair "$a_artifact" "$a_manifest"
run_stage "case 3: publish pair A" || fail "case 3: publishing A failed"
id_a="$(published_identity || true)"

make_pair "$WORK/c3b" 32 "2026-09-15T00:00:06Z"; place_pair "$PAIR_ARTIFACT" "$PAIR_MANIFEST"
run_stage "case 3: publish pair B" || fail "case 3: publishing B failed"
id_b="$(published_identity || true)"

# Republish A: the payload it displaces (B) is the predecessor and must be kept.
place_pair "$a_artifact" "$a_manifest"
run_stage "case 3: republish pair A (predecessor B must survive)" || fail "case 3: republishing A failed"
final="$(published_identity || true)"
if [ "$final" = "$id_a" ]; then
  pass "case 3: publish point returned to pair A"
else
  fail "case 3: publish point '$final' is not pair A '$id_a'"
fi
if [ -d "$VERSIONS_DIR/$id_b" ]; then
  pass "case 3: displaced predecessor B retained"
else
  fail "case 3: predecessor B ('$id_b') was pruned when A was republished"
fi

# --- Case 4: a legacy real-directory publish point is refused -----------------------------------
reset_stage
mkdir -p -- "$STAGE_DIR"
echo "legacy" >"$STAGE_DIR/linux-x86_64" 2>/dev/null || true
make_pair "$WORK/c4" 41 "2026-09-15T00:00:07Z"; place_pair "$PAIR_ARTIFACT" "$PAIR_MANIFEST"
if run_stage "case 4: stage with a real directory at the publish point"; then
  fail "case 4: staging accepted a real-directory publish point"
else
  if stage_output_has "is a real directory"; then
    pass "case 4: the real-directory publish point was refused"
  else
    fail "case 4: staging failed but not for the legacy-directory reason"
  fi
fi
if [ -d "$STAGE_DIR" ] && [ ! -L "$STAGE_DIR" ]; then
  pass "case 4: the real directory survives (no publish point was removed)"
else
  fail "case 4: the real directory was removed or replaced"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "# result: PASS (all staging regressions)"
  exit 0
fi
echo "# result: FAIL ($FAILURES assertion(s) failed)"
exit 1
