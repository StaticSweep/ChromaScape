#!/usr/bin/env python3
"""Tests for the SC-1662 metadata/provenance helper.

Run with either:

    python3 scripts/native/tests/test_remoteinput_metadata.py
    python3 -m unittest discover -s scripts/native/tests
    ./gradlew nativeMetadataTests

These tests use fixtures only: they never invoke CMake or a C++ compiler, so they run wherever
Python 3 is available.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

HELPER_PATH = pathlib.Path(__file__).resolve().parents[1] / "remoteinput_metadata.py"

# The exact string that produced invalid JSON when it was interpolated into a shell heredoc.
JDK_VERSION_WITH_QUOTES = 'openjdk version "17.0.20.1" 2026-08-18 LTS'

REQUIRED_SYMBOLS = ["EIOS_Inject_PID", "EIOS_GetClients", "EIOS_IsMouseButtonHeld"]


def load_helper():
    spec = importlib.util.spec_from_file_location("remoteinput_metadata", HELPER_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["remoteinput_metadata"] = module
    spec.loader.exec_module(module)
    return module


metadata = load_helper()


def write_elf(path: pathlib.Path, elf_class=2, data=1, e_type=3, e_machine=62) -> pathlib.Path:
    header = bytearray(64)
    header[0:4] = b"\x7fELF"
    header[4] = elf_class
    header[5] = data
    header[16] = e_type & 0xFF
    header[17] = (e_type >> 8) & 0xFF
    header[18] = e_machine & 0xFF
    header[19] = (e_machine >> 8) & 0xFF
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(header) + b"\x00" * 512)
    return path


def sha256_of(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ManifestFixture:
    """A build-facts sidecar plus a provenance document, assembled by the tests."""

    def __init__(self, root: pathlib.Path):
        self.root = root
        self.artifact = write_elf(root / "libRemoteInput.so")
        self.facts = root / "build-facts.txt"
        self.provenance = root / "provenance.json"
        self.manifest = root / "remoteinput-manifest.json"
        self.write_facts()
        self.write_provenance()

    def fact_lines(self, **overrides):
        values = {
            "generatedAtUtc": "2026-09-15T00:00:00Z",
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
            "pythonInclude": "C:\\Program Files\\include",
            "buildType": "Release",
            "sourceDir": "/src with spaces",
            "buildDir": "/build",
            "parallelism": "4",
        }
        values.update({key: str(value) for key, value in overrides.items()})
        lines = [f"{key}={value}" for key, value in values.items()]
        lines += [f"requiredSymbol={symbol}" for symbol in REQUIRED_SYMBOLS]
        lines += [f"exportedSymbol={symbol}" for symbol in REQUIRED_SYMBOLS]
        lines.append("exportedSymbol=SimbaPlugin_Extra")
        lines += ["declaredDependency=libGL.so.1", "declaredDependency=libc.so.6"]
        return lines

    def write_facts(self, **overrides):
        self.facts.write_text("\n".join(self.fact_lines(**overrides)) + "\n", encoding="utf-8")

    def write_provenance(self, **overrides):
        document = {
            "sourceRepository": "https://example.invalid/repo",
            "sourceRevision": "5ea3b995f5d13e19c592a6399973fe7dadb5e656",
            "importManifestSha256": "1" * 64,
            "localPatchIdentity": "none",
            "localPatch": None,
            "actualSourceTreeSha256": "2" * 64,
            "importedFileCount": 3,
            "verifiedFileCount": 3,
            "sourceFileCount": 3,
            "changedPaths": [],
            "addedPaths": [],
            "removedPaths": [],
        }
        document.update(overrides)
        self.provenance.write_text(json.dumps(document), encoding="utf-8")

    def build(self):
        return metadata.build_document(
            *metadata.read_build_facts(self.facts),
            provenance=json.loads(self.provenance.read_text(encoding="utf-8")),
            measured=metadata.measure_artifact(self.artifact),
        )

    def publish(self, document=None):
        measured = metadata.measure_artifact(self.artifact)
        metadata.publish_document(
            document if document is not None else self.build(),
            self.manifest,
            measured,
            "linux",
            "x86_64",
        )


class ManifestSerializationTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.fixture = ManifestFixture(pathlib.Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def test_quoted_jdk_version_round_trips_unchanged(self):
        """The regression: a JDK version string containing quotes must survive serialization."""
        self.fixture.publish()
        written = json.loads(self.fixture.manifest.read_text(encoding="utf-8"))
        self.assertEqual(JDK_VERSION_WITH_QUOTES, written["jdk"]["version"])

    def test_hostile_values_round_trip(self):
        self.fixture.publish()
        written = json.loads(self.fixture.manifest.read_text(encoding="utf-8"))
        self.assertEqual("/opt/jdk with spaces", written["jdk"]["home"])
        self.assertEqual("C:\\Program Files\\include", written["python"]["include"])
        self.assertEqual("/src with spaces", written["buildOptions"]["sourceDir"])

    def test_unicode_values_round_trip(self):
        self.fixture.write_facts(cxxPath="/opt/компилятор/日本語/g++")
        self.fixture.publish()
        written = json.loads(self.fixture.manifest.read_text(encoding="utf-8"))
        self.assertEqual("/opt/компилятор/日本語/g++", written["compiler"]["cxx"])

    def test_byte_identical_fact_is_recorded_as_a_boolean(self):
        """The regression: the writer emits 'true' as text; the manifest must carry a real bool."""
        self.fixture.write_facts(
            repeatSha256="a" * 64,
            byteIdentical="true",
        )
        document = self.fixture.build()
        self.assertIs(True, document["repeatBuild"]["byteIdentical"])

    def test_byte_identical_false_is_recorded_as_a_boolean(self):
        self.fixture.write_facts(
            repeatSha256="b" * 64,
            byteIdentical="false",
        )
        document = self.fixture.build()
        self.assertIs(False, document["repeatBuild"]["byteIdentical"])

    def test_byte_identical_absent_stays_null(self):
        document = self.fixture.build()
        self.assertIsNone(document["repeatBuild"]["byteIdentical"])

    def test_byte_identical_junk_is_rejected(self):
        self.fixture.write_facts(byteIdentical="yes")
        with self.assertRaises(metadata.ValidationError):
            self.fixture.build()

    def test_absent_measurements_are_null_not_guessed(self):
        document = self.fixture.build()
        document["cmake"]["version"] = metadata.optional("")
        self.assertIsNone(document["cmake"]["version"])

    def test_published_manifest_is_valid_json_and_world_readable(self):
        self.fixture.publish()
        json.loads(self.fixture.manifest.read_text(encoding="utf-8"))
        mode = self.fixture.manifest.stat().st_mode & 0o777
        self.assertEqual(0o644, mode, oct(mode))

    def test_no_temporary_file_is_left_behind(self):
        self.fixture.publish()
        leftovers = list(self.fixture.root.glob("remoteinput-manifest.json.*.tmp"))
        self.assertEqual([], leftovers)


class ManifestInputTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.fixture = ManifestFixture(pathlib.Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def test_malformed_build_facts_are_rejected(self):
        self.fixture.facts.write_text("this line has no separator\n", encoding="utf-8")
        with self.assertRaises(metadata.ValidationError):
            metadata.read_build_facts(self.fixture.facts)

    def test_missing_build_facts_are_rejected(self):
        with self.assertRaises(metadata.ValidationError):
            metadata.read_build_facts(self.fixture.root / "absent.txt")

    def test_missing_artifact_is_rejected(self):
        self.fixture.artifact.unlink()
        with self.assertRaises(metadata.ValidationError):
            metadata.measure_artifact(self.fixture.artifact)

    def test_empty_artifact_is_rejected(self):
        self.fixture.artifact.write_bytes(b"")
        with self.assertRaises(metadata.ValidationError):
            metadata.measure_artifact(self.fixture.artifact)

    def test_non_elf_artifact_is_rejected(self):
        self.fixture.artifact.write_bytes(b"definitely not an ELF object")
        with self.assertRaises(metadata.ValidationError):
            metadata.measure_artifact(self.fixture.artifact)

    def test_wrong_architecture_artifact_is_rejected(self):
        write_elf(self.fixture.artifact, e_machine=183)
        with self.assertRaises(metadata.ValidationError):
            metadata.measure_artifact(self.fixture.artifact)


class ManifestValidationTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.fixture = ManifestFixture(pathlib.Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def validate(self, document):
        metadata.validate_document(
            document, metadata.measure_artifact(self.fixture.artifact), "linux", "x86_64"
        )

    def test_valid_document_passes(self):
        self.validate(self.fixture.build())

    def test_size_mismatch_is_rejected(self):
        document = self.fixture.build()
        document["artifact"]["sizeBytes"] += 1
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_hash_mismatch_is_rejected(self):
        document = self.fixture.build()
        document["artifact"]["sha256"] = "f" * 64
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_unsupported_schema_version_is_rejected(self):
        document = self.fixture.build()
        document["schemaVersion"] = 99
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_malformed_revision_is_rejected(self):
        document = self.fixture.build()
        document["sourceRevision"] = "not-a-revision"
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_malformed_hashes_are_rejected(self):
        document = self.fixture.build()
        document["importManifestSha256"] = "ZZZ"
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_wrong_platform_is_rejected(self):
        document = self.fixture.build()
        document["host"]["arch"] = "aarch64"
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_missing_required_symbol_is_rejected(self):
        document = self.fixture.build()
        document["exportedSymbols"] = ["SimbaPlugin_Extra"]
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_empty_required_symbol_list_is_rejected(self):
        document = self.fixture.build()
        document["requiredSymbols"] = []
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_repeat_build_consistency_is_enforced(self):
        document = self.fixture.build()
        document["repeatBuild"] = {"sha256": "a" * 64, "byteIdentical": None}
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

        document["repeatBuild"] = {"sha256": "a" * 64, "byteIdentical": True}
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

        document["repeatBuild"] = {
            "sha256": document["artifact"]["sha256"],
            "byteIdentical": True,
        }
        self.validate(document)

    def test_repeat_build_must_be_null_when_not_executed(self):
        document = self.fixture.build()
        document["repeatBuild"] = {"sha256": None, "byteIdentical": True}
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)

    def test_local_patch_identity_must_be_present(self):
        document = self.fixture.build()
        document["localPatchIdentity"] = "  "
        with self.assertRaises(metadata.ValidationError):
            self.validate(document)


class TruncatedManifestTest(unittest.TestCase):
    def test_truncated_json_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = ManifestFixture(root)
            fixture.publish()
            full = fixture.manifest.read_text(encoding="utf-8")
            fixture.manifest.write_text(full[: len(full) // 2], encoding="utf-8")

            report = run_cli(
                "verify-manifest",
                "--manifest",
                str(fixture.manifest),
                "--artifact",
                str(fixture.artifact),
            )
            self.assertEqual(metadata.EXIT_VALIDATION_FAILED, report.returncode)
            self.assertIn("not valid JSON", report.stderr)

    def test_valid_pair_exits_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = ManifestFixture(root)
            fixture.publish()

            report = run_cli(
                "verify-manifest",
                "--manifest",
                str(fixture.manifest),
                "--artifact",
                str(fixture.artifact),
            )
            self.assertEqual(metadata.EXIT_OK, report.returncode, report.stderr)
            self.assertIn("MANIFEST_OK", report.stdout)


def run_cli(*argv):
    """Runs the helper as a subprocess-like call, capturing output."""
    import contextlib
    import io

    stdout, stderr = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        code = metadata.main(list(argv))

    class Report:
        pass

    report = Report()
    report.returncode = code
    report.stdout = stdout.getvalue()
    report.stderr = stderr.getvalue()
    return report


class SourceProvenanceTest(unittest.TestCase):
    """Provenance is checked against a temporary fixture repository, never the real tree."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.sources = self.root / "third-party/RemoteInput"
        (self.sources / "RemoteInput").mkdir(parents=True)
        self.files = {
            "third-party/RemoteInput/RemoteInput/EIOS.cxx": b"// eios\n",
            "third-party/RemoteInput/RemoteInput/EIOS.hxx": b"// header\n",
            "third-party/RemoteInput/CMakeLists.txt": b"project(x)\n",
        }
        for relative, content in self.files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        self.write_manifest(self.files)
        # The documented local metadata files must not count as source modifications.
        # import-manifest.sha256 already exists (write_manifest created it).
        (self.sources / "PROVENANCE.md").write_text("provenance\n", encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def write_manifest(self, files):
        lines = [
            f"{hashlib.sha256(content).hexdigest()}  {relative}"
            for relative, content in sorted(files.items())
        ]
        (self.sources / "import-manifest.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def options(self, **overrides):
        base = {
            "repo_root": str(self.root),
            "import_manifest": None,
            "inventory_root": None,
            "allow_extra": [],
            "patch_declaration": None,
            "source_repository": "https://example.invalid/repo",
            "source_revision": "5ea3b995f5d13e19c592a6399973fe7dadb5e656",
            "output": None,
        }
        base.update(overrides)
        return argparse.Namespace(**base)

    def test_exact_inventory_passes_with_none_identity(self):
        result = metadata.verify_sources(self.options())
        self.assertEqual("none", result["localPatchIdentity"])
        self.assertEqual(3, result["sourceFileCount"])
        self.assertEqual([], result["changedPaths"])

    def test_metadata_files_are_not_treated_as_source(self):
        result = metadata.verify_sources(self.options())
        self.assertEqual(3, result["sourceFileCount"])
        self.assertEqual([], result["unexpectedPaths"])

    def test_modified_source_is_rejected(self):
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios changed\n")
        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options())
        self.assertIn("EIOS.cxx", str(raised.exception))
        self.assertIn("documented patch", str(raised.exception))

    def test_missing_source_is_rejected(self):
        (self.sources / "RemoteInput/EIOS.hxx").unlink()
        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options())
        self.assertIn("missing", str(raised.exception))

    def test_unexpected_source_file_is_detected(self):
        (self.sources / "RemoteInput/NEWFILE.cxx").write_bytes(b"// added\n")
        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options())
        self.assertIn("NEWFILE.cxx", str(raised.exception))

    EIOS_CXX = "third-party/RemoteInput/RemoteInput/EIOS.cxx"
    EIOS_HXX = "third-party/RemoteInput/RemoteInput/EIOS.hxx"

    def test_tree_identity_changes_when_a_source_changes(self):
        before = metadata.verify_sources(self.options())["actualSourceTreeSha256"]
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched\n")
        declaration = self.declare_patch(changed=[self.EIOS_CXX])
        after = metadata.verify_sources(
            self.options(patch_declaration=str(declaration))
        )["actualSourceTreeSha256"]
        self.assertNotEqual(before, after)

    def test_documented_patch_produces_a_derived_identity(self):
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched\n")
        declaration = self.declare_patch(changed=[self.EIOS_CXX])
        declared = json.loads(declaration.read_text(encoding="utf-8"))["patchIdentity"]

        result = metadata.verify_sources(self.options(patch_declaration=str(declaration)))

        self.assertEqual(declared, result["localPatchIdentity"])
        self.assertTrue(result["localPatchIdentity"].startswith("sha256:"))
        self.assertNotEqual(metadata.PATCH_IDENTITY_NONE, result["localPatchIdentity"])
        self.assertEqual([self.EIOS_CXX], result["localPatch"]["changedPaths"])

    def test_patch_identity_cannot_be_supplied_arbitrarily(self):
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched\n")
        declaration = self.declare_patch(
            changed=[self.EIOS_CXX], identity="sha256:local-patch-1"
        )

        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options(patch_declaration=str(declaration)))
        self.assertIn("does not describe the declared patch", str(raised.exception))

    def test_patch_identity_follows_the_patched_content(self):
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched\n")
        first = metadata.verify_sources(
            self.options(patch_declaration=str(self.declare_patch(changed=[self.EIOS_CXX])))
        )["localPatchIdentity"]

        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched twice\n")
        second = metadata.verify_sources(
            self.options(patch_declaration=str(self.declare_patch(changed=[self.EIOS_CXX])))
        )["localPatchIdentity"]

        self.assertNotEqual(first, second)

    def test_patch_declaration_cannot_claim_none(self):
        declaration = self.declare_patch(identity="none")
        with self.assertRaises(metadata.ValidationError):
            metadata.read_patch_declaration(declaration)

    def test_patch_declaration_needs_a_reason(self):
        declaration = self.declare_patch(reason="   ")
        with self.assertRaises(metadata.ValidationError):
            metadata.read_patch_declaration(declaration)

    def test_declared_change_that_never_happened_is_rejected(self):
        declaration = self.declare_patch(changed=[self.EIOS_CXX])

        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options(patch_declaration=str(declaration)))
        self.assertIn("does not describe the working tree exactly", str(raised.exception))

    def test_declared_removal_that_never_happened_is_rejected(self):
        declaration = self.declare_patch(removed=[self.EIOS_HXX])

        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options(patch_declaration=str(declaration)))
        self.assertIn("does not describe the working tree exactly", str(raised.exception))

    def test_declared_addition_that_does_not_exist_is_rejected(self):
        declaration = self.declare_patch(
            added=["third-party/RemoteInput/RemoteInput/PHANTOM.cxx"]
        )

        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options(patch_declaration=str(declaration)))
        self.assertIn("does not describe the working tree exactly", str(raised.exception))

    def test_undeclared_drift_is_rejected(self):
        (self.sources / "RemoteInput/EIOS.cxx").write_bytes(b"// eios patched\n")
        (self.sources / "RemoteInput/EIOS.hxx").write_bytes(b"// header patched\n")
        declaration = self.declare_patch(changed=[self.EIOS_CXX])

        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options(patch_declaration=str(declaration)))
        self.assertIn("does not describe the working tree exactly", str(raised.exception))

    def test_declared_addition_is_measured_and_counted(self):
        added = self.sources / "RemoteInput/LOCAL.cxx"
        added.write_bytes(b"// local addition\n")
        declaration = self.declare_patch(
            added=["third-party/RemoteInput/RemoteInput/LOCAL.cxx"]
        )

        result = metadata.verify_sources(self.options(patch_declaration=str(declaration)))

        self.assertEqual(["third-party/RemoteInput/RemoteInput/LOCAL.cxx"], result["addedPaths"])
        self.assertEqual(4, result["sourceFileCount"])

    def test_ignored_extra_outside_the_allow_list_is_rejected(self):
        (self.sources / "not-really-metadata.txt").write_text("x\n", encoding="utf-8")
        with self.assertRaises(metadata.ValidationError) as raised:
            metadata.verify_sources(self.options())
        self.assertIn("not-really-metadata.txt", str(raised.exception))

    def declare_patch(
        self,
        changed=(),
        added=(),
        removed=(),
        reason="test fixture patch",
        identity=None,
    ):
        """Writes a declaration. Unless an identity is supplied, the derived one is used."""
        document = {
            "patchIdentity": "pending" if identity is None else identity,
            "reason": reason,
            "changedPaths": list(changed),
            "addedPaths": list(added),
            "removedPaths": list(removed),
        }
        if identity is None:
            digests = {}
            for relative in list(changed) + list(added):
                candidate = self.root / relative
                # A declared path that does not exist still gets a placeholder: the declaration
                # must fail the change-set comparison, not a missing file here.
                digests[relative] = (
                    hashlib.sha256(candidate.read_bytes()).hexdigest()
                    if candidate.is_file()
                    else "0" * 64
                )
            document["patchIdentity"] = metadata.derive_patch_identity(document, digests)
        path = self.root / "patch.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main(verbosity=2)
