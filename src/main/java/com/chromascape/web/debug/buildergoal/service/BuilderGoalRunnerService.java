package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisStatus;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2EvidenceSelector;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.chromascape.web.debug.patchloop.dto.AnalysisV2Target;
import com.chromascape.web.debug.patchloop.dto.EvidenceType;
import com.chromascape.web.debug.patchloop.dto.PromptType;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class BuilderGoalRunnerService {

  private static final Logger logger = LoggerFactory.getLogger(BuilderGoalRunnerService.class);

  public static final String SOURCE_TYPE_BUILDER_GOAL = "builder_goal";
  public static final String PATCH_TARGET_MICRO6_TASK_SOURCE = "micro6_task_source";

  private static final String EXECUTION_MODE_MANUAL_RECOVERY = "manual_recovery";
  private static final String ITERATION_ID_PREFIX = "buildergoal_";
  private static final String ANALYSIS_MODE_OPERATOR_EDITED_MCP = "OPERATOR_EDITED_MCP";
  private static final String RECOVERY_AWAITING_OPERATOR_PROMPT = "AWAITING_OPERATOR_PROMPT";
  private static final String RECOVERY_PATCH_PLAN_READY = "PATCH_PLAN_READY";
  private static final String RECOVERY_APPLYING_PATCH = "APPLYING_PATCH";
  private static final String RECOVERY_RESTARTING_TARGET = "RESTARTING_TARGET";
  private static final String RECOVERY_RELOGGING_IN = "RELOGGING_IN";
  private static final String RECOVERY_WAITING_FOR_EXECUTOR = "WAITING_FOR_EXECUTOR";
  private static final String RECOVERY_REPUBLISHING = "REPUBLISHING";
  private static final String RECOVERY_WAITING_FOR_DEBUG_BIND = "WAITING_FOR_DEBUG_BIND";
  private static final String RECOVERY_RELAUNCHED = "RELAUNCHED";
  private static final String RECOVERY_FAILED = "RECOVERY_FAILED";
  private static final String PATCH_LOOP_DEFAULT_TEMPLATE =
      """
      graph TD;
      A[start] --> B[run_script]
      B --> C[collect_evidence]
      C --> D[analyze]
      D --> E[generate_patch]
      E --> F[apply_patch]
      F --> G[validate]
      G --> H[retest]
      H --> I[evaluate_success]
      I -.success.-> J[end]
      I -.retry.-> D
      I -.failure.-> J
      """;
  private static final String PREPARATION_STATE_PREPARING_TARGET = "PREPARING_TARGET";
  private static final String PREPARATION_STATE_WAITING_FOR_AGENT = "WAITING_FOR_AGENT";
  private static final String PREPARATION_STATE_LOGGING_IN = "LOGGING_IN";
  private static final String PREPARATION_STATE_READY_TO_PUBLISH = "READY_TO_PUBLISH";
  private static final String PREPARATION_STATE_WAITING_FOR_LAUNCH_LEASE =
      "WAITING_FOR_LOCAL_LAUNCH_LEASE";
  private static final String DISPLAY_STATUS_WAITING_FOR_TASK_START = "WAITING_FOR_TASK_START";
  private static final String DISPLAY_STATUS_WAITING_FOR_DEBUG_BIND = "WAITING_FOR_DEBUG_BIND";
  private static final String DISPLAY_STATUS_AWAITING_EXECUTOR_CONFIRMATION =
      "AWAITING_EXECUTOR_CONFIRMATION";
  private static final String TASK_METADATA_PUBLISH_ONLY = "publish_only";
  private static final String TASK_METADATA_LAUNCH_FALLBACK_ALLOWED = "launch_fallback_allowed";

  private static final Map<String, Object> PROVENANCE_AUTOMATION =
      Map.of(
          "initiated_by",
          "automation",
          "input_origin",
          "automation",
          "user_clicked",
          false,
          "script_clicked",
          true);

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopPersistenceService persistenceService;
  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopSessionResolverService sessionResolverService;
  private final ScriptExecutionService scriptExecutionService;
  private final AnalysisProvider analysisProvider;
  private final Micro6ExecutorCatalogService micro6ExecutorCatalogService;
  private final LogRepository logRepository;
  private final BuilderGoalLauncherControlService launcherControlService;
  private final BuilderGoalAccountCatalogService accountCatalogService;
  private final Micro6OnlineClientRegistryService clientRegistryService;
  private final Micro6TaskSourcePatchService micro6TaskSourcePatchService;
  private final Micro6TargetRelaunchService micro6TargetRelaunchService;
  private final Micro6TargetPreparationService targetPreparationService;
  private final BuilderGoalRunStateMutationService mutationService;
  private final BuilderGoalLaunchOperationService launchOperationService;
  private final BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator;
  private final ObjectMapper objectMapper;
  private final Executor patchLoopExecutor;
  private final Executor launchExecutor;
  private final long runTimeoutMs;
  private final int maxConsecutiveStatusFailures;
  private final long statusFailureGraceMs;
  private final int executorPort;
  private final Set<String> pendingLaunchIterations = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<String, ActiveLaunchWork> activeLaunchWork =
      new ConcurrentHashMap<>();

  /**
   * Tracks an active launch worker so the watchdog and operator stop can cancel and interrupt it.
   * Created and registered under its owner ID before executor submission so the watchdog can never
   * race a not-yet-registered worker.
   */
  private static final class ActiveLaunchWork implements BuilderGoalLaunchCancellation {
    final BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle;
    final java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    final java.util.concurrent.atomic.AtomicReference<Thread> workerThread =
        new java.util.concurrent.atomic.AtomicReference<>();

    ActiveLaunchWork(BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle) {
      this.leaseHandle = leaseHandle;
    }

    /** Attaches the executing worker thread as its first action. */
    void attachCurrentThread() {
      workerThread.set(Thread.currentThread());
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void cancel() {
      cancelled.set(true);
      Thread thread = workerThread.get();
      if (thread != null && thread.isAlive()) {
        thread.interrupt();
      }
    }
  }

  public BuilderGoalRunnerService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopPersistenceService persistenceService,
      PatchLoopEvidenceService evidenceService,
      PatchLoopSessionResolverService sessionResolverService,
      ScriptExecutionService scriptExecutionService,
      AnalysisProvider analysisProvider,
      Micro6ExecutorCatalogService micro6ExecutorCatalogService,
      LogRepository logRepository,
      BuilderGoalLauncherControlService launcherControlService,
      BuilderGoalAccountCatalogService accountCatalogService,
      Micro6OnlineClientRegistryService clientRegistryService,
      Micro6TaskSourcePatchService micro6TaskSourcePatchService,
      Micro6TargetRelaunchService micro6TargetRelaunchService,
      Micro6TargetPreparationService targetPreparationService,
      BuilderGoalRunStateMutationService mutationService,
      BuilderGoalLaunchOperationService launchOperationService,
      BuilderGoalLocalLaunchCoordinatorService localLaunchCoordinator,
      ObjectMapper objectMapper,
      @Qualifier("patchLoopExecutor") Executor patchLoopExecutor,
      @Qualifier("builderGoalLaunchExecutor") Executor launchExecutor,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String micro6ExecutorBaseUrl,
      @Value("${builder-goal.run-timeout-ms:300000}") long runTimeoutMs,
      @Value("${builder-goal.status-sync-max-consecutive-failures:3}")
          int maxConsecutiveStatusFailures,
      @Value("${builder-goal.status-sync-failure-grace-ms:15000}") long statusFailureGraceMs) {
    this.iterationRepository = iterationRepository;
    this.persistenceService = persistenceService;
    this.evidenceService = evidenceService;
    this.sessionResolverService = sessionResolverService;
    this.scriptExecutionService = scriptExecutionService;
    this.analysisProvider = analysisProvider;
    this.micro6ExecutorCatalogService = micro6ExecutorCatalogService;
    this.logRepository = logRepository;
    this.launcherControlService = launcherControlService;
    this.accountCatalogService = accountCatalogService;
    this.clientRegistryService = clientRegistryService;
    this.micro6TaskSourcePatchService = micro6TaskSourcePatchService;
    this.micro6TargetRelaunchService = micro6TargetRelaunchService;
    this.targetPreparationService = targetPreparationService;
    this.mutationService = mutationService;
    this.launchOperationService = launchOperationService;
    this.localLaunchCoordinator = localLaunchCoordinator;
    this.objectMapper = objectMapper;
    this.patchLoopExecutor = patchLoopExecutor;
    this.launchExecutor = launchExecutor;
    this.executorPort = resolveExecutorPort(micro6ExecutorBaseUrl);
    this.runTimeoutMs = runTimeoutMs;
    this.maxConsecutiveStatusFailures = maxConsecutiveStatusFailures;
    this.statusFailureGraceMs = statusFailureGraceMs;
  }

  public Map<String, Object> importPayload(JsonNode payload) {
    NormalizedPayload normalized = normalizePayload(payload);
    BuilderGoalCatalogSnapshot catalogSnapshot = getBuilderGoalCatalogSnapshot(false);
    JsonNode catalogTask =
        findTaskInCatalog(normalized.taskKey(), catalogSnapshot.catalogPayload());
    String canonicalTaskKey = canonicalTaskKey(catalogTask, normalized.taskKey());
    if (!canonicalTaskKey.equals(normalized.taskKey())) {
      normalized =
          new NormalizedPayload(
              canonicalTaskKey, normalized.displayName(), normalized.settings(), normalized.world());
    }
    return buildImportResponse(normalized, catalogTask, catalogSnapshot);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listAccounts() {
    return accountCatalogService.listAccounts();
  }

  /**
   * Starts an asynchronous Builder Goal account launch and returns the launch-operation view.
   *
   * <p>Validation (account, credentials, world, launcher machine) happens synchronously so bad
   * requests fail fast. The request then always queues the shared lease-protected preparation
   * ({@link Micro6TargetPreparationService#ensureReady}) which inspects any existing executor under
   * the local launch lease, reuses only a matching dispatch-ready executor, and otherwise replaces
   * non-ready, unreachable, or mismatched executors. The operation finishes at {@code READY} when
   * the executor is dispatch-ready and never publishes a task.
   *
   * <p>Both Launch Account and Run Task serialize through the same shared preparation service under
   * the local machine/port lease, eliminating the pre-lease reuse race.
   *
   * <p>The server-side readiness watchdog (90 minutes by default) controls how long a launch waits;
   * browser lifetime never does.
   */
  public Map<String, Object> launchAccount(
      String accountId, String launcherMachineUuid, Integer world) {
    return launchAccount(accountId, launcherMachineUuid, world, false);
  }

  public Map<String, Object> launchAccount(
      String accountId, String launcherMachineUuid, Integer world, boolean forceRelaunch) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (launcherMachineUuid == null || launcherMachineUuid.isBlank()) {
      throw new IllegalArgumentException("launcherMachineUuid is required");
    }

    BuilderGoalAccountCatalogService.AccountDefinition selectedAccount =
        resolveSelectedAccount(accountId);
    requireLaunchReadyAccount(selectedAccount);
    requireValidWorld(world);
    requireLaunchableMachine(launcherMachineUuid);

    String accountKey = firstNonBlank(selectedAccount.accountHash(), selectedAccount.accountId());
    BuilderGoalLaunchOperationService.CreateResult createResult =
        launchOperationService.createOrReuse(
            selectedAccount.accountId(),
            selectedAccount.accountHash(),
            selectedAccount.displayName(),
            launcherMachineUuid,
            world);
    BuilderGoalLaunchOperationService.LaunchOperation operation = createResult.operation();
    if (createResult.deduplicated()) {
      logger.info(
          "Deduplicating Builder Goal launch for account {} onto active operation {}",
          accountKey,
          operation.getOperationId());
      return launchOperationService.toResponse(operation.getOperationId(), true);
    }

    launchOperationService.updateStatus(
        operation.getOperationId(),
        BuilderGoalLaunchOperationService.STATUS_CHECKING_EXISTING,
        "Checking for an existing matching Micro6 executor");

    launchOperationService.updateStatus(
        operation.getOperationId(),
        BuilderGoalLaunchOperationService.STATUS_QUEUED,
        "Queued local Micro6 workspace launch");

    // Serialize via the local launch coordinator
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult acquireResult =
        localLaunchCoordinator.tryAcquire(
            launcherMachineUuid, executorPort, accountKey, operation.getOperationId());
    if (acquireResult.conflict() != null) {
      BuilderGoalLocalLaunchCoordinatorService.Conflict conflict = acquireResult.conflict();
      launchOperationService.markConflict(
          operation.getOperationId(),
          "Another workflow ("
              + conflict.workflowType()
              + ", owner="
              + conflict.ownerId()
              + ") currently holds the local launch lease. Wait for that workflow to complete (or"
              + " fail) before launching again.");
      return launchOperationService.toResponse(operation.getOperationId(), false);
    }
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle = acquireResult.handle();

    // Register active work before executor submission so the watchdog can never observe an
    // unregistered worker; the worker attaches its thread as its first action.
    ActiveLaunchWork work = new ActiveLaunchWork(leaseHandle);
    activeLaunchWork.put(operation.getOperationId(), work);

    try {
      launchExecutor.execute(
          () -> {
            work.attachCurrentThread();
            try {
              continueLaunchOperation(
                  operation.getOperationId(),
                  selectedAccount,
                  launcherMachineUuid,
                  world,
                  forceRelaunch,
                  leaseHandle,
                  work);
            } finally {
              activeLaunchWork.remove(operation.getOperationId(), work);
              localLaunchCoordinator.release(leaseHandle);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      activeLaunchWork.remove(operation.getOperationId(), work);
      localLaunchCoordinator.release(leaseHandle);
      launchOperationService.clearActiveAndTransition(
          operation.getOperationId(), "Launch executor is at capacity. Try again in a moment.");
      throw new LaunchSubmissionException("Launch executor cannot accept the request right now");
    }
    return launchOperationService.toResponse(operation.getOperationId(), false);
  }

  /**
   * Returns the current view of a launch operation for the polling endpoint. Throws {@link
   * IllegalArgumentException} when the operation is unknown or expired.
   */
  public Map<String, Object> getLaunchOperation(String operationId) {
    return launchOperationService.toResponse(operationId, false);
  }

  private void continueLaunchOperation(
      String operationId,
      BuilderGoalAccountCatalogService.AccountDefinition selectedAccount,
      String launcherMachineUuid,
      Integer world,
      boolean forceRelaunch,
      BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle,
      ActiveLaunchWork work) {
    // Check cancellation before touching Micro6
    if (work.isCancelled()) {
      handleCancellation(operationId);
      return;
    }
    // Verify the operation is still active and the lease is held before touching Micro6
    BuilderGoalLaunchOperationService.LaunchOperation operation =
        launchOperationService.get(operationId).orElse(null);
    if (operation == null || operation.isTerminal()) {
      logger.info(
          "Launch operation {} is no longer active; exiting without launching", operationId);
      return;
    }
    if (!localLaunchCoordinator.holdsLease(leaseHandle)) {
      logger.warn("Lease expired for launch operation {}; exiting without launching", operationId);
      return;
    }
    try {
      // Check cancellation before starting Micro6 preparation
      if (work.isCancelled()) {
        handleCancellation(operationId);
        return;
      }
      Micro6TargetPreparationService.PreparationResult preparationResult =
          targetPreparationService.ensureReady(
              new Micro6TargetPreparationService.PreparationRequest(
                  firstNonBlank(selectedAccount.accountHash(), selectedAccount.accountId()),
                  selectedAccount.accountHash(),
                  firstNonBlank(selectedAccount.launchProfileName(), selectedAccount.displayName()),
                  selectedAccount.email(),
                  selectedAccount.password(),
                  world,
                  launcherMachineUuid,
                  null,
                  forceRelaunch),
              leaseHandle,
              work,
              progress -> launchOperationService.updateProgress(operationId, progress));
      // Mark READY only if the operation is still active and cancellation has not occurred.
      if (!work.isCancelled()) {
        launchOperationService.markReady(
            operationId, preparationResult.targetClient(), preparationResult.metadata(), false);
      }
    } catch (BuilderGoalLaunchCancelledException e) {
      // Cancellation escaped from Micro6 preparation/relaunch (watchdog, operator Stop, or
      // unexpected lease loss). If the canceller already terminalized the operation, perform no
      // transition; otherwise mark the workflow FAILED with a credential-free reason so the
      // launch never hangs in an active state.
      handleCancellation(operationId, "Launch cancelled (lease lost or stopped)");
    } catch (Micro6TargetPreparationService.PreparationException e) {
      String launchLogFile = null;
      String failureKind = null;
      String failureDetail = null;
      String attemptId = null;
      if (e.getCause() instanceof Micro6TargetRelaunchService.RelaunchException relaunchException) {
        launchLogFile =
            relaunchException.getLaunchLogFile() != null
                ? relaunchException.getLaunchLogFile().toString()
                : null;
        failureKind = relaunchException.getFailureKind();
        failureDetail = relaunchException.getFailureDetail();
        attemptId = relaunchException.getAttemptId();
      }
      launchOperationService.markFailed(
          operationId,
          firstNonBlank(e.getMessage(), "Failed to launch local Micro6 workspace"),
          e.getPhase(),
          launchLogFile);
      launchOperationService.preserveFailureDetails(
          operationId, failureKind, failureDetail, attemptId);
    } catch (Exception e) {
      launchOperationService.markFailed(
          operationId,
          firstNonBlank(e.getMessage(), "Failed to launch local Micro6 workspace"),
          null,
          null);
    }
  }

  private boolean isCancelled(String operationId) {
    ActiveLaunchWork work = activeLaunchWork.get(operationId);
    return work != null && work.isCancelled();
  }

  private void handleCancellation(String operationId) {
    handleCancellation(operationId, "Launch cancelled by watchdog timeout");
  }

  private void handleCancellation(String operationId, String reason) {
    // Only transition if the watchdog hasn't already marked this operation FAILED
    BuilderGoalLaunchOperationService.LaunchOperation operation =
        launchOperationService.get(operationId).orElse(null);
    if (operation == null || operation.isTerminal()) {
      logger.info(
          "Launch operation {} already terminal; skipping cancellation transition", operationId);
      return;
    }
    launchOperationService.markFailed(
        operationId, reason, BuilderGoalLaunchOperationService.STATUS_FAILED, null);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listCatalog(boolean refresh) {
    BuilderGoalCatalogSnapshot snapshot = getBuilderGoalCatalogSnapshot(refresh);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("tasks", snapshot.tasks());
    response.put("catalogAvailable", snapshot.catalogAvailable());
    response.put("degraded", snapshot.degraded());
    response.put("degradedReason", snapshot.degradedReason());
    response.put("source", snapshot.source());
    return response;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getLatestConfig(String accountId, String taskKey) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (taskKey == null || taskKey.isBlank()) {
      throw new IllegalArgumentException("taskKey is required");
    }
    String normalizedTaskKey = normalizeTaskKey(taskKey);
    return iterationRepository
        .findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL)
        .stream()
        .filter(this::hasStoredConfig)
        .filter(
            iteration -> {
              Map<String, Object> accountMetadata =
                  readAccountMetadata(readTaskMetadata(iteration));
              String storedAccountId =
                  firstNonBlank(
                      stringValue(accountMetadata.get("account_id")),
                      stringValue(accountMetadata.get("account_hash")));
              if (!accountId.equals(storedAccountId)) {
                return false;
              }
              return normalizeTaskKey(iteration.getScriptName()).equals(normalizedTaskKey);
            })
        .findFirst()
        .map(this::buildStoredConfigResponse)
        .orElseGet(
            () -> {
              Map<String, Object> response = new LinkedHashMap<>();
              response.put("success", true);
              response.put("configAvailable", false);
              response.put("accountId", accountId);
              response.put("taskKey", taskKey);
              response.put("settings", Map.of());
              return response;
            });
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRunConfig(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    if (!hasStoredConfig(iteration)) {
      throw new IllegalStateException("This builder goal run does not have a saved launch config");
    }
    return buildStoredConfigResponse(iteration);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRunLogs(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> run = toRunSummary(iteration);
    LocalDateTime windowStart = resolveLogWindowStart(iteration);
    LocalDateTime windowEnd = resolveLogWindowEnd(iteration);
    List<LogEntity> windowLogs =
        logRepository.findByTimestampBetweenOrderByTimestampAsc(windowStart, windowEnd);
    List<Map<String, Object>> matchedLogs = buildMatchedRunLogs(iteration, run, windowLogs);
    boolean fallbackToWindow = matchedLogs.isEmpty();
    List<Map<String, Object>> renderedLogs =
        fallbackToWindow ? buildWindowLogViews(windowLogs) : matchedLogs;
    Map<String, Object> launchLog = readLaunchLogTail(run);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("run", run);
    response.put(
        "filter", buildRunLogFilter(iteration, run, windowStart, windowEnd, fallbackToWindow));
    response.put("historicalLogs", renderedLogs);
    response.put("windowLogCount", windowLogs.size());
    response.put("matchedLogCount", matchedLogs.size());
    response.put("launchLog", launchLog);
    return response;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRunLogsByRecordingId(String recordingId) {
    PatchLoopIteration iteration = loadBuilderRunByRecordingId(recordingId);
    return getRunLogs(iteration.getIterationId());
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRunSummary(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    return toRunSummary(iteration);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRunSummaryByRecordingId(String recordingId) {
    PatchLoopIteration iteration = loadBuilderRunByRecordingId(recordingId);
    return toRunSummary(iteration);
  }

  @Transactional
  public Map<String, Object> rerun(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    String blockedReason = determineRerunBlockedReason(iteration);
    if (blockedReason != null) {
      throw new IllegalStateException(blockedReason);
    }
    Map<String, Object> accountMetadata = readAccountMetadata(readTaskMetadata(iteration));
    String accountId =
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash")));
    String launcherMachineUuid = stringValue(accountMetadata.get("launcher_machine_uuid"));
    NormalizedPayload normalized = normalizeStoredIteration(iteration);
    return startCatalogTaskRun(
        accountId,
        launcherMachineUuid,
        normalized.taskKey(),
        normalized.settings(),
        normalized.displayName(),
        normalized.world(),
        PROVENANCE_AUTOMATION);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> buildAnalysisDraft(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    String blockedReason = determineAnalyzeBlockedReason(iteration, publishStatus);

    AnalysisV1EvidenceBundle evidence = evidenceService.collectEvidence(iteration, null);
    AnalysisPromptData defaults = buildDefaultAnalysisPromptData(iteration);
    AnalysisInputMetadata existingInput = readAnalysisInputMetadata(taskMetadata);
    TimeRangeSelection timeRange = resolveTimeRangeSelection(evidence, existingInput);
    AnalysisV1EvidenceBundle clippedEvidence = clipEvidenceToTimeRange(evidence, timeRange);
    SelectionResult selectionResult =
        buildSelectionResult(clippedEvidence, existingInput.selectedEvidenceIds(), true);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iterationId);
    response.put("canAnalyze", blockedReason == null);
    response.put("analysisBlockedReason", blockedReason);
    response.put(
        "prompt", existingInput.prompt() != null ? existingInput.prompt() : defaults.prompt());
    response.put(
        "context", existingInput.context() != null ? existingInput.context() : defaults.context());
    response.put("evidenceGroups", selectionResult.groups());
    response.put("selectedEvidenceCount", selectionResult.selectedCount());
    response.put("totalEvidenceCount", selectionResult.totalCount());
    response.put("hasCustomPrompt", existingInput.customized());
    response.put("timeRangeStartSeconds", timeRange.startSeconds());
    response.put("timeRangeEndSeconds", timeRange.endSeconds());
    response.put("timeRangeMaxSeconds", timeRange.maxSeconds());
    response.put("timeRangeStartDisplay", formatDurationSeconds(timeRange.startSeconds()));
    response.put("timeRangeEndDisplay", formatDurationSeconds(timeRange.endSeconds()));
    response.put("timeRangeMaxDisplay", formatDurationSeconds(timeRange.maxSeconds()));
    return response;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listRuns(int limit) {
    return iterationRepository
        .findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL)
        .stream()
        .limit(Math.max(1, limit))
        .map(this::toRunSummary)
        .collect(Collectors.toList());
  }

  @Transactional
  public Map<String, Object> startRun(
      JsonNode payload,
      String accountId,
      String targetClientId,
      String launchProfileName,
      String launchExtraArgs,
      String launcherMachineUuid,
      Integer world,
      Map<String, Object> provenance) {
    rejectPublicTargetOverride(payload);
    NormalizedPayload normalized = normalizePayload(payload);
    JsonNode canonicalCatalogTask = resolveCatalogTask(normalized.taskKey());
    String canonicalTaskKey = canonicalTaskKey(canonicalCatalogTask, normalized.taskKey());
    if (!canonicalTaskKey.equals(normalized.taskKey())) {
      normalized =
          new NormalizedPayload(
              canonicalTaskKey, normalized.displayName(), normalized.settings(), normalized.world());
    }
    BuilderGoalAccountCatalogService.AccountDefinition selectedAccount =
        resolveSelectedAccount(accountId);
    if (selectedAccount == null) {
      throw new IllegalArgumentException("Select a launcher account first");
    }
    String launchConflictReason =
        findActiveLaunchConflictReason(
            firstNonBlank(selectedAccount.accountId(), selectedAccount.accountHash()),
            normalized.taskKey());
    if (launchConflictReason != null) {
      throw new IllegalStateException(launchConflictReason);
    }
    String selectedMachineUuid = normalizeOptionalString(launcherMachineUuid);
    if (selectedMachineUuid == null) {
      throw new IllegalArgumentException("Select a launcher machine first");
    }
    BuilderGoalLauncherControlService.LauncherMachine selectedMachine =
        resolveSelectedMachine(selectedMachineUuid);
    requireLaunchReadyAccount(selectedAccount);
    requireLaunchableMachine(selectedMachine, selectedMachineUuid);
    CatalogValidationResult catalogValidation =
        canonicalCatalogTask != null
            ? CatalogValidationResult.validated("VALIDATED_LIVE", canonicalCatalogTask)
            : CatalogValidationResult.skipped("SKIPPED_CATALOG_MISS");
    if (catalogValidation.rejectPublish()) {
      throw new IllegalArgumentException(catalogValidation.errorMessage());
    }

    Map<String, Object> initialTargetMetadata =
        buildRequestedTargetMetadataFromAccount(selectedAccount, selectedMachine);
    PatchLoopIteration iteration =
        createRunIteration(
            normalized,
            payload,
            null,
            null,
            initialTargetMetadata,
            catalogValidation.catalogTask(),
            provenance);
    applyCatalogValidationMetadata(iteration, catalogValidation);

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("mule_account", buildAccountMetadata(selectedAccount, selectedMachine));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    updatePreparationState(
        iteration,
        taskMetadata,
        PREPARATION_STATE_PREPARING_TARGET,
        "Launch accepted. Waiting for the local Micro6 workspace and executor readiness.");
    queuePendingLaunch(iteration.getIterationId());

    iteration = iterationRepository.findByIterationId(iteration.getIterationId()).orElse(iteration);
    String iterationId = iteration.getIterationId();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("run", toRunSummary(iteration));
    response.put("statusUrl", "/api/debug/builder-goals/runs/" + iterationId);
    response.put(
        "import",
        buildImportResponse(
            normalized, catalogValidation.catalogTask(), getBuilderGoalCatalogSnapshot(false)));
    response.put("accepted", true);
    response.put("published", null);
    response.put("entryId", null);
    response.put("targetClientId", null);
    response.put("targetAccountName", selectedAccount.displayName());
    response.put("accountId", selectedAccount.accountId());
    response.put("accountHash", selectedAccount.accountHash());
    response.put("accountName", selectedAccount.displayName());
    response.put("publishStatus", null);
    response.put("publishError", null);
    response.put("catalogValidation", catalogValidation.status());
    response.put("catalogValidationSkipped", catalogValidation.skipped());
    response.put("targetPreparation", readTargetPreparationMetadata(readTaskMetadata(iteration)));
    return response;
  }

  @Transactional
  public Map<String, Object> startCatalogTaskRun(
      String accountId,
      String launcherMachineUuid,
      String taskKey,
      Map<String, Object> settings,
      String displayName,
      Integer world,
      Map<String, Object> provenance) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (taskKey == null || taskKey.isBlank()) {
      throw new IllegalArgumentException("taskKey is required");
    }
    Map<String, Object> acceptedSettings = normalizeSettings(settings);
    JsonNode catalogTask = resolveCatalogTask(taskKey);
    String canonicalTaskKey = canonicalTaskKey(catalogTask, taskKey);
    String resolvedDisplayName =
        firstNonBlank(
            displayName,
            catalogTask != null ? textValue(catalogTask.path("display_name")) : null,
            catalogTask != null ? textValue(catalogTask.path("panel_display_name")) : null,
            taskKey);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("task_key", canonicalTaskKey);
    payload.put("title", resolvedDisplayName);
    payload.put("settings", acceptedSettings);
    payload.put("source", "catalog_task");
    payload.put("account_id", accountId);
    if (world != null) {
      payload.put("world", world);
    }
    return startRun(
        objectMapper.valueToTree(payload),
        accountId,
        null,
        null,
        null,
        launcherMachineUuid,
        world,
        provenance);
  }

  /**
   * Converts an unexpected controller-bound start failure into the stable, credential-free
   * response contract. The richer persisted recovery path can replace this compatibility method
   * without changing the controller API.
   */
  public BuilderGoalStartException recordStartFailure(
      JsonNode payload,
      String accountId,
      String launcherMachineUuid,
      Integer world,
      Throwable failure,
      String correlationId) {
    String message = failure != null ? failure.getMessage() : null;
    String safeDetail =
        message == null || message.isBlank()
            ? "Unexpected Builder Goal start failure"
            : BuilderGoalSensitiveTextSanitizer.sanitize(message);
    org.springframework.http.HttpStatus status =
        failure instanceof IllegalArgumentException
            ? org.springframework.http.HttpStatus.BAD_REQUEST
            : failure instanceof IllegalStateException
                ? org.springframework.http.HttpStatus.CONFLICT
                : org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
    String code =
        failure instanceof IllegalArgumentException
            ? "INVALID_START_REQUEST"
            : failure instanceof IllegalStateException
                ? "START_CONFLICT"
                : "INTERNAL_START_FAILURE";
    return new BuilderGoalStartException(
        status, code, "PREPARING", safeDetail, null, null, correlationId, failure);
  }

  /**
   * Publishes a task to an already-running local Micro6 executor without (re)launching a client.
   *
   * <p>Unlike {@link #startRun}, this path does not call {@link #queuePendingLaunch} and therefore
   * never starts a fresh Micro6 workspace. It creates a persisted run record, then asks the
   * existing {@link ScriptExecutionService#getMicro6ExecutorHealth} to confirm the running executor
   * is reachable, account-matched, and dispatch-ready before delegating to {@link
   * #publishIteration}.
   *
   * <p>When exactly one registry client is not ready or the account/world does not match, the
   * publish is deferred: the run stays in a non-terminal state with a target-preparation phase
   * describing why, and the caller receives {@code published=false}. If {@code allowLaunchFallback}
   * is true, a normal server-owned launch+publish is queued; no caller-supplied executor URL is
   * ever used.
   *
   * <p>This method deliberately does not hold a surrounding database transaction: {@link
   * #publishIteration(PatchLoopIteration)} reserves the entry in a short locked transaction,
   * performs the network call, and applies the outcome in a second short transaction. Keeping the
   * HTTP call outside the caller transaction prevents the row lock from being held while a remote
   * executor is slow or unreachable.
   */
  public Map<String, Object> publishOnly(
      String accountId,
      String launcherMachineUuid,
      String taskKey,
      Map<String, Object> settings,
      Integer world,
      boolean allowLaunchFallback,
      Map<String, Object> provenance) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (taskKey == null || taskKey.isBlank()) {
      throw new IllegalArgumentException("taskKey is required");
    }
    requireValidWorld(world);
    BuilderGoalAccountCatalogService.AccountDefinition selectedAccount =
        resolveSelectedAccount(accountId);
    if (selectedAccount == null) {
      throw new IllegalArgumentException("Select a launcher account first");
    }
    requireRunnableAccount(selectedAccount);
    String launchConflictReason =
        findActiveLaunchConflictReason(
            firstNonBlank(selectedAccount.accountId(), selectedAccount.accountHash()), taskKey);
    if (launchConflictReason != null) {
      throw new IllegalStateException(launchConflictReason);
    }
    String selectedMachineUuid = normalizeOptionalString(launcherMachineUuid);
    BuilderGoalLauncherControlService.LauncherMachine selectedMachine =
        selectedMachineUuid != null ? resolveSelectedMachine(selectedMachineUuid) : null;
    if (allowLaunchFallback && selectedMachine == null) {
      throw new IllegalArgumentException(
          "launcherMachineUuid is required when launch fallback is enabled");
    }
    if (allowLaunchFallback) {
      requireLaunchReadyAccount(selectedAccount);
      requireLaunchableMachine(selectedMachine, selectedMachineUuid);
    }
    Map<String, Object> acceptedSettings = normalizeSettings(settings);
    List<Micro6OnlineClientRegistryService.TargetClient> matchingClients =
        findDispatchReadyClientsForAccount(selectedAccount, world);
    Micro6OnlineClientRegistryService.TargetClient matchingClient =
        matchingClients.size() == 1 ? matchingClients.get(0) : null;
    // Resolve aliases only after an exact target exists. A deferred publish must not fetch the
    // executor catalog merely to canonicalize a task key.
    if (matchingClient != null) {
      taskKey = canonicalTaskKey(resolveCatalogTask(taskKey), taskKey);
    }
    String effectiveTargetBaseUrl =
        matchingClient != null ? matchingClient.advertisedBaseUrl() : null;
    // A publish without exactly one registry-selected target is deferred before any executor
    // catalog call. This is deliberately independent of settings contents: no target means no
    // executor interaction and no caller-selected/default URL fallback.
    CatalogValidationResult catalogValidation;
    JsonNode catalogTask;
    if (matchingClient == null) {
      catalogValidation = CatalogValidationResult.skipped("SKIPPED_NO_TARGET");
      catalogTask = null;
    } else {
      catalogValidation =
          resolvePublishCatalogValidation(taskKey, effectiveTargetBaseUrl, acceptedSettings);
      if (catalogValidation.rejectPublish()) {
        throw new IllegalArgumentException(catalogValidation.errorMessage());
      }
      catalogTask = catalogValidation.catalogTask();
    }
    String resolvedDisplayName =
        firstNonBlank(
            catalogTask != null ? textValue(catalogTask.path("display_name")) : null,
            catalogTask != null ? textValue(catalogTask.path("panel_display_name")) : null,
            taskKey);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("task_key", taskKey);
    payload.put("title", resolvedDisplayName);
    payload.put("settings", acceptedSettings);
    payload.put("source", "catalog_task");
    payload.put("account_id", accountId);
    if (world != null) {
      payload.put("world", world);
    }
    NormalizedPayload normalized = normalizePayload(objectMapper.valueToTree(payload));
    Map<String, Object> initialTargetMetadata =
        buildRequestedTargetMetadataFromAccount(selectedAccount, selectedMachine);
    if (effectiveTargetBaseUrl != null && !effectiveTargetBaseUrl.isBlank()) {
      initialTargetMetadata.put("advertised_base_url", effectiveTargetBaseUrl);
    }
    if (matchingClient != null) {
      initialTargetMetadata.putAll(buildTargetMetadata(matchingClient));
    }
    PatchLoopIteration iteration =
        createRunIteration(
            normalized,
            objectMapper.valueToTree(payload),
            null,
            null,
            initialTargetMetadata,
            catalogTask,
            provenance);
    applyCatalogValidationMetadata(iteration, catalogValidation);

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put(TASK_METADATA_PUBLISH_ONLY, true);
    taskMetadata.put(TASK_METADATA_LAUNCH_FALLBACK_ALLOWED, allowLaunchFallback);
    taskMetadata.put("mule_account", buildAccountMetadata(selectedAccount, selectedMachine));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    String settingsHash = CanonicalSettingsHasher.sha256(objectMapper, acceptedSettings);
    taskMetadata.put("settings_sha256", settingsHash);
    taskMetadata.put("settings_validation", catalogValidation.settingsValidation());
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("run", toRunSummary(iteration));
    response.put("accepted", true);
    response.put("published", null);
    response.put("entryId", null);
    response.put("targetClientId", null);
    response.put("targetAccountName", selectedAccount.displayName());
    response.put("accountId", selectedAccount.accountId());
    response.put("accountHash", selectedAccount.accountHash());
    response.put("accountName", selectedAccount.displayName());
    response.put("publishStatus", null);
    response.put("publishError", null);
    response.put("catalogValidation", catalogValidation.status());
    response.put("catalogValidationSkipped", catalogValidation.skipped());
    response.put("targetClientId", matchingClient != null ? matchingClient.clientId() : null);

    if (matchingClient == null) {
      String reason =
          matchingClients.isEmpty()
              ? "No single non-stale dispatch-ready registry client matches the selected account and world"
              : "Multiple dispatch-ready registry clients match the selected account and world";
      updatePreparationState(iteration, taskMetadata, RECOVERY_WAITING_FOR_EXECUTOR, reason);
      response.put("published", false);
      response.put("publishStatus", RECOVERY_WAITING_FOR_EXECUTOR);
      response.put("publishError", reason);
      response.put("launchFallback", allowLaunchFallback);
      if (allowLaunchFallback) {
        queuePendingLaunch(iteration.getIterationId());
      }
      response.put("run", toRunSummary(iteration));
      response.put("targetPreparation", readTargetPreparationMetadata(readTaskMetadata(iteration)));
      return response;
    }

    try {
      ScriptExecutionService.Micro6PublishResult publishResult = publishIteration(iteration);
      iteration =
          iterationRepository.findByIterationId(iteration.getIterationId()).orElse(iteration);
      response.put("run", toRunSummary(iteration));
      if (publishResult.published()) {
        response.put("published", true);
        response.put("entryId", publishResult.entryId());
        response.put("publishStatus", publishResult.publishStatus());
        response.put("publishError", null);
      } else {
        response.put("published", false);
        response.put("entryId", null);
        response.put("publishStatus", publishResult.publishStatus());
        response.put(
            "publishError",
            firstNonBlank(publishResult.errorMessage(), "Failed to publish task to Micro6"));
      }
      response.put("launchFallback", false);
    } catch (PublishDeferredException e) {
      iteration =
          iterationRepository.findByIterationId(iteration.getIterationId()).orElse(iteration);
      response.put("run", toRunSummary(iteration));
      response.put("published", false);
      response.put("entryId", null);
      response.put("publishStatus", "DEFERRED");
      response.put(
          "publishError",
          firstNonBlank(
              e.getMessage(),
              "Micro6 local executor is not publish-ready; launch or attach a client first"));
      if (allowLaunchFallback) {
        queuePendingLaunch(iteration.getIterationId());
        response.put("launchFallback", true);
      } else {
        response.put("launchFallback", false);
      }
    }
    response.put("targetPreparation", readTargetPreparationMetadata(readTaskMetadata(iteration)));
    return response;
  }

  /**
   * Compatibility overload retained for non-controller integrations. Caller-selected URLs are no
   * longer accepted as dispatch targets.
   */
  @Deprecated
  Map<String, Object> publishOnly(
      String accountId,
      String launcherMachineUuid,
      String taskKey,
      Map<String, Object> settings,
      Integer world,
      String targetBaseUrl,
      boolean allowLaunchFallback,
      Map<String, Object> provenance) {
    if (targetBaseUrl != null && !targetBaseUrl.isBlank()) {
      throw new IllegalArgumentException("TARGET_OVERRIDE_NOT_ALLOWED");
    }
    return publishOnly(
        accountId,
        launcherMachineUuid,
        taskKey,
        settings,
        world,
        allowLaunchFallback,
        provenance);
  }

  private void queuePendingLaunch(String iterationId) {
    if (iterationId == null || iterationId.isBlank()) {
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              patchLoopExecutor.execute(() -> continuePendingLaunchSafely(iterationId));
            }
          });
      return;
    }
    patchLoopExecutor.execute(() -> continuePendingLaunchSafely(iterationId));
  }

  private void continuePendingLaunchSafely(String iterationId) {
    if (iterationId == null || iterationId.isBlank() || !pendingLaunchIterations.add(iterationId)) {
      return;
    }
    try {
      continuePendingLaunch(iterationId);
    } finally {
      pendingLaunchIterations.remove(iterationId);
    }
  }

  private void continuePendingLaunch(String iterationId) {
    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null || iteration.getStatus() == null || iteration.getStatus().isTerminal()) {
      return;
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId != null && entryId > 0) {
      return;
    }

    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    String accountId =
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash")));
    String launcherMachineUuid = stringValue(accountMetadata.get("launcher_machine_uuid"));
    if (accountId == null || launcherMachineUuid == null) {
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.INVALID_REQUEST,
          "Saved launch metadata is incomplete for this Builder Goal run",
          "iterationId=" + iteration.getIterationId());
      return;
    }

    BuilderGoalAccountCatalogService.AccountDefinition selectedAccount =
        resolveSelectedAccount(accountId);
    if (selectedAccount == null) {
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.INVALID_REQUEST,
          "Saved launcher account is no longer available",
          "accountId=" + accountId);
      return;
    }

    // Acquire the local launch lease before touching Micro6
    String accountKey = firstNonBlank(selectedAccount.accountHash(), selectedAccount.accountId());
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult acquireResult =
        localLaunchCoordinator.tryAcquire(
            launcherMachineUuid, executorPort, accountKey, iterationId);
    if (acquireResult.conflict() != null) {
      // Persist WAITING_FOR_LOCAL_LAUNCH_LEASE so scheduled reconciliation retries
      BuilderGoalLocalLaunchCoordinatorService.Conflict conflict = acquireResult.conflict();
      updatePreparationState(
          iteration,
          taskMetadata,
          PREPARATION_STATE_WAITING_FOR_LAUNCH_LEASE,
          "Waiting for the local launch lease; another workflow ("
              + conflict.workflowType()
              + ", owner="
              + conflict.ownerId()
              + ") currently holds it. This run will retry automatically once the lease is free.");
      return;
    }
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle leaseHandle = acquireResult.handle();
    ActiveLaunchWork pendingWork = new ActiveLaunchWork(leaseHandle);
    pendingWork.attachCurrentThread();
    activeLaunchWork.put(iterationId, pendingWork);

    try {
      // Check cancellation before starting Micro6 preparation
      if (pendingWork.isCancelled()) {
        return;
      }
      Micro6TargetPreparationService.PreparationResult preparationResult =
          targetPreparationService.ensureReady(
              new Micro6TargetPreparationService.PreparationRequest(
                  firstNonBlank(selectedAccount.accountHash(), selectedAccount.accountId()),
                  selectedAccount.accountHash(),
                  firstNonBlank(selectedAccount.launchProfileName(), selectedAccount.displayName()),
                  selectedAccount.email(),
                  selectedAccount.password(),
                  intValue(taskMetadata.get("world")),
                  launcherMachineUuid,
                  null,
                  false),
              leaseHandle,
              pendingWork,
              progress ->
                  updatePreparationProgress(
                      iterationId, selectedAccount, launcherMachineUuid, progress));

      // Check iteration terminal/cancelled state before publishing
      iteration = iterationRepository.findByIterationId(iterationId).orElse(iteration);
      if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
        return;
      }
      if (pendingWork.isCancelled()) {
        return;
      }
      taskMetadata = readTaskMetadata(iteration);
      mergeTargetPreparationMetadata(taskMetadata, preparationResult.metadata());
      taskMetadata.put("micro6_target", buildTargetMetadata(preparationResult.targetClient()));
      @SuppressWarnings("unchecked")
      Map<String, Object> storedSettings =
          taskMetadata.get("settings") instanceof Map<?, ?> settingsMap
              ? new LinkedHashMap<>(
                  objectMapper.convertValue(settingsMap, new TypeReference<Map<String, Object>>() {}))
              : new LinkedHashMap<>();
      String storedTaskKey = firstNonBlank(stringValue(taskMetadata.get("task_key")), iteration.getScriptName());
      CatalogValidationResult asyncCatalogValidation =
          resolvePublishCatalogValidation(
              storedTaskKey,
              preparationResult.targetClient().advertisedBaseUrl(),
              storedSettings);
      if (asyncCatalogValidation.rejectPublish()) {
        applyCatalogValidationMetadata(iteration, asyncCatalogValidation);
        Map<String, Object> invalidPublish = readPublishMetadata(taskMetadata);
        invalidPublish.put("status", "INVALID_SETTINGS");
        invalidPublish.put("last_error", asyncCatalogValidation.errorMessage());
        taskMetadata.put("micro6_publish", invalidPublish);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        persistenceService.saveIteration(iteration);
        persistenceService.markIterationFailed(
            iteration,
            PatchLoopFailureCode.INVALID_REQUEST,
            asyncCatalogValidation.errorMessage(),
            "catalogValidation=" + asyncCatalogValidation.status());
        return;
      }
      JsonNode asyncCatalogTask = asyncCatalogValidation.catalogTask();
      String asyncCanonicalTaskKey = canonicalTaskKey(asyncCatalogTask, storedTaskKey);
      if (!asyncCanonicalTaskKey.equals(iteration.getScriptName())) {
        iteration.setScriptName(asyncCanonicalTaskKey);
        taskMetadata.put("task_key", asyncCanonicalTaskKey);
      }
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      persistenceService.saveIteration(iteration);
      applyCatalogValidationMetadata(iteration, asyncCatalogValidation);
      updatePreparationState(
          iteration,
          readTaskMetadata(iteration),
          PREPARATION_STATE_READY_TO_PUBLISH,
          "Target dispatch-ready and ready to publish");

      try {
        publishIteration(iteration);
      } catch (PublishDeferredException e) {
        logger.info(
            "Deferring Builder Goal publish for iteration {} until target becomes publishable: {}",
            iterationId,
            e.getMessage());
        return;
      }
    } catch (BuilderGoalLaunchCancelledException e) {
      // Cancellation escaped from Micro6 preparation/relaunch (watchdog, operator Stop, or
      // unexpected lease loss). If Stop already terminalized the iteration, perform no
      // transition; otherwise mark the run FAILED with a credential-free reason so the pending
      // launch never hangs.
      iteration = iterationRepository.findByIterationId(iterationId).orElse(iteration);
      if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
        logger.info(
            "Pending launch {} already terminal after cancellation; leaving it unchanged",
            iterationId);
        return;
      }
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.SCRIPT_START_FAILED,
          "Micro6 launch was cancelled before the executor became ready",
          "lease lost or stop requested");
    } catch (Micro6TargetPreparationService.PreparationException e) {
      iteration = iterationRepository.findByIterationId(iterationId).orElse(iteration);
      // A stop request can race with the relaunch worker's final readiness poll. Preserve the
      // operator's terminal cancellation instead of converting the late process-exit exception
      // into a failure after the run has already been cancelled.
      if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
        return;
      }
      Map<String, Object> currentTaskMetadata = readTaskMetadata(iteration);
      Throwable cause = e.getCause();
      if (cause instanceof Micro6TargetRelaunchService.RelaunchException relaunchException) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("state", relaunchException.getPhase());
        updates.put("readiness_phase", relaunchException.getPhase());
        updates.put("readiness_reason", relaunchException.getMessage());
        updates.put(
            "launcher_log_file",
            relaunchException.getLaunchLogFile() != null
                ? relaunchException.getLaunchLogFile().toString()
                : null);
        updates.put("effective_gradle_task", relaunchException.getEffectiveGradleTask());
        updates.put("launch_args_preview", relaunchException.getMaskedLaunchArgs());
        updates.put("launch_failure_kind", relaunchException.getFailureKind());
        updates.put("launch_failure_detail", relaunchException.getFailureDetail());
        updates.put(
            "last_observed_readiness_phase", relaunchException.getLastObservedReadinessPhase());
        updates.put("attempt_id", relaunchException.getAttemptId());
        mergeTargetPreparationMetadata(currentTaskMetadata, updates);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(currentTaskMetadata));
        persistenceService.saveIteration(iteration);
      }
      updatePreparationState(iteration, currentTaskMetadata, e.getPhase(), e.getMessage());
      if (!e.isTerminal()) {
        return;
      }
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.SCRIPT_START_FAILED,
          e.getMessage(),
          "account="
              + firstNonBlank(
                  selectedAccount.displayName(), selectedAccount.accountId(), "(none)"));
    } catch (Exception e) {
      iteration = iterationRepository.findByIterationId(iterationId).orElse(iteration);
      if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
        return;
      }
      updatePreparationState(
          iteration,
          readTaskMetadata(iteration),
          PREPARATION_STATE_PREPARING_TARGET,
          firstNonBlank(e.getMessage(), "Failed to continue local Micro6 launch"));
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.INTERNAL_ERROR,
          firstNonBlank(e.getMessage(), "Failed to continue local Micro6 launch"),
          stackTrace(e));
    } finally {
      activeLaunchWork.remove(iterationId, pendingWork);
      localLaunchCoordinator.release(leaseHandle);
    }
  }

  @Scheduled(fixedDelayString = "${builder-goal.pending-launch-sync-interval-ms:3000}")
  public void syncPendingLaunches() {
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        // Reconciliation reloads under a write lock. Skip a row that was failed there even when
        // the scheduler's original list contains a stale active entity snapshot.
        .filter(iteration -> !reconcileStaleLaunchFailure(iteration))
        .filter(iteration -> iteration.getStatus() != null && iteration.getStatus().isActive())
        .filter(this::shouldContinuePendingLaunch)
        .limit(10)
        .forEach(iteration -> queuePendingLaunch(iteration.getIterationId()));
  }

  /**
   * Periodically checks for launch operations that have exceeded the active watchdog duration and
   * transitions them to FAILED.
   */
  @Scheduled(fixedDelayString = "${builder-goal.launch.watchdog-check-interval-ms:60000}")
  public void checkLaunchWatchdog() {
    java.util.List<String> failedIds = launchOperationService.checkActiveWatchdog();
    if (failedIds.isEmpty()) {
      return;
    }
    for (String operationId : failedIds) {
      ActiveLaunchWork work = activeLaunchWork.get(operationId);
      if (work != null) {
        logger.warn(
            "Watchdog: cancelling launch worker for operation {} (account={})",
            operationId,
            work.leaseHandle.accountKey());
        work.cancel();
        // Do NOT release the lease from the scheduler thread; the worker's finally block
        // owns that responsibility.
      }
    }
  }

  private boolean shouldContinuePendingLaunch(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (isPublishOnlyNoLaunch(taskMetadata)) {
      return false;
    }
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId != null && entryId > 0) {
      return false;
    }
    String preparationState = stringValue(readTargetPreparationMetadata(taskMetadata).get("state"));
    if (PREPARATION_STATE_READY_TO_PUBLISH.equalsIgnoreCase(preparationState)) {
      return true;
    }
    return preparationState == null
        || preparationState.isBlank()
        || PREPARATION_STATE_PREPARING_TARGET.equalsIgnoreCase(preparationState)
        || PREPARATION_STATE_WAITING_FOR_LAUNCH_LEASE.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_CHECKING_EXISTING.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_REUSING_EXECUTOR.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_STOPPING_EXISTING.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_PREPARING_BUILD.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_BUILDING_MICRO6.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_LAUNCHING_PROCESS.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_WAITING_FOR_PORT.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_WAITING_FOR_LOGIN.equalsIgnoreCase(preparationState)
        || Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH.equalsIgnoreCase(
            preparationState);
  }

  private void updatePreparationProgress(
      String iterationId,
      BuilderGoalAccountCatalogService.AccountDefinition selectedAccount,
      String launcherMachineUuid,
      Micro6TargetRelaunchService.RelaunchProgress progress) {
    if (progress == null) {
      return;
    }
    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null || iteration.getStatus() == null || iteration.getStatus().isTerminal()) {
      return;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("state", progress.phase());
    updates.put("message", progress.reason());
    updates.put("readiness_phase", progress.phase());
    updates.put("readiness_reason", progress.reason());
    updates.put("local_executor_base_url", progress.localExecutorBaseUrl());
    updates.put("resolved_account_name", progress.resolvedAccountName());
    updates.put("resolved_account_hash", progress.resolvedAccountHash());
    updates.put("requested_account_name", progress.requestedAccountName());
    updates.put("requested_account_hash", progress.requestedAccountHash());
    updates.put("port_owner_pid", progress.portOwnerPid());
    updates.put("dispatch_hold", progress.dispatchHold());
    updates.put("dispatch_hold_reason", progress.dispatchHoldReason());
    updates.put("startup_state", progress.startupState());
    updates.put("login_state", progress.loginState());
    updates.put("login_attempt", progress.loginAttempt());
    updates.put("login_attempt_max", progress.loginAttemptMax());
    updates.put("login_terminal_reason", progress.loginTerminalReason());
    updates.put("effective_gradle_task", progress.effectiveGradleTask());
    updates.put("launch_args_preview", progress.maskedLaunchArgs());
    updates.put("reused_executor", progress.reusedExecutor());
    updates.put("launcher_log_file", progress.launchLogFile());
    updates.put("launch_failure_kind", progress.launchFailureKind());
    updates.put("launch_failure_detail", progress.launchFailureDetail());
    updates.put("attempt_id", progress.attemptId());
    updates.put("launch_mode", "local_micro6_project");
    updates.put("launcher_machine_uuid", launcherMachineUuid);
    updates.put(
        "launcher_machine_name",
        firstNonBlank(
            stringValue(readTargetPreparationMetadata(taskMetadata).get("launcher_machine_name")),
            stringValue(readAccountMetadata(taskMetadata).get("launcher_machine_name"))));
    mergeTargetPreparationMetadata(taskMetadata, updates);
    Map<String, Object> targetMetadata = new LinkedHashMap<>(readTargetMetadata(taskMetadata));
    targetMetadata.put(
        "requested_account_name",
        firstNonBlank(progress.requestedAccountName(), selectedAccount.displayName()));
    targetMetadata.put(
        "requested_account_hash",
        firstNonBlank(progress.requestedAccountHash(), selectedAccount.accountHash()));
    if (progress.resolvedAccountName() != null) {
      targetMetadata.put("resolved_account_name", progress.resolvedAccountName());
    }
    if (progress.resolvedAccountHash() != null) {
      targetMetadata.put("resolved_account_hash", progress.resolvedAccountHash());
    }
    if (!targetMetadata.isEmpty()) {
      taskMetadata.put("micro6_target", targetMetadata);
    }
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId) throws Exception {
    return analyzeFailure(iterationId, null, ANALYSIS_MODE_OPERATOR_EDITED_MCP);
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId, JsonNode payload) throws Exception {
    return analyzeFailure(iterationId, payload, ANALYSIS_MODE_OPERATOR_EDITED_MCP);
  }

  public Map<String, Object> approveRecovery(String iterationId) throws Exception {
    return executeApprovedRecovery(iterationId);
  }

  public Map<String, Object> fixAndRelaunch(String iterationId) throws Exception {
    return executeApprovedRecovery(iterationId);
  }

  /**
   * Opens the same-screen recovery workspace for a failed builder-goal run.
   *
   * <p>Enters recovery mode (setting the {@code AWAITING_OPERATOR_PROMPT} phase when no later phase
   * is active), records the load timestamp, and returns the run, prompt/context defaults, evidence
   * groups, time range, persisted annotations, recording/debug-session metadata, and recovery state
   * so the inline debugger surface can hydrate without a separate page.
   */
  @Transactional
  public Map<String, Object> openRecoveryWorkspace(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    // Build the editable draft from the currently persisted analysis_input state.
    Map<String, Object> draft = buildAnalysisDraft(iterationId);

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    Map<String, Object> workspace = readRecoveryWorkspace(taskMetadata);
    String phase = stringValue(recoveryMetadata.get("phase"));

    Map<String, Object> updatedWorkspace = new LinkedHashMap<>(workspace);
    updatedWorkspace.put("last_loaded_at", LocalDateTime.now().toString());
    String recordingId =
        firstNonBlank(
            stringValue(updatedWorkspace.get("recording_id")), iteration.getRunRecordingId());
    if (recordingId != null) {
      updatedWorkspace.putIfAbsent("recording_id", recordingId);
    }
    String debugSessionId =
        firstNonBlank(
            stringValue(updatedWorkspace.get("debug_session_id")), iteration.getRunSessionId());
    if (debugSessionId != null) {
      updatedWorkspace.putIfAbsent("debug_session_id", debugSessionId);
    }
    taskMetadata.put("recovery_workspace", updatedWorkspace);
    if (!isRecoveryPhaseActive(phase)) {
      Map<String, Object> recovery = new LinkedHashMap<>();
      recovery.put("phase", RECOVERY_AWAITING_OPERATOR_PROMPT);
      recovery.put("message", "Recovery workspace opened for inline editing");
      recovery.put("updated_at", LocalDateTime.now().toString());
      taskMetadata.put("recovery", recovery);
    }
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    Map<String, Object> run = toRunSummary(iteration);
    Map<String, Object> response = new LinkedHashMap<>(draft);
    response.put("run", run);
    response.put("recordingSessionId", stringValue(updatedWorkspace.get("recording_session_id")));
    response.put("recordingId", recordingId);
    response.put("debugSessionId", debugSessionId);
    response.put("annotations", updatedWorkspace.getOrDefault("annotations", List.of()));
    response.put(
        "selectedEvidenceIds", readStoredSelectedEvidenceIds(updatedWorkspace, taskMetadata));
    response.put("activeRangeStartSeconds", intValue(updatedWorkspace.get("active_start_seconds")));
    response.put("activeRangeEndSeconds", intValue(updatedWorkspace.get("active_end_seconds")));
    response.put(
        "patchLoopSeed",
        buildRecoveryPatchLoopSeed(
            iteration,
            taskMetadata,
            updatedWorkspace,
            stringValue(response.get("prompt")),
            stringValueAllowBlank(response.get("context")),
            recordingId,
            debugSessionId));
    response.put("recoveryState", stringValue(readRecoveryMetadata(taskMetadata).get("phase")));
    response.put("analysisAvailable", run.get("analysisAvailable"));
    response.put("approvalAvailable", run.get("approvalAvailable"));
    return response;
  }

  @Transactional
  public Map<String, Object> openRecoveryWorkspaceByRecordingId(String recordingId) {
    PatchLoopIteration iteration = loadBuilderRunByRecordingId(recordingId);
    return openRecoveryWorkspace(iteration.getIterationId());
  }

  @Transactional
  public Map<String, Object> setRecoveryIssueStatement(
      String iterationId, String prompt, String context) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    AnalysisPromptData defaults = buildDefaultAnalysisPromptData(iteration);

    String resolvedPrompt = firstNonBlank(prompt, defaults.prompt());
    String resolvedContext =
        context != null
            ? context
            : firstNonBlank(stringValueAllowBlank(context), defaults.context());

    AnalysisInputMetadata existingInput = readAnalysisInputMetadata(taskMetadata);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("prompt", resolvedPrompt);
    metadata.put("context", resolvedContext);
    metadata.put("selected_evidence_ids", existingInput.selectedEvidenceIds());
    metadata.put("selected_evidence_count", existingInput.selectedEvidenceCount());
    metadata.put("total_evidence_count", existingInput.totalEvidenceCount());
    metadata.put("time_range_start_seconds", existingInput.timeRangeStartSeconds());
    metadata.put("time_range_end_seconds", existingInput.timeRangeEndSeconds());
    metadata.put("mode", firstNonBlank(existingInput.mode(), ANALYSIS_MODE_OPERATOR_EDITED_MCP));
    metadata.put(
        "customized",
        !Objects.equals(resolvedPrompt, defaults.prompt())
            || !Objects.equals(resolvedContext, defaults.context()));
    taskMetadata.put("analysis_input", metadata);

    Map<String, Object> workspace = readRecoveryWorkspace(taskMetadata);
    Map<String, Object> updatedWorkspace = new LinkedHashMap<>(workspace);
    updatedWorkspace.put("issue_statement", resolvedPrompt);
    updatedWorkspace.put("issue_context", resolvedContext);
    updatedWorkspace.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery_workspace", updatedWorkspace);

    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iterationId);
    response.put("prompt", resolvedPrompt);
    response.put("context", resolvedContext);
    response.put("saved", true);
    return response;
  }

  /**
   * Persists operator-authored recovery annotations, the active range, and promoted evidence ids
   * onto the iteration's {@code recovery_workspace} metadata so the inline editing state survives
   * reloads and feeds the structured Codex payload.
   */
  @Transactional
  public Map<String, Object> saveRecoveryAnnotations(String iterationId, JsonNode payload) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> workspace = readRecoveryWorkspace(taskMetadata);
    Map<String, Object> updated = new LinkedHashMap<>(workspace);
    updated.put("annotations", parseAnnotations(payload));
    Integer activeStart = intValue(payload != null ? payload.get("activeRangeStartSeconds") : null);
    Integer activeEnd = intValue(payload != null ? payload.get("activeRangeEndSeconds") : null);
    if (activeStart != null) {
      updated.put("active_start_seconds", activeStart);
    }
    if (activeEnd != null) {
      updated.put("active_end_seconds", activeEnd);
    }
    List<String> selectedIds = selectedEvidenceIds(payload);
    if (selectedIds != null) {
      updated.put("selected_evidence_ids", selectedIds);
    }
    updated.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery_workspace", updated);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iterationId);
    response.put("annotations", updated.get("annotations"));
    response.put("selectedEvidenceIds", updated.getOrDefault("selected_evidence_ids", List.of()));
    response.put("activeRangeStartSeconds", updated.get("active_start_seconds"));
    response.put("activeRangeEndSeconds", updated.get("active_end_seconds"));
    response.put("saved", true);
    return response;
  }

  /**
   * Resolves filtered evidence for the current annotation/selection state without persisting.
   *
   * <p>Evidence is clipped to the active range (payload active range, the union of annotation
   * ranges, or the last persisted selection) and the requested promoted evidence ids are honored.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> resolveRecoveryEvidence(String iterationId, JsonNode payload) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    AnalysisV1EvidenceBundle evidence = evidenceService.collectEvidence(iteration, null);
    AnalysisInputMetadata existingInput = readAnalysisInputMetadata(taskMetadata);

    List<Map<String, Object>> annotations = parseAnnotations(payload);
    TimeRangeSelection timeRange =
        resolveRecoveryTimeRange(annotations, payload, existingInput, evidence);
    AnalysisV1EvidenceBundle clippedEvidence = clipEvidenceToTimeRange(evidence, timeRange);

    List<String> requestedIds = selectedEvidenceIds(payload);
    boolean defaultSelectAll;
    if (requestedIds == null) {
      requestedIds =
          readStoredSelectedEvidenceIds(readRecoveryWorkspace(taskMetadata), taskMetadata);
      defaultSelectAll = requestedIds == null || requestedIds.isEmpty();
    } else {
      defaultSelectAll = requestedIds.isEmpty();
    }
    SelectionResult selectionResult =
        buildSelectionResult(clippedEvidence, requestedIds, defaultSelectAll);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("evidenceGroups", selectionResult.groups());
    response.put("selectedEvidenceIds", selectionResult.selectedIds());
    response.put("selectedEvidenceCount", selectionResult.selectedCount());
    response.put("totalEvidenceCount", selectionResult.totalCount());
    response.put("resolvedTimeWindow", buildResolvedTimeWindow(timeRange));
    return response;
  }

  /**
   * Returns the current recovery state for UI hydration and polling: run summary, recovery phase,
   * relaunch/bind status, preferred debug-session id, and child iteration linkage.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> getRecoveryState(String iterationId) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> run = toRunSummary(iteration);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    Map<String, Object> workspace = readRecoveryWorkspace(taskMetadata);
    String phase = stringValue(recoveryMetadata.get("phase"));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iterationId);
    response.put("run", run);
    response.put("recoveryPhase", phase);
    response.put("recoveryMessage", stringValueAllowBlank(recoveryMetadata.get("message")));
    response.put("recoveryUpdatedAt", stringValue(recoveryMetadata.get("updated_at")));
    response.put("relaunchStatus", deriveRelaunchStatus(phase));
    response.put("bindStatus", deriveBindStatus(iteration, run, phase));
    response.put("preferredSessionId", stringValue(workspace.get("preferred_session_id")));
    response.put("childIterationId", findChildIterationId(iterationId));
    // Include the child run's summary (with runRecordingId/runSessionId) so the same-screen
    // surface can switch the replay to the child run once the debug bind succeeds.
    String childId = findChildIterationId(iterationId);
    if (childId != null) {
      PatchLoopIteration child = iterationRepository.findByIterationId(childId).orElse(null);
      if (child != null) {
        response.put("childRun", toRunSummary(child));
      }
    }
    response.put("analysisAvailable", run.get("analysisAvailable"));
    response.put("approvalAvailable", run.get("approvalAvailable"));
    return response;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getRecoveryStateByRecordingId(String recordingId) {
    PatchLoopIteration iteration = loadBuilderRunByRecordingId(recordingId);
    return getRecoveryState(iteration.getIterationId());
  }

  private Map<String, Object> analyzeFailure(
      String iterationId, JsonNode payload, String analysisMode) throws Exception {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    return analyzeFailureInternal(iteration, payload, analysisMode);
  }

  private Map<String, Object> analyzeFailureInternal(
      PatchLoopIteration iteration, JsonNode payload, String analysisMode) throws Exception {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    String blockedReason = determineAnalyzeBlockedReason(iteration, publishStatus);
    if (blockedReason != null) {
      throw new IllegalStateException(blockedReason);
    }

    AnalysisV1EvidenceBundle evidence = evidenceService.collectEvidence(iteration, null);
    AnalysisPromptData defaults = buildDefaultAnalysisPromptData(iteration);
    AnalysisInputMetadata existingInput = readAnalysisInputMetadata(taskMetadata);
    TimeRangeSelection timeRange = resolveTimeRangeSelection(evidence, payload, existingInput);
    AnalysisV1EvidenceBundle clippedEvidence = clipEvidenceToTimeRange(evidence, timeRange);

    String prompt =
        textValueAllowBlank(payload, "prompt") != null
            ? textValueAllowBlank(payload, "prompt")
            : (existingInput.prompt() != null ? existingInput.prompt() : defaults.prompt());
    String context =
        textValueAllowBlank(payload, "context") != null
            ? textValueAllowBlank(payload, "context")
            : (existingInput.context() != null ? existingInput.context() : defaults.context());

    List<String> requestedIds =
        selectedEvidenceIds(payload) != null
            ? selectedEvidenceIds(payload)
            : existingInput.selectedEvidenceIds();
    boolean defaultSelectAll = requestedIds == null || requestedIds.isEmpty();
    SelectionResult selectionResult =
        buildSelectionResult(clippedEvidence, requestedIds, defaultSelectAll);
    if (selectionResult.selectedCount() <= 0) {
      throw new IllegalArgumentException(
          "At least one evidence item must be selected for analysis");
    }

    // Persist inline recovery annotations + promoted evidence on the recovery_workspace so the
    // structured Codex payload reflects the operator's same-screen editing state.
    List<Map<String, Object>> annotations = parseAnnotations(payload);
    Map<String, Object> recoveryWorkspace = readRecoveryWorkspace(taskMetadata);
    Map<String, Object> updatedWorkspace = new LinkedHashMap<>(recoveryWorkspace);
    if (!annotations.isEmpty()) {
      updatedWorkspace.put("annotations", annotations);
    }
    updatedWorkspace.put("selected_evidence_ids", selectionResult.selectedIds());
    if (timeRange != null) {
      updatedWorkspace.put("active_start_seconds", timeRange.startSeconds());
      updatedWorkspace.put("active_end_seconds", timeRange.endSeconds());
    }
    updatedWorkspace.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery_workspace", updatedWorkspace);

    String providerContext = context;
    String annotationsSummary = buildAnnotationsContext(annotations);
    if (!annotationsSummary.isEmpty()) {
      providerContext =
          context + "\n\nAnnotated replay ranges from operator:\n" + annotationsSummary;
    }

    AnalysisV1EvidenceBundle selectedEvidence = selectionResult.bundle();
    persistenceService.saveEvidenceCounts(iteration, selectedEvidence);
    if (!evidenceService.validateEvidenceSufficiency(selectedEvidence)) {
      throw new IllegalStateException("Insufficient evidence available for analysis");
    }

    AnalysisV2Request request =
        buildAnalysisRequest(iteration, selectedEvidence, prompt, providerContext);
    updateAnalysisInputMetadata(
        iteration,
        taskMetadata,
        prompt,
        context,
        selectionResult.selectedIds(),
        selectionResult.selectedCount(),
        selectionResult.totalCount(),
        defaults,
        analysisMode,
        timeRange);
    persistenceService.saveAnalysisRequest(iteration, objectMapper.valueToTree(request));
    AnalysisV2Response response = analysisProvider.analyzeEvidence(request);
    if (response.getStatus() == AnalysisStatus.FAILURE) {
      String errorMessage =
          response.getError() != null ? response.getError().getMessage() : "Analysis failed";
      throw new IllegalStateException(errorMessage);
    }

    persistenceService.saveAnalysisResults(iteration, response, objectMapper.valueToTree(response));
    AnalysisV2PatchDraft patchDraft = response.getPatchDraft();
    if (patchDraft != null
        && patchDraft.getChanges() != null
        && !patchDraft.getChanges().isEmpty()) {
      updateRecoveryState(
          iteration,
          readTaskMetadata(iteration),
          RECOVERY_PATCH_PLAN_READY,
          "Patch plan is ready for review and relaunch");
    } else {
      updateRecoveryState(
          iteration,
          readTaskMetadata(iteration),
          RECOVERY_FAILED,
          "Patch planning completed but no patch draft was produced");
    }
    return toRunSummary(
        iterationRepository.findByIterationId(iteration.getIterationId()).orElseThrow());
  }

  private Map<String, Object> executeApprovedRecovery(String iterationId) throws Exception {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      throw new IllegalStateException("Run must fail before it can be fixed");
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      throw new IllegalStateException(
          "Publish failed; there is no runnable task instance to relaunch");
    }

    if (iteration.getAnalysisResponseJson() == null
        || iteration.getAnalysisResponseJson().isNull()) {
      throw new IllegalStateException("Analyze Failure must be run first");
    }
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    if (!RECOVERY_PATCH_PLAN_READY.equals(stringValue(recoveryMetadata.get("phase")))) {
      throw new IllegalStateException(
          determineApprovalBlockedReason(iteration, publishStatus, recoveryMetadata));
    }

    AnalysisV2Response analysis =
        objectMapper.treeToValue(iteration.getAnalysisResponseJson(), AnalysisV2Response.class);
    AnalysisV2PatchDraft patchDraft = analysis.getPatchDraft();
    if (patchDraft == null
        || patchDraft.getChanges() == null
        || patchDraft.getChanges().isEmpty()) {
      throw new IllegalStateException("Analysis did not produce a patch draft");
    }

    String taskKey = stringValue(taskMetadata.get("task_key"));
    if (taskKey == null) {
      taskKey = iteration.getScriptName();
    }

    // Acquire the local launch lease before any Micro6 mutation (patch or relaunch)
    String recoveryAccountKey =
        firstNonBlank(
            stringValue(readAccountMetadata(taskMetadata).get("account_hash")),
            stringValue(readAccountMetadata(taskMetadata).get("account_id")));
    String recoveryMachineUuid =
        stringValue(readAccountMetadata(taskMetadata).get("launcher_machine_uuid"));
    if (recoveryAccountKey == null || recoveryMachineUuid == null) {
      throw new IllegalStateException(
          "Cannot acquire launch lease for recovery: account or machine metadata is missing");
    }
    BuilderGoalLocalLaunchCoordinatorService.AcquireResult recoveryAcquire =
        localLaunchCoordinator.tryAcquire(
            recoveryMachineUuid,
            executorPort,
            recoveryAccountKey,
            "recovery:" + iteration.getIterationId());
    if (recoveryAcquire.conflict() != null) {
      BuilderGoalLocalLaunchCoordinatorService.Conflict conflict = recoveryAcquire.conflict();
      throw new IllegalStateException(
          "Another workflow ("
              + conflict.workflowType()
              + ", owner="
              + conflict.ownerId()
              + ") currently holds the local launch lease. Retry recovery after that workflow"
              + " completes or fails.");
    }
    BuilderGoalLocalLaunchCoordinatorService.LeaseHandle recoveryLease = recoveryAcquire.handle();

    try {
      updateRecoveryState(
          iteration, taskMetadata, RECOVERY_APPLYING_PATCH, "Applying patch to Micro6 task source");
      Micro6TaskSourcePatchService.PatchApplyResult patchResult =
          micro6TaskSourcePatchService.applyPatch(
              resolveTargetBaseUrl(taskMetadata), taskKey, iteration.getIterationId(), patchDraft);
      iteration.setMicro6RevisionId(patchResult.revisionId());
      iteration.setMicro6PatchedAt(LocalDateTime.now());
      persistenceService.saveIteration(iteration);

      updateRecoveryState(
          iteration,
          taskMetadata,
          RECOVERY_RESTARTING_TARGET,
          "Restarting Micro6 target to load patched code");
      Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
      Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
      NormalizedPayload normalized = normalizeStoredIteration(iteration);
      Micro6TargetPreparationService.PreparationResult preparationResult =
          targetPreparationService.ensureReady(
              new Micro6TargetPreparationService.PreparationRequest(
                  firstNonBlank(
                      stringValue(accountMetadata.get("account_hash")),
                      stringValue(accountMetadata.get("account_id"))),
                  stringValue(accountMetadata.get("account_hash")),
                  firstNonBlank(
                      stringValue(accountMetadata.get("account_name")),
                      stringValue(targetMetadata.get("account_name"))),
                  stringValue(accountMetadata.get("email")),
                  resolveAccountPassword(accountMetadata),
                  normalized.world(),
                  stringValue(accountMetadata.get("launcher_machine_uuid")),
                  resolveTargetBaseUrl(taskMetadata),
                  true),
              recoveryLease,
              BuilderGoalLaunchCancellation.NONE,
              null);

      updateRecoveryState(
          iteration,
          taskMetadata,
          RECOVERY_RELOGGING_IN,
          "Local Micro6 workspace restarted for the selected account");
      updateRecoveryState(
          iteration,
          taskMetadata,
          RECOVERY_WAITING_FOR_EXECUTOR,
          "Executor became dispatch-ready; preparing republish");
      mergeTargetPreparationMetadata(taskMetadata, preparationResult.metadata());
      taskMetadata.put("micro6_target", buildTargetMetadata(preparationResult.targetClient()));
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      persistenceService.saveIteration(iteration);
      updateRecoveryState(
          iteration,
          readTaskMetadata(iteration),
          RECOVERY_REPUBLISHING,
          "Republishing patched task to relaunched target");
      // Reserve a deterministic debug session id so the restarted client can rebind to the same
      // session on the same debugger surface (carried via SessionStartRequest.preferredSessionId
      // and honored by DebugSessionService.resolveOrCreateSession).
      String preferredSessionId =
          reservePreferredDebugSession(iteration, readTaskMetadata(iteration));
      JsonNode importedPayload = iteration.getImportedPayloadJson();
      PatchLoopIteration relaunched =
          createRunIteration(
              normalized,
              importedPayload,
              iteration.getIterationId(),
              patchResult.revisionId(),
              buildTargetMetadata(preparationResult.targetClient()),
              null,
              PROVENANCE_AUTOMATION);
      Map<String, Object> relaunchedMetadata = readTaskMetadata(relaunched);
      mergeTargetPreparationMetadata(relaunchedMetadata, preparationResult.metadata());
      Map<String, Object> childWorkspace = readRecoveryWorkspace(relaunchedMetadata);
      childWorkspace.put("preferred_session_id", preferredSessionId);
      childWorkspace.put("parent_iteration_id", iteration.getIterationId());
      relaunchedMetadata.put("recovery_workspace", childWorkspace);
      // Surface the reserved preferred session id at a top-level, discoverable key in the task
      // metadata blob that publishMicro6TaskInternal forwards to the executor. The out-of-repo
      // Micro6 client reads task_metadata.preferred_session_id and echoes it into its
      // SESSION_START.preferredSessionId; the in-repo consumer (TickStreamWebSocketHandler ->
      // DebugSessionService.resolveOrCreateSession) then reuses the active reserved session.
      relaunchedMetadata.put("preferred_session_id", preferredSessionId);
      relaunched.setTaskMetadataJson(objectMapper.valueToTree(relaunchedMetadata));
      persistenceService.saveIteration(relaunched);
      ScriptExecutionService.Micro6PublishResult publishResult;
      try {
        publishResult = publishIteration(relaunched);
      } catch (PublishDeferredException e) {
        updateRecoveryState(
            iteration, readTaskMetadata(iteration), RECOVERY_WAITING_FOR_EXECUTOR, e.getMessage());
        Map<String, Object> deferredResponse = new LinkedHashMap<>();
        deferredResponse.put("iterationId", iteration.getIterationId());
        deferredResponse.put("childIterationId", relaunched.getIterationId());
        deferredResponse.put("preferredSessionId", preferredSessionId);
        deferredResponse.put("recoveryPhase", RECOVERY_WAITING_FOR_EXECUTOR);
        deferredResponse.put("status", stringValue(toRunSummary(iteration).get("status")));
        deferredResponse.put("patched", true);
        deferredResponse.put("patchId", patchDraft.getPatchId());
        deferredResponse.put("revisionId", patchResult.revisionId());
        deferredResponse.put("message", patchResult.message());
        deferredResponse.put("launcherPid", preparationResult.metadata().get("launcher_pid"));
        deferredResponse.put(
            "launchLogFile", preparationResult.metadata().get("launcher_log_file"));
        deferredResponse.put("published", false);
        deferredResponse.put("publishStatus", RECOVERY_WAITING_FOR_EXECUTOR);
        deferredResponse.put("publishError", e.getMessage());
        deferredResponse.put("run", toRunSummary(relaunched));
        return deferredResponse;
      }
      // Keep WAITING_FOR_DEBUG_BIND as a nonterminal same-screen recovery state until the
      // restarted client binds to the reserved debug session (syncManualRecoveryStates transitions
      // the parent to RELAUNCHED once the child run reports a bound debug_session_id).
      updateRecoveryState(
          iteration,
          readTaskMetadata(iteration),
          RECOVERY_WAITING_FOR_DEBUG_BIND,
          "Patched task republished to "
              + preparationResult.targetClient().accountName()
              + ". Waiting for the restarted client to bind to debug session "
              + preferredSessionId
              + ".");

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("iterationId", iteration.getIterationId());
      response.put("childIterationId", relaunched.getIterationId());
      response.put("preferredSessionId", preferredSessionId);
      response.put("recoveryPhase", RECOVERY_WAITING_FOR_DEBUG_BIND);
      response.put("status", stringValue(toRunSummary(iteration).get("status")));
      response.put("patched", true);
      response.put("patchId", patchDraft.getPatchId());
      response.put("revisionId", patchResult.revisionId());
      response.put("message", patchResult.message());
      response.put(
          "launcherCommandId",
          preparationResult.startCommand() != null
              ? preparationResult.startCommand().commandId()
              : null);
      response.put("launcherPid", preparationResult.metadata().get("launcher_pid"));
      response.put("launchLogFile", preparationResult.metadata().get("launcher_log_file"));
      response.put("published", publishResult.published());
      response.put("publishStatus", publishResult.publishStatus());
      response.put("publishError", publishResult.errorMessage());
      response.put("run", toRunSummary(relaunched));
      return response;
    } catch (Exception e) {
      updateRecoveryState(
          iteration,
          readTaskMetadata(iteration),
          RECOVERY_FAILED,
          firstNonBlank(e.getMessage(), "Fix + Relaunch failed"));
      throw e;
    } finally {
      localLaunchCoordinator.release(recoveryLease);
    }
  }

  public Map<String, Object> stopRun(String iterationId, JsonNode payload) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    String publishStatus = stringValue(publishMetadata.get("status"));
    String reason = textValue(payload != null ? payload.get("reason") : null);
    if (reason == null || reason.isBlank()) {
      reason = "Stopped from debugger";
    }

    String blockedReason = determineStopBlockedReason(iteration, publishStatus);
    if (blockedReason != null) {
      throw new IllegalStateException(blockedReason);
    }

    // Persist stop intent first via the mutation service (locked transaction).
    BuilderGoalRunStateMutationService.StopRequestResult stopResult =
        mutationService.requestStop(iterationId, reason);
    Integer entryId = stopResult.preservedEntryId();

    if (stopResult.isPrePublish()) {
      // Cancel pre-publish runs transactionally via the mutation service.
      // Flag and interrupt any active launch worker before removing from tracking.
      ActiveLaunchWork work = activeLaunchWork.get(iterationId);
      if (work != null) {
        work.cancel();
        // Do NOT release the lease here; the worker's finally block owns that
      }
      pendingLaunchIterations.remove(iterationId);
      mutationService.cancelPrePublish(iterationId, reason);
    } else {
      // Use the metadata snapshot from the locked stop so the network call uses fresh state.
      Map<String, Object> freshTaskMetadata = stopResult.taskMetadataSnapshot();
      // Send /stop to the executor now that stop intent is persisted.
      ScriptExecutionService.Micro6ControlResult controlResult =
          scriptExecutionService.requestStopMicro6PublishedRun(freshTaskMetadata, entryId, reason);
      if (controlResult == null || !controlResult.delivered()) {
        // Record stop_send_error through the mutation service (locked transaction).
        mutationService.recordStopDeliveryFailure(
            iterationId, entryId, "Stop request did not reach Micro6 executor");
        throw new IllegalStateException(
            "Failed to deliver stop request to Micro6; the executor may still be running the task."
                + " Check the target base URL and retry.");
      }
      if (controlResult.authoritativeStatus() != null
          && controlResult.authoritativeStatus().found()
          && "CANCELLED".equalsIgnoreCase(controlResult.authoritativeStatus().status())) {
        mutationService.applyExecutorStatus(
            iterationId, entryId, controlResult.authoritativeStatus(), LocalDateTime.now());
      }
      // A successful delivery supersedes any stale failure recorded by a prior retry.
      mutationService.clearStopDeliveryFailure(iterationId, entryId);
    }

    iteration = iterationRepository.findByIterationId(iterationId).orElse(iteration);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("run", toRunSummary(iteration));
    response.put(
        "message",
        stopResult.isPrePublish()
            ? "Task removed before publish."
            : "Stop requested. Waiting for Micro6 to confirm cancellation.");
    return response;
  }

  public Map<String, Object> pauseRun(String iterationId) {
    return controlRun(iterationId, "pause");
  }

  public Map<String, Object> resumeRun(String iterationId) {
    return controlRun(iterationId, "resume");
  }

  private Map<String, Object> controlRun(String iterationId, String action) {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId == null || entryId <= 0) {
      throw new IllegalStateException("Run has not been published to Micro6 yet");
    }
    if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
      throw new IllegalStateException("Run is already in a terminal state");
    }
    ScriptExecutionService.Micro6ControlResult controlResult =
        "pause".equalsIgnoreCase(action)
            ? scriptExecutionService.requestPauseMicro6PublishedRun(taskMetadata, entryId)
            : scriptExecutionService.requestResumeMicro6PublishedRun(taskMetadata, entryId);
    if (controlResult == null || !controlResult.delivered()) {
      throw new IllegalStateException("Failed to deliver " + action + " request to Micro6");
    }
    ScriptExecutionService.Micro6RunStatusResult statusResult = controlResult.authoritativeStatus();
    boolean authoritativeControlStatus =
        statusResult != null
            && statusResult.found()
            && ("pause".equalsIgnoreCase(action)
                ? "PAUSED".equalsIgnoreCase(statusResult.status())
                : isResumeActiveStatus(statusResult.status()));
    if (authoritativeControlStatus) {
      mutationService.applyExecutorStatus(iterationId, entryId, statusResult, LocalDateTime.now());
    } else {
      mutationService.recordControlRequested(iterationId, entryId, action);
    }
    PatchLoopIteration updated = iterationRepository.findByIterationId(iterationId).orElse(iteration);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("action", action.toUpperCase(Locale.ENGLISH));
    response.put("run", toRunSummary(updated));
    response.put(
        "message",
        authoritativeControlStatus
            ? ("pause".equalsIgnoreCase(action) ? "Task paused." : "Task resumed.")
            : ("pause".equalsIgnoreCase(action) ? "Pause requested." : "Resume requested."));
    return response;
  }

  private boolean isResumeActiveStatus(String status) {
    return "RECEIVED".equalsIgnoreCase(status)
        || "PENDING".equalsIgnoreCase(status)
        || "ACCEPTED".equalsIgnoreCase(status)
        || "QUEUED".equalsIgnoreCase(status)
        || "ASSIGNED".equalsIgnoreCase(status)
        || "RUNNING".equalsIgnoreCase(status);
  }

  private boolean isPublishOnlyNoLaunch(Map<String, Object> taskMetadata) {
    if (taskMetadata == null || taskMetadata.isEmpty()) {
      return false;
    }
    if (!Boolean.TRUE.equals(taskMetadata.get(TASK_METADATA_PUBLISH_ONLY))) {
      return false;
    }
    if (Boolean.TRUE.equals(taskMetadata.get(TASK_METADATA_LAUNCH_FALLBACK_ALLOWED))) {
      return false;
    }
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    return entryId == null || entryId <= 0;
  }

  private String determineStopBlockedReason(PatchLoopIteration iteration, String publishStatus) {
    if (iteration == null || iteration.getStatus() == null) {
      return "Run is malformed and cannot be stopped";
    }
    if (iteration.getStatus().isTerminal()) {
      return "Run is already in a terminal state and cannot be stopped";
    }
    if ("COMPLETED".equalsIgnoreCase(publishStatus)) {
      return "Run has already completed and cannot be stopped";
    }
    if ("FAILED".equalsIgnoreCase(publishStatus)) {
      return "Run has already failed and cannot be stopped";
    }
    if ("CANCELLED".equalsIgnoreCase(publishStatus)) {
      return "Run has already been cancelled";
    }
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(readTaskMetadata(iteration));
    if (RECOVERY_RELAUNCHED.equals(stringValue(recoveryMetadata.get("phase")))) {
      return "Run has been relaunched and is no longer the active target";
    }
    return null;
  }

  private boolean isStopRequested(
      Map<String, Object> taskMetadata, Map<String, Object> publishMetadata) {
    if (taskMetadata != null) {
      Object stopAt = taskMetadata.get("stop_requested_at");
      if (stopAt != null && !stopAt.toString().isBlank()) {
        return true;
      }
    }
    if (publishMetadata != null) {
      Object stopAt = publishMetadata.get("stop_requested_at");
      if (stopAt != null && !stopAt.toString().isBlank()) {
        return true;
      }
    }
    return false;
  }

  private boolean reconcileStaleLaunchFailure(PatchLoopIteration iteration) {
    if (iteration == null
        || iteration.getIterationId() == null
        || iteration.getStatus() == null
        || iteration.getStatus().isTerminal()) {
      return false;
    }
    Map<String, Object> targetPreparation =
        readTargetPreparationMetadata(readTaskMetadata(iteration));
    if (!Micro6TargetRelaunchService.PHASE_PROCESS_EXITED.equalsIgnoreCase(
        stringValue(targetPreparation.get("state")))) {
      return false;
    }
    return mutationService.failStaleLaunchIfActive(iteration.getIterationId());
  }

  private String findActiveLaunchConflictReason(String accountId, String taskKey) {
    if (accountId == null || accountId.isBlank() || taskKey == null || taskKey.isBlank()) {
      return null;
    }
    String normalizedTaskKey = normalizeTaskKey(taskKey);
    return iterationRepository
        .findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL)
        .stream()
        .filter(iteration -> iteration.getStatus() != null && iteration.getStatus().isActive())
        .filter(this::hasStoredConfig)
        .filter(this::isLaunchConflictCandidate)
        .filter(
            iteration -> {
              Map<String, Object> taskMetadata = readTaskMetadata(iteration);
              Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
              String storedAccountId =
                  firstNonBlank(
                      stringValue(accountMetadata.get("account_id")),
                      stringValue(accountMetadata.get("account_hash")));
              return accountId.equals(storedAccountId)
                  && normalizedTaskKey.equals(normalizeTaskKey(iteration.getScriptName()));
            })
        .map(
            iteration ->
                "A Builder Goal run is already active for this account and task"
                    + (iteration.getIterationId() != null ? ": " + iteration.getIterationId() : ""))
        .findFirst()
        .orElse(null);
  }

  private boolean isLaunchConflictCandidate(PatchLoopIteration iteration) {
    if (iteration == null || iteration.getStatus() == null || iteration.getStatus().isTerminal()) {
      return false;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (isPublishOnlyNoLaunch(taskMetadata)) {
      return false;
    }
    String displayStatus =
        deriveDisplayStatus(
            iteration, stringValue(readPublishMetadata(taskMetadata).get("status")));
    return !"FAILED".equalsIgnoreCase(displayStatus)
        && !"CANCELLED".equalsIgnoreCase(displayStatus)
        && !"COMPLETED".equalsIgnoreCase(displayStatus)
        && !"PROCESS_EXITED".equalsIgnoreCase(displayStatus)
        && !"PUBLISH_FAILED".equalsIgnoreCase(displayStatus);
  }

  @Scheduled(fixedDelayString = "${builder-goal.status-sync-interval-ms:3000}")
  public void syncPublishedRuns() {
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(iteration -> iteration.getStatus() != null && iteration.getStatus().isActive())
        .forEach(this::syncPublishedRunSafely);
    // Fair late-accept recovery: runs previously recorded as PUBLISH_FAILED (terminal) may still
    // have been accepted by the executor after the execute timeout. Rotate through all eligible
    // runs fairly: sort by last_reconcile_attempt_at (null first, then oldest), select at most
    // ten per scheduler run so no run is starved.
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(
            iteration ->
                iteration.getStatus() == PatchLoopStatus.FAILED
                    && "PUBLISH_FAILED"
                        .equalsIgnoreCase(
                            stringValue(
                                readPublishMetadata(readTaskMetadata(iteration)).get("status"))))
        .sorted(
            (a, b) -> {
              String attemptA =
                  stringValue(
                      readPublishMetadata(readTaskMetadata(a)).get("last_reconcile_attempt_at"));
              String attemptB =
                  stringValue(
                      readPublishMetadata(readTaskMetadata(b)).get("last_reconcile_attempt_at"));
              if (attemptA == null && attemptB == null) return 0;
              if (attemptA == null) return -1;
              if (attemptB == null) return 1;
              return attemptA.compareTo(attemptB);
            })
        .limit(10)
        .forEach(this::reconcileLateAcceptedPublish);
  }

  /**
   * Recovers a run that was recorded as PUBLISH_FAILED when late executor evidence proves the task
   * was actually accepted. Keeps the reserved entry id and moves the run back to the active status
   * sync so recording/debug-session ids can attach and the run can progress.
   */
  private void reconcileLateAcceptedPublish(PatchLoopIteration iteration) {
    String iterationId = iteration.getIterationId();
    try {
      // Begin late reconcile via mutation service (locked, persists attempt timestamp).
      Optional<BuilderGoalRunStateMutationService.ReconcileSnapshot> snapshotOpt =
          mutationService.beginLateReconcile(iterationId);
      if (snapshotOpt.isEmpty()) {
        return;
      }
      BuilderGoalRunStateMutationService.ReconcileSnapshot snapshot = snapshotOpt.get();
      int entryId = snapshot.entryId();

      // Poll Micro6 outside the database lock.
      Map<String, Object> taskMetadata = snapshot.taskMetadata();
      ScriptExecutionService.Micro6RunStatusResult statusResult =
          scriptExecutionService.getMicro6RunStatus(taskMetadata, entryId);

      // Extract executor iteration_id and task_key from raw response for identity verification.
      String executorIterationId = null;
      String executorTaskKey = null;
      if (statusResult != null && statusResult.rawResponse() != null) {
        // Preserve identity-bearing whitespace. The late-reconcile mutation must reject a
        // near-match rather than allowing the generic text normalizer to turn it into the
        // persisted canonical key.
        executorIterationId = strictTextValue(statusResult.rawResponse().path("iteration_id"));
        executorTaskKey = strictTextValue(statusResult.rawResponse().path("task_key"));
        if (executorTaskKey == null) {
          // Older executor status payloads used queued_task for the same canonical identity.
          // Preserve its raw value as well; the mutation layer performs the exact comparison.
          executorTaskKey = strictTextValue(statusResult.rawResponse().path("queued_task"));
        }
      }

      // Apply late reconcile via mutation service (locked, with identity verification).
      BuilderGoalRunStateMutationService.ReconcileApplyResult result =
          mutationService.applyLateReconcile(
              iterationId, entryId, executorIterationId, executorTaskKey, statusResult);

      if (result.revived()) {
        logger.info(
            "Late executor acceptance recovered for Builder Goal run {} (entry {}): reason={}",
            iterationId,
            entryId,
            result.reason());
        // Re-enter normal status sync path so the run progresses.
        PatchLoopIteration revived =
            iterationRepository.findByIterationId(iterationId).orElse(null);
        if (revived != null) {
          syncPublishedRunSafely(revived);
        }
      }
    } catch (Exception e) {
      logger.warn(
          "Late-accept reconciliation failed for iteration {}: {}", iterationId, e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${builder-goal.recovery-sync-interval-ms:5000}")
  public void syncManualRecoveryStates() {
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(this::shouldMoveToAwaitingOperatorPrompt)
        .limit(10)
        .forEach(
            iteration ->
                moveToAwaitingOperatorPrompt(
                    iteration,
                    "Task failed. Add your observations, select the relevant evidence, and generate"
                        + " a patch plan."));
    // Transition parents waiting for a debug bind to RELAUNCHED once their child run binds to a
    // debug session, completing the same-screen recovery loop.
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(this::shouldMoveToRelaunched)
        .limit(10)
        .forEach(this::moveToRelaunched);
  }

  private boolean shouldMoveToRelaunched(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String phase = stringValue(readRecoveryMetadata(taskMetadata).get("phase"));
    if (!RECOVERY_WAITING_FOR_DEBUG_BIND.equals(phase)) {
      return false;
    }
    String childId = findChildIterationId(iteration.getIterationId());
    if (childId == null) {
      return false;
    }
    PatchLoopIteration child = iterationRepository.findByIterationId(childId).orElse(null);
    return child != null && child.getRunSessionId() != null && !child.getRunSessionId().isBlank();
  }

  private void moveToRelaunched(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String childId = findChildIterationId(iteration.getIterationId());
    updateRecoveryState(
        iteration,
        taskMetadata,
        RECOVERY_RELAUNCHED,
        "Restarted client bound to debug session. Following child run " + childId + ".");
  }

  private PatchLoopIteration createRunIteration(
      NormalizedPayload normalized,
      JsonNode importedPayload,
      String parentIterationId,
      String revisionId,
      Map<String, Object> targetMetadata,
      JsonNode catalogTask,
      Map<String, Object> provenance) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(ITERATION_ID_PREFIX + UUID.randomUUID().toString().substring(0, 8));
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setScriptName(normalized.taskKey());
    iteration.setPrompt(normalized.displayName());
    iteration.setContext("Imported builder goal run");
    iteration.setExecutionMode(EXECUTION_MODE_MANUAL_RECOVERY);
    iteration.setMaxAttempts(1);
    iteration.setCurrentAttempt(1);
    iteration.setSourceType(SOURCE_TYPE_BUILDER_GOAL);
    iteration.setPatchTarget(PATCH_TARGET_MICRO6_TASK_SOURCE);
    iteration.setParentIterationId(parentIterationId);
    iteration.setImportedPayloadJson(importedPayload);
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(
            buildTaskMetadata(
                iteration.getIterationId(), normalized, targetMetadata, catalogTask, provenance)));
    iteration.setPromptType(PromptType.ISSUE.name());
    if (revisionId != null && !revisionId.isBlank()) {
      iteration.setMicro6RevisionId(revisionId);
      iteration.setMicro6PatchedAt(LocalDateTime.now());
    }
    validateStoredSettingsConsistency(iteration);
    return iterationRepository.save(iteration);
  }

  private ScriptExecutionService.Micro6PublishResult publishIteration(
      PatchLoopIteration iteration) {
    String iterationId = iteration.getIterationId();
    validateStoredSettingsConsistency(iteration);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    ensureTargetStillPublishable(iteration, taskMetadata);

    // Reserve entry id inside a locked transaction via the mutation service.
    int reservedEntryId = scriptExecutionService.reserveMicro6EntryId();
    BuilderGoalRunStateMutationService.PublishReservation reservation =
        mutationService.reservePublish(iterationId, reservedEntryId, LocalDateTime.now());

    // Use the snapshot returned by reservePublish for the /execute call.
    Map<String, Object> snapshotTaskMetadata = reservation.taskMetadataSnapshot();
    ScriptExecutionService.Micro6PublishResult publishResult =
        scriptExecutionService.publishMicro6Task(
            "micro6:" + iteration.getScriptName(), snapshotTaskMetadata, reservedEntryId);

    // On a definitive publish failure, take the health snapshot outside the DB lock and pass it
    // into the atomic outcome mutation. The mutation service merges it with the latest metadata,
    // so a concurrent stop/status poll cannot be overwritten by this stale runner snapshot.
    ScriptExecutionService.Micro6ExecutorHealthResult healthResult =
        publishResult.published() || "RECONCILING".equalsIgnoreCase(publishResult.publishStatus())
            ? null
            : refreshTargetPreparationAfterPublishFailure(
                iteration, snapshotTaskMetadata, publishResult);

    // Apply outcome through the mutation service (locked transaction).
    BuilderGoalRunStateMutationService.ApplyOutcomeResult outcome =
        mutationService.applyPublishOutcome(
            iterationId, reservedEntryId, publishResult, healthResult);

    // If a concurrent stop won the race, send executor cancellation.
    if (outcome.needsExecutorCancel()) {
      try {
        scriptExecutionService.stopMicro6PublishedRun(
            snapshotTaskMetadata, reservedEntryId, "Stop requested before publish completed");
      } catch (Exception e) {
        logger.warn(
            "Failed to cancel executor entry {} after stop won publish race for {}: {}",
            reservedEntryId,
            iterationId,
            e.getMessage());
      }
    }

    return publishResult;
  }

  private Map<String, Object> buildReconcilingPublishMetadata(
      ScriptExecutionService.Micro6PublishResult publishResult,
      LocalDateTime publishedAt,
      Map<String, Object> taskMetadata) {
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", publishResult.entryId());
    publish.put("status", "RECONCILING");
    publish.put("published_at", publishedAt.toString());
    publish.put("attached_to_existing", publishResult.attachedToExisting());
    publish.put("micro6_base_url", publishResult.micro6BaseUrl());
    publish.put(
        "last_error",
        firstNonBlank(
            publishResult.errorMessage(),
            "Awaiting executor confirmation for entry " + publishResult.entryId()));
    return publish;
  }

  private void ensureTargetStillPublishable(
      PatchLoopIteration iteration, Map<String, Object> taskMetadata) {
    ScriptExecutionService.Micro6ExecutorHealthResult healthResult =
        scriptExecutionService.getMicro6ExecutorHealth(taskMetadata);
    boolean accountMatched = matchesExpectedTargetAccount(taskMetadata, healthResult);
    if (!healthResult.reachable()) {
      refreshTargetPreparationFromHealth(
          iteration,
          taskMetadata,
          healthResult,
          firstNonBlank(
              healthResult.readinessPhase(), Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH),
          firstNonBlank(
              healthResult.readinessReason(),
              "Micro6 local executor health could not be confirmed right before publish"));
      throw new PublishDeferredException(
          firstNonBlank(
              healthResult.readinessPhase(), Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH),
          firstNonBlank(
              healthResult.readinessReason(),
              "Micro6 local executor health could not be confirmed right before publish"));
    }
    if (!matchesSelectedTargetHealth(taskMetadata, healthResult)) {
      String reason =
          "Micro6 /health no longer matches the selected registry client identity, world, process, or launch attempt";
      refreshTargetPreparationFromHealth(
          iteration, taskMetadata, healthResult, Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT, reason);
      throw new PublishDeferredException(
          Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT, reason);
    }
    if (!accountMatched) {
      String reason = "Micro6 local executor came up but did not identify the selected account yet";
      refreshTargetPreparationFromHealth(
          iteration,
          taskMetadata,
          healthResult,
          Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT,
          reason);
      throw new PublishDeferredException(
          Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT, reason);
    }
    if (!healthResult.publishReady()) {
      String phase =
          firstNonBlank(
              healthResult.readinessPhase(),
              Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH);
      String reason =
          firstNonBlank(
              healthResult.readinessReason(),
              "Micro6 local executor is logged in but not dispatch-ready yet");
      refreshTargetPreparationFromHealth(iteration, taskMetadata, healthResult, phase, reason);
      throw new PublishDeferredException(phase, reason);
    }
    refreshTargetPreparationFromHealth(
        iteration,
        taskMetadata,
        healthResult,
        PREPARATION_STATE_READY_TO_PUBLISH,
        "Target dispatch-ready and ready to publish");
  }

  private ScriptExecutionService.Micro6ExecutorHealthResult
      refreshTargetPreparationAfterPublishFailure(
          PatchLoopIteration iteration,
          Map<String, Object> taskMetadata,
          ScriptExecutionService.Micro6PublishResult publishResult) {
    ScriptExecutionService.Micro6ExecutorHealthResult healthResult =
        scriptExecutionService.getMicro6ExecutorHealth(taskMetadata);
    String reason;
    String phase;
    if (!healthResult.reachable()) {
      phase =
          firstNonBlank(
              healthResult.readinessPhase(), Micro6TargetRelaunchService.PHASE_WAITING_FOR_HEALTH);
      reason =
          firstNonBlank(
              healthResult.readinessReason(),
              publishResult.errorMessage(),
              "Micro6 local executor health could not be confirmed after publish failure");
    } else if (!matchesExpectedTargetAccount(taskMetadata, healthResult)) {
      phase = Micro6TargetRelaunchService.PHASE_WAITING_FOR_ACCOUNT;
      reason = "Micro6 local executor came up but did not identify the selected account yet";
    } else if (!healthResult.publishReady()) {
      phase =
          firstNonBlank(
              healthResult.readinessPhase(),
              Micro6TargetRelaunchService.PHASE_WAITING_FOR_DISPATCH);
      reason =
          firstNonBlank(
              healthResult.readinessReason(),
              publishResult.errorMessage(),
              "Micro6 local executor is not dispatch-ready after publish failure");
    } else {
      phase = PREPARATION_STATE_READY_TO_PUBLISH;
      reason =
          "Micro6 local executor still appears publish-ready after the publish request failed; the"
              + " executor response may have timed out";
    }
    // The caller passes this health snapshot to the locked publish-outcome mutation. Do not write
    // the runner's pre-lock metadata here; doing so could overwrite a concurrent stop or poll.
    return healthResult;
  }

  private void refreshTargetPreparationFromHealth(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult,
      String state,
      String message) {
    Map<String, Object> updates = new LinkedHashMap<>();
    updates.put("state", state);
    updates.put("message", message);
    if (healthResult != null) {
      updates.put("local_executor_base_url", healthResult.micro6BaseUrl());
      updates.put("readiness_phase", healthResult.readinessPhase());
      updates.put("readiness_reason", firstNonBlank(healthResult.readinessReason(), message));
      updates.put("resolved_account_name", healthResult.resolvedAccountName());
      updates.put("resolved_account_hash", healthResult.resolvedAccountHash());
      updates.put("requested_account_name", healthResult.requestedAccountName());
      updates.put("requested_account_hash", healthResult.requestedAccountHash());
      updates.put("dispatch_hold", healthResult.dispatchHold());
      updates.put("dispatch_hold_reason", healthResult.dispatchHoldReason());
      updates.put("startup_state", healthResult.startupState());
      updates.put("login_state", healthResult.loginState());
      updates.put("login_attempt", healthResult.loginAttempt());
      updates.put("login_attempt_max", healthResult.loginAttemptMax());
      updates.put("login_terminal_reason", healthResult.loginTerminalReason());
      JsonNode rawHealth = healthResult.rawResponse();
      if (rawHealth != null && rawHealth.isObject()) {
        updates.put("process_id", intValue(rawHealth.path("process_id")));
        updates.put("launch_attempt_id", textValue(rawHealth.path("launch_attempt_id")));
        updates.put("account_hash_scheme", textValue(rawHealth.path("account_hash_scheme")));
        updates.put("requested_world", intValue(rawHealth.path("requested_world")));
        updates.put("current_world", intValue(rawHealth.path("current_world")));
        updates.put("world_matched", rawHealth.path("world_matched").isBoolean() ? rawHealth.path("world_matched").asBoolean() : null);
        updates.put("pathfinder_ready", rawHealth.path("pathfinder_ready").isBoolean() ? rawHealth.path("pathfinder_ready").asBoolean() : null);
        updates.put("script_lifecycle_state", textValue(rawHealth.path("script_lifecycle_state")));
        updates.put("script_loop_alive", rawHealth.path("script_loop_alive").isBoolean() ? rawHealth.path("script_loop_alive").asBoolean() : null);
      }
    }
    mergeTargetPreparationMetadata(taskMetadata, updates);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private boolean matchesSelectedTargetHealth(
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult) {
    Map<String, Object> target = readTargetMetadata(taskMetadata);
    JsonNode raw = healthResult != null ? healthResult.rawResponse() : null;
    if (target.isEmpty() || raw == null || !raw.isObject()) {
      return false;
    }
    String expectedHash = stringValue(target.get("account_hash"));
    String requestedHash = textValue(raw.path("requested_account_hash"));
    String actualHash = textValue(raw.path("account_hash"));
    String scheme = textValue(raw.path("account_hash_scheme"));
    Integer expectedWorld = intValue(taskMetadata.get("world"));
    Integer requestedWorld = intValue(raw.path("requested_world"));
    Integer currentWorld = intValue(raw.path("current_world"));
    // Recompute world identity from the current request and observed world.  The heartbeat's
    // world_matched flag may have been calculated for an earlier launch request and is diagnostic
    // only; it must never authorize a publish or reject a current exact match.
    boolean worldMatched =
        expectedWorld != null
            && expectedWorld.equals(requestedWorld)
            && expectedWorld.equals(currentWorld);
    String expectedAttempt = firstNonBlank(
        stringValue(target.get("launch_attempt_id")), stringValue(target.get("attempt_id")));
    String actualAttempt = textValue(raw.path("launch_attempt_id"));
    Integer expectedPid = intValue(target.get("process_id"));
    Integer actualPid = intValue(raw.path("process_id"));
    return AccountIdentityContract.hasRequiredScheme(scheme)
        && AccountIdentityContract.matchesCanonicalHash(actualHash, expectedHash)
        && AccountIdentityContract.matchesCanonicalHash(requestedHash, expectedHash)
        && expectedWorld != null
        && expectedWorld.equals(requestedWorld)
        && expectedWorld.equals(currentWorld)
        && worldMatched
        && Boolean.FALSE.equals(raw.path("dispatch_hold").isBoolean()
            ? raw.path("dispatch_hold").asBoolean()
            : null)
        && "accountbuilder_ready".equalsIgnoreCase(textValue(raw.path("startup_state")))
        && "logged_in".equalsIgnoreCase(textValue(raw.path("login_state")))
        && raw.path("pathfinder_ready").isBoolean()
        && raw.path("pathfinder_ready").asBoolean()
        && raw.path("script_loop_alive").isBoolean()
        && raw.path("script_loop_alive").asBoolean()
        && "RUNNING".equalsIgnoreCase(textValue(raw.path("script_lifecycle_state")))
        && expectedAttempt != null
        && expectedAttempt.equals(actualAttempt)
        && expectedPid != null
        && expectedPid > 0
        && expectedPid.equals(actualPid);
  }

  private boolean matchesExpectedTargetAccount(
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult) {
    if (healthResult == null) {
      return false;
    }
    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
    Map<String, Object> targetPreparationMetadata = readTargetPreparationMetadata(taskMetadata);
    String expectedHash =
        firstNonBlank(
            stringValue(accountMetadata.get("account_hash")),
            stringValue(targetMetadata.get("account_hash")),
            stringValue(targetPreparationMetadata.get("requested_account_hash")),
            stringValue(targetMetadata.get("requested_account_hash")));
    String expectedName =
        firstNonBlank(
            stringValue(accountMetadata.get("account_name")),
            stringValue(targetMetadata.get("account_name")),
            stringValue(targetPreparationMetadata.get("requested_account_name")),
            stringValue(targetMetadata.get("requested_account_name")));

    // The actual logged-in hash is authoritative. Requested/compatibility hashes and matching
    // names never override a present hash mismatch. Even the legacy name-fallback path requires
    // a canonical actual hash and accountconnector/v1 scheme; a name-only health response is
    // diagnostics, never publish authorization.
    JsonNode rawHealth = healthResult.rawResponse();
    if (rawHealth == null
        || !AccountIdentityContract.hasRequiredScheme(
            textValue(rawHealth.path("account_hash_scheme")))
        || !AccountIdentityContract.isCanonicalHash(healthResult.resolvedAccountHash())) {
      return false;
    }
    if (expectedHash != null) {
      return AccountIdentityContract.matchesCanonicalHash(
          healthResult.resolvedAccountHash(), expectedHash);
    }
    return AccountIdentityContract.normalizeName(expectedName) != null
        && AccountIdentityContract.normalizeName(expectedName)
            .equals(AccountIdentityContract.normalizeName(healthResult.resolvedAccountName()));
  }

  private void syncPublishedRunSafely(PatchLoopIteration iteration) {
    try {
      syncPublishedRun(iteration);
    } catch (Exception e) {
      // A scheduler/persistence failure is not evidence that the remote task failed. The next
      // poll must be allowed to reconcile it rather than turning a transient exception into a
      // terminal run state from a stale, unlocked entity.
      logger.warn(
          "Builder goal status sync failed for {} (will retry): {}",
          iteration != null ? iteration.getIterationId() : "(unknown)",
          e.getMessage());
    }
  }

  private void syncPublishedRun(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId == null || entryId <= 0) {
      return;
    }

    // 1. Perform network call outside any lock.
    ScriptExecutionService.Micro6RunStatusResult statusResult =
        scriptExecutionService.getMicro6RunStatus(taskMetadata, entryId);

    // 2. Delegate all locked persistence to the mutation service.
    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            iteration.getIterationId(), entryId, statusResult, LocalDateTime.now());

    // 3. Perform post-lock actions described by the result.
    PatchLoopIteration freshIteration = result.iterationForEvidence();
    if (freshIteration == null) {
      freshIteration = iteration;
    }

    if (result.resolveSession()) {
      resolveRunSession(freshIteration);
    }
    if (result.collectEvidence()) {
      tryCollectEvidence(freshIteration);
    }
    if (result.moveToRecovery() && result.failureReason() != null) {
      moveToAwaitingOperatorPrompt(
          freshIteration,
          firstNonBlank(
              result.failureReason(),
              "Task failed. Add your observations, select the relevant evidence, and generate a"
                  + " patch plan."));
    }
    if (result.sendExecutorCancel()) {
      scriptExecutionService.stopMicro6PublishedRun(
          readTaskMetadata(freshIteration), entryId, "Executor cancellation from status sync");
    }
  }

  private Map<String, Object> buildImportResponse(
      NormalizedPayload normalized,
      JsonNode catalogTask,
      BuilderGoalCatalogSnapshot catalogSnapshot) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("title", normalized.displayName());
    response.put("taskKey", normalized.taskKey());
    response.put("settings", normalized.settings());
    response.put("settingsCount", normalized.settings().size());
    response.put("catalogAvailable", catalogSnapshot.catalogAvailable());
    response.put("catalogDegraded", catalogSnapshot.degraded());
    response.put("catalogSource", catalogSnapshot.source());
    response.put("catalogWarning", catalogSnapshot.degradedReason());

    boolean degradedCatalog =
        catalogSnapshot.degraded()
            && (!catalogSnapshot.catalogAvailable()
                || (catalogTask != null && catalogTask.path("placeholder").asBoolean(false)));

    if (catalogTask == null || catalogTask.isMissingNode() || catalogTask.isNull()) {
      response.put("runnableNow", degradedCatalog);
      if (degradedCatalog) {
        response.put("displayName", firstNonBlank(normalized.displayName(), normalized.taskKey()));
        response.put("matchedSettingsCount", normalized.settings().size());
        response.put("ignoredSettingsCount", 0);
        response.put("matchedSettings", new ArrayList<>(normalized.settings().keySet()));
        response.put("ignoredSettings", List.of());
        response.put("settingsValidationSkipped", true);
        return response;
      }
      response.put("error", "Task was not found in the Micro6 catalog");
      response.put("matchedSettingsCount", 0);
      response.put("ignoredSettingsCount", normalized.settings().size());
      response.put("matchedSettings", List.of());
      response.put("ignoredSettings", new ArrayList<>(normalized.settings().keySet()));
      return response;
    }

    if (degradedCatalog) {
      response.put("runnableNow", catalogTask.path("runnable").asBoolean(true));
      response.put(
          "displayName", catalogTask.path("display_name").asText(normalized.displayName()));
      response.put(
          "panelDisplayName",
          catalogTask.path("panel_display_name").asText(normalized.displayName()));
      response.put("matchedSettingsCount", normalized.settings().size());
      response.put("ignoredSettingsCount", 0);
      response.put("matchedSettings", new ArrayList<>(normalized.settings().keySet()));
      response.put("ignoredSettings", List.of());
      response.put("settingsValidationSkipped", true);
      response.put("disabledReason", textValue(catalogTask.path("disabled_reason")));
      return response;
    }

    Set<String> knownFieldKeys =
        readArgsFields(catalogTask).stream()
            .map(field -> field.path("key").asText(null))
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toSet());

    List<String> matchedSettings =
        normalized.settings().keySet().stream()
            .filter(knownFieldKeys::contains)
            .sorted()
            .collect(Collectors.toList());
    List<String> ignoredSettings =
        normalized.settings().keySet().stream()
            .filter(key -> !knownFieldKeys.contains(key))
            .sorted()
            .collect(Collectors.toList());

    response.put("runnableNow", catalogTask.path("runnable").asBoolean(true));
    response.put("displayName", catalogTask.path("display_name").asText(normalized.displayName()));
    response.put(
        "panelDisplayName",
        catalogTask.path("panel_display_name").asText(normalized.displayName()));
    response.put("matchedSettingsCount", matchedSettings.size());
    response.put("ignoredSettingsCount", ignoredSettings.size());
    response.put("matchedSettings", matchedSettings);
    response.put("ignoredSettings", ignoredSettings);
    response.put("disabledReason", textValue(catalogTask.path("disabled_reason")));
    return response;
  }

  private Map<String, Object> buildStoredConfigResponse(PatchLoopIteration iteration) {
    NormalizedPayload normalized = normalizeStoredIteration(iteration);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("configAvailable", hasStoredConfig(iteration));
    response.put(
        "accountId",
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash"))));
    response.put("accountName", stringValue(accountMetadata.get("account_name")));
    response.put("launcherMachineUuid", stringValue(accountMetadata.get("launcher_machine_uuid")));
    response.put("launcherMachineName", stringValue(accountMetadata.get("launcher_machine_name")));
    response.put("world", normalized.world());
    response.put("taskKey", normalized.taskKey());
    response.put("displayName", normalized.displayName());
    response.put("settings", normalized.settings());
    response.put("source", stringValue(taskMetadata.get("source")));
    response.put("iterationId", iteration.getIterationId());
    response.put("createdAt", iteration.getCreatedAt());
    response.put("runRecordingId", iteration.getRunRecordingId());
    response.put("runSessionId", iteration.getRunSessionId());
    return response;
  }

  private boolean hasStoredConfig(PatchLoopIteration iteration) {
    if (iteration == null) {
      return false;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (iteration.getScriptName() == null || iteration.getScriptName().isBlank()) {
      return false;
    }
    if (!(taskMetadata.get("settings") instanceof Map<?, ?>)) {
      return false;
    }
    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    String accountId =
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash")));
    return accountId != null;
  }

  private boolean hasMissingStoredSettings(PatchLoopIteration iteration) {
    if (iteration == null) {
      return false;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (!(taskMetadata.get("settings") instanceof Map<?, ?> settingsMap)) {
      return false;
    }
    if (!settingsMap.isEmpty()) {
      return false;
    }
    JsonNode importedPayload = iteration.getImportedPayloadJson();
    if (importedPayload == null || importedPayload.isNull()) {
      return false;
    }
    return isNonEmptyObject(importedPayload.path("settings"));
  }

  private String determineRerunBlockedReason(PatchLoopIteration iteration) {
    if (!hasStoredConfig(iteration)) {
      return "This run does not have a saved launch config";
    }
    if (hasMissingStoredSettings(iteration)) {
      return "This run was saved without its task settings and cannot be rerun safely";
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    String accountId =
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash")));
    if (accountId == null) {
      return "Saved run is missing the launcher account id";
    }
    String launcherMachineUuid = stringValue(accountMetadata.get("launcher_machine_uuid"));
    if (launcherMachineUuid == null) {
      return "Saved run is missing the launcher machine selection";
    }
    Optional<BuilderGoalAccountCatalogService.AccountDefinition> selectedAccount =
        accountCatalogService.findAccount(accountId);
    if (selectedAccount.isEmpty()) {
      return "Saved launcher account is no longer available";
    }
    String activeLaunchConflict =
        findActiveLaunchConflictReason(accountId, normalizeTaskKey(iteration.getScriptName()));
    if (activeLaunchConflict != null) {
      return activeLaunchConflict;
    }
    if (!selectedAccount.get().enabled()) {
      return "Saved launcher account is disabled in launcher control";
    }
    if (isBannedStatus(selectedAccount.get().status())) {
      return "Saved launcher account is banned in launcher control";
    }
    try {
      BuilderGoalLauncherControlService.LauncherMachine machine =
          resolveSelectedMachine(launcherMachineUuid);
      if (!launcherControlService.isBuilderGoalRunnableMachine(machine)) {
        return firstNonBlank(
            launcherControlService.getBuilderGoalMachineDisabledReason(machine),
            "Saved launcher machine is not available for Builder Goal local launches");
      }
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
    return null;
  }

  private String normalizeTaskKey(String taskKey) {
    return taskKey == null
        ? ""
        : taskKey
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
  }

  private PatchLoopIteration loadBuilderRun(String iterationId) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationId(iterationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Builder goal run not found: " + iterationId));
    if (!SOURCE_TYPE_BUILDER_GOAL.equals(iteration.getSourceType())) {
      throw new IllegalArgumentException("Iteration is not a builder goal run: " + iterationId);
    }
    return iteration;
  }

  private PatchLoopIteration loadBuilderRunByRecordingId(String recordingId) {
    String normalizedRecordingId = firstNonBlank(recordingId);
    if (normalizedRecordingId == null) {
      throw new IllegalArgumentException("recordingId is required");
    }
    return iterationRepository
        .findFirstBySourceTypeAndRunRecordingIdOrderByCreatedAtDesc(
            SOURCE_TYPE_BUILDER_GOAL, normalizedRecordingId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Builder goal run not found for recordingId: " + normalizedRecordingId));
  }

  private void ensureRunnableCatalogTask(JsonNode catalogTask, String taskKey) {
    if (catalogTask == null || catalogTask.isMissingNode() || catalogTask.isNull()) {
      throw new IllegalArgumentException("Task is not available in the Micro6 catalog: " + taskKey);
    }
    if (!catalogTask.path("runnable").asBoolean(true)) {
      throw new IllegalArgumentException(
          "Task is not runnable in Micro6: "
              + taskKey
              + " ("
              + catalogTask.path("disabled_reason").asText("unknown")
              + ")");
    }
  }

  private JsonNode resolveCatalogTask(String taskKey) {
    return findTaskInCatalog(taskKey, getBuilderGoalCatalogSnapshot(false).catalogPayload());
  }

  private BuilderGoalCatalogSnapshot getBuilderGoalCatalogSnapshot(boolean refresh) {
    JsonNode liveCatalog = micro6ExecutorCatalogService.getCatalog(refresh);
    if (hasNonEmptyCatalogTasks(liveCatalog)) {
      return new BuilderGoalCatalogSnapshot(
          true,
          false,
          null,
          firstNonBlank(textValue(liveCatalog.path("catalog_source")), "micro6_executor"),
          normalizeCatalogPayload(liveCatalog),
          catalogTasksAsMaps(liveCatalog));
    }

    JsonNode cachedCatalog = micro6ExecutorCatalogService.peekCatalog();
    if (hasNonEmptyCatalogTasks(cachedCatalog)) {
      return new BuilderGoalCatalogSnapshot(
          true,
          false,
          null,
          firstNonBlank(textValue(cachedCatalog.path("catalog_source")), "micro6_executor_cache"),
          normalizeCatalogPayload(cachedCatalog),
          catalogTasksAsMaps(cachedCatalog));
    }

    List<Map<String, Object>> fallbackTasks = collectSavedCatalogFallbackTasks();
    JsonNode fallbackPayload =
        objectMapper.valueToTree(
            Map.of("tasks", fallbackTasks, "catalog_source", "builder_goal_saved_config_fallback"));
    String degradedReason =
        fallbackTasks.isEmpty()
            ? "Live Micro6 catalog unavailable and no saved Builder Goal tasks were found."
            : "Live Micro6 catalog unavailable; using saved/imported task keys only.";
    return new BuilderGoalCatalogSnapshot(
        false,
        true,
        degradedReason,
        fallbackTasks.isEmpty() ? "unavailable" : "builder_goal_saved_config_fallback",
        fallbackPayload,
        fallbackTasks);
  }

  private boolean hasNonEmptyCatalogTasks(JsonNode catalog) {
    return catalog != null
        && !catalog.isNull()
        && catalog.path("tasks").isArray()
        && catalog.path("tasks").size() > 0;
  }

  private List<Map<String, Object>> catalogTasksAsMaps(JsonNode catalog) {
    if (catalog == null || catalog.isNull() || !catalog.path("tasks").isArray()) {
      return List.of();
    }
    List<Map<String, Object>> tasks = new ArrayList<>();
    catalog
        .path("tasks")
        .forEach(
            node ->
                tasks.add(
                    normalizeCatalogTaskMap(
                        objectMapper.convertValue(
                            node, new TypeReference<LinkedHashMap<String, Object>>() {}))));
    return tasks;
  }

  private JsonNode normalizeCatalogPayload(JsonNode catalog) {
    if (catalog == null || catalog.isNull() || !catalog.path("tasks").isArray()) {
      return catalog;
    }
    Map<String, Object> normalized =
        objectMapper.convertValue(catalog, new TypeReference<LinkedHashMap<String, Object>>() {});
    normalized.put("tasks", catalogTasksAsMaps(catalog));
    return objectMapper.valueToTree(normalized);
  }

  private Map<String, Object> normalizeCatalogTaskMap(Map<String, Object> task) {
    Map<String, Object> normalized = new LinkedHashMap<>(task);
    String taskKey =
        firstNonBlank(stringValue(normalized.get("task_key")), stringValue(normalized.get("key")));
    if (taskKey != null) {
      normalized.put("task_key", taskKey);
    }
    String displayName =
        firstNonBlank(
            stringValue(normalized.get("display_name")),
            stringValue(normalized.get("panel_display_name")),
            stringValue(normalized.get("name")),
            taskKey);
    if (displayName != null) {
      normalized.put("display_name", displayName);
      normalized.putIfAbsent("panel_display_name", displayName);
    }
    if (!normalized.containsKey("runnable") && normalized.containsKey("enabled")) {
      normalized.put("runnable", boolValue(normalized.get("enabled")));
    }
    if (!normalized.containsKey("disabled_reason")
        && Boolean.FALSE.equals(normalized.get("runnable"))) {
      normalized.put("disabled_reason", "Task is disabled in the Micro6 catalog");
    }
    return normalized;
  }

  private List<Map<String, Object>> collectSavedCatalogFallbackTasks() {
    Map<String, Map<String, Object>> byTaskKey = new LinkedHashMap<>();
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(this::hasStoredConfig)
        .forEach(
            iteration -> {
              Map<String, Object> taskMetadata = readTaskMetadata(iteration);
              String taskKey =
                  firstNonBlank(
                      stringValue(taskMetadata.get("task_key")), iteration.getScriptName());
              if (taskKey == null || taskKey.isBlank()) {
                return;
              }
              String normalizedTaskKey = normalizeTaskKey(taskKey);
              if (byTaskKey.containsKey(normalizedTaskKey)) {
                return;
              }
              String displayName =
                  firstNonBlank(
                      stringValue(taskMetadata.get("panel_display_name")),
                      stringValue(taskMetadata.get("display_name")),
                      iteration.getPrompt(),
                      taskKey);
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("client_type", "micro6");
              row.put("execution_mode", "queue");
              row.put("queueable", true);
              row.put("source", "saved_config");
              row.put("task_key", taskKey);
              row.put("display_name", displayName);
              row.put("panel_display_name", displayName);
              row.put("category", "saved_configs");
              row.put("runnable", true);
              row.put("supports_task_metadata", false);
              row.put("settings_unavailable_reason", "catalog_unavailable");
              row.put("placeholder", true);
              byTaskKey.put(normalizedTaskKey, row);
            });
    return new ArrayList<>(byTaskKey.values());
  }

  private String canonicalTaskKey(JsonNode catalogTask, String fallback) {
    if (catalogTask == null || catalogTask.isNull() || catalogTask.isMissingNode()) {
      return fallback;
    }
    String canonical =
        firstNonBlank(
            textValue(catalogTask.path("canonical_task_key")),
            textValue(catalogTask.path("task_key")),
            textValue(catalogTask.path("key")));
    return canonical != null ? canonical : fallback;
  }

  private JsonNode findTaskInCatalog(String taskKey, JsonNode catalog) {
    if (catalog == null || catalog.isNull()) {
      return null;
    }
    JsonNode direct = findDirectTaskInCatalog(taskKey, catalog);
    if (direct != null) {
      return direct;
    }

    String normalizedTarget = normalizeTaskKey(taskKey);
    JsonNode aliases = catalog.path("aliases");
    if (aliases.isArray()) {
      for (JsonNode alias : aliases) {
        String aliasValue =
            firstNonBlank(textValue(alias.path("normalized_alias")), textValue(alias.path("alias")));
        if (aliasValue == null || !normalizeTaskKey(aliasValue).equals(normalizedTarget)) {
          continue;
        }
        String targetKey =
            firstNonBlank(
                textValue(alias.path("task_key")),
                textValue(alias.path("canonical_task_key")),
                textValue(alias.path("target_task_key")));
        JsonNode target = findDirectTaskInCatalog(targetKey, catalog);
        if (target != null) {
          return target;
        }
      }
    }

    // Some older catalog producers only published aliases on the task row. Resolve those as a
    // compatibility fallback while still returning the canonical task row.
    JsonNode tasks = catalog.path("tasks");
    if (tasks.isArray()) {
      for (JsonNode task : tasks) {
        JsonNode searchAliases = task.path("search_aliases");
        if (!searchAliases.isArray()) {
          continue;
        }
        for (JsonNode alias : searchAliases) {
          if (normalizeTaskKey(textValue(alias)).equals(normalizedTarget)) {
            return task;
          }
        }
      }
    }
    return null;
  }

  private JsonNode findDirectTaskInCatalog(String taskKey, JsonNode catalog) {
    if (taskKey == null || taskKey.isBlank() || catalog == null || catalog.isNull()) {
      return null;
    }
    JsonNode tasks = catalog.path("tasks");
    if (!tasks.isArray()) {
      return null;
    }

    String normalizedTarget = normalizeTaskKey(taskKey);
    for (JsonNode task : tasks) {
      String candidate =
          firstNonBlank(textValue(task.path("task_key")), textValue(task.path("key")));
      if (candidate == null || candidate.isBlank()) {
        continue;
      }
      if (candidate.equals(taskKey) || normalizeTaskKey(candidate).equals(normalizedTarget)) {
        return task;
      }
    }
    return null;
  }

  private NormalizedPayload normalizeStoredIteration(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    @SuppressWarnings("unchecked")
    Map<String, Object> settings =
        taskMetadata.get("settings") instanceof Map
            ? (Map<String, Object>) taskMetadata.get("settings")
            : Map.of();
    String displayName =
        firstNonBlank(
            stringValue(taskMetadata.get("display_name")),
            iteration.getPrompt(),
            iteration.getScriptName());
    return new NormalizedPayload(
        iteration.getScriptName(),
        displayName,
        new LinkedHashMap<>(settings),
        intValue(taskMetadata.get("world")));
  }

  private Map<String, Object> readTaskMetadata(PatchLoopIteration iteration) {
    if (iteration.getTaskMetadataJson() == null || iteration.getTaskMetadataJson().isNull()) {
      return new LinkedHashMap<>();
    }
    return objectMapper.convertValue(
        iteration.getTaskMetadataJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private Map<String, Object> buildTaskMetadata(
      String iterationId,
      NormalizedPayload normalized,
      Map<String, Object> targetMetadata,
      JsonNode catalogTask,
      Map<String, Object> provenance) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("iteration_id", iterationId);
    metadata.put("task_key", normalized.taskKey());
    metadata.put("task_name", normalized.taskKey());
    metadata.put("display_name", normalized.displayName());
    metadata.put(
        "panel_display_name",
        catalogTask != null
            ? firstNonBlank(
                textValue(catalogTask.path("panel_display_name")),
                textValue(catalogTask.path("display_name")),
                normalized.displayName())
            : normalized.displayName());
    metadata.put("persist_settings", true);
    metadata.put("source", "builder_goal_runner");
    metadata.put("settings", normalized.settings());
    metadata.put("requires_settings_receipt", true);
    metadata.put("settings_sha256", CanonicalSettingsHasher.sha256(objectMapper, normalized.settings()));
    metadata.put("settings_validation", "UNVALIDATED");
    metadata.put("world", normalized.world());
    if (targetMetadata != null && !targetMetadata.isEmpty()) {
      metadata.put("micro6_target", targetMetadata);
    }
    if (provenance != null && !provenance.isEmpty()) {
      metadata.putAll(provenance);
    }
    return metadata;
  }

  private Map<String, Object> buildRecoveryPatchLoopSeed(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      Map<String, Object> workspace,
      String prompt,
      String context,
      String recordingId,
      String debugSessionId) {
    String taskKey =
        firstNonBlank(stringValue(taskMetadata.get("task_key")), iteration.getScriptName());
    String displayName =
        firstNonBlank(
            stringValue(taskMetadata.get("display_name")),
            stringValue(taskMetadata.get("panel_display_name")),
            iteration.getPrompt(),
            taskKey);
    Map<String, Object> seed = new LinkedHashMap<>();
    seed.put("source", "builder_goal_recovery_workspace");
    seed.put("iterationId", iteration.getIterationId());
    seed.put("taskKey", taskKey);
    seed.put("scriptName", taskKey);
    seed.put("displayName", displayName);
    seed.put("goalPrompt", firstNonBlank(prompt, "Investigate and fix failed builder-goal run"));
    seed.put("context", stringValueAllowBlank(context));
    seed.put("executionMode", "analysis_only");
    seed.put("mermaidTemplate", PATCH_LOOP_DEFAULT_TEMPLATE);

    Map<String, Object> sessionContext = new LinkedHashMap<>();
    if (debugSessionId != null) {
      sessionContext.put("debugSessionId", debugSessionId);
    }
    if (recordingId != null) {
      sessionContext.put("recordingId", recordingId);
    }
    sessionContext.put("resolutionSource", "builder_goal_recovery_workspace");
    seed.put("sessionContext", sessionContext);

    Map<String, Object> executionTaskMetadata = new LinkedHashMap<>();
    if (taskKey != null) {
      executionTaskMetadata.put("task_key", taskKey);
      executionTaskMetadata.put(
          "task_name", firstNonBlank(stringValue(taskMetadata.get("task_name")), taskKey));
    }
    if (displayName != null) {
      executionTaskMetadata.put("display_name", displayName);
      executionTaskMetadata.put(
          "panel_display_name",
          firstNonBlank(stringValue(taskMetadata.get("panel_display_name")), displayName));
    }
    executionTaskMetadata.put("persist_settings", true);
    executionTaskMetadata.put("requires_settings_receipt", true);
    executionTaskMetadata.put("source", "builder_goal_recovery_workspace");
    executionTaskMetadata.put("builder_goal_iteration_id", iteration.getIterationId());
    if (taskMetadata.get("settings") instanceof Map<?, ?> settingsMap) {
      executionTaskMetadata.put(
          "settings",
          new LinkedHashMap<>(
              objectMapper.convertValue(settingsMap, new TypeReference<Map<String, Object>>() {})));
    }
    Integer world = intValue(taskMetadata.get("world"));
    if (world != null) {
      executionTaskMetadata.put("world", world);
    }
    Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
    if (!targetMetadata.isEmpty()) {
      executionTaskMetadata.put("micro6_target", new LinkedHashMap<>(targetMetadata));
    }
    String preferredSessionId = stringValue(workspace.get("preferred_session_id"));
    if (preferredSessionId != null) {
      executionTaskMetadata.put("preferred_session_id", preferredSessionId);
    }
    seed.put("taskMetadata", executionTaskMetadata);

    List<String> summaryParts = new ArrayList<>();
    if (displayName != null) {
      summaryParts.add(displayName);
    }
    if (recordingId != null) {
      summaryParts.add("recording " + recordingId);
    }
    if (debugSessionId != null) {
      summaryParts.add("session " + debugSessionId);
    }
    seed.put("summary", String.join(" • ", summaryParts));
    return seed;
  }

  private CatalogValidationResult resolvePublishCatalogValidation(
      String taskKey, String targetBaseUrl, Map<String, Object> settings) {
    JsonNode targetCatalog =
        targetBaseUrl != null && !targetBaseUrl.isBlank()
            ? micro6ExecutorCatalogService.getCatalogForBaseUrl(targetBaseUrl, true)
            : null;
    if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
      return settings != null && !settings.isEmpty()
          ? CatalogValidationResult.reject(
              "SETTINGS_SCHEMA_UNAVAILABLE",
              "A canonical executor settings schema is required for non-empty settings",
              null)
          : CatalogValidationResult.skipped("SKIPPED_NO_TARGET");
    }
    JsonNode targetTask = findTaskInCatalog(taskKey, targetCatalog);
    if (targetTask != null) {
      if (!hasExplicitTrueRunnable(targetTask)) {
        return CatalogValidationResult.reject(
            "REJECTED_CACHED_MISS",
            "Task is not runnable in cached Micro6 catalog: "
                + taskKey
                + " ("
                + targetTask.path("disabled_reason").asText("unknown")
                + ")",
            targetTask);
      }
      return validatePublishSettings(
          CatalogValidationResult.validated("VALIDATED_CACHED_TARGET", targetTask), settings);
    }

    JsonNode cachedTargetCatalog =
        targetBaseUrl != null && !targetBaseUrl.isBlank()
            ? micro6ExecutorCatalogService.peekCatalogForBaseUrl(targetBaseUrl)
            : null;
    JsonNode cachedTargetTask = findTaskInCatalog(taskKey, cachedTargetCatalog);
    if (cachedTargetTask != null) {
      if (!hasExplicitTrueRunnable(cachedTargetTask)) {
        return CatalogValidationResult.reject(
            "REJECTED_CACHED_DISABLED",
            "Task is not runnable in cached Micro6 catalog: "
                + taskKey
                + " ("
                + cachedTargetTask.path("disabled_reason").asText("unknown")
                + ")",
            cachedTargetTask);
      }
      return validatePublishSettings(
          CatalogValidationResult.validated("VALIDATED_CACHED_TARGET", cachedTargetTask), settings);
    }

    boolean hadCachedCatalog =
        hasCatalogPayload(targetCatalog)
            || hasCatalogPayload(cachedTargetCatalog);
    if (hadCachedCatalog) {
      return CatalogValidationResult.reject(
          "TASK_NOT_FOUND",
          "Task is not present in the selected Micro6 catalog: " + taskKey,
          null);
    }

    return settings != null && !settings.isEmpty()
        ? CatalogValidationResult.reject(
            "SETTINGS_SCHEMA_UNAVAILABLE",
            "A canonical executor settings schema is required for non-empty settings",
            null)
        : CatalogValidationResult.skipped("SKIPPED_CATALOG_MISS");
  }

  private CatalogValidationResult validatePublishSettings(
      CatalogValidationResult result, Map<String, Object> settings) {
    if (result == null || result.rejectPublish()) {
      return result;
    }
    JsonNode task = result.catalogTask();
    if (!hasExplicitTrueRunnable(task)) {
      return CatalogValidationResult.reject(
          "CATALOG_INVALID",
          "The selected executor catalog must explicitly declare runnable=true",
          task);
    }
    if (task == null
        || !"AVAILABLE".equalsIgnoreCase(task.path("settings_schema_state").asText())
        || !task.path("args_schema").isObject()
        || textValue(task.path("settings_schema_version")) == null
        || textValue(task.path("settings_schema_hash")) == null) {
      return CatalogValidationResult.reject(
          "SETTINGS_SCHEMA_UNAVAILABLE",
          "The selected executor did not provide a usable settings schema",
          task);
    }
    JsonNode fields = task.path("args_schema").path("fields");
    if (!fields.isArray()) {
      return CatalogValidationResult.reject(
          "SETTINGS_SCHEMA_UNAVAILABLE", "The selected task settings schema is malformed", task);
    }
    Map<String, JsonNode> definitions = new LinkedHashMap<>();
    fields.forEach(field -> {
      String key = textValue(field.path("key"));
      if (key != null) {
        definitions.put(key, field);
      }
    });
    Map<String, Object> effectiveSettings = settings == null ? Map.of() : settings;
    for (Map.Entry<String, JsonNode> definitionEntry : definitions.entrySet()) {
      if (definitionEntry.getValue().path("required").asBoolean(false)
          && !definitionEntry.getValue().has("default")
          && !effectiveSettings.containsKey(definitionEntry.getKey())) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:REQUIRED",
            "Required task setting is missing: " + definitionEntry.getKey(),
            task);
      }
    }
    for (Map.Entry<String, Object> entry : effectiveSettings.entrySet()) {
      JsonNode definition = definitions.get(entry.getKey());
      if (definition == null) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:UNKNOWN_KEY", "Unknown task setting: " + entry.getKey(), task);
      }
      JsonNode value = objectMapper.valueToTree(entry.getValue());
      String type = definition.path("type").asText("").toLowerCase(Locale.ENGLISH);
      if (!Set.of("boolean", "integer", "number", "string", "text", "select", "enum", "multi_select")
          .contains(type)) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:TYPE", "Task setting has an unsupported type: " + entry.getKey(), task);
      }
      if (("integer".equals(type) && !value.isIntegralNumber())
          || ("number".equals(type) && !value.isNumber())) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:TYPE", "Task setting has an invalid type: " + entry.getKey(), task);
      }
      if (("boolean".equals(type)) && !value.isBoolean()) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:TYPE", "Task setting has an invalid type: " + entry.getKey(), task);
      }
      if (("string".equals(type)
              || "text".equals(type)
              || "select".equals(type)
              || "enum".equals(type))
          && !value.isTextual()) {
        return CatalogValidationResult.reject(
            "INVALID_SETTINGS:TYPE", "Task setting has an invalid type: " + entry.getKey(), task);
      }
      if ("multi_select".equals(type)) {
        if (!value.isArray() || !value.elements().hasNext()) {
          return CatalogValidationResult.reject(
              "INVALID_SETTINGS:TYPE",
              "Task setting must be a non-empty array: " + entry.getKey(),
              task);
        }
      } else if ("select".equals(type)
          || "enum".equals(type)
          || definition.path("options").isArray()
          || definition.path("options_by_dependency").isObject()) {
        Set<String> allowedOptions =
            schemaOptionValues(definition, effectiveSettings, definitions);
        if (allowedOptions == null
            || !value.isTextual()
            || allowedOptions.isEmpty()
            || !allowedOptions.contains(value.asText())) {
          return CatalogValidationResult.reject(
              "INVALID_SETTINGS:ENUM",
              "Task setting has an invalid enum value: " + entry.getKey(),
              task);
        }
      }
      if ("multi_select".equals(type)) {
        Set<String> allowedOptions =
            schemaOptionValues(definition, effectiveSettings, definitions);
        if (allowedOptions == null || allowedOptions.isEmpty()) {
          return CatalogValidationResult.reject(
              "INVALID_SETTINGS:ENUM",
              "Task setting has no active enum options: " + entry.getKey(),
              task);
        }
        for (JsonNode selected : value) {
          if (!selected.isTextual() || !allowedOptions.contains(selected.asText())) {
            return CatalogValidationResult.reject(
                "INVALID_SETTINGS:ENUM",
                "Task setting has an invalid enum value: " + entry.getKey(),
                task);
          }
        }
      }
      if (value.isNumber()) {
        if (definition.has("min") && value.asDouble() < definition.path("min").asDouble()) {
          return CatalogValidationResult.reject(
              "INVALID_SETTINGS:BOUNDS", "Task setting is below its minimum: " + entry.getKey(), task);
        }
        if (definition.has("max") && value.asDouble() > definition.path("max").asDouble()) {
          return CatalogValidationResult.reject(
              "INVALID_SETTINGS:BOUNDS", "Task setting exceeds its maximum: " + entry.getKey(), task);
        }
      }
    }
    return result.withSettingsValidation("VALID");
  }

  private Set<String> schemaOptionValues(
      JsonNode definition,
      Map<String, Object> effectiveSettings,
      Map<String, JsonNode> definitions) {
    Set<String> values = new LinkedHashSet<>();
    JsonNode options = definition.path("options");
    JsonNode byDependency = definition.path("options_by_dependency");
    if (byDependency.isObject()) {
      String dependencyKey = textValue(definition.path("depends_on"));
      if (dependencyKey == null) {
        return null;
      }
      Object dependencyValue = effectiveSettings.get(dependencyKey);
      if (dependencyValue == null) {
        JsonNode dependencyDefinition = definitions.get(dependencyKey);
        if (dependencyDefinition != null && dependencyDefinition.has("default")) {
          dependencyValue = objectMapper.convertValue(dependencyDefinition.get("default"), Object.class);
        }
      }
      if (dependencyValue == null) {
        return null;
      }
      JsonNode activeBranch = byDependency.get(String.valueOf(dependencyValue));
      if (!activeBranchIsArray(activeBranch)) {
        return null;
      }
      activeBranch.forEach(
          option -> {
            if (option.isTextual()) {
              values.add(option.asText());
            }
          });
      // options_by_dependency is authoritative; the flattened legacy options field must not
      // widen the branch selected by the dependent setting.
      return values;
    }
    if (options.isArray()) {
      options.forEach(option -> {
        if (option.isTextual()) {
          values.add(option.asText());
        }
      });
    }
    return values;
  }

  private boolean activeBranchIsArray(JsonNode branch) {
    return branch != null && branch.isArray();
  }

  private boolean hasExplicitTrueRunnable(JsonNode task) {
    return task != null && task.path("runnable").isBoolean() && task.path("runnable").asBoolean();
  }

  private boolean hasCatalogPayload(JsonNode catalog) {
    return hasNonEmptyCatalogTasks(catalog);
  }

  private void applyCatalogValidationMetadata(
      PatchLoopIteration iteration, CatalogValidationResult catalogValidation) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("catalog_validation", catalogValidation.status());
    taskMetadata.put("catalog_validation_skipped", catalogValidation.skipped());
    if (catalogValidation.errorMessage() != null) {
      taskMetadata.put("catalog_validation_error", catalogValidation.errorMessage());
    } else {
      taskMetadata.remove("catalog_validation_error");
    }
    JsonNode catalogTask = catalogValidation.catalogTask();
    if (catalogTask != null && catalogTask.isObject()) {
      taskMetadata.put("settings_schema_version", textValue(catalogTask.path("settings_schema_version")));
      taskMetadata.put("settings_schema_hash", textValue(catalogTask.path("settings_schema_hash")));
    }
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private Map<String, Object> normalizeSettings(Map<String, Object> settings) {
    if (settings == null) {
      return new LinkedHashMap<>();
    }
    return new LinkedHashMap<>(settings);
  }

  private void rejectPublicTargetOverride(JsonNode payload) {
    if (containsNonBlankTargetOverride(payload)) {
      throw new IllegalArgumentException("TARGET_OVERRIDE_NOT_ALLOWED");
    }
  }

  private boolean containsNonBlankTargetOverride(JsonNode node) {
    if (node == null || node.isNull()) {
      return false;
    }
    if (node.isObject()) {
      java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        java.util.Map.Entry<String, JsonNode> field = fields.next();
        String name = field.getKey();
        JsonNode value = field.getValue();
        if (("targetBaseUrl".equals(name) || "target_base_url".equals(name))
            && value != null
            && !value.isNull()
            && (!value.isTextual() || !value.asText("").isBlank())) {
          return true;
        }
        if (containsNonBlankTargetOverride(value)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (containsNonBlankTargetOverride(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private NormalizedPayload normalizePayload(JsonNode payload) {
    if (payload == null || payload.isNull() || !payload.isObject()) {
      throw new IllegalArgumentException("Builder payload must be a JSON object");
    }

    JsonNode builderExecution = payload.path("builder_execution");
    JsonNode queuePreview = payload.path("queue_payload_preview");
    String taskKey =
        firstNonBlank(
            textValue(builderExecution.path("task_key")),
            textValue(queuePreview.path("task_key")),
            textValue(payload.path("task_key")));
    if (taskKey == null) {
      throw new IllegalArgumentException("Builder payload is missing task_key");
    }

    JsonNode settingsNode = resolveAuthoritativeSettings(payload, builderExecution, queuePreview);

    Map<String, Object> settings =
        normalizeSettings(
            objectMapper.convertValue(
                settingsNode, new TypeReference<LinkedHashMap<String, Object>>() {}));

    String displayName =
        firstNonBlank(
            textValue(payload.path("node_context").path("title")),
            textValue(queuePreview.path("title")),
            textValue(queuePreview.path("name")),
            textValue(payload.path("title")),
            taskKey);
    Integer world = intValue(payload.path("world"));
    if (world == null) {
      world = intValue(payload.path("queue_payload_preview").path("world"));
    }

    return new NormalizedPayload(taskKey, displayName, settings, world);
  }

  private void resolveRunSession(PatchLoopIteration iteration) {
    Optional<PatchLoopSessionResolverService.ResolvedSessionContext> resolved =
        sessionResolverService.resolveForIteration(iteration);
    resolved.ifPresent(
        session -> {
          sessionResolverService.applyResolvedSession(iteration, session);
          persistenceService.saveIteration(iteration);
        });
  }

  private void tryCollectEvidence(PatchLoopIteration iteration) {
    try {
      AnalysisV1EvidenceBundle evidence = evidenceService.collectEvidence(iteration, null);
      persistenceService.saveEvidenceCounts(iteration, evidence);
    } catch (Exception ignored) {
      // Evidence collection should not flip the run result after execution completed.
    }
  }

  private AnalysisV2Request buildAnalysisRequest(
      PatchLoopIteration iteration,
      AnalysisV1EvidenceBundle evidence,
      String prompt,
      String context) {
    AnalysisV2Request request = new AnalysisV2Request();
    request.setPromptType(PromptType.ISSUE);

    AnalysisV2Target target = new AnalysisV2Target();
    target.setScriptName(iteration.getScriptName());
    target.setRunId(iteration.getIterationId());
    if (evidence.getTimeWindow() != null) {
      AnalysisV2Target.SessionBounds bounds = new AnalysisV2Target.SessionBounds();
      bounds.setStart(evidence.getTimeWindow().getStart());
      bounds.setEnd(evidence.getTimeWindow().getEnd());
      target.setSessionBounds(bounds);
    }
    request.setTarget(target);
    request.setPrompt(prompt);
    request.setContext(context);

    List<AnalysisV2EvidenceSelector> selectors = new ArrayList<>();
    if (!evidence.getLogs().isEmpty()) {
      AnalysisV2EvidenceSelector logs = new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
      logs.setMaxCount(evidence.getLogs().size());
      logs.setTimeWindow(evidence.getTimeWindow());
      selectors.add(logs);
    }
    if (!evidence.getTickLogs().isEmpty() && evidence.getTickWindow() != null) {
      AnalysisV2EvidenceSelector tickLogs = new AnalysisV2EvidenceSelector(EvidenceType.TICK_LOGS);
      tickLogs.setMaxCount(evidence.getTickLogs().size());
      tickLogs.setTickWindow(evidence.getTickWindow());
      selectors.add(tickLogs);
    }
    if (!evidence.getDecisions().isEmpty()) {
      AnalysisV2EvidenceSelector decisions = new AnalysisV2EvidenceSelector(EvidenceType.DECISIONS);
      decisions.setMaxCount(evidence.getDecisions().size());
      decisions.setTimeWindow(evidence.getTimeWindow());
      selectors.add(decisions);
    }
    request.setEvidenceSelectors(selectors);
    request.setEvidenceBundle(evidence);

    return request;
  }

  private AnalysisPromptData buildDefaultAnalysisPromptData(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String displayName = stringValue(taskMetadata.get("display_name"));
    String taskKey = stringValue(taskMetadata.get("task_key"));
    String prompt =
        "Create a Micro6 patch plan for the failed builder goal task '"
            + firstNonBlank(displayName, taskKey, iteration.getScriptName())
            + "'. Use my selected evidence and observations only.";
    String context =
        "Patch target: "
            + firstNonBlank(iteration.getPatchTarget(), PATCH_TARGET_MICRO6_TASK_SOURCE)
            + ". Apply fixes against the Micro6 task source for task_key="
            + firstNonBlank(taskKey, iteration.getScriptName())
            + ". Failure reason: "
            + firstNonBlank(iteration.getFailureReason(), "unknown")
            + ". Wait for explicit approval before any patch application or relaunch.";

    Optional<com.chromascape.web.debug.patchloop.dto.SessionContextDto> sessionContext =
        sessionResolverService.readSessionContext(iteration);
    if (sessionContext.isPresent()) {
      context =
          context + " Debug session context: " + objectMapper.valueToTree(sessionContext.get());
    }
    return new AnalysisPromptData(prompt, context);
  }

  private void updateRecoveryState(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      String phase,
      String message) {
    Map<String, Object> recovery = new LinkedHashMap<>();
    recovery.put("phase", phase);
    recovery.put("message", message);
    recovery.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery", recovery);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private void updatePreparationState(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      String state,
      String message) {
    Map<String, Object> preparation = readTargetPreparationMetadata(taskMetadata);
    Map<String, Object> updated = new LinkedHashMap<>(preparation);
    updated.put("state", state);
    updated.put("message", message);
    updated.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("target_preparation", updated);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private void mergeTargetPreparationMetadata(
      Map<String, Object> taskMetadata, Map<String, Object> updates) {
    Map<String, Object> existing = readTargetPreparationMetadata(taskMetadata);
    Map<String, Object> merged = new LinkedHashMap<>(existing);
    if (updates != null) {
      merged.putAll(updates);
    }
    merged.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("target_preparation", merged);
  }

  private boolean shouldMoveToAwaitingOperatorPrompt(PatchLoopIteration iteration) {
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return false;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    if (determineAnalyzeBlockedReason(iteration, publishStatus) != null) {
      return false;
    }
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    String phase = stringValue(recoveryMetadata.get("phase"));
    if (RECOVERY_AWAITING_OPERATOR_PROMPT.equals(phase)
        || RECOVERY_PATCH_PLAN_READY.equals(phase)
        || RECOVERY_RELAUNCHED.equals(phase)
        || RECOVERY_APPLYING_PATCH.equals(phase)
        || RECOVERY_RESTARTING_TARGET.equals(phase)
        || RECOVERY_RELOGGING_IN.equals(phase)
        || RECOVERY_WAITING_FOR_EXECUTOR.equals(phase)
        || RECOVERY_REPUBLISHING.equals(phase)) {
      return false;
    }
    return iteration.getPatchId() == null || iteration.getPatchId().isBlank();
  }

  private void moveToAwaitingOperatorPrompt(PatchLoopIteration iteration, String message) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    updateRecoveryState(iteration, taskMetadata, RECOVERY_AWAITING_OPERATOR_PROMPT, message);
  }

  private TimeRangeSelection resolveTimeRangeSelection(
      AnalysisV1EvidenceBundle evidence, AnalysisInputMetadata existingInput) {
    return resolveTimeRangeSelection(evidence, null, existingInput);
  }

  private TimeRangeSelection resolveTimeRangeSelection(
      AnalysisV1EvidenceBundle evidence, JsonNode payload, AnalysisInputMetadata existingInput) {
    int maxSeconds = computeMaxRangeSeconds(evidence);
    Integer requestedStart =
        intValue(payload != null ? payload.get("timeRangeStartSeconds") : null);
    Integer requestedEnd = intValue(payload != null ? payload.get("timeRangeEndSeconds") : null);
    int startSeconds =
        clampRangeValue(
            requestedStart != null ? requestedStart : existingInput.timeRangeStartSeconds(),
            0,
            maxSeconds);
    int endSeconds =
        clampRangeValue(
            requestedEnd != null ? requestedEnd : existingInput.timeRangeEndSeconds(),
            maxSeconds,
            maxSeconds);
    if (endSeconds <= startSeconds) {
      endSeconds = maxSeconds;
      startSeconds = 0;
    }
    return new TimeRangeSelection(startSeconds, endSeconds, maxSeconds);
  }

  private int computeMaxRangeSeconds(AnalysisV1EvidenceBundle evidence) {
    if (evidence == null
        || evidence.getTimeWindow() == null
        || !evidence.getTimeWindow().isValid()) {
      return 0;
    }
    long seconds =
        ChronoUnit.SECONDS.between(
            evidence.getTimeWindow().getStart(), evidence.getTimeWindow().getEnd());
    return (int) Math.max(0L, seconds);
  }

  private int clampRangeValue(Integer requested, int defaultValue, int maxValue) {
    int value = requested != null ? requested : defaultValue;
    if (value < 0) {
      return 0;
    }
    if (maxValue >= 0 && value > maxValue) {
      return maxValue;
    }
    return value;
  }

  private AnalysisV1EvidenceBundle clipEvidenceToTimeRange(
      AnalysisV1EvidenceBundle evidence, TimeRangeSelection selection) {
    if (evidence == null
        || selection == null
        || selection.maxSeconds() <= 0
        || evidence.getTimeWindow() == null
        || !evidence.getTimeWindow().isValid()) {
      return evidence;
    }
    LocalDateTime absoluteStart =
        evidence.getTimeWindow().getStart().plusSeconds(selection.startSeconds());
    LocalDateTime absoluteEnd =
        evidence.getTimeWindow().getStart().plusSeconds(selection.endSeconds());

    AnalysisV1EvidenceBundle clipped = new AnalysisV1EvidenceBundle();
    clipped.setScriptName(evidence.getScriptName());
    clipped.setTimeWindow(
        new com.chromascape.web.debug.patchloop.dto.TimeWindow(absoluteStart, absoluteEnd));
    clipped.setLogs(
        evidence.getLogs().stream()
            .filter(log -> withinRange(log.getTimestamp(), absoluteStart, absoluteEnd))
            .toList());
    clipped.setTickLogs(
        evidence.getTickLogs().stream()
            .filter(tick -> withinRange(tick.getTimestamp(), absoluteStart, absoluteEnd))
            .toList());
    clipped.setDecisions(
        evidence.getDecisions().stream()
            .filter(decision -> withinRange(decision.getTimestamp(), absoluteStart, absoluteEnd))
            .toList());
    if (!clipped.getTickLogs().isEmpty()) {
      Long minTick =
          clipped.getTickLogs().stream()
              .map(AnalysisV1EvidenceBundle.TickLogEvidence::getTick)
              .filter(Objects::nonNull)
              .min(Long::compareTo)
              .orElse(null);
      Long maxTick =
          clipped.getTickLogs().stream()
              .map(AnalysisV1EvidenceBundle.TickLogEvidence::getTick)
              .filter(Objects::nonNull)
              .max(Long::compareTo)
              .orElse(null);
      if (minTick != null && maxTick != null) {
        clipped.setTickWindow(
            new com.chromascape.web.debug.patchloop.dto.TickWindow(minTick, maxTick));
      }
    }
    return clipped;
  }

  private boolean withinRange(LocalDateTime timestamp, LocalDateTime start, LocalDateTime end) {
    if (timestamp == null || start == null || end == null) {
      return false;
    }
    return !timestamp.isBefore(start) && !timestamp.isAfter(end);
  }

  private String formatDurationSeconds(int totalSeconds) {
    int minutes = Math.max(0, totalSeconds) / 60;
    int seconds = Math.max(0, totalSeconds) % 60;
    return String.format("%02d:%02d", minutes, seconds);
  }

  private boolean putIfChanged(Map<String, Object> target, String key, Object value) {
    Object existing = target.get(key);
    if (Objects.equals(existing, value)) {
      return false;
    }
    target.put(key, value);
    return true;
  }

  private List<JsonNode> readArgsFields(JsonNode catalogTask) {
    JsonNode fields = catalogTask.path("args_schema").path("fields");
    if (!fields.isArray()) {
      return List.of();
    }
    List<JsonNode> result = new ArrayList<>();
    fields.forEach(result::add);
    return result;
  }

  private Map<String, Object> toRunSummary(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
    Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
    Map<String, Object> targetPreparationMetadata = readTargetPreparationMetadata(taskMetadata);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    AnalysisInputMetadata analysisInputMetadata = readAnalysisInputMetadata(taskMetadata);
    String publishStatus = stringValue(publishMetadata.get("status"));
    boolean taskStartedObserved = Boolean.TRUE.equals(taskMetadata.get("task_start_confirmed"));
    boolean taskStartConfirmed = taskStartedObserved && iteration.getRunSessionId() != null;
    String displayStatus = deriveDisplayStatus(iteration, publishStatus);
    boolean cancelled =
        iteration.getStatus() == PatchLoopStatus.CANCELLED
            || "CANCELLED".equalsIgnoreCase(publishStatus);
    boolean stopRequested = isStopRequested(taskMetadata, publishMetadata);
    boolean stopping =
        stopRequested && iteration.getStatus() != null && !iteration.getStatus().isTerminal();
    String stopBlockedReason = determineStopBlockedReason(iteration, publishStatus);
    boolean canStop = stopBlockedReason == null;
    boolean analysisAvailable =
        iteration.getAnalysisResponseJson() != null
            && !iteration.getAnalysisResponseJson().isNull();
    boolean patchDraftAvailable =
        iteration.getPatchId() != null && !iteration.getPatchId().isBlank();
    boolean approvalRequired =
        iteration.getStatus() == PatchLoopStatus.FAILED
            && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus);
    boolean approvalAvailable =
        !cancelled
            && approvalRequired
            && analysisAvailable
            && patchDraftAvailable
            && RECOVERY_PATCH_PLAN_READY.equals(stringValue(recoveryMetadata.get("phase")));
    boolean canAnalyze =
        !cancelled
            && iteration.getStatus() == PatchLoopStatus.FAILED
            && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus);
    boolean canFixAndRelaunch = approvalAvailable;
    boolean configAvailable = hasStoredConfig(iteration);
    String rerunBlockedReason = determineRerunBlockedReason(iteration);
    boolean canRerun = rerunBlockedReason == null;
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iteration.getIterationId());
    response.put("status", displayStatus);
    // Keep the camel-case alias used by older debugger/MCP clients while status remains the
    // canonical wire field.
    response.put("displayStatus", displayStatus);
    response.put(
        "iterationStatus", iteration.getStatus() != null ? iteration.getStatus().name() : null);
    response.put("taskKey", iteration.getScriptName());
    response.put(
        "displayName",
        firstNonBlank(
            stringValue(taskMetadata.get("display_name")),
            iteration.getPrompt(),
            iteration.getScriptName()));
    response.put("sourceType", iteration.getSourceType());
    response.put("patchTarget", iteration.getPatchTarget());
    response.put("parentIterationId", iteration.getParentIterationId());
    response.put("micro6RevisionId", iteration.getMicro6RevisionId());
    response.put("micro6PatchedAt", iteration.getMicro6PatchedAt());
    response.put("createdAt", iteration.getCreatedAt());
    response.put("completedAt", iteration.getCompletedAt());
    response.put("runStartTime", iteration.getRunStartTime());
    response.put("runEndTime", iteration.getRunEndTime());
    response.put("runSessionId", iteration.getRunSessionId());
    response.put("runRecordingId", iteration.getRunRecordingId());
    response.put("taskStartedObserved", taskStartedObserved);
    response.put("taskStartConfirmed", taskStartConfirmed);
    response.put("taskStartedAt", stringValue(taskMetadata.get("task_started_at")));
    response.put("runSuccess", iteration.getRunSuccess());
    response.put("failureCode", iteration.getFailureCode());
    response.put("failureReason", iteration.getFailureReason());
    response.put("publishStatus", publishStatus);
    response.put("publishActivationState", stringValue(publishMetadata.get("activation_state")));
    response.put("controlRequested", Boolean.TRUE.equals(publishMetadata.get("control_requested")));
    response.put("controlAction", stringValue(publishMetadata.get("control_action")));
    response.put("controlRequestedAt", stringValue(publishMetadata.get("control_requested_at")));
    response.put("settingsSha256", stringValue(taskMetadata.get("settings_sha256")));
    response.put("settingsValidation", stringValue(taskMetadata.get("settings_validation")));
    response.put("settingsReceipt", publishMetadata.get("settings_receipt"));
    response.put("publishEntryId", intValue(publishMetadata.get("entry_id")));
    response.put(
        "publishAttachedToExisting",
        Boolean.TRUE.equals(publishMetadata.get("attached_to_existing")));
    response.put("publishedAt", stringValue(publishMetadata.get("published_at")));
    response.put("publishError", stringValue(publishMetadata.get("last_error")));
    response.put("publishReadinessPhase", stringValue(publishMetadata.get("readiness_phase")));
    response.put(
        "publishReadinessReason", stringValueAllowBlank(publishMetadata.get("readiness_reason")));
    response.put("publishDispatchHold", boolValue(publishMetadata.get("dispatch_hold")));
    response.put(
        "publishDispatchHoldReason",
        stringValueAllowBlank(publishMetadata.get("dispatch_hold_reason")));
    response.put("publishStartupState", stringValue(publishMetadata.get("startup_state")));
    response.put("publishLoginState", stringValue(publishMetadata.get("login_state")));
    response.put(
        "publishLoginTerminalReason",
        stringValueAllowBlank(publishMetadata.get("login_terminal_reason")));
    response.put("publishLaunchLogFile", stringValue(publishMetadata.get("launch_log_file")));
    response.put("stopSendError", stringValue(publishMetadata.get("stop_send_error")));
    response.put("analysisId", iteration.getAnalysisId());
    response.put("analysisAvailable", analysisAvailable);
    response.put("patchId", iteration.getPatchId());
    response.put("patchDraftAvailable", patchDraftAvailable);
    response.put("diagnosisSummary", iteration.getDiagnosisSummary());
    response.put("rootCause", iteration.getRootCause());
    response.put("confidence", iteration.getConfidence());
    response.put("patchDraftSummary", iteration.getPatchDraftSummary());
    response.put("retestPlanSummary", iteration.getRetestPlanSummary());
    response.put("resultType", iteration.getResultType());
    response.put("evidenceLogCount", iteration.getEvidenceLogCount());
    response.put("evidenceTickLogCount", iteration.getEvidenceTickLogCount());
    response.put("evidenceDecisionCount", iteration.getEvidenceDecisionCount());
    response.put("accountId", stringValue(accountMetadata.get("account_id")));
    response.put("accountHash", stringValue(accountMetadata.get("account_hash")));
    response.put("launcherAccountHash", stringValue(accountMetadata.get("account_hash")));
    response.put(
        "accountName",
        firstNonBlank(
            stringValue(accountMetadata.get("account_name")),
            stringValue(targetMetadata.get("account_name"))));
    response.put("accountSource", stringValue(accountMetadata.get("source")));
    response.put("accountOnline", Boolean.TRUE.equals(accountMetadata.get("online")));
    response.put("launcherOnline", Boolean.TRUE.equals(accountMetadata.get("online")));
    response.put("accountRunnable", Boolean.TRUE.equals(accountMetadata.get("runnable")));
    response.put(
        "accountDisabledReason", stringValueAllowBlank(accountMetadata.get("disabled_reason")));
    response.put(
        "accountExecutorOnline", Boolean.TRUE.equals(accountMetadata.get("executorOnline")));
    response.put("accountHasEmail", Boolean.TRUE.equals(accountMetadata.get("hasEmail")));
    response.put("accountHasPassword", Boolean.TRUE.equals(accountMetadata.get("hasPassword")));
    response.put("accountLaunchReady", Boolean.TRUE.equals(accountMetadata.get("launchReady")));
    response.put(
        "accountLaunchDisabledReason",
        stringValueAllowBlank(accountMetadata.get("launchDisabledReason")));
    response.put("configAvailable", configAvailable);
    response.put("canRerun", canRerun);
    response.put("rerunBlockedReason", rerunBlockedReason);
    response.put("targetClientId", stringValue(targetMetadata.get("client_id")));
    response.put("targetAccountName", stringValue(targetMetadata.get("account_name")));
    response.put("targetBaseUrl", stringValue(targetMetadata.get("advertised_base_url")));
    response.put("targetStatus", stringValue(targetMetadata.get("status")));
    response.put(
        "micro6ResolvedAccountName",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("resolved_account_name")),
            stringValue(targetMetadata.get("resolved_account_name")),
            stringValue(targetMetadata.get("account_name"))));
    response.put(
        "micro6ResolvedAccountHash",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("resolved_account_hash")),
            stringValue(targetMetadata.get("resolved_account_hash")),
            stringValue(targetMetadata.get("account_hash"))));
    response.put(
        "micro6RequestedAccountName",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("requested_account_name")),
            stringValue(targetMetadata.get("requested_account_name"))));
    response.put(
        "micro6RequestedAccountHash",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("requested_account_hash")),
            stringValue(targetMetadata.get("requested_account_hash"))));
    response.put(
        "agentServerBaseUrl", stringValue(targetPreparationMetadata.get("agent_server_base_url")));
    response.put(
        "launchProfileName",
        firstNonBlank(
            stringValue(accountMetadata.get("launch_profile_name")),
            stringValue(targetMetadata.get("launch_profile_name"))));
    response.put(
        "accountHashScheme",
        firstNonBlank(
            stringValue(accountMetadata.get("account_hash_scheme")),
            stringValue(targetPreparationMetadata.get("account_hash_scheme")),
            stringValue(targetMetadata.get("account_hash_scheme"))));
    response.put(
        "launchArgsPreview", stringValue(targetPreparationMetadata.get("launch_args_preview")));
    response.put(
        "launchArgsConfigured", targetPreparationMetadata.get("launch_args_preview") != null);
    response.put(
        "effectiveGradleTask", stringValue(targetPreparationMetadata.get("effective_gradle_task")));
    response.put(
        "localExecutorBaseUrl",
        stringValue(targetPreparationMetadata.get("local_executor_base_url")));
    response.put(
        "targetReadinessPhase", stringValue(targetPreparationMetadata.get("readiness_phase")));
    response.put(
        "targetReadinessReason",
        stringValueAllowBlank(targetPreparationMetadata.get("readiness_reason")));
    response.put("world", intValue(taskMetadata.get("world")));
    response.put(
        "launcherMachineUuid",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("launcher_machine_uuid")),
            stringValue(accountMetadata.get("launcher_machine_uuid"))));
    response.put(
        "launcherMachineName",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("launcher_machine_name")),
            stringValue(accountMetadata.get("launcher_machine_name"))));
    response.put(
        "launcherBaseUrl",
        firstNonBlank(
            stringValue(targetPreparationMetadata.get("launcher_base_url")),
            stringValue(accountMetadata.get("launcher_base_url"))));
    response.put(
        "launcherCommandId", stringValue(targetPreparationMetadata.get("launcher_command_id")));
    response.put(
        "launcherCommandStatus",
        stringValue(targetPreparationMetadata.get("launcher_command_status")));
    response.put("launchMode", stringValue(targetPreparationMetadata.get("launch_mode")));
    response.put("attemptId", stringValue(targetPreparationMetadata.get("attempt_id")));
    response.put("launcherPid", longValue(targetPreparationMetadata.get("launcher_pid")));
    response.put("launchLogFile", stringValue(targetPreparationMetadata.get("launcher_log_file")));
    response.put("portOwnerPid", longValue(targetPreparationMetadata.get("port_owner_pid")));
    response.put("dispatchHold", boolValue(targetPreparationMetadata.get("dispatch_hold")));
    response.put(
        "dispatchHoldReason", stringValue(targetPreparationMetadata.get("dispatch_hold_reason")));
    response.put("startupState", stringValue(targetPreparationMetadata.get("startup_state")));
    response.put("loginState", stringValue(targetPreparationMetadata.get("login_state")));
    response.put("loginAttempt", intValue(targetPreparationMetadata.get("login_attempt")));
    response.put("loginAttemptMax", intValue(targetPreparationMetadata.get("login_attempt_max")));
    response.put("requestedWorld", intValue(targetPreparationMetadata.get("requested_world")));
    response.put("currentWorld", intValue(targetPreparationMetadata.get("current_world")));
    response.put("worldMatched", boolValue(targetPreparationMetadata.get("world_matched")));
    response.put("pathfinderReady", boolValue(targetPreparationMetadata.get("pathfinder_ready")));
    response.put(
        "scriptLifecycleState",
        stringValue(targetPreparationMetadata.get("script_lifecycle_state")));
    response.put("scriptLoopAlive", boolValue(targetPreparationMetadata.get("script_loop_alive")));
    response.put(
        "loginTerminalReason",
        stringValueAllowBlank(targetPreparationMetadata.get("login_terminal_reason")));
    response.put("reusedExecutor", boolValue(targetPreparationMetadata.get("reused_executor")));
    response.put(
        "launchFailureKind", stringValue(targetPreparationMetadata.get("launch_failure_kind")));
    response.put(
        "launchFailureDetail",
        stringValueAllowBlank(targetPreparationMetadata.get("launch_failure_detail")));
    response.put(
        "lastObservedReadinessPhase",
        stringValue(targetPreparationMetadata.get("last_observed_readiness_phase")));
    boolean localExecutorObserved =
        response.get("portOwnerPid") != null
            || response.get("micro6ResolvedAccountName") != null
            || response.get("targetClientId") != null;
    boolean localExecutorReady =
        PREPARATION_STATE_READY_TO_PUBLISH.equalsIgnoreCase(
                stringValue(targetPreparationMetadata.get("state")))
            || publishStatus != null
            || Boolean.FALSE.equals(boolValue(targetPreparationMetadata.get("dispatch_hold")))
                && response.get("micro6ResolvedAccountName") != null;
    response.put("localExecutorObserved", localExecutorObserved);
    response.put("localExecutorReady", localExecutorReady);
    response.put("targetPreparationState", stringValue(targetPreparationMetadata.get("state")));
    response.put(
        "targetPreparationMessage",
        stringValueAllowBlank(targetPreparationMetadata.get("message")));
    response.put("analysisCustomized", analysisInputMetadata.customized());
    response.put("analysisMode", analysisInputMetadata.mode());
    response.put("analysisSelectedEvidenceCount", analysisInputMetadata.selectedEvidenceCount());
    response.put("analysisTotalEvidenceCount", analysisInputMetadata.totalEvidenceCount());
    response.put("analysisTimeRangeStartSeconds", analysisInputMetadata.timeRangeStartSeconds());
    response.put("analysisTimeRangeEndSeconds", analysisInputMetadata.timeRangeEndSeconds());
    response.put("recoveryPhase", stringValue(recoveryMetadata.get("phase")));
    response.put("recoveryMessage", stringValueAllowBlank(recoveryMetadata.get("message")));
    response.put("recoveryUpdatedAt", stringValue(recoveryMetadata.get("updated_at")));
    response.put(
        "lastSuccessfulPollAt", stringValue(publishMetadata.get("last_successful_poll_at")));
    response.put("lastPollAt", stringValue(publishMetadata.get("last_poll_at")));
    response.put(
        "consecutivePollFailures", intValue(publishMetadata.get("consecutive_poll_failures")));
    response.put("approvalRequired", approvalRequired);
    response.put("approvalAvailable", approvalAvailable);
    response.put(
        "approvalBlockedReason",
        determineApprovalBlockedReason(iteration, publishStatus, recoveryMetadata));
    response.put("canAnalyze", canAnalyze);
    response.put("canFixAndRelaunch", canFixAndRelaunch);
    response.put("analyzeBlockedReason", determineAnalyzeBlockedReason(iteration, publishStatus));
    response.put(
        "fixBlockedReason",
        determineApprovalBlockedReason(iteration, publishStatus, recoveryMetadata));
    response.put("canStop", canStop);
    response.put("stopBlockedReason", stopBlockedReason);
    response.put("stopRequestedAt", stringValue(taskMetadata.get("stop_requested_at")));
    response.put("stopReason", stringValue(taskMetadata.get("stop_reason")));
    response.put("stopping", stopping);
    response.put("cancelled", cancelled);
    return response;
  }

  private String determineAnalyzeBlockedReason(PatchLoopIteration iteration, String publishStatus) {
    if (iteration.getStatus() == PatchLoopStatus.CANCELLED
        || "CANCELLED".equalsIgnoreCase(publishStatus)) {
      return "Run was cancelled";
    }
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return "Run must fail before it can be analyzed";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "Publish failed; there is no task run to analyze";
    }
    return null;
  }

  private LocalDateTime resolveLogWindowStart(PatchLoopIteration iteration) {
    LocalDateTime base =
        firstNonNull(iteration.getRunStartTime(), iteration.getCreatedAt(), LocalDateTime.now());
    return base.minusSeconds(30);
  }

  private LocalDateTime resolveLogWindowEnd(PatchLoopIteration iteration) {
    LocalDateTime base =
        firstNonNull(iteration.getRunEndTime(), iteration.getCompletedAt(), LocalDateTime.now());
    return base.plusSeconds(30);
  }

  private Map<String, Object> buildRunLogFilter(
      PatchLoopIteration iteration,
      Map<String, Object> run,
      LocalDateTime windowStart,
      LocalDateTime windowEnd,
      boolean fallbackToWindow) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("iterationId", iteration.getIterationId());
    filter.put("taskKey", iteration.getScriptName());
    filter.put("accountName", stringValue(run.get("accountName")));
    filter.put("sessionId", stringValue(run.get("runSessionId")));
    filter.put("recordingId", stringValue(run.get("runRecordingId")));
    filter.put("windowStart", windowStart.atOffset(ZoneOffset.UTC).toString());
    filter.put("windowEnd", windowEnd.atOffset(ZoneOffset.UTC).toString());
    filter.put("matchMode", fallbackToWindow ? "window_fallback" : "run_filtered");
    return filter;
  }

  private List<Map<String, Object>> buildMatchedRunLogs(
      PatchLoopIteration iteration, Map<String, Object> run, List<LogEntity> logs) {
    String iterationId = iteration.getIterationId();
    String accountName = stringValue(run.get("accountName"));
    String sessionId = stringValue(run.get("runSessionId"));
    String recordingId = stringValue(run.get("runRecordingId"));
    String taskKey = iteration.getScriptName();
    return logs.stream()
        .filter(
            log -> matchesRunLog(log, iterationId, accountName, sessionId, recordingId, taskKey))
        .map(log -> buildLogView(log, true))
        .toList();
  }

  private List<Map<String, Object>> buildWindowLogViews(List<LogEntity> logs) {
    return logs.stream().map(log -> buildLogView(log, false)).toList();
  }

  private boolean matchesRunLog(
      LogEntity log,
      String iterationId,
      String accountName,
      String sessionId,
      String recordingId,
      String taskKey) {
    if (equalsIgnoreCase(log.getScriptName(), taskKey)) {
      return true;
    }
    String haystack =
        String.join(
            "\n",
            safeString(log.getSource()),
            safeString(log.getMessage()),
            safeString(log.getStructuredData()),
            safeString(log.getScriptName()));
    return containsIgnoreCase(haystack, iterationId)
        || containsIgnoreCase(haystack, accountName)
        || containsIgnoreCase(haystack, sessionId)
        || containsIgnoreCase(haystack, recordingId)
        || containsIgnoreCase(haystack, taskKey);
  }

  private Map<String, Object> buildLogView(LogEntity log, boolean matched) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put(
        "timestamp",
        log.getTimestamp() != null ? log.getTimestamp().atOffset(ZoneOffset.UTC).toString() : null);
    view.put("level", log.getLevel());
    view.put("source", log.getSource());
    view.put("scriptName", log.getScriptName());
    view.put("message", log.getMessage());
    view.put("logType", log.getLogType());
    view.put("tick", log.getTick());
    view.put("structuredData", log.getStructuredData());
    view.put("matched", matched);
    return view;
  }

  private Map<String, Object> readLaunchLogTail(Map<String, Object> run) {
    String launchLogFile = stringValue(run.get("launchLogFile"));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("path", launchLogFile);
    if (launchLogFile == null || launchLogFile.isBlank()) {
      response.put("available", false);
      response.put("message", "This run does not have a saved launch log file.");
      response.put("lines", List.of());
      return response;
    }
    Path logPath = Path.of(launchLogFile);
    if (!Files.exists(logPath)) {
      response.put("available", false);
      response.put("message", "Launch log file is missing on disk.");
      response.put("lines", List.of());
      return response;
    }
    try {
      response.put("available", true);
      response.put("message", null);
      response.put("lines", tailFile(logPath, 200));
      return response;
    } catch (IOException e) {
      response.put("available", false);
      response.put("message", "Failed to read launch log file: " + e.getMessage());
      response.put("lines", List.of());
      return response;
    }
  }

  private List<String> tailFile(Path path, int maxLines) throws IOException {
    try {
      return tailFile(path, maxLines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return tailFile(path, maxLines, Charset.forName("windows-1252"));
    }
  }

  private List<String> tailFile(Path path, int maxLines, Charset charset) throws IOException {
    ArrayDeque<String> lines = new ArrayDeque<>(maxLines);
    try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (lines.size() == maxLines) {
          lines.removeFirst();
        }
        lines.addLast(line);
      }
    }
    return new ArrayList<>(lines);
  }

  private boolean containsIgnoreCase(String haystack, String needle) {
    if (haystack == null || haystack.isBlank() || needle == null || needle.isBlank()) {
      return false;
    }
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  private boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return left.equalsIgnoreCase(right);
  }

  private String safeString(Object value) {
    return value == null ? "" : value.toString();
  }

  @SafeVarargs
  private final <T> T firstNonNull(T... values) {
    if (values == null) {
      return null;
    }
    for (T value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String determineApprovalBlockedReason(
      PatchLoopIteration iteration, String publishStatus, Map<String, Object> recoveryMetadata) {
    if (iteration.getStatus() == PatchLoopStatus.CANCELLED
        || "CANCELLED".equalsIgnoreCase(publishStatus)) {
      return "Run was cancelled";
    }
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return "Run must fail before it can be recovered";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "Publish failed; there is no runnable task instance to relaunch";
    }
    if (iteration.getAnalysisResponseJson() == null
        || iteration.getAnalysisResponseJson().isNull()) {
      String phase = stringValue(recoveryMetadata.get("phase"));
      if (RECOVERY_FAILED.equals(phase)) {
        return stringValueAllowBlank(recoveryMetadata.get("message"));
      }
      return "Generate a patch plan before relaunching";
    }
    if (iteration.getPatchId() == null || iteration.getPatchId().isBlank()) {
      return "Patch planning did not produce a patch draft";
    }
    String phase = stringValue(recoveryMetadata.get("phase"));
    if (RECOVERY_PATCH_PLAN_READY.equals(phase)) {
      return null;
    }
    if (phase == null || phase.isBlank()) {
      return "Patch plan is not ready to apply";
    }
    if (RECOVERY_FAILED.equals(phase)) {
      return stringValueAllowBlank(recoveryMetadata.get("message"));
    }
    if (RECOVERY_RELAUNCHED.equals(phase)) {
      return "Recovery has already relaunched this task";
    }
    return "Patch plan is not ready to apply";
  }

  @SuppressWarnings("unchecked")
  private AnalysisInputMetadata readAnalysisInputMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return new AnalysisInputMetadata(null, null, List.of(), false, null, null, null, null, null);
    }
    Object value = taskMetadata.get("analysis_input");
    if (!(value instanceof Map<?, ?> mapValue)) {
      return new AnalysisInputMetadata(null, null, List.of(), false, null, null, null, null, null);
    }
    Map<String, Object> metadata = new LinkedHashMap<>((Map<String, Object>) mapValue);
    List<String> selectedIds = new ArrayList<>();
    Object selectedValue = metadata.get("selected_evidence_ids");
    if (selectedValue instanceof List<?> values) {
      for (Object item : values) {
        if (item != null) {
          String text = item.toString().trim();
          if (!text.isEmpty()) {
            selectedIds.add(text);
          }
        }
      }
    }
    return new AnalysisInputMetadata(
        stringValue(metadata.get("prompt")),
        stringValueAllowBlank(metadata.get("context")),
        selectedIds,
        Boolean.TRUE.equals(metadata.get("customized")),
        stringValue(metadata.get("mode")),
        intValue(metadata.get("selected_evidence_count")),
        intValue(metadata.get("total_evidence_count")),
        intValue(metadata.get("time_range_start_seconds")),
        intValue(metadata.get("time_range_end_seconds")));
  }

  private void updateAnalysisInputMetadata(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      String prompt,
      String context,
      List<String> selectedEvidenceIds,
      int selectedEvidenceCount,
      int totalEvidenceCount,
      AnalysisPromptData defaults,
      String analysisMode,
      TimeRangeSelection timeRange) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("prompt", prompt);
    metadata.put("context", context);
    metadata.put("selected_evidence_ids", selectedEvidenceIds);
    metadata.put("selected_evidence_count", selectedEvidenceCount);
    metadata.put("total_evidence_count", totalEvidenceCount);
    if (timeRange != null) {
      metadata.put("time_range_start_seconds", timeRange.startSeconds());
      metadata.put("time_range_end_seconds", timeRange.endSeconds());
    }
    metadata.put("mode", analysisMode);
    boolean customized =
        !Objects.equals(prompt, defaults.prompt())
            || !Objects.equals(context, defaults.context())
            || selectedEvidenceCount != totalEvidenceCount
            || (timeRange != null
                && (timeRange.startSeconds() > 0
                    || timeRange.endSeconds() < timeRange.maxSeconds()));
    metadata.put("customized", customized);
    taskMetadata.put("analysis_input", metadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private SelectionResult buildSelectionResult(
      AnalysisV1EvidenceBundle evidence, List<String> requestedIds, boolean defaultSelectAll) {
    List<ResolvedEvidenceItem> items = resolveEvidenceItems(evidence);
    Set<String> selectableIds =
        items.stream()
            .map(ResolvedEvidenceItem::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> selectedIds = new LinkedHashSet<>();
    if (requestedIds != null) {
      for (String requestedId : requestedIds) {
        if (requestedId != null && selectableIds.contains(requestedId)) {
          selectedIds.add(requestedId);
        }
      }
    }
    if (selectedIds.isEmpty() && defaultSelectAll) {
      selectedIds.addAll(selectableIds);
    }

    Map<String, Object> logsGroup = buildGroup("logs", "Logs");
    Map<String, Object> tickLogsGroup = buildGroup("tickLogs", "Tick Logs");
    Map<String, Object> decisionsGroup = buildGroup("decisions", "Decisions");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> logsItems = (List<Map<String, Object>>) logsGroup.get("items");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tickLogItems = (List<Map<String, Object>>) tickLogsGroup.get("items");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> decisionItems =
        (List<Map<String, Object>>) decisionsGroup.get("items");

    AnalysisV1EvidenceBundle selectedBundle = new AnalysisV1EvidenceBundle();
    selectedBundle.setScriptName(evidence.getScriptName());
    selectedBundle.setTimeWindow(evidence.getTimeWindow());
    selectedBundle.setTickWindow(evidence.getTickWindow());
    List<AnalysisV1EvidenceBundle.LogEvidence> selectedLogs = new ArrayList<>();
    List<AnalysisV1EvidenceBundle.TickLogEvidence> selectedTickLogs = new ArrayList<>();
    List<AnalysisV1EvidenceBundle.DecisionEvidence> selectedDecisions = new ArrayList<>();

    for (ResolvedEvidenceItem item : items) {
      boolean selected = selectedIds.contains(item.id());
      Map<String, Object> view = new LinkedHashMap<>(item.view());
      view.put("selected", selected);
      switch (item.groupKey()) {
        case "logs" -> logsItems.add(view);
        case "tickLogs" -> tickLogItems.add(view);
        case "decisions" -> decisionItems.add(view);
        default -> {}
      }
      if (!selected) {
        continue;
      }
      switch (item.groupKey()) {
        case "logs" -> selectedLogs.add((AnalysisV1EvidenceBundle.LogEvidence) item.source());
        case "tickLogs" -> selectedTickLogs.add(
            (AnalysisV1EvidenceBundle.TickLogEvidence) item.source());
        case "decisions" -> selectedDecisions.add(
            (AnalysisV1EvidenceBundle.DecisionEvidence) item.source());
        default -> {}
      }
    }

    selectedBundle.setLogs(selectedLogs);
    selectedBundle.setTickLogs(selectedTickLogs);
    selectedBundle.setDecisions(selectedDecisions);
    return new SelectionResult(
        selectedBundle,
        List.of(logsGroup, tickLogsGroup, decisionsGroup),
        new ArrayList<>(selectedIds),
        selectedIds.size(),
        items.size());
  }

  private Map<String, Object> buildGroup(String key, String label) {
    Map<String, Object> group = new LinkedHashMap<>();
    group.put("key", key);
    group.put("label", label);
    group.put("items", new ArrayList<Map<String, Object>>());
    return group;
  }

  private List<ResolvedEvidenceItem> resolveEvidenceItems(AnalysisV1EvidenceBundle evidence) {
    List<ResolvedEvidenceItem> items = new ArrayList<>();

    Map<String, Integer> logOccurrences = new HashMap<>();
    for (AnalysisV1EvidenceBundle.LogEvidence log : evidence.getLogs()) {
      String canonical =
          safe(log.getTimestamp())
              + "|"
              + safe(log.getLevel())
              + "|"
              + safe(log.getSource())
              + "|"
              + safe(log.getMessage());
      String id = buildEvidenceId("log", canonical, logOccurrences);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put("title", firstNonBlank(log.getMessage(), "(no log message)"));
      view.put(
          "subtitle",
          String.format(
              "%s • %s • %s",
              safe(log.getTimestamp()), safe(log.getLevel()), safe(log.getSource())));
      view.put("detail", log.getMessage());
      items.add(new ResolvedEvidenceItem(id, "logs", view, log));
    }

    Map<String, Integer> tickOccurrences = new HashMap<>();
    for (AnalysisV1EvidenceBundle.TickLogEvidence tickLog : evidence.getTickLogs()) {
      String canonical =
          safe(tickLog.getTick())
              + "|"
              + safe(tickLog.getTimestamp())
              + "|"
              + safe(tickLog.getSubsystem())
              + "|"
              + safe(tickLog.getSeverity())
              + "|"
              + safe(tickLog.getMessage())
              + "|"
              + safe(tickLog.getDetails());
      String id = buildEvidenceId("tick", canonical, tickOccurrences);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put(
          "title",
          String.format(
              "Tick %s • %s",
              safe(tickLog.getTick()), firstNonBlank(tickLog.getMessage(), "(no tick message)")));
      view.put(
          "subtitle",
          String.format(
              "%s • %s/%s",
              safe(tickLog.getTimestamp()),
              safe(tickLog.getSubsystem()),
              safe(tickLog.getSeverity())));
      view.put("detail", firstNonBlank(tickLog.getDetails(), tickLog.getMessage()));
      items.add(new ResolvedEvidenceItem(id, "tickLogs", view, tickLog));
    }

    Map<String, Integer> decisionOccurrences = new HashMap<>();
    for (AnalysisV1EvidenceBundle.DecisionEvidence decision : evidence.getDecisions()) {
      String canonical =
          safe(decision.getTimestamp())
              + "|"
              + safe(decision.getDecisionType())
              + "|"
              + safe(decision.getSuccess())
              + "|"
              + safe(decision.getFailureCode())
              + "|"
              + safe(decision.getTargetEntity())
              + "|"
              + safe(decision.getContext());
      String id = buildEvidenceId("decision", canonical, decisionOccurrences);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put(
          "title",
          String.format(
              "%s • %s", safe(decision.getDecisionType()), safe(decision.getTargetEntity())));
      view.put(
          "subtitle",
          String.format(
              "%s • success=%s • failure=%s",
              safe(decision.getTimestamp()),
              safe(decision.getSuccess()),
              safe(decision.getFailureCode())));
      view.put("detail", decision.getContext());
      items.add(new ResolvedEvidenceItem(id, "decisions", view, decision));
    }

    return items;
  }

  private String buildEvidenceId(
      String prefix, String canonicalValue, Map<String, Integer> occurrences) {
    String digest = sha256(canonicalValue);
    int occurrence = occurrences.merge(digest, 1, Integer::sum);
    return prefix + "-" + digest.substring(0, 16) + "-" + occurrence;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to hash evidence item", e);
    }
  }

  private String safe(Object value) {
    return value == null ? "" : value.toString();
  }

  private List<String> selectedEvidenceIds(JsonNode payload) {
    if (payload == null || payload.isNull() || !payload.has("selectedEvidenceIds")) {
      return null;
    }
    JsonNode selectedNode = payload.path("selectedEvidenceIds");
    if (!selectedNode.isArray()) {
      return List.of();
    }
    List<String> selectedIds = new ArrayList<>();
    selectedNode.forEach(
        item -> {
          String value = textValue(item);
          if (value != null) {
            selectedIds.add(value);
          }
        });
    return selectedIds;
  }

  private JsonNode objectNode(JsonNode node) {
    return node != null && node.isObject() ? node : objectMapper.createObjectNode();
  }

  private JsonNode firstNonEmptyObjectNode(JsonNode... candidates) {
    if (candidates == null) {
      return objectMapper.createObjectNode();
    }
    for (JsonNode candidate : candidates) {
      if (isNonEmptyObject(candidate)) {
        return candidate;
      }
    }
    return objectMapper.createObjectNode();
  }

  /**
   * Extracts only task settings from an imported Builder Goal payload. An explicit settings object
   * is authoritative even when empty; only a genuinely legacy raw-settings payload is allowed to
   * use its top-level fields. This prevents transport fields such as task_key, title, world, and
   * account_id from being persisted or sent to Micro6 as task settings.
   */
  private JsonNode resolveAuthoritativeSettings(
      JsonNode payload, JsonNode builderExecution, JsonNode queuePreview) {
    JsonNode directSettings = payload.get("settings");
    if (directSettings != null) {
      return requireSettingsObject(directSettings, "settings");
    }

    JsonNode builderMetadata = builderExecution.get("task_metadata");
    if (builderMetadata != null) {
      JsonNode nestedSettings = builderMetadata.get("settings");
      return nestedSettings != null
          ? requireSettingsObject(nestedSettings, "builder_execution.task_metadata.settings")
          : stripReservedFields(builderMetadata);
    }

    JsonNode queueMetadata = queuePreview.get("task_metadata");
    if (queueMetadata != null) {
      JsonNode nestedSettings = queueMetadata.get("settings");
      return nestedSettings != null
          ? requireSettingsObject(nestedSettings, "queue_payload_preview.task_metadata.settings")
          : stripReservedFields(queueMetadata);
    }

    // Legacy raw settings have no settings/envelope field. Remove every known transport field
    // before treating the remaining top-level object as configuration.
    return stripReservedFields(payload);
  }

  /**
   * Removes every reserved transport field from a legacy settings candidate. The same filter is
   * applied to legacy nested task metadata and to raw top-level settings so that task identity,
   * account identity, and node context never leak into persisted task settings.
   */
  private JsonNode stripReservedFields(JsonNode candidate) {
    if (candidate == null || !candidate.isObject()) {
      return candidate;
    }
    com.fasterxml.jackson.databind.node.ObjectNode filtered = objectMapper.createObjectNode();
    candidate
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (!RESERVED_SETTINGS_TRANSPORT_FIELDS.contains(key)) {
                filtered.set(key, entry.getValue());
              }
            });
    return filtered;
  }

  private static final java.util.Set<String> RESERVED_SETTINGS_TRANSPORT_FIELDS =
      java.util.Set.of(
          "task_key",
          "taskKey",
          "taskName",
          "title",
          "name",
          "display_name",
          "displayName",
          "world",
          "source",
          "account_id",
          "accountId",
          "account_hash",
          "accountHash",
          "account_hash_scheme",
          "accountHashScheme",
          "launcher_account_id",
          "launcherAccountId",
          "launcher_machine_uuid",
          "launcherMachineUuid",
          "launcher_machine_name",
          "launcherMachineName",
          "requested_account_hash",
          "requestedAccountHash",
          "requested_account_name",
          "requestedAccountName",
          "requested_account_id",
          "requestedAccountId",
          "requested_launcher_machine_uuid",
          "requestedLauncherMachineUuid",
          "requested_launcher_machine_name",
          "requestedLauncherMachineName",
          "launch_profile_name",
          "launchProfileName",
          "launch_profile",
          "launchProfile",
          "launch_extra_args",
          "launchExtraArgs",
          "launch_attempt_id",
          "launchAttemptId",
          "launcher_base_url",
          "launcherBaseUrl",
          "target_client_id",
          "targetClientId",
          "target_base_url",
          "targetBaseUrl",
          "local_executor_base_url",
          "localExecutorBaseUrl",
          "advertised_base_url",
          "advertisedBaseUrl",
          "executor_bind_addr",
          "executorBindAddr",
          "process_id",
          "processId",
          "entry_id",
          "entryId",
          "iteration_id",
          "iterationId",
          "run_id",
          "runId",
          "session_id",
          "sessionId",
          "recording_id",
          "recordingId",
          "provenance",
          "allowLaunchFallback",
          "allow_launch_fallback",
          "forceRelaunch",
          "force_relaunch",
          "builderPayload",
          "selectedEvidenceIds",
          "selected_evidence_ids",
          "target_metadata",
          // Builder Goal transport/control fields. These are metadata in legacy flat payloads,
          // never task arguments (an explicitly present settings object bypasses this filter and
          // remains authoritative, including {}).
          "settings",
          "persist_settings",
          "requires_settings_receipt",
          "execution_mode",
          "executionMode",
          "client_type",
          "clientType",
          "queueable",
          "enabled",
          "runnable",
          "catalog_validation",
          "catalog_validation_skipped",
          "catalog_validation_error",
          "target_preparation",
          "micro6_target",
          "micro6_publish",
          "builder_execution",
          "queue_payload_preview",
          "node_context",
          "task_metadata");

  private JsonNode requireSettingsObject(JsonNode node, String fieldName) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(fieldName + " must be a JSON object");
    }
    return node;
  }

  private boolean isNonEmptyObject(JsonNode node) {
    return node != null && node.isObject() && node.fields().hasNext();
  }

  private void validateStoredSettingsConsistency(PatchLoopIteration iteration) {
    if (iteration == null || iteration.getImportedPayloadJson() == null) {
      return;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (!(taskMetadata.get("settings") instanceof Map<?, ?> persistedSettings)) {
      throw new IllegalStateException("Builder Goal run is missing persisted task settings");
    }
    NormalizedPayload imported = normalizePayload(iteration.getImportedPayloadJson());
    JsonNode importedSettings = objectMapper.valueToTree(imported.settings());
    JsonNode storedSettings = objectMapper.valueToTree(persistedSettings);
    if (!importedSettings.equals(storedSettings)) {
      throw new IllegalStateException(
          "Builder Goal run has imported task settings that do not match its persisted launch settings");
    }
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  /** Reads an identity field without trimming whitespace that must be validated exactly. */
  private String strictTextValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private String textValueAllowBlank(JsonNode payload, String fieldName) {
    if (payload == null || payload.isNull() || !payload.has(fieldName)) {
      return null;
    }
    JsonNode node = payload.get(fieldName);
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    return node.asText("");
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text.isBlank() ? null : text.trim();
  }

  private String normalizeOptionalString(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String stringValueAllowBlank(Object value) {
    return value == null ? null : value.toString();
  }

  private boolean isBannedStatus(String status) {
    String normalized = normalizeOptionalString(status);
    if (normalized == null) {
      return false;
    }
    String lowered = normalized.toLowerCase(Locale.ENGLISH);
    return "banned".equals(lowered)
        || "temp_banned".equals(lowered)
        || "tempban".equals(lowered)
        || "temp-ban".equals(lowered)
        || "temp_ban".equals(lowered);
  }

  /**
   * Validates generic account eligibility for publishing to an existing executor. Missing
   * credentials do not block publishing because a publish-only path never relaunches Micro6.
   */
  private void requireValidWorld(Integer world) {
    if (world == null || world < 300 || world > 700) {
      throw new IllegalArgumentException("world must be between 300 and 700");
    }
  }

  private void requireRunnableAccount(
      BuilderGoalAccountCatalogService.AccountDefinition selectedAccount) {
    if (selectedAccount == null) {
      throw new IllegalArgumentException("Select a launcher account first");
    }
    if (!selectedAccount.enabled()) {
      throw new IllegalStateException("Selected launcher account is disabled");
    }
    if (isBannedStatus(selectedAccount.status())) {
      throw new IllegalStateException("Selected launcher account is banned");
    }
    if (!AccountIdentityContract.isCanonicalHash(selectedAccount.accountHash())) {
      throw new IllegalStateException(
          "Selected launcher account is missing the canonical game-account hash");
    }
    if (!AccountIdentityContract.hasRequiredScheme(selectedAccount.accountHashScheme())) {
      throw new IllegalStateException(
          "Selected launcher account is missing the canonical account hash scheme");
    }
  }

  /**
   * Validates launch readiness for paths that relaunch a local Micro6 workspace. Rejects the
   * request before any iteration is persisted so configuration errors never create recovery runs.
   */
  private void requireLaunchReadyAccount(
      BuilderGoalAccountCatalogService.AccountDefinition selectedAccount) {
    requireRunnableAccount(selectedAccount);
    if (!selectedAccount.hasEmail()) {
      throw new IllegalStateException(
          "Selected launcher account is missing an email for local Micro6 relaunch");
    }
    if (!selectedAccount.hasPassword()) {
      throw new IllegalStateException(
          "Selected launcher account is missing a password for local Micro6 relaunch");
    }
  }

  /**
   * Resolves the selected launcher machine from the current launcher-control response and rejects
   * it unless it is eligible for local Builder Goal launches. Invoked before any run record or
   * pending launch is persisted or queued.
   */
  private void requireLaunchableMachine(String launcherMachineUuid) {
    BuilderGoalLauncherControlService.LauncherMachine machine =
        resolveSelectedMachine(launcherMachineUuid);
    requireLaunchableMachine(machine, launcherMachineUuid);
  }

  private void requireLaunchableMachine(
      BuilderGoalLauncherControlService.LauncherMachine machine, String launcherMachineUuid) {
    if (machine == null) {
      throw new IllegalArgumentException("Selected launcher machine was not found");
    }
    if (!launcherControlService.isBuilderGoalRunnableMachine(machine)) {
      throw new IllegalStateException(
          firstNonBlank(
              launcherControlService.getBuilderGoalMachineDisabledReason(machine),
              "Selected launcher machine is not available for Builder Goal local launches"));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readTargetMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return Map.of();
    }
    Object value = taskMetadata.get("micro6_target");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readTargetPreparationMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return new LinkedHashMap<>();
    }
    Object value = taskMetadata.get("target_preparation");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readPublishMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return new LinkedHashMap<>();
    }
    Object value = taskMetadata.get("micro6_publish");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return new LinkedHashMap<>();
  }

  private Map<String, Object> buildTargetMetadata(
      Micro6OnlineClientRegistryService.TargetClient targetClient) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("client_id", targetClient.clientId());
    target.put("account_name", targetClient.accountName());
    target.put("account_hash", targetClient.accountHash());
    // Keep requested and actual identity separate.  Falling back to the actual hash here would
    // make a heartbeat that omitted requested_account_hash look as if the selected launch
    // identity had been verified.  Selection already rejects that heartbeat; persisted metadata
    // must not erase the missing-field evidence.
    target.put("requested_account_hash", targetClient.requestedAccountHash());
    target.put("account_hash_scheme", targetClient.accountHashScheme());
    target.put("requested_world", targetClient.requestedWorld());
    target.put("current_world", targetClient.currentWorld());
    target.put("world_matched", targetClient.worldMatched());
    target.put("launch_attempt_id", targetClient.launchAttemptId());
    target.put("process_id", targetClient.processId());
    target.put("advertised_base_url", targetClient.advertisedBaseUrl());
    target.put("executor_bind_addr", targetClient.executorBindAddr());
    target.put("source_client_type", targetClient.sourceClientType());
    target.put("executor_profile", targetClient.executorProfile());
    target.put("status", targetClient.status());
    target.put("current_task", targetClient.currentTask());
    return target;
  }

  private Optional<Micro6OnlineClientRegistryService.TargetClient> findRunnableClientForAccount(
      BuilderGoalAccountCatalogService.AccountDefinition account) {
    if (account == null) {
      return Optional.empty();
    }
    return clientRegistryService.findOnlineClientForRecovery(
        account.accountHash(), account.displayName(), null, Instant.now().minusSeconds(45));
  }

  /**
   * Dispatch-ready variant used when selecting the executor that a Builder Goal task should be
   * published to. Requires the client to be online AND on the requested world with a live
   * AccountBuilder loop; a wrong-world or stopped-script client is never selected.
   */
  private List<Micro6OnlineClientRegistryService.TargetClient>
      findDispatchReadyClientsForAccount(
          BuilderGoalAccountCatalogService.AccountDefinition account, Integer expectedWorld) {
    if (account == null) {
      return List.of();
    }
    if (!AccountIdentityContract.isCanonicalHash(account.accountHash())
        || !AccountIdentityContract.hasRequiredScheme(account.accountHashScheme())) {
      return List.of();
    }
    return clientRegistryService.findDispatchReadyClientsForRecovery(
        account.accountHash(),
        account.displayName(),
        null,
        Instant.now().minusSeconds(45),
        expectedWorld);
  }

  private Map<String, Object> buildRequestedTargetMetadataFromAccount(
      BuilderGoalAccountCatalogService.AccountDefinition account,
      BuilderGoalLauncherControlService.LauncherMachine machine) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("requested_account_id", account.accountId());
    target.put("requested_account_hash", account.accountHash());
    target.put(
        "requested_account_name",
        firstNonBlank(account.accountName(), account.displayName()));
    target.put("account_hash_scheme", AccountIdentityContract.HASH_SCHEME);
    if (machine != null) {
      target.put("requested_launcher_machine_uuid", machine.machineUuid());
      target.put("requested_launcher_machine_name", machine.displayName());
    }
    return target;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readAccountMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return Map.of();
    }
    Object value = taskMetadata.get("mule_account");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return Map.of();
  }

  private Map<String, Object> buildAccountMetadata(
      BuilderGoalAccountCatalogService.AccountDefinition account,
      BuilderGoalLauncherControlService.LauncherMachine machine) {
    boolean executorOnline = findRunnableClientForAccount(account).isPresent();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("account_id", account.accountId());
    metadata.put("account_hash", account.accountHash());
    metadata.put(
        "account_hash_scheme",
        account.accountHashScheme());
    metadata.put("account_name", firstNonBlank(account.accountName(), account.displayName()));
    metadata.put("launch_profile_name", account.launchProfileName());
    metadata.put("launcher_account_id", account.accountId());
    metadata.put("display_name", account.displayName());
    metadata.put("email", account.email());
    metadata.put("has_password", account.hasPassword());
    metadata.put("hasEmail", account.hasEmail());
    metadata.put("hasPassword", account.hasPassword());
    metadata.put("runnable", account.isRunnable());
    metadata.put("disabled_reason", account.determineDisabledReason());
    metadata.put("launchReady", account.isLaunchReady());
    metadata.put("launchDisabledReason", account.determineLaunchDisabledReason());
    metadata.put("executorOnline", executorOnline);
    metadata.put("online", executorOnline);
    metadata.put("source", account.source());
    metadata.put("enabled", account.enabled());
    metadata.put("status", account.status());
    metadata.put("launcher_machine_uuid", machine != null ? machine.machineUuid() : null);
    metadata.put("launcher_machine_name", machine != null ? machine.displayName() : null);
    metadata.put("launcher_base_url", account.metadata().get("launcherBaseUrl"));
    return metadata;
  }

  private String resolveAccountPassword(Map<String, Object> accountMetadata) {
    if (accountMetadata == null || accountMetadata.isEmpty()) {
      return null;
    }
    String accountId =
        firstNonBlank(
            stringValue(accountMetadata.get("account_id")),
            stringValue(accountMetadata.get("account_hash")));
    if (accountId == null) {
      return null;
    }
    return accountCatalogService
        .findAccount(accountId)
        .map(BuilderGoalAccountCatalogService.AccountDefinition::password)
        .orElse(null);
  }

  private BuilderGoalLauncherControlService.LauncherMachine resolveSelectedMachine(
      String machineUuid) {
    String normalizedMachineUuid = normalizeOptionalString(machineUuid);
    if (normalizedMachineUuid == null) {
      throw new IllegalArgumentException("Select a launcher machine first");
    }
    return launcherControlService
        .findMachineByUuid(normalizedMachineUuid)
        .orElseThrow(() -> new IllegalArgumentException("Selected launcher machine was not found"));
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private Boolean boolValue(Object value) {
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    if (value instanceof String text && !text.isBlank()) {
      if ("true".equalsIgnoreCase(text.trim())) {
        return Boolean.TRUE;
      }
      if ("false".equalsIgnoreCase(text.trim())) {
        return Boolean.FALSE;
      }
    }
    return null;
  }

  private BuilderGoalAccountCatalogService.AccountDefinition resolveSelectedAccount(
      String accountId) {
    if (accountId == null || accountId.isBlank()) {
      return null;
    }
    return accountCatalogService
        .findAccount(accountId)
        .orElseThrow(() -> new IllegalArgumentException("Selected launcher account was not found"));
  }

  private String maskLaunchArgs(String launchArgs) {
    if (launchArgs == null || launchArgs.isBlank()) {
      return null;
    }
    String[] parts = launchArgs.trim().split("\\s+");
    List<String> masked = new ArrayList<>();
    for (String part : parts) {
      if (part.startsWith("--profile-email=")) {
        int colonIndex = part.indexOf(':');
        if (colonIndex > 0) {
          masked.add(part.substring(0, colonIndex + 1) + "********");
        } else {
          masked.add(part);
        }
      } else if (part.toLowerCase(Locale.ENGLISH).startsWith("password=")) {
        masked.add("password=********");
      } else {
        masked.add(part);
      }
    }
    String preview = String.join(" ", masked);
    return preview.length() > 120 ? preview.substring(0, 117) + "..." : preview;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readRecoveryMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return Map.of();
    }
    Object value = taskMetadata.get("recovery");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readRecoveryWorkspace(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return new LinkedHashMap<>();
    }
    Object value = taskMetadata.get("recovery_workspace");
    if (value instanceof Map<?, ?> mapValue) {
      return new LinkedHashMap<>((Map<String, Object>) mapValue);
    }
    return new LinkedHashMap<>();
  }

  private List<String> readStoredSelectedEvidenceIds(
      Map<String, Object> workspace, Map<String, Object> taskMetadata) {
    Object value = workspace.get("selected_evidence_ids");
    if (value instanceof List<?> list) {
      List<String> ids = new ArrayList<>();
      for (Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          ids.add(item.toString().trim());
        }
      }
      if (!ids.isEmpty()) {
        return ids;
      }
    }
    return readAnalysisInputMetadata(taskMetadata).selectedEvidenceIds();
  }

  /**
   * Parses the inline recovery annotations submitted from the debugger surface into a stable map
   * list. Missing ids are generated so annotations remain addressable across save/refresh cycles.
   */
  private List<Map<String, Object>> parseAnnotations(JsonNode payload) {
    if (payload == null || payload.isNull()) {
      return List.of();
    }
    JsonNode node = payload.path("annotations");
    if (!node.isArray()) {
      return List.of();
    }
    List<Map<String, Object>> annotations = new ArrayList<>();
    for (JsonNode item : node) {
      if (item == null || !item.isObject()) {
        continue;
      }
      Map<String, Object> annotation = new LinkedHashMap<>();
      String id = textValue(item.path("id"));
      annotation.put("id", id != null ? id : UUID.randomUUID().toString());
      annotation.put("label", firstNonBlank(textValue(item.path("label")), ""));
      annotation.put("note", stringValueAllowBlank(textValue(item.path("note"))));
      annotation.put("important", Boolean.TRUE.equals(item.path("important").asBoolean(false)));
      annotation.put("startSeconds", intValue(item.path("startSeconds")));
      annotation.put("endSeconds", intValue(item.path("endSeconds")));
      annotation.put("startTick", intValue(item.path("startTick")));
      annotation.put("endTick", intValue(item.path("endTick")));
      annotation.put("startFrame", intValue(item.path("startFrame")));
      annotation.put("endFrame", intValue(item.path("endFrame")));
      annotations.add(annotation);
    }
    return annotations;
  }

  /** Builds a compact human-readable summary of annotations to append to the Codex context. */
  private String buildAnnotationsContext(List<Map<String, Object>> annotations) {
    if (annotations == null || annotations.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    int index = 1;
    for (Map<String, Object> a : annotations) {
      sb.append("Range ").append(index++).append(": ");
      sb.append(formatDurationSeconds(intValueOrZero(a.get("startSeconds"))));
      sb.append(" -> ").append(formatDurationSeconds(intValueOrZero(a.get("endSeconds"))));
      if (Boolean.TRUE.equals(a.get("important"))) {
        sb.append(" [IMPORTANT]");
      }
      String label = stringValue(a.get("label"));
      if (label != null && !label.isBlank()) {
        sb.append(" label=").append(label);
      }
      String note = stringValueAllowBlank(a.get("note"));
      if (note != null && !note.isBlank()) {
        sb.append(" note=").append(note);
      }
      Integer startTick = intValue(a.get("startTick"));
      Integer endTick = intValue(a.get("endTick"));
      if (startTick != null || endTick != null) {
        sb.append(" ticks=").append(startTick).append("-").append(endTick);
      }
      sb.append("\n");
    }
    return sb.toString().trim();
  }

  private int intValueOrZero(Object value) {
    Integer parsed = intValue(value);
    return parsed != null ? parsed : 0;
  }

  /**
   * Resolves the evidence time window for recovery evidence resolution: payload active range wins,
   * then the union of annotation ranges, then the last persisted selection, then the full range.
   */
  private TimeRangeSelection resolveRecoveryTimeRange(
      List<Map<String, Object>> annotations,
      JsonNode payload,
      AnalysisInputMetadata existingInput,
      AnalysisV1EvidenceBundle evidence) {
    int maxSeconds = computeMaxRangeSeconds(evidence);
    Integer start = intValue(payload != null ? payload.get("activeRangeStartSeconds") : null);
    Integer end = intValue(payload != null ? payload.get("activeRangeEndSeconds") : null);
    if (start == null || end == null) {
      int annStart = Integer.MAX_VALUE;
      int annEnd = Integer.MIN_VALUE;
      for (Map<String, Object> a : annotations) {
        Integer s = intValue(a.get("startSeconds"));
        Integer e = intValue(a.get("endSeconds"));
        if (s != null) {
          annStart = Math.min(annStart, s);
        }
        if (e != null) {
          annEnd = Math.max(annEnd, e);
        }
      }
      if (annStart != Integer.MAX_VALUE && annEnd != Integer.MIN_VALUE) {
        start = annStart;
        end = annEnd;
      } else {
        start = existingInput.timeRangeStartSeconds();
        end = existingInput.timeRangeEndSeconds();
      }
    }
    int startSeconds = clampRangeValue(start, 0, maxSeconds);
    int endSeconds = clampRangeValue(end, maxSeconds, maxSeconds);
    if (endSeconds <= startSeconds) {
      endSeconds = maxSeconds;
      startSeconds = 0;
    }
    return new TimeRangeSelection(startSeconds, endSeconds, maxSeconds);
  }

  private Map<String, Object> buildResolvedTimeWindow(TimeRangeSelection timeRange) {
    Map<String, Object> window = new LinkedHashMap<>();
    window.put("startSeconds", timeRange.startSeconds());
    window.put("endSeconds", timeRange.endSeconds());
    window.put("maxSeconds", timeRange.maxSeconds());
    window.put("startDisplay", formatDurationSeconds(timeRange.startSeconds()));
    window.put("endDisplay", formatDurationSeconds(timeRange.endSeconds()));
    window.put("maxDisplay", formatDurationSeconds(timeRange.maxSeconds()));
    return window;
  }

  private String findChildIterationId(String iterationId) {
    List<PatchLoopIteration> children =
        iterationRepository.findByParentIterationIdOrderByCreatedAtDesc(iterationId);
    return children.isEmpty() ? null : children.get(0).getIterationId();
  }

  private String deriveRelaunchStatus(String phase) {
    if (phase == null) {
      return "IDLE";
    }
    return switch (phase) {
      case RECOVERY_APPLYING_PATCH -> "APPLYING_PATCH";
      case RECOVERY_RESTARTING_TARGET -> "RESTARTING_TARGET";
      case RECOVERY_RELOGGING_IN -> "RELOGGING_IN";
      case RECOVERY_WAITING_FOR_EXECUTOR -> "WAITING_FOR_EXECUTOR";
      case RECOVERY_REPUBLISHING -> "REPUBLISHING";
      case RECOVERY_WAITING_FOR_DEBUG_BIND -> "WAITING_FOR_DEBUG_BIND";
      case RECOVERY_RELAUNCHED -> "RELAUNCHED";
      case RECOVERY_FAILED -> "FAILED";
      default -> "IDLE";
    };
  }

  private String deriveBindStatus(
      PatchLoopIteration iteration, Map<String, Object> run, String phase) {
    String displayStatus = stringValue(run.get("status"));
    if (DISPLAY_STATUS_WAITING_FOR_DEBUG_BIND.equals(displayStatus)
        || RECOVERY_WAITING_FOR_DEBUG_BIND.equals(phase)) {
      return "WAITING_FOR_DEBUG_BIND";
    }
    if (iteration.getRunSessionId() != null && !iteration.getRunSessionId().isBlank()) {
      return "BOUND";
    }
    return "UNBOUND";
  }

  /**
   * Reserves a preferred debug session id for the relaunched client to rebind to. Prefers the
   * existing reserved id, then the failed run's bound session (for continuity), then a fresh
   * deterministic id. Persists the reservation on the parent's recovery_workspace metadata.
   */
  private String reservePreferredDebugSession(
      PatchLoopIteration iteration, Map<String, Object> taskMetadata) {
    Map<String, Object> workspace = readRecoveryWorkspace(taskMetadata);
    String preferred =
        firstNonBlank(
            stringValue(workspace.get("preferred_session_id")),
            iteration.getRunSessionId(),
            "debug_" + System.currentTimeMillis());
    Map<String, Object> updated = new LinkedHashMap<>(workspace);
    updated.put("preferred_session_id", preferred);
    updated.put("reserved_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery_workspace", updated);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
    return preferred;
  }

  private boolean isRecoveryPhaseActive(String phase) {
    return RECOVERY_AWAITING_OPERATOR_PROMPT.equals(phase)
        || RECOVERY_PATCH_PLAN_READY.equals(phase)
        || RECOVERY_APPLYING_PATCH.equals(phase)
        || RECOVERY_RESTARTING_TARGET.equals(phase)
        || RECOVERY_RELOGGING_IN.equals(phase)
        || RECOVERY_WAITING_FOR_EXECUTOR.equals(phase)
        || RECOVERY_REPUBLISHING.equals(phase)
        || RECOVERY_WAITING_FOR_DEBUG_BIND.equals(phase)
        || RECOVERY_RELAUNCHED.equals(phase);
  }

  private String resolveTargetBaseUrl(Map<String, Object> taskMetadata) {
    Map<String, Object> targetMetadata =
        taskMetadata != null && taskMetadata.containsKey("advertised_base_url")
            ? taskMetadata
            : readTargetMetadata(taskMetadata);
    String value = stringValue(targetMetadata.get("advertised_base_url"));
    if (value == null) {
      return null;
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value.isBlank() ? null : value;
  }

  private Map<String, Object> buildPublishMetadata(
      ScriptExecutionService.Micro6PublishResult publishResult, LocalDateTime publishedAt) {
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", publishResult.entryId());
    publish.put("status", firstNonBlank(publishResult.publishStatus(), "PUBLISHED"));
    publish.put("published_at", publishedAt.toString());
    publish.put("attached_to_existing", publishResult.attachedToExisting());
    publish.put("micro6_base_url", publishResult.micro6BaseUrl());
    return publish;
  }

  private Map<String, Object> buildPublishFailureMetadata(
      ScriptExecutionService.Micro6PublishResult publishResult,
      LocalDateTime publishedAt,
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult) {
    Map<String, Object> publish = new LinkedHashMap<>();
    Map<String, Object> targetPreparationMetadata = readTargetPreparationMetadata(taskMetadata);
    publish.put("status", "PUBLISH_FAILED");
    publish.put("published_at", publishedAt.toString());
    publish.put("last_error", publishResult.errorMessage());
    publish.put(
        "micro6_base_url",
        firstNonBlank(
            healthResult != null ? healthResult.micro6BaseUrl() : null,
            publishResult.micro6BaseUrl()));
    publish.put(
        "readiness_phase",
        firstNonBlank(
            healthResult != null ? healthResult.readinessPhase() : null,
            stringValue(targetPreparationMetadata.get("readiness_phase"))));
    publish.put(
        "readiness_reason",
        firstNonBlank(
            healthResult != null ? healthResult.readinessReason() : null,
            stringValueAllowBlank(targetPreparationMetadata.get("readiness_reason"))));
    publish.put(
        "dispatch_hold",
        firstNonNull(
            healthResult != null ? healthResult.dispatchHold() : null,
            boolValue(targetPreparationMetadata.get("dispatch_hold"))));
    publish.put(
        "dispatch_hold_reason",
        firstNonBlank(
            healthResult != null ? healthResult.dispatchHoldReason() : null,
            stringValue(targetPreparationMetadata.get("dispatch_hold_reason"))));
    publish.put(
        "startup_state",
        firstNonBlank(
            healthResult != null ? healthResult.startupState() : null,
            stringValue(targetPreparationMetadata.get("startup_state"))));
    publish.put(
        "login_state",
        firstNonBlank(
            healthResult != null ? healthResult.loginState() : null,
            stringValue(targetPreparationMetadata.get("login_state"))));
    publish.put(
        "login_terminal_reason",
        firstNonBlank(
            healthResult != null ? healthResult.loginTerminalReason() : null,
            stringValueAllowBlank(targetPreparationMetadata.get("login_terminal_reason"))));
    publish.put("launch_log_file", stringValue(targetPreparationMetadata.get("launcher_log_file")));
    return publish;
  }

  private String mapPublishStatus(String micro6Status) {
    if (micro6Status == null || micro6Status.isBlank()) {
      return "RECONCILING";
    }
    if ("QUEUED".equalsIgnoreCase(micro6Status)) {
      return "QUEUED";
    }
    if ("ACCEPTED".equalsIgnoreCase(micro6Status)
        || "RECEIVED".equalsIgnoreCase(micro6Status)
        || "PENDING".equalsIgnoreCase(micro6Status)) {
      return "ACCEPTED";
    }
    if ("ASSIGNED".equalsIgnoreCase(micro6Status)) {
      return "ASSIGNED";
    }
    if ("FAILED".equalsIgnoreCase(micro6Status)) {
      return "FAILED";
    }
    if ("CANCELLED".equalsIgnoreCase(micro6Status)) {
      return "CANCELLED";
    }
    if ("COMPLETED".equalsIgnoreCase(micro6Status)) {
      return "COMPLETED";
    }
    if ("RUNNING".equalsIgnoreCase(micro6Status)) {
      return "RUNNING";
    }
    if ("PAUSED".equalsIgnoreCase(micro6Status)) {
      // PAUSED is an activated-but-suspended state and must not collapse into RUNNING: the summary
      // and UI label it "Task paused" while resume/stop remain available.
      return "PAUSED";
    }
    // Unknown executor states are ambiguous, never confirmation of publication.
    return "RECONCILING";
  }

  private String deriveDisplayStatus(PatchLoopIteration iteration, String publishStatus) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String preparationState = stringValue(readTargetPreparationMetadata(taskMetadata).get("state"));
    boolean taskStarted = Boolean.TRUE.equals(taskMetadata.get("task_start_confirmed"));
    boolean taskStartConfirmed = taskStarted && iteration.getRunSessionId() != null;
    if (iteration.getStatus() == PatchLoopStatus.CANCELLED) {
      return "CANCELLED";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "PUBLISH_FAILED";
    }
    if (iteration.getStatus() == PatchLoopStatus.COMPLETED
        || Boolean.TRUE.equals(iteration.getRunSuccess())) {
      return "COMPLETED";
    }
    if (iteration.getStatus() == PatchLoopStatus.FAILED
        || Boolean.FALSE.equals(iteration.getRunSuccess())) {
      return "FAILED";
    }
    if (isStopRequested(taskMetadata, readPublishMetadata(taskMetadata))
        && iteration.getStatus() != null
        && !iteration.getStatus().isTerminal()) {
      return "STOPPING";
    }
    if ("PAUSED".equalsIgnoreCase(publishStatus)) {
      // PAUSED is activated but suspended: preserve the public state and active controls.
      return "PAUSED";
    }
    if ("RECONCILING".equalsIgnoreCase(publishStatus)) {
      // Ambiguous execute outcome: display a waiting state, never "Publish failed".
      return DISPLAY_STATUS_AWAITING_EXECUTOR_CONFIRMATION;
    }
    if ("PUBLISHING".equalsIgnoreCase(publishStatus)) {
      return "PUBLISHING";
    }
    if (preparationState != null
        && (publishStatus == null || publishStatus.isBlank())
        && !PREPARATION_STATE_READY_TO_PUBLISH.equals(preparationState)) {
      return preparationState;
    }
    if (taskStarted
        && !taskStartConfirmed
        && ("RUNNING".equalsIgnoreCase(publishStatus)
            || "PAUSED".equalsIgnoreCase(publishStatus)
            || "QUEUED".equalsIgnoreCase(publishStatus)
            || "ACCEPTED".equalsIgnoreCase(publishStatus)
            || "PUBLISHED".equalsIgnoreCase(publishStatus))
        && iteration.getStatus() != PatchLoopStatus.COMPLETED
        && iteration.getStatus() != PatchLoopStatus.FAILED) {
      return DISPLAY_STATUS_WAITING_FOR_DEBUG_BIND;
    }
    if (!taskStartConfirmed
        && ("PUBLISHED".equalsIgnoreCase(publishStatus)
            || "RUNNING".equalsIgnoreCase(publishStatus)
            || "QUEUED".equalsIgnoreCase(publishStatus)
            || "ACCEPTED".equalsIgnoreCase(publishStatus))
        && iteration.getStatus() != PatchLoopStatus.COMPLETED
        && iteration.getStatus() != PatchLoopStatus.FAILED) {
      return DISPLAY_STATUS_WAITING_FOR_TASK_START;
    }
    return firstNonBlank(
        publishStatus,
        iteration.getStatus() != null ? iteration.getStatus().name() : null,
        "QUEUED");
  }

  private Integer intValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private LocalDateTime parseLocalDateTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String stackTrace(Exception e) {
    StringBuilder builder = new StringBuilder();
    builder.append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
    for (StackTraceElement element : e.getStackTrace()) {
      builder.append("  at ").append(element).append('\n');
    }
    return builder.toString();
  }

  /** Derives the local Micro6 executor port from the configured executor base URL. */
  private static int resolveExecutorPort(String micro6ExecutorBaseUrl) {
    if (micro6ExecutorBaseUrl != null && !micro6ExecutorBaseUrl.isBlank()) {
      try {
        java.net.URI uri = java.net.URI.create(micro6ExecutorBaseUrl.trim());
        if (uri.getPort() > 0) {
          return uri.getPort();
        }
      } catch (Exception ignored) {
        // fall through to the default port
      }
    }
    return 8098;
  }

  private static final class PublishDeferredException extends IllegalStateException {
    private final String phase;

    private PublishDeferredException(String phase, String message) {
      super(message);
      this.phase = phase;
    }

    @SuppressWarnings("unused")
    private String getPhase() {
      return phase;
    }
  }

  private record NormalizedPayload(
      String taskKey, String displayName, Map<String, Object> settings, Integer world) {}

  private record AnalysisPromptData(String prompt, String context) {}

  private record BuilderGoalCatalogSnapshot(
      boolean catalogAvailable,
      boolean degraded,
      String degradedReason,
      String source,
      JsonNode catalogPayload,
      List<Map<String, Object>> tasks) {}

  private record AnalysisInputMetadata(
      String prompt,
      String context,
      List<String> selectedEvidenceIds,
      boolean customized,
      String mode,
      Integer selectedEvidenceCount,
      Integer totalEvidenceCount,
      Integer timeRangeStartSeconds,
      Integer timeRangeEndSeconds) {}

  private record TimeRangeSelection(int startSeconds, int endSeconds, int maxSeconds) {}

  private record ResolvedEvidenceItem(
      String id, String groupKey, Map<String, Object> view, Object source) {}

  private record SelectionResult(
      AnalysisV1EvidenceBundle bundle,
      List<Map<String, Object>> groups,
      List<String> selectedIds,
      int selectedCount,
      int totalCount) {}

  private record CatalogValidationResult(
      String status,
      boolean skipped,
      boolean rejectPublish,
      String errorMessage,
      JsonNode catalogTask,
      String settingsValidation) {

    private static CatalogValidationResult validated(String status, JsonNode catalogTask) {
      return new CatalogValidationResult(status, false, false, null, catalogTask, "VALID");
    }

    private static CatalogValidationResult skipped(String status) {
      return new CatalogValidationResult(status, true, false, null, null, "UNVALIDATED");
    }

    private static CatalogValidationResult reject(
        String status, String errorMessage, JsonNode catalogTask) {
      return new CatalogValidationResult(status, false, true, errorMessage, catalogTask, status);
    }

    private CatalogValidationResult withSettingsValidation(String validation) {
      return new CatalogValidationResult(
          status, skipped, rejectPublish, errorMessage, catalogTask, validation);
    }
  }

  /**
   * Thrown when the launch executor rejects a submission (capacity reached). Controllers map this
   * to HTTP 503.
   */
  public static class LaunchSubmissionException extends RuntimeException {
    public LaunchSubmissionException(String message) {
      super(message);
    }
  }
}
