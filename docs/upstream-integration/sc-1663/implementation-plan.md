# SC-1663 — Linux process discovery and explicit RuneLite/Micro6 target selection: implementation specification

> Frozen pre-implementation planning record.
> Current implementation and verification state is recorded in implementation-evidence.md.
> Unchecked historical checklist items are not current-state claims.

Status: planned only; no production implementation or SC-1663 test pass is claimed.
Story: https://app.shortcut.com/leeuwbot/story/1663
Canonical project: C:\Dev\IdeaProjects\ChromaScape
Classification: change:runtime. Intended workflow: shortcut_delivery.
Branch: codex/sc-1663-linux-process-discovery.
Parent: SC-1662 delivery (`codex/sc-1662-linux-remoteinput`, head `be4230c` plus its uncommitted
implementation in the ChromaScape-sc1662-native worktree). Rebase onto the merged integration head
before opening the PR.
Pinned source: StaticSweep/ChromaScape `5ea3b995f5d13e19c592a6399973fe7dadb5e656`.
Reconciled production baseline: `966e0e3e47db71fe5d57629205efcb23cd11e026`.
Compatibility contract: `docs/upstream-integration/sc-1661/compatibility-map.md` Decision 1,
Boundary 1 and the downstream responsibility table.

## Prerequisite closure and planning record

SC-1661 is reviewed, merged (PRs #3/#4) and Done in Shortcut. SC-1662 is the immediate predecessor:
its plan (`docs/upstream-integration/sc-1662/implementation-plan.md`) is committed; its native
build, loader and development smoke implementation currently live uncommitted in the
`ChromaScape-sc1662-native` worktree, and the story is still In Progress in Shortcut. SC-1663's hard
prerequisite (SC-1661) is satisfied; its delivery sequence requires SC-1662's commits to land or at
minimum be stacked on before the SC-1663 PR, because both stories touch
`docs/upstream-integration/**` and this plan references SC-1662 artifacts.

Felix task lookup for `shortcut:sc-1663` and the canonical project identity
`C:\Dev\IdeaProjects\ChromaScape` must be resolved at implementation time. The SC-1662 and SC-1661
records both failed canonical project resolution on the Fedora server
(`Project not found: C:\Dev\IdeaProjects\ChromaScape`). Do not create a duplicate POSIX identity or
bypass validation; persist scope, checklists and rule links through real gates, and record the
outcome honestly. This document is a committed plan, not a claim of Felix persistence, state
transition or synchronization.

## Outcome and exclusions

Replace the upstream first-match `/proc` lookup and the fork's Windows-only selection identity with
a platform-neutral, deterministic process-target model: enumerate eligible local RuneLite/Micro6
clients on Linux, bind each to a stable process identity, and resolve an explicit selection that is
revalidated before any consumer may use it. Preserve the Windows selection, ranking and capture
behavior unchanged.

Out of scope:
- injection, pairing, focus, mouse/keyboard lifecycle and teardown — SC-1664;
- pixel-buffer capture and screen-consumer migration — SC-1665;
- recorder/debugger integration — SC-1666;
- Fedora startup/attachment preflight and Builder Goal routing changes — SC-1667;
- host qualification and the integration PR — SC-1668; SC-1696/SC-1697 follow-ups;
- native build, staging, manifest, licence and distribution work — SC-1662 owns those and this story
  adds no native task;
- X11/Wayland window enumeration or window-title identity (upstream capture is driven by the
  RemoteInput canvas buffer, not a window handle);
- any attachment, `dlopen`, EIOS call or client launch;
- new dependencies, JDK/Gradle changes, network protocols, macOS/ARM qualification, sandbox/ptrace
  policy, setcap or root execution.

## Evidence-backed starting points

Read before changes:

- `docs/upstream-integration/sc-1661/compatibility-map.md` — Decision 1 (neutral process identity),
  Boundary 1 (selection classes), and the downstream responsibility table. This story owns both the
  discovery port and selection preservation.
- `docs/upstream-integration/sc-1662/native-loading-contract.md` — loading is separated from
  attachment, and `dlopen` of `libRemoteInput.so` is **not side-effect free** (load-time
  constructors self-instrument the loading process). Discovery must therefore never load, reference
  or initialize the RemoteInput library. Attachment is SC-1664.
- Pinned upstream `5ea3b99`:
  - `utils/core/screen/window/ProcessManager.java` (`int getPid()`),
  - `LinuxProcessManager.java` (first `cmdline.contains("net.runelite.client.RuneLite")` match,
    returns `-1`),
  - `ProcessManagerFactory.java` (OS-name switch), `WindowsProcessManager.java`,
    `MacProcessManager.java` (returns `-1`),
  - `utils/core/input/remoteinput/RemoteInput.java` — the `RemoteInput(int pid)` constructor injects
    and pairs immediately; nothing in this story may construct it.
- Fork baseline (verified against the SC-1662 branch worktree):
  - `WindowSelectionService.java` — `AtomicReference<WindowInfo>`, `isSelected` uses **reference
    equality**, `getSelectedHwnd()` leaks `HWND`; no identity, no generation, no validation.
  - `WindowInfo.java` — `HWND` required in the constructor; `pid` present; no neutral identity.
  - `WindowHandler.java` — Win32-only enumeration with canvas-preference ranking
    (`CaptureSignature`, `WindowCandidate`, `selectBestWindow`, `deduplicateWindowsByPid`). This
    ranking and the Windows behavior must survive.
  - `WindowController.java` (HTTP `/api/windows`), `WindowDebugController.java:143`
    (`getAllTargetWindows`), `ScreenManager.java:84-96` (selection → `getSelectedHwnd`),
    `RecordingController.java:95` (`getSelectedWindow().getAccountName()`).
  - `Controller.java:65` — `new Kinput(WindowHandler.getPid(WindowHandler.getTargetWindow()))`; the
    three verified `Kinput` callers are SC-1664's, but this is why a neutral PID identity is needed
    rather than an `HWND`.
  - `Micro6TargetRelaunchService.java` — the fork already implements the exact PID-reuse pattern
    this story should reuse: `processStartTime(pid)` via `ProcessHandle.info().startInstant()`
    (around line 1467), `matchesBuilderGoalCommandSignature(pid, projectDir)` checking
    `commandLine.contains(projectPath)` and
    `commandLine.contains("net.runelite.client.RuneLite") || commandLine.contains("runBuilderGoal")`
    (around line 1495), and `buildLaunchCommand` (line 2043) whose `NON_WINDOWS_GRADLE` branch is
    `bash -lc "./gradlew :client:runBuilderGoal '--args=…'"` (line 2083).
  - `Micro6OnlineClientRegistryService.java:72,271` — the remote executor heartbeat carries
    `process_id`; that number is a **remote** PID and must never be treated as a local target.
  - `application.yml:174` / `.env.example:3` —
    `builder-goal.launcher.local-machine-uuid` (`BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID`) is the
    available local-host identity.
- Observed host state (2026-09-17): a Micro6 remote-launcher agent
  (`micro6/scripts/remote-launcher/agent.py`) is running on the Fedora host; no RuneLite/Micro6
  client was running during this planning pass. Real command lines must be captured at
  implementation time (Phase 1) before the matching rules are frozen.

## The exact gap

Today there is no neutral target identity anywhere below the UI. `WindowInfo` requires an `HWND`,
`WindowSelectionService.getSelectedHwnd()` cannot exist on Linux, `isSelected()` compares object
references (so a freshly enumerated but logically identical window is never "selected"), and the
upstream Linux implementation returns whichever `/proc/<pid>/cmdline` contains the RuneLite main
class first, or `-1`. On Linux the existing debugger endpoints (`/api/windows`, debug window
endpoints) would initialize Win32/JNA and fail.

Decision 1 requires, for this story:

1. a platform-neutral process identity with a pid **and** a start-time/boot-id component so a
   recycled PID cannot silently retarget a selection;
2. `WindowInfo` remains the user-facing selection value, but no longer requires an `HWND` in its
   neutral form; `getSelectedHwnd()` becomes a Windows-only adapter accessor;
3. platform code resolves the neutral identity to a platform surface later (SC-1665 for capture);
   selection semantics (`getSelectedWindow`, `isSelected`, `hasSelection`, `clearSelection`) and the
   Windows canvas-preference ranking are preserved.

## Architecture and proposed interfaces

Names below are proposals; exact final names must be recorded if they change. All new code lives in
`com.chromascape.utils.core.screen.window` unless stated otherwise.

### 1. Neutral process identity

```java
enum ProcessOrigin { LOCAL, REMOTE }

record ProcessIdentity(
    ProcessOrigin origin,   // discovery always yields LOCAL
    String hostId,          // builder-goal.launcher.local-machine-uuid or "" when unset
    long pid,
    Instant startTime,      // Linux: /proc/<pid>/stat field 22 converted; cross-checked with JDK
    String bootId)          // /proc/sys/kernel/random/boot_id, "" when unavailable
```

- Equality includes origin + pid + startTime + bootId; `hostId` is metadata for the session binding
  and is not part of identity equality (it can be empty in development).
- `isLocal()` is `origin == LOCAL`.
- Linux identity construction reads `/proc/<pid>/stat` field 22 (starttime ticks) and the boot id;
  `ProcessHandle.of(pid).info().startInstant()` is recorded alongside as a cross-check. Ticks are
  converted with the host clock tick rate; if the rate cannot be determined, the raw ticks and boot
  id still form a stable tuple and the JDK start time is used for display/ordering.
- The identity is self-describing and serialisable for diagnostics; it must never contain command
  lines, account names, credentials or other process content.

### 2. Discovery seam (`ProcessManager` adaptation)

```java
interface ProcessManager {
  List<DiscoveredTarget> discoverEligibleLocal();
  Optional<ProcessIdentity> resolveLocal(long pid);          // read-only re-read of /proc
  default Optional<DiscoveredTarget> findLocal(ProcessIdentity identity); // validation helper
}
```

- The upstream `int getPid()` contract is deliberately **not** preserved: it is the first-match
  behavior this story replaces. No caller in the fork currently uses `ProcessManager`, so there is
  no compatibility burden; `ProcessManagerFactory` becomes the platform switch that returns the
  Linux, Windows or unsupported implementation lazily.
- `DiscoveredTarget` carries `ProcessIdentity identity`, `WindowInfo window`, `String source`
  (`"process"` on Linux, the existing `"supported-client"`/`"java-fallback"` on Windows) and a
  `selectionReason`. It never becomes an attachment.
- `WindowsProcessManager` wraps the existing `WindowHandler` enumeration (keeping canvas ranking and
  sequential ids) and enriches each `WindowInfo` with a `ProcessIdentity` built from
  `ProcessHandle` start time. Windows keeps today's auto-detection/ranking; Linux adds the
  fail-closed ambiguity rule below.
- `UnsupportedProcessManager` (macOS/unknown) returns an empty list and an
  `UNSUPPORTED_PLATFORM` diagnostic instead of throwing at startup. `MacProcessManager` either
  delegates to it or is removed from the factory's reachable graph.
- `ProcessManagerFactory.getProcessManager()` is the only platform switch. On Linux it must not
  initialize or reference `com.sun.jna.platform.win32.*` (see isolation tests).

### 3. Linux `/proc` adapter

```java
final class ProcFs {                       // injectable root, defaults to /proc
  List<Long> listPidDirs();                // non-throwing; bounded
  Optional<byte[]> readCmdline(long pid);  // missing/unreadable -> empty
  Optional<long[]> readStartTimeTicksAndUptime(long pid); // pid gone -> empty
  Optional<String> readBootId();           // /proc/sys/kernel/random/boot_id
}
```

- A synthetic root (temp directory fixture) is injected in tests; production uses `/proc`.
- Every per-entry failure (process exits between listing and read, malformed stat, permission
  denied, empty cmdline) is skipped with a counted, bounded diagnostic; the scan never throws and
  never aborts on one bad entry.
- Command lines are **never logged, returned in diagnostics, or written to evidence**. They can
  contain account credentials (Micro6 client args carry account fields), so the matcher is a pure
  predicate over an in-memory string and drops it immediately.

### 4. Eligibility and matching rules (finalized by Phase 1 mapping)

Provisional rule set for Linux, all conditions required:

1. executable is a Java runtime (`/proc/<pid>/exe` or argv[0] basename `java`; bundled-JRE paths
   accepted);
2. command line contains `net.runelite.client.RuneLite` **or** contains `runBuilderGoal`;
3. for the `runBuilderGoal` variant, either the command line contains the configured Micro6 project
   root (`builder-goal.micro6.project-dir`) or the client main class is present — the exact
   discriminator is fixed after Phase 1 mapping of real command lines;
4. the process is not the ChromaScape JVM itself (self pid excluded);
5. Gradle daemon/worker processes (`org.gradle.launcher.daemon`, `gradle-daemon-main`,
   `GradleDaemon`) and the remote-launcher agent (`remote-launcher/agent.py`, `python`) are never
   eligible — a `runBuilderGoal` string can appear in a Gradle command line without a live client.

Rules are deterministic and order-independent: candidates are sorted by `(startTime, pid)`.
Multiple eligible candidates produce an **ambiguity diagnostic**, not a first-match:
`resolveSelectedTarget()` refuses to return a target unless exactly one candidate exists or an
explicit selection matches one identity. This is the acceptance-criterion answer to "multiple
clients cannot cause silent attachment to the first process". (Windows keeps its existing ranking
and auto-detection for compatibility; the Linux pipeline is explicitly stricter.)

### 5. Selection, host/session binding and revalidation

```java
record TargetSelection(WindowInfo window, ProcessIdentity identity, long generation) {}

enum SelectionValidation {
  VALID, NONE, STALE_GENERATION, EXITED, REPLACED, NOT_LOCAL, UNSUPPORTED_PLATFORM
}
```

`WindowSelectionService` changes:

- selection becomes an `AtomicReference<TargetSelection>`; `setSelectedWindow`/`select` increments
  the generation; `clearSelection` increments and clears;
- `getSelectedWindow()`, `hasSelection()`, `getSelectedHwnd()` (Windows adapter; `null` elsewhere)
  and `clearSelection()` keep their signatures;
- `isSelected(WindowInfo)` compares the neutral identity first, falling back to the reference
  comparison only when neither side has an identity (fixes the reference-equality defect without
  changing the HTTP contract);
- new: `getSelectedIdentity()`, `getSelectedGeneration()`, `validateSelected(ProcessManager)` —
  the validation re-reads `/proc` (or Windows process state) and returns `EXITED` when the process
  is gone, `REPLACED` when the pid exists with a different start time/boot id, `NOT_LOCAL` when the
  identity's origin is `REMOTE`, `STALE_GENERATION` when the caller presents an older generation;
- validation is read-only: it never attaches, injects, focuses or kills. SC-1664 consumes
  `VALID` + a matching generation as the precondition for its one-attachment lifecycle.

`WindowInfo` gains a nullable `processIdentity` field and a constructor overload; the existing
constructor remains and derives `null` identity, so all current construction sites and
`WindowHandlerTest` continue to compile and pass. `getPid()` keeps returning the identity pid when
present, or the stored pid otherwise, so `RecordingController` and `WindowDebugController` are
unaffected.

### 6. Consumer facade

A Spring `TargetDiscoveryService` (thin, injectable) is the single read path for
`WindowController` and `WindowDebugController`:

- Windows: delegates to `WindowHandler.getUiWindowCandidates()`/`getAllTargetWindows()` exactly as
  today, preserving the JSON shape and sequential ids;
- Linux: builds candidates from `ProcessManager.discoverEligibleLocal()`, with
  `id = "linux:" + pid + ":" + startTicks`, `title` left null or set to the process label,
  `accountName` null, `bounds` null, `hwnd` null, plus `source`, `fallback=false`, `captureable`
  (SC-1665 decides actual capturability) and `selectionReason`;
- both controllers return the same field names; `hwnd` is already serialised as a string only in the
  debug endpoint and becomes null on Linux.

`ScreenManager` is not rewritten in this story: it continues to call `getSelectedHwnd()` on
Windows. The neutral identity is what SC-1665 will resolve to a capture surface.

### 7. Diagnostics

`TargetSelectionDiagnostic` (or an extension of the existing diagnostic vocabulary): `NO_ELIGIBLE_TARGET`,
`AMBIGUOUS_TARGET`, `PROC_ENTRY_UNREADABLE` (counted), `UNSUPPORTED_PLATFORM`, `TARGET_EXITED`,
`TARGET_REPLACED`, `TARGET_NOT_LOCAL`, `STALE_SELECTION`. Every diagnostic carries the pid, origin,
host id (when set), expected vs observed start time (when known) and the bounded cause. No
diagnostic carries command lines, credentials, account identity or file contents.

### 8. Isolation guarantees

- Discovery never references `com.chromascape.utils.core.input.remoteinput.*`; a compiled-class scan
  test asserts that `LinuxProcessManager`, `ProcFs`, `ProcessIdentity` and `TargetDiscoveryService`
  have no constant-pool reference to `RemoteInput`, `RemoteInputLibraryLoader`, the smoke verifier or
  any `EIOS_*` symbol. This is the machine-checked form of "no attach" for this story.
- On Linux, no `Win32`/JNA `user32` class is initialized by discovery or by the controllers: a test
  runs discovery with an empty `jna.library.path` (Win32 loads would throw) and a compiled-class
  scan asserts the Linux path does not reference `WindowHandler`/`WindowsProcessManager`.
- No static initializer in the new types performs I/O; the classes do no work until called, so
  ordinary application startup on a machine with no clients and no native artifact is unaffected.

## Implementation sequence and files

### Phase 0 — branch and mapping prerequisites
- [ ] Confirm SC-1662 commits are present in the base or stack this branch on them; record the
      actual base commit in the PR.
- [ ] Resolve the Felix `shortcut:sc-1663` task under the canonical project identity, or record the
      same resolution failure honestly.

### Phase 1 — map real identities (evidence before rules)
- [ ] Launch one Micro6 client on Fedora via the documented path
      (`./gradlew :client:runBuilderGoal`) and one alternate variant available on the host (direct
      `java -jar` RuneLite or bundled-JRE launcher), then capture sanitized structural evidence:
      executable basename, presence of `net.runelite.client.RuneLite`/`runBuilderGoal`, project-path
      presence, pid, start time, parent process — **not raw command lines**.
- [ ] Confirm which process actually renders/owns the client (Gradle forks a JVM; the daemon is not
      the client) and record the rule that distinguishes them.
- [ ] Write `docs/upstream-integration/sc-1663/process-identity-mapping.md` with the sanitized
      observations, the chosen matching rules and the rejected variants.
- [ ] Freeze the eligibility rule set (Section 4) from that evidence.

### Phase 2 — neutral identity and Linux discovery
- [ ] Add `ProcessIdentity`, `ProcessOrigin`, `DiscoveredTarget`.
- [ ] Add `ProcFs` with an injectable root and non-throwing reads.
- [ ] Rewrite `LinuxProcessManager` to enumerate `/proc`, apply the frozen rules, sort
      deterministically and return `DiscoveredTarget` values.
- [ ] Adapt `ProcessManager` (Section 2) and `ProcessManagerFactory`; add
      `UnsupportedProcessManager`; keep `WindowsProcessManager` behavior and enrich it with
      identities.
- [ ] Add `TargetSelectionDiagnostic` codes and bounded, sanitized reporting.

### Phase 3 — selection, generation and revalidation
- [ ] Convert `WindowSelectionService` to `TargetSelection` with generation, identity-aware
      `isSelected`, `getSelectedHwnd` Windows adapter, `getSelectedIdentity`,
      `getSelectedGeneration` and `validateSelected`.
- [ ] Extend `WindowInfo` with the optional identity while preserving the existing constructor.
- [ ] Add `SelectionValidation` and the read-only validation logic for Linux and Windows.

### Phase 4 — consumer facade and Windows compatibility
- [ ] Add `TargetDiscoveryService`; switch `WindowController` and `WindowDebugController` to it
      without changing response field names, id round-tripping or status codes.
- [ ] Verify `WindowHandlerTest` and `WindowControllerTest` pass unchanged; add neutral-identity
      selection tests and the Linux ambiguity/refusal tests.
- [ ] Confirm `ScreenManager`, `RecordingController` and `Controller` compile untouched (their
      current calls must keep working, `Controller` remaining Windows-only until SC-1664 replaces
      `Kinput`).

### Phase 5 — evidence, docs and PR
- [ ] Add `docs/upstream-integration/sc-1663/implementation-evidence.md` with the strict
      PASS/FAIL/NOT RUN/BLOCKED vocabulary used by SC-1662, listing changed files, executed
      commands, UTC times, exits, counts, revisions and checksums.
- [ ] Retain JUnit XML and logs under `docs/upstream-integration/sc-1663/evidence/` with an
      `index.md` and per-file SHA-256; redact and hash any host-derived evidence.
- [ ] Run the focused tests, the six launcher suites (per the SC-1661 procedure), `replayTest` and
      the ordinary suite; compare failures by identity, not totals, against the recorded baselines
      (110 ordinary / 12 replay failures, 13 skips, 16 Spotless files; focused baseline 175
      passing).
- [ ] Run Spotless/Checkstyle on changed files only; avoid unrelated format churn.
- [ ] Open one story PR against `integration/chromascape-consolidated-20260901` (or the then-current
      integration head) with the evidence index, and record the Windows verification result or an
      explicit unverified note — never PASS unexecuted.

## Acceptance and test specification

1. Given zero eligible local clients, when discovery runs, then the result is a deterministic empty
   list with `NO_ELIGIBLE_TARGET`, no exception, and zero native/attachment calls.
2. Given exactly one eligible local client, when discovery runs, then its identity is reported
   deterministically (same pid/start time/boot id on repeated runs of the same process).
3. Given several eligible local clients, when a consumer asks for a target without an explicit
   selection, then resolution fails closed with `AMBIGUOUS_TARGET`; when an explicit selection names
   one identity, then exactly that identity is resolved.
4. Given a selected process that exits, when validation runs, then `EXITED` is returned and the
   stale selection cannot be used.
5. Given a selected PID reused by a different process, when validation runs, then `REPLACED` is
   returned from the start-time/boot-id mismatch and the stale selection cannot be used.
6. Given unreadable, disappearing or malformed `/proc` entries, when discovery runs, then failures
   are counted and bounded, remaining candidates are still returned, and no Win32 class is
   initialized on Linux; on an unsupported platform, `UNSUPPORTED_PLATFORM` is returned without
   throwing.
7. Given a remote launcher PID equal to a local pid, when local resolution is requested, then the
   remote-origin identity is refused with `NOT_LOCAL`; remote `process_id` values from
   `Micro6OnlineClientRegistryService` never enter the local resolution path.
8. Given the Windows build, when windows are enumerated and selected, then `WindowHandlerTest`,
   `WindowControllerTest` and the existing ranking/auto-detection behavior pass unchanged, and
   `getSelectedHwnd()` still resolves the selected window.

Test execution checklist (all currently unrun for SC-1663):
- [ ] `ProcessIdentityTest` — equality, origin, local/remote, start-time semantics.
- [ ] `ProcFsTest` — synthetic root: missing dir, missing cmdline, unreadable entry, malformed stat,
      boot id present/absent, disappearing pid.
- [ ] `LinuxProcessManagerTest` — single/multiple/zero candidates, deterministic order, rule
      variants (gradle daemon, python agent, unrelated java, self), no-throw on unreadable entries.
- [ ] `SelectionValidationTest` — VALID/EXITED/REPLACED/NOT_LOCAL/STALE_GENERATION with synthetic
      procfs and a fake clock.
- [ ] `WindowSelectionServiceTest` — identity-aware `isSelected`, generation increments, clear,
      hwnd adapter null on neutral identity.
- [ ] `ProcessManagerFactoryTest` + compiled-class isolation scans (no Win32 on the Linux path, no
      RemoteInput/EIOS reference in discovery classes).
- [ ] `TargetDiscoveryServiceTest`/`WindowControllerTest` — HTTP contract unchanged on Windows;
      Linux candidates shape asserted; ambiguity refusal surfaced.
- [ ] Real Fedora mapping run and live discovery against a running Micro6 client (Phase 1), with
      sanitized evidence.
- [ ] Focused suite plus six launcher suites; `replayTest` and ordinary test results compared by
      failure identity.
- [ ] Spotless/Checkstyle changed-file delta versus the recorded baseline.
- [ ] Windows packaging/selection regression on a Windows host; absent host is unverified, never
      PASS.

## Delivery, estimate and rollback

Plan commits: (1) mapping evidence and neutral identity types, (2) `/proc` discovery and platform
factory isolation, (3) selection generation, validation and diagnostics, (4) consumer facade and
Windows compatibility, (5) tests, evidence and documentation. Open a dedicated PR; do not include
SC-1664+ work. Recheck target movement before starting coding and record the actual base commit.

Provisional effort: 2–3 focused engineering days plus Fedora host access for the Phase 1 mapping and
the live discovery run, and reviewer availability. The main uncertainty is the real Micro6 launch
command line on Fedora (Gradle-forked JVM versus direct java), which Phase 1 resolves before rules
are frozen. This is not an estimate for the remaining epic.

Rollback: revert the isolated story commits; Windows selection/capture paths are untouched, no
native build or distribution change is made, and no DB migration or server API contract changes.
`Kinput` remains intact until SC-1664 replaces it.

## Appendix A — launch variants to map on the Fedora host

| Variant | How it starts | What the matcher must distinguish |
|---|---|---|
| Micro6 via Gradle (`NON_WINDOWS_GRADLE`) | `bash -lc "./gradlew :client:runBuilderGoal '--args=…'"` | Gradle daemon/worker lines also contain `runBuilderGoal`; only the forked JVM with the Micro6 classpath is the client |
| Direct RuneLite jar | `java -jar …/RuneLite.jar` (system or bundled JRE) | main class may be `net.runelite.client.RuneLite` on the command line or inside the jar manifest |
| ChromaScape JVM | `bootRun` / application jar | self pid must be excluded even though it may reference the same project tree |
| Remote agent | `python3 …/remote-launcher/agent.py` | never eligible; not a client |
| Browser/IDE java | IDE test JVMs | executable/path exclusions mirroring `ProcessVerificationUtil`'s Windows exclusions |

All captured evidence is structural (executable, marker presence, pid, start time, parent), never
raw command lines, because Micro6 client args can carry account credentials.

## Appendix B — open decisions to settle during implementation

- Exact Linux eligibility discriminator for `runBuilderGoal` (project-root match versus classpath
  match) — frozen from Phase 1 evidence.
- Whether `WindowInfo.title` on Linux stays null or receives a non-identity label (must not imply
  account identity that was not proven).
- Whether `MacProcessManager` is deleted or delegates to `UnsupportedProcessManager` (Decision 1
  only requires that unsupported platforms fail bounded and diagnostic).
- Stable Linux id format (`linux:<pid>:<startTicks>`) once tested against the debugger UI.
- Whether the selection generation is exposed through any HTTP endpoint or reserved for SC-1664's
  internal lifecycle (no API change is planned in this story).
