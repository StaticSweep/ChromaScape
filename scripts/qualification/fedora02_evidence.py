#!/usr/bin/env python3
"""Create, update, and verify honest FEDORA-02 packaged-distribution records.

FEDORA-02 certifies that the ChromaScape tarball and RPM work independently of
the development checkout. This module owns the record shape and the release
gate. It deliberately knows nothing about how the host runs the checks: it only
refuses to let a claim be recorded that its own schema cannot support.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import tempfile
from collections import Counter
from typing import Any

SCHEMA_VERSION = 1
STORY = "FEDORA-02"
ROLES = ("tarball", "rpm")
STATUSES = ("PASS", "FAIL", "NOT_RUN")

# Checks shared by every form. They prove the packaged payload runs with no
# checkout, no build tools and no repository files, and that it leaves nothing
# behind after removal.
SHARED_CHECKS = (
    "launchWithoutBuildTools",
    "javaDetection",
    "nativeDetection",
    "loopbackBinding",
    "debuggerEndpoint",
    "targetDiscovery",
    "explicitSelection",
    "packagedNativeAttachment",
    "frameCapture",
    "boundedInput",
    "inputRelease",
    "cleanClose",
    "runtimeRestart",
    "targetRestart",
    "multipleClients",
    "upgrade",
    "rollback",
    "uninstall",
    "noResidue",
)

CHECKS = {
    "tarball": ("tarballInstallNoSudo",) + SHARED_CHECKS,
    "rpm": ("rpmDisposableInstall",) + SHARED_CHECKS,
}

SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
SAFE_HOST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")


class EvidenceError(ValueError):
    """Raised when a packaged-distribution record cannot support its claim."""


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


def new_record(role: str, host_id: str, revision: str, version: str) -> dict[str, Any]:
    if role not in ROLES:
        raise EvidenceError(f"unsupported role: {role}")
    if not SAFE_HOST_ID.fullmatch(host_id):
        raise EvidenceError("host-id must be 1-64 safe identifier characters")
    if not GIT_SHA.fullmatch(revision):
        raise EvidenceError("revision must be a full lowercase Git SHA")
    if not version.strip():
        raise EvidenceError("package version must be non-empty")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "story": STORY,
        "role": role,
        "hostId": host_id,
        "applicationRevision": revision,
        "packageVersion": version,
        "capturedAtUtc": utc_now(),
        "versions": {
            "os": "NOT_RECORDED",
            "arch": "NOT_RECORDED",
            "java": "NOT_RECORDED",
            "packageSha256": "NOT_RECORDED",
            "nativePairSha256": "NOT_RECORDED",
        },
        "checks": {name: {"status": "NOT_RUN", "evidence": "not executed"} for name in CHECKS[role]},
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
    if not str(record.get("packageVersion", "")).strip():
        errors.append(f"{source}: packageVersion must be non-empty")
    if not UTC_TIMESTAMP.fullmatch(str(record.get("capturedAtUtc", ""))):
        errors.append(f"{source}: capturedAtUtc must be a whole-second UTC timestamp")
    versions = record.get("versions")
    if not isinstance(versions, dict):
        errors.append(f"{source}: versions must be an object")
    else:
        for key in ("os", "arch", "java", "packageSha256", "nativePairSha256"):
            value = versions.get(key)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{source}: versions.{key} must be non-empty")
        for digest_key in ("packageSha256", "nativePairSha256"):
            digest = versions.get(digest_key)
            if digest not in ("NOT_RECORDED", "NOT_APPLICABLE") and not SHA256.fullmatch(str(digest)):
                errors.append(f"{source}: versions.{digest_key} must be SHA-256 or a marker")
        if require_complete:
            for key in ("os", "arch", "java"):
                if versions.get(key) == "NOT_RECORDED":
                    errors.append(f"{source}: versions.{key} is NOT_RECORDED, required exact value")
            if not SHA256.fullmatch(str(versions.get("packageSha256"))):
                errors.append(f"{source}: versions.packageSha256 is required for completion")
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
        errors.append(f"matrix must contain each package form exactly once; got {roles}")
    revisions = {record.get("applicationRevision") for _, record in records}
    if len(revisions) != 1:
        errors.append("both package forms must certify the exact same application revision")
    versions = {record.get("packageVersion") for _, record in records}
    if len(versions) != 1:
        errors.append("both package forms must certify the exact same package version")
    digests = [str(record.get("versions", {}).get("packageSha256")) for _, record in records]
    if all(SHA256.fullmatch(digest) for digest in digests) and len(set(digests)) != len(digests):
        errors.append("tarball and RPM must be distinct artifacts with distinct digests")
    return errors


def command_init(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.output)
    if target.exists() and not args.force:
        raise EvidenceError(f"refusing to overwrite existing record: {target}")
    atomic_write(target, new_record(args.role, args.host_id, args.revision, args.version))
    print(f"FEDORA02_EVIDENCE status=INITIALIZED role={args.role} output={target}")


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
    print(f"FEDORA02_EVIDENCE status=RECORDED role={role} check={args.check} result={args.status}")


def command_version(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.file)
    record = load(target)
    value = args.value.strip()
    if not value:
        raise EvidenceError("version value must be non-empty")
    if args.name == "packageVersion":
        record["packageVersion"] = value
    else:
        if args.name not in record.get("versions", {}):
            raise EvidenceError(f"unknown version field: {args.name}")
        record["versions"][args.name] = value
    record["capturedAtUtc"] = utc_now()
    errors = validate_record(record, str(target), False)
    if errors:
        raise EvidenceError("; ".join(errors))
    atomic_write(target, record)
    print(f"FEDORA02_EVIDENCE status=VERSION_RECORDED role={record['role']} name={args.name}")


def command_verify(args: argparse.Namespace) -> None:
    paths = [pathlib.Path(path) for path in args.files]
    errors = validate_matrix(paths, args.require_complete)
    if errors:
        for error in errors:
            print(f"FEDORA02_EVIDENCE status=INVALID message={json.dumps(error)}")
        raise SystemExit(2)
    completion = "COMPLETE" if args.require_complete else "VALID"
    print(f"FEDORA02_EVIDENCE status={completion} forms={len(paths)}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="create a fail-closed package record")
    init.add_argument("--role", choices=ROLES, required=True)
    init.add_argument("--host-id", required=True)
    init.add_argument("--revision", required=True)
    init.add_argument("--version", required=True)
    init.add_argument("--output", required=True)
    init.add_argument("--force", action="store_true")
    init.set_defaults(handler=command_init)
    record = commands.add_parser("record", help="record one observed check result")
    record.add_argument("--file", required=True)
    record.add_argument("--check", required=True)
    record.add_argument("--status", choices=STATUSES, required=True)
    record.add_argument("--evidence", required=True)
    record.set_defaults(handler=command_record)
    version = commands.add_parser("version", help="record one exact host/runtime/package version")
    version.add_argument("--file", required=True)
    version.add_argument(
        "--name",
        choices=("os", "arch", "java", "packageSha256", "nativePairSha256", "packageVersion"),
        required=True,
    )
    version.add_argument("--value", required=True)
    version.set_defaults(handler=command_version)
    verify = commands.add_parser("verify", help="validate the two-form package matrix")
    verify.add_argument("--require-complete", action="store_true")
    verify.add_argument("files", nargs=2)
    verify.set_defaults(handler=command_verify)
    return root


def main() -> None:
    args = parser().parse_args()
    try:
        args.handler(args)
    except EvidenceError as failure:
        print(f"FEDORA02_EVIDENCE status=REFUSED message={json.dumps(str(failure))}")
        raise SystemExit(2) from failure


if __name__ == "__main__":
    main()
