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
  local name="$1"; local repo="$2"; local status_file="$OUT_DIR/${name}.tree-status"; local error_file="$OUT_DIR/${name}.tree-error"
  : >"$status_file"; : >"$error_file"
  if ! git -C "$repo" status --porcelain=v1 >"$status_file" 2>"$error_file"; then
    printf 'UNAVAILABLE\n' >"$status_file"
    GATES["${name}_clean"]="FAIL"
    return 0
  fi
  if [[ ! -s "$status_file" ]]; then GATES["${name}_clean"]="PASS"; else GATES["${name}_clean"]="FAIL"; fi
}
check_tree micro6 "$MICRO6_ROOT"
check_tree chromascape "$ROOT"
check_tree leeuwbotapi "$LEEUWBOT_API_ROOT"

run_gate leeuwbot_go_tests bash -c "cd '$LEEUWBOT_API_ROOT/plugin-service' && go test ./..."
run_gate leeuwbot_go_build bash -c "cd '$LEEUWBOT_API_ROOT/plugin-service' && go build ./..."
run_gate chromascape_tests bash -c "cd '$ROOT' && JAVA_HOME=\"${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\" ./gradlew --offline --no-daemon --no-watch-fs --max-workers=1 test"
run_gate micro6_manifest_build bash -c "cd '$MICRO6_ROOT' && JAVA_HOME=\"${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}\" ./gradlew --offline --no-daemon --no-watch-fs --max-workers=1 :runelite-client:generateAllTaskSourceBundles --rerun-tasks"

python3 - "$ROOT" "$MICRO6_ROOT" "$LEEUWBOT_API_ROOT" "$OUT_DIR" "$MICRO6_REV" "$CHROMA_REV" "$API_REV" <<'PY'
import hashlib, json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
micro6 = pathlib.Path(sys.argv[2])
api = pathlib.Path(sys.argv[3])
out = pathlib.Path(sys.argv[4])
micro6_rev, chroma_rev, api_rev = sys.argv[5:]

bundle_root = pathlib.Path(os.environ.get("MICRO6_SOURCE_BUNDLE_ROOT", ""))
manifest_path = pathlib.Path()
current_path = bundle_root / "current.json"
if current_path.is_file():
    try:
        current = json.loads(current_path.read_text())
        bundle_id = current["bundle_id"]
        manifest_path = bundle_root / "bundles" / bundle_id / "manifests" / "FISHING_TRAINING.json"
    except Exception:
        manifest_path = pathlib.Path()
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
    status = status_file.read_text().strip() if status_file.exists() else "UNAVAILABLE"
    identity["tree_status"][name] = "CLEAN" if status == "" else ("UNAVAILABLE" if status == "UNAVAILABLE" else "DIRTY")
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

# Candidate registration is derived only from an actual authenticated registry transaction and
# canonical GET/download readback. Missing worker artifacts or credentials is a hard FAIL.
import urllib.request, urllib.error
candidate_manifest_path = pathlib.Path(os.environ.get("SC967_CANDIDATE_MANIFEST", ""))
candidate_jar_path = pathlib.Path(os.environ.get("SC967_CANDIDATE_JAR", ""))
candidate_sbom_path = pathlib.Path(os.environ.get("SC967_CANDIDATE_SBOM", ""))
candidate_cert_path = pathlib.Path(os.environ.get("SC967_CANDIDATE_CERTIFICATION", ""))
registry_url = os.environ.get("SC967_CANDIDATE_REGISTRY_URL", "").rstrip("/")
registry_token = os.environ.get("SC967_CANDIDATE_REGISTRY_TOKEN", "")
candidate_ok = False
if registry_url and registry_token and all(path.is_file() for path in (candidate_manifest_path, candidate_jar_path, candidate_sbom_path, candidate_cert_path)):
    try:
        candidate = json.loads(candidate_manifest_path.read_text())
        candidate_id = candidate["candidate"]["candidate_id"]
        boundary = "----sc967-demo-" + hashlib.sha256(candidate_manifest_path.read_bytes()).hexdigest()[:16]
        parts = [("manifest", candidate_manifest_path, "application/json"), ("artifact", candidate_jar_path, "application/java-archive"), ("sbom", candidate_sbom_path, "application/json"), ("certification", candidate_cert_path, "application/json")]
        body = bytearray()
        for name, path, content_type in parts:
            body.extend((f"--{boundary}\\r\\nContent-Disposition: form-data; name=\\\"{name}\\\"; filename=\\\"{path.name}\\\"\\r\\nContent-Type: {content_type}\\r\\n\\r\\n").encode())
            body.extend(path.read_bytes()); body.extend(b"\\r\\n")
        body.extend(f"--{boundary}--\\r\\n".encode())
        headers = {"Authorization": "Bearer " + registry_token, "Content-Type": "multipart/form-data; boundary=" + boundary, "Idempotency-Key": "sha256:" + hashlib.sha256(candidate_manifest_path.read_bytes()).hexdigest()}
        try:
            request = urllib.request.Request(registry_url + "/api/v1/candidates", data=bytes(body), headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status not in (201, 409): raise RuntimeError("registry registration status")
        except urllib.error.HTTPError as error:
            if error.code != 409: raise
        def get_json(path):
            request = urllib.request.Request(registry_url + path, headers={"Authorization": "Bearer " + registry_token})
            with urllib.request.urlopen(request, timeout=30) as response: return json.loads(response.read())
        stored = get_json("/api/v1/candidates/" + candidate_id)
        stored_manifest = stored.get("manifest", {})
        comparable = json.loads(json.dumps(stored_manifest))
        if isinstance(comparable.get("identity"), dict): comparable["identity"].pop("candidate", None)
        if stored.get("state") != "CERTIFIED_CANDIDATE" or json.dumps(comparable, sort_keys=True, separators=(",", ":")) != json.dumps(candidate, sort_keys=True, separators=(",", ":")): raise RuntimeError("registry manifest mismatch")
        request = urllib.request.Request(registry_url + "/api/v1/candidates/" + candidate_id + "/download", headers={"Authorization": "Bearer " + registry_token})
        with urllib.request.urlopen(request, timeout=30) as response: downloaded = response.read()
        if response.headers.get("X-Candidate-ID") != candidate_id or response.headers.get("X-Artifact-SHA256") != candidate["jar_sha256"] or downloaded != candidate_jar_path.read_bytes(): raise RuntimeError("registry artifact mismatch")
        candidate_ok = True
    except Exception as exc:
        identity["candidate_registry_error"] = str(exc)
gates["candidate_registry"] = "PASS" if candidate_ok else "FAIL"
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
