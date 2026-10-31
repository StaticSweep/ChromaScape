# SC-2150 W12 — native round-trip recording session (Fedora), 2026-10-31

Owner decisions: record on **Fedora with the X11 canvas recorder** and a real dispatched task:
**fishing, drop** (no banking, no purchases). About 10 minutes. Owner does any login.

## Why a new recorder

ChromaScape's recorder captures the canvas HWND via `user32` and only runs on Windows.
`scripts/sc2150/record_x11_canvas.py` is its Linux counterpart. It writes the same
`<id>.mp4` + `<id>.frames.jsonl` (`chromascape-recording-frames/v1`), so
`label_from_mouse_trace.py` reads them unchanged.

- It grabs the heavyweight game-canvas X window (XWayland) through MIT-SHM. It never sends
  input and never touches window state.
- `captureNanos` is CLOCK_MONOTONIC (the clock Java's `System.nanoTime()` uses) at grab
  completion. `requestNanos` brackets each grab, and the converter drops a frame whose bracket
  contains a render.

### Qualified on a stand-in client

The stand-in is a Java AWT heavyweight canvas that draws a magenta crosshair along a known path
and writes `micro6-mouse-trace/v1`. 25 s at 30 fps (751 frames) gave:

| check | result |
|---|---|
| canvas found | the innermost 765x503 window, owner = the Java process |
| grab (MIT-SHM) | 7 ms median (host load ≈ 13); plain XGetImage took 14–33 ms, so SHM is the default |
| converter labels vs crosshair pixels in the decoded video | 509 visible frames: median 0 px, max 1.41 px, **100 % within 2 px**; 157/157 absent correct; 0 visibility errors |
| dropped | 85 (render during grab, stale render or no render yet) |

Found along the way, and fixed before qualification:

- The completion stamp was first taken after the Python buffer copy, which left one render in
  ~40 % of frames.
- With the completion stamp taken when the grab returns, every frame matches its label.

micro6's overlay stamps a render **during paint**, before presentation. The stand-in stamps
after `show()`+`sync()`. So the real session repeats the pixel cross-check (step 8) and does
not assume the stand-in's latency.

## Isolation

Reuse the SC-2015 qualification kit (`../../../../sc2015-qual-20261026`, mode **forward**). Its
jar (`client-1.13.4-SNAPSHOT-shaded.jar`) contains the `micro6-mouse-trace/v1` logger. Its
previous session shows canvas 765x503 **stretched to 1224x842**.

- The client runs inside the kit's netns boundary with executor 127.0.0.1:8099, capture server
  127.0.0.1:8093, `-Duser.home=client-home-client-forward`, and SC968 acceptance mode.
- Its self-test refuses to start Java if 8090 is reachable or 8093 is not.
- The live 8090 server and any 8098 client are untouched.
- The trace is written to `client-home-client-forward/.runelite/mouse-trace/` and is also sent
  to 8093, never to 8090.
- The X display is reached through the filesystem socket, as in earlier kit sessions.

## Steps (agent unless marked OWNER)

1. Preconditions: run `ss -ltnp '( sport = :8090 or sport = :8093 or sport = :8098 or sport = :8099 )'`.
   8093 and 8099 must be free. Confirm the account is not logged in on another client.
2. Start the qualification server: `sc2015-qual-20261026/start-qual-server.sh`. Wait for
   8093 health.
3. **OWNER** authorizes the account launch. If the staged handoff is stale, re-stage it with the
   documented staging snippet (`micro6/docs/JAGEX_ACCOUNT_LAUNCH.md`, mode `forward`). Never
   print its values.
4. Start the client: `sc2015-qual-20261026/start-qual-client.sh forward --developer-mode`.
   Check that the process owns 8099 and that `read 5 credentials` appears in the log.
5. **OWNER** confirms login and stands next to a net/bait fishing spot with the tool (for
   example shrimps). Dev Tools → **Bot Mouse** stays active (the crosshair is what is
   labelled).
6. Find the canvas and start recording. Take `stretchedSize` from the newest trace render:

   ```bash
   python3 scripts/sc2150/record_x11_canvas.py --out-dir build/sc2150-w12/rec \
     --canvas 1224x842 --list
   python3 scripts/sc2150/record_x11_canvas.py --out-dir build/sc2150-w12/rec \
     --id w12-fishing-<UTC> --canvas 1224x842 --duration 660 --wait 30
   ```

7. Dispatch fishing/drop through `POST http://127.0.0.1:8099/api/v1/execute`:
   - Settings come from `GET /api/v1/catalog`.
   - Purchasing and banking off.
   - The token is read from `executor-token` inside the request and never printed.

   Record the settings receipt. After about 10 minutes, `POST /api/v1/runs/{id}/stop` and
   confirm the run stopped. The recorder ends at `--duration`.
8. Convert and verify:
   - Run `label_from_mouse_trace.py --recording build/sc2150-w12/rec/<id>.mp4
     --trace <home>/.runelite/mouse-trace/<newest>.jsonl --split dev --out …`.
   - Check the clock cross-check and the counts.
   - Run the pixel cross-check: detect the drawn crosshair in the decoded frames against the
     labels (as on the stand-in).
   - Classify generated (bot) against human (`human` field) positions.
9. Score: import the recording on a disposable instance, run `visual/v1`, and score against the
   trace labels (dev split; not held-out).
10. Restore:
    - Stop only the identified kit client and the 8093 server.
    - Leave the staged handoff private and in place.
    - Record evidence under `verification-runs/<UTC>-w12-native/` (no media, no credentials).

## Rollback

Evidence-only: stop the kit client (8099) and server (8093) and delete `build/sc2150-w12`. The
recorder and converter changes are independent commits.
