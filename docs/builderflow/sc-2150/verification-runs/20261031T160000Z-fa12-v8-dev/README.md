# FA12 dev rerun — cursor-template/v8 (hold ends at a missed sample), 2026-10-31

W6, dev split only (held-out not used). Harness: `MouseVideoCursorCorpusHarnessTest` with
`SC2150_FA12_CORPUS=build/sc2150-fa12`, `SC2150_FA12_SPLIT=dev`; scorer: frozen
`../20261024T024537Z-fa12-v6/tools/score.py`. Baseline: the R28 predictions
(`build/sc2150-fa12-out-r28`, `cursor-template/v7`).

## Defect

In `compressed-300k` the cursor left over cursor-like art; the sample in which it left was missed,
and v7 then accepted the static art at the same spot as a "held" (stopped) cursor, because the
hold compared with the last detection rather than the previous sample's. The hold then renewed
itself every sample: 87 samples, i.e. all 47 absent-frame false detections of R28 plus 40
partial-visibility don't-care frames. On the R28 dev predictions every one of the 265 correct
holds follows a detection in the immediately previous sample.

Fix: a missed sample ends the hold (`MouseVideoCursorTracker.holds` requires `missed == 0`);
method bumped to `cursor-template/v8`. Regression test
`MouseVideoCursorTrackerTest.aHoldDoesNotSurviveAMissedSample` failed before the fix and passes after.

## Results (dev, 8 supported clips)

| | recall | absent false rate | median px | p95 px | cut F1 | gates failing |
|---|---|---|---|---|---|---|
| R28 (v7) | 0.9657 | 0.1295 | 0.0 | 2.15 | pass | recall, absent false |
| **v8** | **0.9657** | **0.0** | 0.0 | 2.15 | pass | recall |

Per clip, only `compressed-300k` changed (absent false 0.547 → 0.0); recall is identical on every
clip. Recall (0.99 gate) still fails: `capture-1.5x` (unsupported, 0.0), `cut` 0.885,
`overlay` 0.911, `resized-0.7x` 0.954 — next W6 targets, still dev only.

## Tests

`./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*'`: 315 tests, 0 failures
(2 opt-in skips); `spotlessJavaCheck` passed.

## Files

`results/r28-dev.score.json`, `results/v8-dev.score.json`. Predictions stay in
`build/sc2150-fa12-out-v8` (not committed).
