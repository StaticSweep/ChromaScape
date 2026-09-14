# SC-1661 — Compatibility Map

Baseline: `583a05b91687d156b1baa52dbda2b219604c7b39`
Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`

This map records the interfaces that later stories must preserve or intentionally adapt. Every
class named below was verified to exist in the pinned baseline worktree. Signatures are quoted
from the baseline, not from upstream.

## Boundary 1 — Process / window selection

**Classes:** `utils/core/screen/window/WindowSelectionService.java` (80 lines),
`WindowController.java` (157), `WindowHandler.java` (867)

**Current callers and expected behaviour.** `WindowSelectionService` is the single source of
truth for which client is targeted:

```java
WindowInfo getSelectedWindow();   void setSelectedWindow(WindowInfo window);
boolean isSelected(WindowInfo window);   HWND getSelectedHwnd();
boolean hasSelection();           void clearSelection();
```

`WindowController` exposes this over HTTP (`listWindows`, `getCurrentWindow`,
`selectWindow`, `clearSelection`). `WindowHandler` performs Win32 enumeration and target
selection, including canvas-aware preference logic (`getTargetWindow`,
`getSelectableTargetWindows`, `getUiWindowCandidates`, `getAllJavaWindows`, and the
`CaptureSignature(canvasCount, hasSecondaryCanvas, area)` /
`WindowCandidate` records). The baseline's selection policy explicitly prefers a window with a
direct capture canvas over a larger zero-canvas sibling.

**Upstream differences.** `69ff913` **deletes `WindowHandler.java`** (157 lines removed) along
with the KInput native project, and rewrites `ScreenManager`. Upstream has no equivalent of
`WindowSelectionService` or the canvas-preference heuristic.

**Proposed adaptation.** Preserve the baseline selection model unchanged — explicit client
selection must survive the Linux port. Re-implement only the platform enumeration beneath it
(Win32 → Linux window discovery), keeping `getSelectedWindow()`/`isSelected()` semantics and
the `WindowCandidate` ranking rules intact.

**Owning story:** SC-1662 (session/selection), SC-1663 (capture).

**Regression tests:** `WindowHandlerTest`, `WindowControllerTest` (both named in the story
plan; confirm they exist and run under the chosen JDK — see `baseline-validation.md`).

## Boundary 2 — Capture

**Classes:** `utils/core/screen/window/ScreenManager.java`,
`ScreenManagerInitializer.java`

**Current callers and expected behaviour.** `ScreenManager` is the capture and coordinate
authority:

```java
static BufferedImage captureWindow();
static BufferedImage captureZone(Rectangle zone);
static Rectangle getWindowBounds();
static Rectangle getMonitorBounds();
static Rectangle toClientBounds(Rectangle screenBounds);   // screen -> client
static Point     toClientCoords(Point screenPoint);        // screen -> client
static void setWindowSelectionService(WindowSelectionService service);
```

**Upstream differences.** `db17529` rewrites `ScreenManager` (-117 lines) for capture
optimisation; `69ff913` rewrites it again (-251); `cad0847` changes it again for
BufferedImage↔Mat conversion. Three separate upstream rewrites land on the same class.

**— Key decision: canvas-relative vs screen coordinates. —**

This is the contract that must be resolved here, not in a dependent story.

- The baseline speaks **screen coordinates** and converts inward via
  `toClientBounds(...)` and `toClientCoords(...)`.
- Upstream RemoteInput expects **canvas-relative** coordinates.
- **Resolution:** `toClientCoords`/`toClientBounds` remain the *only* sanctioned conversion
  seam. Upstream canvas-relative values must be translated at that seam, never by callers
  doing ad-hoc arithmetic. No new conversion path may be introduced alongside it.

**Proposed adaptation.** Keep `ScreenManager`'s public surface stable; adapt its internals to
the Linux capture source. Preserve the invariant that `getWindowBounds()`/`getMonitorBounds()`
are screen-space and that client/canvas translation happens solely through the two `toClient*`
methods.

**Owning story:** SC-1663 (capture, bounds, coordinate behaviour).

**Regression tests:** `ScreenCaptureServiceTest`; new round-trip assertions for
`toClientCoords`/`toClientBounds`; device-pixel-ratio and non-zero-origin window cases.

## Boundary 3 — Recording capture

**Classes:** `web/recording/capture/WindowFrameCapture.java` (interface, 20 lines),
`ScreenManagerWindowFrameCapture.java`

**Current callers and expected behaviour.** The interface is deliberately minimal:

```java
BufferedImage capture();   // null when no frame; throws when window unavailable/invalid
```

Its own Javadoc fixes the responsibility split: implementations are thin, and **retry and
readiness policy belongs to `RecordingService`, not to implementations**. Unit tests supply
deterministic frame/failure sequences without touching Win32.

**Upstream differences.** Upstream has no equivalent abstraction; `69ff913` calls capture
directly.

**Proposed adaptation.** **Reuse this abstraction as the Linux capture seam.** Linux capture
becomes a new `WindowFrameCapture` implementation. Keep `capture()` free of retry/readiness
policy so existing tests and `RecordingService` behaviour are unaffected.

**Owning story:** SC-1663.

**Regression tests:** `RecordingConfigInitialFrameTest`,
`RecordingServiceInitialFrameReadinessTest` — these pin the null-on-no-frame contract.

## Boundary 4 — Frame delivery

**Classes:** `utils/core/screen/FrameEvent.java` (72 lines),
`FrameListener.java` (34), `web/recording/service/RecordingService.java`
(implements `FrameListener`)

**Current callers and expected behaviour.**

```java
// FrameEvent - immutable value
FrameEvent(BufferedImage frame, long captureTimeNanos, int sourceWidth, int sourceHeight);
getFrame(); getCaptureTimeNanos(); getSourceWidth(); getSourceHeight();

// FrameListener
void onFrameCaptured(FrameEvent event);
boolean isActive();
```

Contract points that must not drift: delivery is **asynchronous on a separate executor thread
per listener** (a slow listener must not block others); `isActive()` gates delivery without
unregistering; `captureTimeNanos` plus `sourceWidth`/`sourceHeight` carry frame ownership and
timing. `RecordingService` consumes frames this way and additionally exposes
`startRecording`, `stopRecording`, `stopRecordingIfCurrent`, `forceResetRecordingState`,
metrics, and auto-record gates.

**Upstream differences.** None — no upstream analogue; this is custom infrastructure.

**Proposed adaptation.** Preserve unchanged. New Linux capture implementations must emit the
same `FrameEvent` and honour `isActive()`.

**Owning story:** SC-1663; metadata/readiness aspects SC-1665.

**Regression tests:** `RecordingControllerMetadataTest`,
`RecordingServiceInitialFrameReadinessTest`, `RecordingServiceAutoStartGateTest`.

## Boundary 5 — Input (the RemoteInput seam)

**Classes:** `utils/core/input/remoteinput/Kinput.java` (286),
`utils/core/input/mouse/VirtualMouseUtils.java` (298),
`utils/core/input/keyboard/VirtualKeyboardUtils.java` (86)

**Current callers and expected behaviour.** `Kinput` is the native-injection façade:

```java
Kinput(int pid);
synchronized void clickLeft(int x, int y);  synchronized void clickRight(int x, int y);
synchronized void middleInput(int x, int y, int eventID);
synchronized void moveMouse(int x, int y);
synchronized void sendKeyEvent(int eventID, char keyChar);
synchronized void sendModifierKey(int eventID, String key, int keyID);
synchronized void sendArrowKey(int eventID, String key, int keyID);
synchronized void destroy();
```

`VirtualMouseUtils` wraps it with pathing/jitter (`moveTo(Point, String speed)`,
`leftClick`, `rightClick`, `middleClick`, `microJitter`, `getCurrentClientPosition`,
`getMouseOverlay`) and is constructed with `(Kinput, Rectangle bounds)`. `VirtualKeyboardUtils`
wraps key sending. Verified callers of `Kinput`: `Controller.java`, `VirtualMouseUtils`,
`VirtualKeyboardUtils`.

**Upstream differences — the single largest divergence.** `69ff913` **deletes
`Kinput.java`** and all of `third_party/KInput/**`, and adds
`RemoteInput.java`, `RemoteInputInterface.java`, `ControlKey.java`, `MouseButton.java` plus a
vendored `third-party/RemoteInput/**` tree, rewriting `VirtualMouseUtils` (+348) and
`VirtualKeyboardUtils` (+131).

**— Key decision: native attachment ownership. —**

`Kinput` is constructed with a **PID** and holds a single native attachment. Upstream
RemoteInput must not be allowed to attach across client or session boundaries.

- **Resolution:** native attachment is owned by exactly one client/session at a time, keyed
  off the `WindowSelectionService` selection. Attachment must be torn down via the existing
  `destroy()` lifecycle when selection changes or a session ends; no second attachment may be
  opened while one is live.
- The upstream vendored tree must be reviewed before inclusion (it carries
  `.xcodeproj/xcuserdata/**/UserInterfaceState.xcuserstate` and
  `.github/workflows/build.zip` — editor state and a build archive, not source).

**Proposed adaptation.** Introduce the input abstraction at this seam so Linux uses RemoteInput
while preserving `VirtualMouseUtils`/`VirtualKeyboardUtils` call shapes. Upstream's
`Kinput.java` **deletion must not be taken literally** — the baseline's callers need a
substitute interface first.

**Owning story:** SC-1662–SC-1667 (**RemoteInput implementation is explicitly out of scope for
SC-1661**).

**Regression tests:** input parity between KInput and RemoteInput paths; single-attachment
ownership test; `destroy()` lifecycle test.

## Boundary 6 — Builder Goal

**Classes:** `web/debug/buildergoal/service/BuilderGoalLauncherControlService.java`,
`Micro6ExecutorReadinessEvaluator.java`

**Current callers and expected behaviour.** `BuilderGoalLauncherControlService` routes launcher
commands and owns machine/account resolution and local-machine detection:

```java
static final String MANAGED_CLIENT_TYPE = "leeuwbot";
List<LauncherAccount> listAccounts();   String revealAccountPassword(String accountHash);
List<Map<String,Object>> listMachineSummaries();   Optional<LauncherMachine> findMachineByUuid(String);
boolean isLocalMachine(LauncherMachine);   boolean isBuilderGoalRunnableMachine(LauncherMachine);
String getBuilderGoalMachineDisabledReason(LauncherMachine);   List<LauncherMachine> listMachines();
StartCommandResult startAccount(String accountIdentifier, String machineUuid);
CommandStatus awaitCommandSuccess(String commandId);   CommandStatus getCommandStatus(String);
String getDefaultMachineUuid();
```

`Micro6ExecutorReadinessEvaluator` is a pure, static readiness state machine with phases
`WAITING_FOR_LOGIN`, `WAITING_FOR_WORLD`, `WAITING_FOR_SCRIPT`, `WAITING_FOR_DISPATCH`,
`READY_TO_PUBLISH`, returning `Evaluation(phase, reason, publishReady)`.

**Upstream differences.** No upstream analogue; this is custom. The Fedora remote routing
(`97284f1` / `ced7487`) extends it.

**Proposed adaptation.** Preserve host, client and session routing exactly. The Fedora change
is an **Adapt** (see `custom-change-reconciliation.md` — its two SHAs differ by one
`launchAttemptId()` vs `launchAttemptId` expression).

**Owning story:** SC-1667 / Fedora follow-up.

**Regression tests:** `BuilderGoalLauncherControlServiceTest`,
`Micro6ExecutorReadinessEvaluatorTest`.

## Boundary 7 — Build

**File:** `build.gradle.kts`

**Current callers and expected behaviour.** The build declares a **Java 17 toolchain**
(`languageVersion.set(JavaLanguageVersion.of(17))`), the `checkstyle` plugin
(`config/checkstyle/google_checks.xml` with suppressions), Spotless 6.19.0, and custom tasks
that must be preserved:

- `replayTest` (line 151), `replayCertificationTest` (173), `replayStressCertificationTest` (255)
- `cleanChromascape` (506), `cleanAll` (522)
- `copyNativeLibraries` (459)
- `check` is wired to `dependsOn("spotlessCheck", "checkstyleMain")`

**Upstream differences.** Three upstream commits touch this file: `cad0847` **removes 78
lines**, plus `580a619` (also touches `settings.gradle.kts`) and `5ea3b99`.

**Proposed adaptation.** Merge **per-hunk only**. The custom test/certification tasks and
native-library copy tasks are load-bearing for this fork and must survive. A wholesale
upstream take of `build.gradle.kts` would delete them.

**Owning story:** SC-1666.

**Regression tests:** `./gradlew tasks` shows all custom tasks; `replayTest` and
`spotlessCheck checkstyleMain` still resolve. (Currently **not verified** — see
`baseline-validation.md`.)

## Downstream responsibility summary

| Boundary | Owning story | Must preserve |
|---|---|---|
| Window selection | SC-1662 | Explicit client selection; canvas-preference ranking |
| Capture + coordinates | SC-1663 | `toClient*` as the only conversion seam |
| Recording capture | SC-1663 | Thin `capture()`; policy stays in `RecordingService` |
| Frame delivery | SC-1663 / SC-1665 | Async per-listener delivery; ownership + timestamps |
| Input / RemoteInput | SC-1662–SC-1667 | Single native attachment owned by one session |
| Builder Goal | SC-1667 | Host/client/session routing |
| Build | SC-1666 | Custom test, certification, native-library tasks |
