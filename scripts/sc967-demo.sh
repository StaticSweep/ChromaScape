#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MICRO6_ROOT="${MICRO6_ROOT:-$ROOT/../micro6}"
LEEUWBOT_API_ROOT="${LEEUWBOT_API_ROOT:-$ROOT/../leeuwbotbackend/leeuwbot-api/leeuwbotapi}"
OUT_DIR="${SC967_OUTPUT_DIR:-$ROOT/output/sc967}"
mkdir -p "$OUT_DIR"

export FEATURES_AI_PLAYER_CONTROL=false

declare -A GATES
GATE_DETAILS=()
run_gate() {
  local name="$1"; shift
  local log_file="$OUT_DIR/${name}.log"
  "$@" >"$log_file" 2>&1
  local code=$?
  if [[ $code -eq 0 ]]; then
    GATES["$name"]="PASS"
  else
    GATES["$name"]="FAIL"
  fi
  printf '%s\n' "$code" >"$OUT_DIR/${name}.exit"
  GATE_DETAILS+=("$name|$code|$log_file")
  return 0
}

revision() {
  git -C "$1" rev-parse HEAD 2>/dev/null || printf 'UNAVAILABLE'
}
sha256_file() {
  python3 - "$1" <<'PY'
import hashlib, sys
print("sha256:" + hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
}

# A clean-checkout demo must not silently run against an unrelated revision.
MICRO6_REV="$(revision "$MICRO6_ROOT")"
CHROMA_REV="$(revision "$ROOT")"
API_REV="$(revision "$LEEUWBOT_API_ROOT")"
EXPECTED_MICRO6="${SC967_EXPECTED_MICRO6_REV:-08fd5930f04fe2e255c6fbcc1a05d07b1f53aa52}"
EXPECTED_CHROMA="${SC967_EXPECTED_CHROMA_REV:-7628b381687ac43a4a67864057c1d8f1a86885fd}"
EXPECTED_API="${SC967_EXPECTED_API_REV:-42bf2a60605b610eaf32862d6b2ed751c868ac3e}"
if [[ "${SC967_ALLOW_REVISIONS:-false}" != "true" ]] && { [[ "$MICRO6_REV" != "$EXPECTED_MICRO6" ]] || [[ "$CHROMA_REV" != "$EXPECTED_CHROMA" ]] || [[ "$API_REV" != "$EXPECTED_API" ]]; }; then
  echo "SC-967 revision guard failed; set SC967_ALLOW_REVISIONS=true only with explicitly inspected replacements" >&2
  exit 2
fi

check_tree() {
  local name="$1"; local repo="$2"; local status_file="$OUT_DIR/${name}.tree-status"
  git -C "$repo" status --porcelain=v1 >"$status_file" 2>&1 || true
  if [[ ! -s "$status_file" ]]; then GATES["${name}_clean"]="PASS"; else GATES["${name}_clean"]="FAIL"; fi
}
check_tree micro6 "$MICRO6_ROOT"
check_tree chromascape "$ROOT"
check_tree leeuwbotapi "$LEEUWBOT_API_ROOT"

run_gate leeuwbot_go_tests bash -c "cd '$LEEUWBOT_API_ROOT/plugin-service' && go test ./..."
run_gate leeuwbot_go_build bash -c "cd '$LEEUWBOT_API_ROOT/plugin-service' && go build ./..."
run_gate chromascape_tests bash -c "cd '$ROOT' && JAVA_HOME=\"${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\" ./gradlew --offline --no-daemon --no-watch-fs --max-workers=1 test"
run_gate micro6_manifest_build bash -c "cd '$MICRO6_ROOT' && JAVA_HOME=\"${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\" ./gradlew --offline --no-daemon --no-watch-fs --max-workers=1 :runelite-client:generateFishingSourceManifest --rerun-tasks"

python3 - "$ROOT" "$MICRO6_ROOT" "$LEEUWBOT_API_ROOT" "$OUT_DIR" "$MICRO6_REV" "$CHROMA_REV" "$API_REV" <<'PY'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1])
micro6 = pathlib.Path(sys.argv[2])
api = pathlib.Path(sys.argv[3])
out = pathlib.Path(sys.argv[4])
micro6_rev, chroma_rev, api_rev = sys.argv[5:]

manifest_path = micro6 / "runelite-client/build/generated/fishing-source-manifest/micro6-contracts/fishing-source-manifest.json"
fixtures = [
    root / "src/test/resources/sc967/micro6-pass-recording-v1.json",
    root / "src/test/resources/sc967/runelite-fishing-capture-v1.json",
    root / "src/test/resources/sc967/fishing-seeded-fail-source-v1.json",
]
gates = {}
for log in out.glob("*.log"):
    name = log.stem
    # Exit code is persisted by the shell after this script's first pass.
    code_file = out / f"{name}.exit"
    gates[name] = "PASS" if code_file.exists() and code_file.read_text().strip() == "0" else "FAIL"

def file_hash(path):
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()

identity = {"control_allowed": False, "features_ai_player_control": False}
identity["tree_status"] = {}
for name in ("micro6", "chromascape", "leeuwbotapi"):
    status_file = out / f"{name}.tree-status"
    identity["tree_status"][name] = "CLEAN" if status_file.exists() and not status_file.read_text().strip() else "DIRTY"
    gates[f"{name}_tree_state"] = "PASS" if identity["tree_status"][name] == "CLEAN" else "FAIL"
if manifest_path.is_file():
    try:
        source = json.loads(manifest_path.read_text())
        identity["micro6_source_snapshot_id"] = source["source_snapshot_id"]
        identity["micro6_source_manifest_hash"] = source["source_manifest_hash"]
        identity["micro6_source_files"] = len(source["files"])
        gates["source_manifest_json"] = "PASS"
    except Exception as exc:
        identity["source_manifest_error"] = str(exc)
        gates["source_manifest_json"] = "FAIL"
else:
    gates["source_manifest_json"] = "FAIL"

if all(path.is_file() for path in fixtures):
    identity["fixture_hashes"] = {path.name: file_hash(path) for path in fixtures}
    gates["fixtures"] = "PASS"
else:
    gates["fixtures"] = "FAIL"

# Candidate registration is deliberately not inferred from files or configuration. Until the
# real isolated worker and authenticated registry readback produce evidence, this gate is FAIL.
gates.setdefault("candidate_registry", "FAIL")
all_pass = bool(gates) and all(value == "PASS" for value in gates.values())
manifest = {
    "schema": "sc967-demo-manifest/v1",
    "repositories": {"micro6": micro6_rev, "chromascape": chroma_rev, "leeuwbotapi": api_rev},
    "task": {
        "task_key": "FISHING_TRAINING",
        "pass_manifest_hash": "sha256:48828b3b91182c2a4425856ca3d5c4efba53031540cbe8f9d9a2fdd3955d4034",
        "fail_manifest_hash": "sha256:b201629e712be24815e43551a5185a2b45fe3a0b77b2aca8f2a51dd250917956",
    },
    "identity": identity,
    "gates": dict(sorted(gates.items())),
    "overall": "PASS" if all_pass else "FAIL",
}
unsigned = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
manifest["manifest_hash"] = "sha256:" + hashlib.sha256(unsigned).hexdigest()
(out / "sc-967-demo-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
print(out / "sc-967-demo-manifest.json")
if not all_pass:
    sys.exit(1)
PY
