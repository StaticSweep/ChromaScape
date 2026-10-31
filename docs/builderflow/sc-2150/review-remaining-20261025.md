# SC-2150 remaining-work review — 2026-10-25 follow-up

Project: `C:\Dev\IdeaProjects\ChromaScape`.
This reviews the local A1/A2/review-mode increment after `audit-20261025.md`.
No application source, tracker state, Git index or branch was changed by this review.

## What is now implemented

- A1: the systemd execution scope sets `MemorySwapMax=0`. Independently compiled the current
  runner/properties/resource-test code against the retained audit dependencies and invoked all
  three resource test methods: effective limits, over-budget allocation kill, process-count cap
  and cleanup assertions passed. This was a targeted probe, not a new full Gradle suite.
- A2: imported replay now uses the explicit imported-media message and hides the tick loader;
  selecting native recordings restores recording wording. The current JavaScript suite, including
  both regressions, passes. A new real-backend browser run of this fix was not performed here.
- Review-mode controls, priority queue, trail, keyboard labels, cumulative autosave and Blind
  toggle exist. They should no longer be listed as wholly missing. The correctness issues below
  prevent treating this increment as ready for independent held-out labelling.

## Review findings

### R1 — P1: Blind mode still exposes predictions and can misstate labelling provenance

`mouse-video.js` lines 1034–1047 render the original prediction overlay whenever its checkbox
is checked, without consulting Blind. Only the newly added guess/trail block checks Blind.
Fresh browser reproduction with the real page scripts: Blind on, prediction checkbox checked,
one visible `.prediction-point`, reason `without-suggestions`.

A second browser reproduction accepted a detector guess, switched Blind on and saved. The
saved revision claimed `without-suggestions` while retaining `accepted · queue:suspicious jump`.
Hiding future suggestions does not make earlier assisted labels independent. Entering review
mode also loads/displays observations before the held-out Blind guard is applied.

Required: suppress every suggestion path before held-out display, preserve per-label/session
provenance, and refuse to relabel assisted drafts/history as unassisted. Test the original
prediction layer, forced-held-out entry and assisted-to-blind transitions.

### R2 — P1: In-flight save clears the dirty state of later labels

`saveReviewCumulative` unconditionally sets `review.dirty=false` and `labelsSinceSave=0`
after a successful response, even if the draft changed after the request body was serialized.
With a 700 ms response delay: label one frame, start save, label another frame, wait, close
review mode. The draft contains two labels, but only one POST containing one label was sent;
closing does not flush the second label. Refreshing can then lose that label.

Required: snapshot the submitted source/analysis/base/content revision, acknowledge only that
snapshot, retain later edits as dirty, serialize/queue saves and await the final flush before
closing or switching source/analysis. Cover failure/conflict and close-during-save cases.

### R3 — P1: Cumulative seed is not bound to the selected analysis

`enterReviewMode` passes source-wide history to `seedDraft`. `seedDraft` selects the latest
revision without checking `analysisId`. In a two-analysis fixture, analysis B's newer coordinates
are selected even when preparing analysis A's review. The analysis-change handler also leaves
the existing draft in place. Coordinates can therefore be resubmitted under a different
analysis identity while appearing cumulative.

Required: select the latest revision for the selected analysis, retain source/analysis identity
with every draft, and explicitly separate/reload drafts when switching analyses. Include two
analyses with different coordinates and revision ordering in integration tests.

### R4 — P2: Trail bridges cuts and short unknown spans

`buildTrail` discards non-visible samples and ignores `SCENE_CUT` observations, then breaks only
when the remaining visible samples are more than 0.3 s apart. A visible sample at t=0, a cut plus
AMBIGUOUS sample at t=0.1, and a distant visible sample at t=0.2 become one connected segment.
The expected-next dot then extrapolates that discontinuity.

Required: terminate segments at cuts and explicit hidden/ambiguous/unsupported spans and
extrapolate only within an uninterrupted segment. Test short cuts/gaps as well as long gaps.

## Verification performed in this review

- Fresh complete JavaScript run: **360 passed, 0 failed, 0 skipped**.
- Current resource code: all three resource test methods passed through the production runner.
- Real Chromium with the current page scripts and stubbed APIs reproduced R1 and R2; a second
  browser probe reproduced assisted-label provenance loss under R1.
- Direct current-core fixtures reproduced R3 and R4.
- Evidence: `C:\Dev\IdeaProjects\ChromaScape\build\sc2150-review-followup-20261025\` contains
  the logs, browser/core result JSON, probe scripts and reviewed-source hashes.
- No held-out media or real labels were used, changed or scored. No full Java/Python/real-backend
  review-flow rerun was performed in this follow-up. The prior 69-failure full-suite result is
  historical evidence for the prior snapshot, not a fresh count for this increment.

## Remaining completion work, in order

1. Correct R1–R4 and add behavior-level regressions; do not collect qualification labels through
   the current Blind mode.
2. Exercise the corrected review workflow against actual analysed media and PostgreSQL,
   including two analyses, slow saves, retries, conflicts, reload and immutable export/conversion.
   Rerun video/database/process coverage with Docker available; the latest implementation
   evidence reports 31 skipped cases and cannot close those gates.
3. Complete independently labelled real dev/held-out data and the corpus-size decision; tune on
   dev only, freeze the method, then score once against FA12's 99% recall and other thresholds.
   Qualify cuts and other visible observations separately; target-region remains unsupported.
4. Finish constrained product YouTube acquisition and its end-to-end success/refusal/egress/
   limits/cancellation evidence. Downloaded evaluation footage does not qualify the product API.
5. Complete a genuine micro6 capture-to-label session and supported-host/Windows qualification.
6. Reconcile broader regression failures, review the final change set, commit/push the local
   increment and update PR #70. Its fetched head is still `16ecd8393dea66a3bdbf7885c1acac7a6d4d1aa5`;
   the fixes and review mode reviewed here are local changes, not that PR head.
7. Reconcile pending evidence and stale Felix checklists/specification, then pass the actual
   review/done gates and synchronize Shortcut after successful Felix transitions.

Felix read-back: `task_1792382767281_cd8dbu69`, `in_progress`, `spec_state=draft`.
Shortcut SC-2150 remains In Progress. PR #70 remains open/unmerged. No external write or
synchronization was attempted in this review.
