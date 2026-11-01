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
| 1 | GrimsEpicGames s1 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=90c0ef48-73a4-466d-9f83-93c2bef43e2f |
| 2 | GrimsEpicGames s2 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=4c821619-33b7-4b0c-ba72-2729e70bc565 |
| 3 | GrimsEpicGames s3 | overworld, 30 fps: one range | http://127.0.0.1:18191/mouse/video?source=c5ad2e65-c6bd-406d-aaae-2d524994a840 |
| 4 | TheEdB0ys s1 | boss combat | http://127.0.0.1:18191/mouse/video?source=1f84e256-7433-44e0-80c2-f5080729502b |
| 5 | TheEdB0ys s2 | boss combat | http://127.0.0.1:18191/mouse/video?source=80d54cdd-57c5-4c17-bd0d-2a06fec9193d |
| 6 | TheEdB0ys s3 | boss combat | http://127.0.0.1:18191/mouse/video?source=ed1202b1-e432-4cff-9b75-c7cf2e4beacf |
| 7 | cape's second screen s1 | minigame interface | http://127.0.0.1:18191/mouse/video?source=4f700c73-8606-4080-a1d9-e2a0e727044e |
| 8 | cape's second screen s2 | minigame interface | http://127.0.0.1:18191/mouse/video?source=3a1160a3-3286-420a-8a71-9e3e39ded648 |
| 9 | cape's second screen s3 | minigame interface | http://127.0.0.1:18191/mouse/video?source=3a581997-f951-4343-8fba-f636fdf2359e |
| 10 | 420slayerxpdrop s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=9468a1e9-e684-46db-af9e-2fc584c3fc91 |
| 11 | Blizzard0199 s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=f983732c-770d-487a-9a7b-961e083e29a7 |
| 12 | Kelzayeee s1 | earlier held-out channel | http://127.0.0.1:18191/mouse/video?source=bac6d337-4b25-47ec-8edc-a85f1b6316dd |

## Optional (more data, narrower confidence interval)

| Section | Link |
|---|---|
| 420slayerxpdrop s2 | http://127.0.0.1:18191/mouse/video?source=686c77d0-a7a0-4ac3-972f-e1d31532cf30 |
| 420slayerxpdrop s3 | http://127.0.0.1:18191/mouse/video?source=69ca4031-dbdd-4215-809b-ca55c756352f |
| Blizzard0199 s2 | http://127.0.0.1:18191/mouse/video?source=e5adb973-1438-4f8b-b3b4-98c95ae66650 |
| Blizzard0199 s3 | http://127.0.0.1:18191/mouse/video?source=3d80054b-e5ee-42d0-9bf1-f5bd680df48f |
| Kelzayeee s2 | http://127.0.0.1:18191/mouse/video?source=cddb5163-77e7-49fe-9278-aa67ef3a13df |
| Kelzayeee s3 | http://127.0.0.1:18191/mouse/video?source=252b57ce-0461-4f78-89c7-75ebef9f7cf3 |
| Theoatrix OSRS s1 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=0f306286-063e-489b-b652-044271009623 |
| Theoatrix OSRS s2 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=ba3f2a3d-671b-4d5c-9c92-acb444b4f781 |
| Theoatrix OSRS s3 (cut-only: almost all H) | http://127.0.0.1:18191/mouse/video?source=2cbcc27d-783e-4680-b15d-0568f3efb074 |

## After sealing (agent)

Export, then `labels_from_review_export.py` and `assemble_label_set.py`. The frozen v8 detector then
runs once on the held-out set and is scored against the gates (recall ≥ 95 %, absent false ≤ 1 %,
median ≤ 4 px, p95 ≤ 12 px, cut F1 ≥ 0.90), with the result recorded under
`verification-runs/<UTC>-fa12-heldout/`. Tell the agent when the sections are sealed.
