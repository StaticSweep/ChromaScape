# SC-2150 — Remaining work: full plan and implementation details (2026-10-31)

Project: `C:\Dev\IdeaProjects\ChromaScape`. Branch `codex/sc-2150-mouse-video-import`.
Felix task `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`, `change:ui`). Windows: SC-2204.

This document consolidates what is still open after the R28 verification (2026-10-29), the
independent R28 review (`review-r28-20261029.md`) and the 2026-10-31 staging fix. It supersedes
the status sections of `fedora-remaining-implementation-plan-20261026.md` (which stays the
reference for detailed contracts) and §2 of `fa12-review-mode-and-real-footage-plan.md`.

---

## 0. Where things stand (verified facts)

> **Status 2026-11-01 — `main` is the source of truth; this table is the 2026-10-31 snapshot.**
>
> Landed on `main`: #86 (SC-2150 code, migrations renumbered `V80`–`V86`), #87 (held-out expansion
> and the owner's blind labelling guide), #88 (post-merge verification), #89 (W13 queue coverage,
> provider outcomes, rollout/rollback checklist), #90 (W10 preparation: frozen scorer in-repo,
> recorded gate decision, corpus staging, runbook), #91 (2026-10-25 triage snapshot), #93
> (reproducible dev evidence + recorded gate decision), #94 (`/mouse/video` route — it had only
> ever existed as an uncommitted edit, so a clean build served 404), #95 (rollout/rollback
> rehearsal against the live 8090 datasource). PR #71 is **closed as superseded** (main carries
> `cursor-template/v8`); PR #70 holds nothing SC-2150 that `main` lacks and stays open only for
> other stories' commits.
>
> The 8090 deploy now has a verified precondition (`rollout-rehearsal-20261101.md`): the existing
> `chromascape` database is at Flyway V36 from the diverged `wip/chromascape-checkpoint-20261007`
> checkout and **cannot** accept `main`; it needs recreating from `main`'s migrations, with the
> three populated tables (`logs`, `task_recording_preferences`, `debug_sessions`) decided on.
> Rollback is a snapshot restore plus the previous build.
>
> Detector on dev (`cursor-template/v8`, `20261031T160000Z-fa12-v8-dev`): recall 96.57 %, absent
> false 0.0 %, median 0 px, p95 2.15 px, cut F1 pass — every gate passes under D-RECALL's 95 %
> recall, and recall alone fails under the scorer's frozen 99 %.
>
> Remaining: sealed blind held-out labels plus a frozen cursor-template set (W10, owner), the W12
> recording session (owner), and the 8090 deploy (owner's go-ahead). W11 (YouTube acquisition) is
> automated work that is blocked on the D3 decision; nothing else on the story is outstanding.

| Area | State | Evidence |
|---|---|---|
| Git | Local HEAD `e1eb17cd` = PR #70 head `16ecd839` + 1 unpushed commit; ~140 modified/added tracked files and ~425 untracked, mixed with other stories' WIP | `git status`; PR #70 OPEN, mergeable |
| Delivery bundle (R28 P1) | **Fixed 2026-10-31.** `mouse-video-review-core.js`, `test_sc2150_explain_cursor_track.py`, `test_sc2150_labels_from_review_export.py` staged; a clean `git checkout-index` extraction reproduces **41/41** candidate hashes (`build/sc2150-r28-candidate.json`). Only the SC-2150 Docker API hunk of `build.gradle.kts` is staged; the SC-2106 R0.5 and SC-663 hunks are left unstaged. Not committed. | this document |
| Review protocol (F1–F3 mechanics) | Implemented. 60-label real browser flow, seal, immutable export, v3 conversion, assembler + tamper refusals, access/Origin matrix, sealed read-only: passed on generated fixtures | `verification-evidence-r28-20261028.md` |
| Tests at R28 | JS 370/370, Python 95/95, Java video package 303 (302 pass, 1 opt-in skip), static checks pass | same |
| Full Java suite | **Red:** 2,940 tests, 66 failures, 16 skips; 0 in the video package; dispositions not per-failure | same; R28 review P2 |
| Detector | `cursor-template/v7`. Dev (spent) corpus: median 0.0 px, p95 2.15 px, cut F1 1.0, **recall 96.57 % (gate 99 %)**, **absent false 12.95 % (gate 1 %)** — gates fail | R28 evidence |
| Real corpus | 24 sections downloaded and imported (8 videos / 8 channels). Held-out: 3 full videos (9 sections) + Theoatrix cut-only; target ≥ 6 full videos, ~6,000 visible labels | `fa12-review-mode-and-real-footage-plan.md` §1 |
| Owner truth | **None.** Only synthetic `*.labels.json` exist (`build/sc2150-fa12-v3/dev`) | `find build -name '*.labels.json'` |
| Owner decisions | Held-out size (`split.json` `decision: null`), D3 acquisition pins, D4 native recording session, D5 cut-F1 gate, D6 unrelated failures — all open | `fa12-real-footage-completion-plan.md` |
| YouTube product route | `youtube-enabled=false`; no acquisition sandbox/egress proof | F8 |
| Native round trip | Code exists; no owner capture | F9 |

---

## 1. Critical path

```
W1 freeze/commit bundle ─┐
W2 Step-3 containment ───┼─► W4 regressions/dispositions ─► W5 candidate freeze ─┐
W3 Step-4 matrix ────────┘                                                         │
W6 detector (dev) ─► W7 owner dev review ─► W8 detector on real dev ──────────────┤
D-HO held-out decision ─► W9 held-out top-up + blind labels ─► W10 final eval ─────┤
D3 ─► W11 YouTube acquisition                                                      ├─► W14 delivery
D4 ─► W12 native round trip                                                        │
W13 telemetry/readiness/rollback ──────────────────────────────────────────────────┘
```

W1–W6 and W11/W13 preparation need no owner input. W7, W9, W12 need the owner's time.
W10 runs **once**, only after W8 and W9 are frozen.

---

## 2. Owner inputs and decisions (blocking items, ask once)

| ID | Decision / input | Recommended | Unblocks |
|---|---|---|---|
| D-HO | Held-out size: (A) add 2–3 new held-out longplay channels, (B) 5 sections per existing held-out video, (C) move a dev channel, (D) accept small set + wide CI. Also label density: tick grid (~100/section) or every 0.2 s (~300/section) | **A + 0.2 s density** | W9, W10 |
| D3 | YouTube acquisition: confirm yt-dlp 2026.08.19 pin, JS runtime, one public test clip, egress proxy provisioning | pin as recorded; owner names clip | W11 |
| D4 | When to do a micro6 developer-mode recording session (owner logs in and plays) | any time after W2 | W12 |
| D5 | If real cut F1 < 0.90 after W8: keep the gate or revise with evidence | keep the gate | W10 |
| D6 | The 66 unrelated full-suite failures: block delivery, or record as pre-existing with per-failure proof | record, with W4 proof | W4, W14 |
| OWN-1 | Dev review sessions (~1,600 queue items ≈ 2–4 h) | — | W7 |
| OWN-2 | Held-out blind labelling (9 + new sections) | — | W9 |

---

### Owner decisions recorded 2026-10-31

| ID | Decision |
|---|---|
| D-HO | **Option A + 0.2 s density:** add 2–3 new held-out longplay channels (sections fixed at 20/50/80 % before viewing), blind labels every 0.2 s (~300 per section). |
| D5 | **Keep the 0.90 cut-F1 gate**; a failed gate stays failed. |
| D6 | **Record, don't block:** the pre-existing/other-story full-suite failures (`full-suite-dispositions-20261031.md`) do not block SC-2150. |
| OWN-1/D4 | Owner review and recording sessions **soon**: prepare the dev review queue and the recording setup next. |
| D4 (W12) | **Record with a real dispatched task** (owner, 2026-10-31: "better to have a task"), so the trace carries generated bot paths; purchasing off, run stopped after the recording. |

Progress 2026-10-31: W1–W5 complete (see `verification-evidence-w2…`, `-w3…`, `-w5…`,
`full-suite-dispositions-20261031.md`). D3 (YouTube acquisition pins/clip/egress) is still open.

## 3. Work packages with implementation details

### W1 — Freeze and commit the delivery bundle (R28 P1 follow-through)

1. Review the staged set: `git diff --cached --stat`; confirm every staged path is SC-2150-owned
   (video packages, `mouse-video*` UI, `scripts/sc2150`, `src/test/python/test_sc2150_*`,
   `docs/builderflow/sc-2150`, V47/V48 migrations, the Docker hunk). Unstage anything else with
   `git restore --staged <path>`; never stage other stories' WIP wholesale.
2. Commit: `SC-2150: complete the R28 delivery bundle (review core, script tests, Docker opt-in)`.
3. Re-verify: `git checkout-index -a --prefix=<tmp>/` and compare all
   `build/sc2150-r28-candidate.json` hashes (script used on 2026-10-31; keep it as
   `scripts/sc2150/verify_candidate_extraction.py` with a Python test).
4. Push to `codex/sc-2150-mouse-video-import` so PR #70 matches the tested candidate.

Exit: PR #70 head reproduces the candidate manifest from a clean checkout.

### W2 — Step 3: containment/cleanup proof (R28 review P2 #2)

Problem: `/tmp/opencode/sc2150-complete/containment_probe.py` zips `systemctl show --value`
output with the requested property list; systemd does not keep that order and omits absent
properties, so MainPID/TasksCurrent were mislabelled. Root/process identity, control outcomes,
drain completion and deadlines were not captured.

Implementation:
1. Move the probe into the repo: `scripts/sc2150/containment_probe.py`.
   - Use `systemctl show <unit> -p ControlGroup,MainPID,MemoryCurrent,TasksCurrent,ActiveState,SubState,Result,ExecMainStartTimestampMonotonic`
     **without `--value`**; parse `key=value` lines into a dict. Absent keys are `None`, never shifted.
   - Read membership from `/sys/fs/cgroup/<ControlGroup>/cgroup.procs`; for each PID record
     `/proc/<pid>/stat` start time (field 22), `comm`, `cmdline`, `ppid`, and the session ID.
   - Record read failures distinctly (`READ_FAILED: <errno>`) from proven empty membership
     (`EMPTY`). Never treat an unreadable cgroup as empty.
   - Sample on a fixed interval with monotonic timestamps; emit JSONL under `build/`.
2. Correlate with server state: job ID, generation, lease, cleanup deadline, control-command
   outcome (`systemctl kill`/`stop` exit codes) and drain completion from the application log/DB.
3. Test `src/test/python/test_sc2150_containment_probe.py`: fixture `systemctl show` outputs in
   different orders and with missing keys; read-failure vs empty; PID start-time identity.
4. Live run (disposable instance, ports 18190–18199, disposable DB): ≥ 60 preview jobs plus
   fast-parent-exit, session-changing descendant, cancel mid-preview, shutdown during preview,
   JVM kill. Expect every owned scope `inactive/dead` with empty `cgroup.procs` before capacity
   release; any ambiguity stays `NEEDS_RECONCILIATION` (never forced success).
5. Fix any source defect found, with a deterministic regression in the runner tests.

Exit: per-job receipts proving root identity, descendant death, control outcome, drain and
deadline; previously failing frame-15 case cannot recur.

### W3 — Step 4: finish the browser acceptance matrix (R28 review P2 #1)

Map each required case to a test + receipt (`docs/builderflow/sc-2150/step4-case-map.md`), then
add what is missing. Cases still unproven, with the implementation for each:

| Case | Where | How |
|---|---|---|
| Delayed nonce / history / POST callbacks | `scripts/sc2150/qualify_review_protocol.mjs` | Playwright `page.route` with per-route promise gates; edit during each delay; assert one acknowledgement per edit lineage and no lost edits |
| Lost response + later edits | same | abort after server commit (already partial); then edit, save; assert exact-UUID retry first, later edits under a new UUID |
| Two sources / two analyses / two sessions | new `qualify_review_sessions.mjs` | open A, edit, switch to B mid-save; stale callbacks must not mutate B; reload both |
| Remove-to-empty | same | label N, remove all, save; reload shows 0 labels and revision advanced |
| 2,000 / 2,001 items and encoded-byte limit | same + `MouseVideoReviewSessionPostgresTest` | 2,000 accepted, 2,001 refused 413/400 with stable code; body at byte cap ±1 |
| Long-range paging + cancellation | same | source > 8,200 frames (generated); assert full contiguous coverage; cancel mid-page; changing cursor refused |
| Every sealed mutation path | `qualify_review_protocol.mjs` | keyboard, click/accept, H/A, undo, draft removal, manual form, save, direct API — all refused, no false "saved" notice |
| Prediction-bearing routes before seal | same | list each registered route; assert zero prediction bytes before seal |

Also fix the R28 UI gap: on reload of a sealed session the queue status overwrites the
"sealed and read-only" message (`mouse-video.js`): render the sealed state last / pin it in a
separate status region, with a JS test.

Exit: every case has a passing receipt on the exact candidate; generated labels only.

### W4 — Full-suite failure dispositions (R28 review P2 #3, D6)

1. Run the full suite on the candidate and on an appropriate baseline (main at PR #70's merge
   base) in **private snapshots** (no WIP wipe). Use JDK 21 launcher, Java 17 toolchain,
   `DOCKER_API_VERSION=1.44`, tmpdir on the repo disk.
2. Produce `docs/builderflow/sc-2150/full-suite-dispositions.md`: one row per failing test —
   failing on baseline too? (pre-existing) / only on candidate (regression → fix) / environment
   (name the prerequisite). No blanket attribution.
3. Known lead: `CdiStartupRegressionTest` fails from duplicate `objectMapper` beans in
   `BootstrapRecordingCoordinatorTest` / `BootstrapApprovalStorePostgresTest` test configs. Fix only
   if it is SC-2150-caused; otherwise record it.

Exit: every failure has a proven disposition; candidate-only failures are fixed.

### W5 — Freeze and rerun the candidate (Step 5)

Re-hash source/runtime/tools into a new `build/sc2150-r29-candidate.json`; rerun Java video
package, focused PG/migration tests, JS, Python, static checks, corpus harness (explicit, not
skipped), W2/W3 receipts. Record actual counts.

### W6 — Detector work on dev only (F5; FA12 Parts A/D)

Goal: pass dev gates — recall ≥ 99 %, absent false ≤ 1 %, p95 ≤ 12 px — without touching held-out.

1. Diagnostics: `explain_cursor_track.py` (exists) on Langsamt s3 ordinals 330–420, 1950–2000
   (hard negatives 375, 1989) and EVScape s1/s3 interface screens.
2. Candidate mechanisms (implement only those the diagnostics justify; each gets a test in
   `MouseVideoCursorTrackerTest`):
   - local-change track start (patch changed but surrounding ring did not change wholesale);
   - stopped-cursor hold requires prior motion of the same track;
   - periodic full-frame re-check during long holds;
   - no-client gating via persistent client chrome (minimap/inventory frame), measured on dev
     cut-only EVScape s2 only.
3. Every retained change → new method id (`cursor-template/v8`, …), new run record
   `verification-runs/<UTC>-fa12-v8/`; synthetic 8-shard harness must not regress; live parity on
   the 29 arrow dev clips must stay at 0 mismatches.
4. Dev library tooling: `scripts/sc2150/analyze_library_sections.py` — loopback-only, explicit
   source IDs, refuses held-out via server membership, validates original hashes, skips only an
   exact completed identity match, collects predictions from existing analyses (no re-upload).

Exit: dev gates pass on synthetic and (after W7) on real dev labels; parity 0 mismatches.

### W7 — Owner dev review sessions (OWN-1)

Prepare: analyse all 11 full dev sections + EVScape s2 with the W6 method; open Review mode in
order (Langsamt s3 first, then s1/s2, Sleepy Longplays, shaa rin, EVScape s1/s3, EVScape s2).
Owner reviews (~140 items/section). After each: seal → export → `labels_from_review_export.py` →
`assemble_label_set.py` → score. These are **dev** labels (tuning allowed).

### W8 — Detector on real dev labels (F5/F6; FA12 Part D)

Score W6 against W7 labels per source; iterate only on dev. Implement F6 declared observations
only where qualified (action indicators, pause boundaries, genuine cuts vs interface changes);
everything else stays explicitly UNSUPPORTED in the capability manifest. Freeze method + scorer.

### W9 — Held-out top-up and blind labels (F4; D-HO, OWN-2)

1. Per D-HO: acquire 2–3 new held-out longplay channels (sections at 20/50/80 %, chosen before
   viewing) under the recorded acquisition rules; append to the manifest; never move dev to
   held-out; record Theoatrix as cut-only/contaminated in the ledger.
2. Freeze inclusion/exclusion, rights/privacy, channel groups, ranges, 10 Hz (or chosen) ordinals.
3. Owner labels held-out in **Blind** mode (no detector run on held-out before labels exist).
4. Seal, export, convert (v3), assemble per source; hash label index, exports, frame indices.

Exit: frozen, source-disjoint truth corpus with counts of visible/absent/ambiguous/dropped.

### W10 — Single final evaluation (F7)

Freeze candidate code, method, templates, tools, scorer, truth hashes **before** scoring.
Run once. Gates: median ≤ 4 px, p95 ≤ 12 px (1080p-equivalent), visible recall ≥ 99 %, absent
false ≤ 1 %, cut F1 ≥ 0.90, declared methods precision ≥ 0.95 / recall ≥ 0.80, pause p95 within
two frame intervals, determinism (identical hashes), live/offline parity 0 mismatches.
Report per-source results and source-clustered CIs. A failed gate stays failed.

### W11 — YouTube product acquisition (F8; D3)

Files: `MouseVideoYouTubeAcquirer`, restricted runner, new acquisition sandbox + filtering proxy,
config/capabilities, tests.
1. Verify the yt-dlp 2026.08.19 signature and CLI flags; pin the JS runtime.
2. Sandbox: systemd scope + bwrap, private netns whose only route is a filtering CONNECT proxy
   (port 443, reviewed provider/CDN names, connect-time address checks incl. redirects/rebinding,
   deny loopback/private/CGNAT/link-local/metadata/ULA/multicast; time/byte/connection caps).
3. Server-owned argv only; no cookies/profiles/netrc/plugins; bounded workspace; verify pins
   right before launch; metadata first; accept only the expected complete media file.
4. Publish through the existing fenced/idempotent import registration; cancellation kills and
   verifies containment.
5. Tests: `/imports/youtube` success/retry, private/unavailable, pin drift, missing runtime,
   oversize/timeout, cancel, egress/filesystem/credential attacks (controlled fixtures).
Keep `youtube-enabled=false` until all pass.

### W12 — Native round trip (F9; D4)

Owner records a micro6 developer-mode session with the existing sidecar/trace paths enabled
while a short owner-approved task runs (D4: dispatched task; purchasing off; stopped afterwards). Convert with `label_from_mouse_trace.py`; verify source identity,
PTS/trace skew ≤ 50 ms, drops/duplicates, exact ordinals; classify generated vs human. Restore
settings. Also rerun the Fedora host boundary proof (memory + `MemorySwapMax=0`, CPU/PID/time/
output limits, hard job storage, network/secret denial, descendant termination, restart/sweep).

### W13 addendum (found 2026-10-31 on the dev library instance)

- **Fixed:** in-JVM analysis passes ignored cancellation. A job fenced to
  `NEEDS_RECONCILIATION` (its lease expired during 72 s of host thread starvation) kept
  analysing for ~12 min and held its job volume (`fuse2fs`) until it reached publication, where
  the fence correctly refused it. `MouseVideoVisualAnalyzer` now checks the job's cancellation
  before every sample of each full pass.
- **Done (was open):** no operator path resolved `NEEDS_RECONCILIATION`. The row keeps an admission slot
  forever; `reconcileExpiredClaims`/`reconcileOnStartup` only create such rows. W13 needs an
  owner-scoped "confirm cleanup" action that first proves the scope and volume are gone, then
  settles the row as failed/interrupted, with an event and an audit trail. Affected row on the
  library instance: job `6e505115` (Langsamt s1); the section was re-analysed under a new job.
  Implemented as `POST /jobs/{id}/reconcile` (`MouseVideoReconciliationService` +
  `MouseVideoCleanupProof`): refuses with 409 unless no worker, no mounted workspace of any
  generation and no unattributed `chromascape-video-*` scope exist and scope state is readable;
  then FAILED (error code kept) with a `RECONCILED` event carrying the proof.

### W13 — Telemetry, readiness, rollback (F10)

Low-cardinality metrics for session create/refuse/seal/abandon, exposure, retry/conflict,
queue coverage, export/converter refusals, provider/egress outcomes, containment
reconciliation (no actor/source/URL labels). Capability report: configured / available /
runnable / isolated / qualified. Rehearse rollout and rollback on a disposable clone (migration
first; never reverse V47/V48 destructively; old exports untouched).

### W14 — Delivery (F11)

Feature-only diff on PR #70 matching the final candidate; implementation and verification
evidence notes (`## Changed files / ## Tests / ## Remaining work`;
`## Acceptance results / ## Tests / ## Risks / ## Rollback`); Felix/Shortcut transitions only if
the owner re-enables them (currently skipped by owner request).

---

## 4. Acceptance criteria status

| ID | Criterion (short) | Status | Closed by |
|---|---|---|---|
| A1 | No predictions escape for unsealed held-out | Mechanics pass | W3 route list |
| A2 | Assisted/exposed lineage never certified Blind | Pass | — |
| A3 | Delayed/lost-response edits acknowledged once | Partial | W3 |
| A4 | Two contexts, stale responses isolated | Open | W3 |
| A5 | Trails/extrapolation respect cuts and PTS | Pass (JS) | W5 rerun |
| A6 | 60 labels + remove-to-empty survive reload/export | 60 pass; empty open | W3 |
| A7 | Legacy/V46 upgrade immutable | Pass | W5 rerun |
| A8 | Long sources, caps, malformed cursors | Partial | W3 |
| A9 | Auth/nondisclosure/atomicity/idempotency | Pass | W5 rerun |
| A10 | Sealed truth identities on conversion/scoring | Pass | — |
| A11 | Single frozen evaluation meets all gates | Open | W10 |
| A12 | Product YouTube route bounded and proven | Open | W11 |
| A13 | Owner native round trip | Open | W12 |
| A14 | Fedora resource/recovery + regressions | Partial | W2, W4, W12 |
| A15 | Delivery evidence and handoff | Open | W14 |

---

## 5. Effort and order (estimates)

| Order | Package | Who | Estimate |
|---|---|---|---|
| 1 | W1 bundle commit/push | agent | 0.5 h |
| 2 | W2 containment proof | agent | 0.5–1 day |
| 3 | W3 Step-4 matrix + sealed status fix | agent | 1–2 days |
| 4 | W4 full-suite dispositions | agent | 0.5–1 day |
| 5 | W6 detector (dev) | agent | 2–4 days |
| 6 | D-HO, D3, D4, D5, D6 | owner | minutes each |
| 7 | W7 dev review | owner | 2–4 h |
| 8 | W8 detector on real dev | agent | 1–3 days |
| 9 | W9 held-out top-up + blind labels | agent + owner | 1 day + 2–4 h owner |
| 10 | W11 YouTube acquisition | agent | 2–3 days |
| 11 | W12 native round trip | owner + agent | 1 h owner + 0.5 day |
| 12 | W5 freeze, W10 final eval, W13, W14 | agent | 1–2 days |

Heavy runs (W2–W5, W8, W10) need a responsive host: close idle agent sessions first; zram swap
was full on 2026-10-31.

## 6. Risks

- Detector may not reach 99 % recall / 1 % absent false on real footage; a failed W10 gate stays
  failed (new held-out needed to retry).
- Held-out set too small without D-HO option A → wide confidence intervals.
- Full suite stays red for reasons outside SC-2150 (D6).
- Shared working tree mixes several stories' WIP; every commit must be path-scoped.
- YouTube acquisition depends on provider behaviour; use controlled fixtures for faults.
