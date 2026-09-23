# FEDORA-03 live recording and debugger qualification runbook

## 1. Prerequisites

| Item | Requirement |
|---|---|
| Host | Fedora Linux x86-64, GNOME/Wayland with XWayland (`DISPLAY` set) |
| Client | Host-native RuneLite/Micro6 client running as the same desktop user, outside Flatpak/bwrap |
| Micro6 root | `BUILDER_GOAL_MICRO6_PROJECT_DIR` exported to the Micro6 checkout |
| Container | rootless `podman` for the disposable Postgres and the native build |
| Tools | `python3`, `jq`, `ffprobe`, `curl` |
| Native pair | locally built `libRemoteInput.so` + `remoteinput-manifest.json` (never shipped) |

The native pair is local-only: `third-party/RemoteInput/PROVENANCE.md` records
`top-level-licence-resolution: unresolved`, the shipped package contains no RemoteInput payload, and
this runbook wires the pair into the private installed prefix.

## 1b. Launch the client (canonical entry point only)

Use the IntelliJ-equivalent Builder Goal task from a Micro6 checkout whose `capture-contract.lock`
matches the shared contracts revision (at the time of writing: `micro6-sc1618-fedora`, lock
`7f4fa47e…`):

```bash
printf -- '-developer-mode\n-clean-jagex-launcher\n' > /tmp/micro6-client-args.txt
JAVA_HOME="$HOME/jdks/jdk-17.0.20.1+1" \
MICRO6_CLIENT_ARGS_FILE=/tmp/micro6-client-args.txt \
MICRO6_LOCAL_EXECUTOR_ENABLED=true \
  bash ./gradlew --no-daemon --no-watch-fs :client:runBuilderGoal
```

The client process is eligible for discovery when its command line carries
`net.runelite.client.RuneLite`. Do **not** launch the shaded client JAR directly: that path produced
`error_game_js5connect_outofdate` and a black canvas, and every capture came back uniform.

The main `micro6` checkout at the time of writing cannot compile the client because its Gradle plugin
build lacks the `component` generator (and its ANTLR runtime dependency); that fix belongs on a
Micro6 branch, not in the ChromaScape qualification.

## 2. Build and stage the native pair

The host has no compiler toolchain; the build runs in the pinned Fedora toolchain container
(digest `sha256:15179d9f…`, image ID `cabd7e24e0ca`). The container-level label confinement flag is
required because the checkout lives on an unlabeled mount; no host SELinux policy is modified.

```bash
podman run --rm --userns=keep-id --security-opt label=disable \
  -v "$PWD":/ws -v "$HOME/jdks/jdk-17.0.20.1+1":/opt/jdk17:ro -w /ws -e JAVA_HOME=/opt/jdk17 \
  localhost/sc1662-fedora-toolchain:latest \
  bash scripts/native/build-remoteinput-linux.sh --no-stage --jobs 4 \
    --patch-declaration docs/upstream-integration/fedora-live-qualification/evidence/native-patch-declaration.json

JAVA_HOME="$HOME/jdks/jdk-17.0.20.1+1" bash ./gradlew --offline --no-daemon \
  stageRemoteInputLinux -PremoteInputSkipBuild=true
```

Staging validates the pair and publishes it under `build/dist/native/.versions/<pair-identity>`
with the `linux-x86_64` relative symlink. Expect library SHA-256
`65606e68…`, patch identity `sha256:7af65407…`.

## 3. Build the package

```bash
JAVA_HOME="$HOME/jdks/jdk-17.0.20.1+1" bash ./gradlew --offline --no-daemon assembleLinuxDistribution
bash scripts/fedora/package-distribution.sh
```

The tarball lands in `build/packages/`. It is RemoteInput-free; the harness copies the staged pair
into the private installed release at qualification time.

## 4. Run the live matrix

```bash
BUILDER_GOAL_MICRO6_PROJECT_DIR=<micro6 checkout> \
FEDORA03_TARGET_PID=<client pid> \
bash scripts/fedora/certify-live-recording-debugger.sh \
  --package build/packages/chromascape-<version>-linux-x86_64.tar.gz \
  --native-pair-dir build/dist/native/linux-x86_64 \
  --evidence build/packages/fedora03-live-evidence.json \
  --host-id "$(hostname -s)" --revision "$(git rev-parse HEAD)"
```

The harness reads the ignored `.env.fedora.local` through the Fedora wrapper's bounded parser
and uses its source-trust credentials when present. For a client configured to send ticks to
`127.0.0.1:8090`, first stop the wrapper-owned development instance, then add
`--runtime-port 8090` to the harness command. The selected port must be free. The client must
reconnect and send a fresh `SESSION_START` to the packaged instance and its disposable database;
the session on the earlier development instance is not evidence for this run. Restart the
development instance after the harness exits if it is still needed.

`--target-pid` is required whenever more than one eligible client is visible; the harness refuses to
select automatically. The harness installs into a private prefix, starts a disposable Postgres,
starts the packaged runtime with `SPRING_PROFILES_ACTIVE=local-development` and the qualification
controller enabled, selects the window explicitly, and then runs:

| Check | Observation |
|---|---|
| `liveTargetDiscovery` | `/api/windows` candidates visible to the packaged runtime |
| `liveExplicitSelection` | `POST /api/windows/select` plus matching current selection |
| `qualificationAttach` | `/api/qualification/session/open` binds selected pid, start time, boot id |
| `qualificationCapture` | `/api/qualification/capture` returns non-uniform rendered pixels and a PNG |
| `recordingStartStop` | `/api/recordings/start|stop` complete a manual cycle |
| `recordingSourceBinding` | during-recording metrics report the bound source with zero mismatches |
| `recordingArtifact` | `ffprobe` decodes a non-empty MP4 |
| `recordingSessionFinalized` | `/api/recordings/sessions` reports `FINALIZED` |
| `recordingDownload` | download endpoint returns a byte-identical MP4 |
| `debuggerEndpoint` | `GET /debugger` returns 200 on the installed tree |
| `debuggerViewportFrames` | `/ws/viewport` streams binary frames (dependency-free probe) |
| `debuggerSourceBinding` | `/api/debug/viewport/pipeline` reports the selected pid |
| `debuggerTickSession` | client opened a debugger tick session (else honest `NOT_RUN`) |
| `debuggerRecordingLink` | after the independent manual recording completes, `/api/debug/replay/start-recording` reuses the active tick session and binds a second recording; the lookup matches that ID before stop and stop needs no link repair |
| `sessionExportVideo` | export of that debugger-linked session contains a non-empty `video.mp4` |
| `boundedInput` | in-bounds move, click and 120 ms SHIFT press |
| `inputRelease` | no key or button remains held |
| `cleanClose` | session closes without poison |
| `runtimeRestart` | stop/start re-serves `/debugger` |
| `noResidue` | no process, container or install path remains |

## 5. Verify the record

```bash
python3 -B scripts/qualification/fedora03_evidence.py verify build/packages/fedora03-live-evidence.json
python3 -B scripts/qualification/fedora03_evidence.py verify --require-complete build/packages/fedora03-live-evidence.json
```

The first command validates an honest partial matrix. The second is the release gate: it exits 2
unless the record carries the exact os/arch/java/client, package and native hashes, the package
revision equals the application revision, and every check is `PASS`.

The manual `/api/recordings/start` cycle and the debugger-linked replay recording are distinct
recordings. Manual recording does not bind a tick session; comparing its ID with the debugger
session's ID would make the link check impossible to pass on the supported API. The harness
therefore starts and stops a second recording through the debug replay API after proving the
manual cycle. It requires the existing tick session ID to be reused and verifies the link before
the stop endpoint can attempt any repair.

## 6. Cleanup

`--keep-work` retains the scratch root for debugging. Without it the harness removes the prefix, the
files downloaded during verification, and the disposable Postgres container; the runtime process is
stopped with TERM and then KILL inside its own process group. The user configuration, captures and
recordings under `$HOME` are never touched by this harness.
