#!/usr/bin/env bash
#
# SC-1662 shell staging regressions.
#
# Exercises `stage_artifact` from `scripts/native/build-remoteinput-linux.sh` **directly**, with the
# staging globals reassigned to an isolated temporary root. The Kotlin stager is covered by the
# companion `staging-regressions.sh`; this file is what gives the shell stager behavioural coverage
# beyond `bash -n`.
#
# Cases:
#   1. a valid pair publishes, and the publish point is the pair identity (not the artifact hash).
#   2. an existing payload that validates against its own manifest but is not the expected pair is
#      refused (build-remoteinput-linux.sh: the existing-payload identity check).
#   3. a copied temporary pair that does not reproduce the incoming identity is refused
#      (build-remoteinput-linux.sh: the copied-pair identity check). Forced deterministically by
#      stubbing `install` so the copy step lands a *different* valid pair.
#   4. the immediate predecessor is retained when an older pair is republished.
#   5. a real directory at the publish point is refused, and the directory survives.
#
# Exit: 0 when every case passed, 1 otherwise.
#
# Environment: requires Python 3. No JVM is needed: the shell stager invokes only the Python helper.

set -u

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
STAGER="$REPO_ROOT/scripts/native/build-remoteinput-linux.sh"
HELPER="$REPO_ROOT/scripts/native/remoteinput_metadata.py"
FIXTURES="$REPO_ROOT/scripts/native/tests/staging_regression_fixtures.py"

WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT

FAILURES=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILURES=$((FAILURES + 1)); }

# Source the real stager to get `stage_artifact`, `helper`, `log`, `die`, `sha256_of` and
# `pair_identity`. `set --` first so the sourced argument parser sees no arguments and does not run;
# the script's `main` guard keeps main from firing when sourced.
set --
# shellcheck disable=SC1090,SC1091
source "$STAGER"
# The stager enables errexit; the harness expects failures and inspects their exit codes.
set +e

# Isolate: the sourced globals are plain variables, so the harness repoints the publish root away
# from build/dist/native before calling stage_artifact.
STAGE_PARENT="$WORK/dist"
STAGE_VERSIONS_DIR="$STAGE_PARENT/.versions"
STAGE_DIR="$STAGE_PARENT/linux-x86_64"

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

run_stage() { # $1 = label, $2 = artifact, $3 = manifest -> writes $WORK/stage.out, returns rc
  echo "== $1 =="
  ( stage_artifact "$2" "$3" ) >"$WORK/stage.out" 2>&1
  local rc=$?
  sed 's/^/   /' "$WORK/stage.out"
  echo "   exit=$rc"
  return $rc
}

stage_output_has() { grep -qF -- "$1" "$WORK/stage.out"; }

published_identity() {
  [ -L "$STAGE_DIR" ] || return 1
  basename "$(readlink "$STAGE_DIR")"
}

echo "# SC-1662 shell staging regressions (stage_artifact)"
echo "# revision: $(cd "$REPO_ROOT" && git rev-parse HEAD 2>/dev/null || echo unknown)"
echo

# --- Case 1: publish succeeds and the identity is the pair -------------------------------------
make_pair "$WORK/s1" 101 "2026-09-15T00:00:01Z"
s1_artifact="$PAIR_ARTIFACT"; s1_manifest="$PAIR_MANIFEST"
s1_expected="$(pair_identity "$s1_artifact" "$s1_manifest")"
s1_artifact_sha="$(sha256_of "$s1_artifact")"
if run_stage "case 1: publish pair S1" "$s1_artifact" "$s1_manifest"; then
  pass "case 1: staging succeeded"
else
  fail "case 1: staging failed"
fi
s1_published="$(published_identity || true)"
if [ "$s1_published" = "$s1_expected" ]; then
  pass "case 1: publish point names the pair identity"
else
  fail "case 1: publish point '$s1_published' != expected '$s1_expected'"
fi
if [ "$s1_published" != "$s1_artifact_sha" ]; then
  pass "case 1: identity is the pair, not the artifact hash"
else
  fail "case 1: identity equals the artifact hash"
fi

# --- Case 2: existing payload with a mismatched identity is refused -----------------------------
make_pair "$WORK/s2a" 102 "2026-09-15T00:00:02Z"
s2_artifact="$PAIR_ARTIFACT"; s2_manifest="$PAIR_MANIFEST"
s2_expected="$(pair_identity "$s2_artifact" "$s2_manifest")"
run_stage "case 2: publish pair S2" "$s2_artifact" "$s2_manifest" >/dev/null || fail "case 2: setup publish failed"
# Poison .versions/<expected>/ with a different, internally valid pair.
make_pair "$WORK/s2b" 103 "2026-09-15T00:00:03Z"
install -m 0644 "$PAIR_ARTIFACT" "$STAGE_VERSIONS_DIR/$s2_expected/libRemoteInput.so"
install -m 0644 "$PAIR_MANIFEST" "$STAGE_VERSIONS_DIR/$s2_expected/remoteinput-manifest.json"
if run_stage "case 2: re-stage S2 while its payload holds a different valid pair" "$s2_artifact" "$s2_manifest"; then
  fail "case 2: a mismatched existing payload was accepted"
elif stage_output_has "not the expected pair"; then
  pass "case 2: the mismatched existing payload was refused by identity"
else
  fail "case 2: staging failed but not for the pair-identity reason"
fi

# --- Case 3: a copied temporary pair that differs from the incoming identity is refused ---------
# Force it deterministically: stub `install` so the copy step lands a *different* valid pair. The
# incoming pair validates, so only the copied-pair identity check can reject it.
make_pair "$WORK/s3" 104 "2026-09-15T00:00:04Z"
s3_artifact="$PAIR_ARTIFACT"; s3_manifest="$PAIR_MANIFEST"
make_pair "$WORK/s3decoy" 105 "2026-09-15T00:00:05Z"
DECOY_ARTIFACT="$PAIR_ARTIFACT"
DECOY_MANIFEST="$PAIR_MANIFEST"
install() {
  local last="${!#}"
  case "$last" in
    */libRemoteInput.so)
      if [ -n "${DECOY_ARTIFACT:-}" ]; then cp -f -- "$DECOY_ARTIFACT" "$last"; return 0; fi ;;
    */remoteinput-manifest.json)
      if [ -n "${DECOY_MANIFEST:-}" ]; then cp -f -- "$DECOY_MANIFEST" "$last"; return 0; fi ;;
  esac
  command install "$@"
}
if run_stage "case 3: copied pair does not reproduce the incoming identity" "$s3_artifact" "$s3_manifest"; then
  fail "case 3: a copied pair with a different identity was accepted"
elif stage_output_has "did not reproduce the expected identity"; then
  pass "case 3: the copied-pair identity mismatch was refused"
else
  fail "case 3: staging failed but not for the copied-pair identity reason"
fi
DECOY_ARTIFACT=""
DECOY_MANIFEST=""
unset -f install

# --- Case 4: the immediate predecessor is retained ----------------------------------------------
rm -rf -- "$STAGE_PARENT"
make_pair "$WORK/s4a" 106 "2026-09-15T00:00:06Z"; a_artifact="$PAIR_ARTIFACT"; a_manifest="$PAIR_MANIFEST"
make_pair "$WORK/s4b" 107 "2026-09-15T00:00:07Z"; b_artifact="$PAIR_ARTIFACT"; b_manifest="$PAIR_MANIFEST"
id_a="$(pair_identity "$a_artifact" "$a_manifest")"
id_b="$(pair_identity "$b_artifact" "$b_manifest")"
run_stage "case 4: publish A" "$a_artifact" "$a_manifest" >/dev/null || fail "case 4: publishing A failed"
run_stage "case 4: publish B" "$b_artifact" "$b_manifest" >/dev/null || fail "case 4: publishing B failed"
run_stage "case 4: republish A (predecessor B must survive)" "$a_artifact" "$a_manifest" >/dev/null || fail "case 4: republishing A failed"
final="$(published_identity || true)"
if [ "$final" = "$id_a" ]; then pass "case 4: publish point returned to pair A"; else fail "case 4: publish point '$final' is not pair A '$id_a'"; fi
if [ -d "$STAGE_VERSIONS_DIR/$id_b" ]; then pass "case 4: displaced predecessor B retained"; else fail "case 4: predecessor B ('$id_b') was pruned"; fi

# --- Case 5: a legacy real-directory publish point is refused -----------------------------------
rm -rf -- "$STAGE_PARENT"
mkdir -p -- "$STAGE_DIR"
make_pair "$WORK/s5" 108 "2026-09-15T00:00:08Z"
if run_stage "case 5: stage with a real directory at the publish point" "$PAIR_ARTIFACT" "$PAIR_MANIFEST"; then
  fail "case 5: staging accepted a real-directory publish point"
elif stage_output_has "is a real directory"; then
  pass "case 5: the real-directory publish point was refused"
else
  fail "case 5: staging failed but not for the legacy-directory reason"
fi
if [ -d "$STAGE_DIR" ] && [ ! -L "$STAGE_DIR" ]; then
  pass "case 5: the real directory survives (no publish point was removed)"
else
  fail "case 5: the real directory was removed or replaced"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "# result: PASS (all shell staging regressions)"
  exit 0
fi
echo "# result: FAIL ($FAILURES assertion(s) failed)"
exit 1
