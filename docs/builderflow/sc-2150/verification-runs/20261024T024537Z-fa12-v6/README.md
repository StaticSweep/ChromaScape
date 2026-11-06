# FA12 v6 run — frozen scorer (2026-10-24)

Committed on 2026-11-01 for provenance only. `tools/score.py` is the scorer the dev gates were
measured with, byte-identical to the copy that produced
`../../20261031T160000Z-fa12-v8-dev/results/v8-dev.score.json`
(`sha256:43e2c40a1cbdb9aba292d921c6ba9272e767a409686c3ac024b377329386c238`). The v8 README and
`remaining-plan-20261031.md` both reference this path; before this commit the reference pointed at a
file that existed only in an untracked working directory.

The scorer is **frozen**: its metric definitions and its gate values are never edited. Owner
decision D-RECALL (2026-10-31) lowered the visible-recall gate from 99 % to 95 % before any
held-out label existed. That decision is applied by
`scripts/sc2150/apply_gate_decision.py` against this scorer's report, so both the frozen gate
result and the decided gate result stay visible and auditable — see
`../../../w10-final-evaluation-runbook-20261101.md`.

Usage (unchanged):

```bash
python3 tools/score.py --corpus <corpus> --split dev|heldout \
    --predictions <predictions-dir> [--compare <second-run-dir>] --out <report.json>
```

## Reproduction check (2026-11-01)

`score.py --corpus build/sc2150-fa12 --split dev --predictions build/sc2150-fa12-out-v8` reproduces
`20261031T160000Z-fa12-v8-dev/results/v8-dev.score.json` exactly — every field except the
`determinism` block (which depends on `--compare`) and the per-clip `analyze_millis` timings. The
corpus and predictions are local build artifacts on the Fedora host, not committed.
