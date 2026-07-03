package com.chromascape.web.debug.buildergoal.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class Micro6TargetPreparationService {

  public static final String PHASE_PREPARING_TARGET = "PREPARING_TARGET";
  public static final String PHASE_WAITING_FOR_EXECUTOR = "WAITING_FOR_EXECUTOR";
  public static final String PHASE_READY_TO_PUBLISH = "READY_TO_PUBLISH";

  private final BuilderGoalLauncherControlService launcherControlService;
  private final Micro6TargetRelaunchService micro6TargetRelaunchService;

  public Micro6TargetPreparationService(
      BuilderGoalLauncherControlService launcherControlService,
      Micro6TargetRelaunchService micro6TargetRelaunchService) {
    this.launcherControlService = launcherControlService;
    this.micro6TargetRelaunchService = micro6TargetRelaunchService;
  }

  public PreparationResult ensureReady(PreparationRequest request) {
    return ensureReady(request, null);
  }

  public PreparationResult ensureReady(
      PreparationRequest request, Consumer<Micro6TargetRelaunchService.RelaunchProgress> progressListener) {
    String accountHash = firstNonBlank(request.accountHash(), request.accountIdentifier());
    String accountName = firstNonBlank(request.accountName(), request.accountIdentifier(), accountHash);
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

    Micro6TargetRelaunchService.RelaunchResult relaunchResult;
    try {
      relaunchResult =
          micro6TargetRelaunchService.restartTarget(
              accountName,
              request.accountHash(),
              request.accountEmail(),
              request.accountPassword(),
              request.world(),
              request.targetBaseUrl(),
              progressListener);
    } catch (Micro6TargetRelaunchService.RelaunchException e) {
      throw new PreparationException(
          e.getPhase(),
          firstNonBlank(e.getMessage(), "Failed while waiting for local Micro6 executor"),
          e,
          e.isTerminal());
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
    metadata.put("launcher_machine_uuid", selectedMachine.machineUuid());
    metadata.put("launcher_machine_name", selectedMachine.displayName());
    metadata.put("launcher_pid", relaunchResult.launcherPid());
    metadata.put("launcher_log_file", relaunchResult.launchLogFile() != null
        ? relaunchResult.launchLogFile().toString()
        : null);
    metadata.put("launch_args_preview", relaunchResult.maskedLaunchArgs());
    metadata.put("effective_gradle_task", relaunchResult.effectiveGradleTask());
    metadata.put("local_executor_base_url", relaunchResult.localExecutorBaseUrl());
    metadata.put("readiness_phase", relaunchResult.readinessPhase());
    metadata.put("readiness_reason", relaunchResult.readinessReason());
    metadata.put("resolved_account_name", relaunchResult.resolvedAccountName());
    metadata.put("resolved_account_hash", relaunchResult.resolvedAccountHash());
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
      String targetBaseUrl) {}

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
