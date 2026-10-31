#!/usr/bin/env python3
"""Observe the systemd scopes that SC-2150 preview jobs run in (Step 3 containment proof).

Schedules frame-preview jobs on a disposable loopback verification server and samples every new
`chromascape-video-*` user scope until each job is terminal, then records the scopes' final
state. The receipt is the evidence that a job's processes were contained and gone before the
server reported cleanup.

What this probe records, and why it differs from the earlier ad-hoc probe:

* `systemctl show` is parsed as named `key=value` lines. systemd neither keeps the requested
  property order nor prints absent properties, so zipping `--value` output with the requested
  list mislabels values (memory sizes appeared as MainPID).
* A cgroup whose `cgroup.procs` cannot be read is `READ_FAILED` with its error, never an empty
  member list. Only a successful read of an empty file is `EMPTY`.
* Each member process is identified by PID plus its kernel start time, parent, session and
  command, so a reused PID cannot pass for the job's process.

Refuses anything that is not a loopback verification instance, and refuses the owner's 8090
service. The receipt is written under `build/` (never into `docs/`).
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

ALLOWED_HOSTS = ("127.0.0.1", "localhost", "::1")
REFUSED_PORTS = (8090,)
SCOPE_PATTERN = "chromascape-video-*"
SHOW_PROPERTIES = (
    "Id",
    "ActiveState",
    "SubState",
    "Result",
    "ControlGroup",
    "MainPID",
    "TasksCurrent",
    "MemoryCurrent",
    "InactiveEnterTimestampMonotonic",
)
TERMINAL_JOB_STATES = ("SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_RECONCILIATION")


def parse_show(text):
    """Parses `systemctl show -p ...` output into a dict keyed by property name.

    Lines are `Name=value`; a value may itself contain `=`. Properties systemd did not print are
    simply absent from the result, so a caller can tell "not reported" from an empty value.
    """
    properties = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        name, value = line.split("=", 1)
        properties[name] = value
    return properties


def read_members(cgroup, cgroup_root="/sys/fs/cgroup"):
    """Reads a cgroup's member PIDs.

    Returns {"state": "MEMBERS"|"EMPTY"|"NO_CGROUP"|"READ_FAILED", "pids": [...], "error": ...}.
    An unreadable or vanished cgroup is never reported as EMPTY.
    """
    if not cgroup:
        return {"state": "NO_CGROUP", "pids": [], "error": None}
    path = Path(cgroup_root) / cgroup.lstrip("/") / "cgroup.procs"
    try:
        text = path.read_text(encoding="ascii")
    except OSError as error:
        return {"state": "READ_FAILED", "pids": [], "error": f"{type(error).__name__}: {error.strerror}"}
    pids = [int(token) for token in text.split()]
    return {"state": "MEMBERS" if pids else "EMPTY", "pids": pids, "error": None}


def parse_proc_stat(text):
    """Parses /proc/<pid>/stat. The command is parenthesised and may contain spaces or ')'."""
    open_paren = text.index("(")
    close_paren = text.rindex(")")
    rest = text[close_paren + 2:].split()
    # rest[0] is field 3 (state); field N is rest[N - 3].
    return {
        "comm": text[open_paren + 1:close_paren],
        "state": rest[0],
        "ppid": int(rest[1]),
        "sid": int(rest[3]),
        "start_ticks": int(rest[19]),
    }


def process_identity(pid, proc_root="/proc"):
    """Returns pid plus kernel start time, parent, session and command, or a read failure."""
    base = Path(proc_root) / str(pid)
    try:
        identity = {"pid": pid, **parse_proc_stat((base / "stat").read_text(encoding="utf-8"))}
    except (OSError, ValueError, IndexError) as error:
        return {"pid": pid, "error": f"{type(error).__name__}: {error}"}
    try:
        raw = (base / "cmdline").read_bytes()
        identity["cmdline"] = [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part][:12]
    except OSError as error:
        identity["cmdline_error"] = f"{type(error).__name__}: {error.strerror}"
    return identity


def require_loopback(base_url):
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme != "http" or parsed.hostname not in ALLOWED_HOSTS:
        raise SystemExit(f"refusing non-loopback target {base_url}")
    if parsed.port in REFUSED_PORTS:
        raise SystemExit(f"refusing the owner's service on port {parsed.port}")
    return parsed


def require_build_output(path):
    resolved = Path(path).resolve()
    build = (Path.cwd() / "build").resolve()
    if build not in resolved.parents:
        raise SystemExit(f"receipt must be written under {build}")
    return resolved


class Systemd:
    """Reads user-scope state through `systemctl --user`. Never stops or kills anything."""

    def __init__(self, runner=subprocess.run):
        self.run = runner

    def units(self):
        result = self.run(["systemctl", "--user", "list-units", SCOPE_PATTERN, "--all", "--no-legend",
                           "--plain"], capture_output=True, text=True)
        return {line.split()[0] for line in result.stdout.splitlines() if line.strip()}

    def show(self, unit):
        result = self.run(["systemctl", "--user", "show", unit, "-p", ",".join(SHOW_PROPERTIES)],
                          capture_output=True, text=True)
        record = {"unit": unit, "monotonic": time.monotonic(), "show_exit": result.returncode,
                  "properties": parse_show(result.stdout)}
        if result.returncode != 0:
            record["show_error"] = result.stderr.strip()[:300]
        members = read_members(record["properties"].get("ControlGroup", ""))
        members["identities"] = [process_identity(pid) for pid in members["pids"]]
        record["members"] = members
        return record


class Api:
    def __init__(self, base_url):
        self.root = base_url.rstrip("/")
        self.base = self.root + "/api/debug/mouse-videos"

    def call(self, method, path, body=None, headers=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers or {})
        if data is not None:
            request.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"{}")


def probe_job(api, systemd, source, analysis, ordinal, timeout):
    """Schedules one preview job and samples its scopes until the job is terminal."""
    _, nonce = api.call("GET", "/write-nonce")
    before = systemd.units()
    request_id = str(uuid.uuid4())
    status, receipt = api.call(
        "POST", f"/sources/{source}/analyses/{analysis}/frame-previews",
        {"requestId": request_id, "frameOrdinal": ordinal},
        {"X-Mouse-Video-Nonce": nonce["nonce"], "Origin": api.root})
    scopes = {}
    job = None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for unit in systemd.units() - before:
            scopes.setdefault(unit, {"samples": []})
        for unit, record in scopes.items():
            samples = record["samples"]
            if not samples or samples[-1]["properties"].get("ActiveState") in ("active", "activating", "deactivating"):
                samples.append(systemd.show(unit))
        if receipt.get("jobId"):
            try:
                _, job = api.call("GET", "/jobs/" + receipt["jobId"])
            except OSError:
                job = None
            if job and job.get("status") in TERMINAL_JOB_STATES:
                break
        time.sleep(0.02)
    time.sleep(1)
    final = {unit: systemd.show(unit) for unit in scopes}
    return {
        "requestId": request_id,
        "ordinal": ordinal,
        "scheduleStatus": status,
        "receipt": receipt,
        "job": job,
        "scopes": {unit: {"samples": record["samples"], "final": final[unit]} for unit, record in scopes.items()},
        "verdict": verdict(job, final),
    }


def verdict(job, final_scopes):
    """CONTAINED only if the job is terminal and every owned scope ended with a proven-empty cgroup."""
    if not job or job.get("status") not in TERMINAL_JOB_STATES:
        return "JOB_NOT_TERMINAL"
    if not final_scopes:
        return "NO_SCOPE_OBSERVED"
    for record in final_scopes.values():
        members = record["members"]
        if members["state"] == "MEMBERS":
            return "SURVIVORS"
        if members["state"] == "READ_FAILED":
            return "UNPROVEN"
        if record["properties"].get("ActiveState") not in ("inactive", "failed", None):
            return "SCOPE_STILL_ACTIVE"
    return "CONTAINED"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True, help="loopback verification server, e.g. http://127.0.0.1:18190")
    parser.add_argument("--source", required=True)
    parser.add_argument("--analysis", required=True)
    parser.add_argument("--ordinals", required=True, help="comma-separated frame ordinals, one job each")
    parser.add_argument("--timeout", type=float, default=120.0, help="seconds per job")
    parser.add_argument("--out", required=True, help="receipt path under build/")
    args = parser.parse_args(argv)
    require_loopback(args.base_url)
    out = require_build_output(args.out)
    api, systemd = Api(args.base_url), Systemd()
    jobs = [probe_job(api, systemd, args.source, args.analysis, int(o), args.timeout)
            for o in args.ordinals.split(",") if o.strip()]
    summary = {}
    for job in jobs:
        summary[job["verdict"]] = summary.get(job["verdict"], 0) + 1
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"schema": "sc2150-containment-probe/v2", "baseUrl": args.base_url,
                               "summary": summary, "jobs": jobs}, indent=1), encoding="utf-8")
    print(json.dumps(summary))
    return 0 if set(summary) == {"CONTAINED"} else 1


if __name__ == "__main__":
    sys.exit(main())
