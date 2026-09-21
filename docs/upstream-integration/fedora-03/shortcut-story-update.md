# FEDORA-03 shortcut story update

## Title

FEDORA-03: Qualify live recording and the debugger on Fedora

## Delivered

- `scripts/fedora/certify-live-recording-debugger.sh` installs the FEDORA-02 tarball into a private
  prefix without sudo, wires the locally built RemoteInput pair into the installed release, starts
  the packaged runtime with the qualification controller enabled, explicitly selects a real
  host-native RuneLite/Micro6 client, and drives 20 live checks.
- `scripts/qualification/fedora03_evidence.py` is the fail-closed record contract; the release gate
  refuses a matrix unless every case is PASS and the exact os/arch/java/client, package and native
  hashes, patch identity and package revision are recorded.
- `scripts/qualification/ws_probe.py` is a dependency-free WebSocket probe used to observe the
  debugger live view.
- Regressions: 19 harness, 11 evidence-contract and 8 probe cases, wired as the
  `fedora03LiveQualificationTests` Gradle task.

## Verified live (packaged install, no checkout)

- Explicit selection of `net.runelite.client.RuneLite@micro6-sc1618-fedora` (pid 150259).
- Attachment with process start time and boot-id fence; 765x503 non-uniform rendered frame captured.
- Manual recording start/stop → finalized MP4 (764x502 h264), FINALIZED database row, byte-identical
  download, zero source-mismatch frames.
- Debugger `/debugger` 200, `/ws/viewport` 91 binary frames in 8 s (≈56.6 KB/frame), viewport
  pipeline source binding matching the selected pid.
- Bounded input, complete release, clean close, runtime restart, and no residue.
- Result: 17 PASS, 3 NOT_RUN, 0 FAIL; the release gate correctly refuses the incomplete matrix.

## Findings recorded

- The client must be launched through the canonical Gradle/IntelliJ entry point
  (`:client:runBuilderGoal`, `-developer-mode -clean-jagex-launcher`). A direct shaded-JAR launch
  produced a black canvas (`error_game_js5connect_outofdate`) and no frames.
- The main `micro6` checkout cannot compile the client: its Gradle plugin build lacks the `component`
  generator and the ANTLR runtime. The launch used `micro6-sc1618-fedora`; the main-checkout fix
  belongs to a Micro6 branch.
- The recording lane wrote 4 frames over a ~45 s window while the viewport delivered ≈11 fps
  (duplicate-frame accounting), recorded as an open item.

## Not certified (honest gaps)

- Debugger tick session, recording link and session video export: the client opened no Micro6
  `SESSION_START` (standalone login screen, no ChromaScape connection) — `NOT_RUN` with that
  prerequisite.
- In-game capture/recording (no credentials in the qualification session) and multiple-client
  selection (only one client running).
- The shipped package still excludes RemoteInput; the provenance licence marker stays `unresolved`.

## Delivery

- Branch: `codex/fedora-03-live-recording-debugger`
- Evidence: `docs/upstream-integration/fedora-03/verification-evidence.md`
- Pull request link is recorded here when opened.
