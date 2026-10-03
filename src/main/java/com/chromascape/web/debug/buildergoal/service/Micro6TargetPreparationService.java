package com.chromascape.web.debug.buildergoal.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class Micro6TargetPreparationService {

  private static final Logger logger =
      LoggerFactory.getLogger(Micro6TargetPreparationService.class);

  public static final String PHASE_PREPARING_TARGET = "PREPARING_TARGET";
  public static final String PHASE_WAITING_FOR_EXECUTOR = "WAITING_FOR_EXECUTOR";
  public static final String PHASE_READY_TO_PUBLISH = "READY_TO_PUBLISH";
  public static final String PHASE_CHECKING_EXISTING = "CHECKING_EXISTING";
  public static final String PHASE_REUSING_EXECUTOR = "REUSING_EXECUTOR";
  public static final String PHASE_STOPPING_EXISTING = "STOPPING_EXISTING";
  public static final String PHASE_BUILDING_MICRO6 = "BUILDING_MICRO6";
  public static final String PHASE_BUILD_FAILED = "BUILD_FAILED";

  @org.springframework.beans.factory.annotation.Autowired
  private Micro6RemoteLauncherService remoteLauncher;

  private final BuilderGoalLauncherControlService launcherControlService;
  private final Micro6TargetRelaunchService micro6TargetRelaunchService;
  private final BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator;

  public Micro6TargetPreparationService(
      BuilderGoalLauncherControlService launcherControlService,
      Micro6TargetRelaunchService micro6TargetRelaunchService,
      BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator) {
    this.launcherControlService = launcherControlService;
    this.micro6TargetRelaunchService = micro6TargetRelaunchService;
    this.localLaunchCoordinator = localLaunchCoordinator;
  }

  /**
   * Ensures the Micro6 target is ready for a builder-goal launch. Destructive preparation (patching
   * source, compiling, stopping existing processes, launching Java, readiness waits, and republish
   * preparation) is serialized through the local launch coordinator: the caller must hold the
   * supplied lease, which is validated at method entry, after launcher-machine validation, and
   * immediately before the destructive restart, and is folded into the combined cancellation
   * supplied to the relaunch service so lease loss aborts every long-running boundary.
   *
   * <p>Read-only executor inspection is deliberately outside this method; callers use the
   * registry/health inspection path when they only need to observe an executor.
   *
   * @param request preparation request (required)
   * @param lease local launch lease held by the caller (required)
   * @param cancellation worker cancellation (required; may be {@link
   *     BuilderGoalLaunchCancellation#NONE})
   * @param progressListener optional relaunch progress callback
   */
  public PreparationResult ensureReady(
      PreparationRequest request,
      BuilderGoalLocalLaunchCoordinatorService.LeaseHandle lease,
      BuilderGoalLaunchCancellation cancellation,
      Consumer<Micro6TargetRelaunchService.RelaunchProgress> progressListener) {
    // Reject null lease: all destructive ensureReady callers must hold a lease.
    if (lease == null) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET,
          "A local launch lease is required for Micro6 preparation that may stop or start a process");
    }
    if (cancellation == null) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET, "A cancellation handle is required for Micro6 preparation");
    }
    logger.debug(
        "ensureReady called with lease handle owner={} account={}",
        lease.ownerId(),
        lease.accountKey());
    cancellation.throwIfCancelled();
    // Validate the caller still holds the lease before touching Micro6
    if (!localLaunchCoordinator.holdsLease(lease)) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET,
          "Local launch lease was lost before Micro6 preparation could start");
    }
    String accountHash = firstNonBlank(request.accountHash(), request.accountIdentifier());
    String accountName =
        firstNonBlank(request.accountName(), request.accountIdentifier(), accountHash);
    if (accountName == null) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET, "A launcher-backed account name or identifier is required");
    }
    String launcherMachineUuid = firstNonBlank(request.launcherMachineUuid());
    if (launcherMachineUuid == null) {
      throw new PreparationException(PHASE_PREPARING_TARGET, "Select a launcher machine first");
    }

    BuilderGoalLauncherControlService.LauncherMachine selectedMachine =
        launcherControlService
            .findMachineByUuid(launcherMachineUuid)
            .orElseThrow(
                () ->
                    new PreparationException(
                        PHASE_PREPARING_TARGET, "Selected launcher machine was not found"));
    if (!launcherControlService.isBuilderGoalRunnableMachine(selectedMachine)) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET,
          firstNonBlank(
              launcherControlService.getBuilderGoalMachineDisabledReason(selectedMachine),
              "Selected launcher machine is not available for local Builder Goal launches"));
    }

    // Re-validate lease immediately before the destructive restartTarget call
    cancellation.throwIfCancelled();
    if (!localLaunchCoordinator.holdsLease(lease)) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET,
          "Local launch lease was lost before Micro6 restart could proceed");
    }

    // Combine worker cancellation with lease loss and thread interruption so every long-running
    // relaunch boundary aborts cooperatively. Worker cancellation is never replaced by lease
    // checking alone.
    BuilderGoalLaunchCancellation combinedCancellation =
        () ->
            cancellation.isCancelled()
                || Thread.currentThread().isInterrupted()
                || !localLaunchCoordinator.holdsLease(lease);

    if (!launcherControlService.isLocalMachine(selectedMachine)) {
      if (request.packetCapture()) {
        throw new PreparationException(
            PHASE_PREPARING_TARGET, "Packet Capture requires the local launch host");
      }
      return remoteLauncher.ensureReady(
          request,
          selectedMachine,
          lease.ownerToken().toString(),
          combinedCancellation,
          progressListener);
    }

    Micro6TargetRelaunchService.RelaunchResult relaunchResult;
    try {
      relaunchResult =
          request.packetCapture()
              ? micro6TargetRelaunchService.restartTarget(
                  accountName,
                  request.accountHash(),
                  request.accountEmail(),
                  request.accountPassword(),
                  request.world(),
                  request.targetBaseUrl(),
                  progressListener,
                  combinedCancellation,
                  true,
                  true)
              : request.forceRelaunch()
                  ? micro6TargetRelaunchService.restartTarget(
                      accountName,
                      request.accountHash(),
                      request.accountEmail(),
                      request.accountPassword(),
                      request.world(),
                      request.targetBaseUrl(),
                      progressListener,
                      combinedCancellation,
                      true)
                  : micro6TargetRelaunchService.restartTarget(
                      accountName,
                      request.accountHash(),
                      request.accountEmail(),
                      request.accountPassword(),
                      request.world(),
                      request.targetBaseUrl(),
                      progressListener,
                      combinedCancellation);
    } catch (Micro6TargetRelaunchService.RelaunchException e) {
      throw new PreparationException(
          e.getPhase(),
          firstNonBlank(e.getMessage(), "Failed while waiting for local Micro6 executor"),
          e,
          e.isTerminal());
    } catch (BuilderGoalLaunchCancelledException e) {
      // Cooperative cancellation must propagate unchanged so the runner can distinguish it from a
      // terminal launch failure and never convert it into a misleading failure message.
      throw e;
    } catch (Exception e) {
      throw new PreparationException(
          PHASE_PREPARING_TARGET,
          firstNonBlank(e.getMessage(), "Failed to launch local Micro6 workspace"),
          e);
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("state", PHASE_READY_TO_PUBLISH);
    metadata.put("message", "Local Micro6 workspace launched and executor is dispatch-ready");
    metadata.put("launch_mode", "local_micro6_project");
    metadata.put("packetCapture", request.packetCapture());
    metadata.put("launcher_machine_uuid", selectedMachine.machineUuid());
    metadata.put("launcher_machine_name", selectedMachine.displayName());
    metadata.put("launcher_pid", relaunchResult.launcherPid());
    metadata.put(
        "launcher_log_file",
        relaunchResult.launchLogFile() != null ? relaunchResult.launchLogFile().toString() : null);
    metadata.put("launch_args_preview", relaunchResult.maskedLaunchArgs());
    metadata.put("effective_gradle_task", relaunchResult.effectiveGradleTask());
    metadata.put("local_executor_base_url", relaunchResult.localExecutorBaseUrl());
    metadata.put("readiness_phase", relaunchResult.readinessPhase());
    metadata.put("readiness_reason", relaunchResult.readinessReason());
    metadata.put("resolved_account_name", relaunchResult.resolvedAccountName());
    metadata.put("resolved_account_hash", relaunchResult.resolvedAccountHash());
    metadata.put("account_hash_scheme", AccountIdentityContract.HASH_SCHEME);
    metadata.put("requested_account_name", relaunchResult.requestedAccountName());
    metadata.put("requested_account_hash", relaunchResult.requestedAccountHash());
    metadata.put("port_owner_pid", relaunchResult.portOwnerPid());
    metadata.put("observed_executor_status", relaunchResult.observedExecutorStatus());
    metadata.put("dispatch_hold", relaunchResult.dispatchHold());
    metadata.put("dispatch_hold_reason", relaunchResult.dispatchHoldReason());
    metadata.put("startup_state", relaunchResult.startupState());
    metadata.put("login_state", relaunchResult.loginState());
    metadata.put("login_attempt", relaunchResult.loginAttempt());
    metadata.put("login_attempt_max", relaunchResult.loginAttemptMax());
    metadata.put("login_terminal_reason", relaunchResult.loginTerminalReason());
    metadata.put("reused_executor", relaunchResult.reusedExecutor());
    metadata.put("world", relaunchResult.world());
    metadata.put("attempt_id", relaunchResult.attemptId());
    metadata.put("account_name", accountName);
    metadata.put("resolved_from", "local_micro6_project");
    metadata.put("resolved_path_source", "micro6_project_dir");

    return new PreparationResult(relaunchResult.targetClient(), metadata, null, null);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  public record PreparationRequest(
      String accountIdentifier,
      String accountHash,
      String accountName,
      String accountEmail,
      String accountPassword,
      Integer world,
      String launcherMachineUuid,
      String targetBaseUrl,
      boolean forceRelaunch,
      boolean packetCapture) {
    public PreparationRequest(
        String accountIdentifier,
        String accountHash,
        String accountName,
        String accountEmail,
        String accountPassword,
        Integer world,
        String launcherMachineUuid,
        String targetBaseUrl,
        boolean forceRelaunch) {
      this(
          accountIdentifier,
          accountHash,
          accountName,
          accountEmail,
          accountPassword,
          world,
          launcherMachineUuid,
          targetBaseUrl,
          forceRelaunch,
          false);
    }

    public PreparationRequest(
        String accountIdentifier,
        String accountHash,
        String accountName,
        String accountEmail,
        String accountPassword,
        Integer world,
        String launcherMachineUuid,
        String targetBaseUrl) {
      this(
          accountIdentifier,
          accountHash,
          accountName,
          accountEmail,
          accountPassword,
          world,
          launcherMachineUuid,
          targetBaseUrl,
          false);
    }
  }

  public record PreparationResult(
      Micro6OnlineClientRegistryService.TargetClient targetClient,
      Map<String, Object> metadata,
      BuilderGoalLauncherControlService.StartCommandResult startCommand,
      BuilderGoalLauncherControlService.CommandStatus commandStatus) {}

  public static class PreparationException extends IllegalStateException {
    private final String phase;
    private final boolean terminal;

    public PreparationException(String phase, String message) {
      this(phase, message, null, true);
    }

    public PreparationException(String phase, String message, boolean terminal) {
      this(phase, message, null, terminal);
    }

    public PreparationException(String phase, String message, Throwable cause) {
      this(phase, message, cause, true);
    }

    public PreparationException(String phase, String message, Throwable cause, boolean terminal) {
      super(message, cause);
      this.phase = phase;
      this.terminal = terminal;
    }

    public String getPhase() {
      return phase;
    }

    public boolean isTerminal() {
      return terminal;
    }
  }
}
