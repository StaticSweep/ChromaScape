#!/usr/bin/env python3
"""SC-1662 native metadata, manifest and provenance helper.

This helper exists for three narrow jobs, and deliberately does nothing else:

``verify-sources``
    Confirm the vendored native sources still match the pinned import before anything is
    compiled, and derive a deterministic identity for the tree that is actually built.

``write-manifest``
    Build the artifact provenance manifest with a real JSON serializer, re-read it, validate
    it, and only then publish it.

``verify-manifest``
    Re-validate a published manifest against the artifact it describes. Used by Gradle
    staging so a mismatched or incomplete pair is never distributed.

Values reach this helper through files and arguments -- never by interpolating them into
generated source -- because shell string interpolation is exactly what produced invalid JSON
when a JDK version string contained quotes.

Exit codes: 0 success, 2 usage error, 3 validation failure, 4 I/O or parse failure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path

SCHEMA_VERSION = 1
SUPPORTED_SCHEMA_VERSIONS = (SCHEMA_VERSION,)

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_VALIDATION_FAILED = 3
EXIT_IO_FAILED = 4

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
SYMBOL_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
IMPORT_MANIFEST_LINE_RE = re.compile(r"^(?P<digest>[0-9a-f]{64}) {2}(?P<path>.+)$")

DEFAULT_EXPECTED_OS = "linux"
DEFAULT_EXPECTED_ARCH = "x86_64"

DEFAULT_IMPORT_MANIFEST = "third-party/RemoteInput/import-manifest.sha256"
DEFAULT_INVENTORY_ROOT = "third-party"

# Files the story itself adds next to the imported sources. They are not upstream inputs and
# must not be mistaken for local modifications of the native tree.
DEFAULT_ALLOWED_EXTRAS = (
    "third-party/RemoteInput/PROVENANCE.md",
    "third-party/RemoteInput/import-manifest.sha256",
)

# Build-fact keys that may legitimately appear more than once.
LIST_KEYS = ("requiredSymbol", "exportedSymbol", "declaredDependency")

# ELF constants (System V gABI).
ELF_MAGIC = b"\x7fELF"
ELF_HEADER_BYTES = 20
EI_CLASS_OFFSET = 4
EI_DATA_OFFSET = 5
E_TYPE_OFFSET = 16
E_MACHINE_OFFSET = 18
ELFCLASS64 = 2
ELFDATA2LSB = 1
ET_DYN = 3
EM_X86_64 = 62
ELF_CLASS_64 = "ELF64"
ELF_MACHINE_X86_64 = "EM_X86_64"
ELF_TYPE_DYN = "ET_DYN"

PATCH_IDENTITY_NONE = "none"


class ValidationError(Exception):
    """A measured value contradicts the manifest, or an input is malformed."""


# ---------------------------------------------------------------------------
# Measurement
# ---------------------------------------------------------------------------


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def elf_identity(path: Path) -> dict:
    """Read the ELF header and return the normalised platform identity."""
    try:
        with open(path, "rb") as handle:
            header = handle.read(ELF_HEADER_BYTES)
    except OSError as failure:
        raise ValidationError(f"artifact could not be read: {path} ({failure})") from failure

    if len(header) < ELF_HEADER_BYTES or header[:4] != ELF_MAGIC:
        raise ValidationError(f"artifact is not an ELF object: {path}")
    if header[EI_CLASS_OFFSET] != ELFCLASS64:
        raise ValidationError(f"artifact is not a 64-bit ELF object: {path}")
    if header[EI_DATA_OFFSET] != ELFDATA2LSB:
        raise ValidationError(f"artifact is not little-endian: {path}")

    e_type = header[E_TYPE_OFFSET] | (header[E_TYPE_OFFSET + 1] << 8)
    e_machine = header[E_MACHINE_OFFSET] | (header[E_MACHINE_OFFSET + 1] << 8)
    if e_type != ET_DYN:
        raise ValidationError(f"artifact is not a shared object (ELF type {e_type}): {path}")
    if e_machine != EM_X86_64:
        raise ValidationError(f"artifact does not target x86-64 (ELF machine {e_machine}): {path}")

    return {
        "elfClass": ELF_CLASS_64,
        "elfMachine": ELF_MACHINE_X86_64,
        "elfType": ELF_TYPE_DYN,
    }


def measure_artifact(path: Path) -> dict:
    if not path.exists():
        raise ValidationError(f"artifact is missing: {path}")
    if not path.is_file():
        raise ValidationError(f"artifact is not a regular file: {path}")
    size = path.stat().st_size
    if size <= 0:
        raise ValidationError(f"artifact is empty: {path}")
    measured = {
        "filename": path.name,
        "sizeBytes": size,
        "sha256": sha256_file(path),
    }
    measured.update(elf_identity(path))
    return measured


# ---------------------------------------------------------------------------
# Source provenance
# ---------------------------------------------------------------------------


def parse_import_manifest(path: Path) -> list:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as failure:
        raise ValidationError(f"import manifest could not be read: {path} ({failure})") from failure

    entries = []
    seen = set()
    for lineno, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            continue
        match = IMPORT_MANIFEST_LINE_RE.match(raw)
        if not match:
            raise ValidationError(
                f"{path}:{lineno}: malformed import-manifest line (expected '<sha256>  <path>')"
            )
        relative = match.group("path")
        if relative in seen:
            raise ValidationError(f"{path}:{lineno}: duplicate entry for {relative}")
        seen.add(relative)
        entries.append((match.group("digest"), relative))

    if not entries:
        raise ValidationError(f"import manifest lists no files: {path}")
    return entries


def read_patch_declaration(path: Path) -> dict:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise ValidationError(f"patch declaration could not be read: {path} ({failure})") from failure
    except json.JSONDecodeError as failure:
        raise ValidationError(f"patch declaration is not valid JSON: {path} ({failure})") from failure

    if not isinstance(document, dict):
        raise ValidationError(f"patch declaration must be a JSON object: {path}")

    identity = document.get("patchIdentity")
    if not isinstance(identity, str) or not identity.strip():
        raise ValidationError(f"patch declaration needs a non-empty patchIdentity: {path}")
    if identity.strip().lower() == PATCH_IDENTITY_NONE:
        raise ValidationError(
            f"a patch declaration may not claim patchIdentity '{PATCH_IDENTITY_NONE}': {path}"
        )

    reason = document.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        raise ValidationError(f"patch declaration needs a non-empty reason: {path}")

    for key in ("changedPaths", "addedPaths", "removedPaths"):
        value = document.get(key, [])
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            raise ValidationError(f"patch declaration '{key}' must be a list of paths: {path}")

    return document


def require_disjoint_categories(declaration: dict) -> None:
    """A path may only be declared in one category."""
    owner = {}
    for key in ("changedPaths", "addedPaths", "removedPaths"):
        for relative in sorted(declaration.get(key, [])):
            if relative in owner:
                raise ValidationError(
                    f"patch declaration lists {relative} as both {owner[relative]} and {key}"
                )
            owner[relative] = key


def require_exact_declaration(name: str, declared: set, measured: set) -> None:
    """The declaration must describe the measured change set exactly, in both directions.

    A subset check is not enough: it accepts a declared change, addition or removal that never
    happened, which lets the declaration describe a patch the tree does not contain.
    """
    never_happened = sorted(declared - measured)
    undeclared = sorted(measured - declared)
    if never_happened or undeclared:
        raise ValidationError(
            f"patch declaration {name} does not describe the working tree exactly "
            f"(declared but not measured={never_happened}, measured but not declared={undeclared})"
        )


def derive_patch_identity(declaration: dict, digests: dict) -> str:
    """Derive the patch identity from the declaration and the content it claims.

    The identity is a function of the declared paths, the digest of each of those files as
    measured in the working tree, and the declared reason, so it cannot be supplied arbitrarily
    and silently re-identify a different patch.
    """
    payload = {
        "reason": declaration["reason"].strip(),
        "changedPaths": {path: digests[path] for path in sorted(declaration.get("changedPaths", []))},
        "addedPaths": {path: digests[path] for path in sorted(declaration.get("addedPaths", []))},
        "removedPaths": sorted(declaration.get("removedPaths", [])),
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def inventory_files(repo_root: Path, inventory_root: Path) -> list:
    files = []
    for dirpath, dirnames, filenames in os.walk(inventory_root):
        dirnames.sort()
        for name in sorted(filenames):
            full = Path(dirpath) / name
            files.append(full.relative_to(repo_root).as_posix())
    return sorted(files)


def source_tree_identity(measured: dict) -> str:
    """Deterministic identity over 'path + measured digest', independent of walk order."""
    digest = hashlib.sha256()
    for relative in sorted(measured):
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(measured[relative].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def relative_to_root(repo_root: Path, path: Path) -> str:
    """Repo-relative POSIX path when possible (Path.is_relative_to is 3.9+)."""
    try:
        return path.relative_to(repo_root).as_posix()
    except ValueError:
        return str(path)


def verify_sources(args) -> dict:
    repo_root = Path(args.repo_root).resolve()
    if not repo_root.is_dir():
        raise ValidationError(f"repository root is not a directory: {repo_root}")

    manifest_path = Path(args.import_manifest) if args.import_manifest else repo_root / DEFAULT_IMPORT_MANIFEST
    inventory_root = Path(args.inventory_root) if args.inventory_root else repo_root / DEFAULT_INVENTORY_ROOT

    entries = parse_import_manifest(manifest_path)

    allowed_extras = set(DEFAULT_ALLOWED_EXTRAS)
    allowed_extras.update(args.allow_extra or ())

    missing, drifted, measured = [], [], {}
    for expected_digest, relative in entries:
        candidate = repo_root / relative
        if not candidate.is_file():
            missing.append(relative)
            continue
        actual = sha256_file(candidate)
        measured[relative] = actual
        if actual != expected_digest:
            drifted.append(relative)

    listed = {relative for _, relative in entries}
    present = set(inventory_files(repo_root, inventory_root))
    unexpected = sorted(present - listed - allowed_extras)

    declaration = read_patch_declaration(Path(args.patch_declaration)) if args.patch_declaration else None

    if declaration is None:
        if missing:
            raise ValidationError(
                "imported source(s) missing from the working tree: " + ", ".join(sorted(missing))
            )
        if drifted:
            raise ValidationError(
                "imported source(s) differ from the pinned import with no documented patch: "
                + ", ".join(sorted(drifted))
            )
        if unexpected:
            raise ValidationError(
                "unexpected file(s) in the declared source inventory: " + ", ".join(unexpected)
            )
        local_patch_identity = PATCH_IDENTITY_NONE
        patch_record = None
    else:
        declared_changed = set(declaration.get("changedPaths", []))
        declared_added = set(declaration.get("addedPaths", []))
        declared_removed = set(declaration.get("removedPaths", []))

        require_disjoint_categories(declaration)

        # The declaration must equal what was measured, in both directions. Accepting a superset
        # would let a declaration describe changes, additions or removals that never happened.
        require_exact_declaration("changedPaths", declared_changed, set(drifted))
        require_exact_declaration("addedPaths", declared_added, set(unexpected))
        require_exact_declaration("removedPaths", declared_removed, set(missing))

        for relative in sorted(declared_changed | declared_removed):
            if relative not in listed:
                raise ValidationError(f"patch declaration names a path absent from the import: {relative}")

        measured_digests = dict(measured)
        for relative in sorted(declared_added):
            measured_digests[relative] = sha256_file(repo_root / relative)

        derived_identity = derive_patch_identity(declaration, measured_digests)
        declared_identity = declaration["patchIdentity"].strip()
        if declared_identity != derived_identity:
            raise ValidationError(
                "patch declaration patchIdentity does not describe the declared patch "
                f"(declared {declared_identity!r}, derived {derived_identity!r})"
            )

        local_patch_identity = derived_identity
        patch_record = {
            "identity": local_patch_identity,
            "reason": declaration["reason"].strip(),
            "changedPaths": sorted(declared_changed),
            "addedPaths": sorted(declared_added),
            "removedPaths": sorted(declared_removed),
        }

    # The recorded tree is the measured one: imported files at their measured digest, plus the
    # measured additions. Nothing is taken from the declaration without having been measured.
    recorded = dict(measured)
    for relative in unexpected:
        recorded[relative] = sha256_file(repo_root / relative)

    result = {
        "sourceRepository": args.source_repository,
        "sourceRevision": args.source_revision,
        "importManifestPath": relative_to_root(repo_root, manifest_path),
        "importManifestSha256": sha256_file(manifest_path),
        "localPatchIdentity": local_patch_identity,
        "localPatch": patch_record,
        "actualSourceTreeSha256": source_tree_identity(recorded),
        "importedFileCount": len(entries),
        "verifiedFileCount": len(measured),
        "sourceFileCount": len(recorded),
        "changedPaths": sorted(drifted),
        "addedPaths": sorted(unexpected),
        "removedPaths": sorted(missing),
        "unexpectedPaths": [],
    }
    return result


# ---------------------------------------------------------------------------
# Build facts
# ---------------------------------------------------------------------------


def read_build_facts(path: Path) -> tuple:
    """Read a key=value sidecar. Repeated LIST_KEYS keys accumulate, others last-wins."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as failure:
        raise ValidationError(f"build facts could not be read: {path} ({failure})") from failure

    scalars = {}
    lists = {key: [] for key in LIST_KEYS}
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValidationError(f"{path}:{lineno}: expected 'key=value', got {line!r}")
        key, _, value = line.partition("=")
        key = key.strip()
        if not key:
            raise ValidationError(f"{path}:{lineno}: empty key")
        if key in lists:
            if value:
                lists[key].append(value)
        else:
            scalars[key] = value
    return scalars, lists


def optional(value):
    """Absent/blank measurement is recorded as null, never guessed."""
    if value is None:
        return None
    text = value.strip()
    return text or None


def optional_int(value, field):
    text = optional(value)
    if text is None:
        return None
    try:
        return int(text)
    except ValueError as failure:
        raise ValidationError(f"{field} must be an integer, got {text!r}") from failure


def optional_bool(value, field):
    """Strict boolean fact: only 'true'/'false' are accepted; absent stays None."""
    text = optional(value)
    if text is None:
        return None
    lowered = text.lower()
    if lowered not in {"true", "false"}:
        raise ValidationError(f"{field} must be 'true' or 'false', got {text!r}")
    return lowered == "true"


def build_document(facts: dict, lists: dict, provenance: dict, measured: dict) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAtUtc": facts.get("generatedAtUtc"),
        "sourceRepository": provenance.get("sourceRepository"),
        "sourceRevision": provenance.get("sourceRevision"),
        "importManifestSha256": provenance.get("importManifestSha256"),
        "localPatchIdentity": provenance.get("localPatchIdentity"),
        "localPatch": provenance.get("localPatch"),
        "actualSourceTreeSha256": provenance.get("actualSourceTreeSha256"),
        "sourceInventory": {
            "importedFileCount": provenance.get("importedFileCount"),
            "verifiedFileCount": provenance.get("verifiedFileCount"),
            "sourceFileCount": provenance.get("sourceFileCount"),
            "changedPaths": provenance.get("changedPaths", []),
            "addedPaths": provenance.get("addedPaths", []),
            "removedPaths": provenance.get("removedPaths", []),
        },
        "host": {
            "os": optional(facts.get("hostOs")),
            "arch": optional(facts.get("hostArch")),
            "platform": optional(facts.get("hostPlatform")),
        },
        "compiler": {
            "cxx": optional(facts.get("cxxPath")),
            "cxxVersion": optional(facts.get("cxxVersion")),
            "cxxStandard": optional(facts.get("cxxStandard")),
        },
        "cmake": {
            "version": optional(facts.get("cmakeVersion")),
            "generator": optional(facts.get("cmakeGenerator")),
        },
        "jdk": {
            "home": optional(facts.get("jdkHome")),
            "version": optional(facts.get("jdkVersion")),
            "majorVersion": optional(facts.get("jdkMajorVersion")),
        },
        "python": {
            "executable": optional(facts.get("pythonExecutable")),
            "version": optional(facts.get("pythonVersion")),
            "include": optional(facts.get("pythonInclude")),
        },
        "buildOptions": {
            "buildType": optional(facts.get("buildType")),
            "sourceDir": optional(facts.get("sourceDir")),
            "buildDir": optional(facts.get("buildDir")),
            "parallelism": optional_int(facts.get("parallelism"), "parallelism"),
        },
        "artifact": dict(measured),
        "declaredSharedLibraryDependencies": sorted(set(lists["declaredDependency"])),
        "requiredSymbols": lists["requiredSymbol"],
        "exportedSymbols": sorted(set(lists["exportedSymbol"])),
        "repeatBuild": {
            "sha256": optional(facts.get("repeatSha256")),
            "byteIdentical": optional_bool(facts.get("byteIdentical"), "byteIdentical"),
        },
    }


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def require_hash(value, field):
    require(
        isinstance(value, str) and SHA256_RE.match(value),
        f"{field} must be a 64-character lowercase hex SHA-256, got {value!r}",
    )


def validate_document(document, measured: dict, expected_os: str, expected_arch: str) -> None:
    require(isinstance(document, dict), "manifest must be a JSON object")

    schema = document.get("schemaVersion")
    require(
        schema in SUPPORTED_SCHEMA_VERSIONS,
        f"unsupported schemaVersion {schema!r}; supported: {list(SUPPORTED_SCHEMA_VERSIONS)}",
    )

    generated_at = document.get("generatedAtUtc")
    require(
        isinstance(generated_at, str) and TIMESTAMP_RE.match(generated_at),
        f"generatedAtUtc must look like 2026-01-01T00:00:00Z, got {generated_at!r}",
    )

    revision = document.get("sourceRevision")
    require(
        isinstance(revision, str) and REVISION_RE.match(revision),
        f"sourceRevision must be a 40-character lowercase hex revision, got {revision!r}",
    )

    require_hash(document.get("importManifestSha256"), "importManifestSha256")
    require_hash(document.get("actualSourceTreeSha256"), "actualSourceTreeSha256")

    patch_identity = document.get("localPatchIdentity")
    require(
        isinstance(patch_identity, str) and patch_identity.strip(),
        "localPatchIdentity must be a non-empty string (use 'none' when unpatched)",
    )

    host = document.get("host")
    require(isinstance(host, dict), "host must be an object")
    require(
        isinstance(host.get("os"), str) and host["os"].lower() == expected_os.lower(),
        f"host.os must be {expected_os!r}, got {host.get('os')!r}",
    )
    require(
        isinstance(host.get("arch"), str) and host["arch"].lower() == expected_arch.lower(),
        f"host.arch must be {expected_arch!r}, got {host.get('arch')!r}",
    )

    artifact = document.get("artifact")
    require(isinstance(artifact, dict), "artifact must be an object")
    require(
        isinstance(artifact.get("filename"), str) and artifact["filename"].strip(),
        "artifact.filename must be a non-empty string",
    )
    require(
        isinstance(artifact.get("sizeBytes"), int) and not isinstance(artifact.get("sizeBytes"), bool),
        f"artifact.sizeBytes must be an integer, got {artifact.get('sizeBytes')!r}",
    )
    require(artifact["sizeBytes"] > 0, "artifact.sizeBytes must be positive")
    require_hash(artifact.get("sha256"), "artifact.sha256")
    require(
        artifact["sizeBytes"] == measured["sizeBytes"],
        f"artifact.sizeBytes ({artifact['sizeBytes']}) does not match the measured artifact "
        f"({measured['sizeBytes']})",
    )
    require(
        artifact["sha256"] == measured["sha256"],
        f"artifact.sha256 ({artifact['sha256']}) does not match the measured artifact "
        f"({measured['sha256']})",
    )
    for field in ("elfClass", "elfMachine", "elfType"):
        require(
            artifact.get(field) == measured[field],
            f"artifact.{field} must be {measured[field]!r}, got {artifact.get(field)!r}",
        )

    required_symbols = document.get("requiredSymbols")
    require(
        isinstance(required_symbols, list) and required_symbols,
        "requiredSymbols must be a non-empty list",
    )
    require(
        all(isinstance(symbol, str) and SYMBOL_RE.match(symbol) for symbol in required_symbols),
        "requiredSymbols contains an entry that is not a plausible symbol name",
    )
    require(
        len(set(required_symbols)) == len(required_symbols),
        "requiredSymbols contains duplicates",
    )

    exported_symbols = document.get("exportedSymbols")
    require(isinstance(exported_symbols, list), "exportedSymbols must be a list")
    absent = sorted(set(required_symbols) - set(exported_symbols))
    require(not absent, "required symbol(s) absent from the exported inventory: " + ", ".join(absent))

    dependencies = document.get("declaredSharedLibraryDependencies")
    require(isinstance(dependencies, list), "declaredSharedLibraryDependencies must be a list")
    require(
        all(isinstance(item, str) for item in dependencies),
        "declaredSharedLibraryDependencies must contain only strings",
    )

    repeat = document.get("repeatBuild")
    require(isinstance(repeat, dict), "repeatBuild must be an object")
    repeat_sha = repeat.get("sha256")
    byte_identical = repeat.get("byteIdentical")
    if repeat_sha is None:
        require(
            byte_identical is None,
            "repeatBuild.byteIdentical must be null when no repeat build was executed",
        )
    else:
        require_hash(repeat_sha, "repeatBuild.sha256")
        require(
            isinstance(byte_identical, bool),
            "repeatBuild.byteIdentical must be a boolean when a repeat hash is present",
        )
        require(
            byte_identical == (repeat_sha == artifact["sha256"]),
            "repeatBuild.byteIdentical contradicts the recorded hashes",
        )


# ---------------------------------------------------------------------------
# Publication
# ---------------------------------------------------------------------------


def publish_document(document: dict, output_path: Path, measured: dict, expected_os, expected_arch) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    validate_document(document, measured, expected_os, expected_arch)

    handle, temporary_name = tempfile.mkstemp(
        prefix=output_path.name + ".", suffix=".tmp", dir=str(output_path.parent)
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(document, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())

        # Re-read what was actually written: the serializer output is validated, not the
        # in-memory document, so a write-truncation bug cannot publish a bad manifest.
        try:
            with open(temporary_name, "r", encoding="utf-8") as stream:
                reparsed = json.load(stream)
        except json.JSONDecodeError as failure:
            raise ValidationError(f"serialized manifest is not valid JSON: {failure}") from failure

        validate_document(reparsed, measured, expected_os, expected_arch)
        # mkstemp creates 0600; a published manifest is a normal world-readable build artifact.
        os.chmod(temporary_name, 0o644)
        os.replace(temporary_name, output_path)
    except BaseException:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------


def command_verify_sources(args) -> int:
    result = verify_sources(args)
    text = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
        print(
            f"PROVENANCE_OK files={result['sourceFileCount']} "
            f"tree={result['actualSourceTreeSha256']} patch={result['localPatchIdentity']}"
        )
    else:
        sys.stdout.write(text)
    return EXIT_OK


def command_write_manifest(args) -> int:
    facts, lists = read_build_facts(Path(args.build_facts))

    provenance_path = Path(args.provenance)
    try:
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise ValidationError(f"provenance could not be read: {provenance_path} ({failure})") from failure
    except json.JSONDecodeError as failure:
        raise ValidationError(f"provenance is not valid JSON: {provenance_path} ({failure})") from failure
    require(isinstance(provenance, dict), "provenance must be a JSON object")

    measured = measure_artifact(Path(args.artifact))
    document = build_document(facts, lists, provenance, measured)
    publish_document(
        document, Path(args.output), measured, args.expected_os, args.expected_arch
    )
    print(
        f"MANIFEST_WRITTEN {args.output} sha256={measured['sha256']} "
        f"size={measured['sizeBytes']} patch={document['localPatchIdentity']}"
    )
    return EXIT_OK


def command_verify_manifest(args) -> int:
    manifest_path = Path(args.manifest)
    try:
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
    except OSError as failure:
        raise ValidationError(f"manifest could not be read: {manifest_path} ({failure})") from failure
    except json.JSONDecodeError as failure:
        raise ValidationError(f"manifest is not valid JSON: {manifest_path} ({failure})") from failure

    measured = measure_artifact(Path(args.artifact))
    validate_document(document, measured, args.expected_os, args.expected_arch)

    print(
        f"MANIFEST_OK manifest={manifest_path} artifact={args.artifact} "
        f"sha256={measured['sha256']} size={measured['sizeBytes']} "
        f"revision={document['sourceRevision']} patch={document['localPatchIdentity']}"
    )
    return EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="remoteinput_metadata.py",
        description="SC-1662 native source provenance and artifact manifest helper.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    sources = subparsers.add_parser(
        "verify-sources", help="verify the pinned import and derive the built tree identity"
    )
    sources.add_argument("--repo-root", required=True)
    sources.add_argument("--import-manifest", default=None)
    sources.add_argument("--inventory-root", default=None)
    sources.add_argument("--allow-extra", action="append", default=[])
    sources.add_argument(
        "--patch-declaration",
        default=None,
        help=(
            "declared local patch (JSON). changedPaths/addedPaths/removedPaths must equal the "
            "measured change set exactly, and patchIdentity must be the derived 'sha256:...' "
            "identity of the declaration plus the measured content -- the error prints it"
        ),
    )
    sources.add_argument("--source-repository", required=True)
    sources.add_argument("--source-revision", required=True)
    sources.add_argument("--output", default=None)
    sources.set_defaults(handler=command_verify_sources)

    manifest = subparsers.add_parser(
        "write-manifest", help="serialize, validate and publish the artifact manifest"
    )
    manifest.add_argument("--build-facts", required=True)
    manifest.add_argument("--provenance", required=True)
    manifest.add_argument("--artifact", required=True)
    manifest.add_argument("--output", required=True)
    manifest.add_argument("--expected-os", default=DEFAULT_EXPECTED_OS)
    manifest.add_argument("--expected-arch", default=DEFAULT_EXPECTED_ARCH)
    manifest.set_defaults(handler=command_write_manifest)

    verify = subparsers.add_parser(
        "verify-manifest", help="re-validate a published manifest against its artifact"
    )
    verify.add_argument("--manifest", required=True)
    verify.add_argument("--artifact", required=True)
    verify.add_argument("--expected-os", default=DEFAULT_EXPECTED_OS)
    verify.add_argument("--expected-arch", default=DEFAULT_EXPECTED_ARCH)
    verify.set_defaults(handler=command_verify_manifest)

    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.handler(args)
    except ValidationError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return EXIT_VALIDATION_FAILED
    except OSError as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return EXIT_IO_FAILED


if __name__ == "__main__":
    sys.exit(main())
