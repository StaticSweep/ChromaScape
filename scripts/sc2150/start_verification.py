#!/usr/bin/env python3
"""Start a disposable loopback verification instance from an immutable runtime snapshot.

Never starts the normal 8090 service. A separate disposable database must already exist.
Only process-local configuration changes; prints PID and readiness without credentials.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--port", type=int, default=18190)
    parser.add_argument("--owner", default="sc2150-fedora-a")
    parser.add_argument("--profile", default="local-development")
    parser.add_argument("--disable-workers", action="store_true")
    args = parser.parse_args()
    assert args.database.startswith("sc2150_") and args.database.replace("_", "").isalnum()
    assert 18190 <= args.port <= 18199
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    pid_file = output / "server.pid"
    if pid_file.exists():
        proc = Path("/proc") / pid_file.read_text().strip()
        if (proc / "cmdline").exists() and (proc / "cmdline").read_bytes():
            raise RuntimeError("Recorded PID is still alive; stop the verified owned JVM before replacing its runtime snapshot")
    snapshot = output / "runtime"
    snapshot.mkdir(exist_ok=True)
    entries = []
    for i, entry in enumerate(args.classpath.read_text().split(os.pathsep)):
        source = Path(entry)
        if source.is_dir():
            target = snapshot / str(i)
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(source, target)
            static_script = target / "static/js/mouse-video.js"
            checked_script = Path("src/main/resources/static/js/mouse-video.js")
            if static_script.exists() and checked_script.exists():
                shutil.copy2(checked_script, static_script)
            entries.append(str(target))
        else:
            entries.append(entry)
    env = os.environ.copy()
    local = Path(".env.local")
    if local.exists():
        for line in local.read_text().splitlines():
            if "=" not in line or line.lstrip().startswith("#"):
                continue
            key, value = line.split("=", 1)
            if key.startswith(("CHROMASCAPE_SOURCE_", "MICRO6_SOURCE_")) or key in (
                "STABILIZATION_AUTH_TOKEN", "STABILIZATION_AUTH_WRITE_TOKEN"
            ):
                env[key] = value.strip().strip('"').strip("'")
    env.update(
        SPRING_PROFILES_ACTIVE=args.profile,
        SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(args.port),
        SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:5433/{args.database}?sslmode=disable",
        CHROMASCAPE_MOUSE_VIDEO_ENABLED=str(not args.disable_workers).lower(),
        CHROMASCAPE_MOUSE_VIDEO_YOUTUBE_ENABLED="false",
        CHROMASCAPE_MOUSE_VIDEO_DIRECTORY=str(output.parent / "media"),
        CHROMASCAPE_MOUSE_VIDEO_RESTRICTED_LAUNCHER="true",
        CHROMASCAPE_MOUSE_VIDEO_MAX_JOB_DISK_BYTES="134217728",
        CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED="false",
        OVERLAY_BROADCASTER_ENABLED="false", MCP_AUTO_START="false",
    )
    command = [args.java, "-Xmx768m", "-Duser.name=" + args.owner, "-cp", os.pathsep.join(entries),
               "com.chromascape.web.ChromaScapeApplication", "--analysis.mcp.auto-start=false",
               "--overlay-broadcaster.enabled=false", "--leeuwbot.integration.enabled=false",
               "--leeuwbot.integration.stream.enabled=false", "--logging.level.root=INFO",
               "--logging.level.com.chromascape=INFO", "--logging.level.org.hibernate=INFO"]
    with (output / "server.log").open("w") as log:
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (output / "server.pid").write_text(str(process.pid))
    (output / "launch.json").write_text(json.dumps({"pid": process.pid, "port": args.port,
        "owner": args.owner, "database": args.database, "profile": args.profile,
        "runtimeSnapshot": str(snapshot), "youtubeEnabled": False}, indent=2))
    print("Started owned verification JVM", process.pid, "on", args.port, flush=True)
    deadline = time.monotonic() + 180
    while process.poll() is None and time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{args.port}/api/debug/mouse-videos/capabilities", timeout=2) as response:
                assert response.status == 200
                print("READY", flush=True)
                return
        except urllib.error.HTTPError as error:
            if error.code == 401 and args.profile != "local-development":
                print("READY anonymous API returns 401", flush=True)
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(.5)
    raise RuntimeError("Verification instance failed readiness; inspect its server.log and owned PID")


if __name__ == "__main__":
    main()
