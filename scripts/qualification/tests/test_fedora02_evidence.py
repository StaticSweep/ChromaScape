import json
import pathlib
import tempfile
import unittest

from scripts.qualification import fedora02_evidence as evidence


def complete(record, digest):
    record["versions"].update(
        os="Fedora Linux 44",
        arch="x86_64",
        java="openjdk 17.0.20.1",
        packageSha256=digest,
    )
    for result in record["checks"].values():
        result.update(status="PASS", evidence="retained log: case.log")
    return record


class Fedora02EvidenceTest(unittest.TestCase):
    REVISION = "a" * 40

    def test_new_record_fails_closed(self):
        record = evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0")

        self.assertTrue(record["checks"])
        self.assertEqual({"NOT_RUN"}, {item["status"] for item in record["checks"].values()})
        self.assertIn("tarballInstallNoSudo", record["checks"])

    def test_roles_share_every_check_except_installation(self):
        tarball = set(evidence.CHECKS["tarball"])
        rpm = set(evidence.CHECKS["rpm"])

        self.assertEqual({"tarballInstallNoSudo"}, tarball - rpm)
        self.assertEqual({"rpmDisposableInstall"}, rpm - tarball)

    def test_record_validation_rejects_missing_check(self):
        record = evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0")
        del record["checks"]["noResidue"]

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("missing=['noResidue']" in error for error in errors))

    def test_complete_matrix_requires_every_check_to_pass(self):
        records = [
            evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0"),
            evidence.new_record("rpm", "fedora-a", self.REVISION, "0.2.0"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, True)

        self.assertTrue(any("required PASS" in error for error in errors))

    def test_complete_matrix_accepts_matching_revision_and_distinct_digests(self):
        records = [
            complete(evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0"), "b" * 64),
            complete(evidence.new_record("rpm", "fedora-a", self.REVISION, "0.2.0"), "c" * 64),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, True)

        self.assertEqual([], errors)

    def test_matrix_rejects_same_digest_for_both_forms(self):
        records = [
            complete(evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0"), "b" * 64),
            complete(evidence.new_record("rpm", "fedora-a", self.REVISION, "0.2.0"), "b" * 64),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, True)

        self.assertTrue(any("distinct artifacts" in error for error in errors))

    def test_matrix_rejects_mismatched_version_or_revision(self):
        records = [
            complete(evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0"), "b" * 64),
            complete(evidence.new_record("rpm", "fedora-a", "d" * 40, "0.3.0"), "c" * 64),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, False)

        self.assertTrue(any("same application revision" in error for error in errors))
        self.assertTrue(any("same package version" in error for error in errors))

    def test_complete_record_rejects_unrecorded_versions_and_template_evidence(self):
        record = evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0")
        for result in record["checks"].values():
            result["status"] = "PASS"

        errors = evidence.validate_record(record, "fixture", True)

        self.assertTrue(any("required exact value" in error for error in errors))
        self.assertTrue(any("packageSha256 is required" in error for error in errors))
        self.assertTrue(any("PASS cannot retain template evidence" in error for error in errors))

    def test_matrix_reports_malformed_role_without_crashing(self):
        records = [
            evidence.new_record("tarball", "fedora-a", self.REVISION, "0.2.0"),
            evidence.new_record("rpm", "fedora-a", self.REVISION, "0.2.0"),
        ]
        records[1]["role"] = None
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, record in enumerate(records):
                path = pathlib.Path(directory, f"{index}.json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, False)

        self.assertTrue(any("unsupported role" in error for error in errors))
        self.assertTrue(any("each package form exactly once" in error for error in errors))

    def test_atomic_write_replaces_record_without_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory, "record.json")
            evidence.atomic_write(target, {"value": 1})
            evidence.atomic_write(target, {"value": 2})

            self.assertEqual({"value": 2}, json.loads(target.read_text(encoding="utf-8")))
            self.assertEqual([target], list(pathlib.Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
