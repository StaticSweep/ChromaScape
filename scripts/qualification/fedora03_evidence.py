#!/usr/bin/env python3
"""Create, update, and verify honest FEDORA-03 live-qualification records.

FEDORA-03 proves that the FEDORA-02 packaged ChromaScape runtime records a real
host-native RuneLite/Micro6 client and serves a working debugger live view on
Fedora. This module owns the record shape and the release gate. It deliberately
knows nothing about how the host runs the checks: it only refuses to let a claim
be recorded that its own schema cannot support.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import tempfile
from typing import Any

SCHEMA_VERSION = 1
STORY = "FEDORA-03"
ROLE = "live"
STATUSES = ("PASS", "FAIL", "NOT_RUN")

# Checks that prove the packaged runtime records a live client and serves the
# debugger. Every check is prerequisite-gated: an unexecuted check stays
# NOT_RUN with the exact missing prerequisite and the release gate refuses it.
CHECKS = (
    "liveTargetDiscovery",
    "liveExplicitSelection",
    "qualificationAttach",
    "qualificationCapture",
    "recordingStartStop",
    "recordingArtifact",
    "recordingSessionFinalized",
    "recordingDownload",
    "recordingSourceBinding",
    "debuggerEndpoint",
    "debuggerViewportFrames",
    "debuggerSourceBinding",
    "debuggerTickSession",
    "debuggerRecordingLink",
    "sessionExportVideo",
    "boundedInput",
    "inputRelease",
    "cleanClose",
    "runtimeRestart",
    "noResidue",
)

VERSION_FIELDS = (
    "os",
    "arch",
    "java",
    "client",
    "packageRevision",
    "packageSha256",
    "packageManifestSha256",
    "nativePairSha256",
    "nativeLibrarySha256",
    "nativeManifestSha256",
    "nativePatchIdentity",
)

SHA256 = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
PATCH_IDENTITY = re.compile(r"^sha256:[0-9a-f]{64}$")
SAFE_HOST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

# Version fields that may honestly be absent when the native pair could not be
# produced. Completion still demands them, so an absent pair cannot pass the
# gate; the markers exist so a partial record stays valid and reviewable.
MARKERS = ("NOT_RECORDED", "NOT_APPLICABLE")


class EvidenceError(ValueError):
    """Raised when a live-qualification record cannot support its claim."""


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


def new_record(host_id: str, revision: str) -> dict[str, Any]:
    if not SAFE_HOST_ID.fullmatch(host_id):
        raise EvidenceError("host-id must be 1-64 safe identifier characters")
    if not GIT_SHA.fullmatch(revision):
        raise EvidenceError("revision must be a full lowercase Git SHA")
    return {
        "schemaVersion": SCHEMA_VERSION,
        "story": STORY,
        "role": ROLE,
        "hostId": host_id,
        "applicationRevision": revision,
        "capturedAtUtc": utc_now(),
        "versions": {name: "NOT_RECORDED" for name in VERSION_FIELDS},
        "checks": {name: {"status": "NOT_RUN", "evidence": "not executed"} for name in CHECKS},
    }


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise EvidenceError(f"cannot read {path}: {failure}") from failure
    if not isinstance(value, dict):
        raise EvidenceError(f"{path}: root must be an object")
    return value


def _validate_digest(errors: list[str], source: str, name: str, value: Any, pattern: re.Pattern[str]) -> None:
    text = str(value)
    if text in MARKERS:
        return
    if not pattern.fullmatch(text):
        errors.append(f"{source}: versions.{name} must match {pattern.pattern} or a marker")


def validate_record(record: dict[str, Any], source: str, require_complete: bool) -> list[str]:
    errors: list[str] = []
    if record.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"{source}: schemaVersion must be {SCHEMA_VERSION}")
    if record.get("story") != STORY:
        errors.append(f"{source}: story must be {STORY}")
    if record.get("role") != ROLE:
        errors.append(f"{source}: role must be {ROLE}")
        return errors
    if not SAFE_HOST_ID.fullmatch(str(record.get("hostId", ""))):
        errors.append(f"{source}: invalid hostId")
    if not GIT_SHA.fullmatch(str(record.get("applicationRevision", ""))):
        errors.append(f"{source}: applicationRevision must be a full lowercase Git SHA")
    if not UTC_TIMESTAMP.fullmatch(str(record.get("capturedAtUtc", ""))):
        errors.append(f"{source}: capturedAtUtc must be a whole-second UTC timestamp")
    versions = record.get("versions")
    if not isinstance(versions, dict):
        errors.append(f"{source}: versions must be an object")
        versions = {}
    for name in VERSION_FIELDS:
        value = versions.get(name)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{source}: versions.{name} must be non-empty")
    for digest_name in ("packageSha256", "packageManifestSha256", "nativePairSha256",
                        "nativeLibrarySha256", "nativeManifestSha256"):
        _validate_digest(errors, source, digest_name, versions.get(digest_name, ""), SHA256)
    _validate_digest(errors, source, "packageRevision", versions.get("packageRevision", ""), GIT_SHA)
    _validate_digest(errors, source, "nativePatchIdentity", versions.get("nativePatchIdentity", ""),
                     PATCH_IDENTITY)
    if require_complete:
        for name in ("os", "arch", "java", "client"):
            if versions.get(name) in MARKERS:
                errors.append(f"{source}: versions.{name} is {versions.get(name)}, required exact value")
        for name in ("packageRevision", "packageSha256", "packageManifestSha256",
                     "nativePairSha256", "nativeLibrarySha256", "nativeManifestSha256"):
            if not SHA256.fullmatch(str(versions.get(name))) and versions.get(name) != record.get(
                "applicationRevision"
            ):
                errors.append(f"{source}: versions.{name} is required for completion")
        if not PATCH_IDENTITY.fullmatch(str(versions.get("nativePatchIdentity"))):
            errors.append(f"{source}: versions.nativePatchIdentity is required for completion")
        if versions.get("packageRevision") != record.get("applicationRevision"):
            errors.append(f"{source}: packageRevision must equal applicationRevision for completion")
    checks = record.get("checks")
    if not isinstance(checks, dict):
        errors.append(f"{source}: checks must be an object")
        return errors
    expected = set(CHECKS)
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


def command_init(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.output)
    if target.exists() and not args.force:
        raise EvidenceError(f"refusing to overwrite existing record: {target}")
    atomic_write(target, new_record(args.host_id, args.revision))
    print(f"FEDORA03_EVIDENCE status=INITIALIZED role={ROLE} output={target}")


def command_record(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.file)
    record = load(target)
    if record.get("role") != ROLE:
        raise EvidenceError(f"record role must be {ROLE}")
    if args.check not in CHECKS:
        raise EvidenceError(f"check {args.check!r} is not defined for FEDORA-03")
    record["checks"][args.check] = {"status": args.status, "evidence": args.evidence.strip()}
    record["capturedAtUtc"] = utc_now()
    errors = validate_record(record, str(target), False)
    if errors:
        raise EvidenceError("; ".join(errors))
    atomic_write(target, record)
    print(f"FEDORA03_EVIDENCE status=RECORDED check={args.check} result={args.status}")


def command_version(args: argparse.Namespace) -> None:
    target = pathlib.Path(args.file)
    record = load(target)
    value = args.value.strip()
    if not value:
        raise EvidenceError("version value must be non-empty")
    if args.name not in VERSION_FIELDS:
        raise EvidenceError(f"unknown version field: {args.name}")
    record["versions"][args.name] = value
    record["capturedAtUtc"] = utc_now()
    errors = validate_record(record, str(target), False)
    if errors:
        raise EvidenceError("; ".join(errors))
    atomic_write(target, record)
    print(f"FEDORA03_EVIDENCE status=VERSION_RECORDED name={args.name}")


def command_verify(args: argparse.Namespace) -> None:
    path = pathlib.Path(args.file)
    record = load(path)
    errors = validate_record(record, str(path), args.require_complete)
    if errors:
        for error in errors:
            print(f"FEDORA03_EVIDENCE status=INVALID message={json.dumps(error)}")
        raise SystemExit(2)
    completion = "COMPLETE" if args.require_complete else "VALID"
    print(f"FEDORA03_EVIDENCE status={completion} role={ROLE}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="create a fail-closed live record")
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
    version = commands.add_parser("version", help="record one exact host/runtime/package version")
    version.add_argument("--file", required=True)
    version.add_argument("--name", choices=VERSION_FIELDS, required=True)
    version.add_argument("--value", required=True)
    version.set_defaults(handler=command_version)
    verify = commands.add_parser("verify", help="validate the live record")
    verify.add_argument("--require-complete", action="store_true")
    verify.add_argument("file")
    verify.set_defaults(handler=command_verify)
    return root


def main() -> None:
    args = parser().parse_args()
    try:
        args.handler(args)
    except EvidenceError as failure:
        print(f"FEDORA03_EVIDENCE status=REFUSED message={json.dumps(str(failure))}")
        raise SystemExit(2) from failure


if __name__ == "__main__":
    main()
