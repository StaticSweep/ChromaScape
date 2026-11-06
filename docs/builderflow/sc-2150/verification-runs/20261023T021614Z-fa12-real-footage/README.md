# FA12 real-footage corpus run — 2026-10-23

Felix task `task_1792382767281_cd8dbu69` (`shortcut:sc-2150`). This run covers plan items **I1**
(download and manifest) and **I2** (screening and channel-level `split.json`) of
`../../fa12-real-footage-completion-plan.md`. It produces no labels and changes no shipped
behaviour: video import and YouTube import stay disabled.

## What was fetched

- Downloader: the qualified `~/.local/share/sc2150-tools/yt-dlp-2026.08.19/yt-dlp_linux`,
  SHA-256 `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a`, run with
  `--ignore-config --no-cache-dir`, video-only, capped at 1080p.
- All eight candidate videos, three 60-second sections each = **24 sections, 333 MB**, all
  kept on this machine under `build/sc2150-yt/raw/` (gitignored, never committed).
- Section times were fixed **before any footage was watched**: 20 %, 50 % and 80 % of each video's
  duration, so a section cannot be picked to flatter the detector. `manifest.json` records the URL,
  the requested window, the downloaded file, its SHA-256, size, container, codec and dimensions.
- Two sections start up to 4 s before the requested window because the cut lands on a keyframe;
  their true duration is recorded (`63.999 s` for `33MkV_p4jvs`, `62.3 s` for `OqSngAyh1rY`).

Reproduce: `tools/fetch_sections.py --out build/sc2150-yt/raw --manifest build/sc2150-yt/manifest.json`
(resumable: completed sections are skipped and the manifest is written after each one).

## Screening

`tools/screen_sections.py` extracted one midpoint frame per section at 960 px and built one montage
per video; the two edited guides additionally got a 4-frames-per-minute strip, because a single
frame cannot tell a title card from gameplay. Verdicts and the montage tile they come from are in
`screening.json`.

| Verdict | Sections | Meaning |
|---|---|---|
| `full` | 20 | playable OSRS client view with the cursor visible in the sampled frames |
| `cut_only` | 4 | real edits (title cards, facecam, b-roll) but no playable client view, so it can only carry cut labels |

`cut_only` sections, all listed rather than dropped: `9YFTgB78bDY` sections 1–3 (Theoatrix guide:
title cards over b-roll, no chatbox or minimap) and `trm4e1ON4HM` section 2 (EVScape facecam).

**Cursor fraction is not measured.** The plan's screen asks for a cursor visible at least 50 % of
the time; measuring that needs a reviewed cursor template, which the plan takes from dev footage
*after* the split. Every section therefore records `cursorFraction: null`, and a null is never a
pass. The detector-based measurement runs once the template exists.

## Split

`split.json` fixes the channel assignment before any label exists. A channel never appears on both
sides, and both sides carry longplays plus one edited guide.

| Side | Channels | Full sections | Cut-only sections | Videos with full sections |
|---|---|---|---|---|
| dev | Sleepy Longplays, shaa rin, Langsamt, EVScape | 11 | 1 | 4 |
| heldout | Blizzard0199, Kelzayeee, 420slayerxpdrop, Theoatrix OSRS | 9 | 3 | 3 |

**Open shortfall.** The plan's statistical note wants about 6,000 visible held-out labels over at
least 6 videos. Nine held-out full sections give 5,400 labels at 10 Hz *before* the visible/absent
split, over 3 videos, because the held-out guide screens as `cut_only`. `split.json` lists three
options (more sections per held-out longplay, moving a longplay to held-out, or accepting the
smaller set and reporting the wider CI) with `decision: null`. This needs the owner.

## Not done in this run

- Labelling (W2) — dev clips are detector-assisted, held-out clips are labelled by the owner (D2).
- Detector v5 (I8–I10) — deliberately not started before dev footage exists, because each step must
  be measured on dev.
- Live verification (W7), additional gates (W8), FA13 (W9), Windows (W10), delivery (W11).

The review-export → corpus-labels converter those steps need is implemented and tested; see
`docs/mouse-video-import.md` under "Review export to FA12 corpus labels".
