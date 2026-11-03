#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# A main-based runtime may live in an isolated checkout while this working tree is preserved.
RUNTIME_PROJECT_DIR="$(python3 - "$PROJECT_DIR" <<'PYRUNTIME'
import os, sys
from pathlib import Path
project = Path(sys.argv[1]).resolve()
value = os.environ.get("CHROMASCAPE_FEDORA_RUNTIME_PROJECT_DIR", "")
local = project / ".env.local"
if not value and local.is_file():
    for line in local.read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and line.split("=", 1)[0].strip() == "CHROMASCAPE_FEDORA_RUNTIME_PROJECT_DIR":
            value = line.split("=", 1)[1].strip().strip("\"'")
if value:
    target = Path(value)
    if not target.is_absolute() or not (target / "scripts/start-fedora-dev.sh").is_file():
        raise SystemExit("Configured Fedora runtime checkout is unavailable")
    if target.resolve() != project:
        print(target.resolve())
PYRUNTIME
)"
if [[ -n "$RUNTIME_PROJECT_DIR" ]]; then
    exec "$RUNTIME_PROJECT_DIR/scripts/start-fedora-dev.sh" "$@"
fi

MICRO6_DIR="$(cd "$PROJECT_DIR/../micro6" && pwd)"

if [ "$(uname -s)" != "Linux" ]; then
    echo "This launcher is only for the Fedora development environment." >&2
    exit 1
fi

# Fixed loopback endpoints prevent this development process from registering
# with or dispatching work to the Windows production-like environment.
export LEEUWBOT_ENVIRONMENT="fedora-development"
export SPRING_PROFILES_ACTIVE="fedora-development"
export SERVER_ADDRESS="127.0.0.1"
export SERVER_PORT="8090"
export LEEUWBOT_BRIDGE_URL="http://127.0.0.1:8092"
export LEEUWBOT_STREAM_URL="ws://127.0.0.1:8084/ws/screens/agent"
export LEEUWBOT_STREAM_HOST_ID="fedora-development"
export BUILDER_GOAL_LAUNCHER_BASE_URL="http://127.0.0.1:8084"
export BUILDER_GOAL_ACCOUNT_CATALOG_BASE_URL="http://127.0.0.1:8092"
export CHROMASCAPE_PATCHLOOP_MICRO6_EXECUTOR_BASE_URL="http://127.0.0.1:8098"
export MICRO6_EXECUTOR_BASE_URL="http://127.0.0.1:8098"
export GAMEPLAY_BOOTSTRAP_MICRO6_URL="http://127.0.0.1:8098"
export LEEUWBOT_CANDIDATE_REGISTRY_URL="http://127.0.0.1:8081"
export BUILDER_GOAL_MICRO6_PROJECT_DIR="$MICRO6_DIR"
export CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED="false"
export CHROMASCAPE_BUILD_ID="fedora-development"
export CHROMASCAPE_LOG_LEVEL="${CHROMASCAPE_LOG_LEVEL:-info}"
export CHROMASCAPE_API_KEY="${CHROMASCAPE_API_KEY:-dev-token}"
export BUILDER_GOAL_LAUNCHER_API_TOKEN="${BUILDER_GOAL_LAUNCHER_API_TOKEN:-devtoken123}"

echo "Starting Fedora-only ChromaScape at http://127.0.0.1:8090"
# Load source/auth settings without importing another host's identity or endpoints.
exec python3 - "$PROJECT_DIR" "$@" <<'PYENV'
import os
import re
import secrets
import sys
from pathlib import Path

project = Path(sys.argv[1])
local_env = project / ".env.local"
if local_env.is_file():
    for line in local_env.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = (part.strip() for part in line.split("=", 1))
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            continue
        if not (name.startswith(("CHROMASCAPE_SOURCE_", "MICRO6_SOURCE_",
                                 "CHROMASCAPE_MOUSE_VIDEO_"))
                or name in ("STABILIZATION_AUTH_TOKEN", "STABILIZATION_AUTH_WRITE_TOKEN",
                            "JAVA_HOME", "GRADLE_USER_HOME")):
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if value and not os.environ.get(name):
            os.environ[name] = value
for name in ("STABILIZATION_AUTH_TOKEN", "STABILIZATION_AUTH_WRITE_TOKEN"):
    if not os.environ.get(name):
        os.environ[name] = secrets.token_hex(32)
os.execv(str(project / "gradlew"), [str(project / "gradlew"), "--no-daemon", "bootRun", *sys.argv[2:]])
PYENV
