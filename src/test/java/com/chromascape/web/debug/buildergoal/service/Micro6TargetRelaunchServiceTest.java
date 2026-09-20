package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService.TargetClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class Micro6TargetRelaunchServiceTest {

  /**
   * Test subclass that pins the platform so launch-mode resolution is deterministic regardless of
   * the machine the suite runs on.
   */
  static class ControlledPlatformService extends Micro6TargetRelaunchService {
    private final boolean windows;
    private final boolean structuredWindowsLaunch;

    ControlledPlatformService(
        boolean windows,
        boolean structuredWindowsLaunch,
        String projectDir,
        String launchCommandTemplate,
        String extraRunArgs) {
      this(
          windows,
          structuredWindowsLaunch,
          projectDir,
          launchCommandTemplate,
          extraRunArgs,
          "http://127.0.0.1:8098");
    }

    ControlledPlatformService(
        boolean windows,
        boolean structuredWindowsLaunch,
        String projectDir,
        String launchCommandTemplate,
        String extraRunArgs,
        String defaultExecutorBaseUrl) {
      super(
          new ObjectMapper(),
          projectDir,
          "",
          launchCommandTemplate,
          extraRunArgs,
          360,
          180_000L,
          2_000L,
          "micro6-recovery-launch.log",
          "http://localhost:8090",
          defaultExecutorBaseUrl,
          "http://localhost:8086");
      this.windows = windows;
      this.structuredWindowsLaunch = structuredWindowsLaunch;
    }

    @Override
    protected boolean isWindows() {
      return windows;
    }

    @Override
    protected boolean useStructuredWindowsLaunch() {
      return structuredWindowsLaunch;
    }
  }

  /**
   * Programmable health-check service pinned to a high, non-default executor port (18998) so the
   * integrated cancellation tests can never race with a live local Micro6 executor or kill an
   * arbitrary port owner via the pre-launch cleanup.
   */
  static class ProgrammableHealthService extends ControlledPlatformService {
    volatile boolean interruptOnHealthCheck;
    volatile boolean readyOnNextHealthCheck;
    final AtomicBoolean readyObserved = new AtomicBoolean(false);

    ProgrammableHealthService(String projectDir) {
      super(false, false, projectDir, "", "", "http://127.0.0.1:18998");
    }

    @Override
    protected HealthCheckResult readHealthyExecutorTarget(
        String baseUrl,
        String expectedAccountHash,
        String expectedAccountName,
        Integer requestedWorld,
        Instant seenAfter,
        long launchedProcessPid,
        String expectedAttemptId,
        BuilderGoalLaunchCancellation cancellation) {
      if (launchedProcessPid != -1L && interruptOnHealthCheck) {
        Thread.currentThread().interrupt();
        throw new BuilderGoalLaunchCancelledException();
      }
      if (launchedProcessPid != -1L && readyOnNextHealthCheck) {
        readyObserved.set(true);
        return new HealthCheckResult(
            Optional.of(
                new TargetClient(
                    "client-1",
                    "bigGreenMeat",
                    "1111111111111111",
                    "http://127.0.0.1:18998",
                    "0.0.0.0:18998",
                    "micro6",
                    "accountbuilder_builderpro",
                    "IDLE",
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)),
            PHASE_READY,
            null,
            launchedProcessPid,
            "bigGreenMeat",
            "1111111111111111",
            "bigGreenMeat",
            "1111111111111111",
            false,
            null,
            "accountbuilder_ready",
            "logged_in",
            0,
            30,
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "test: executor port not listening",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
  }

  /**
   * Cancellation that flips on a fixed {@code isCancelled()} call, used to pin exact boundaries.
   */
  static final class CountedCancellation implements BuilderGoalLaunchCancellation {
    private final int triggerCall;
    private int calls;

    CountedCancellation(int triggerCall) {
      this.triggerCall = triggerCall;
    }

    @Override
    public boolean isCancelled() {
      calls++;
      return calls >= triggerCall;
    }
  }

  /**
   * Health service that never reports the target ready, pinning a short launch watchdog deadline so
   * the readiness-timeout cleanup path can be exercised without the full production timeout.
   */
  static class NeverReadyHealthService extends ControlledPlatformService {
    NeverReadyHealthService(String projectDir) {
      super(false, false, projectDir, "", "", "http://127.0.0.1:18999");
    }

    @Override
    protected HealthCheckResult readHealthyExecutorTarget(
        String baseUrl,
        String expectedAccountHash,
        String expectedAccountName,
        Integer requestedWorld,
        Instant seenAfter,
        long launchedProcessPid,
        String expectedAttemptId,
        BuilderGoalLaunchCancellation cancellation) {
      return new HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "test: executor port not listening",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    @Override
    protected Instant resolveLaunchDeadline() {
      return Instant.now().plusMillis(3_000L);
    }
  }

  /**
   * Windows service that records when the {@code taskkill /T} backstop fires and whether the owned
   * root was still alive at that moment.
   */
  static class RecordingTaskkillService extends ControlledPlatformService {
    final AtomicLong taskkillPid = new AtomicLong(-1L);
    final AtomicBoolean rootAliveWhenTaskkillInvoked = new AtomicBoolean(false);

    RecordingTaskkillService(String projectDir) {
      super(true, true, projectDir, "", "");
    }

    @Override
    protected void runTaskkillTree(long rootPid) {
      taskkillPid.set(rootPid);
      rootAliveWhenTaskkillInvoked.set(
          ProcessHandle.of(rootPid).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  /**
   * Windows structured-launch service with a legacy custom launch template configured. The platform
   * overrides return constants rather than fields so constructor-time launch-mode resolution (which
   * derives the effective Gradle task) observes the pinned platform; {@link
   * ControlledPlatformService} fields are not yet assigned while its super constructor runs.
   */
  static class WindowsStructuredLegacyTemplateService extends Micro6TargetRelaunchService {
    WindowsStructuredLegacyTemplateService(String projectDir) {
      super(
          new ObjectMapper(),
          projectDir,
          "",
          "echo {micro6ProjectDir} {runArgs}",
          "",
          360,
          180_000L,
          2_000L,
          "micro6-recovery-launch.log",
          "http://localhost:8090",
          "http://127.0.0.1:8098",
          "http://localhost:8086");
    }

    @Override
    protected boolean isWindows() {
      return true;
    }

    @Override
    protected boolean useStructuredWindowsLaunch() {
      return true;
    }
  }

  @TempDir Path tempDir;

  private ControlledPlatformService newWindowsService(
      String projectDir, String launchCommandTemplate, String extraRunArgs) {
    return new ControlledPlatformService(
        true, true, projectDir, launchCommandTemplate, extraRunArgs);
  }

  private ControlledPlatformService newUnixService(
      String projectDir, String launchCommandTemplate, String extraRunArgs) {
    return new ControlledPlatformService(
        false, false, projectDir, launchCommandTemplate, extraRunArgs);
  }

  private ControlledPlatformService newWindowsGradleService(String projectDir) {
    return new ControlledPlatformService(true, false, projectDir, "", "");
  }

  private ControlledPlatformService newWindowsTemplateService(String projectDir) {
    return new ControlledPlatformService(
        true, false, projectDir, "echo {micro6ProjectDir} {runArgs}", "");
  }

  private Path createProjectDir(String name) throws Exception {
    Path project = tempDir.resolve(name);
    Files.createDirectories(project);
    return project;
  }

  private Path createWindowsGradleProject(String name) throws Exception {
    Path project = createProjectDir(name);
    Files.writeString(project.resolve("gradlew.bat"), "echo windows wrapper");
    return project;
  }

  private Path createUnixGradleProject(String name, boolean executable) throws Exception {
    Path project = createProjectDir(name);
    Path wrapper = project.resolve("gradlew");
    Files.writeString(wrapper, "#!/bin/sh\necho unix wrapper\n", StandardCharsets.UTF_8);
    if (executable) {
      wrapper.toFile().setExecutable(true, false);
    }
    return project;
  }

  /**
   * Creates a project whose fake {@code gradlew} launcher spawns a long-lived foreground child and
   * a backgrounded descendant (writing both PIDs to {@code pids.txt}), then writes {@code
   * started.marker}. The launcher tree stays alive so cancellation boundaries can be exercised.
   */
  private Path createFakeLauncherProject(String name) throws Exception {
    Path project = createProjectDir(name);
    Path wrapper = project.resolve("gradlew");
    Files.writeString(
        wrapper,
        "#!/bin/bash\n"
            + "sleep 600 &\n"
            + "echo \"$$ $!\" > pids.txt\n"
            + "touch started.marker\n"
            + "sleep 600\n",
        StandardCharsets.UTF_8);
    wrapper.toFile().setExecutable(true, false);
    return project;
  }

  private void awaitFile(Path file) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000L;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(file)) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("expected file to appear but it never did: " + file);
  }

  private void assertNoOwnedSurvivors(Path projectDir, String accountName) {
    List<ProcessHandle> survivors =
        ProcessHandle.allProcesses()
            .filter(
                handle -> {
                  String commandLine = handle.info().commandLine().orElse("");
                  return commandLine.contains(accountName)
                      || commandLine.contains(projectDir.toAbsolutePath().toString());
                })
            .collect(Collectors.toList());
    assertThat(survivors)
        .as(
            "no owned process may survive cancellation: %s",
            survivors.stream().map(h -> h.info().commandLine().orElse("?")).toList())
        .isEmpty();
  }

  private void assertChildPidsDead(Path projectDir) throws Exception {
    Path pidsFile = projectDir.resolve("pids.txt");
    if (!Files.exists(pidsFile)) {
      return;
    }
    for (String line : Files.readAllLines(pidsFile)) {
      for (String token : line.trim().split("\\s+")) {
        if (token.isBlank()) {
          continue;
        }
        long pid = Long.parseLong(token);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            .as("owned descendant pid %d must be dead before the lease-owning method returns", pid)
            .isFalse();
      }
    }
  }

  private void killProcessTreeByPid(long pid) {
    ProcessHandle.of(pid)
        .ifPresent(
            root -> {
              root.descendants().forEach(ProcessHandle::destroyForcibly);
              root.destroyForcibly();
            });
  }

  private void invokeValidate(Micro6TargetRelaunchService service, Path projectDir)
      throws Exception {
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod("validateMicro6ProjectDir", Path.class);
    method.setAccessible(true);
    try {
      method.invoke(service, projectDir);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw e;
    }
  }

  @Test
  void applyBuilderGoalEnvironmentSetsLeeuwbotApiUrlAndExecutorFlags() throws Exception {
    Micro6TargetRelaunchService service =
        new Micro6TargetRelaunchService(
            new ObjectMapper(),
            "C:/Dev/IdeaProjects/micro6",
            "",
            "",
            "",
            360,
            180_000L,
            2_000L,
            "micro6-recovery-launch.log",
            "http://localhost:8090",
            "http://127.0.0.1:8098",
            "http://localhost:8086");

    Map<String, String> environment = new LinkedHashMap<>();
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "applyBuilderGoalEnvironment", Map.class, Integer.class);
    method.setAccessible(true);
    method.invoke(service, environment, 8098);

    assertThat(environment)
        .containsEntry("MICRO6_LOCAL_EXECUTOR_ENABLED", "true")
        .containsEntry("MICRO6_EXECUTOR_BIND_ADDR", "127.0.0.1:8098")
        .containsEntry("CHROMASCAPE_BASE_URL", "http://localhost:8090")
        .containsEntry("LEEUWBOT_API_URL", "http://localhost:8086");
  }

  @Test
  void buildClientArgsAlwaysRequestsCleanAutoLoginForSelectedProfile() throws Exception {
    Micro6TargetRelaunchService service =
        new Micro6TargetRelaunchService(
            new ObjectMapper(),
            "C:/Dev/IdeaProjects/micro6",
            "",
            "",
            "--extra-flag",
            360,
            180_000L,
            2_000L,
            "micro6-recovery-launch.log",
            "http://localhost:8090",
            "http://127.0.0.1:8098",
            "http://localhost:8086");

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "buildClientArgs",
            String.class,
            String.class,
            String.class,
            String.class,
            Integer.class,
            String.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> clientArgs =
        (List<String>)
            method.invoke(
                service,
                "udasuAvuco",
                "b01c7f006bace664",
                "user@example.com",
                "secret",
                360,
                "attempt-123");

    assertThat(clientArgs)
        .contains("--clean-jagex-launcher")
        .contains("--auto-enable-accountbuilder")
        .contains("--profile=udasuAvuco")
        .contains("--expected-account-hash=b01c7f006bace664")
        .contains("--launch-attempt-id=attempt-123")
        .contains("--profile-email=user@example.com:secret")
        .contains("--world=360")
        .contains("--extra-flag");
  }

  @Test
  void structuredWindows_launcherScriptMissingFailsWithPathAndMode() throws Exception {
    Path project = createProjectDir("structured-missing");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    assertThatThrownBy(() -> invokeValidate(service, project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(project.toString())
        .hasMessageContaining("STRUCTURED_WINDOWS")
        .hasMessageContaining("launch-buildergoal-client.ps1");
  }

  @Test
  void structuredWindows_launcherScriptPresentPasses() throws Exception {
    Path project = createProjectDir("structured-present");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"), "param([string]$PayloadFile)");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    invokeValidate(service, project);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void structuredWindows_payloadIsCreatedOnlyWhenBuildingAnAttemptCommand() throws Exception {
    Path project = createProjectDir("structured-payload");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"), "param([string]$PayloadFile)");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "buildLaunchCommand",
            List.class,
            Path.class,
            String.class,
            String.class,
            String.class,
            int.class,
            Integer.class,
            String.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> command =
        (List<String>)
            method.invoke(
                service,
                List.of(
                    "--profile=account", "--profile-email=user@example.com:secret", "--world=360"),
                project,
                "account",
                "user@example.com",
                "secret",
                360,
                8098,
                "http://127.0.0.1:8098");
    int payloadIndex = command.indexOf("-PayloadFile");
    assertThat(payloadIndex).isGreaterThanOrEqualTo(0);
    Path payload = Path.of(command.get(payloadIndex + 1));
    assertThat(payload).isRegularFile();

    Method cleanup =
        Micro6TargetRelaunchService.class.getDeclaredMethod("deleteOwnedLaunchFiles", List.class);
    cleanup.setAccessible(true);
    cleanup.invoke(service, command);
    assertThat(payload).doesNotExist();
  }

  @Test
  void structuredWindows_directoryNamedLauncherScriptFails() throws Exception {
    Path project = createProjectDir("structured-script-directory");
    Files.createDirectory(project.resolve("launch-buildergoal-client.ps1"));

    assertThatThrownBy(() -> invokeValidate(newWindowsService(project.toString(), "", ""), project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("regular required entry point")
        .hasMessageContaining("launch-buildergoal-client.ps1");
  }

  /** Reads the private effective Gradle task selected at construction time. */
  private String readEffectiveGradleTask(Micro6TargetRelaunchService service) throws Exception {
    java.lang.reflect.Field field =
        Micro6TargetRelaunchService.class.getDeclaredField("gradleRunTask");
    field.setAccessible(true);
    return (String) field.get(service);
  }

  @Test
  void windowsWithLegacyCustomTemplateReportsStructuredWindowsTaskNotCustomCommand()
      throws Exception {
    Path project = createProjectDir("windows-legacy-template-task");
    WindowsStructuredLegacyTemplateService service =
        new WindowsStructuredLegacyTemplateService(project.toString());

    // Windows always selects the structured PowerShell launch; a configured legacy custom
    // launch template is ignored and must never relabel the metadata as custom_launch_command.
    String effectiveTask = readEffectiveGradleTask(service);
    assertThat(effectiveTask).isEqualTo(":client:runBuilderGoal");
    assertThat(effectiveTask).isNotEqualTo("custom_launch_command");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsWithLegacyCustomTemplateBuildsStructuredLauncherCommand() throws Exception {
    Path project = createProjectDir("windows-legacy-template-command");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"), "param([string]$PayloadFile)");
    WindowsStructuredLegacyTemplateService service =
        new WindowsStructuredLegacyTemplateService(project.toString());
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "buildLaunchCommand",
            List.class,
            Path.class,
            String.class,
            String.class,
            String.class,
            int.class,
            Integer.class,
            String.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> command =
        (List<String>)
            method.invoke(
                service,
                List.of("--profile=account", "--world=360"),
                project,
                "account",
                "user@example.com",
                "secret",
                360,
                8098,
                "http://127.0.0.1:8098");
    String joined = String.join(" ", command);
    assertThat(joined).contains("launch-buildergoal-client.ps1");
    assertThat(joined).contains("-PayloadFile");
    // The legacy template body must never reach the built command on Windows.
    assertThat(joined).doesNotContain("echo");
  }

  @Test
  void unixCustomTemplateStillReportsCustomLaunchCommand() throws Exception {
    Path project = createProjectDir("unix-custom-template-task");
    // Non-Windows custom-template behavior is unchanged: the template mode is selected and the
    // metadata keeps the custom_launch_command label.
    ControlledPlatformService service =
        newUnixService(project.toString(), "echo {micro6ProjectDir} {runArgs}", "");

    assertThat(readEffectiveGradleTask(service)).isEqualTo("custom_launch_command");
  }

  @Test
  void directGradleLaunchWithoutTemplateReportsCanonicalTask() throws Exception {
    Path project = createProjectDir("direct-gradle-task");
    ControlledPlatformService service = newUnixService(project.toString(), "", "");

    assertThat(readEffectiveGradleTask(service)).isEqualTo(":client:runBuilderGoal");
  }

  @Test
  void customTemplate_requiresNoRepositoryEntryPoint() throws Exception {
    Path project = createProjectDir("custom-template");
    // The custom-template mode resolves when structured Windows launch is not active and a
    // template is configured.
    ControlledPlatformService service =
        newUnixService(project.toString(), "echo {micro6ProjectDir} && gradle {runArgs}", "");

    invokeValidate(service, project);
  }

  @Test
  void windowsGradle_missingGradlewBatFailsWithPathAndMode() throws Exception {
    Path project = createProjectDir("windows-gradle-missing");
    ControlledPlatformService service = newWindowsGradleService(project.toString());

    assertThatThrownBy(() -> invokeValidate(service, project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(project.toString())
        .hasMessageContaining("WINDOWS_GRADLE")
        .hasMessageContaining("gradlew.bat");
  }

  @Test
  void windowsGradle_gradlewBatPresentPasses() throws Exception {
    Path project = createWindowsGradleProject("windows-gradle-present");
    ControlledPlatformService service = newWindowsGradleService(project.toString());

    invokeValidate(service, project);
  }

  @Test
  void windowsTemplate_requiresNoRepositoryEntryPoint() throws Exception {
    Path project = createProjectDir("windows-template");
    invokeValidate(newWindowsTemplateService(project.toString()), project);
  }

  @Test
  void unixGradle_missingGradlewFailsWithPathAndMode() throws Exception {
    Path project = createProjectDir("unix-gradle-missing");
    ControlledPlatformService service = newUnixService(project.toString(), "", "");

    assertThatThrownBy(() -> invokeValidate(service, project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(project.toString())
        .hasMessageContaining("gradlew");
  }

  @Test
  void unixGradle_nonExecutableGradlewFailsWithPathAndMode() throws Exception {
    Path project = createUnixGradleProject("unix-gradle-not-exec", false);
    Path wrapper = project.resolve("gradlew");
    wrapper.toFile().setExecutable(false, false);
    ControlledPlatformService service = newUnixService(project.toString(), "", "");

    // The executability check only applies on non-Windows hosts (Windows does not honor
    // Unix execute bits), and the test platform is pinned to non-Windows via the subclass.
    boolean checkApplies = !System.getProperty("os.name", "").toLowerCase().contains("win");
    if (checkApplies) {
      assertThatThrownBy(() -> invokeValidate(service, project))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(project.toString())
          .hasMessageContaining("gradlew")
          .hasMessageContaining("not executable");
    } else {
      invokeValidate(service, project);
    }
  }

  @Test
  void unixGradle_executableGradlewPasses() throws Exception {
    Path project = createUnixGradleProject("unix-gradle-exec", true);
    ControlledPlatformService service = newUnixService(project.toString(), "", "");

    invokeValidate(service, project);
  }

  @Test
  void unixGradle_directoryNamedGradlewFails() throws Exception {
    Path project = createProjectDir("unix-gradle-directory");
    Files.createDirectory(project.resolve("gradlew"));

    assertThatThrownBy(() -> invokeValidate(newUnixService(project.toString(), "", ""), project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("regular required entry point")
        .hasMessageContaining("gradlew");
  }

  @Test
  void stopExistingTargetProcess_verifiesOwnershipViaHealthPidNotWorkspaceWideCleanup() {
    // The broad workspace-wide PowerShell cleanup script has been removed. Ownership verification
    // is now PID-scoped via resolveVerifiedExecutorPid, which is covered by dedicated tests. This
    // guard documents that no buildWindowsCleanupScript entry point remains.
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");
    assertThat(
            Arrays.stream(Micro6TargetRelaunchService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("buildWindowsCleanupScript")))
        .isFalse();
  }

  @Test
  void listeningPortProbe_freeWindowsPortMeansPortIsFree() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    Micro6TargetRelaunchService.PortProbe probe =
        service.parseListeningPortProbeResult("PORT_FREE", 0, true);

    assertThat(probe.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.FREE);
    assertThat(probe.pid()).isNull();
  }

  @Test
  void listeningPortProbe_activeWindowsListenerReportsPid() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    Micro6TargetRelaunchService.PortProbe probe =
        service.parseListeningPortProbeResult("PORT_PID=4242", 0, true);

    assertThat(probe.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.OCCUPIED);
    assertThat(probe.pid()).isEqualTo(4242L);
  }

  @Test
  void listeningPortProbe_malformedMultipleZeroNegativeOverflowFailsClosed() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    // Malformed protocol line.
    Micro6TargetRelaunchService.PortProbe malformed =
        service.parseListeningPortProbeResult("not-a-pid", 0, true);
    // Multiple protocol lines.
    Micro6TargetRelaunchService.PortProbe multiple =
        service.parseListeningPortProbeResult("PORT_PID=123\nPORT_PID=456", 0, true);
    // Zero PID.
    Micro6TargetRelaunchService.PortProbe zero =
        service.parseListeningPortProbeResult("PORT_PID=0", 0, true);
    // Negative PID.
    Micro6TargetRelaunchService.PortProbe negative =
        service.parseListeningPortProbeResult("PORT_PID=-1", 0, true);
    // Overflowing PID.
    Micro6TargetRelaunchService.PortProbe overflow =
        service.parseListeningPortProbeResult("PORT_PID=99999999999999999999", 0, true);
    // Blank output is never a free result.
    Micro6TargetRelaunchService.PortProbe blank =
        service.parseListeningPortProbeResult("", 0, true);
    // Port-pid token with a non-numeric suffix.
    Micro6TargetRelaunchService.PortProbe trailing =
        service.parseListeningPortProbeResult("PORT_PID=123junk", 0, true);

    for (Micro6TargetRelaunchService.PortProbe probe :
        new Micro6TargetRelaunchService.PortProbe[] {
          malformed, multiple, zero, negative, overflow, blank, trailing
        }) {
      assertThat(probe.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.UNKNOWN);
    }
  }

  @Test
  void listeningPortProbe_anyNonzeroExitFailsClosedRegardlessOfOutput() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    // Access-denied / unexpected CIM failure: nonzero exit is UNKNOWN regardless of output.
    Micro6TargetRelaunchService.PortProbe probe =
        service.parseListeningPortProbeResult("Get-NetTCPConnection: access denied", 1, true);

    assertThat(probe.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.UNKNOWN);
    assertThat(probe.detail()).isEqualTo("listener probe exited with 1");

    // Even a PORT_FREE token is not trusted when the process exited nonzero.
    Micro6TargetRelaunchService.PortProbe freeWithNonzero =
        service.parseListeningPortProbeResult("PORT_FREE", 1, true);
    assertThat(freeWithNonzero.state())
        .isEqualTo(Micro6TargetRelaunchService.PortProbeState.UNKNOWN);
  }

  @Test
  void listeningPortProbe_leakedCmdletizationTextFailsClosed() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    // CmdletizationQuery_NotFound is handled inside PowerShell through the structured
    // FullyQualifiedErrorId filter. If it ever leaks to stdout, Java must never trust it as a
    // free result or a PID.
    Micro6TargetRelaunchService.PortProbe probeExitZero =
        service.parseListeningPortProbeResult("CmdletizationQuery_NotFound", 0, true);
    Micro6TargetRelaunchService.PortProbe probeEmbedded =
        service.parseListeningPortProbeResult(
            "Get-NetTCPConnection : CmdletizationQuery_NotFound, MSFT_NetTCPConnection", 0, true);

    assertThat(probeExitZero.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.UNKNOWN);
    assertThat(probeEmbedded.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.UNKNOWN);
  }

  @Test
  void listeningPortProbe_unixFreeAndOccupiedProtocol() {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");

    Micro6TargetRelaunchService.PortProbe free =
        service.parseListeningPortProbeResult("PORT_FREE", 0, false);
    Micro6TargetRelaunchService.PortProbe occupied =
        service.parseListeningPortProbeResult("PORT_PID=999", 0, false);

    assertThat(free.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.FREE);
    assertThat(occupied.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.OCCUPIED);
    assertThat(occupied.pid()).isEqualTo(999L);
  }

  @Test
  void windowsProbeScriptFiltersCmdletizationAndEmitsProtocolTokens() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    List<String> command = service.buildWindowsPortProbeCommand(8098);
    String script = String.join(" ", command);

    // The PowerShell probe must filter CmdletizationQuery_NotFound internally and emit exactly
    // one protocol token: PORT_FREE or PORT_PID=<owner>.
    assertThat(script)
        .contains("CmdletizationQuery_NotFound")
        .contains("-ErrorAction SilentlyContinue")
        .contains("-ErrorVariable")
        .contains("Get-NetTCPConnection -LocalPort 8098 -State Listen")
        .contains("-notlike 'CmdletizationQuery_NotFound*'")
        .contains("PORT_FREE")
        .contains("'PORT_PID=' + $owner");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsIntegratedPortProbeExecutesGeneratedPowerShell() throws Exception {
    // Bind a listener on an ephemeral port and run the actual generated PowerShell command; the
    // probe must classify it as OCCUPIED with the owning PID.
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      int port = socket.getLocalPort();
      ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");
      List<String> command = service.buildWindowsPortProbeCommand(port);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes()).trim();
      int exitCode = process.waitFor();
      Micro6TargetRelaunchService.PortProbe probe =
          service.parseListeningPortProbeResult(output, exitCode, true);
      assertThat(probe.state()).isEqualTo(Micro6TargetRelaunchService.PortProbeState.OCCUPIED);
      assertThat(probe.pid()).isNotNull();
    }
  }

  @Test
  void windowsGradle_directoryNamedGradlewBatFails() throws Exception {
    Path project = createProjectDir("windows-gradle-directory");
    Files.createDirectory(project.resolve("gradlew.bat"));

    assertThatThrownBy(() -> invokeValidate(newWindowsGradleService(project.toString()), project))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("regular required entry point")
        .hasMessageContaining("gradlew.bat");
  }

  @Test
  void missingProjectDirectoryFailsWithPathAndMode() throws Exception {
    Path missing = tempDir.resolve("does-not-exist");
    ControlledPlatformService service = newUnixService(missing.toString(), "", "");

    assertThatThrownBy(() -> invokeValidate(service, missing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(missing.toString())
        .hasMessageContaining("NON_WINDOWS_GRADLE")
        .hasMessageContaining("does not exist");
  }

  @Test
  void pathExistsButIsAFileFailsWithPathAndMode() throws Exception {
    Path file = tempDir.resolve("not-a-directory.txt");
    Files.writeString(file, "plain file");
    ControlledPlatformService service = newUnixService(file.toString(), "", "");

    assertThatThrownBy(() -> invokeValidate(service, file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(file.toString())
        .hasMessageContaining("NON_WINDOWS_GRADLE")
        .hasMessageContaining("is not a directory");
  }

  @Test
  void extractLaunchFailureDetailPrefersNewestKnownFailureOverOlderGradleBlock() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "extractLaunchFailureDetail", List.class);
    method.setAccessible(true);
    String detail =
        (String)
            method.invoke(
                service,
                List.of(
                    "FAILURE: Build failed with an exception.",
                    "",
                    "* What went wrong:",
                    "Cannot locate tasks that match ':runelite-client:run' as project 'runelite-client' not found in root project 'runelite'. Some candidates are: 'runelite-agent'.",
                    "",
                    "* Try:",
                    "BUILD FAILED in 13s",
                    "The command line is too long."));

    assertThat(detail).isEqualTo("The command line is too long.");
  }

  @Test
  void restartTargetThrowsWhenAlreadyCancelled() {
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "bigGreenMeat",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    (BuilderGoalLaunchCancellation) () -> true))
        .isInstanceOf(BuilderGoalLaunchCancelledException.class);
  }

  @Test
  void sleepPollThrowsWhenAlreadyCancelled() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "sleepPoll", long.class, BuilderGoalLaunchCancellation.class);
    method.setAccessible(true);
    try {
      method.invoke(service, 5000L, (BuilderGoalLaunchCancellation) () -> true);
      throw new AssertionError("expected cancellation exception");
    } catch (java.lang.reflect.InvocationTargetException e) {
      assertThat(e.getCause()).isInstanceOf(BuilderGoalLaunchCancelledException.class);
    }
  }

  @Test
  void sleepPollPreservesInterruptStatusOnInterrupt() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "sleepPoll", long.class, BuilderGoalLaunchCancellation.class);
    method.setAccessible(true);
    Thread.currentThread().interrupt();
    try {
      try {
        method.invoke(service, 5000L, (BuilderGoalLaunchCancellation) () -> false);
        throw new AssertionError("expected cancellation exception");
      } catch (java.lang.reflect.InvocationTargetException e) {
        assertThat(e.getCause()).isInstanceOf(BuilderGoalLaunchCancelledException.class);
      }
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void terminateOwnedProcessTreeDestroysRootAndDescendants() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Process root = new ProcessBuilder("bash", "-c", "sleep 30 & sleep 30").start();
    List<ProcessHandle> descendants;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (true) {
      descendants = root.descendants().collect(Collectors.toList());
      if (!descendants.isEmpty() || System.currentTimeMillis() > deadline) {
        break;
      }
      Thread.sleep(20L);
    }
    assertThat(descendants).as("root process should spawn a descendant").isNotEmpty();
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "terminateOwnedProcessTree", Process.class);
    method.setAccessible(true);
    method.invoke(service, root);
    assertThat(root.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(root.isAlive()).isFalse();
    for (ProcessHandle descendant : descendants) {
      assertThat(descendant.isAlive()).isFalse();
    }
  }

  @Test
  void terminateOwnedProcessTreeIgnoresNullProcess() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "terminateOwnedProcessTree", Process.class);
    method.setAccessible(true);
    method.invoke(service, new Object[] {null});
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_cancellationImmediatelyAfterProcessCreationTerminatesOwnedTree()
      throws Exception {
    Path project = createFakeLauncherProject("cancel-after-create");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());

    // The check immediately after startTargetProcess() is the 7th cancellation boundary.
    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "cancelAfterCreate",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    new CountedCancellation(7)))
        .isInstanceOf(BuilderGoalLaunchCancelledException.class);

    assertNoOwnedSurvivors(project, "cancelAfterCreate");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_cancellationWhileSleepingInsideWaitForTargetReadyTerminatesOwnedTree()
      throws Exception {
    Path project = createFakeLauncherProject("cancel-while-sleep");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());
    Path marker = project.resolve("started.marker");
    AtomicBoolean cancelled = new AtomicBoolean(false);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Throwable> outcome =
          pool.submit(
              () -> {
                try {
                  service.restartTarget(
                      "cancelWhileSleep",
                      "1111111111111111",
                      "user@example.com",
                      "secret",
                      360,
                      null,
                      (BuilderGoalLaunchCancellation) () -> cancelled.get());
                  return null;
                } catch (Throwable t) {
                  return t;
                }
              });
      awaitFile(marker);
      cancelled.set(true);
      assertThat(outcome.get(20, TimeUnit.SECONDS))
          .isInstanceOf(BuilderGoalLaunchCancelledException.class);
    } finally {
      pool.shutdownNow();
    }
    assertNoOwnedSurvivors(project, "cancelWhileSleep");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_interruptedHealthRequestTerminatesOwnedTreeAndPreservesInterruptStatus()
      throws Exception {
    Path project = createFakeLauncherProject("interrupt-health");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());
    service.interruptOnHealthCheck = true;
    AtomicBoolean interruptPreservedAtExit = new AtomicBoolean(false);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Throwable> outcome =
          pool.submit(
              () -> {
                try {
                  service.restartTarget(
                      "interruptHealth",
                      "1111111111111111",
                      "user@example.com",
                      "secret",
                      360,
                      null,
                      BuilderGoalLaunchCancellation.NONE);
                  return null;
                } catch (Throwable t) {
                  interruptPreservedAtExit.set(Thread.currentThread().isInterrupted());
                  return t;
                }
              });
      assertThat(outcome.get(20, TimeUnit.SECONDS))
          .isInstanceOf(BuilderGoalLaunchCancelledException.class);
      assertThat(interruptPreservedAtExit).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertNoOwnedSurvivors(project, "interruptHealth");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_cancellationImmediatelyBeforeReadyTerminatesOwnedTree() throws Exception {
    Path project = createFakeLauncherProject("cancel-before-ready");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());
    service.readyOnNextHealthCheck = true;

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "cancelBeforeReady",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    (BuilderGoalLaunchCancellation) () -> service.readyObserved.get()))
        .isInstanceOf(BuilderGoalLaunchCancelledException.class);

    assertNoOwnedSurvivors(project, "cancelBeforeReady");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_nonCancelledReadyWorkflowLeavesOwnedProcessUntouched() throws Exception {
    Path project = createFakeLauncherProject("no-cancel-ready");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());
    service.readyOnNextHealthCheck = true;
    long launcherPid = -1L;
    try {
      Micro6TargetRelaunchService.RelaunchResult result =
          service.restartTarget(
              "noCancelReady",
              "1111111111111111",
              "user@example.com",
              "secret",
              360,
              null,
              BuilderGoalLaunchCancellation.NONE);
      assertThat(result).isNotNull();
      assertThat(result.targetClient()).isNotNull();
      assertThat(result.targetClient().accountName()).isEqualTo("bigGreenMeat");
      launcherPid = result.launcherPid();
      assertThat(ProcessHandle.of(launcherPid).map(ProcessHandle::isAlive).orElse(false))
          .as("non-cancelled workflow must leave its owned process running")
          .isTrue();
    } finally {
      // Clean up the intentionally still-running launcher tree so the test host stays clean.
      // The root launcher pid anchors the whole owned tree (including its sleep descendants,
      // whose command lines do not contain the account name).
      if (launcherPid > 0) {
        killProcessTreeByPid(launcherPid);
      }
      ProcessHandle.allProcesses()
          .filter(handle -> handle.info().commandLine().orElse("").contains("noCancelReady"))
          .forEach(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_readinessTimeoutKillsRootAndDescendantsBeforeReturning() throws Exception {
    Path project = createFakeLauncherProject("readiness-timeout");
    NeverReadyHealthService service = new NeverReadyHealthService(project.toString());

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "readinessTimeout",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    BuilderGoalLaunchCancellation.NONE))
        .isInstanceOf(Micro6TargetRelaunchService.RelaunchException.class);

    assertNoOwnedSurvivors(project, "readinessTimeout");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void integrated_progressListenerFailureAfterProcessCreationTerminatesOwnedTree()
      throws Exception {
    Path project = createFakeLauncherProject("progress-listener-failure");
    ProgrammableHealthService service = new ProgrammableHealthService(project.toString());

    // Throw only on the third progress emission so the owned launcher has fully spawned its
    // descendants before the failure forces cleanup.
    java.util.concurrent.atomic.AtomicInteger progressCalls =
        new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "progressListenerFailure",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    progress -> {
                      if (progressCalls.incrementAndGet() >= 3) {
                        throw new IllegalStateException("boom");
                      }
                    },
                    BuilderGoalLaunchCancellation.NONE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom");

    assertNoOwnedSurvivors(project, "progressListenerFailure");
    assertChildPidsDead(project);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void terminateOwnedProcessTreeInvokesTaskkillWhileRootAlive() throws Exception {
    RecordingTaskkillService service =
        new RecordingTaskkillService(tempDir.resolve("taskkill-while-alive").toString());
    // Root ignores SIGTERM so graceful termination cannot complete; the taskkill /T backstop must
    // fire while the owned root is still alive, and the surviving descendants must still die.
    Process root =
        new ProcessBuilder("bash", "-c", "trap '' TERM; sleep 30 & trap '' TERM; sleep 30").start();
    List<ProcessHandle> descendants;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (true) {
      descendants = root.descendants().collect(Collectors.toList());
      if (!descendants.isEmpty() || System.currentTimeMillis() > deadline) {
        break;
      }
      Thread.sleep(20L);
    }
    assertThat(descendants).as("root process should spawn a descendant").isNotEmpty();

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "terminateOwnedProcessTree", Process.class);
    method.setAccessible(true);
    method.invoke(service, root);

    assertThat(service.taskkillPid.get())
        .as("taskkill backstop must target the owned root pid")
        .isEqualTo(root.pid());
    assertThat(service.rootAliveWhenTaskkillInvoked.get())
        .as("taskkill /T must be invoked while the owned root is still alive")
        .isTrue();
    assertThat(root.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(root.isAlive()).isFalse();
    // The forced descendants may take a few moments to observe SIGKILL delivery on slower hosts
    // (for example WSL). Wait bounded for every captured descendant to exit before asserting.
    long descendantDeadline = System.currentTimeMillis() + 5_000L;
    List<ProcessHandle> stillAlive = new java.util.ArrayList<>(descendants);
    while (!stillAlive.isEmpty() && System.currentTimeMillis() < descendantDeadline) {
      stillAlive.removeIf(handle -> !handle.isAlive());
      if (!stillAlive.isEmpty()) {
        Thread.sleep(20L);
      }
    }
    assertThat(stillAlive)
        .as("every captured descendant must exit after force termination")
        .isEmpty();
  }

  /**
   * Service subclass that stubs the port-owner resolution so the real {@code /health} HTTP handling
   * in {@code readHealthyExecutorTarget} can be exercised without OS tooling.
   */
  static class HttpAwareService extends ControlledPlatformService {
    private final long fakePortOwnerPid;

    HttpAwareService(String projectDir, String baseUrl, long fakePortOwnerPid) {
      super(false, false, projectDir, "", "", baseUrl);
      this.fakePortOwnerPid = fakePortOwnerPid;
    }

    @Override
    protected Long resolveListeningPid(Integer port) {
      return fakePortOwnerPid;
    }
  }

  private String healthPayload(
      String accountHash,
      String accountName,
      String requestedAccountHash,
      String requestedAccountName,
      boolean dispatchHold,
      String startupState,
      String loginState) {
    return "{\"ok\":true,"
        + "\"account_hash_scheme\":\"accountconnector/v1\","
        + "\"account_hash\":"
        + (accountHash == null ? "null" : "\"" + accountHash + "\"")
        + ",\"account_name\":"
        + (accountName == null ? "null" : "\"" + accountName + "\"")
        + ",\"requested_account_hash\":"
        + (requestedAccountHash == null ? "null" : "\"" + requestedAccountHash + "\"")
        + ",\"requested_account_name\":"
        + (requestedAccountName == null ? "null" : "\"" + requestedAccountName + "\"")
        + ",\"dispatch_hold\":"
        + dispatchHold
        + ",\"dispatch_hold_reason\":null"
        + ",\"startup_state\":"
        + (startupState == null ? "null" : "\"" + startupState + "\"")
        + ",\"login_state\":"
        + (loginState == null ? "null" : "\"" + loginState + "\"")
        + ",\"advertised_base_url\":\"http://127.0.0.1:18997\""
        + ",\"executor_bind_addr\":\"127.0.0.1:18997\""
        + ",\"executor_instance_id\":\"exec-1\""
        + ",\"executor_profile\":\"accountbuilder_builderpro\""
        + ",\"status\":\"IDLE\""
        + ",\"requested_world\":360"
        + ",\"current_world\":360"
        + ",\"world_matched\":true"
        + ",\"pathfinder_ready\":true"
        + ",\"script_lifecycle_state\":\"RUNNING\""
        + ",\"script_loop_alive\":true}";
  }

  @Test
  void readHealthyExecutorTarget_mapsLoginIncompleteToWaitingForLogin() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      String body =
          healthPayload(
              "1111111111111111",
              "bigGreenMeat",
              "1111111111111111",
              "bigGreenMeat",
              false,
              "starting_accountbuilder",
              "auto_login_in_progress");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "1111111111111111",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_LOGIN);
      assertThat(result.readinessReason()).contains("auto-login");
      assertThat(result.accountMatched()).isTrue();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_mapsLoggedInDispatchHoldToWaitingForDispatch() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      String body =
          healthPayload(
              "1111111111111111",
              "bigGreenMeat",
              "1111111111111111",
              "bigGreenMeat",
              true,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "1111111111111111",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH);
      assertThat(result.accountMatched()).isTrue();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_retainsCanonicalReadinessFieldsOnReadyTarget() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      String body =
          healthPayload(
              "1111111111111111",
              "bigGreenMeat",
              "1111111111111111",
              "bigGreenMeat",
              false,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "1111111111111111",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.targetClient()).isPresent();
      TargetClient target = result.targetClient().orElseThrow();
      assertThat(target.dispatchHold()).isFalse();
      assertThat(target.startupState()).isEqualTo("accountbuilder_ready");
      assertThat(target.loginState()).isEqualTo("logged_in");
      assertThat(target.pathfinderReady()).isTrue();
      assertThat(target.requestedAccountHash()).isEqualTo("1111111111111111");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_doesNotPromoteRequestedHashToObservedTargetIdentity()
      throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      // This is the legacy name-fallback case: the game name is known, but no actual hash has
      // been resolved. Identity is a readiness field, so a missing resolved hash keeps the
      // executor in WAITING_FOR_ACCOUNT; the requested launch hash stays diagnostic metadata
      // and is never promoted into an observed TargetClient identity.
      String body =
          healthPayload(
              null,
              "bigGreenMeat",
              "requested-hash",
              "bigGreenMeat",
              false,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              null,
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.targetClient()).isEmpty();
      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT);
      assertThat(result.resolvedAccountHash()).isNull();
      assertThat(result.requestedAccountHash()).isEqualTo("requested-hash");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_requestedOnlyIdentityIsNeverSufficient() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      // Resolved identity is missing; only the launch-args requested identity is present.
      String body =
          healthPayload(
              null,
              null,
              "1111111111111111",
              "bigGreenMeat",
              false,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "1111111111111111",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      // A requested-only identity without a resolved counterpart is never sufficient to match.
      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT);
      assertThat(result.accountMatched()).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_hashMismatchOverridesMatchingName() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      // Resolved name matches but resolved hash differs: hash mismatch must override the name
      // match.
      String body =
          healthPayload(
              "9999999999999999",
              "bigGreenMeat",
              "1111111111111111",
              "bigGreenMeat",
              false,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "1111111111111111",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT);
      assertThat(result.accountMatched()).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void readHealthyExecutorTarget_matchingMalformedHashesAreNotReady() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    try {
      String body =
          healthPayload(
              "hash-1",
              "bigGreenMeat",
              "hash-1",
              "bigGreenMeat",
              false,
              "accountbuilder_ready",
              "logged_in");
      server.createContext(
          "/health",
          exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
          });
      server.start();
      HttpAwareService service =
          new HttpAwareService(
              tempDir.toString(), "http://127.0.0.1:" + server.getAddress().getPort(), 4242L);

      Micro6TargetRelaunchService.HealthCheckResult result =
          service.readHealthyExecutorTarget(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "hash-1",
              "bigGreenMeat",
              360,
              Instant.now().minusSeconds(5),
              -1L,
              null,
              null);

      assertThat(result.readinessPhase())
          .isEqualTo(Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT);
      assertThat(result.accountMatched()).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void analyzeLaunchFailure_isAttemptScopedAndIgnoresHistoricalFailures() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Path logFile = tempDir.resolve("attempt-scoped.log");
    Files.writeString(
        logFile,
        "FAILURE: Build failed with an exception.\n"
            + "* What went wrong:\n"
            + "Execution failed for task ':client:runBuilderGoal'.\n"
            + "BUILD FAILED in 13s\n",
        StandardCharsets.UTF_8);
    long offset = Files.size(logFile);
    // The current attempt appends only a successful build marker; the earlier BUILD FAILED block
    // belongs to a previous attempt and must never be classified as the current attempt's failure.
    Files.writeString(
        logFile,
        "[buildergoal] task=:client:runBuilderGoal\n[buildergoal] phase=LAUNCHING_PROCESS\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "analyzeLaunchFailure", Path.class, long.class);
    method.setAccessible(true);
    Object result = method.invoke(service, logFile, offset);
    assertThat(result).isNotNull();
    // The historical BUILD FAILED from the previous attempt must not be classified as the current
    // attempt's failure; the current attempt has no Gradle failure, so it falls back to
    // PROCESS_EXITED.
    assertThat(result.getClass().getDeclaredMethod("kind").invoke(result).toString())
        .isEqualTo("PROCESS_EXITED");
  }

  @Test
  void hasSeenLaunchingProcessMarker_ignoresMarkerFromEarlierAttempt() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Path logFile = tempDir.resolve("marker.log");
    Files.writeString(logFile, "[buildergoal] phase=LAUNCHING_PROCESS\n", StandardCharsets.UTF_8);
    long offset = Files.size(logFile);
    Files.writeString(
        logFile,
        "[buildergoal] task=:client:runBuilderGoal\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "hasSeenLaunchingProcessMarker", Path.class, long.class);
    method.setAccessible(true);
    assertThat(method.invoke(service, logFile, offset)).isEqualTo(false);
    assertThat(method.invoke(service, logFile, 0L)).isEqualTo(true);
  }

  @Test
  void analyzeLaunchFailure_ignoresEarlierBuildFailedFromPreviousAttempt() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    Path logFile = tempDir.resolve("attempt-scoped2.log");
    // Previous attempt's failure references the obsolete classpath task.
    Files.writeString(
        logFile,
        "FAILURE: Build failed with an exception.\n* What went wrong:\nExecution failed for task ':client:writeBuilderGoalRuntimeClasspath'.\nBUILD FAILED in 13s\n",
        StandardCharsets.UTF_8);
    long offset = Files.size(logFile);
    // Current attempt's failure references the canonical task. The earlier BUILD FAILED block must
    // never be classified as the current attempt's failure.
    Files.writeString(
        logFile,
        "FAILURE: Build failed with an exception.\n* What went wrong:\nExecution failed for task ':client:runBuilderGoal'.\nBUILD FAILED in 5s\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "analyzeLaunchFailure", Path.class, long.class);
    method.setAccessible(true);
    Object result = method.invoke(service, logFile, offset);
    assertThat(result).isNotNull();
    assertThat(result.getClass().getDeclaredMethod("kind").invoke(result).toString())
        .isEqualTo("GRADLE_TASK_FAILED");
    String detail = result.getClass().getDeclaredMethod("detail").invoke(result).toString();
    assertThat(detail).contains(":client:runBuilderGoal");
    assertThat(detail).doesNotContain("writeBuilderGoalRuntimeClasspath");
  }

  /**
   * Windows service whose launch-configuration validation always fails, pinning the behavior that a
   * broken Gradle task or launcher configuration must fail before any existing Micro6 process is
   * stopped.
   */
  static class FailingValidationService extends ControlledPlatformService {
    FailingValidationService(String projectDir) {
      super(true, true, projectDir, "", "");
    }

    @Override
    protected Micro6TargetRelaunchService.HealthCheckResult readHealthyExecutorTarget(
        String baseUrl,
        String expectedAccountHash,
        String expectedAccountName,
        Integer requestedWorld,
        Instant seenAfter,
        long launchedProcessPid,
        String expectedAttemptId,
        BuilderGoalLaunchCancellation cancellation) {
      return new Micro6TargetRelaunchService.HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "test: executor port not listening",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          requestedWorld,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    @Override
    protected void verifyLauncherContract(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      // Contract check passes; the failure is pinned to configuration validation.
    }

    @Override
    protected void validateLaunchConfiguration(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      throw new Micro6TargetRelaunchService.RelaunchException(
          Micro6TargetRelaunchService.PHASE_BUILD_FAILED,
          "Micro6 launch configuration is invalid. Check " + logFile.toAbsolutePath(),
          true,
          logFile,
          ":client:runBuilderGoal",
          null,
          "LAUNCH_CONFIGURATION_INVALID",
          "Micro6 launch configuration validation failed",
          Micro6TargetRelaunchService.PHASE_PREPARING_BUILD,
          "attempt-1");
    }
  }

  /**
   * Windows service whose launcher contract check always fails, pinning that an incompatible
   * launcher must fail before any preflight or process cleanup.
   */
  static class FailingContractService extends ControlledPlatformService {
    FailingContractService(String projectDir) {
      super(true, true, projectDir, "", "");
    }

    @Override
    protected Micro6TargetRelaunchService.HealthCheckResult readHealthyExecutorTarget(
        String baseUrl,
        String expectedAccountHash,
        String expectedAccountName,
        Integer requestedWorld,
        Instant seenAfter,
        long launchedProcessPid,
        String expectedAttemptId,
        BuilderGoalLaunchCancellation cancellation) {
      return new Micro6TargetRelaunchService.HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "test: executor port not listening",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          requestedWorld,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    @Override
    protected void verifyLauncherContract(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      throw new Micro6TargetRelaunchService.RelaunchException(
          Micro6TargetRelaunchService.PHASE_BUILD_FAILED,
          "Builder Goal launcher contract is incompatible (expected contract_version 2). Check "
              + logFile.toAbsolutePath(),
          true,
          logFile,
          ":client:runBuilderGoal",
          null,
          "LAUNCH_CONFIGURATION_INVALID",
          "Builder Goal launcher contract is incompatible (expected contract_version 2)",
          Micro6TargetRelaunchService.PHASE_PREPARING_BUILD,
          "attempt-1");
    }
  }

  /**
   * Windows service whose production-class compilation always fails, pinning that a compile failure
   * must fail as GRADLE_TASK_FAILED before any existing Micro6 process is stopped.
   */
  static class FailingCompileService extends ControlledPlatformService {
    FailingCompileService(String projectDir) {
      super(true, true, projectDir, "", "");
    }

    @Override
    protected Micro6TargetRelaunchService.HealthCheckResult readHealthyExecutorTarget(
        String baseUrl,
        String expectedAccountHash,
        String expectedAccountName,
        Integer requestedWorld,
        Instant seenAfter,
        long launchedProcessPid,
        String expectedAttemptId,
        BuilderGoalLaunchCancellation cancellation) {
      return new Micro6TargetRelaunchService.HealthCheckResult(
          Optional.empty(),
          PHASE_WAITING_FOR_PORT,
          "test: executor port not listening",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          false,
          null,
          null,
          requestedWorld,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    @Override
    protected void verifyLauncherContract(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      // Contract check passes.
    }

    @Override
    protected void validateLaunchConfiguration(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      // Validation passes; the failure is pinned to compilation.
    }

    @Override
    protected void compileMicro6ProductionClasses(
        Path projectDir, Path logFile, BuilderGoalLaunchCancellation cancellation) {
      throw new Micro6TargetRelaunchService.RelaunchException(
          Micro6TargetRelaunchService.PHASE_BUILD_FAILED,
          "Micro6 production class compilation failed. Check " + logFile.toAbsolutePath(),
          true,
          logFile,
          ":client:runBuilderGoal",
          null,
          "GRADLE_TASK_FAILED",
          "Micro6 production class compilation failed with exit code 1",
          Micro6TargetRelaunchService.PHASE_PREPARING_BUILD,
          "attempt-1");
    }
  }

  @Test
  void restartTarget_configurationValidationFailureDoesNotStopExistingClient() throws Exception {
    Path project = createFakeLauncherProject("validate-fails");
    // The structured Windows launch mode requires the production launcher script to exist.
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"),
        "# fake launcher",
        StandardCharsets.UTF_8);
    FailingValidationService service = new FailingValidationService(project.toString());

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "validateFails",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    (BuilderGoalLaunchCancellation) () -> false))
        .isInstanceOf(Micro6TargetRelaunchService.RelaunchException.class)
        .hasMessageContaining("Micro6 launch configuration is invalid");
    // The launcher script's fake process tree was never started, so it must still be alive — the
    // validation failure aborted the workflow before any stop or launch boundary.
    assertThat(project.resolve("started.marker")).doesNotExist();
  }

  @Test
  void restartTarget_launcherContractMismatchFailsBeforeAnyCleanup() throws Exception {
    Path project = createFakeLauncherProject("contract-fails");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"),
        "# fake launcher",
        StandardCharsets.UTF_8);
    FailingContractService service = new FailingContractService(project.toString());

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "contractFails",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    (BuilderGoalLaunchCancellation) () -> false))
        .isInstanceOf(Micro6TargetRelaunchService.RelaunchException.class)
        .hasMessageContaining("launcher contract is incompatible")
        .satisfies(
            e -> {
              Micro6TargetRelaunchService.RelaunchException ex =
                  (Micro6TargetRelaunchService.RelaunchException) e;
              assertThat(ex.getFailureKind()).isEqualTo("LAUNCH_CONFIGURATION_INVALID");
            });
    // Nothing was compiled, stopped, or launched.
    assertThat(project.resolve("started.marker")).doesNotExist();
  }

  @Test
  void restartTarget_compileFailureDoesNotStopExistingClient() throws Exception {
    Path project = createFakeLauncherProject("compile-fails");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"),
        "# fake launcher",
        StandardCharsets.UTF_8);
    FailingCompileService service = new FailingCompileService(project.toString());

    assertThatThrownBy(
            () ->
                service.restartTarget(
                    "compileFails",
                    "1111111111111111",
                    "user@example.com",
                    "secret",
                    360,
                    null,
                    (BuilderGoalLaunchCancellation) () -> false))
        .isInstanceOf(Micro6TargetRelaunchService.RelaunchException.class)
        .hasMessageContaining("compilation failed")
        .satisfies(
            e -> {
              Micro6TargetRelaunchService.RelaunchException ex =
                  (Micro6TargetRelaunchService.RelaunchException) e;
              assertThat(ex.getFailureKind()).isEqualTo("GRADLE_TASK_FAILED");
            });
    // The existing client must still be running — the compile failure aborted before any stop.
    assertThat(project.resolve("started.marker")).doesNotExist();
  }

  @Test
  void resolveVerifiedExecutorPid_prefersVerifiedHealthPidAndBlocksUnverifiedPortOwner()
      throws Exception {
    Path project = createProjectDir("ownership");
    // Give the service a matching-health result whose port owner is this JVM (a live process the
    // probe would confirm as occupying no port, so ownership verification falls back to the
    // command-line signature check).
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");
    Method resolveMethod =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "resolveVerifiedExecutorPid",
            Integer.class,
            Path.class,
            Micro6TargetRelaunchService.HealthCheckResult.class);
    resolveMethod.setAccessible(true);

    // An unverified listener PID (no health confirmation and no command-line signature) must
    // resolve to null so the caller blocks the replacement instead of killing an arbitrary process.
    Micro6TargetRelaunchService.HealthCheckResult noMatchHealth =
        new Micro6TargetRelaunchService.HealthCheckResult(
            Optional.empty(),
            Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH,
            "test",
            987654321L,
            null,
            null,
            null,
            null,
            false,
            null,
            "accountbuilder_ready",
            "logged_in",
            null,
            null,
            null,
            true,
            null,
            null,
            360,
            360,
            true,
            true,
            "RUNNING",
            true,
            null,
            null);
    Object resolved = resolveMethod.invoke(service, 18998, project, noMatchHealth);
    assertThat(resolved).isNull();
  }

  @Test
  void matchesBuilderGoalCommandSignature_requiresRootAndEntryPoint() throws Exception {
    Path project = createProjectDir("signature");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "matchesBuilderGoalCommandSignature", long.class, Path.class);
    method.setAccessible(true);

    long ownPid = ProcessHandle.current().pid();
    // This JVM's command line is not a Builder Goal client signature, so it must fail closed.
    assertThat(method.invoke(service, ownPid, project)).isEqualTo(false);
    assertThat(method.invoke(service, 999_999_999L, project)).isEqualTo(false);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void terminateVerifiedExecutorTreeKillsOnlyTheVerifiedRootTree() throws Exception {
    ControlledPlatformService service = newUnixService(tempDir.toString(), "", "");
    // An unrelated long-lived process must survive the verified-root termination.
    Process unrelated = new ProcessBuilder("bash", "-c", "sleep 30").start();
    // The verified root with a backgrounded descendant.
    Process root = new ProcessBuilder("bash", "-c", "sleep 30 & sleep 30").start();
    List<ProcessHandle> descendants;
    long deadline = System.currentTimeMillis() + 5_000L;
    while (true) {
      descendants = root.descendants().collect(Collectors.toList());
      if (!descendants.isEmpty() || System.currentTimeMillis() > deadline) {
        break;
      }
      Thread.sleep(20L);
    }
    try {
      assertThat(descendants).isNotEmpty();
      Method method =
          Micro6TargetRelaunchService.class.getDeclaredMethod(
              "terminateVerifiedExecutorTree", long.class);
      method.setAccessible(true);
      method.invoke(service, root.pid());

      assertThat(root.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(root.isAlive()).isFalse();
      for (ProcessHandle descendant : descendants) {
        assertThat(descendant.isAlive()).isFalse();
      }
      // The unrelated process is untouched.
      assertThat(unrelated.isAlive()).isTrue();
    } finally {
      killProcessTreeByPid(unrelated.pid());
    }
  }

  /** Windows-only process-tree test. Skipped on non-Windows hosts. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void terminateOwnedProcessTreeDestroysWindowsCmdTree() throws Exception {
    RecordingTaskkillService service =
        new RecordingTaskkillService(tempDir.resolve("windows-tree").toString());
    // cmd.exe parent spawns a long-lived child; both must be killed by owned-tree cleanup.
    Process root =
        new ProcessBuilder(
                "cmd.exe",
                "/c",
                "start /b cmd.exe /c ping -n 60 127.0.0.1 >nul & ping -n 60 127.0.0.1 >nul")
            .start();
    List<ProcessHandle> descendants;
    long deadline = System.currentTimeMillis() + 8_000L;
    while (true) {
      descendants = root.descendants().collect(Collectors.toList());
      if (!descendants.isEmpty() || System.currentTimeMillis() > deadline) {
        break;
      }
      Thread.sleep(50L);
    }

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "terminateOwnedProcessTree", Process.class);
    method.setAccessible(true);
    method.invoke(service, root);

    assertThat(root.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(root.isAlive()).isFalse();
    for (ProcessHandle descendant : descendants) {
      assertThat(descendant.isAlive()).isFalse();
    }
  }

  @Test
  void buildCompileOnlyCommand_runsPrepareTaskWithoutPayload() throws Exception {
    // Compile-only must never pass a payload or create a client-argument file: it runs
    // :client:prepareBuilderGoal directly with no client arguments.
    ControlledPlatformService service = newWindowsService(tempDir.toString(), "", "");
    Path launcher = tempDir.resolve("launch-buildergoal-client.ps1");
    Files.writeString(launcher, "# fake launcher", StandardCharsets.UTF_8);

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod("buildCompileOnlyCommand", Path.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> command = (List<String>) method.invoke(service, launcher);

    assertThat(command).contains("-CompileOnly");
    assertThat(command).doesNotContain("-PayloadFile");
    // No ChromaScape-owned payload temp file is referenced anywhere in the command.
    assertThat(command.stream().noneMatch(c -> c.contains("buildergoal-launch-"))).isTrue();
    assertThat(command.get(command.indexOf("-File") + 1)).isEqualTo(launcher.toString());
  }

  /**
   * Exercises the real {@code compileMicro6ProductionClasses()} (not an override) against a fake
   * launcher that records its received parameters. Proves the production compile-only preflight
   * invokes the launcher with {@code -CompileOnly} and without {@code -PayloadFile}.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void compileMicro6ProductionClasses_realLauncherCompileOnlySucceeds() throws Exception {
    Path project = createProjectDir("compile-real-success");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"),
        "param([string]$PayloadFile, [switch]$CompileOnly, [switch]$ValidateOnly, [switch]$ContractVersion)\n"
            + "[Console]::Out.Write(($PSBoundParameters.Keys -join ','))\n"
            + "exit 0\n",
        StandardCharsets.UTF_8);
    Path logFile = project.resolve("micro6-recovery-launch.log");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "compileMicro6ProductionClasses",
            Path.class,
            Path.class,
            BuilderGoalLaunchCancellation.class);
    method.setAccessible(true);
    method.invoke(service, project, logFile, null);

    String output = Files.readString(logFile, StandardCharsets.UTF_8);
    assertThat(output).contains("CompileOnly");
    assertThat(output).doesNotContain("PayloadFile");
  }

  /**
   * The real {@code compileMicro6ProductionClasses()} must classify a nonzero launcher exit as
   * {@code GRADLE_TASK_FAILED} (a pre-stop compile failure), proving compile-only failures abort
   * before any existing Micro6 process is stopped.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void compileMicro6ProductionClasses_realLauncherCompileOnlyFailureThrows() throws Exception {
    Path project = createProjectDir("compile-real-failure");
    Files.writeString(
        project.resolve("launch-buildergoal-client.ps1"),
        "param([string]$PayloadFile, [switch]$CompileOnly, [switch]$ValidateOnly, [switch]$ContractVersion)\n"
            + "exit 1\n",
        StandardCharsets.UTF_8);
    Path logFile = project.resolve("micro6-recovery-launch.log");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "compileMicro6ProductionClasses",
            Path.class,
            Path.class,
            BuilderGoalLaunchCancellation.class);
    method.setAccessible(true);
    try {
      method.invoke(service, project, logFile, null);
      throw new AssertionError("expected RelaunchException");
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      assertThat(cause).isInstanceOf(Micro6TargetRelaunchService.RelaunchException.class);
      assertThat(((Micro6TargetRelaunchService.RelaunchException) cause).getFailureKind())
          .isEqualTo("GRADLE_TASK_FAILED");
    }
  }
}
