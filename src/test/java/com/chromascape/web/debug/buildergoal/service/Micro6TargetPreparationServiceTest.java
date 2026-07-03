package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Micro6TargetPreparationServiceTest {

  @Mock private BuilderGoalLauncherControlService launcherControlService;
  @Mock private Micro6TargetRelaunchService micro6TargetRelaunchService;

  private Micro6TargetPreparationService service;

  @BeforeEach
  void setUp() {
    service =
        new Micro6TargetPreparationService(launcherControlService, micro6TargetRelaunchService);
  }

  @Test
  void ensureReady_usesLocalMicro6WorkspaceLaunch() throws Exception {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1",
            "CZC8518C27 (Beheerder)",
            "ONLINE",
            true,
            true,
            Map.of("hostname", "CZC8518C27"));
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "hash-1",
            "http://100.120.100.78:8098",
            "0.0.0.0:8098",
            "micro6",
            "accountbuilder_builderpro",
            "IDLE",
            null,
            Instant.now());
    when(launcherControlService.findMachineByUuid("machine-1")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(
            micro6TargetRelaunchService.restartTarget(
                "bigGreenMeat",
                "hash-1",
                "IlaWalter280@outlook.com",
                "spook2586",
                null,
                "http://100.120.100.78:8098",
                null))
        .thenReturn(
            new Micro6TargetRelaunchService.RelaunchResult(
                targetClient,
                List.of("cmd.exe", "/c", "gradlew.bat", ":runelite-client:run"),
                12345L,
                Path.of("F:/Dev/IdeaProjects/micro6/micro6-recovery-launch.log"),
                "--clean-jagex-launcher --profile=bigGreenMeat --profile-email=IlaWalter280@***:*** --world=380",
                ":runelite-client:run",
                380,
                "http://127.0.0.1:8098",
                "READY_TO_PUBLISH",
                null,
                "bigGreenMeat",
                "hash-1",
                "bigGreenMeat",
                "hash-1",
                22222L,
                "IDLE",
                false,
                "ready_for_dispatch",
                "accountbuilder_ready",
                "logged_in",
                0,
                30,
                null,
                false));

    Micro6TargetPreparationService.PreparationResult result =
        service.ensureReady(
            new Micro6TargetPreparationService.PreparationRequest(
                "hash-1",
                "hash-1",
                "bigGreenMeat",
                "IlaWalter280@outlook.com",
                "spook2586",
                null,
                "machine-1",
                "http://100.120.100.78:8098"));

    assertThat(result.targetClient().accountName()).isEqualTo("bigGreenMeat");
    assertThat(result.metadata().get("launch_mode")).isEqualTo("local_micro6_project");
    assertThat(result.metadata().get("launcher_machine_uuid")).isEqualTo("machine-1");
    assertThat(result.metadata().get("launcher_pid")).isEqualTo(12345L);
    assertThat(result.metadata().get("launch_args_preview"))
        .isEqualTo(
            "--clean-jagex-launcher --profile=bigGreenMeat --profile-email=IlaWalter280@***:*** --world=380");
    assertThat(result.startCommand()).isNull();
    assertThat(result.commandStatus()).isNull();
    verify(launcherControlService, never()).startAccount("hash-1", "machine-1");
  }

  @Test
  void ensureReady_rejectsNonLocalMachineForBuilderGoalFlow() {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-2", "Remote Host", "ONLINE", true, true, Map.of());
    when(launcherControlService.findMachineByUuid("machine-2")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(false);
    when(launcherControlService.getBuilderGoalMachineDisabledReason(machine))
        .thenReturn("Builder Goal local dev launches only run on the local ChromaScape host");

    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-2", null)))
        .isInstanceOf(Micro6TargetPreparationService.PreparationException.class)
        .hasMessage("Builder Goal local dev launches only run on the local ChromaScape host");
  }
}
