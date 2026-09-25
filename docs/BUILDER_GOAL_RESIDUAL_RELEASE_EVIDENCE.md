# Release Evidence: Unrelated Full-Suite Failure Groups

Date: 2026-08-08
Scope: Residual Gap Plan §6 — baseline evidence for full-suite failure groups that are
outside the Builder Goal patch but must not be hand-waved as "pre-existing".

## Historical Group 1 — Micro6 `:client:compileTestJava` failed on `-source 11`

This was a historical build-time failure, not a test-result failure. The two Java-11-incompatible
tests have since been rewritten and Micro6's JUnit Platform configuration has been completed in
the Micro6 repository. Native Windows verification is still required before this historical
waiver can be closed.

### Exact failing test class names

- `net.runelite.client.plugins.microbot.accountbuilder.integration.builder.Micro6BuilderExecutorServerTest`
- `net.runelite.client.plugins.openrunereplay.OpenRuneReplayClientTest`

### Exact failure messages

```
/.../Micro6BuilderExecutorServerTest.java:109: error: switch expressions are not supported in -source 11
    return switch (status.toUpperCase()) {
           ^
  (use -source 14 or higher to enable switch expressions)
/.../Micro6BuilderExecutorServerTest.java:110: error: multiple case labels are not supported in -source 11
      case "RECEIVED", "PENDING", "QUEUED", "ASSIGNED" -> 202;
                     ^
  (use -source 14 or higher to enable multiple case labels)
/.../Micro6BuilderExecutorServerTest.java:110: error: switch rules are not supported in -source 11
      case "RECEIVED", "PENDING", "QUEUED", "ASSIGNED" -> 202;
                                                       ^
  (use -source 14 or higher to enable switch rules)
/.../OpenRuneReplayClientTest.java:168: error: text blocks are not supported in -source 11
			String json = """
			              ^
  (use -source 15 or higher to enable text blocks)
```

Reproduced with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`:
`./gradlew --offline :client:compileTestJava`

The `-source 11` constraint comes from `common.settings.gradle.kts`:
`tasks.withType<JavaCompile> { options.encoding = "UTF-8"; options.release = 11 }`,
which is applied to every subproject including `:client`.

### Baseline

- `Micro6BuilderExecutorServerTest.java` committed at `7f080a6868` ("change to gradle").
- `OpenRuneReplayClientTest.java` committed at `5aa320e65f` ("change to gradle").
- Both files use Java 14+ syntax (switch expressions / text blocks) while the build
  compiles tests with `-source 11`; they cannot compile regardless of the Builder Goal
  patch. No Builder Goal change touches these files or the `common.settings.gradle.kts`
  source-level configuration, so the dependency graph/configuration is unaffected.

### Historical Waiver

This waiver is retained only as the pre-remediation baseline. The accepted remediation is to
keep the shared Java release at 11 and use Java-11-compatible test syntax; the Builder Goal
security tests must then run through normal Gradle test discovery.

### Prior independent verification of the new Micro6 Builder Goal tests

The new/updated Micro6 tests were compiled and executed directly with the resolved
`:client` test runtime classpath plus JUnit 5 / Mockito / Logback:

- `net.runelite.client.util.CommandLineArgumentSanitizerTest` — pass
- `net.runelite.client.RuneLiteSystemPropertyLoggingTest` — pass

Prior result: 19 tests, 0 failures. The log-capture assertions confirm both `RuneLite` and
`RuneLiteDebug` log `Setting property http.proxyPassword=********` (never the standalone
value) and keep ordinary properties visible. This standalone result remains supplemental;
the Windows runbook now requires normal `:client:test` execution.

## Other verification performed for this patch

- ChromaScape `compileJava compileTestJava` — pass (JDK 17 toolchain).
- ChromaScape Builder Goal suite — 268 tests, 0 failures (operation snapshots, coordinator,
  cancellation integration, recovery, controller sanitization, runner/preparation).
- Micro6 `:client:prepareBuilderGoal` — pass; compiles the client against the root composite's
  local `runelite-api`, `runelite-agent`, `runelite-jshell`, and Gradle plugin builds and
  validates `RuneLite.class`, `Micro6BuilderExecutorServer.class`, and the processed
  `runelite.properties` (required keys present, no `${...}` placeholders).
- Micro6 PowerShell suite `scripts/tests/launch-buildergoal-client.tests.ps1` — 41 passed,
  0 failed, including real `BUILDER_GOAL_COMPILE_ONLY=true` invocation (exits 0, never
  starts Java, payload removed on success and on preparation failure).
- Micro6 Gradle task suite `scripts/tests/buildergoal-gradle.tasks.tests.ps1` — the root
  composite exposes `client:prepareBuilderGoal` and `client:runBuilderGoal`; `runBuilderGoal`
  is a `JavaExec` with main class `net.runelite.client.RuneLite`, the Micro6 root working
  directory, the IntelliJ-equivalent JVM arguments, and the inherited executor environment.

Browser suite: `node --test src/test/js/account-task-planner.test.js` — 92 passed, 0 failed.
The bundle-37 browser coverage includes the canonical `{task_key, settings}` editor envelope,
legacy raw-settings compatibility, task-selector synchronization, outbound nested-settings
payloads, and stale `/config/last` response protection.

Micro6 normal Gradle test execution and live acceptance remain Windows-host gates. The
Micro6-specific procedure is [RELEASE_VERIFICATION_RUNBOOK.md](/mnt/c/Dev/IdeaProjects/micro6/docs/RELEASE_VERIFICATION_RUNBOOK.md);
the ChromaScape live procedure is [CHROMASCAPE_RESTART_RUNBOOK.md](/mnt/c/Dev/IdeaProjects/ChromaScape/docs/CHROMASCAPE_RESTART_RUNBOOK.md).
Release verification runs on the Windows host via the native Micro6 `gradlew.bat` wrapper;
a cold WSL build on `/mnt/c` is not used.
