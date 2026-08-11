# Fishing AI-player prototype

This is the smallest end-to-end autonomous-agent prototype currently implemented. ChromaScape is
the source of truth for recordings; the local Micro6 Debug Recorder JSON export is not used.

## What is implemented

- Micro6 captures `MenuOptionClicked` events into the ChromaScape tick stream as
  `PLAYER_ACTION/MENU_CLICK` records. Capture is independent of the local JSON recorder.
- ChromaScape preflight loads finalized recordings, aligns each action to the exact action tick (or
  the immediately previous snapshot), and rejects incomplete or mixed recordings.
- Training writes a JSONL dataset, manifest, and deterministic nearest-state demonstration model
  under `.chromascape/datasets/fishing/`.
- Replay evaluates a held-out recording at the recorded decision gates. It never sends live input.

The prototype is intentionally restricted to one fishing method and exactly five catches. Banking,
walking, inventory-full handling, Agent Server control, Codex runtime calls, and live Micro6 control
are not implemented. Keep `FEATURES_AI_PLAYER_CONTROL=false`.

## Required recording contract

Make two short manual ChromaScape recordings:

1. Training recording: exactly five catches.
2. Test recording: exactly five catches, using the same location, tool, method, and starting
   inventory, but a separate recording ID.

During either recording, do not walk, bank, drop items, or make unrelated clicks. The stream must
include player state, inventory/equipment, NPCs, and the strict replay sections (objects, ground
objects/items, players, widgets, quest, and cooldowns). Use a one-tick batch interval.

## Enablement

Set these environment variables for the ChromaScape process:

```dotenv
FEATURES_AI_PLAYER_ENABLED=true
FEATURES_AI_PLAYER_CONTROL=false
```

When using the Windows restart wrapper, put the same values in the git-ignored `.env.local` (the
template is `.env.example`).

In the Micro6 Debug Recorder configuration, enable ChromaScape streaming, player state/inventory,
NPCs, objects, ground objects/items, players, widgets, combat/cooldowns, and a tick batch interval
of `1`. Restart ChromaScape and Micro6 after changing configuration. Use
`CHROMASCAPE_RESTART_RUNBOOK.md` for the Windows restart path.

## API sequence

First verify that action capture is working with a short recording containing one fishing click. Use
the recording ID returned by the ChromaScape session confirmation:

```http
GET http://localhost:8090/api/ai-player/prototype/fishing/preflight?recordingId=<recording-id>
```

If the confirmation was missed, list sessions and use the `recordingId` from the finalized session:

```http
GET http://localhost:8090/api/debug/sessions
```

Create the training model only after preflight returns `"status":"READY"`:

```http
POST http://localhost:8090/api/ai-player/prototype/fishing/train
Content-Type: application/json

{"recordingId":"<training-recording-id>"}
```

The response contains `modelId`, dataset path, manifest path, and model path. Evaluate the separate
test recording:

```http
POST http://localhost:8090/api/ai-player/prototype/fishing/run
Content-Type: application/json

{"modelId":"<model-id>","recordingId":"<test-recording-id>"}
```

`PASSED` means every recorded fishing decision matched the deterministic model. `FAILED` includes a
stable failure code, tick, expected action, predicted action, and/or preflight issues. A run is
replay-only; it does not control the game client.

## Stable failure gates

`preflight` reports `NOT_READY` for missing snapshots/telemetry/actions, unsupported actions,
unaligned actions, action-queue overflow, a catch delta other than five, unchanged Fishing XP, or
an unfinished session. Training refuses to create artifacts when preflight is not ready. The run
endpoint rejects a missing model, the training recording reused as test data, and unfinished or
invalid test recordings.

## Next extension

The next safe increment is a shadow/live adapter that consumes the same `FishingState` and
`SemanticAction` contract while leaving control disabled by default. Only after that is verified
should Agent Server navigation, inventory-full handling, banking, and live action dispatch be added.
