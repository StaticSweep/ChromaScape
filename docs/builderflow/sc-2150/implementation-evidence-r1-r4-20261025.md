# SC-2150 implementation evidence — R1–R4 review-mode corrections (2026-10-25)

Project: `C:\Dev\IdeaProjects\ChromaScape`. Branch: `codex/sc-2150-mouse-video-import`.
Base commit: `e1eb17cd34af79333cff3f61c79a5271937a38a3` plus the working-tree increment.
No commit, push, Felix transition or Shortcut write is claimed by this note.

## Scope

Corrects the four review-mode defects reported in
`C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\review-remaining-20261025.md`.

- **R1 — Blind leaks predictions.** `showSuggestionLayer` now suppresses every prediction layer
  whenever Blind is active, including the original prediction overlay, the guess box and the trail.
  Blind state is decided before observations load and before the first frame renders; a `· heldout ·`
  source is forced blind as soon as it is opened, so predictions never appear before the review
  toggle is used. Each review label records structured provenance
  (`INDEPENDENT` / `DETECTOR_ASSISTED`), held-out seeding keeps only `INDEPENDENT` history and a
  save whose reason says `without-suggestions` is refused while the draft contains any assisted or
  unknown label.
- **R2 — Save race loses labels.** Cumulative saves now snapshot the draft, version every
  save-relevant edit, serialize through a queue and acknowledge only the submitted version. A label
  added during a slow request keeps the draft dirty and triggers a second cumulative save. Closing
  or switching analysis/source waits for the final acknowledgement; a failed flush leaves review
  mode open with the error visible. Empty cumulative drafts are still saved so removing the last
  label persists.
- **R3 — Seed crosses analyses.** `seedDraft(history, { analysisId })` selects the latest revision
  for the selected analysis, not the latest source-wide revision. Drafts carry
  `{ sourceId, analysisId }`; labels and saves are refused when the context no longer matches, and
  switching analysis flushes, clears the draft/undo state and reseeds for the new analysis.
- **R4 — Trail bridges discontinuities.** Trails now split at `SCENE_CUT` observations, explicit
  hidden/ambiguous/unsupported cursor samples, frame-index holes (dropped frames or timing changes)
  and gaps longer than 0.3 s. The expected-next dot is drawn only when the last trail sample is the
  frame being reviewed, so no extrapolation crosses a hidden span.

## Changed files

- `src/main/resources/static/js/mouse-video-review-core.js` — suggestion gating, label provenance,
  analysis-filtered seeding, save acknowledgement/queue helpers, trail barriers and targeted
  extrapolation; hardened `frameTime`.
- `src/main/resources/static/js/mouse-video.js` — draft version/context state, blind UI and render
  gating, forced held-out entry, serialized cumulative saves, final flush, guarded close/switch,
  analysis-scoped reseeding, provenance on review labels.
- `src/main/resources/templates/mouse-video.html` — cache-busting versions bumped to
  `mouse-video-review-core.js?v=2` and `mouse-video.js?v=6`.
- `src/test/js/mouse-video.test.js` — regressions for analysis-bound seeding, cut/ambiguous/frame-hole
  trails, VFR trail timing, suggestion gating, independent/assisted provenance, blind save refusal
  and versioned save acknowledgement/queue behaviour.

Source hashes at the time of this evidence:

| File | SHA-256 |
|---|---|
| `mouse-video.js` | `524822b749016a818b0329b2ff6564982f0cb70f01b9813e271a678a92d30d39` |
| `mouse-video-review-core.js` | `d8dd689a6f5a83ef7a2d8ef71265f208dab6c4dda93d7749736b360afecb6aad` |
| `mouse-video.test.js` | `14303fa9aea475ec6f3862f2f1b22f237452dc616cce61f55882f2455122b276` |
| `mouse-video.html` | `aec4e2a13cde49f5666dd96ee200407a15380efa883cbe61c67f6efbf6e56df4` |

## Tests

- `npm test` (Node `node --test src/test/js/*.test.js`): **366 passed, 0 failed, 0 skipped.**
  This is the fresh count for this increment (360 before the five behaviour regressions plus one VFR
  trail case).
- Real Chromium against the real page scripts with stubbed HTTP APIs
  (`build/sc2150-review-corrections-20261025/browser-corrections-probe.mjs`, results
  `browser-results.json`):
  - Assisted frame 126 shows **1** `.prediction-point`; after Blind is switched on the same frame
    shows **0** prediction points, **0** guess boxes, **0** trails and the prediction checkbox is
    unchecked and disabled.
  - Held-out source: the prediction checkbox is unchecked/disabled before review mode is entered;
    after entering review and selecting frame 126 there are **0** prediction points and the reason is
    `without-suggestions`.
  - Save race with a 700 ms delayed POST: two POSTs were sent; the final body carried the seeded
    label plus both labels added around the in-flight save (`ordinals [6, 123, 0]`, 2 hidden
    labels); closing review mode waited for the acknowledgement and the draft count remained 3.
  - Analysis seeding: analysis A seeded `x=111` although analysis B had the newer base revision
    (`x=999`); switching to B seeded `999`, switching back to A restored `111`.
  - Assisted → Blind save was refused with no POST and an explicit status; turning Blind off saved
    once.
  - No page errors were recorded.
- Not run in this increment: Java/Gradle, PostgreSQL, Python, real-backend review flow, held-out
  media scoring. The earlier 69-failure full-suite result remains historical, not a fresh count.

## Remaining work

1. Extend the browser harness to the real Spring/PostgreSQL backend (two analyses, 60 labels,
   lost responses/retries/conflicts, stale callbacks, undo/remove-to-empty, forced blind, long-source
   paging, review → immutable export → label conversion).
2. Server-enforced review sessions, corpus membership and suggestion-exposure provenance
   (CP25I8–CP25I11).
3. Complete bounded range queues and session-pinned exports.
4. Fresh independent held-out corpus/labels, detector improvement, and the single frozen FA12
   evaluation.
5. Constrained product YouTube acquisition and native/Windows qualification.
6. Regression reconciliation, PR #70 update, and only then Felix review/done gates and Shortcut
   synchronization.
