# SC-2150 FA12: synthetic-composite cursor qualification (run 20261022T022007Z)

**Outcome: FA12 NOT MET.** The method was frozen before held-out evaluation. On held-out data it
meets the position-accuracy gates and determinism, but fails visible recall, absent-span false
detections and (raw) cut F1. No change after the freeze is reported as the FA12 result.

## Corpus (owner-approved substitute for an annotated real corpus)

On 2026-10-22 the owner approved ("yes do all") the synthetic-composite approach. A known cursor
sprite is drawn onto real, cursor-free OSRS recordings at scripted, human-like positions (minimum-jerk
moves, stops, hidden spans, edge visits), so every frame has an exact hotspot label. This is
cleaner than real capture: there is no real motion blur, and compositing happens before encoding.
It does not replace a natively labelled corpus (for example micro6 capture with logged mouse
positions).

- Builder: `tools/build_corpus.py`, using the Python standard library and Fedora ffmpeg 8.0.1. The
  encoder is OpenH264 H.264, because the system ffmpeg has no libx264.
- Sources (local `recordings/`, never committed; some login frames show an account email). The
  split is by whole recording:
  - dev: `fa5228cb` (sha256 `373c4c00…65bd`), `f9f78ab0` (`48616da7…87fb1`)
  - held-out: `78cde7ca` (`3fcd63c0…e618`), `87a88065` (`2308a377…b454`)
- Templates: a classic arrow (own work) and the RuneLite `cursor-rs3-gold` and `cursor-dragon-dagger`
  (BSD-2, runelite `15b2aafc`), all with hotspot (0,0).
- Variants per source: native 4 Mb/s, 300 kb/s compression, 1.5× capture, 0.7× post-resize,
  variable frame rate (about 35% irregular drops), cuts (game → login → game), an opaque box plus a
  translucent band drawn over the cursor, and a moving login-screen background.
- Scoring classes, fixed before held-out evaluation:
  - at least half the sprite visible: visible (counts for recall and error)
  - nothing visible: absent (counts for false detections)
  - otherwise: don't care
- Supported cases are geometric (`MouseVideoCursorTracker.resolvable`): the true displayed size,
  and the next smaller calibration scale, each render at least 12 fully covered analysis pixels.
  This is computed from labels, never from detector output.
- Dev: 9 clips, 2.4 min. Held-out: 16 clips, 3.96 min.

## Harness

`MouseVideoCursorCorpusHarnessTest` is opt-in (`SC2150_FA12_CORPUS`). It builds the frame index with
the production ffprobe command (`MouseVideoDerivativeWorker.frameIndexCommand`), derives the
production stride, decodes with the production command (`MouseVideoAnalysisWorker.samplingCommand`)
and runs the production `MouseVideoVisualAnalyzer`. `tools/score.py` joins predictions to labels by
PTS and applies the gates in 1080p-equivalent pixels, with 95% clip-bootstrap CIs.

Not covered: upload, job, finalization and HTTP. The harness exercises the analyzer path from the
decoded frames onward. The upload pipeline was qualified separately in run
`20261021T152511Z-fedora-completion`.

## Development (tuning) history, dev split only

| Iteration | Change | Recall | Median / p95 px | Absent FP |
|---|---|---|---|---|
| baseline `cursor-template/v1` | unscaled rectangle SAD | 17.7% | 594 / 1301 | 27.9% |
| v2-1 | alpha mask, displayed-scale rendering, ZNCC | 35.2% | 0 / 4.8 | 1.7% |
| v2-3 | static look-alike suppression via ±1.5 s neighbours | 73.2% | 0 / 6.0 | 14.2% |
| v2-4 | per-neighbour evidence, noise floor, arrival radius | 77.5% | 0 / 6.0 | 4.7% |
| v2-5 | gain floor 0.5 → 0.25, Fisher-z calibration, summed-area prefilter | 87.5% | 0 / 2.2 | 15.9% |
| v2-6 | previous-sample hold rule, too-small guard, scoring classes, 2 cuts per cut clip | 94.8% | 0 / 1.4 | 9.4% |
| **v2-7 (frozen)** | min score 0.80 → 0.85 | **94.7%** | **0 / 1.4** | **0.28%** |

The frozen v2-7 dev run passed every gate, with cuts F1 1.0 (2/2): `results/dev-final-cursor-template-v2.json`.
Freeze record (source, scorer and builder SHA-256, thresholds): `results/freeze.json`.

## Held-out result (evaluated once): `results/heldout-fa12.json`

| Gate (frozen) | Held-out, supported clips | 95% CI (clip bootstrap) | Pass |
|---|---|---|---|
| Median error ≤ 4 px | 0.0 | [0.0, 0.0] | yes |
| p95 error ≤ 12 px | 1.28 | [0.0, 1.89] | yes |
| Visible recall ≥ 90% | **88.2%** (4425/5016) | [72.7%, 98.1%] | **no** |
| Absent false detections ≤ 1% | **7.1%** (48/673) | [0.6%, 13.3%] | **no** |
| Cuts F1 ≥ 0.90 | **0.40** raw (4 TP, 12 FP) | — | **no** |
| Determinism | 16/16 identical observation hashes across two runs | — | yes |

All clips, including the two unsupported 1.5× dragon-dagger clips: recall 76.3%. The unsupported
clips produced no fabricated cursor positions.

**Label error found after scoring** (`results/heldout-label-errata.json`): the `87a88065` game
segment start of 52.5 s sits on the login → welcome → gameplay transition, so six clips contain two
real source cuts that the labels omit. Decoded frames confirm it. With those added, cuts are 16 TP,
0 FP, F1 1.0. This is a ground-truth correction, reported next to the raw score and not instead of it.

**Failure causes** (diagnosis only; no fix is claimed as qualified):
1. Lock-on to static interface art. A look-alike that briefly appears non-static, or is detected
   once, is then held by the stop rule. On held-out these decoys score 0.857–0.886, above the
   dev-tuned 0.85 floor (dev decoys scored at most 0.843), so the floor overfit. Examples: chat
   buttons (376,485), debug-overlay text (158,11).
2. Partial placements at the frame's left edge (x = 2–3).
3. Calibration near-tie. On `87a88065--resized-0.7x`, Fisher z of 177.6 against 176.4 chose the
   smaller scale, the too-small guard then declared a supported cursor unresolvable, and recall was 0.

## Other measurements

- Throughput (single thread, 40-core Fedora host, 480 px analysis): about 60–155 s per 16 s clip at
  30 sampled fps, which is 4–10× slower than real time. A 3,600-sample analysis would take about
  10–20 min. The analyzer does not observe cancellation between frames; cancellation is checked
  after `analyze` returns. v1 had the same structure at a similar cost per frame.
- Tuning-set rerun after formatting: 9/9 identical observation hashes, so formatting changed nothing.

## What would qualify FA12

A v3 addressing the three causes above, frozen, then evaluated once on a **new** held-out set from
recordings not used here. The four usable cursor-free sources on this host are spent. The other six
recordings are black or show only the login screen. New footage is needed: either fresh cursor-free
recordings, or micro6 captures with native mouse logs, which would also remove the synthetic
caveat.

The FA12 detector gates for actions, target regions, camera/activity and pause timing are **not
measured** here: the corpus has no labels for them. They either need labels or an explicit,
owner-approved revision of the supported-case specification.

## Reproducibility note (added 2026-10-22, after run 20261022T103825Z)

The `cursor-template/v2` / `scene-diff/v2` sources evaluated here were superseded in the working
tree by v3 before any commit. The exact v2 sources are **not retained**. `results/freeze.json`
records their SHA-256 values, and the observation hashes in `results/*.json` identify the outputs.
Re-running this result requires reconstructing v2 and matching those hashes.
