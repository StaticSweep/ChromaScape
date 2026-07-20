package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Micro6TargetRelaunchServiceTest {

  @Test
  void applyBuilderGoalEnvironmentSetsLeeuwbotApiUrlAndExecutorFlags() throws Exception {
    Micro6TargetRelaunchService service =
        new Micro6TargetRelaunchService(
            new ObjectMapper(),
            "F:/Dev/IdeaProjects/micro6",
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
  void buildRunArgsAlwaysRequestsCleanAutoLoginForSelectedProfile() throws Exception {
    Micro6TargetRelaunchService service =
        new Micro6TargetRelaunchService(
            new ObjectMapper(),
            "F:/Dev/IdeaProjects/micro6",
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
            "buildRunArgs", String.class, String.class, String.class, Integer.class);
    method.setAccessible(true);
    String runArgs =
        (String) method.invoke(service, "bigGreenMeat", "user@example.com", "secret", 360);

    assertThat(runArgs)
        .contains("--clean-jagex-launcher")
        .contains("--auto-enable-accountbuilder")
        .contains("--profile=bigGreenMeat")
        .contains("--profile-email=user@example.com:secret")
        .contains("--world=360")
        .contains("--extra-flag");
  }

  @Test
  void extractLaunchFailureDetailPrefersNewestKnownFailureOverOlderGradleBlock() throws Exception {
    Micro6TargetRelaunchService service =
        new Micro6TargetRelaunchService(
            new ObjectMapper(),
            "F:/Dev/IdeaProjects/micro6",
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
