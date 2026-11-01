#!/usr/bin/env python3
"""Apply a recorded owner gate decision to a frozen FA12 scorer report (SC-2150 W10).

The frozen scorer (``verification-runs/20261024T024537Z-fa12-v6/tools/score.py``) pins the gate
values it was frozen with. Owner decision D-RECALL lowered the visible-recall gate to 95 % on
2026-10-31, before any held-out label existed. Editing a frozen scorer after the fact would
destroy the evidence chain, so this tool instead re-evaluates the same ``overall``/``cuts``
metrics against the recorded decision and writes a separate, self-describing result document.

Both verdicts survive: ``frozen`` is what the scorer itself said, ``decided`` is what the owner's
recorded gate values say, and ``relaxed``/``tightened`` name every gate whose value differs from
the frozen one. Nothing is silently loosened.

Usage: apply_gate_decision.py --report REPORT.json --decisions DECISIONS.json
       [--decision-id D-RECALL] --out RESULT.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

REPORT_SCHEMA = "sc2150-fa12-score/v1"
DECISIONS_SCHEMA = "sc2150-gate-decisions/v1"
RESULT_SCHEMA = "sc2150-fa12-gate-decision-result/v1"
MAX_BYTES = 32 * 1024 * 1024

# Gate name -> (metric path in the report, comparison direction).
GATES = {
    "median_px": (("overall", "median_px"), "max"),
    "p95_px": (("overall", "p95_px"), "max"),
    "recall": (("overall", "recall"), "min"),
    "absent_false_rate": (("overall", "absent_false_rate"), "max"),
    "cut_f1": (("cuts", "f1"), "min"),
}


class DecisionError(Exception):
    """Refusal with a precise reason; never a partially written result."""


def load(path: Path, schema: str, label: str) -> dict:
    if path.stat().st_size > MAX_BYTES:
        raise DecisionError("%s exceeds the size cap: %s" % (label, path))
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise DecisionError("%s is not readable JSON: %s" % (label, failure))
    if not isinstance(document, dict):
        raise DecisionError("%s must be a JSON object" % label)
    if document.get("schema") != schema:
        raise DecisionError(
            "%s schema is %r, expected %r" % (label, document.get("schema"), schema)
        )
    return document


def pick_decision(document: dict, decision_id: str | None) -> dict:
    decisions = document.get("decisions")
    if not isinstance(decisions, list) or not decisions:
        raise DecisionError("decisions document has no decisions")
    if decision_id is None:
        if len(decisions) != 1:
            raise DecisionError(
                "decisions document holds %d decisions; pass --decision-id" % len(decisions)
            )
        return decisions[0]
    for decision in decisions:
        if decision.get("id") == decision_id:
            return decision
    raise DecisionError("no decision with id %r" % decision_id)


def metric(report: dict, path: tuple[str, str]) -> float | None:
    section, key = path
    value = (report.get(section) or {}).get(key)
    if value is None:
        return None
    if not isinstance(value, (int, float)):
        raise DecisionError("report metric %s.%s is not a number" % (section, key))
    return float(value)


def evaluate(report: dict, gates: dict) -> dict:
    results = {}
    values = {}
    for name, (path, direction) in GATES.items():
        value = metric(report, path)
        values[name] = value
        gate = gates[name]
        if value is None:
            results[name] = False
            continue
        results[name] = value <= gate if direction == "max" else value >= gate
    return {"gate_results": results, "metric_values": values, "all_gates_pass": all(results.values())}


def build(report_path: Path, decisions_path: Path, decision_id: str | None) -> dict:
    report = load(report_path, REPORT_SCHEMA, "scorer report")
    document = load(decisions_path, DECISIONS_SCHEMA, "decisions document")
    decision = pick_decision(document, decision_id)

    gates = decision.get("gates")
    if not isinstance(gates, dict):
        raise DecisionError("decision %r has no gates" % decision.get("id"))
    if set(gates) != set(GATES):
        missing = sorted(set(GATES) - set(gates))
        extra = sorted(set(gates) - set(GATES))
        raise DecisionError(
            "decision %r gate set must match the scorer exactly (missing=%s extra=%s)"
            % (decision.get("id"), missing, extra)
        )
    for name, value in gates.items():
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise DecisionError("gate %r is not a number" % name)

    frozen_gates = report.get("gates")
    if not isinstance(frozen_gates, dict):
        raise DecisionError("scorer report has no gates block")

    frozen = evaluate(report, frozen_gates)
    decided = evaluate(report, gates)
    relaxed = sorted(
        name for name, value in gates.items() if _looser(name, value, frozen_gates.get(name))
    )
    tightened = sorted(
        name for name, value in gates.items() if _looser(name, frozen_gates.get(name), value)
    )
    changed = sorted(name for name, value in gates.items() if value != frozen_gates.get(name))

    return {
        "schema": RESULT_SCHEMA,
        "decision": {
            "id": decision.get("id"),
            "decided_at": decision.get("decided_at"),
            "decided_by": decision.get("decided_by"),
            "statement": decision.get("statement"),
        },
        "report": {
            "path": str(report_path),
            "sha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
            "split": report.get("split"),
            "gate_scope": report.get("gate_scope"),
            "supported_clips": report.get("supported_clips"),
        },
        "decisions_document": {
            "path": str(decisions_path),
            "sha256": hashlib.sha256(decisions_path.read_bytes()).hexdigest(),
        },
        "gates": {"frozen": frozen_gates, "decided": gates},
        "changed_gates": changed,
        "relaxed": relaxed,
        "tightened": tightened,
        "frozen": frozen,
        "decided": decided,
        # The scorer's --compare may name a determinism rerun or a baseline run, and the report
        # does not record which. Equality is therefore reported, never judged here.
        "comparison": {
            "entries": len(report.get("determinism") or []),
            "equal_observations": sum(
                1 for row in report.get("determinism") or [] if row.get("equal")
            ),
        },
    }


def _looser(name: str, candidate: float | None, reference: float | None) -> bool:
    """True when `candidate` is a weaker gate than `reference` for this metric's direction."""
    if candidate is None or reference is None:
        return False
    direction = GATES[name][1]
    return candidate < reference if direction == "min" else candidate > reference


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--decisions", required=True, type=Path)
    parser.add_argument("--decision-id")
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = build(args.report, args.decisions, args.decision_id)
    except (DecisionError, OSError) as failure:
        print("refused: %s" % failure, file=sys.stderr)
        return 2
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "decision": result["decision"]["id"],
                "frozen_all_gates_pass": result["frozen"]["all_gates_pass"],
                "decided_all_gates_pass": result["decided"]["all_gates_pass"],
                "relaxed": result["relaxed"],
                "gate_results": result["decided"]["gate_results"],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
