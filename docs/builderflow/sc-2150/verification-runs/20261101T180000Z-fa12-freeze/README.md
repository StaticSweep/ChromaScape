# FA12 freeze — detector and scorer inputs (2026-11-01)

Pinned before the first held-out scoring, per W10. `freeze.json` is the record; nothing in it may
change after that run.

| Frozen | Value |
|---|---|
| Algorithm | `visual/v1` |
| Cursor tracker | `cursor-template/v8` |
| Sample stride | `pts-window-stride/v1` |
| Corrections / frame preview | `corrections/v1`, `frame-png/v1` |
| Sampling | 30 fps target, 480 px sample width |
| Thresholds | `minScore 0.85`, `startScore 0.88`, `minGain 0.35`, `maxGain 1.6`, `minContrast 12`, `ambiguityMargin 0.05`, `staticMaxDelta 6` |
| Templates | `arrow`, `dragon-dagger`, `rs3-gold` (hashes in `freeze.json`; the PNGs stay out of Git) |
| Scorer | `…/20261024T024537Z-fa12-v6/tools/score.py`, `sha256:43e2c40a…c238` |
| Gate decision | D-RECALL (`…/gate-decisions-20261101.json`, `sha256:1c24df56…2c29`): recall 95 %, everything else unchanged |

A real-footage run copies the template PNGs into its corpus and records their hashes in that run's
staging manifest; the manifest hashes must match the pinned set above.

## Dry run of the whole chain (dev corpus, no held-out data)

The runbook's steps were executed end to end against real artifacts, without touching held-out
material:

```
stage_corpus.py --labels build/sc2150-fa12/dev --media-dir build/sc2150-fa12/dev \
    --templates build/sc2150-fa12/templates --split dev --out /tmp/sc2150-chain/corpus
score.py --corpus /tmp/sc2150-chain/corpus --split dev \
    --predictions build/sc2150-fa12-out-v8 --out /tmp/sc2150-chain/report.json
apply_gate_decision.py --report … --decisions gate-decisions-20261101.json \
    --decision-id D-RECALL --out /tmp/sc2150-chain/decision.json
```

| Check | Result |
|---|---|
| Staging | 9 clips, 3 templates, every media digest verified |
| Scorer `overall` | **identical** to the committed `v8-dev.score.json` (recall 0.9657, p95 2.15 px, absent false 0) |
| Gate decision | frozen verdict fails recall (99 % gate); decided verdict passes all gates (95 %); `relaxed: ["recall"]` |

Two stager gaps surfaced and were fixed by this dry run: synthetic corpora emit
`sc2150-fa12-labels/v1` (now accepted), and one source id can carry several clips, so
`--media-dir` resolves `<clip_id>.mp4` next to the labels while `--media SOURCE_ID=PATH` stays
available for media held elsewhere.

## Dry run including the harness (2026-11-02)

The remaining runbook step was exercised too, on the same staged corpus, so the whole order of
operations is proven with the production code path:

```
SC2150_FA12_CORPUS=/tmp/sc2150-chain/corpus SC2150_FA12_SPLIT=dev \
SC2150_FA12_OUT=/tmp/sc2150-chain/pred-harness \
./gradlew test --tests '…MouseVideoCursorCorpusHarnessTest'
score.py --corpus /tmp/sc2150-chain/corpus --split dev \
    --predictions /tmp/sc2150-chain/pred-harness --out report-harness.json
apply_gate_decision.py --report report-harness.json --decision-id D-RECALL --out decision.json
```

| Check | Result |
|---|---|
| Harness | 9 prediction documents produced from the staged corpus (9m 13s) |
| Scorer `overall` | identical to `v8-dev.score.json` |
| Per-clip `observations_sha256` | identical on all 9 clips — the harness reproduces the committed predictions exactly |
| Gate decision | frozen fails recall (99 %), decided passes all gates (95 %), `relaxed: ["recall"]` |

Every step of `w10-final-evaluation-runbook-20261101.md` has now been executed end to end; only the
owner's sealed labels are missing.
