import json
import pathlib
import tempfile
import unittest

from scripts.qualification import sc1668_evidence as evidence


class Sc1668EvidenceTest(unittest.TestCase):
    REVISION = "a" * 40

    def test_new_record_fails_closed(self):
        record = evidence.new_record("fedora-primary", "fedora-a", self.REVISION)

        self.assertTrue(record["checks"])
        self.assertEqual({"NOT_RUN"}, {item["status"] for item in record["checks"].values()})

    def test_record_validation_rejects_missing_check(self):
        record = evidence.new_record("windows", "windows-a", self.REVISION)
        del record["checks"]["replay"]

        errors = evidence.validate_record(record, "fixture", False)

        self.assertTrue(any("missing=['replay']" in error for error in errors))

    def test_complete_matrix_requires_every_check_to_pass(self):
        records = [
            evidence.new_record("fedora-primary", "fedora-a", self.REVISION),
            evidence.new_record("fedora-secondary", "fedora-b", self.REVISION),
            evidence.new_record("windows", "windows-a", self.REVISION),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, True)

        self.assertTrue(any("required PASS" in error for error in errors))

    def test_complete_matrix_accepts_same_revision_and_distinct_hosts(self):
        records = [
            evidence.new_record("fedora-primary", "fedora-a", self.REVISION),
            evidence.new_record("fedora-secondary", "fedora-b", self.REVISION),
            evidence.new_record("windows", "windows-a", self.REVISION),
        ]
        for record in records:
            record["versions"].update(
                os="exact-os",
                arch="exact-arch",
                java="exact-java",
                client="exact-client",
            )
            if record["role"] != "windows":
                record["versions"]["nativeArtifactSha256"] = "b" * 64
            for result in record["checks"].values():
                result.update(status="PASS", evidence="retained log: case.log")
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for record in records:
                path = pathlib.Path(directory, record["role"] + ".json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, True)

        self.assertEqual([], errors)

    def test_complete_matrix_rejects_unrecorded_versions_and_template_evidence(self):
        record = evidence.new_record("fedora-primary", "fedora-a", self.REVISION)
        for result in record["checks"].values():
            result["status"] = "PASS"

        errors = evidence.validate_record(record, "fixture", True)

        self.assertTrue(any("required exact value" in error for error in errors))
        self.assertTrue(any("required for completion" in error for error in errors))
        self.assertTrue(any("PASS cannot retain template evidence" in error for error in errors))

    def test_matrix_rejects_reused_fedora_host_identity(self):
        records = [
            evidence.new_record("fedora-primary", "same-host", self.REVISION),
            evidence.new_record("fedora-secondary", "same-host", self.REVISION),
            evidence.new_record("windows", "windows-a", self.REVISION),
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, record in enumerate(records):
                path = pathlib.Path(directory, f"{index}.json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, False)

        self.assertIn("Fedora qualification records must identify two distinct hosts", errors)

    def test_matrix_reports_malformed_role_without_crashing(self):
        records = [
            evidence.new_record("fedora-primary", "fedora-a", self.REVISION),
            evidence.new_record("fedora-secondary", "fedora-b", self.REVISION),
            evidence.new_record("windows", "windows-a", self.REVISION),
        ]
        records[2]["role"] = None
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, record in enumerate(records):
                path = pathlib.Path(directory, f"{index}.json")
                path.write_text(json.dumps(record), encoding="utf-8")
                paths.append(path)

            errors = evidence.validate_matrix(paths, False)

        self.assertTrue(any("unsupported role" in error for error in errors))
        self.assertTrue(any("each role exactly once" in error for error in errors))

    def test_atomic_write_replaces_record_without_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory, "record.json")
            evidence.atomic_write(target, {"value": 1})
            evidence.atomic_write(target, {"value": 2})

            self.assertEqual({"value": 2}, json.loads(target.read_text(encoding="utf-8")))
            self.assertEqual([target], list(pathlib.Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
