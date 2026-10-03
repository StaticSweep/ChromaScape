#!/usr/bin/env python3
"""Create, update, and verify honest SC-1668 host qualification records."""

from __future__ import annotations

import argparse
from collections import Counter
import datetime as dt
import json
import os
import pathlib
import re
import tempfile
from typing import Any

SCHEMA_VERSION = 1
STORY = "SC-1668"
UPSTREAM_REVISION = "5ea3b995f5d13e19c592a6399973fe7dadb5e656"
ROLES = ("fedora-primary", "fedora-secondary", "windows")
STATUSES = ("PASS", "FAIL", "NOT_RUN")
CHECKS = {
    "fedora-primary": (
        "build",
        "discovery",
        "targetSelection",
        "attachment",
        "input",
        "capture",
        "recording",
        "debugger",
        "multiClientIsolation",
        "remoteLaunch",
        "resizeMinimize",
        "targetRestart",
        "repeatedAttachment",
        "release",
    ),
    "fedora-secondary": (
        "build",
        "discovery",
        "targetSelection",
        "attachment",
        "input",
        "capture",
        "recording",
        "debugger",
        "multiClientIsolation",
        "remoteLaunch",
        "resizeMinimize",
        "targetRestart",
        "repeatedAttachment",
        "release",
    ),
    "windows": (
        "build",
        "discovery",
        "targetSelection",
        "input",
        "capture",
        "recording",
        "debugger",
        "replay",
        "remoteLaunch",
        "release",
    ),
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
SAFE_HOST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")


class EvidenceError(ValueError):
    """Raised when a qualification record cannot support its claim."""


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def atomic_write(path: pathlib.Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(payload, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def new_record(role: str, host_id: str, revision: str) -> dict[str, Any]:
    if role not in ROLES:
        raise EvidenceError(f"unsupported role: {role}")
    if not SAFE_HOST_ID.fullmatch(host_id):
        raise EvidenceError("host-id must be 1-64 safe identifier characters")
    if not GIT_SHA.fullmatch(revision):
        raise EvidenceError("revision must be a full lowercase Git SHA")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "story": STORY,
        "role": role,
        "hostId": host_id,
        "applicationRevision": revision,
        "upstreamRevision": UPSTREAM_REVISION,
        "capturedAtUtc": utc_now(),
        "versions": {
            "os": "NOT_RECORDED",
            "arch": "NOT_RECORDED",
            "java": "NOT_RECORDED",
            "client": "NOT_RECORDED",
            "nativeArtifactSha256": "NOT_APPLICABLE" if role == "windows" else "NOT_RECORDED",
        },
        "checks": {
            name: {"status": "NOT_RUN", "evidence": "not executed"} for name in CHECKS[role]
        },
    }


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise EvidenceError(f"cannot read {path}: {failure}") from failure
    if not isinstance(value, dict):
        raise EvidenceError(f"{path}: root must be an object")
    return value


def validate_record(record: dict[str, Any], source: str, require_complete: bool) -> list[str]:
    errors: list[str] = []
    role = record.get("role")
    if record.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"{source}: schemaVersion must be {SCHEMA_VERSION}")
    if record.get("story") != STORY:
        errors.append(f"{source}: story must be {STORY}")
    if role not in ROLES:
        errors.append(f"{source}: unsupported role {role!r}")
        return errors
    if not SAFE_HOST_ID.fullmatch(str(record.get("hostId", ""))):
        errors.append(f"{source}: invalid hostId")
    if not GIT_SHA.fullmatch(str(record.get("applicationRevision", ""))):
        errors.append(f"{source}: applicationRevision must be a full lowercase Git SHA")
    if record.get("upstreamRevision") != UPSTREAM_REVISION:
        errors.append(f"{source}: upstreamRevision does not match the pinned source")
    if not UTC_TIMESTAMP.fullmatch(str(record.get("capturedAtUtc", ""))):
        errors.append(f"{source}: capturedAtUtc must be a whole-second UTC timestamp")
    versions = record.get("versions")
    if not isinstance(versions, dict):
        errors.append(f"{source}: versions must be an object")
    else:
        for key in ("os", "arch", "java", "client", "nativeArtifactSha256"):
            value = versions.get(key)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{source}: versions.{key} must be non-empty")
        native_sha = versions.get("nativeArtifactSha256")
        if role != "windows" and native_sha != "NOT_RECORDED" and not SHA256.fullmatch(str(native_sha)):
            errors.append(f"{source}: Fedora nativeArtifactSha256 must be SHA-256 or NOT_RECORDED")
        if role == "windows" and native_sha != "NOT_APPLICABLE":
            errors.append(f"{source}: Windows nativeArtifactSha256 must be NOT_APPLICABLE")
        if require_complete:
            for key in ("os", "arch", "java", "client"):
                if versions.get(key) == "NOT_RECORDED":
                    errors.append(f"{source}: versions.{key} is NOT_RECORDED, required exact value")
            if role != "windows" and not SHA256.fullmatch(str(native_sha)):
                errors.append(f"{source}: Fedora nativeArtifactSha256 is required for completion")
    checks = record.get("checks")
    if not isinstance(checks, dict):
        errors.append(f"{source}: checks must be an object")
        return errors
    expected = set(CHECKS[role])
    actual = set(checks)
    if actual != expected:
        errors.append(
            f"{source}: checks differ; missing={sorted(expected - actual)} extra={sorted(actual - expected)}"
        )
    for name in sorted(expected & actual):
        result = checks[name]
        if not isinstance(result, dict):
            errors.append(f"{source}: checks.{name} must be an object")
            continue
        status = result.get("status")
        evidence = result.get("evidence")
        if status not in STATUSES:
            errors.append(f"{source}: checks.{name}.status is invalid")
        if not isinstance(evidence, str) or not evidence.strip():
            errors.append(f"{source}: checks.{name}.evidence must be non-empty")
        elif status == "PASS" and evidence.strip() == "not executed":
            errors.append(f"{source}: checks.{name} PASS cannot retain template evidence")
        if require_complete and status != "PASS":
            errors.append(f"{source}: checks.{name} is {status}, required PASS")
    return errors


def validate_matrix(paths: list[pathlib.Path], require_complete: bool) -> list[str]:
    records = [(path, load(path)) for path in paths]
    errors: list[str] = []
    for path, record in records:
        errors.extend(validate_record(record, str(path), require_complete))
    roles = [record.get("role") for _, record in records]
    if Counter(roles) != Counter(ROLES):
        errors.append(f"matrix must contain each role exactly once; got {roles}")
    revisions = {record.get("applicationRevision") for _, record in records}
    if len(revisions) != 1:
        errors.append("all hosts must qualify the exact same application revision")
    fedora_ids = {
        record.get("hostId") for _, record in records if str(record.get("role", "")).startswith("fedora-")
    }
    if len(fedora_ids) != 2:
        errors.append("Fedora qualification records must identify two distinct hosts")
    return errors


def command_init(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.output)
    if target.exists() and not args.force:
        raise EvidenceError(f"refusing to overwrite existing record: {target}")
    atomic_write(target, new_record(args.role, args.host_id, args.revision))
    print(f"SC1668_EVIDENCE status=INITIALIZED role={args.role} output={target}")


def command_record(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.file)
    record = load(target)
    role = record.get("role")
    if args.check not in CHECKS.get(role, ()):
        raise EvidenceError(f"check {args.check!r} is not defined for role {role!r}")
    record["checks"][args.check] = {"status": args.status, "evidence": args.evidence.strip()}
    record["capturedAtUtc"] = utc_now()
    errors = validate_record(record, str(target), False)
    if errors:
        raise EvidenceError("; ".join(errors))
    atomic_write(target, record)
    print(f"SC1668_EVIDENCE status=RECORDED role={role} check={args.check} result={args.status}")


def command_version(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.file)
    record = load(target)
    if args.name not in record.get("versions", {}):
        raise EvidenceError(f"unknown version field: {args.name}")
    value = args.value.strip()
    if not value:
        raise EvidenceError("version value must be non-empty")
    record["versions"][args.name] = value
    record["capturedAtUtc"] = utc_now()
    errors = validate_record(record, str(target), False)
    if errors:
        raise EvidenceError("; ".join(errors))
    atomic_write(target, record)
    print(f"SC1668_EVIDENCE status=VERSION_RECORDED role={record['role']} name={args.name}")


def command_verify(args: argparse.Namespace) -> None:
    paths = [pathlib.Path(path) for path in args.files]
    errors = validate_matrix(paths, args.require_complete)
    if errors:
        for error in errors:
            print(f"SC1668_EVIDENCE status=INVALID message={json.dumps(error)}")
        raise SystemExit(2)
    completion = "COMPLETE" if args.require_complete else "VALID"
    print(f"SC1668_EVIDENCE status={completion} hosts={len(paths)}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="create a fail-closed host record")
    init.add_argument("--role", choices=ROLES, required=True)
    init.add_argument("--host-id", required=True)
    init.add_argument("--revision", required=True)
    init.add_argument("--output", required=True)
    init.add_argument("--force", action="store_true")
    init.set_defaults(handler=command_init)
    record = commands.add_parser("record", help="record one observed check result")
    record.add_argument("--file", required=True)
    record.add_argument("--check", required=True)
    record.add_argument("--status", choices=STATUSES, required=True)
    record.add_argument("--evidence", required=True)
    record.set_defaults(handler=command_record)
    version = commands.add_parser("version", help="record one exact host/runtime version")
    version.add_argument("--file", required=True)
    version.add_argument("--name", choices=("os", "arch", "java", "client", "nativeArtifactSha256"), required=True)
    version.add_argument("--value", required=True)
    version.set_defaults(handler=command_version)
    verify = commands.add_parser("verify", help="validate the three-host matrix")
    verify.add_argument("--require-complete", action="store_true")
    verify.add_argument("files", nargs=3)
    verify.set_defaults(handler=command_verify)
    return root


def main() -> None:
    args = parser().parse_args()
    try:
        args.handler(args)
    except EvidenceError as failure:
        print(f"SC1668_EVIDENCE status=REFUSED message={json.dumps(str(failure))}")
        raise SystemExit(2) from failure


if __name__ == "__main__":
    main()
