# SC-2150 — Owner held-out (blind) labelling guide, 2026-11-01

These are the **held-out** sections. The detector is evaluated against them only after the labels are sealed. They must be labelled **blind**: the server refuses detector output for every held-out section (verified: observations return 403, and the only eligible mode is `WITHOUT_SUGGESTIONS`), so you cannot see a guess by accident. Please don't look at these clips anywhere else beforehand.

## Workload

- The review protocol is dense: **one queue item every 0.1 s**. The server's seal check enforces this, so the 0.2 s picked in D-HO isn't available without changing the frozen protocol.
- A 64 s section at 60 fps is about **640 items**, in two ranges (a range holds at most 2,000 frames).
- The statistical target is about 6,000 visible labels. **The 12 sections below (about 7,200 items) cover it.** Allow roughly 3–5 hours, split across sittings; everything autosaves.

## How

1. Open a link. **Blind** is the only mode offered; leave it on.
2. Tick the attestation ("I will follow the annotation protocol and disclose prior suggestion exposure."), then switch **Review mode** on.
3. Range: frames 0–1999 first. Then set **Range start 2000** and **Range end** to the last frame (GrimsEpicGames sections need only one range).
4. Per item: **click** the arrow's tip, **H** for no cursor visible, **A** if you can't tell (blur, overlap), **S** skip, **←/→** move, **Z** undo.
5. When every item in the range is labelled, press **Seal completed range** (once per range).

## Required (12 sections)

| # | Section | Content | Link |
|---|---|---|---|
| 1 | GrimsEpicGames s1 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=df1833ad-3fec-474c-a372-9ac2a5aeae39 |
| 2 | GrimsEpicGames s2 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=212ea84d-c511-42b5-a6c0-86935a99d244 |
| 3 | GrimsEpicGames s3 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=d906c41b-766e-4fa0-9a30-001e2ca14bb7 |
| 4 | TheEdB0ys s1 | boss combat | http://127.0.0.1:18191/mouse/video?source=33a6c509-dfb7-40db-97af-4dbea1f6c71a |
| 5 | TheEdB0ys s2 | boss combat | http://127.0.0.1:18191/mouse/video?source=18c23ef6-5082-4e7c-9a1f-d116cc64c525 |
| 6 | TheEdB0ys s3 | boss combat | http://127.0.0.1:18191/mouse/video?source=60eacfac-85f6-44c9-9b3d-514913b0dc8b |
| 7 | cape's second screen s1 | minigame interface | http://127.0.0.1:18191/mouse/video?source=3c49171d-3088-4e63-8fe4-cc2bdeb63ba1 |
| 8 | cape's second screen s2 | minigame interface | http://127.0.0.1:18191/mouse/video?source=0c9c405a-af8a-4dc7-aa16-1b9884bb534e |
| 9 | cape's second screen s3 | minigame interface | http://127.0.0.1:18191/mouse/video?source=1feaae2a-1e12-4597-ac35-17144be4a0d8 |
| 10 | 420slayerxpdrop s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=73f14711-99da-4fb0-b5fe-d7f14755278a |
| 11 | Blizzard0199 s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=509ec782-cd93-4d8f-ba7e-08615c18689a |
| 12 | Kelzayeee s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=01f2bf22-8d7b-42b0-80bf-babd58231b85 |

## Optional (more data, narrower confidence interval)

| Section | Link |
|---|---|
| 420slayerxpdrop s2 | http://127.0.0.1:18191/mouse/video?source=2de9d39d-5dbc-483a-a474-0450aca3f30b |
| 420slayerxpdrop s3 | http://127.0.0.1:18191/mouse/video?source=09473b61-afcc-4bd6-baeb-19dcf7b6b1bd |
| Blizzard0199 s2 | http://127.0.0.1:18191/mouse/video?source=a7101649-8dd0-4e51-8c14-d69da0b6ba97 |
| Blizzard0199 s3 | http://127.0.0.1:18191/mouse/video?source=ff4ea379-bb36-4e22-bf7b-c6eb964950d5 |
| Kelzayeee s2 | http://127.0.0.1:18191/mouse/video?source=0131f227-4d7d-43f5-912e-7499e0a3dc40 |
| Kelzayeee s3 | http://127.0.0.1:18191/mouse/video?source=64807f1d-4cb8-4368-9a5f-5b14f73288ef |
| Theoatrix OSRS s1 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=ed078c44-94a3-43e1-ad31-7568ca4e0453 |
| Theoatrix OSRS s2 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=de7b90b8-8b84-4453-adb7-9d500b97a28d |
| Theoatrix OSRS s3 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=74d36f28-6fb4-4ba0-b79b-c5679f901631 |

## After sealing (agent)

Export, then `labels_from_review_export.py` and `assemble_label_set.py`. The frozen v8 detector then
runs once on the held-out set and is scored against the gates (recall ≥ 95 %, absent false ≤ 1 %,
median ≤ 4 px, p95 ≤ 12 px, cut F1 ≥ 0.90), with the result recorded under
`verification-runs/<UTC>-fa12-heldout/`. Tell the agent when the sections are sealed.
