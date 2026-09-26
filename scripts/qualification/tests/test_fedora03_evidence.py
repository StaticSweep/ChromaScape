import json
import pathlib
import tempfile
import unittest

from scripts.qualification import fedora03_evidence as evidence


def complete(record):
    record["versions"].update(
        os="Fedora Linux 44",
        arch="x86_64",
        java="openjdk 25.0.4.1",
        client="microbot-1.8.9.3.jar",
        packageRevision=record["applicationRevision"],
        packageSha256="b" * 64,
        packageManifestSha256="c" * 64,
        nativePairSha256="d" * 64,
        nativeLibrarySha256="e" * 64,
        nativeManifestSha256="f" * 64,
        nativePatchIdentity="sha256:" + "1" * 64,
    )
    for result in record["checks"].values():
        result.update(status="PASS", evidence="retained log: case.log")
    return record


class Fedora03EvidenceTest(unittest.TestCase):
    REVISION = "a" * 40

    def test_new_record_fails_closed(self):
        record = evidence.new_record("fedora-a", self.REVISION)

        self.assertTrue(record["checks"])
        self.assertEqual({"NOT_RUN"}, {item["status"] for item in record["checks"].values()})
        self.assertIn("recordingArtifact", record["checks"])
        self.assertIn("debuggerViewportFrames", record["checks"])
        self.assertEqual({"NOT_RECORDED"}, set(record["versions"].values()))

    def test_record_validation_rejects_missing_check(self):
        record = evidence.new_record("fedora-a", self.REVISION)
        del record["checks"]["cleanClose"]

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("missing=['cleanClose']" in error for error in errors))

    def test_record_validation_rejects_unknown_check(self):
        record = evidence.new_record("fedora-a", self.REVISION)
        record["checks"]["recordingInvented"] = {"status": "PASS", "evidence": "fabricated"}

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("extra=['recordingInvented']" in error for error in errors))

    def test_complete_record_requires_every_check_to_pass(self):
        record = complete(evidence.new_record("fedora-a", self.REVISION))
        record["checks"]["debuggerTickSession"] = {
            "status": "NOT_RUN",
            "evidence": "client never opened a debugger tick session",
        }

        errors = evidence.validate_record(record, "fixture", True)

        self.assertTrue(any("checks.debuggerTickSession is NOT_RUN" in error for error in errors))

    def test_complete_record_accepts_exact_versions(self):
        record = complete(evidence.new_record("fedora-a", self.REVISION))

        errors = evidence.validate_record(record, "fixture", True)

        self.assertEqual([], errors)

    def test_complete_record_rejects_unrecorded_versions_and_template_evidence(self):
        record = evidence.new_record("fedora-a", self.REVISION)
        for result in record["checks"].values():
            result["status"] = "PASS"

        errors = evidence.validate_record(record, "fixture", True)

        self.assertTrue(any("required exact value" in error for error in errors))
        self.assertTrue(any("packageSha256 is required" in error for error in errors))
        self.assertTrue(any("nativePatchIdentity is required" in error for error in errors))
        self.assertTrue(any("PASS cannot retain template evidence" in error for error in errors))

    def test_complete_record_rejects_package_revision_mismatch(self):
        record = complete(evidence.new_record("fedora-a", self.REVISION))
        record["versions"]["packageRevision"] = "9" * 40

        errors = evidence.validate_record(record, "fixture", True)

        self.assertTrue(any("packageRevision must equal applicationRevision" in error for error in errors))

    def test_record_validation_rejects_malformed_native_hashes(self):
        record = evidence.new_record("fedora-a", self.REVISION)
        record["versions"]["nativeLibrarySha256"] = "not-a-hash"
        record["versions"]["nativePatchIdentity"] = "patch-1"

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("versions.nativeLibrarySha256" in error for error in errors))
        self.assertTrue(any("versions.nativePatchIdentity" in error for error in errors))

    def test_record_validation_rejects_wrong_story_or_role(self):
        record = evidence.new_record("fedora-a", self.REVISION)
        record["story"] = "FEDORA-02"
        record["role"] = "tarball"

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("story must be FEDORA-03" in error for error in errors))
        self.assertTrue(any("role must be live" in error for error in errors))

    def test_record_roundtrip_through_files_and_verify_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            partial = pathlib.Path(directory, "partial.json")
            evidence.atomic_write(partial, evidence.new_record("fedora-a", self.REVISION))
            self.assertEqual(
                [],
                evidence.validate_record(json.loads(partial.read_text(encoding="utf-8")), "partial", False),
            )

            full = pathlib.Path(directory, "full.json")
            evidence.atomic_write(full, complete(evidence.new_record("fedora-a", self.REVISION)))
            self.assertEqual(
                [],
                evidence.validate_record(json.loads(full.read_text(encoding="utf-8")), "full", True),
            )

    def test_atomic_write_replaces_record_without_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory, "record.json")
            evidence.atomic_write(target, {"value": 1})
            evidence.atomic_write(target, {"value": 2})

            self.assertEqual({"value": 2}, json.loads(target.read_text(encoding="utf-8")))
            self.assertEqual([target], list(pathlib.Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
