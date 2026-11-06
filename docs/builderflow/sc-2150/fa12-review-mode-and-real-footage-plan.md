# SC-2150 FA12 — review mode, detector fixes and real-footage qualification (full plan, 2026-10-24)

Written so an implementer with no prior context can execute it. Do the parts in order. Each part
ends with a **checkpoint**: stop, report the listed evidence, and wait before continuing.

## 0. Purpose and context (read first)

**Purpose of SC-2150.** Turn other players' OSRS videos (YouTube or uploads) into *human mouse
movement references*: where the cursor is on each frame, and therefore its path, speed, pauses and
clicks. The references are used to diagnose unnatural bot movement (Original NaturalMouse). The
human-versus-bot comparison itself is **SC-309** and out of scope here. Video-derived paths are
estimates, never native telemetry, and never training data.

**FA12** is the qualification that the extracted cursor path can be trusted. Its gates are median
error ≤ 4 px, p95 ≤ 12 px (1080p-equivalent), visible recall ≥ 99 %, absent false rate ≤ 1 % and cut
F1 ≥ 0.9, evaluated once on frozen methods against independently labelled held-out footage. See
`fa12-gate-amendment-20261023.md` and `fa12-real-footage-completion-plan.md`.

**Where things stand (verified 2026-10-24).**

| Area | State |
|---|---|
| Import, probe, frame index, proxy, exact-frame preview, prediction overlay, corrections, review revisions, export | Implemented (PR #70) |
| Detector `cursor-template/v5` (ZNCC, scale calibration, static-art rejection, hysteresis, backward gap fill) | Implemented |
| Synthetic dev split | recall 92.95 % (gate 99 %), median 0.80 px, p95 2.28 px, absent false 0.11 %, cut F1 0.857 |
| Live HTTP parity (29 arrow clips) | 0 mismatched frames |
| Real footage | **Unreliable.** 32–49 % "visible" on cursor-free b-roll (Theoatrix s1–s3). On Langsamt s3 (gameplay) the most confident guesses sit on static marks at the left screen edge while the real cursor is elsewhere (frames 375, 1989; overlays in `build/sc2150-live/overlay/`). |
| Labels on real footage | None saved |
| Review UX | Prediction is a small point; no trail, no confidence label, ~5 clicks plus scrolling per frame, no stepping by time, no autosave, no difficult-frame queue |

**Two rules that earlier advice got wrong. Keep them in force.**
1. A stationary cursor is valid. "Did not move" must never by itself mean background.
2. Labels every 0.6 s do not prove the path between them. Path checks need extra frames chosen
   *between* ticks, prioritised at jumps.

### Hard rules

- Repo: `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape` (Fedora), branch
  `codex/sc-2150-mouse-video-import`, PR #70 (base `wip/chromascape-checkpoint-20261007`).
- The working tree holds lots of uncommitted work from **other stories**. Never `checkout`, `reset`,
  `stash` or `clean`. Stage only files this plan touches. Commit only when the owner asks.
- Felix and Shortcut: no writes unless the owner asks (`task_1792382767281_cd8dbu69`, SC-2150).
- Never commit media, frames or screenshots of footage. Everything derived from video stays under
  gitignored `build/`. Evidence in `docs/` holds JSON, hashes and READMEs only.
- **Held-out is single-use.** Never run the detector on real held-out sections, and never tune with
  held-out results. Real-footage split (from
  `verification-runs/20261023T021614Z-fa12-real-footage/split.json`; re-read it before acting):

  | Split | Videos |
  |---|---|
  | dev | l7dv8lbS87E (Sleepy Longplays), U1uiLTv6quo (shaa rin), 33MkV_p4jvs (Langsamt), trm4e1ON4HM (EVScape; s2 cut-only) |
  | held-out | OqSngAyh1rY (Blizzard0199), xckk8Rft2lc (Kelzayeee), SNc0I1V0GXw (420slayerxpdrop), 9YFTgB78bDY (Theoatrix; all cut-only) |
 Never score the synthetic
  held-out split (`build/sc2150-fa12-v3/heldout`) before Part E. Theoatrix (9YFTgB78bDY) s1–s3 have
  already been observed once by a probe: do not use those observations for tuning.
- Downloaded media is untrusted: run Python that reads it with `python3 -I`, and keep scripts
  outside `build/sc2150-yt/`.
- Port 8090 belongs to the owner's sc664 checkout. Never stop it. Never run `./gradlew --stop`. Kill
  only PIDs you started, verified by `/proc/<pid>/cmdline`. Never `pkill -f` a pattern that can
  match your own shell.
- No gameplay. Never drive micro6 or the game client.

### Environment

```bash
cd /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape
export JAVA_HOME=$HOME/.jdks/jdk-21.0.12.1+1 PATH=$HOME/.jdks/jdk-21.0.12.1+1/bin:$PATH
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/build/tmp-java -Dapi.version=1.44"
G="--console=plain -Psc967VectorsPath=$(realpath ../contracts/sc967/ed25519-vectors.json)"
./gradlew $G classes                       # before restarting a server
./gradlew $G test --tests '<filter>'       # focused Java tests (full video package ~7 min)
node --test src/test/js/*.test.js          # JS
python3 -I -m unittest discover -s src/test/python
./gradlew $G spotlessApply -PspotlessIdeHook=<ABSOLUTE path>   # format one file; never repo-wide
```

Servers (disposable, loopback; launch only with `scripts/sc2150/start_verification.py`, ports
18190–18199, databases `sc2150_*`):

| Port | Database | Role | Launch extras |
|---|---|---|---|
| 18191 | `sc2150_library` | Library with the 24 imported YouTube sections, used for review | `--env CHROMASCAPE_MOUSE_VIDEO_MAX_JOB_DISK_BYTES=1073741824 --env CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_TEMPLATE=$PWD/build/sc2150-fa12-v3/templates/arrow.png --env CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_HOTSPOT_X=0 --env CHROMASCAPE_MOUSE_VIDEO_VISUAL_CURSOR_HOTSPOT_Y=0` |
| 18190 | `sc2150_fa12_live` | Scratch copy for live parity and probes | same extras |

```bash
python3 -I scripts/sc2150/start_verification.py --classpath build/tmp-java/sc2150-runtime.classpath \
  --java $JAVA_HOME/bin/java --output build/sc2150-library/server --database sc2150_library --port 18191 <extras>
```

The launcher copies `build/classes` into a snapshot, so run `./gradlew classes` and restart after
every Java change. The 1 GiB job disk is required: sampling 64 s of 1080p60 writes about 134 MB, and
the launcher's default is 128 MiB.

Key files:
- UI: `src/main/resources/templates/mouse-video.html`, `src/main/resources/static/js/mouse-video.js`
  (review state `review.*`, `loadObservations`, `renderOverlay`, `buildCorrection`,
  `addCorrection`, `saveReview`), tests in `src/test/js/mouse-video.test.js`.
- Detector: `.../video/service/MouseVideoCursorTracker.java` (static rejection and hold:
  `foreground(...)` around line 445, `track(...)` around line 343),
  `MouseVideoVisualAnalyzer.java` (forward and backward passes).
- Review contract: `MouseVideoCorrectionContract.java` (max 2000 corrections per revision),
  `MouseVideoReviewService.java`.
- Scripts: `scripts/sc2150/labels_from_review_export.py` (export → corpus labels; protocols
  `without-suggestions` and `detector-assisted`; held-out allows only `without-suggestions`),
  `qualify_live_cursor.py` (client helpers), `import_corpus_to_library.py`.
- Scorer: `docs/builderflow/sc-2150/verification-runs/20261023T163000Z-fa12-v5/tools/score.py`
  (sha256 `43e2c40a…c238`). Never edit it. Copy it into each new run's `tools/`.

**Revision semantics.** An export pins exactly one review revision, and the converter reads only that
revision's `corrections`. Review mode therefore saves **cumulative** revisions: each save carries
every label of the session so far (the previous revision's corrections plus the new ones), within
the 2000-correction limit.

---

## 1. The YouTube videos

Eight public OSRS videos from eight different channels, three 60-second sections each, for 24
sections (333 MB). Section start times were fixed at 20 %, 50 % and 80 % of each video's length
**before anyone watched the footage**, so no section was picked to flatter the detector.

- Downloaded with `yt-dlp` 2026.08.19, video only, capped at 1080p.
- Files: `build/sc2150-yt/raw/<videoId>--s<n>.<mp4|webm>` (gitignored).
- Facts: `build/sc2150-yt/manifest.json` (URL, window, SHA-256, codec, size).
- Screening: `verification-runs/20261023T021614Z-fa12-real-footage/screening.json`.
- Split: `split.json` in the same run.
- The split is by **channel**, decided before any label existed. A channel is never on both
  sides.

### 1.1 Dev videos (used to build, review and tune; detector boxes may be shown)

| Video | Channel | Type | Sections (start time in the video) | Format | Screening | Notes |
|---|---|---|---|---|---|---|
| [33MkV_p4jvs](https://www.youtube.com/watch?v=33MkV_p4jvs) | Langsamt | longplay | s1 1:49:52 · s2 4:34:40 · s3 7:19:28 | 1080p60 webm | full ×3 | Quest gameplay (Biohazard quest seen in s3). s3 is analysed in the library and shows the confident-wrong lock (Part A). **Review first.** |
| [l7dv8lbS87E](https://www.youtube.com/watch?v=l7dv8lbS87E) | Sleepy Longplays | longplay | s1 2:13:34 · s2 5:33:56 · s3 8:54:18 | 1920×1046, 60 fps | full ×3 | Not yet watched beyond the screening frames |
| [U1uiLTv6quo](https://www.youtube.com/watch?v=U1uiLTv6quo) | shaa rin | longplay | s1 1:28:09 · s2 3:40:22 · s3 5:52:36 | 1080p60 | full ×3 | Imported only after the frame-index budget fix |
| [trm4e1ON4HM](https://www.youtube.com/watch?v=trm4e1ON4HM) | EVScape | edited guide | s1 0:04:28 · s2 0:11:10 · s3 0:17:52 | 1080p60 | s1, s3 full · **s2 cut-only** | s1 settings/interface screens, s3 combat interfaces (hard cases). s2 is facecam plus title cards: the only dev source of cursor-free real footage and real edits |

Dev totals: 11 full sections and 1 cut-only section.

### 1.2 Held-out videos (final test only; Blind mode; never run the detector before labels exist)

| Video | Channel | Type | Sections | Format | Screening | Notes |
|---|---|---|---|---|---|---|
| [OqSngAyh1rY](https://www.youtube.com/watch?v=OqSngAyh1rY) | Blizzard0199 | longplay | s1 2:12:37 · s2 5:31:34 · s3 8:50:30 | 1080p60 webm | full ×3 | |
| [xckk8Rft2lc](https://www.youtube.com/watch?v=xckk8Rft2lc) | Kelzayeee | longplay | s1 1:49:08 · s2 4:32:50 · s3 7:16:32 | 1080p, **24 fps** | full ×3 | Low frame rate: cursor jumps further between frames |
| [SNc0I1V0GXw](https://www.youtube.com/watch?v=SNc0I1V0GXw) | 420slayerxpdrop | longplay | s1 0:12:01 · s2 0:30:04 · s3 0:48:07 | **1280×660**, 30 fps | full ×3 | Low resolution: smallest cursor |
| [9YFTgB78bDY](https://www.youtube.com/watch?v=9YFTgB78bDY) | Theoatrix OSRS | edited guide | s1 0:04:52 · s2 0:12:11 · s3 0:19:30 | 1080p30 | **cut-only ×3** | Title cards over b-roll, no client. Already probed once (32–49 % false sightings); not usable for tuning |

Held-out totals: 9 full sections (3 videos) and 3 cut-only sections.

### 1.3 Status of every section

| | Downloaded | In library (18191) | Detector analysed | Reviewed / labelled |
|---|---|---|---|---|
| Dev, 12 sections | ✅ | ✅ | Langsamt s3 only | none |
| Held-out, 12 sections | ✅ | ✅ (probe only) | **must stay no** until labelled | none |

The 18190 scratch database also holds probe analyses of 33MkV_p4jvs s1–s3 and 9YFTgB78bDY s1–s3
from the stopped presence probe (`20261024T031500Z-yt-cursor-presence`).

### 1.4 Review order and workload

1. Langsamt s3, then check the export → labels → score path end to end (Part C3).
2. Langsamt s1, s2; Sleepy Longplays s1–s3; shaa rin s1–s3.
3. EVScape s1, s3 (interface-heavy), then EVScape s2 (all no-cursor plus cut marks).

At about 140 queue items per section (Part B3), dev is about 1,600 items. At one key or click per
item, that is roughly 2–4 hours of owner time in total, spread across sessions.

### 1.5 Open decision: the held-out set is small (owner decision; `split.json` `decision: null`)

- The plan target was about 6,000 visible held-out labels over at least 6 videos. Today held-out
  has 3 playable videos, 9 sections.
- Label density matters too. Tick-grid labelling gives about 100 labels per section, so 9 sections
  give about 900 labels, not 5,400. That makes the 99 % recall estimate's confidence interval wide.

| Option | Effect |
|---|---|
| A. Add 2–3 **new** held-out longplay channels (new download under the FA13 acquisition rules; sections at 20/50/80 % again) | **Recommended.** More independent videos and channels; costs a download and more blind labelling |
| B. Take five sections instead of three from each held-out longplay | More labels, still only 3 videos |
| C. Move one dev longplay channel to held-out | 4 held-out videos but less dev data; breaks the 4/4 split |
| D. Accept the small set and report the wider CI | Cheapest; weakest claim |

Also decide the held-out label density: tick grid (about 100 per section), or denser blind
labelling (for example every 0.2 s, about 300 per section).

---

## 2. Everything in SC-2150: done and left

| # | Item | State | Where |
|---|---|---|---|
| 1 | Upload import, probe, frame index, proxy/thumbnail, durable idempotent jobs, owner-scoped catalog | ✅ done | PR #70 |
| 2 | Exact-frame preview, prediction overlay, append-only review, versioned exports | ✅ done | PR #70 |
| 3 | Replay catalog split: My recordings / From others | ✅ done | PR #70 |
| 4 | Frame-index budget fix (60 fps footage) | ✅ done | PR #70 |
| 5 | 24 YouTube sections imported into a local library | ✅ done (local, 18191) | §1 |
| 6 | Synthetic cursor corpus, harness, scorer; detector v1–v5 | ✅ done; recall 92.95 % < 99 % | PR #70 run records |
| 7 | Live HTTP parity | ✅ 0 mismatches (29 clips) | `20261023T213932Z-fa12-live` |
| 8 | Detector confident-wrong lock on real gameplay | ❌ open | **Part A** |
| 9 | Review mode (trail, quick keys, queue, autosave, Blind) | ❌ open | **Part B** |
| 10 | Dev sections analysed and reviewed; real-dev scores | ❌ open | **Part C** |
| 11 | Detector meets the gates on real dev | ❌ open | **Part D** |
| 12 | Held-out set decision (§1.5) | ❓ owner decision | §1.5 |
| 13 | Held-out blind labels plus single final scoring | ❌ open | **Part E** |
| 14 | YouTube URL import in the product (`youtubeEnabled=false` today) | ❌ open | Part F, `fa13-youtube-acquisition-proposal.md` |
| 15 | Native-capture labels from own micro6 sessions | ❌ open (code ready; needs new recordings) | Part F |
| 16 | Windows qualification checks | ❌ open | Part F |
| 17 | Owner decisions D3–D7 | ❓ owner decision | `fa12-real-footage-completion-plan.md` |
| 18 | Felix/Shortcut synchronisation | ⏸ skipped by owner request | — |
| 19 | PR #70 review and merge | ⏳ open | GitHub |
| — | Human-versus-NaturalMouse comparison | out of scope (SC-309) | — |
| — | Video-to-tick alignment | out of scope (SC-2151) | — |
| — | Training admission | out of scope (SC-712) | — |

Critical path: **A → B → C (owner reviews dev) → D → §1.5 decision → E**. Part F items can run in
parallel when the owner chooses.

---

## Part A — Diagnose and fix the confident-wrong lock (detector, no labels needed)

Goal: on real gameplay, the detector must stop reporting confident positions on static interface
marks. Keep stationary cursors valid.

**Hypothesis to test first (unverified).** The static-art rule keeps a placement that is unchanged
versus its ±1.5 s neighbours when it is "exactly where the cursor was detected in the previous sample
and unchanged since" (a stopped cursor). If a track ever starts on an interface mark, for example
when a panel change makes the mark briefly look new, this exception keeps it there indefinitely.

### A1. Expose why each sample was accepted

- Check whether `GET .../analyses/{id}/observations` returns the observation `evidence` map.
  The current pages omit it. If they do, add a bounded `evidence` passthrough for
  `CURSOR_VISIBILITY` items, limited to the keys `rule, continuation, pass, score, runnerUpScore,
  sourceScale, centreDelta, meanDelta, windowMaxDelta, forwardReason, missedSamples`. Keep it
  additive, update the API docs in `docs/mouse-video-import.md`, and add a controller/service test.
- Add a small diagnostic script `scripts/sc2150/explain_cursor_track.py` (stdlib, loopback-only). It
  pages a source's observations and prints, per sample, ordinal, visibility, x/y, confidence and the
  evidence keys. Optional flags `--from/--to` restrict ordinals. Output goes to stdout or a JSON
  under `build/`.

### A2. Reproduce on Langsamt s3

- Library source `3a5f38f0-aab8-4d8f-a591-4283ff97d273` (33MkV_p4jvs s3, dev). It already has a
  `visual/v1` analysis (`ac56a19a…`); re-run it after A1 so evidence is present.
- Explain ordinals around 330–420 and 1950–2000. Confirm or refute the hypothesis by finding the
  sample where the track *started* on the left-edge mark and the rule that accepted it, then which
  rule kept it.
- Record the findings in the run README (A5).

### A3. Fix, based on what A2 shows

Candidate mechanisms. Implement the smallest set that A2 justifies, and justify each in the commit
message.
1. **Local-change start.** A track may start only where the patch changed *and* a surrounding ring
   (for example 3× the sprite size) did not change wholesale. A cursor arriving changes a small
   area; a panel opening or scrolling changes everything around the mark.
2. **Hold needs prior motion.** The stopped-cursor hold applies only when the track reached that
   spot by moving: at least one earlier accepted sample of the same track more than `ARRIVAL_RADIUS`
   away. A track that never moved since it started may not use the hold. Stationary cursors stay
   valid because a real cursor arrives somewhere before it rests.
3. **Re-check long holds.** While holding, periodically (for example every 2 s of video) re-run the
   full-frame search. If an unheld foreground candidate scores at least as well as the hold, end the
   hold and continue from it.

Do not add a rule that treats "unchanged for a long time" as background on its own (rule 1 of §0).

Tests (unit, in `MouseVideoCursorTrackerTest`):
- A synthetic static mark that changes once (a panel flash) does not become a track.
- A cursor that moves in and then rests for 5 s stays VISIBLE throughout.
- A held static mark yields to a moving cursor elsewhere within the re-check period.

### A4. No-client gating (optional; only if A3 does not remove the b-roll false sightings)

When the frame shows no OSRS client view, report every cursor sample as `HIDDEN` with reason
`no client view`. Detection should use persistent client chrome (for example the minimap/compass
area or the inventory frame) correlated against the clip's own early frames. It must be measured on
**dev** cut-only material only: trm4e1ON4HM s2 (EVScape facecam and title cards) is the one dev
cut-only section. Add synthetic negatives (title-card frames rendered over dev b-roll) under
`build/` if more are needed. Never measure it on Theoatrix (held-out).

### A5. Measure

- Synthetic dev, no regression: 8 shards via the harness runner, frozen `score.py`. Recall must not
  drop, absent false rate ≤ 1 %, p95 ≤ 12 px. Compare with `20261023T163000Z-fa12-v5`.
- Live parity: rerun `qualify_live_cursor.py` on the 29 arrow dev clips against 18190, expecting 0
  mismatched frames.
- Real dev, qualitative: re-render the Langsamt s3 overlays (green box = guess, yellow = last-second
  path) at the same ordinals plus 10 random ordinals. Report how many boxes sit on the cursor by eye.
  Say clearly that this is not a labelled metric.
- New method id `cursor-template/v6`. Run record in
  `docs/builderflow/sc-2150/verification-runs/<UTC>-fa12-v6/` (tools, results, README; no media).

**Checkpoint A:** the hypothesis verdict with evidence, the mechanisms chosen and why, the synthetic
v5-versus-v6 table, the parity result, overlay observations, and the changed files.

---

## Part B — Review mode on the Mouse Video page

Goal: reviewing one minute of footage takes minutes, not an hour, and the labels are directly
usable by `labels_from_review_export.py`.

### B1. Layout ("Review mode" toggle inside the existing Frame review section)

- One large exact-frame preview at the top: the existing preview pipeline (`loadPreview`,
  `applyPreview`) with the existing SVG overlay, scaled to fill the width.
- Overlay contents:
  - **Detector guess:** a box around the hotspot, sized from the analysis scale, defaulting to
    24×32 source px, plus a label `VISIBLE 0.86` or `UNSURE`.
  - **Trail:** detector positions over the previous 1.0 s, as a polyline with fading opacity, plus
    small markers at sample points. Break the line across gaps longer than 0.3 s.
  - **Expected next position:** a faint dot extrapolated from the last two trail points.
  - **Your label:** a distinct marker, once set.
- A status strip shows `frame N · t=12.60 s · item 38/140 · reason: suspicious jump`, plus counts
  of labelled, accepted, corrected and no-cursor items.
- Hide the 200-row frame table while review mode is on. Frame loading must page automatically
  (`loadFrames`) up to whatever the queue needs.

### B2. Controls (keyboard first; every key also has a button)

| Key | Action |
|---|---|
| Enter | Accept the detector guess as the label (VISIBLE at the guess's x/y). Disabled when the guess is UNSURE |
| Click on frame | Label VISIBLE at the clicked source pixel (existing `imageCoordinates`) |
| H | Label HIDDEN (no cursor), reason `not visible` |
| A | Label AMBIGUOUS (excluded from scoring), reason `unsure` |
| S | Skip without labelling |
| ← / → | Previous / next queue item |
| Z | Undo the last label in this session |

Every label action adds a `CURSOR_VISIBILITY` correction through `buildCorrection`/`addCorrection`.
If the frame already has a draft label, replace it rather than duplicating. Then advance to the next
queue item automatically. Each correction's `note` records how it was made: `accepted`, `corrected`
or `queue:<reason>`.

### B3. The queue (which frames to review)

Build the queue client-side from the analysis observations and the frozen frame index:
1. **Tick grid:** the frame nearest each 0.6 s step by PTS (never a fixed frame count; 24 fps has
   14.4 frames per tick).
2. **Priority items**, which go first, deduplicated against the grid:
   - UNSURE samples;
   - suspicious jumps: displacement between consecutive VISIBLE samples > 25 % of frame width within
     0.1 s;
   - **confident-still tracks:** VISIBLE at nearly the same spot (≤ 2 px) for more than 3 s. This
     catches the Part A failure, because a real resting cursor passes review quickly;
   - **between-tick path checks:** for each priority jump, the frames halfway between it and its
     neighbouring ticks.
3. **Spot checks:** a seeded random 10 % of confident tick-grid items, shuffled in. Record the seed
   in the review reason.

The order is priority items, then the remaining tick grid in time order. Show each item's reason in
the status strip.

### B4. Saving

- Autosave every 25 labels and when review mode closes, via the existing `saveReview` request shape.
- **Cumulative:** on entering review mode, load the latest revision for this analysis
  (`loadHistory`) and seed the draft with its `CURSOR_VISIBILITY` corrections. Each save sends the
  full set. Guard the 2000 limit and say so in the UI if it is reached.
- Use the review reason `FA12 dev review · detector-assisted · seed=<n>` and source use
  `INSPECTION_ONLY`.
- Handle conflicts with the existing 409 flow. Never drop the draft.

### B5. Held-out (blind) mode

- A **Blind** toggle hides the detector guess, trail and expected-next dot, disables Enter and the
  priority queue, and uses the plain tick grid plus a seeded random 10 % between-tick frames.
- The review reason must then say `without-suggestions`. Blind mode is the only mode allowed for
  held-out sources. Add a guard: if the source's `context` contains `· heldout ·` (the importer's
  label), force Blind and lock the toggle.

### B6. Tests (`src/test/js/mouse-video.test.js`, existing style; pure helpers exported for tests)

- Tick grid by PTS for 60, 30 and 24 fps, including VFR timestamps.
- Queue: UNSURE first; jump detection; confident-still detection; between-tick insertion; seeded
  spot checks are deterministic; no duplicate ordinals.
- Label actions produce valid `buildCorrection` entries, replace rather than duplicate, and Undo
  works.
- Cumulative save body contains earlier and new labels; the 2000 limit is enforced.
- Blind mode hides predictions, disables Enter, and is forced for `· heldout ·` sources.
- Trail polyline breaks across gaps longer than 0.3 s.
- Run `node --test src/test/js/*.test.js`; all must pass. Then check the page with Playwright
  (installed: `node_modules/@playwright/test`), headless, read-only, and screenshot review mode on
  Langsamt s3 into `build/` (never `docs/`).

**Checkpoint B:** screenshots (paths only), test counts, a timing estimate (seconds per item when
driven by keyboard in Playwright), and the changed files.

---

## Part C — Analyse the dev sections and run the first review session

### C1. Analyse dev sections in the library

Write `scripts/sc2150/analyze_library_sections.py` (stdlib, loopback-only, `--only` filter,
idempotent: skip sources whose selected analysis is already the current method). It schedules
`visual/v1` on each **dev full** section in 18191 and waits for completion. Never touch held-out
sections: read `split.json` and refuse any source whose `context` contains `· heldout ·`. Print
one JSON line per section.

Dev full sections today: 33MkV_p4jvs s1–s3 (Langsamt), l7dv8lbS87E s1–s3 (Sleepy Longplays),
U1uiLTv6quo s1–s3 (shaa rin, 1080p60) and trm4e1ON4HM s1, s3 (EVScape, interface-heavy). That makes
11 sections. Confirm against `split.json` and `screening.json` before running.

### C2. Owner review session (the owner does this; you prepare it)

- Give the owner the exact URL of each section's Mouse Video page on 18191 and a five-line how-to
  (review mode, keys, autosave).
- Start with Langsamt s3. After it is reviewed, run C3 on it before the owner continues.

### C3. Export → labels → score (per reviewed section)

1. Create an export from the UI or `POST /sources/{id}/exports`, and fetch the manifest, the pinned
   revision and the frame-index pages.
2. Run `labels_from_review_export.py --labeller owner --labelling-protocol detector-assisted …`
   into `build/sc2150-real-labels/dev/`.
3. **Predictions for the same frames:** add `--existing-source <id> --analysis <id>` to
   `qualify_live_cursor.py`, or write a small sibling script. It must fetch observations for an
   already-imported source without uploading, and write a harness-format predictions file
   (`media_sha256`, `observations_sha256`, `cursor`, `step`, `sampled_pts_time`, …).
4. Score with the frozen `score.py --corpus build/sc2150-real-labels --split dev`.
5. Run record `verification-runs/<UTC>-fa12-real-dev/` (labels hashes, score JSON, README; no
   media, no frames).

Note: detector-assisted labels can over-agree with the detector. Report the spot-check accuracy
separately, from the 10 % random confident items, as the honest estimate of confident precision.

**Checkpoint C:** sections analysed, the first section's label counts (visible, hidden, ambiguous,
accepted, corrected), its real-dev scores and spot-check accuracy.

---

## Part D — Improve the detector on real dev labels

- Loop on dev only: diagnose misses and false sightings with `explain_cursor_track.py`, fix,
  re-score real dev and synthetic dev (no regression), and keep live parity.
- Likely work, in order of expected value:
  - motion-model search (search first near the position predicted from the trail);
  - higher-resolution matching near the predicted position (the 480 px analysis width shrinks a
    1080p cursor to a few pixels);
  - per-channel cursor templates when a channel uses a custom cursor (a template must come from
    **dev** footage of that channel);
  - Part A4 gating if needed.
- Bump the method id for each kept change and write a run record per iteration.
- Stop when real dev meets the FA12 gates with margin, or report the blocker.

**Checkpoint D:** per-iteration table (real dev and synthetic dev), what changed, and whether the
gates are met.

---

## Part E — Freeze and held-out verification (single use)

1. Freeze the method: record the method id, config digest, git commit and template hashes.
2. The owner labels held-out real sections in **Blind** mode (Part B5) with the protocol
   `without-suggestions`. Analyse held-out sections only *after* their labels are saved.
3. Score once: real held-out and synthetic held-out (`build/sc2150-fa12-v3/heldout`), with the
   frozen `score.py`. No reruns after seeing results; any change means a new held-out set.
4. Run record `verification-runs/<UTC>-fa12-heldout-final/`.

**Checkpoint E:** final gate table. FA12 passes only if every gate passes on held-out.

---

## Part F — Remaining SC-2150 items (after FA12, or in parallel by owner decision)

- YouTube acquisition per `fa13-youtube-acquisition-proposal.md` (provider path; currently
  `youtubeEnabled=false`).
- Native-capture labels from the owner's own micro6 sessions (`label_from_mouse_trace.py` plus the
  recording frame-timing sidecar). These are cheap exact labels for detector regression. New
  recordings are required: existing ones have no `.frames.jsonl`. They label the bot's cursor, not
  human movement.
- Windows qualification checks listed in `fedora-implementation-and-qualification-plan.md`.
- Owner decisions D3–D7 and the held-out top-up decision (`split.json`, `decision: null`).
- Out of scope: human-versus-NaturalMouse comparison (SC-309), video-to-tick alignment (SC-2151),
  training admission (SC-712).

---

## Acceptance criteria (Given / When / Then)

- **AC1.** Given Langsamt s3 analysed with the fixed method, when its overlays are rendered at
  ordinals 375 and 1989, then the detector does not report a confident position on the left-edge
  marks. If the cursor is not found, it reports UNSURE.
- **AC2.** Given a cursor that moves in and rests for 5 s, when tracked, then every resting sample
  stays VISIBLE.
- **AC3.** Given the synthetic dev split, when scored with the frozen scorer, then recall does not
  drop below v5, absent false rate ≤ 1 % and p95 ≤ 12 px; and live parity has 0 mismatched frames.
- **AC4.** Given review mode on a dev section, when the reviewer presses Enter, H, A or clicks, then
  one valid `CURSOR_VISIBILITY` correction exists for that frame and the view advances to the next
  queue item.
- **AC5.** Given 60 labels in a session, when the page autosaves twice and is reloaded, then the
  latest revision contains all 60 labels, and an export of it converts with
  `labels_from_review_export.py`.
- **AC6.** Given a source whose context marks it held-out, when review mode opens, then Blind mode
  is forced, predictions are hidden and Enter is disabled.
- **AC7.** Given the queue, when built for a section with confident-still tracks longer than 3 s,
  suspicious jumps and UNSURE samples, then all three appear before the plain tick grid, together
  with seeded random spot checks.
- **AC8.** Given reviewed dev labels, when scored, then the run record contains the label hashes,
  scores and spot-check accuracy, and no media.

## Test checklist

- [ ] Tracker unit tests (A3) and existing `MouseVideoCursorTrackerTest`
- [ ] Analyzer, derivative, import and review service tests touched by A1
- [ ] Synthetic dev harness run (8 shards), frozen scorer
- [ ] Live parity, 29 arrow dev clips
- [ ] JS suite including the new review-mode tests
- [ ] Python suite including new script tests (`explain_cursor_track.py`,
  `analyze_library_sections.py`, predictions-from-existing-source)
- [ ] Playwright read-only screenshots of review mode
- [ ] Spotless on touched Java files; Checkstyle has no new warnings on touched lines

## Risks

- Detector-assisted labels bias towards the detector. Mitigations: spot checks, Blind mode for
  held-out, and reporting spot-check accuracy separately.
- Cumulative revisions grow towards the 2000 limit on long sections. Sections are 60 s, about 140
  queue items, so this is well within the limit.
- Channels with custom cursors need their own templates, and a template must come from dev
  footage.
- Synthetic results do not predict real footage. Gate decisions use real labels only.

## Reporting (every checkpoint)

Verified versus unverified kept separate; exact commands and pass counts; files changed (note
`scripts/sc2150/*` needs `git add -f`); nothing committed unless asked; servers left running and
how to stop them.
