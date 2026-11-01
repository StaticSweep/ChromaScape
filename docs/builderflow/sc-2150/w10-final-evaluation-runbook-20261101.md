# SC-2150 — W10 final held-out evaluation runbook (2026-11-01)

The single frozen evaluation (F7, acceptance A11). It runs **once**, after the owner has sealed the
blind held-out labels, and the result is recorded whatever it says. A failed gate stays failed
(owner decision D5); the visible-recall gate is 95 % (owner decision D-RECALL).

This runbook is executable as written. The only inputs it cannot supply are the owner's sealed
labels and the frozen cursor template — see §1.

## 1. Prerequisites

| Input | Status | Where |
|---|---|---|
| Blind held-out labels, sealed | **Open — owner (OWN-2)** | `owner-heldout-labelling-guide-20261101.md`; 12 required sections |
| Frozen method | Done | `cursor-template/v8` (`MouseVideoCursorTracker.METHOD`) |
| Frozen scorer | Done | `verification-runs/20261024T024537Z-fa12-v6/tools/score.py`, `sha256:43e2c40a…c238` |
| Recorded gate decision | Done | `gate-decisions-20261101.json` (D-RECALL) |
| Frozen cursor templates | Done | `verification-runs/20261101T180000Z-fa12-freeze/freeze.json` pins `arrow`, `dragon-dagger`, `rs3-gold` by hash |
| Held-out membership | Done | server refuses observations for all 9 new sections and the older held-out sections (403) |

Templates are an input, not truth: choosing them needs no labels, but their hashes must be in the
recorded staging manifest **before** the harness runs, so the scored method is reconstructible. Do
not re-pick a template after seeing a score.

## 2. Command sequence

All commands run from the repository root. `$STAMP` is the UTC run directory name.

**a. Convert each sealed session to a corpus label document.** One document per sealed review
range, using the converter's sealed-export path (it verifies the bytes against the loopback
server and refuses detector-assisted held-out exports):

```bash
python3 scripts/sc2150/labels_from_review_export.py \
  --session-export build/sc2150-heldout/<session>.export.json \
  --verify-server http://127.0.0.1:18191 \
  --frame-index build/sc2150-heldout/<source>/index \
  --split heldout --labeller owner --labelling-protocol without-suggestions \
  --source-size 1920x1080 --output-size 1920x1080 \
  --clip-id "$(basename <session>)" \
  --template arrow --hotspot "arrow tip (review click)" \
  --out build/sc2150-heldout/labels/<session>.labels.json
```

`--source-size` must equal the sealed coordinate plane's display size. `--output-size` must equal
the plane the harness predicts in, which is the media's coded size (1080p sections: `1920x1080`,
`px_to_1080p` = 1.0, so gate errors are real pixels). `--template` must name a `templates/<name>.png`
present in the staging directory; it is a class name for per-template reporting, not a path.

**b. Stage the scoring corpus.** The stager proves every label document's `media_sha256` matches
the media file and that every named template exists, then writes the exact layout the harness and
scorer read:

```bash
python3 scripts/sc2150/stage_corpus.py \
  --labels build/sc2150-heldout/labels \
  --media <source-id>=<original section media> [--media ...] \
  --templates build/sc2150-heldout/templates \
  --split heldout --out build/sc2150-heldout/corpus
```

If the media sits beside the label documents as `<clip_id>.mp4`, `--media-dir build/sc2150-heldout/media`
replaces the repeated `--media` entries. The stager accepts `sc2150-fa12-labels/v1`–`v3` and refuses
mismatched media digests, missing templates, duplicate clip ids and a non-empty destination.

It refuses a non-empty destination, so a second staging run cannot silently mix sets.

**c. Produce predictions with the frozen method.** No threshold overrides:

```bash
SC2150_FA12_CORPUS=build/sc2150-heldout/corpus \
SC2150_FA12_SPLIT=heldout \
SC2150_FA12_OUT=build/sc2150-heldout/predictions-1 \
./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.service.MouseVideoCursorCorpusHarnessTest' \
  -Psc967VectorsPath=<contracts fixture>
```

Run it a second time into `predictions-2` for the determinism comparison.

**d. Score once.**

```bash
python3 docs/builderflow/sc-2150/verification-runs/20261024T024537Z-fa12-v6/tools/score.py \
  --corpus build/sc2150-heldout/corpus --split heldout \
  --predictions build/sc2150-heldout/predictions-1 \
  --compare build/sc2150-heldout/predictions-2 \
  --out "docs/builderflow/sc-2150/verification-runs/$STAMP-fa12-heldout/results/heldout.score.json"
```

**e. Apply the recorded owner gate decision.** The frozen scorer keeps its own verdict; the
decided verdict is a separate, hash-linked document:

```bash
python3 scripts/sc2150/apply_gate_decision.py \
  --report "docs/builderflow/sc-2150/verification-runs/$STAMP-fa12-heldout/results/heldout.score.json" \
  --decisions docs/builderflow/sc-2150/gate-decisions-20261101.json \
  --decision-id D-RECALL \
  --out "docs/builderflow/sc-2150/verification-runs/$STAMP-fa12-heldout/results/heldout.gate-decision.json"
```

**f. Record the run.** In `verification-runs/$STAMP-fa12-heldout/README.md`: the freeze table
(method, scorer hash, decision-document hash, every template hash, staging-manifest hash, media
hashes), the harness environment, the scorer report, the gate-decision result, and the per-source
figures from the report's `by_source` block. Predictions and media stay out of Git.

## 3. What the result means

- `frozen.all_gates_pass` is the scorer's verdict under the gate values it was frozen with (recall
  99 %).
- `decided.all_gates_pass` is the verdict under the owner's recorded values (recall 95 %,
  everything else unchanged). `relaxed` lists exactly which gates were loosened; today that is
  `["recall"]` and nothing else may appear without a new recorded decision.
- `comparison` reports how many compared observations were equal. It is a fact, not a verdict: the
  same field holds a determinism rerun or a baseline run depending on `--compare`.

Held-out numbers never justify a detector change. A miss here means the method failed its own
gate; only a new method id with new, still-unseen held-out footage can be evaluated again.
