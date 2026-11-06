# Held-out expansion (D-HO), 2026-10-31

Owner decision D-HO: add 2–3 new held-out longplay channels, with sections fixed at 20/50/80 %
before any footage is viewed.

## Selection

YouTube **metadata only** (id, channel, duration, title) was read with the pinned
`yt-dlp 2026.08.19` (SHA-256 `58162f9b…`, verified). Three channels new to the split were chosen
by title and content type, before any footage was viewed:

| Video | Channel | Duration | Why |
|---|---|---|---|
| `qJXe6GUepmk` | cape's second screen | 3,784 s | "OSRS No Commentary", one minigame |
| `NTe3PTTBhuU` | GrimsEpicGames | 3,672 s | "playing OSRS (No-Commentary)" |
| `lM4EJ4UuSZ8` | TheEdB0ys | 3,914 s | "1 Hour of Royal Titans (Solo) \| OSRS" |

Excluded: RS3, RSC and Dragonwilds videos, music, edited multi-hour series, an ambiguous
"Playing Runescape", and "SleepyScape", whose name is too close to the dev channel "Sleepy
Longplays" to be sure it is independent.

## Fetch, screen and import

- `tools/fetch_sections.py` (the 10-23 tool with this video table): 9 sections of about 60 s at
  20/50/80 %, all 1080p. They are kept in `build/sc2150-yt/raw-heldout2/` with
  `manifest-heldout2.json` (gitignored, never committed).
- `tools/screen_sections.py`: one midpoint frame per section. All 9 are `full` (`screening.json`).
- `split.json` assigns all three channels to **heldout**, before any label exists.
- The library instance's corpus policy enrols the 9 new groups as held-out. Existing groups are
  immutable and were not changed.
- Imported through the real upload route (`import-receipts.json`: 9 succeeded). The server refuses
  observations for all 9 (403), and their only review mode is `WITHOUT_SUGGESTIONS`.

The owner's labelling guide is `../../owner-heldout-labelling-guide-20261101.md`.
