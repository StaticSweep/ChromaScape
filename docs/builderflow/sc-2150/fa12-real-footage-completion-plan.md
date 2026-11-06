# SC-2150 completion plan: real-footage cursor qualification (2026-10-23)

Felix task `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`), status `in_progress`. This plan
supersedes the "next directions" sections of the v2/v3/v4 run READMEs. It does not change any
shipped behaviour: video import and YouTube import stay disabled until the gates below pass.

## 1. Where we are

| Area | State |
|---|---|
| Upload, probe, jobs, review, export (Fedora) | Qualified in run `20261021T152511Z-fedora-completion` |
| Cursor detector | `cursor-template/v4`. Dev: recall 88.8%, median 0.80 px, p95 2.3 px, absent FP 0.04% |
| Cut detector | `scene-diff/v3`. Dev F1 0.857; an in-game interface pop is indistinguishable from a same-location edit by frame difference |
| Test footage | Synthetic composites only: real OSRS recordings with a drawn cursor on scripted paths. The owner judged the motion unrealistic |
| Held-out | v3 split (7 recordings, 56 clips) unspent |
| Native labels | micro6 Bot Mouse trace + recorder frame-timing sidecar + `/api/micro6/mouse-trace` built and unit-tested; never run together live |
| YouTube | Pinned yt-dlp 2026.08.19 downloaded and verified; media download blocked pending owner permission |
| Windows | Not run |
| Unrelated test failures | 70, owned by other work (SC-814/SC-2106 contracts, user WIP) |

## 2. Goal and gates

FA12 passes when a frozen method, evaluated **once** on held-out **real** footage, meets all of
the following:

| Gate | Threshold |
|---|---|
| Visible recall | **≥ 99%** (owner-raised from 90%, `fa12-gate-amendment-20261023.md`) |
| Median / p95 position error | ≤ 4 / ≤ 12 px, 1080p-equivalent |
| Absent-span false detections | ≤ 1% |
| Cut F1 | ≥ 0.90, one indexed frame tolerance |
| Determinism | identical observation hashes on rerun |
| Additional detector gates | actions, target regions, camera activity, pause timing: thresholds from the plan, measured on labelled spans |

Statistical note: 99% recall can only be claimed if the held-out set is large enough. With about
6,000 visible held-out frames spread over at least 6 videos, a 99.0% point estimate has a
clip-bootstrap lower bound near 98%. The report will state the point estimate and the CI, and the
gate is judged on the point estimate as before.

## 3. Scope and exclusions

**In scope:** real-footage corpus (YouTube plus native micro6 captures), labelling through the
existing SC-2150 review UI, detector v5, cut detection with real edits, the live verification
flow, the additional detector gates, FA13 acquisition, Windows qualification, and delivery
(commits, Felix, Shortcut).

**Excluded:** video-to-tick alignment (SC-2151), movement diagnosis (SC-309), training admission
(SC-712), gameplay automation of any kind (the owner runs the client and logs in), GPU or
network-hosted models, and changing shipped defaults before the gates pass.

## 4. Workstreams

### W1. Real-footage corpus from YouTube

1. **Download** (blocked; needs owner). Run the pinned, signature-verified
   `~/.local/share/sc2150-tools/yt-dlp-2026.08.19/yt-dlp_linux` with `--ignore-config
   --no-cache-dir`, at most 1080p, taking three 60 s sections per video. Candidates: 6 no-commentary
   longplays from different channels (`l7dv8lbS87E`, `OqSngAyh1rY`, `U1uiLTv6quo`,
   `33MkV_p4jvs`, `xckk8Rft2lc`, `SNc0I1V0GXw`) and 2 edited guides with real cuts
   (`9YFTgB78bDY`, `trm4e1ON4HM`). Media goes to `build/sc2150-yt/raw/` (gitignored); a manifest
   records the URL, section, SHA-256, format and yt-dlp version.
2. **Screen** each section for a visible cursor at least 50% of the time and for an OSRS client
   view. Rejected sections are listed, not silently dropped.
3. **Split by channel** before anyone looks at labels or tunes anything: dev 4 channels,
   held-out 4 channels, with a fixed assignment recorded in `split.json`. A channel never appears
   on both sides.
4. **Cursor templates:** every distinct cursor style found (default OSRS gold, RuneLite custom
   cursors, OS arrow) is cut from dev footage only and reviewed by the owner.

### W2. Labelling with the SC-2150 review UI

1. Import each section through the real upload route on a disposable loopback instance.
2. **Dev clips:** run `visual/v1`, then correct its suggestions in the review UI. Corrections use
   `corrections/v1` kinds: `CURSOR_VISIBILITY` (VISIBLE/HIDDEN/AMBIGUOUS with x,y), `SCENE_CUT`,
   `PAUSE`, `ACTION_INDICATOR`, `TARGET_REGION`, `CAMERA_ACTIVITY`.
3. **Held-out clips:** label **without** detector suggestions, so the evaluation does not measure
   agreement with the detector being evaluated. Label every 3rd frame (10 Hz), at most 2,000
   corrections per revision. Unlabelled frames between labels are excluded from scoring, never
   interpolated into the score.
4. **Who labels** (owner decision D2): option A, the owner labels held-out and Claude labels dev;
   option B, Claude labels all, every label marked `labeller: claude`, with an owner spot-check of a
   random 5% sample per clip and an agreement target of ≥ 95% within 4 px.
5. Export an immutable snapshot per clip. `scripts/sc2150/labels_from_review_export.py` (new)
   converts it into corpus labels (`sc2150-fa12-labels/v2`) and records the export ID and checksum.

### W3. Native labels from micro6

1. The owner starts micro6 in developer mode (Bot Mouse on by default) and starts a ChromaScape
   recording. micro6 drives the mouse and the owner plays; Claude does not run gameplay.
2. The recorder writes `<id>.frames.jsonl`. micro6 posts the trace to `/api/micro6/mouse-trace`,
   which stores `<id>.mouse-trace.jsonl`. The debug recorder separately keeps sending
   `MOUSE_TRAJECTORY`.
3. `label_from_mouse_trace.py` aligns them and reports clock skew, dropped frames and counts.
4. Use: a live end-to-end check of the capture path with exact truth (I5), and extra dev data for
   detector training. Because the crosshair rotates and changes colour, these clips are a separate
   template class and do not count toward human-cursor gates.

### W4. Realistic synthetic stress set

Drive the synthetic corpus builder's cursor paths from real human paths instead of minimum-jerk
scripts:
- `MOUSE_TRAJECTORY` events with `source: human_observed` from owner debug sessions;
- dev-split YouTube label tracks.

The synthetic set stays a stress and regression suite. It is not used for FA12 qualification.

### W5. Detector v5

Ordered by expected gain; each step is measured on dev only.
1. **Colour input:** sample `rgb24` instead of gray and correlate over all three channels. Thin
   coloured cursors (dagger, gold) gain the most.
2. **Learned appearance:**
   - after calibration, average the aligned patches from confident detections across the whole
     video to form a per-video template;
   - rerun tracking with that template, which absorbs scaling, encoder blur and the OS cursor
     style.
3. **Two-pass tracking:**
   - run forward and backward passes and merge them;
   - accept continuations from the other pass's track;
   - recovers "below start threshold" misses at track starts.
4. **Small learned verifier (only if 1–3 fall short):**
   - a CPU-only classifier on candidate patches, trained on dev labels from W2 and W3 plus synthetic data;
   - deterministic inference with weights pinned by SHA-256;
   - rejects look-alikes so thresholds can drop.
5. Freeze: method ID, thresholds, weights and template hashes recorded in `freeze.json` before
   held-out scoring.

### W6. Cuts

1. Use real edits from the guide videos (W1) as positives, and in-game interface changes (bank,
   world map, login) as hard negatives.
2. Add features that separate them:
   - **Spatial spread:** an edit changes regions all over the frame, while an interface pop is
     local.
   - **Persistent-UI change:** the minimap, chat or orbs jump at an edit and stay put when an
     interface opens.
   - **Post-change continuity:** after an edit, the next frames follow the new scene.
3. If dev F1 stays below 0.90 on real edits, bring the evidence to the owner instead of relaxing
   the gate unilaterally (decision D5).

### W7. Live verification flow

`scripts/sc2150/qualify_live_cursor.py` against a disposable instance (`start_verification.py
--env`, DB `sc2150_fa12_live`, never port 8090):
- upload → ingest → `visual/v1` → published observations, paged over HTTP;
- scored with the frozen scorer;
- per-frame parity with the offline harness (0 mismatches required).

It runs at three points: on dev during development, once with the frozen method before held-out
scoring, and on Windows.

### W8. Additional detector gates

Score actions, target regions, camera activity and pause timing against the W2 labels on
held-out clips, using the thresholds in the plan. If a detector has no labels it stays
"unmeasured", which never counts as a pass.

### W9. FA13 YouTube acquisition

1. The owner confirms the pin (`yt-dlp_linux` 2026.08.19, `58162f9b…230a`), whether a JS runtime is
   allowed if needed, a test clip the owner uploaded, and egress.
2. Run the FA13 CLI checks from the proposal against that clip, with proxy-only egress through the
   network namespace.
3. Qualify the `/imports/youtube` route end to end on the disposable instance. `youtube-enabled`
   stays false in shipped config.

### W10. Windows qualification

On the owner's Windows host:
- the existing Fedora live suite;
- the recorder frame-timing sidecar;
- micro6 trace delivery and the trace-label clock cross-check (must stay ≤ 50 ms);
- W7.

### W11. Delivery

1. **Commits:** separate commits per repository (ChromaScape, micro6) containing SC-2150 files only;
   unrelated user work-in-progress is left untouched. Commit only when the owner asks.
2. **The 70 unrelated failures:** the owner decides (D6) whether they block SC-2150 or are
   recorded as pre-existing with evidence.
3. **Felix:** an Implementation Evidence note (`## Changed files`, `## Tests`, `## Remaining work`),
   then `in_review`; a Verification Evidence note (`## Acceptance results`, `## Tests`, `## Risks`,
   `## Rollback`), then `done`.
4. **Shortcut:** sync the mapped states with `[felix-sync:task_1792382767281_cd8dbu69:review]` and
   `:done`, posted only after the Felix transition succeeds.

## 5. Acceptance criteria (Given/When/Then)

- **RF1:** Given YouTube sections split by channel before labelling, when the held-out split is
  scored once with a frozen method, then visible recall is ≥ 99%, median ≤ 4 px, p95 ≤ 12 px and
  absent false detections ≤ 1%, reported with clip-bootstrap CIs.
- **RF2:** Given held-out labels made without detector suggestions, when the labelling provenance
  is audited, then every held-out label names its labeller and review export, and no held-out
  label came from detector output.
- **RF3:** Given real edits and in-game interface changes in held-out footage, when cuts are
  scored, then F1 is ≥ 0.90.
- **RF4:** Given the frozen method, when the live verification flow runs the held-out clips
  through the real upload and analysis routes, then its scores equal the harness scores and
  per-frame parity has 0 mismatches.
- **RF5:** Given a micro6 developer-mode session recorded by ChromaScape, when the trace and frame
  sidecar are aligned, then clock skew is ≤ 50 ms, every frame is labelled or explicitly dropped,
  and the trace file sits beside the recording.
- **RF6:** Given labelled action, region, camera and pause spans, when the additional detector
  gates are scored, then each meets its plan threshold or is reported as unmet. "Unmeasured" never
  counts as a pass.
- **RF7:** Given the same inputs, configuration and templates, when analysis runs twice, then
  observation hashes are identical.
- **RF8:** Given a Windows host, when the live suite, recorder sidecar and micro6 trace delivery
  run, then they pass with the same results as Fedora.

## 6. Implementation checklist

- [x] I1 Download the W1 sections and write the manifest (2026-10-23: 24 sections, 333 MB in
  `build/sc2150-yt/raw/`, `manifest.json` in run `20261023T021614Z-fa12-real-footage`)
- [x] I2 Screening report and channel-level `split.json`, written before labelling (2026-10-23: 20
  full / 4 cut-only sections; cursor fraction deliberately unmeasured until a template exists)
- [x] I3 `labels_from_review_export.py`, review-export → corpus labels, with tests (2026-10-23:
  implemented; provenance refusals included; see §12)
- [ ] I4 Label the dev clips (review UI, detector-assisted)
- [ ] I5 Label the held-out clips (review UI, no suggestions; labeller per D2)
- [ ] I6 Run one live micro6 session; check the micro6 → ChromaScape trace and labels end to end
- [ ] I7 Corpus builder takes human paths (W4)
- [ ] I8 v5 colour input
- [ ] I9 v5 learned per-video appearance
- [ ] I10 v5 two-pass tracking
- [ ] I11 v5 learned verifier (only if needed)
- [ ] I12 Cut features (spread, persistent UI, continuity) on real edits
- [ ] I13 Freeze record for v5
- [ ] I14 FA13 CLI checks plus a proxy-only acquisition run (blocked on D3)
- [ ] I15 Docs: `mouse-video-import.md` method and defaults, and a run README per run

## 7. Test checklist

- [ ] T1 Unit: tracker v5 golden tests (colour, learned appearance, two-pass), deterministic
- [x] T2 Unit: review-export → labels converter (provenance, exclusion of unlabelled frames).
  19 tests pass: 18 converter cases plus one that scores a converted export with the frozen
  `score.py`
- [ ] T3 Unit: micro6 trace logger and sender (done: 2/2 and 3/3); ChromaScape trace endpoint
  (done: 5/5); recorder frame timing (done: 3/3, plus 40/40 existing)
- [ ] T4 Integration: video package suite with real PostgreSQL (`-Dapi.version=1.44`)
- [ ] T5 Harness: dev runs per change; one frozen held-out run
- [ ] T6 Live: W7 on dev, frozen pre-held-out, and Windows
- [ ] T7 Live: one micro6 session through capture, trace, labels and score
- [ ] T8 Windows: W10
- [ ] T9 Spotless and Checkstyle clean on SC-2150 files

## 8. Owner decisions needed

| ID | Decision | Blocks |
|---|---|---|
| D1 | Allow the pinned yt-dlp to download, or run the download yourself | W1–W2, everything downstream |
| D2 | Who labels held-out: you (A), or Claude with your 5% spot-check (B) | W2, RF2 |
| D3 | FA13: confirm the pin, JS runtime, test clip, egress | W9 |
| D4 | When to run a micro6 developer-mode recording session | W3, I6 |
| D5 | If real-edit cut F1 stays under 0.90: keep the gate, or revise it with evidence | W6 |
| D6 | The 70 unrelated failures: block, or record as pre-existing | W11 |
| D7 | Windows host access | W10 |

## 9. Order of work

1. D1 → I1, I2 (corpus and split), I3 (converter)
2. I4 dev labels → I8–I10 detector work, measured on dev, with I6 and I7 in parallel
3. I12 cuts on dev → I11 only if recall is still short
4. I5 held-out labels → I13 freeze → T5 held-out once, plus T6 frozen live run
5. W8 additional gates → W9 FA13 → W10 Windows → W11 delivery

## 10. Risks

- **99% may not be reachable on real compressed footage**, especially for thin cursors at
  480 px. Mitigation: colour, a learned appearance and a verifier first; an analysis width above
  480 is allowed only if it is measured as both better and fast enough. If it is still short, the
  owner sees the evidence before any gate change.
- **Label bias:** held-out labels made from detector suggestions would inflate scores. Mitigation:
  RF2.
- **Small held-out sets:** per-channel clustering widens the CI. Mitigation: at least 4 held-out
  channels and about 6,000 visible frames.
- **YouTube terms of service and privacy:** sections are kept local, never committed, and used for
  evaluation only; channel IDs are recorded for provenance.
- **Clock alignment on Windows** is unverified. Mitigation: the header cross-check aborts rather
  than mislabel.
- **micro6 crosshair realism:** it is not a human cursor, so it is excluded from human-cursor gates.

## 11. Rollback

Every change sits behind disabled feature flags or produces new files. To roll back:
- set `recording.frame-timing-sidecar=false`;
- run micro6 with `-Dmicro6.mouseTrace=false`;
- leave the trace endpoint idle; it stores nothing outside an active recording;
- turn Bot Mouse off in Developer Tools.

Detector versions are recorded in every analysis identity, so older analyses stay reproducible.

## 12. Progress log

**2026-10-23 — I3 and T2 delivered (no footage needed).**

- `scripts/sc2150/labels_from_review_export.py` converts one export manifest, the review revision
  the export pins, and the frozen frame-index pages into `sc2150-fa12-labels/v2` in the analysis
  output plane the harness predicts in. Mapping and refusal rules are documented in
  `docs/mouse-video-import.md` under "Review export to FA12 corpus labels".
- Provenance is enforced rather than recorded on trust: the revision must be the pinned one,
  `--labeller` and `--labelling-protocol` are mandatory, a held-out clip may only declare
  `without-suggestions`, and export/manifest/revision/corrections/frame-index digests are stored.
- `src/test/python/test_sc2150_labels_from_review_export.py` runs 19 tests, including one that
  feeds a converted export to the frozen FA12 `score.py` and checks recall, `px_to_1080p` error,
  absent false rate, unmatched samples and cut F1. Full Python suite: 56 tests pass.
- Still blocked: I4 onward needs the review instance and labelling, and I6 needs D4. Detector v5
  (I8–I10) is deliberately not started before dev labels exist, because each step must be measured
  on dev.

**2026-10-23 — I1 and I2 delivered (run `20261023T021614Z-fa12-real-footage`).**

- D1 resolved as "agent runs the pinned downloader"; D2 resolved as "owner labels held-back".
- 8 channels x 3 sections x 60 s = 24 sections, 333 MB, downloaded with the qualified yt-dlp
  (`58162f9b…230a`), video-only, 1080p cap, into the gitignored `build/sc2150-yt/raw/`. Section
  times were fixed at 20/50/80 % of each video before any footage was watched.
- Screening: 20 sections are a playable OSRS client view with a visible cursor; 4 are `cut_only`
  (Theoatrix `9YFTgB78bDY` 1-3 title cards over b-roll, EVScape `trm4e1ON4HM` 2 facecam). Every
  verdict, its montage tile and the tile digest are in `screening.json`; nothing was dropped
  silently.
- `split.json` fixes 4 dev channels against 4 held-out channels before any label exists, and the
  two sides never share a channel.
- **Shortfall to decide:** held-out has 9 full sections over 3 videos (5,400 labels at 10 Hz before
  the visible/absent split), short of the plan's "about 6,000 labels over at least 6 videos",
  because the held-out guide screens as `cut_only`. Three options are recorded in `split.json` with
  `decision: null`; extending the held-out longplays from three to five sections each is the
  cheapest way to close it.

**2026-10-23 — catalog split, import script, detector v5 (run `20261023T163000Z-fa12-v5`).**

- Replay page: "My recordings" / "From others (YouTube / uploads)" tabs backed by the video
  library; no external clip enters the recordings table. JS suite 348/348.
- `scripts/sc2150/import_corpus_to_library.py` (10 tests; Python suite 67/67). Dry run plans all 24
  sections. **Not yet run against a server**: 8090 runs the `ChromaScape-sc664` checkout, whose
  database migrations conflict with this checkout, so the import needs this checkout on its own
  port and database.
- Detector v5 (two-pass, I10) on the synthetic dev split with the frozen v4 `score.py`: recall
  88.8 % → 92.95 % (clip-bootstrap CI 88.9–96.4), median 0.80 px and p95 2.28 px unchanged,
  absent false rate 0.04 % → 0.11 % (gate passes), cut F1 0.857 unchanged. Recall gate (99 %) still
  fails; held-out unspent.
- Cuts (I12): dev errors are 2 misses and 0 false cuts, so stricter cut features cannot raise F1,
  and the earlier probe showed the missed same-location edits overlap a bank interface opening on
  frame-difference features. Not changed; needs labelled real edits (W6) first.


**2026-10-23 — corpus imported into the library; live verification (run `20261023T213932Z-fa12-live`).**

- Library: this checkout on 127.0.0.1:18191 with its own database `sc2150_library`. The importer
  uploaded all 24 sections (24 SUCCEEDED, receipts in
  `20261023T021614Z-fa12-real-footage/import-receipts.json`). All 24 originals are qualified for
  playback (18 mp4, 6 webm), 0 of 24 analyses are training-eligible, and the recordings table
  stayed at 0 rows before and after.
- The first import pass timed out on every 60 fps section (`WORKER_TIMEOUT` at PUBLISH): the
  frame-index ffprobe runs under `probeBudgetSeconds` (default 30 s) and needs ~41 s for a 60 s
  1080p60 section. Re-run with `CHROMASCAPE_MOUSE_VIDEO_PROBE_BUDGET_SECONDS=180` on a fresh
  database. Fixed on 2026-10-24: the frame index now runs under `analysisBudgetSeconds` like the
  other full-decode steps (`MouseVideoDerivativeWorkerTest.frameIndexRunsUnderTheAnalysisBudgetNotTheProbeBudget`).
- Live check (disposable copy on 18190, v5, arrow template): 29 dev clips, 8243 frames, 0 mismatched,
  0 missing, cuts equal on all 29. Live and harness score reports are identical. Arrow subset: recall
  0.966, median 0.76 px, p95 1.70 px, absent false rate 0, cut F1 1.0. The recall gate still fails.
- `qualify_live_cursor.py` now writes `media_sha256` and `observations_sha256`, which `score.py`
  needs.

**2026-10-24 — frame-index budget fix; cursor-presence probe on real footage (run `20261024T031500Z-yt-cursor-presence`).**

- The frame index now runs under `analysisBudgetSeconds` (commit 51d243a7). 60 fps sections import
  with default budgets.
- Running v5 (arrow) over real sections reports a visible cursor on 32–49 % of samples in the
  cursor-free Theoatrix b-roll sections. On real footage the false-sighting rate is far above the
  synthetic 0.11 %, and gameplay sections come out mostly AMBIGUOUS. Synthetic dev results do not
  predict real-footage behaviour. Real labels (W6) are now the gating item, including labelled
  cursor-absent footage for the absent false rate. The probe ran on three held-out cut-only
  sections; nothing was scored, and those observations must not be used for tuning.
