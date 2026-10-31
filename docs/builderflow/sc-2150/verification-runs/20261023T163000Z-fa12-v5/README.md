# FA12 cursor-template/v5 — synthetic development split

- Method: `cursor-template/v5` (`MouseVideoCursorTracker` + the two-pass merge in
  `MouseVideoVisualAnalyzer.backwardPass`). v4 forward pass unchanged; a forward miss takes a
  backward detection only when that detection continues a backward track.
- Corpus: `build/sc2150-fa12-v3` dev split (87 clips; media not committed). Held-out was **not**
  run and remains unspent.
- Scorer: `tools/score.py`, sha256 `43e2c40a1cbdb9aba292d921c6ba9272e767a409686c3ac024b377329386c238`
  (identical to the v4 run's, recall gate 0.99).
- Harness: `MouseVideoCursorCorpusHarnessTest`, 8 shards, 14 m 41 s wall.

| dev (supported clips) | v4 (`20261023T013000Z-fa12-v4`) | v5 (this run) |
|---|---|---|
| recall | 0.888 (CI 0.843–0.928) | **0.9295** (CI 0.889–0.964) |
| median px (1080p) | 0.80 | 0.80 |
| p95 px (1080p) | 2.28 | 2.28 |
| absent false rate | 0.0004 | 0.0011 |
| cut F1 (tp/fp/fn) | 0.857 (6/0/2) | 0.857 (6/0/2) |

Gates: median, p95 and absent false rate pass; **recall (0.99) and cut F1 fail**. Per variant
recall: native 0.813 (lowest; absent false rate 0.017), hidpi-1.5-vfr-drop 0.891, hidpi-1.5 0.895,
all others ≥ 0.92. Per template: arrow 0.926, dragon-dagger 0.881, rs3-gold 0.944.

Files: `results/dev-v5-dev-1.json` (full score output), `results/split.json` (the clip split).
