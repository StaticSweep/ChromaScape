# SC-2150 — Owner dev review guide (W7), 2026-10-31

These are the **dev** sections: you may see the detector's guesses here (it is the held-out sections
that must stay blind). Your labels become real dev truth for tuning the detector (W8). Each section is ~3,840
frames (64 s at 60 fps), reviewed as **two ranges** of at most 2,000 frames, at 10 queue items per
second of video: ~640 items per section, ~7,700 in total (2–4 hours at one key or click per item); do it in as many sittings as you like —
everything autosaves and resumes.

## Where

Library instance: `http://127.0.0.1:18191` (task-owned verification server, database `sc2150_library`;
your normal ChromaScape on 8090 is not involved). The server enforces the split: held-out sections refuse
detector output, so you cannot see a held-out guess by accident.

## How, per section

1. Open the link below. The range defaults to frames **0–1999**; for the second pass set
   **Range start 2000** and **Range end** to the last frame (shown in the frame list), then continue.
2. Tick **"I will follow the annotation protocol and disclose prior suggestion exposure."**, then switch
   **Review mode** on.
   Leave **Blind** off for dev sections (detector-assisted is allowed and faster).
3. For each queue item (one frame every 0.1 s of video):
   - **Enter** — the detector's box is on the cursor tip: accept it.
   - **Click** the cursor tip — the guess is wrong or missing: label the exact point.
   - **H** — there is no cursor in this frame (hidden / off-screen / no game client).
   - **A** — you cannot tell (motion blur, overlapping UI).
   - **S** skip, **←/→** move between items, **Z** undo.
4. When every item of the range is labelled, press **Seal completed range** (once per range). Sealing freezes the labels (immutable export). Only seal a
   section you are done with.

Tips: the amber trail shows the last second of cursor path; label where the arrow's **tip** is. For
cut-only material (EVScape s2: facecam and title cards) almost every frame is **H**.

## Order and links

| # | Section | Link |
|---|---|---|
| 1 | Langsamt s3 (known hard case: confident-wrong lock) | http://127.0.0.1:18191/mouse/video?source=3a5f38f0-aab8-4d8f-a591-4283ff97d273 |
| 2 | Langsamt s1 | http://127.0.0.1:18191/mouse/video?source=b86d9619-f007-449a-9ce8-21488ea3da6d |
| 3 | Langsamt s2 | http://127.0.0.1:18191/mouse/video?source=40c3978a-20d1-482b-b574-013269fcc98f |
| 4 | Sleepy Longplays s1 | http://127.0.0.1:18191/mouse/video?source=0fae208f-ce6f-444f-b940-8345a372634f |
| 5 | Sleepy Longplays s2 | http://127.0.0.1:18191/mouse/video?source=540e50b7-8fe8-4be1-a0c5-8a93652b5042 |
| 6 | Sleepy Longplays s3 | http://127.0.0.1:18191/mouse/video?source=48dfa3b0-576a-48bc-b09c-9a9ae6195caa |
| 7 | shaa rin s1 | http://127.0.0.1:18191/mouse/video?source=06a82a9b-bb97-4d33-b132-566c87dad02b |
| 8 | shaa rin s2 | http://127.0.0.1:18191/mouse/video?source=c08b833e-1d0d-4557-bb2d-9ca2a5a2bf62 |
| 9 | shaa rin s3 | http://127.0.0.1:18191/mouse/video?source=75d283c8-0a01-42b7-a970-2e60a74496e4 |
| 10 | EVScape s1 (settings/interface screens) | http://127.0.0.1:18191/mouse/video?source=d57da0ba-ae5a-4223-9723-52336483eb4f |
| 11 | EVScape s3 (combat interfaces) | http://127.0.0.1:18191/mouse/video?source=00ea644f-5be6-410d-8dea-5dedbfa4dc1f |
| 12 | EVScape s2 (cut-only: facecam, title cards) | http://127.0.0.1:18191/mouse/video?source=8c8e6f10-4a08-45ec-8271-01714cc487d7 |

## After each sealed section (agent)

Export → `labels_from_review_export.py` → `assemble_label_set.py` → score against the detector, recorded
under `verification-runs/<UTC>-fa12-dev-review/`. Tell the agent which sections are sealed.
