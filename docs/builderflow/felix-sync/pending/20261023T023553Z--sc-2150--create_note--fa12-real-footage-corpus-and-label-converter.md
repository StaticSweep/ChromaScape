---
schema_version: 1
offline_record_id: 20261023T023553Z-sc2150-fa12-corpus-and-converter
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
checkout_path_this_host: /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape
shortcut_story_id: sc-2150
shortcut_tag: "shortcut:sc-2150"
felix_task_id: task_1792382767281_cd8dbu69
operation: create_note
target_note_id: null
title: "SC-2150 FA12 real-footage corpus (I1, I2) and review-export label converter (I3)"
note_type: implementation_evidence
stable_tags:
  - "shortcut:sc-2150"
  - "change:data"
parent_id: null
entities:
  - type: shortcut_story
    id: sc-2150
  - type: file
    path: C:\Dev\IdeaProjects\ChromaScape\scripts\sc2150\labels_from_review_export.py
  - type: file
    path: C:\Dev\IdeaProjects\ChromaScape\src\test\python\test_sc2150_labels_from_review_export.py
  - type: file
    path: C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\verification-runs\20261023T021614Z-fa12-real-footage\README.md
created_at: 2026-10-23T02:35:53Z
intended_felix_state: in_progress
intended_shortcut_transition: null
idempotency_marker: null
---

# SC-2150 FA12 real footage: corpus, screening, split and label converter

Felix could not be reached from this host's agent session (no Felix MCP transport available), so
this record holds the note that should be written when it is. Nothing here claims a Felix note or
checklist item was written, and no Shortcut comment was posted: the task stays `in_progress` with
no sync marker, and no transition to `in_review` is claimed.

## Task resolution

- Task: `task_1792382767281_cd8dbu69`, workflow `shortcut_delivery`, tag `shortcut:sc-2150`.
- Repository that owns the change: `C:\Dev\IdeaProjects\ChromaScape`.
- Classification: `change:data` (corpus, labels and evidence; no shipped behaviour changes; video
  import and YouTube import remain disabled).
- Owner decisions taken this session: **D1** agent runs the pinned downloader; **D2** owner labels
  the held-back clips.

## Note payload

### What was implemented

1. `scripts/sc2150/labels_from_review_export.py` (plan I3) converts one export manifest
   (`mouse-video-evidence/v1`), the review revision that export pins (`corrections/v1`) and the
   frozen frame-index pages into `sc2150-fa12-labels/v2` in the analysis output plane the harness
   predicts in. Provenance is enforced: the revision must be the pinned one, `--labeller` and
   `--labelling-protocol` are mandatory, a held-out clip may only declare `without-suggestions`
   (RF2), and export/review/corrections/frame-index digests are recorded. `VISIBLE` maps to
   `VISIBLE`, `HIDDEN` to `ABSENT`, `AMBIGUOUS`/`UNSUPPORTED` to `dropped`; unlabelled frames are
   absent from `labels` and therefore unscored. `SCENE_CUT` becomes `cut_compose_ordinals`; pause,
   indicator, target-region and camera-activity corrections are kept under `additional_spans` for
   W8.
2. Corpus (plan I1): 8 channels, 3 sections of 60 s each, 24 sections / 333 MB, fetched with the
   qualified yt-dlp 2026.08.19 (`58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a`)
   with `--ignore-config --no-cache-dir`, video-only, 1080p cap, into
   `build/sc2150-yt/raw/` (gitignored). Section times were fixed at 20/50/80 % of each video before
   any footage was watched. `manifest.json` records URL, window, file, SHA-256, size, container,
   codec and dimensions.
3. Screening and split (plan I2): `screening.json` gives every section a verdict with its montage
   tile and tile digest — 20 `full`, 4 `cut_only` (`9YFTgB78bDY` 1–3 title cards over b-roll,
   `trm4e1ON4HM` 2 facecam). `split.json` fixes 4 dev channels against 4 disjoint held-out channels
   before any label exists.

### Changed files

- `ChromaScape/scripts/sc2150/labels_from_review_export.py` (new)
- `ChromaScape/src/test/python/test_sc2150_labels_from_review_export.py` (new)
- `ChromaScape/docs/mouse-video-import.md` (new section: review export to FA12 corpus labels)
- `ChromaScape/docs/builderflow/sc-2150/fa12-real-footage-completion-plan.md` (I1, I2, I3, T2
  ticked; progress log)
- `ChromaScape/docs/builderflow/sc-2150/verification-runs/20261023T021614Z-fa12-real-footage/`
  (`README.md`, `manifest.json`, `screening.json`, `split.json`, `tools/fetch_sections.py`,
  `tools/screen_sections.py`)
- No Java source, configuration default or feature flag changed.

### Tests

- `python3 -m unittest discover -s src/test/python -p 'test_sc2150_labels*.py'` — 19 tests pass
  (18 converter cases plus one that scores a converted export with the frozen FA12 `score.py`).
- `python3 -m unittest discover -s src/test/python -p 'test_*.py'` — 57 tests pass.
- Consistency check over the run: 24 manifest sections = 24 screening verdicts, 4 + 4 channels with
  no channel on both sides, every `full` section assigned to its video's split.
- Not run: any Java/Gradle test (no Java change), Spotless/Checkstyle (no Java change), and the
  detector harness (blocked on labels).

### Remaining work

- I4 dev labelling, I5 held-out labelling (owner, per D2), I6 micro6 session (needs D4).
- I7–I13: human paths, detector v5 (colour, learned appearance, two-pass), cut features, freeze.
- **Open shortfall for the owner:** held-out has 9 full sections over 3 videos — 5,400 labels at
  10 Hz before the visible/absent split, short of the plan's "about 6,000 labels over at least 6
  videos". Options recorded in `split.json` with `decision: null`; extending the held-out longplays
  from three to five sections each is the cheapest fix.
- The review-export tool is under `scripts/sc2150/`, which `.gitignore` excludes via
  `/scripts/*/`; it needs `git add -f` when the owner asks for commits, as the other SC-2150
  scripts did.
- Windows (D7), FA13 (D3), cut-gate decision (D5) and the 70 unrelated failures (D6) remain open.

## Checklist reconciliation

- I1 Download the W1 sections and write the manifest → **checked**, evidence: run
  `20261023T021614Z-fa12-real-footage/manifest.json` (24 sections) and `build/sc2150-yt/raw/`.
- I2 Screening report and channel-level `split.json` → **checked**, evidence: `screening.json`
  (20 full / 4 cut-only) and `split.json` (4 + 4 disjoint channels).
- I3 `labels_from_review_export.py` converter with tests → **checked**, evidence: converter file
  and 19 passing tests.
- T2 Unit: review-export → labels converter → **checked**, evidence: same test module.
- Every other item stays unchecked; nothing was marked on the strength of a run that did not
  happen.

## State reconciliation

1. `in_progress` — already true; nothing to transition.
2. Do **not** transition to `in_review`: gates RF1–RF8 are unmet and labelling has not started.

## Shortcut reconciliation

- No workflow transition and no idempotency marker were posted. The story stays in its current
  state; `[felix-sync:task_1792382767281_cd8dbu69:planned]` and `:started]` would have to be
  evaluated against the existing comments before any new comment is written.
- Remaining blocker: the notes above reach Felix only when a Felix-capable session replays this
  record.

## Sync result

Pending.
