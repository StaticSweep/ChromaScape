package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Micro6TargetRelaunchServiceTest {

  /**
   * Test subclass that pins the platform so launch-mode resolution is deterministic regardless
   * of the machine the suite runs on.
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
          "http://127.0.0.1:8098",
          "http://localhost:8086",
          false);
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

  private void invokeValidate(Micro6TargetRelaunchService service, Path projectDir)
      throws Exception {
    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "validateMicro6ProjectDir", Path.class);
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
            "http://localhost:8086",
            false);

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
            "http://localhost:8086",
            false);

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "buildClientArgs", String.class, String.class, String.class, Integer.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> clientArgs =
        (List<String>) method.invoke(service, "bigGreenMeat", "user@example.com", "secret", 360);

    assertThat(clientArgs)
        .contains("--clean-jagex-launcher")
        .contains("--auto-enable-accountbuilder")
        .contains("--profile=bigGreenMeat")
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
    Files.writeString(project.resolve("launch-buildergoal-client.ps1"), "param([string]$PayloadFile)");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    invokeValidate(service, project);
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
  void windowsCleanupScriptMatchesBothWindowsPathStylesWithoutPowerShellPidVariable() throws Exception {
    Path project = createProjectDir("cleanup-project");
    ControlledPlatformService service = newWindowsService(project.toString(), "", "");

    Method method =
        Micro6TargetRelaunchService.class.getDeclaredMethod(
            "buildWindowsCleanupScript", Integer.class, Path.class);
    method.setAccessible(true);
    String script = (String) method.invoke(service, 8098, project);

    assertThat(script)
        .contains("$portPid")
        .contains("$targetProcessId")
        .contains(project.toAbsolutePath().toString())
        .contains(project.toAbsolutePath().toString().replace('\\', '/'))
        .doesNotContain("foreach ($pid in");
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
}
