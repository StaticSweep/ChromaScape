# YouTube corpus — cursor presence probe (stopped after 6 of 24 sections)

Goal: estimate how often the cursor is on screen in each downloaded YouTube section, to choose which
sections are worth labelling. Not a labelled metric.

- Server: disposable copy on 127.0.0.1:18190 (`sc2150_fa12_live`), `cursor-template/v5`, arrow
  template, `CHROMASCAPE_MOUSE_VIDEO_MAX_JOB_DISK_BYTES=1073741824`. Stopped afterwards.
- Tool: `tools/yt_presence.py` uploads each section, runs `visual/v1` and counts cursor samples.

| video / section | screening | split | VISIBLE | AMBIGUOUS | visible share | scene cuts |
|---|---|---|---|---|---|---|
| 33MkV_p4jvs s1 | full | dev | 84 | 1196 | 0.07 | 3 |
| 33MkV_p4jvs s2 | full | dev | 0 | 1280 | 0.00 | 1 |
| 33MkV_p4jvs s3 | full | dev | 486 | 794 | 0.38 | 2 |
| 9YFTgB78bDY s1 | cut_only (no client, no cursor) | heldout | 286 | 613 | 0.32 | 7 |
| 9YFTgB78bDY s2 | cut_only (no client, no cursor) | heldout | 440 | 459 | 0.49 | 5 |
| 9YFTgB78bDY s3 | cut_only (no client, no cursor) | heldout | 379 | 521 | 0.42 | 6 |

## Finding

The probe cannot measure presence. On the three Theoatrix sections, which have no client and no
cursor, v5 reports a visible cursor on 32–49 % of samples. That is a false-sighting rate, compared
with 0.11 % on the synthetic dev split. The Langsamt gameplay sections, which have a cursor, come out
mostly AMBIGUOUS. The synthetic corpus does not represent real footage for false sightings: real
b-roll, title cards and UI contain many arrow-like shapes. The run was stopped because the remaining
sections could not change this.

Held-out note: 9YFTgB78bDY is in the real-footage held-out split. Nothing was scored (there are no
labels), but these three observations must not be used to tune the detector.

## Run-environment findings

- `start_verification.py` caps job disk at 128 MiB. Sampling a 64 s 1080p60 section writes about
  134 MB of grey PNGs, so the first attempt failed with `DECODER_FAILED`. The production default
  is 4 GiB.
- The frame-index budget fix (commit 51d243a7) held: every 60 fps section imported with default
  budgets.
