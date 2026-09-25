#!/usr/bin/env bash
# Cross-repository tick-batch fixture certification for SC-1199 durability closure.
#
# Compares every shared fixture byte-for-byte between the Micro6 and ChromaScape checkouts and
# fails when either path is missing. This is intentionally a separate explicit script, not a unit
# test: unit tests assert fixed manifest hashes inside their own repository (portable on native
# Windows), while this script proves the two checkouts agree with each other.
#
# Usage:
#   scripts/certify-tick-batch-fixtures.sh [micro6_dir] [chromascape_dir]
# Defaults resolve to the canonical canonical sibling checkouts.
set -euo pipefail

MICRO6_DIR="${1:-C:/Dev/IdeaProjects/micro6}"
CHROMA_DIR="${2:-C:/Dev/IdeaProjects/ChromaScape}"

to_wsl() {
  local path="$1"
  if [[ "$path" =~ ^[A-Za-z]:/ ]]; then
    local drive="${path:0:1}"
    drive="$(printf '%s' "$drive" | tr '[:upper:]' '[:lower:]')"
    printf '/mnt/%s/%s' "$drive" "${path:3}"
  else
    printf '%s' "$path"
  fi
}

MICRO6_WSL="$(to_wsl "$MICRO6_DIR")"
CHROMA_WSL="$(to_wsl "$CHROMA_DIR")"

FIXTURES=(
  tick-batch-v2-golden.json
  tick-batch-canonical-vectors.json
  recording-action-checksum-vectors.json
  tick-batch-fishing-golden.json
  tick-batch-fixture-manifest.json
)

failures=0
for fixture in "${FIXTURES[@]}"; do
  micro6_file="$MICRO6_WSL/runelite-client/src/test/resources/$fixture"
  chroma_file="$CHROMA_WSL/src/test/resources/$fixture"
  if [[ ! -f "$micro6_file" ]]; then
    echo "FAIL: missing Micro6 fixture: $micro6_file"
    failures=$((failures + 1))
    continue
  fi
  if [[ ! -f "$chroma_file" ]]; then
    echo "FAIL: missing ChromaScape fixture: $chroma_file"
    failures=$((failures + 1))
    continue
  fi
  micro6_hash="$(sha256sum "$micro6_file" | cut -d' ' -f1)"
  chroma_hash="$(sha256sum "$chroma_file" | cut -d' ' -f1)"
  if [[ "$micro6_hash" != "$chroma_hash" ]]; then
    echo "FAIL: $fixture differs (micro6=$micro6_hash chromascape=$chroma_hash)"
    failures=$((failures + 1))
  else
    echo "PASS: $fixture $micro6_hash"
  fi
done

if [[ "$failures" -gt 0 ]]; then
  echo "certify-tick-batch-fixtures: $failures failure(s)"
  exit 1
fi
echo "certify-tick-batch-fixtures: all ${#FIXTURES[@]} fixtures identical"
