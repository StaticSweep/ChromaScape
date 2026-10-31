# SC-2150 — W3 Step 4 browser matrix (real Spring/PostgreSQL/Chromium), 2026-10-31

Project: `C:\Dev\IdeaProjects\ChromaScape`. Plan: `remaining-plan-20261031.md` W3; answers R28 review
P2 (Step 4 only partially evidenced). Generated fixtures only: synthetic protocol mechanics, not human
input or held-out accuracy.

## Setup

- Disposable instance on 18190 (`start_verification.py`), fresh database per run (final run
  `sc2150_w3l_20261031`), corpus policy enrolling the generated fixtures as held-out.
- Script: `scripts/sc2150/qualify_review_matrix.mjs` (Playwright 1.64). Server-side bounds are in
  `MouseVideoReviewSessionPostgresTest` (commit `f512c788`).
- Receipts (not committed): `build/sc2150-w2-20261031/w3/evidence` (`matrix-result.json`,
  `post-log.json`, `action-log.json`).

## Acceptance results (final run, exit 0)

| Case | Result |
|---|---|
| A. Delayed nonce / snapshot POST / history (74 delayed requests) while labelling 38 frames | After the flushed close and a reload: 38 labels; 35 revisions; acknowledged edit versions unique and ordered |
| B. A committed snapshot whose response is lost, then later edits | Lost revision recorded exactly once; the uncertain snapshot retried with its exact request id (2 sends); later edits saved under new ids |
| D. Sealed session (after completing all 60 dense items) | Keys H/A/S/Z/Enter and every review control change nothing; no revision added; no "saved" text; direct API write 409; after reload the status reads "sealed and read-only" |
| C. Two sources reviewed in two tabs, interleaved delayed saves | 15 and 21 labels; every revision stays in its own session |
| E. Prediction-bearing responses before seal | none (observations/exports) |
| F. 9,000-frame source, review toggled off/on during index load | Complete index paged (500/page, past the old 8,200-frame truncation); no page errors |
| Containment during all runs after the runner fix | 0 jobs with unconfirmed cleanup |

Note: the Save button stays enabled on a sealed session; pressing it is refused (no revision, no
false notice). Disabling it would be clearer UX; not a write bypass.

## Product defects found by the matrix and fixed

| Commit | Defect | Fix |
|---|---|---|
| `4d64d9d8` | A failed ffmpeg availability probe (5 s, busy host) was cached for the process lifetime; every later import silently had no frame index/thumbnail/proxy yet reported SUCCEEDED | Confirmed result kept; failure re-probed after 30 s |
| `178f7031` | A job finishing on the reviewed source re-inspected it and reset the session/draft/review mode whenever the draft was clean (right after an acknowledged save or a reopen) — loaded labels vanished and a label in that window was lost | `preservesReviewOnRefresh`: a live review is kept (dirty, pending save, review mode, open session) |
| `273c6088` | Step 3 root cause: cleanup was judged by one probe taken right after the tool exited; a still-deactivating scope made clean preview/import jobs `CLEANUP_UNCONFIRMED` although the probe after stop proved the scope inactive | The post-stop probe is authoritative; private reason records the last probe |

Harness corrections made along the way (not product defects): wait for the queue to advance (or
the count to grow on the last item) per label; wait for review mode's flushed close before
reloading (a scripted reload bypasses the page's beforeunload guard); read the open session from
the review policy; complete dense coverage before sealing; tolerate route handlers outliving their
route.

## Tests

| Check | Result |
|---|---|
| `qualify_review_matrix.mjs` (final run) | exit 0, all cases above |
| `npm test` | 372 passed |
| `MouseVideoProcessRunnerTest` (2 new settling-scope cases) | 12/12 |
| `MouseVideoDerivativeWorkerTest` (1 new) | 24/24 |
| `MouseVideoReviewSessionPostgresTest` (3 new: remove-to-empty, 2,000/2,001, wire bound) | 17/17 |
| `spotlessJavaCheck` | passed |

Not rerun here: the whole Java video package and full suite (W4/W5).

## Rollback

Evidence-only instances: stop the owned JVM (`build/sc2150-w2-20261031/instance-w3/server.pid`),
drop the `sc2150_w3*_20261031` databases, delete `build/sc2150-w2-20261031`. Each product fix is
an independent commit and can be reverted on its own.
