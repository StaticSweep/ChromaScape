"""Bounded tests for scripts/sc2150/apply_gate_decision.py (SC-2150 W10 gate decisioning).

The tool must never hide a loosened gate and must never rewrite the frozen scorer's verdict.
The primary fixture is the committed dev report from `20261031T160000Z-fa12-v8-dev`, so the
95 % decision recorded by the owner (D-RECALL) is checked against real evidence rather than a
hand-made document.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "sc2150" / "apply_gate_decision.py"
V8_REPORT = (
    ROOT
    / "docs"
    / "builderflow"
    / "sc-2150"
    / "verification-runs"
    / "20261031T160000Z-fa12-v8-dev"
    / "results"
    / "v8-dev.score.json"
)
DECISIONS = ROOT / "docs" / "builderflow" / "sc-2150" / "gate-decisions-20261101.json"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TOOL = load("apply_gate_decision", SCRIPT)


def decision_document(gates, decisions=1):
    return {
        "schema": "sc2150-gate-decisions/v1",
        "decisions": [
            {
                "id": "D-TEST-%d" % index,
                "decided_at": "2026-10-31",
                "decided_by": "owner",
                "gates": dict(gates),
            }
            for index in range(decisions)
        ],
    }


class ApplyGateDecisionTest(unittest.TestCase):
    def write(self, directory, name, document):
        path = Path(directory) / name
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def test_the_recorded_recall_decision_passes_real_dev_evidence(self):
        result = TOOL.build(V8_REPORT, DECISIONS, "D-RECALL")

        self.assertFalse(result["frozen"]["all_gates_pass"])
        self.assertFalse(result["frozen"]["gate_results"]["recall"])
        self.assertTrue(result["decided"]["all_gates_pass"])
        self.assertEqual(result["relaxed"], ["recall"])
        self.assertEqual(result["changed_gates"], ["recall"])
        # Every other gate keeps the frozen value and the frozen verdict.
        for name in ("median_px", "p95_px", "absent_false_rate", "cut_f1"):
            self.assertEqual(result["decided"]["gate_results"][name],
                             result["frozen"]["gate_results"][name])
        self.assertAlmostEqual(result["decided"]["metric_values"]["recall"],
                               0.9656786271450858)
        # The committed report compared against the v7 baseline, so no observation is equal; the
        # tool reports that without calling it a determinism verdict.
        self.assertEqual(result["comparison"]["entries"], 9)
        self.assertEqual(result["comparison"]["equal_observations"], 0)

    def test_a_tightened_gate_is_reported_and_can_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            gates = dict(json.loads(V8_REPORT.read_text())["gates"], recall=0.995)
            path = self.write(directory, "decisions.json", decision_document(gates))

            result = TOOL.build(V8_REPORT, path, None)

        self.assertEqual(result["tightened"], ["recall"])
        self.assertEqual(result["relaxed"], [])
        self.assertFalse(result["decided"]["gate_results"]["recall"])

    def test_a_gate_set_that_does_not_match_the_scorer_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            gates = dict(json.loads(V8_REPORT.read_text())["gates"])
            incomplete = {key: value for key, value in gates.items() if key != "cut_f1"}
            with self.assertRaises(TOOL.DecisionError):
                TOOL.build(V8_REPORT,
                           self.write(directory, "missing.json", decision_document(incomplete)),
                           None)
            with self.assertRaises(TOOL.DecisionError):
                TOOL.build(V8_REPORT,
                           self.write(directory, "extra.json",
                                      decision_document(dict(gates, gate_typo=1.0))),
                           None)

    def test_an_unknown_report_schema_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            report = json.loads(V8_REPORT.read_text())
            report["schema"] = "something-else/v1"
            path = self.write(directory, "report.json", report)
            with self.assertRaises(TOOL.DecisionError):
                TOOL.build(path, DECISIONS, "D-RECALL")

    def test_ambiguous_decision_documents_require_an_explicit_id(self):
        with tempfile.TemporaryDirectory() as directory:
            gates = json.loads(V8_REPORT.read_text())["gates"]
            path = self.write(directory, "two.json", decision_document(gates, decisions=2))
            with self.assertRaises(TOOL.DecisionError):
                TOOL.build(V8_REPORT, path, None)
            self.assertEqual(TOOL.build(V8_REPORT, path, "D-TEST-1")["decision"]["id"],
                             "D-TEST-1")

    def test_the_written_result_is_byte_identical_across_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "a.json"
            second = Path(directory) / "b.json"
            for out in (first, second):
                code = TOOL.main(
                    ["--report", str(V8_REPORT), "--decisions", str(DECISIONS),
                     "--decision-id", "D-RECALL", "--out", str(out)]
                )
                self.assertEqual(code, 0)
            self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
