package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Micro6TargetPreparationServiceTest {

  @Mock private BuilderGoalLauncherControlService launcherControlService;
  @Mock private Micro6TargetRelaunchService micro6TargetRelaunchService;
  @Mock private BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator;

  private Micro6TargetPreparationService service;

  @BeforeEach
  void setUp() {
    lenient().when(launcherControlService.isLocalMachine(any())).thenReturn(true);
    service =
        new Micro6TargetPreparationService(
            launcherControlService, micro6TargetRelaunchService, localLaunchCoordinator);
  }

  @Test
  void remoteCaptureIsRejectedBeforeEitherLauncherRuns() {
    var machine = new BuilderGoalLauncherControlService.LauncherMachine(
        "remote", "Remote", "online", true, true, Map.of());
    when(launcherControlService.findMachineByUuid("remote")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(launcherControlService.isLocalMachine(machine)).thenReturn(false);
    when(localLaunchCoordinator.holdsLease(any())).thenReturn(true);
    var lease = new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
        "remote", 8098, "0123456789abcdef", "owner", java.util.UUID.randomUUID());
    var request = new Micro6TargetPreparationService.PreparationRequest(
        "account", "0123456789abcdef", "Account", "test@example.invalid", "dummy",
        360, "remote", null, false, true);
    assertThatThrownBy(() -> service.ensureReady(request, lease, BuilderGoalLaunchCancellation.NONE, null))
        .hasMessageContaining("Packet Capture requires the local launch host");
    org.mockito.Mockito.verifyNoInteractions(micro6TargetRelaunchService);
  }

  @Test
  void remoteMachineNeverInvokesLocalRelaunch() {
    var remote = org.mockito.Mockito.mock(Micro6RemoteLauncherService.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "remoteLauncher", remote);
    var machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "fedora", "Fedora", "online", true, true, Map.of());
    when(launcherControlService.findMachineByUuid("fedora")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(launcherControlService.isLocalMachine(machine)).thenReturn(false);
    when(localLaunchCoordinator.holdsLease(any())).thenReturn(true);
    var lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "fedora", 8098, "0123456789abcdef", "owner", java.util.UUID.randomUUID());
    var request =
        new Micro6TargetPreparationService.PreparationRequest(
            "account",
            "0123456789abcdef",
            "DogaAGI",
            "test@example.invalid",
            "dummy",
            360,
            "fedora",
            null);
    var expected = new Micro6TargetPreparationService.PreparationResult(null, Map.of(), null, null);
    when(remote.ensureReady(
            eq(request), eq(machine), eq(lease.ownerToken().toString()), any(), any()))
        .thenReturn(expected);
    assertThat(service.ensureReady(request, lease, BuilderGoalLaunchCancellation.NONE, null))
        .isSameAs(expected);
    org.mockito.Mockito.verifyNoInteractions(micro6TargetRelaunchService);
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
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(launcherControlService.findMachineByUuid("machine-1")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(micro6TargetRelaunchService.restartTarget(
            eq("bigGreenMeat"),
            eq("hash-1"),
            eq("IlaWalter280@outlook.com"),
            eq("spook2586"),
            eq(null),
            eq("http://100.120.100.78:8098"),
            eq(null),
            any()))
        .thenReturn(
            new Micro6TargetRelaunchService.RelaunchResult(
                targetClient,
                List.of("cmd.exe", "/c", "gradlew.bat", ":runelite-client:run"),
                12345L,
                Path.of("C:/Dev/IdeaProjects/micro6/micro6-recovery-launch.log"),
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
                false,
                null));

    when(localLaunchCoordinator.holdsLease(any())).thenReturn(true);

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test-recovery", java.util.UUID.randomUUID());
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
                "http://100.120.100.78:8098"),
            leaseHandle,
            BuilderGoalLaunchCancellation.NONE,
            null);

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

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle rejectLease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-2", 8098, "hash-1", "test-reject", java.util.UUID.randomUUID());
    when(localLaunchCoordinator.holdsLease(rejectLease)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-2", null),
                    rejectLease,
                    BuilderGoalLaunchCancellation.NONE,
                    null))
        .isInstanceOf(Micro6TargetPreparationService.PreparationException.class)
        .hasMessage("Builder Goal local dev launches only run on the local ChromaScape host");
  }

  @Test
  void ensureReady_rejectsNullLease() throws Exception {
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-1", null),
                    null,
                    BuilderGoalLaunchCancellation.NONE,
                    null))
        .isInstanceOf(Micro6TargetPreparationService.PreparationException.class)
        .hasMessageContaining("local launch lease is required");
    verify(micro6TargetRelaunchService, never())
        .restartTarget(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void ensureReady_rejectsNullCancellation() {
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test", java.util.UUID.randomUUID());
    lenient().when(localLaunchCoordinator.holdsLease(lease)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-1", null),
                    lease,
                    null,
                    null))
        .isInstanceOf(Micro6TargetPreparationService.PreparationException.class)
        .hasMessageContaining("cancellation handle is required");
  }

  @Test
  void ensureReady_rejectsLeaseLossBeforeRestart() throws Exception {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Local Host", "ONLINE", true, true, Map.of());
    lenient()
        .when(launcherControlService.findMachineByUuid("machine-1"))
        .thenReturn(Optional.of(machine));
    lenient().when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(localLaunchCoordinator.holdsLease(any())).thenReturn(false);

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test-lost", java.util.UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-1", null),
                    lease,
                    BuilderGoalLaunchCancellation.NONE,
                    null))
        .isInstanceOf(Micro6TargetPreparationService.PreparationException.class)
        .hasMessageContaining("lease was lost");
    verify(micro6TargetRelaunchService, never())
        .restartTarget(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void ensureReady_combinedCancellationDetectsLeaseLossMidRelaunch() throws Exception {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Local Host", "ONLINE", true, true, Map.of());
    when(launcherControlService.findMachineByUuid("machine-1")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    AtomicBoolean leaseHeld = new AtomicBoolean(true);
    when(localLaunchCoordinator.holdsLease(any())).thenAnswer(invocation -> leaseHeld.get());

    Micro6OnlineClientRegistryService.TargetClient targetClient =
        new Micro6OnlineClientRegistryService.TargetClient(
            "client-1",
            "bigGreenMeat",
            "hash-1",
            "http://127.0.0.1:8098",
            "0.0.0.0:8098",
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
            null);
    AtomicReference<BuilderGoalLaunchCancellation> capturedCancellation = new AtomicReference<>();
    when(micro6TargetRelaunchService.restartTarget(
            eq("bigGreenMeat"),
            eq("hash-1"),
            eq("IlaWalter280@outlook.com"),
            eq("spook2586"),
            eq(null),
            eq("http://127.0.0.1:8098"),
            eq(null),
            any()))
        .thenAnswer(
            invocation -> {
              capturedCancellation.set(invocation.getArgument(7));
              return new Micro6TargetRelaunchService.RelaunchResult(
                  targetClient,
                  List.of(),
                  12345L,
                  Path.of("C:/micro6/micro6-recovery-launch.log"),
                  null,
                  ":runelite-client:run",
                  360,
                  "http://127.0.0.1:8098",
                  "READY_TO_PUBLISH",
                  null,
                  "bigGreenMeat",
                  "hash-1",
                  "bigGreenMeat",
                  "hash-1",
                  null,
                  "IDLE",
                  false,
                  null,
                  "accountbuilder_ready",
                  "logged_in",
                  0,
                  30,
                  null,
                  false,
                  null);
            });

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test", java.util.UUID.randomUUID());
    service.ensureReady(
        new Micro6TargetPreparationService.PreparationRequest(
            "hash-1",
            "hash-1",
            "bigGreenMeat",
            "IlaWalter280@outlook.com",
            "spook2586",
            null,
            "machine-1",
            "http://127.0.0.1:8098"),
        lease,
        BuilderGoalLaunchCancellation.NONE,
        null);

    assertThat(capturedCancellation.get()).isNotNull();
    assertThat(capturedCancellation.get().isCancelled()).isFalse();

    // Losing the lease makes the combined cancellation report cancelled.
    leaseHeld.set(false);
    assertThat(capturedCancellation.get().isCancelled()).isTrue();
  }

  @Test
  void ensureReady_cancelledBeforeRestartThrows() throws Exception {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Local Host", "ONLINE", true, true, Map.of());
    lenient()
        .when(launcherControlService.findMachineByUuid("machine-1"))
        .thenReturn(Optional.of(machine));
    lenient().when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    lenient().when(localLaunchCoordinator.holdsLease(any())).thenReturn(true);

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test", java.util.UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1", "hash-1", "bigGreenMeat", null, null, null, "machine-1", null),
                    lease,
                    () -> true,
                    null))
        .isInstanceOf(BuilderGoalLaunchCancelledException.class);
    verify(micro6TargetRelaunchService, never())
        .restartTarget(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void ensureReady_propagatesCancellationUnchangedFromRelaunch() throws Exception {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        new BuilderGoalLauncherControlService.LauncherMachine(
            "machine-1", "Local Host", "ONLINE", true, true, Map.of());
    when(launcherControlService.findMachineByUuid("machine-1")).thenReturn(Optional.of(machine));
    when(launcherControlService.isBuilderGoalRunnableMachine(machine)).thenReturn(true);
    when(localLaunchCoordinator.holdsLease(any())).thenReturn(true);
    when(micro6TargetRelaunchService.restartTarget(
            eq("bigGreenMeat"),
            eq("hash-1"),
            eq("user@example.com"),
            eq("secret"),
            eq(null),
            eq("http://127.0.0.1:8098"),
            eq(null),
            any()))
        .thenThrow(new BuilderGoalLaunchCancelledException());

    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease =
        new BuilderGoalLocalLaunchCoordinatorService.LeaseHandle(
            "machine-1", 8098, "hash-1", "test", java.util.UUID.randomUUID());
    assertThatThrownBy(
            () ->
                service.ensureReady(
                    new Micro6TargetPreparationService.PreparationRequest(
                        "hash-1",
                        "hash-1",
                        "bigGreenMeat",
                        "user@example.com",
                        "secret",
                        null,
                        "machine-1",
                        "http://127.0.0.1:8098"),
                    lease,
                    BuilderGoalLaunchCancellation.NONE,
                    null))
        .isInstanceOf(BuilderGoalLaunchCancelledException.class)
        .hasMessage(BuilderGoalLaunchCancelledException.MESSAGE)
        .satisfies(e -> assertThat(((BuilderGoalLaunchCancelledException) e).getCause()).isNull());
  }
}
