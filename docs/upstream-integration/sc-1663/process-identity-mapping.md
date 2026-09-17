# SC-1663 — process identity mapping evidence and frozen matching rules

Status: exclusion rules evidenced live on the Fedora host; client-side identity capture NOT RUN (no
eligible client was running and no client was launched in this pass).

Story: https://app.shortcut.com/leeuwbot/story/1663
Plan: `docs/upstream-integration/sc-1663/implementation-plan.md`
Evidence collected: 2026-09-16 (UTC), Fedora host, read-only `/proc` scan.

Recorded method: for every numeric `/proc/<pid>` entry the executable basename
(`readlink /proc/<pid>/exe`), marker booleans evaluated over the in-memory command line
(`net.runelite.client.RuneLite`, `runBuilderGoal`, Gradle daemon/worker markers,
`remote-launcher`/`agent.py`) and the `stat` parent pid were recorded. Raw command lines were never
printed, stored or included in this document, because Micro6 client arguments can carry account
credentials.

## Live observations

| pid | exe basename | runelite main class | runBuilderGoal | gradle daemon/worker | remote agent | ppid |
|---|---|---|---|---|---|---|
| 1484351 | java | NO | NO | YES | NO | 1409 |
| 1499 | python3.14 | NO | NO | NO | YES | 1409 |
| 1618433 | bash | YES | YES | YES | YES | 1571638 |

Additional structural observations:

- Ten transient `/proc` entries disappeared between directory listing and `cmdline` read during the
  scan (observed as ENOENT). This is live confirmation for the bounded, counted skip behavior:
  per-entry failures are skipped and counted (`PROC_ENTRY_UNREADABLE`), never fatal.
- The bash entry (pid 1618433) is the
  `bash -lc "./gradlew :client:runBuilderGoal '--args=…'"` wrapper from
  `Micro6TargetRelaunchService.buildLaunchCommand` (the `NON_WINDOWS_GRADLE` branch). Its command
  line carries BOTH `runBuilderGoal` AND `net.runelite.client.RuneLite` (the wrapper echoes the
  full Gradle invocation). A matcher relying on command-line markers alone would classify this
  wrapper as the client. Rule 1 (java executable) is what excludes it — evidenced, not
  hypothetical.
- The Gradle daemon JVM (pid 1484351) is a java process carrying Gradle daemon markers and no
  client markers on this host; rule 5 excludes it even when a `runBuilderGoal` string appears in a
  daemon command line (Appendix A case).
- The remote-launcher agent (pid 1499) runs under `python3` (not `python`); the exclusion therefore
  matches the `python` executable-name prefix plus the `remote-launcher`/`agent.py` markers, not an
  exact binary name.
- No eligible client JVM was running during the scan: no java process carried the RuneLite main
  class or `runBuilderGoal` markers outside the bash wrapper and daemon cases above.

## Client-side identity capture — NOT RUN

The plan's Phase 1 calls for launching one Micro6 client via `./gradlew :client:runBuilderGoal` and
one alternate variant (direct `java -jar`), then recording the forked client JVM's structural
identity and which process renders. No client was launched in this pass: launching the game client
is an operator action on this host (interactive display, account credentials) and was not
available. The rule set below is frozen from the live exclusion evidence plus the verified fork
patterns in `Micro6TargetRelaunchService.matchesBuilderGoalCommandSignature` (project path + entry
point) and `buildLaunchCommand` (`NON_WINDOWS_GRADLE`). The client-side half of the mapping (exact
forked JVM command-line shape, rendering process confirmation) remains open and must be completed
by the first live discovery run against a real client before SC-1664 consumes this identity for
attachment.

## Frozen rule set (Linux)

All conditions required, evaluated in order; first failure excludes the pid:

1. executable is a Java runtime: basename of the `/proc/<pid>/exe` symlink target (bundled JRE
   paths accepted) — falling back to the `argv[0]` basename when the symlink is unreadable — is
   `java` or `javaw`. Excludes the bash wrapper, the python agent and IDE tooling.
2. command line contains `net.runelite.client.RuneLite` OR contains `runBuilderGoal`.
3. for a `runBuilderGoal` command line without the RuneLite main class, the command line must
   contain the configured Micro6 project root
   (`builder-goal.recovery.micro6-project-dir`, default `../micro6`, matched as the absolute
   normalized path and its forward-slash variant, mirroring
   `Micro6TargetRelaunchService.matchesBuilderGoalCommandSignature`). A command line carrying the
   RuneLite main class passes regardless. (The plan proposed the property name
   `builder-goal.micro6.project-dir`; the real fork property is
   `builder-goal.recovery.micro6-project-dir` — recorded as a name deviation.)
4. the process is not the ChromaScape JVM itself (`ProcessHandle.current().pid()` excluded).
5. never eligible regardless of other matches: command lines carrying Gradle daemon/worker markers
   (`org.gradle.launcher.daemon`, `gradle-daemon-main`, `GradleDaemon`, `gradle-worker`), and the
   remote-launcher agent (executable basename starting with `python` or command line containing
   `remote-launcher`).

Candidates are sorted by (process start time, pid); discovery returns every eligible candidate and
never a first match. Multiple candidates require an explicit selection or produce
`AMBIGUOUS_TARGET` at resolution (`TargetDiscoveryService.resolveSelectedTarget`).

## Rejected variants

- Upstream first-match `getPid()` (`LinuxProcessManager` at pinned `5ea3b99`): returns the first
  `/proc/<pid>/cmdline` containing the RuneLite main class, or `-1`. Rejected: silent first-match
  with multiple clients is exactly the defect this story removes.
- Command-line-marker-only matching: rejected by live evidence — the bash Gradle wrapper carries
  every marker.
- Window-title identity on Linux: rejected — no Win32 window handle exists below the UI on Linux,
  and window enumeration is out of scope (SC-1665 owns capture surfaces).
- Remote `process_id` from `Micro6OnlineClientRegistryService` heartbeats: rejected — remote PIDs
  never enter the local resolution path; remote-origin identities are refused with `NOT_LOCAL`.

## Resolved open decisions (plan Appendix B)

- Discriminator for `runBuilderGoal`: project-root match (rule 3), with RuneLite-main-class
  presence overriding it; live client-side confirmation pending (see NOT RUN above).
- `WindowInfo.title` on Linux: `null` — no unproven label is implied.
- `MacProcessManager`: not carried; `ProcessManagerFactory` routes unsupported platforms to
  `UnsupportedProcessManager` (empty results plus `UNSUPPORTED_PLATFORM` diagnostic).
- Stable Linux candidate id format: `linux:<pid>:<startTicks>` (start ticks from
  `/proc/<pid>/stat` field 22), surfaced only through the debugger/`/api/windows` candidate ids.
- Selection generation: internal to `WindowSelectionService`; no HTTP field is added in this story.
