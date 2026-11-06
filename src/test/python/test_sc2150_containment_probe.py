"""Tests for scripts/sc2150/containment_probe.py (SC-2150 Step 3, R28 review P2).

The earlier probe zipped `systemctl show --value` output with its requested property list and
treated an unreadable cgroup as empty, so its receipts mislabelled MainPID and could not prove
that a scope's processes were gone. These tests pin named-property parsing (any order, missing
properties, values containing '='), the READ_FAILED / EMPTY / MEMBERS distinction, process
identity from /proc stat (including commands with spaces and parentheses), the containment
verdict, and the loopback/build-output refusals. No systemd, server or real process is used.
"""

from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "containment_probe.py"
SPEC = importlib.util.spec_from_file_location("containment_probe", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

STAT = ("4242 (bwrap (sandbox) x) S 4200 4242 4242 0 -1 4194560 120 0 0 0 1 2 0 0 20 0 1 0 "
        "987654 1234 56 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0")


class ParseShowTest(unittest.TestCase):
    def test_named_properties_survive_any_order_and_missing_keys(self):
        # systemd prints MainPID before ControlGroup and omits TasksCurrent on this scope.
        text = "MainPID=0\nMemoryCurrent=548864\nControlGroup=/user.slice/x.scope\nActiveState=active\n"
        parsed = MODULE.parse_show(text)
        self.assertEqual("0", parsed["MainPID"])
        self.assertEqual("548864", parsed["MemoryCurrent"])
        self.assertEqual("/user.slice/x.scope", parsed["ControlGroup"])
        self.assertNotIn("TasksCurrent", parsed)

    def test_values_may_contain_equals_and_be_empty(self):
        parsed = MODULE.parse_show("Result=success\nId=a=b.scope\nControlGroup=\n")
        self.assertEqual("a=b.scope", parsed["Id"])
        self.assertEqual("", parsed["ControlGroup"])


class ReadMembersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def cgroup(self, name, content):
        directory = self.root / name
        directory.mkdir(parents=True)
        (directory / "cgroup.procs").write_text(content, encoding="ascii")

    def test_members_and_proven_empty(self):
        self.cgroup("user.slice/a.scope", "11\n12\n")
        self.cgroup("user.slice/b.scope", "")
        self.assertEqual({"state": "MEMBERS", "pids": [11, 12], "error": None},
                         MODULE.read_members("/user.slice/a.scope", self.root))
        self.assertEqual({"state": "EMPTY", "pids": [], "error": None},
                         MODULE.read_members("/user.slice/b.scope", self.root))

    def test_unreadable_or_vanished_cgroup_is_never_empty(self):
        result = MODULE.read_members("/user.slice/gone.scope", self.root)
        self.assertEqual("READ_FAILED", result["state"])
        self.assertIn("FileNotFoundError", result["error"])
        self.assertEqual("NO_CGROUP", MODULE.read_members("", self.root)["state"])


class ProcessIdentityTest(unittest.TestCase):
    def test_parse_stat_with_spaces_and_parentheses_in_command(self):
        parsed = MODULE.parse_proc_stat(STAT)
        self.assertEqual({"comm": "bwrap (sandbox) x", "state": "S", "ppid": 4200, "sid": 4242,
                          "start_ticks": 987654}, parsed)

    def test_identity_reads_stat_and_cmdline_or_reports_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp) / "4242"
            proc.mkdir()
            (proc / "stat").write_text(STAT, encoding="utf-8")
            (proc / "cmdline").write_bytes(b"bwrap\0--die-with-parent\0ffmpeg\0")
            identity = MODULE.process_identity(4242, tmp)
            self.assertEqual(987654, identity["start_ticks"])
            self.assertEqual(["bwrap", "--die-with-parent", "ffmpeg"], identity["cmdline"])
            self.assertIn("error", MODULE.process_identity(9999, tmp))


class VerdictTest(unittest.TestCase):
    @staticmethod
    def scope(active, members_state):
        return {"properties": {"ActiveState": active}, "members": {"state": members_state}}

    def test_contained_only_with_terminal_job_and_proven_empty_scopes(self):
        done = {"status": "SUCCEEDED"}
        self.assertEqual("CONTAINED", MODULE.verdict(done, {"a": self.scope("inactive", "EMPTY")}))
        self.assertEqual("CONTAINED", MODULE.verdict(done, {"a": self.scope(None, "NO_CGROUP")}))
        self.assertEqual("SURVIVORS", MODULE.verdict(done, {"a": self.scope("inactive", "MEMBERS")}))
        self.assertEqual("UNPROVEN", MODULE.verdict(done, {"a": self.scope("inactive", "READ_FAILED")}))
        self.assertEqual("SCOPE_STILL_ACTIVE", MODULE.verdict(done, {"a": self.scope("active", "EMPTY")}))
        self.assertEqual("NO_SCOPE_OBSERVED", MODULE.verdict(done, {}))
        self.assertEqual("JOB_NOT_TERMINAL", MODULE.verdict({"status": "RUNNING"}, {}))
        self.assertEqual("JOB_NOT_TERMINAL", MODULE.verdict(None, {}))


class SystemdTest(unittest.TestCase):
    def test_show_does_not_use_value_mode_and_keeps_names(self):
        calls = []

        class Result:
            def __init__(self, stdout, returncode=0, stderr=""):
                self.stdout, self.returncode, self.stderr = stdout, returncode, stderr

        def runner(args, **kwargs):
            calls.append(args)
            return Result("TasksCurrent=3\nControlGroup=\nActiveState=inactive\n")

        record = MODULE.Systemd(runner).show("chromascape-video-1.scope")
        self.assertNotIn("--value", calls[0])
        self.assertEqual("3", record["properties"]["TasksCurrent"])
        self.assertEqual("NO_CGROUP", record["members"]["state"])


class RefusalTest(unittest.TestCase):
    def test_non_loopback_and_owner_service_are_refused(self):
        for url in ("http://192.168.1.6:18190", "https://127.0.0.1:18190", "http://127.0.0.1:8090"):
            with self.subTest(url=url):
                with self.assertRaises(SystemExit):
                    MODULE.require_loopback(url)
        MODULE.require_loopback("http://127.0.0.1:18190")

    def test_receipt_must_be_under_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            cwd = os.getcwd()
            os.chdir(tmp)
            try:
                with self.assertRaises(SystemExit):
                    MODULE.require_build_output("docs/receipt.json")
                self.assertTrue(str(MODULE.require_build_output("build/x/receipt.json")).endswith("receipt.json"))
            finally:
                os.chdir(cwd)


if __name__ == "__main__":
    unittest.main()
