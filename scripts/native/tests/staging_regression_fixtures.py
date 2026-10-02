#!/usr/bin/env python3
"""Fixture generator for the SC-1662 staging regressions.

The staging regressions (``scripts/native/staging-regressions.sh``) drive the real
``stageRemoteInputLinux`` Gradle task against synthetic library/manifest pairs, so they exercise the
shipping staging implementation without needing a C++ toolchain. This module builds those fixtures:

* a synthetic 64-bit x86-64 shared object (ELF header plus deterministic padding keyed by ``--seed``);
* a build-facts sidecar and a provenance document shaped exactly like the ones the build writes;
* a manifest produced by the *real* helper (``write-manifest``), so the pair validates under
  ``verify-manifest`` exactly as a built pair would.

The artifact bytes are a pure function of ``--seed``, so two invocations with the same seed produce
byte-identical artifacts while ``--generated-at`` varies the manifest. That is what lets a regression
distinguish a payload identity that binds both files from one that binds the artifact alone.

This module is not a test module: ``unittest`` discovery only imports ``test*.py`` files, so its
presence in the tests directory does not add a test case.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import sys

REQUIRED_SYMBOLS = ["EIOS_Inject_PID", "EIOS_GetClients", "EIOS_IsMouseButtonHeld"]
JDK_VERSION_WITH_QUOTES = 'openjdk version "17.0.20.1" 2026-08-18 LTS'


def load_helper(helper_path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("remoteinput_metadata", helper_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["remoteinput_metadata"] = module
    spec.loader.exec_module(module)
    return module


def write_elf(path: pathlib.Path, seed: int) -> pathlib.Path:
    """A synthetic but structurally valid 64-bit little-endian x86-64 shared object."""
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = 2  # ELFCLASS64
    header[5] = 1  # ELFDATA2LSB
    header[16] = 3  # ET_DYN
    header[18] = 62  # EM_X86_64
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(header) + bytes([seed & 0xFF]) * 512)
    return path


def fact_lines(generated_at: str) -> list:
    values = {
        "generatedAtUtc": generated_at,
        "hostOs": "linux",
        "hostArch": "x86_64",
        "hostPlatform": "linux-x86_64",
        "cxxPath": "/usr/bin/g++ (ccache)",
        "cxxVersion": "g++ (GCC) 14.2.1 20240912",
        "cxxStandard": "20",
        "cmakeVersion": "3.31.1",
        "cmakeGenerator": "Unix Makefiles",
        "jdkHome": "/opt/jdk with spaces",
        "jdkVersion": JDK_VERSION_WITH_QUOTES,
        "jdkMajorVersion": "17",
        "pythonExecutable": "/usr/bin/python3",
        "pythonVersion": "3.14.3",
        "pythonInclude": "/usr/include/python3.14",
        "buildType": "Release",
        "sourceDir": "/src with spaces",
        "buildDir": "/build",
        "parallelism": "4",
    }
    lines = [f"{key}={value}" for key, value in values.items()]
    lines += [f"requiredSymbol={symbol}" for symbol in REQUIRED_SYMBOLS]
    lines += [f"exportedSymbol={symbol}" for symbol in REQUIRED_SYMBOLS]
    lines.append("exportedSymbol=SimbaPlugin_Extra")
    lines += ["declaredDependency=libGL.so.1", "declaredDependency=libc.so.6"]
    return lines


def provenance_document() -> dict:
    return {
        "sourceRepository": "https://example.invalid/repo",
        "sourceRevision": "5ea3b995f5d13e19c592a6399973fe7dadb5e656",
        "importManifestSha256": "1" * 64,
        "localPatchIdentity": "none",
        "localPatch": None,
        "actualSourceTreeSha256": "2" * 64,
        "importedFileCount": 278,
        "verifiedFileCount": 278,
        "sourceFileCount": 278,
        "changedPaths": [],
        "addedPaths": [],
        "removedPaths": [],
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="staging_regression_fixtures.py")
    parser.add_argument("--helper", required=True)
    parser.add_argument("--outdir", required=True)
    parser.add_argument("--seed", required=True, type=int)
    parser.add_argument("--generated-at", required=True)
    args = parser.parse_args(argv)

    helper = load_helper(pathlib.Path(args.helper))
    outdir = pathlib.Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    artifact = write_elf(outdir / "libRemoteInput.so", args.seed)

    facts_path = outdir / "build-facts.txt"
    facts_path.write_text("\n".join(fact_lines(args.generated_at)) + "\n", encoding="utf-8")

    provenance_path = outdir / "provenance.json"
    provenance_path.write_text(json.dumps(provenance_document()), encoding="utf-8")

    manifest = outdir / "remoteinput-manifest.json"
    measured = helper.measure_artifact(artifact)
    helper.publish_document(
        helper.build_document(
            *helper.read_build_facts(facts_path),
            provenance=json.loads(provenance_path.read_text(encoding="utf-8")),
            measured=measured,
        ),
        manifest,
        measured,
        "linux",
        "x86_64",
    )

    print(f"ARTIFACT {artifact}")
    print(f"MANIFEST {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
