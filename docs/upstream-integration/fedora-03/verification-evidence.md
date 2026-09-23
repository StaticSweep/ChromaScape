# FEDORA-03 verification evidence

This file records what was actually executed and observed. Checks not run are named as such; no
fixture result is presented as a live result. The raw evidence record is a build artifact; its
SHA-256 is recorded here so a retained copy can be matched.

## Environment

| Item | Value |
|---|---|
| Host | Fedora Linux 44 (Workstation Edition), x86-64, kernel `6.19.10-300.fc44.x86_64` |
| Session | GNOME / Wayland, `DISPLAY=:0` via XWayland |
| Runtime Java (packaged, verified) | `openjdk 25.0.4.1` |
| Build Java (Gradle toolchain) | `17.0.20.1` |
| Client | Micro6 `net.runelite.client.RuneLite`, launched from `micro6-sc1618-fedora` (branch `verify/sc-1618-fedora-current`) |
| Native toolchain image | `localhost/sc1662-fedora-toolchain:latest`, ID `cabd7e24e0ca`, digest `sha256:15179d9f…` |
| Database | disposable `postgres:16-alpine` (rootless podman), random loopback port |

## Identities

| Item | Value |
|---|---|
| Application revision | `ef7dff0ef78912bb85f5fdd942fc30022a2ca570` |
| Package revision recorded | `ef7dff0ef78912bb85f5fdd942fc30022a2ca570` |
| Tarball SHA-256 | `f39472ac5b42c59454c3b58e8208b76a03f762e2e11e99b799e89aaca1c56469` |
| Package manifest SHA-256 | `b8eb5bb96066bf6800ff97f0c8f78f9dc760fa7f473c4666633fd0fe1ea8c1ce` |
| Native library SHA-256 | `65606e682f46756af8c62b15cfed555115f434604674d9facc761a9f5dd2426f` |
| Native manifest SHA-256 | `413f23329cecc2f8761d92de4fa3637bc4e3115a7cbf9a444172cf6146d2baf2` |
| Native pair identity | `40dd37b7c1c204b2a2bc4443a88ceb81af7875be25ce299b25938e203793884a` |
| Native patch identity | `sha256:7af654074906652c1bff730071978e56f1e0c6846f217962eedf7196bc9661e6` |
| Evidence record SHA-256 | `4e488a34c1e9570a253590e74bedc610810ac7f3f2925666b980cf715efd00b7` |
| Run log SHA-256 | `2656f47ca9cab2f00573100fc1965ef38139513fa2adbd28b8a4d94e862ea14f` |

The tarball is byte-identical when rebuilt: the packaging is deterministic, and the commits after
`e3f9e184` change only scripts, tests, documentation and Gradle task registration, never the
application JAR. The recorded `packageRevision` therefore names the exact harness revision whose
build produced this JAR and this hash.

## Client launch

The client was launched the canonical way, using the IntelliJ-equivalent Builder Goal entry point:

```bash
printf -- '-developer-mode\n-clean-jagex-launcher\n' > /tmp/micro6-client-args.txt
JAVA_HOME=<corretto-17> MICRO6_CLIENT_ARGS_FILE=/tmp/micro6-client-args.txt \
  bash ./gradlew --no-daemon --no-watch-fs :client:runBuilderGoal
```

from `micro6-sc1618-fedora` (the checkout whose `capture-contract.lock` matches the shared
contracts revision `7f4fa47e…`). The process reached `gameState=LOGIN_SCREEN` with a rendering
canvas.

A direct `java -cp microbot-1.8.9.3.jar net.runelite.client.RuneLite` launch was tried first and is
**wrong for this environment**: that client logged `error_game_js5connect_outofdate`, never drew a
frame, and every capture was a uniform black image. The canonical Gradle launch is required.

## Live matrix

Command:

```bash
BUILDER_GOAL_MICRO6_PROJECT_DIR=<micro6-sc1618-fedora> FEDORA03_TARGET_PID=150259 \
bash scripts/fedora/certify-live-recording-debugger.sh \
  --package build/packages/chromascape-0.2.0-linux-x86_64.tar.gz \
  --native-pair-dir build/dist/native/linux-x86_64 \
  --evidence build/packages/fedora03-live-evidence.json \
  --host-id <host> --revision ef7dff0e…
```

Result: `FEDORA03 level=PASS code=QUALIFICATION checks=20` — **17 PASS, 3 NOT_RUN, 0 FAIL**.

| Check | Result | Evidence |
|---|---|---|
| `debuggerEndpoint` | PASS | `/debugger` HTTP 200 on the installed tree |
| `liveTargetDiscovery` | PASS | 2 candidates; the Gradle launcher JVM and the client |
| `liveExplicitSelection` | PASS | selected the client window for pid 150259 (`net.runelite.client.RuneLite@micro6-sc1618-fedora`) |
| `qualificationAttach` | PASS | selected pid with process start time and boot-id fence |
| `qualificationCapture` | PASS | 765x503 non-uniform rendered frame written as PNG |
| `recordingStartStop` | PASS | `recording_1135c742`, 4 frames |
| `recordingSourceBinding` | PASS | `host=unconfigured pid=150259 generation=1`, `sourceMismatchFrames=0` over 3 frames |
| `recordingArtifact` | PASS | MP4 with a 764x502 h264 stream, duration 0.13 s |
| `recordingSessionFinalized` | PASS | `recording_sessions` row `FINALIZED` |
| `recordingDownload` | PASS | download byte-identical to the artifact |
| `debuggerSourceBinding` | PASS | viewport pipeline reports the selected pid while streaming |
| `debuggerViewportFrames` | PASS | 91 binary frames in 8 s, 5,150,824 bytes (≈56.6 KB/frame — real rendered pixels) |
| `debuggerTickSession` | NOT_RUN | client is attached but opened no tick session (no Micro6 `SESSION_START`) |
| `debuggerRecordingLink` | NOT_RUN | no tick session to link |
| `sessionExportVideo` | NOT_RUN | no tick session to export |
| `boundedInput` | PASS | in-bounds move, click and 120 ms SHIFT press |
| `inputRelease` | PASS | no key or button left held |
| `cleanClose` | PASS | session closed without poison |
| `runtimeRestart` | PASS | stop/start re-served `/debugger` |
| `noResidue` | PASS | no managed process, container port or install path remains |

Evidence gate:

```
python3 -B scripts/qualification/fedora03_evidence.py verify build/packages/fedora03-live-evidence.json
→ FEDORA03_EVIDENCE status=VALID role=live

python3 -B scripts/qualification/fedora03_evidence.py verify --require-complete …
→ FEDORA03_EVIDENCE status=INVALID … (the release gate refuses the three NOT_RUN cases)
```

## Findings

1. **The main `micro6` checkout cannot compile the client.** Its `runelite-gradle-plugin` lacks the
   `component` plugin that generates `ComponentID`/`InterfaceID`, so 37 client sources fail with
   `cannot find symbol`; `micro6-sc1618-fedora` and other branches have the plugin and the matching
   contracts lock. The main checkout's `assembleRs2asm` also failed with
   `NoClassDefFoundError: org/antlr/v4/runtime/CharStream` because its plugin build consumes the
   cache JAR as a raw file and does not inherit ANTLR. A one-line
   `implementation(libs.antlr.runtime)` was added to `micro6/runelite-gradle-plugin/build.gradle.kts`
   (uncommitted; disclosed, not part of this delivery) so the canonical task could run.
2. **A direct shaded-JAR launch is not a valid client launch.** It produced a black canvas and
   `error_game_js5connect_outofdate`. Only the canonical Gradle/IntelliJ entry point reached the
   rendering login screen. Live pixel checks must use that path.
3. **Recording frame accounting is low.** The recording wrote 4 frames over a ~45 s window
   (0.13 s of video) while the viewport delivered ≈11 fps. The frames are real (3.6 Mbps), so this
   is duplicate-frame accounting in the recording lane, not a capture failure. It is recorded as an
   open item for the recording owners; the FEDORA-03 acceptance only requires a finalized artifact
   with a video stream, which is proven.
4. **Tick-session checks need the Micro6 host session.** The client never sent a Micro6
   `SESSION_START` to ChromaScape's tick stream because it was launched standalone at the login
   screen without the ChromaScape connection. Those three cases are honestly `NOT_RUN` and belong
   to the Builder Goal / remote-launch qualification follow-up.

## Not executed

- A logged-in game session: the client had no credentials, so capture/recording were qualified
  against the rendering login screen, not in-game frames.
- Debugger tick session, recording link and session video export: prerequisite above.
- Multiple-client selection: only one client was running.
- RPM re-certification for this revision: unchanged from FEDORA-02.
