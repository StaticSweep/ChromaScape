# SC-2150 FA12 gate amendment (2026-10-23)

On 2026-10-23 the owner raised the FA12/PA11 visible-recall gate from 90% to **99%**. The
instruction was "target should be 99%". All other FA12 gates are unchanged:

| Gate | Threshold |
|---|---|
| Median position error | ≤ 4 px (1080p-equivalent) |
| p95 position error | ≤ 12 px |
| Visible recall | **≥ 99%** (was ≥ 90%) |
| Absent-span false detections | ≤ 1% |
| Cut F1 | ≥ 0.90 |
| Determinism | identical observation hashes across two runs |

- This tightens the gate and relaxes nothing. Earlier plans (`full-plan.md`,
  `completion-plan-after-independent-review.md`,
  `post-verification-remediation-and-full-completion-plan.md`,
  `verification-and-acceptance-plan.md`) still say 90%. They are kept as history; this amendment
  supersedes them.
- Neither earlier run met the old 90% gate (`20261022T022007Z-fa12-synthetic` and
  `20261022T103825Z-fa12-v3`), so no earlier result changes status.
- Felix acceptance items PA11 and FA12 on task `task_1792382767281_cd8dbu69` carry the new value.
- The owner also asked for a live verification flow. FA12 will additionally be scored through the
  running service (upload → ingest → `visual/v1` → published observations) with
  `scripts/sc2150/qualify_live_cursor.py`. Per-frame parity with the offline harness shows the
  harness measures what production serves.
