# SC-2150 FA12 v3: synthetic-composite cursor qualification (run 20261022T103825Z)

Status: **in development. The held-out split has not been evaluated.** This file records the
pre-registered design before any held-out scoring.

Predecessor: run `20261022T022007Z-fa12-synthetic`. There, the frozen `cursor-template/v2` failed
held-out recall, absent false detections and raw cut F1. All four of its sources are spent and join
this run's development split.

## Split (fixed before any v3 tuning): `results/split.json`

- 14 recordings not used by the previous run, found in `ChromaScape-sc1976-pr/recordings` and
  `ChromaScape-sc1991-local-integration-20261013/recordings` and deduplicated by SHA-256. They are
  sorted by SHA-256 and assigned alternately to held-out and dev, starting with held-out.
- Held-out: `2e261112`, `d2ba83a8`, `16ed9d0e`, `4a0ad618`, `7f367df1`, `18b787da`, `0f78c471`.
- Dev: `4c4898c5`, `931d13fc`, `f16b6dd7`, `6a594507`, `11e2eec3`, `3133de8d`, `2e64e2fc`, plus
  the four spent sources `fa5228cb`, `f9f78ab0`, `78cde7ca`, `87a88065`.
- Media stays local and is never committed: character names, and on login screens an account
  email, are visible.

## Corpus recipe: `tools/build_corpus.py`

- 10 s clips at 30 fps, encoded with OpenH264 (Fedora ffmpeg 8.0.1 has no libx264).
- **Raw-pixel segment screening, with no detector involved:** reject a segment if any
  consecutive 480 px grey frame pair has a mean absolute difference above 0.04 (interface pops,
  teleports, logins), or if mean luma is below 0.06. This prevents the previous run's label error,
  where real source cuts went unlabelled. As a consequence, cut false positives are measured only
  on footage without abrupt changes. That is stated here as a known limitation.
- An inserted cut is labelled **unobservable**, and excluded, when its boundary frames differ by
  less than 0.02 in raw pixels.
- Variants: native size; HiDPI 1.5× and 2× (nearest-neighbour sprite enlargement); 1.5× at
  300 kb/s; 2× with 0.7× post-resize; 1.5× with variable frame rate; 1.5× with cuts (three
  segments); 1.5× under an opaque box plus a translucent band. Templates rotate across the
  own-work arrow and RuneLite `rs3-gold` and `dragon-dagger` (BSD-2). The analyzer is always
  given the native template; scale is calibrated.
- Long stops (2–4 s) are included so the stop/hold rule is exercised.
- Dev: 87 clips (one skipped: a 16.7 s recording could not supply three screened cut pieces).
  Held-out: 56 clips.

## Scoring (unchanged rules, plus one correction): `tools/score.py`

The visible / absent / don't-care classes and the 1080p-equivalent gates are as in the previous
run. Supported cases are geometric: `MouseVideoCursorTracker.resolvable`.

**Correction made during v3 development:** `resolvable` now uses the same all-phase rendering
rule the tracker requires. In v2 it checked phase 0 only, so a few cursors were called "supported"
that the tracker can never render. This is decided from geometry, not from results.

## Gates (frozen, plan §10)

| Gate | Threshold |
|---|---|
| Median position error | ≤ 4 px (1080p-equivalent) |
| p95 position error | ≤ 12 px |
| Visible recall | ≥ 90% |
| Absent-span false detections | ≤ 1% |
| Cut F1 (one indexed-frame tolerance) | ≥ 0.90 |
| Determinism | identical observation hashes across two runs |

Actions, target regions, camera/activity and pause timing remain **unmeasured**: the corpus has no
labels for them.

## Development results (dev split only; the held-out split is NOT evaluated)

| Iteration | Change | Recall | Median / p95 px | Absent FP | Cuts TP/FP/FN |
|---|---|---|---|---|---|
| `dev-v3-dev-1` | v3: hysteresis 0.92, scale tie-break, flat arrays (2.7× faster) | 68.5% | 1.19 / 2.82 | 2.6% | 5/0/3 |
| `dev-v3-dev-2a` | gain floor 0.35, tie-break reverted (it scored 73/87 vs 77/87 correct scales offline), all-phase `resolvable`, start 0.88 | 78.5% | 1.43 / 3.80 | 1.02% | 5/0/3 |
| `dev-v3-dev-2b` | as 2a with start 0.85 (hysteresis off) | 79.7% | 1.47 / 4.10 | 1.31% | 5/0/3 |
| `dev-v3-dev-3-w960` | as 2a at 960 px sampling | 62.8% | 1.19 / 2.28 | 1.89% | 6/0/2 |
| `dev-v3-dev-4` | 2^(1/8) scale grid, windowed search while tracking, cut floor 0.015 | 80.4% | 0.76 / 2.82 | **0.0%** | 7/37/1 |
| **`dev-v3-dev-final`** | as 4 with cut floor 0.04 (chosen by offline replay of the cut rule) | **80.4%** | **0.76 / 2.82** | **0.0%** | **6/0/2** |

Final v3 on dev: recall CI [73.6%, 86.7%]. By template: rs3-gold 92.1%, arrow 84.7%,
dragon-dagger 61.3%. Seven dagger clips stay uncalibrated, because a thin diagonal line matches
itself at many scales.

Cut floor 0.04 equals the segment-screening threshold, so zero false cuts is partly a consequence
of the screening. The two missed cuts have boundary differences of 0.017 and 0.037, which this rule
cannot reach without flooding false cuts (dev run at 0.015: 37 false cuts).

## Decision

v3 fails two gates on its own development split: recall (80.4% < 90%) and cut F1 (0.857 < 0.90).
Evaluating the held-out split with a method already known to fail would spend the only fresh,
unseen footage on this host for no qualification value. **The held-out split is therefore left
unevaluated for a future method.** No held-out media has been scored, and no held-out label has
been inspected.

What would plausibly close the gap, all untested:
- Colour input, instead of grayscale only; it particularly affects thin coloured cursors.
- A learned detector.
- Per-template calibration hints, such as the operator declaring display scale.
- Natively labelled footage (micro6 capture with logged mouse positions) to replace synthetic
  compositing.
