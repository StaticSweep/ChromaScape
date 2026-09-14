# SC-1661 — Compatibility Map

Candidate baseline: `583a05b91687d156b1baa52dbda2b219604c7b39`
Reconciled code baseline: `966e0e3`
Pinned upstream: `5ea3b995f5d13e19c592a6399973fe7dadb5e656`

Every class named below was verified to exist in the candidate baseline. Signatures are quoted
from the baseline, not from upstream.

This revision replaces two earlier assumptions that were not implementable:

- that "Win32 window discovery" could simply become "Linux window discovery" (it cannot — the
  neutral identity is a **process** identity, not a window handle; see Decision 1);
- that ownership could be left at the level of "SC-1662–SC-1667" (the stories have specific
  scopes; see Decision 7).

## Required SC-1661 decisions

Each decision names the affected callers, the required behaviour, the owning story, and the method
by which it will be verified. Implementation belongs to the owning story; SC-1661 only fixes the
contract.

### Decision 1 — Platform-neutral process identity vs the HWND-based selection model

**The unsupported assumption being replaced.** The earlier draft said: "Re-implement only the
platform enumeration beneath it (Win32 → Linux window discovery), keeping
`getSelectedWindow()`/`isSelected()` semantics." That is not implementable as written, because the
baseline's selection model is not merely *enumerated* with Win32 — its **identity type is a Win32
handle**:

```java
WindowInfo getSelectedWindow();   HWND getSelectedHwnd();     // HWND is a Win32 type
boolean isSelected(WindowInfo window);   boolean hasSelection();
```

There is no Linux equivalent of an `HWND`, and a window is not a stable target on Linux anyway:
the client may run under X11 or Wayland, may own several surfaces, and may be one of several
processes. The existing `Kinput(int pid)` constructor already shows what the rest of the codebase
actually needs: **a process to inject into**, not a window.

**Required behaviour.**

1. Introduce a **platform-neutral target identity** — process identity (`pid` plus a start-time or
   boot-id component so a recycled PID cannot silently retarget an attachment) plus an optional
   surface descriptor for capture.
2. Keep `WindowInfo` as the user-facing/selection value, but stop requiring an `HWND` to be present
   in its platform-neutral form. `getSelectedHwnd()` becomes a **platform-adapter** accessor, valid
   only on Windows builds.
3. Platform code resolves the neutral identity → platform surface (Win32 `HWND`, X11 window,
   Wayland surface). Selection semantics (`getSelectedWindow`, `isSelected`, `hasSelection`,
   `clearSelection`) and the canvas-preference ranking
   (`CaptureSignature(canvasCount, hasSecondaryCanvas, area)`, `WindowCandidate`) are preserved.

**Affected callers.** `WindowController` (HTTP surface: `listWindows`, `getCurrentWindow`,
`selectWindow`, `clearSelection`); `ScreenManager.captureWindow`/`getWindowBounds`;
`RecordingService` through the capture abstraction; `Kinput` construction (already PID-based);
`WindowHandler` enumeration.

**Owning story:** **SC-1663** — process discovery and explicit target selection.

**Verification method.** Selection-semantics unit tests that pass with a non-Windows target
identity double; a test asserting a recycled/absent process cannot satisfy `isSelected`; and
`WindowControllerTest` unchanged at the HTTP contract level.

### Decision 2 — Which screen APIs must retain compatibility, and which need adapters

**Must keep their exact signatures** (declared compatibility surface; callers outside the screen
package depend on them):

```java
static BufferedImage captureWindow();
static BufferedImage captureZone(Rectangle zone);
static Rectangle getWindowBounds();          // screen space
static Rectangle getMonitorBounds();         // screen space
static Rectangle toClientBounds(Rectangle screenBounds);
static Point     toClientCoords(Point screenPoint);
static void setWindowSelectionService(WindowSelectionService service);
```

**Need an adapter behind the same surface** (implemented by the owning story, not here): the
capture source itself, `ColourContours`, and the BufferedImage↔Mat conversion that upstream
`cad0847` introduces.

**Explicitly not a compatibility requirement:** `WindowHandler`'s Win32 enumeration internals and
the `HWND` type leaked by `getSelectedHwnd()`.

**Affected callers.** `ScreenCaptureServiceTest`; `RecordingService` (via `WindowFrameCapture`);
replay/compiler code reading frames.

**Owning story:** **SC-1665** — rendered-buffer capture and screen API compatibility.

**Verification method.** `ScreenCaptureServiceTest` unchanged; a round-trip assertion set for
`toClientCoords`/`toClientBounds`; device-pixel-ratio and non-zero-origin cases.

### Decision 3 — Canvas-relative vs desktop coordinates

**The distinction, stated precisely.**

| Space | Meaning | Produced by | Consumed by |
|---|---|---|---|
| **Desktop** | Absolute screen coordinates across all monitors | `getWindowBounds()`, `getMonitorBounds()` | window placement, monitor selection |
| **Client/canvas** | Origin at the target's capture canvas, `(0,0)` = canvas top-left | `toClientBounds` / `toClientCoords` | input injection (RemoteInput expects canvas-relative), recording frame metadata |

**Required behaviour.** `toClientCoords`/`toClientBounds` remain the **only** sanctioned conversion
seam. Upstream canvas-relative values are translated *at that seam and nowhere else*; no caller may
do ad-hoc arithmetic, and no second conversion path may be introduced alongside it. Screen-space
methods stay screen-space.

**Affected callers.** `VirtualMouseUtils` (`moveTo`, click helpers) which currently receives
`(Kinput, Rectangle bounds)`; `PointSelector`; replay/compiler action classes that carry target
coordinates.

**Owning story:** **SC-1665** owns the conversion seam; **SC-1664** must consume it rather than
re-deriving coordinates.

**Verification method.** Round-trip identity assertions (`toClientCoords` then inverse) on a
non-zero-origin window; a negative test that a canvas-relative value fed to an input API is never
double-offset.

### Decision 4 — How host, process, and session identity bind an attachment

`Kinput` is constructed with a **PID** and holds a single native attachment. RemoteInput must not be
allowed to attach across client or session boundaries.

**Required binding.** An attachment is bound to the triple **(host, process identity, session)**:

1. **host** — the machine the attachment runs on. This now matters because SC-1667's carried Fedora
   routing means a launch target can be remote, not only local.
2. **process identity** — the neutral identity from Decision 1, resolved to the platform surface at
   attach time.
3. **session** — the selection/session that requested it, so that a selection change or session end
   tears the attachment down.

**Required behaviour.** Exactly one live attachment per (host, process, session). A second attach
attempt while one is live must fail closed rather than silently re-target. Teardown uses the
existing `destroy()` lifecycle. No attachment may outlive its session.

**Affected callers.** `Controller.java`, `VirtualMouseUtils`, `VirtualKeyboardUtils` (the three
verified `Kinput` callers); `WindowSelectionService` (selection change is the teardown trigger);
the carried `Micro6RemoteLauncherService` (remote host identity).

**Owning story:** **SC-1664** — input attachment and mouse/keyboard lifecycle.

**Verification method.** A single-attachment ownership test; a `destroy()`-on-selection-change
lifecycle test; a negative test that a second attach is refused while one is live.

### Decision 5 — How recording retains its capture abstraction and readiness policy

The abstraction is deliberately minimal and must not absorb policy:

```java
BufferedImage capture();   // null when no frame; throws when window unavailable/invalid
```

**Required behaviour.** Retry and readiness policy stays in `RecordingService`, never in a
`WindowFrameCapture` implementation. Linux capture becomes a **new implementation of this
interface**, not a new interface. The null-on-no-frame contract is preserved. `FrameEvent`
delivery stays asynchronous on a separate executor thread per listener, `isActive()` keeps gating
delivery without unregistering, and `captureTimeNanos`/`sourceWidth`/`sourceHeight` keep carrying
frame ownership and timing.

**Affected callers.** `RecordingService` (implements `FrameListener`; exposes `startRecording`,
`stopRecording`, `stopRecordingIfCurrent`, `forceResetRecordingState`, metrics, auto-record gates);
`RecordingController`; `ReplayViewController`.

**Owning story:** **SC-1666** — recording, debugger, and remote-launch integration.

**Verification method.** `RecordingConfigInitialFrameTest` (pins null-on-no-frame) and
`RecordingServiceInitialFrameReadinessTest` must pass unchanged against the Linux implementation.

### Decision 6 — Which build and certification tasks must survive upstream integration

These are load-bearing for this fork and must survive any per-hunk merge of `build.gradle.kts`:

| Task / setting | Why it must survive |
|---|---|
| `replayTest` | Isolated replay suite; must remain independently runnable |
| `replayCertificationTest` | Windows-only hard gate; fails closed rather than skipping |
| `replayStressCertificationTest` | Determinism/failure-injection/PostgreSQL race evidence |
| `cleanChromascape`, `cleanAll` | Operator-facing cleanup |
| `copyNativeLibraries` | Native library staging — directly implicated by the RemoteInput build |
| `check` → `dependsOn("spotlessCheck", "checkstyleMain")` | Quality gate wiring |
| Java 17 toolchain (`languageVersion.set(JavaLanguageVersion.of(17))`) | The verified launcher/toolchain pairing for this baseline |

**Upstream contest.** Three upstream commits touch this file (`cad0847` removes 78 lines,
`580a619`, `5ea3b99`). A wholesale take would delete the tasks above.

**Owning story:** **SC-1662** — native RemoteInput build and library loading.

**Verification method.** `./gradlew tasks` must still enumerate every task above after each merge,
and `./gradlew replayTest` must still execute. Both are currently verified at the candidate
baseline — see `baseline-validation.md`.

### Decision 7 — Ownership resolution

The earlier map left Boundary 1 owned by "SC-1662 (session/selection)", Boundary 3/4 by SC-1663,
and the build by SC-1666. Those do not match the actual stories. The corrected assignment is in the
summary table below, drawn from the authoritative scopes in `upstream-commit-ledger.md`.

## Boundary 1 — Process / window selection

**Classes:** `utils/core/screen/window/WindowSelectionService.java`,
`WindowController.java`, `WindowHandler.java`

**Upstream differences.** `69ff913` deletes `WindowHandler.java` and the KInput native project, and
rewrites `ScreenManager`. Upstream has no equivalent of `WindowSelectionService` or the
canvas-preference heuristic.

**Contract:** Decision 1.

**Owning story:** **SC-1663** (process discovery, explicit target selection). Selection *preservation*
across the port is SC-1663's as well — not SC-1662, which owns the native build.

**Regression tests:** `WindowHandlerTest`, `WindowControllerTest`.

## Boundary 2 — Capture

**Classes:** `utils/core/screen/window/ScreenManager.java`, `ScreenManagerInitializer.java`

**Upstream differences.** Three upstream rewrites land on the same class: `db17529` (-117),
`69ff913` (-251), `cad0847` (Mat conversion).

**Contract:** Decisions 2 and 3.

**Owning story:** **SC-1665**.

**Regression tests:** `ScreenCaptureServiceTest`; new round-trip assertions for
`toClientCoords`/`toClientBounds`; device-pixel-ratio and non-zero-origin cases.

## Boundary 3 — Recording capture

**Classes:** `web/recording/capture/WindowFrameCapture.java` (interface),
`ScreenManagerWindowFrameCapture.java`

**Upstream differences.** None — upstream has no equivalent abstraction; `69ff913` calls capture
directly.

**Contract:** Decision 5.

**Owning story:** **SC-1666**.

**Regression tests:** `RecordingConfigInitialFrameTest`,
`RecordingServiceInitialFrameReadinessTest`.

## Boundary 4 — Frame delivery

**Classes:** `utils/core/screen/FrameEvent.java`, `FrameListener.java`,
`web/recording/service/RecordingService.java`

**Upstream differences.** None — custom infrastructure.

**Contract:** Decision 5 (delivery semantics).

**Owning story:** **SC-1666**.

**Regression tests:** `RecordingControllerMetadataTest`,
`RecordingServiceInitialFrameReadinessTest`, `RecordingServiceAutoStartGateTest`.

## Boundary 5 — Input (the RemoteInput seam)

**Classes:** `utils/core/input/remoteinput/Kinput.java`,
`utils/core/input/mouse/VirtualMouseUtils.java`,
`utils/core/input/keyboard/VirtualKeyboardUtils.java`

**Upstream differences — the single largest divergence.** `69ff913` deletes `Kinput.java` and all
of `third_party/KInput/**`, then adds `RemoteInput.java`, `RemoteInputInterface.java`,
`ControlKey.java`, `MouseButton.java` plus a vendored `third-party/RemoteInput/**` tree, rewriting
`VirtualMouseUtils` (+348) and `VirtualKeyboardUtils` (+131).

**Contract:** Decision 4. Upstream's `Kinput.java` **deletion must not be taken literally** — the
baseline's three verified callers (`Controller.java`, `VirtualMouseUtils`, `VirtualKeyboardUtils`)
need a substitute interface first. The vendored tree must be reviewed before inclusion; it carries
`.xcodeproj/xcuserdata/**/UserInterfaceState.xcuserstate` and `.github/workflows/build.zip`.

**Owning stories:** **SC-1662** (native build/library loading), **SC-1664** (attachment and
mouse/keyboard lifecycle). RemoteInput implementation is **explicitly out of scope for SC-1661**.

**Regression tests:** input parity between the `Kinput` and RemoteInput paths; single-attachment
ownership; `destroy()` lifecycle.

## Boundary 6 — Builder Goal

**Classes:** `web/debug/buildergoal/service/BuilderGoalLauncherControlService.java`,
`Micro6ExecutorReadinessEvaluator.java`

**Upstream differences.** No upstream analogue; custom. The Fedora remote routing
(`97284f1` / `ced7487`) extends it and has been **carried** into this branch as `966e0e3`.

**Carried contract.** `isBuilderGoalRunnableMachine` accepts a machine when
`isLocalMachine(machine) || remoteLauncher.supports(machine)`; non-local machines must advertise
`builder-goal-launch-v2`; client filtering is by machine for remote launches; launch metadata
records `remote_micro6_agent`, `launcher_endpoint`, `agent_version`, `agent_capabilities`.
`Micro6OnlineClientRegistryService.belongsToMachine` compares `target.launchAttemptId()` (record
accessor) with `state.launchAttemptId` (**field** — `ClientState` is a plain class).

**Owning story:** **SC-1667** — Fedora startup and attachment diagnostics.

**Regression tests:** `BuilderGoalLauncherControlServiceTest`,
`Micro6ExecutorReadinessEvaluatorTest`, `Micro6RemoteLauncherServiceTest`,
`Micro6OnlineClientRegistryServiceTest` — all passing on this branch.

## Boundary 7 — Build

**File:** `build.gradle.kts` (plus `settings.gradle.kts` for `580a619`)

**Contract:** Decision 6.

**Owning story:** **SC-1662**.

**Regression tests:** `./gradlew tasks` enumerates the custom tasks; `replayTest` and
`spotlessCheck checkstyleMain` still resolve. Verified at the candidate baseline.

## Downstream responsibility summary

| Boundary | Owning story | Must preserve |
|---|---|---|
| Process identity / target selection | **SC-1663** | Neutral process identity; selection semantics; canvas-preference ranking |
| Capture + coordinates | **SC-1665** | `toClient*` as the only conversion seam; screen-space bounds |
| Recording capture | **SC-1666** | Thin `capture()`; policy stays in `RecordingService` |
| Frame delivery | **SC-1666** | Async per-listener delivery; ownership + timestamps |
| Input / RemoteInput | **SC-1662** (build) + **SC-1664** (lifecycle) | Single `(host, process, session)`-bound attachment; `destroy()` teardown |
| Builder Goal / Fedora routing | **SC-1667** | Host/client/session routing; carried `966e0e3` behaviour |
| Build + certification tasks | **SC-1662** | `replayTest`, certification tasks, native-library copy, quality-gate wiring, Java 17 toolchain |
| Host qualification | **SC-1668** | End-to-end integration and the integration PR |
