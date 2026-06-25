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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuilderGoalRunnerService {

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
  private static final String RECOVERY_RELAUNCHED = "RELAUNCHED";
  private static final String RECOVERY_FAILED = "RECOVERY_FAILED";
  private static final String PREPARATION_STATE_PREPARING_TARGET = "PREPARING_TARGET";
  private static final String PREPARATION_STATE_WAITING_FOR_AGENT = "WAITING_FOR_AGENT";
  private static final String PREPARATION_STATE_LOGGING_IN = "LOGGING_IN";
  private static final String PREPARATION_STATE_READY_TO_PUBLISH = "READY_TO_PUBLISH";

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopPersistenceService persistenceService;
  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopSessionResolverService sessionResolverService;
  private final ScriptExecutionService scriptExecutionService;
  private final AnalysisProvider analysisProvider;
  private final Micro6ExecutorCatalogService micro6ExecutorCatalogService;
  private final BuilderGoalLauncherControlService launcherControlService;
  private final BuilderGoalAccountCatalogService accountCatalogService;
  private final Micro6OnlineClientRegistryService clientRegistryService;
  private final Micro6TaskSourcePatchService micro6TaskSourcePatchService;
  private final Micro6TargetRelaunchService micro6TargetRelaunchService;
  private final Micro6TargetPreparationService targetPreparationService;
  private final ObjectMapper objectMapper;
  private final Executor patchLoopExecutor;
  private final long runTimeoutMs;
  private final int maxConsecutiveStatusFailures;
  private final long statusFailureGraceMs;

  public BuilderGoalRunnerService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopPersistenceService persistenceService,
      PatchLoopEvidenceService evidenceService,
      PatchLoopSessionResolverService sessionResolverService,
      ScriptExecutionService scriptExecutionService,
      AnalysisProvider analysisProvider,
      Micro6ExecutorCatalogService micro6ExecutorCatalogService,
      BuilderGoalLauncherControlService launcherControlService,
      BuilderGoalAccountCatalogService accountCatalogService,
      Micro6OnlineClientRegistryService clientRegistryService,
      Micro6TaskSourcePatchService micro6TaskSourcePatchService,
      Micro6TargetRelaunchService micro6TargetRelaunchService,
      Micro6TargetPreparationService targetPreparationService,
      ObjectMapper objectMapper,
      @Qualifier("patchLoopExecutor") Executor patchLoopExecutor,
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
    this.launcherControlService = launcherControlService;
    this.accountCatalogService = accountCatalogService;
    this.clientRegistryService = clientRegistryService;
    this.micro6TaskSourcePatchService = micro6TaskSourcePatchService;
    this.micro6TargetRelaunchService = micro6TargetRelaunchService;
    this.targetPreparationService = targetPreparationService;
    this.objectMapper = objectMapper;
    this.patchLoopExecutor = patchLoopExecutor;
    this.runTimeoutMs = runTimeoutMs;
    this.maxConsecutiveStatusFailures = maxConsecutiveStatusFailures;
    this.statusFailureGraceMs = statusFailureGraceMs;
  }

  public Map<String, Object> importPayload(JsonNode payload) {
    NormalizedPayload normalized = normalizePayload(payload);
    JsonNode catalogTask = resolveCatalogTask(normalized.taskKey());
    return buildImportResponse(normalized, catalogTask);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listAccounts() {
    return accountCatalogService.listAccounts();
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
    return iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(this::hasStoredConfig)
        .filter(
            iteration -> {
              Map<String, Object> accountMetadata = readAccountMetadata(readTaskMetadata(iteration));
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
        accountId, launcherMachineUuid, normalized.taskKey(), normalized.settings(), normalized.displayName());
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
    response.put("prompt", existingInput.prompt() != null ? existingInput.prompt() : defaults.prompt());
    response.put("context", existingInput.context() != null ? existingInput.context() : defaults.context());
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
    return iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
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
      String launcherMachineUuid) {
    NormalizedPayload normalized = normalizePayload(payload);
    BuilderGoalAccountCatalogService.AccountDefinition selectedAccount =
        resolveSelectedAccount(accountId);
    if (selectedAccount == null) {
      throw new IllegalArgumentException("Select a launcher account first");
    }
    String selectedMachineUuid = normalizeOptionalString(launcherMachineUuid);
    if (selectedMachineUuid == null) {
      throw new IllegalArgumentException("Select a launcher machine first");
    }
    BuilderGoalLauncherControlService.LauncherMachine selectedMachine =
        resolveSelectedMachine(selectedMachineUuid);
    CatalogValidationResult catalogValidation =
        resolvePublishCatalogValidation(normalized.taskKey(), null);
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
            catalogValidation.catalogTask());
    applyCatalogValidationMetadata(iteration, catalogValidation);

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("mule_account", buildAccountMetadata(selectedAccount, selectedMachine));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);

    updatePreparationState(
        iteration,
        taskMetadata,
        PREPARATION_STATE_PREPARING_TARGET,
        "Launching the local Micro6 workspace for the selected account");

    Micro6TargetPreparationService.PreparationResult preparationResult;
    try {
      preparationResult =
          targetPreparationService.ensureReady(
              new Micro6TargetPreparationService.PreparationRequest(
                  firstNonBlank(selectedAccount.accountHash(), selectedAccount.accountId()),
                  selectedAccount.accountHash(),
                  selectedAccount.displayName(),
                  selectedAccount.email(),
                  selectedAccount.password(),
                  selectedMachine.machineUuid(),
                  null));
    } catch (Micro6TargetPreparationService.PreparationException e) {
      updatePreparationState(iteration, readTaskMetadata(iteration), e.getPhase(), e.getMessage());
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.SCRIPT_START_FAILED,
          e.getMessage(),
          "account=" + firstNonBlank(selectedAccount.displayName(), selectedAccount.accountId(), "(none)"));
      throw e;
    }

    taskMetadata = readTaskMetadata(iteration);
    mergeTargetPreparationMetadata(taskMetadata, preparationResult.metadata());
    taskMetadata.put("micro6_target", buildTargetMetadata(preparationResult.targetClient()));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
    updatePreparationState(
        iteration, readTaskMetadata(iteration), PREPARATION_STATE_READY_TO_PUBLISH, "Target ready to publish");

    ScriptExecutionService.Micro6PublishResult publishResult = publishIteration(iteration);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("run", toRunSummary(iteration));
    response.put("import", buildImportResponse(normalized, catalogValidation.catalogTask()));
    response.put("published", publishResult.published());
    response.put("entryId", publishResult.entryId());
    response.put("targetClientId", preparationResult.targetClient().clientId());
    response.put("targetAccountName", preparationResult.targetClient().accountName());
    response.put("accountId", selectedAccount.accountId());
    response.put("accountHash", selectedAccount.accountHash());
    response.put("accountName", selectedAccount.displayName());
    response.put("publishStatus", publishResult.publishStatus());
    response.put("publishError", publishResult.errorMessage());
    response.put("catalogValidation", catalogValidation.status());
    response.put("catalogValidationSkipped", catalogValidation.skipped());
    response.put("targetPreparation", preparationResult.metadata());
    return response;
  }

  @Transactional
  public Map<String, Object> startCatalogTaskRun(
      String accountId,
      String launcherMachineUuid,
      String taskKey,
      Map<String, Object> settings,
      String displayName) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("accountId is required");
    }
    if (taskKey == null || taskKey.isBlank()) {
      throw new IllegalArgumentException("taskKey is required");
    }
    JsonNode catalogTask = resolveCatalogTask(taskKey);
    String resolvedDisplayName =
        firstNonBlank(
            displayName,
            catalogTask != null ? textValue(catalogTask.path("display_name")) : null,
            catalogTask != null ? textValue(catalogTask.path("panel_display_name")) : null,
            taskKey);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("task_key", taskKey);
    payload.put("title", resolvedDisplayName);
    payload.put("settings", settings != null ? settings : Map.of());
    payload.put("source", "catalog_task");
    payload.put("account_id", accountId);
    return startRun(objectMapper.valueToTree(payload), accountId, null, null, null, launcherMachineUuid);
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId) throws Exception {
    return analyzeFailure(iterationId, null, ANALYSIS_MODE_OPERATOR_EDITED_MCP);
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId, JsonNode payload) throws Exception {
    return analyzeFailure(iterationId, payload, ANALYSIS_MODE_OPERATOR_EDITED_MCP);
  }

  @Transactional
  public Map<String, Object> approveRecovery(String iterationId) throws Exception {
    return executeApprovedRecovery(iterationId);
  }

  @Transactional
  public Map<String, Object> fixAndRelaunch(String iterationId) throws Exception {
    return executeApprovedRecovery(iterationId);
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
      throw new IllegalArgumentException("At least one evidence item must be selected for analysis");
    }

    AnalysisV1EvidenceBundle selectedEvidence = selectionResult.bundle();
    persistenceService.saveEvidenceCounts(iteration, selectedEvidence);
    if (!evidenceService.validateEvidenceSufficiency(selectedEvidence)) {
      throw new IllegalStateException("Insufficient evidence available for analysis");
    }

    AnalysisV2Request request = buildAnalysisRequest(iteration, selectedEvidence, prompt, context);
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

    persistenceService.saveAnalysisResults(
        iteration, response, objectMapper.valueToTree(response));
    AnalysisV2PatchDraft patchDraft = response.getPatchDraft();
    if (patchDraft != null && patchDraft.getChanges() != null && !patchDraft.getChanges().isEmpty()) {
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
    return toRunSummary(iterationRepository.findByIterationId(iteration.getIterationId()).orElseThrow());
  }

  private Map<String, Object> executeApprovedRecovery(String iterationId) throws Exception {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      throw new IllegalStateException("Run must fail before it can be fixed");
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      throw new IllegalStateException("Publish failed; there is no runnable task instance to relaunch");
    }

    if (iteration.getAnalysisResponseJson() == null || iteration.getAnalysisResponseJson().isNull()) {
      throw new IllegalStateException("Analyze Failure must be run first");
    }
    Map<String, Object> recoveryMetadata = readRecoveryMetadata(taskMetadata);
    if (!RECOVERY_PATCH_PLAN_READY.equals(stringValue(recoveryMetadata.get("phase")))) {
      throw new IllegalStateException(determineApprovalBlockedReason(iteration, publishStatus, recoveryMetadata));
    }

    AnalysisV2Response analysis =
        objectMapper.treeToValue(iteration.getAnalysisResponseJson(), AnalysisV2Response.class);
    AnalysisV2PatchDraft patchDraft = analysis.getPatchDraft();
    if (patchDraft == null || patchDraft.getChanges() == null || patchDraft.getChanges().isEmpty()) {
      throw new IllegalStateException("Analysis did not produce a patch draft");
    }

    String taskKey = stringValue(taskMetadata.get("task_key"));
    if (taskKey == null) {
      taskKey = iteration.getScriptName();
    }

    try {
      updateRecoveryState(iteration, taskMetadata, RECOVERY_APPLYING_PATCH, "Applying patch to Micro6 task source");
      Micro6TaskSourcePatchService.PatchApplyResult patchResult =
          micro6TaskSourcePatchService.applyPatch(
              resolveTargetBaseUrl(taskMetadata), taskKey, iteration.getIterationId(), patchDraft);
      iteration.setMicro6RevisionId(patchResult.revisionId());
      iteration.setMicro6PatchedAt(LocalDateTime.now());
      persistenceService.saveIteration(iteration);

      updateRecoveryState(
          iteration, taskMetadata, RECOVERY_RESTARTING_TARGET, "Restarting Micro6 target to load patched code");
      Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
      Map<String, Object> accountMetadata = readAccountMetadata(taskMetadata);
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
                  stringValue(accountMetadata.get("launcher_machine_uuid")),
                  resolveTargetBaseUrl(taskMetadata)));

      updateRecoveryState(
          iteration,
          taskMetadata,
          RECOVERY_RELOGGING_IN,
          "Local Micro6 workspace restarted for the selected account");
      updateRecoveryState(
          iteration, taskMetadata, RECOVERY_WAITING_FOR_EXECUTOR, "Executor heartbeat returned; preparing republish");
      mergeTargetPreparationMetadata(taskMetadata, preparationResult.metadata());
      taskMetadata.put("micro6_target", buildTargetMetadata(preparationResult.targetClient()));
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      persistenceService.saveIteration(iteration);
      updateRecoveryState(
          iteration, readTaskMetadata(iteration), RECOVERY_REPUBLISHING, "Republishing patched task to relaunched target");
      JsonNode importedPayload = iteration.getImportedPayloadJson();
      NormalizedPayload normalized = normalizeStoredIteration(iteration);
      PatchLoopIteration relaunched =
          createRunIteration(
              normalized,
              importedPayload,
              iteration.getIterationId(),
              patchResult.revisionId(),
              buildTargetMetadata(preparationResult.targetClient()),
              null);
      Map<String, Object> relaunchedMetadata = readTaskMetadata(relaunched);
      mergeTargetPreparationMetadata(relaunchedMetadata, preparationResult.metadata());
      relaunched.setTaskMetadataJson(objectMapper.valueToTree(relaunchedMetadata));
      persistenceService.saveIteration(relaunched);
      ScriptExecutionService.Micro6PublishResult publishResult = publishIteration(relaunched);
      updateRecoveryState(
          iteration,
          taskMetadata,
          RECOVERY_RELAUNCHED,
          "Patched task republished to " + preparationResult.targetClient().accountName());

      Map<String, Object> response = new LinkedHashMap<>();
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
    }
  }

  @Scheduled(fixedDelayString = "${builder-goal.status-sync-interval-ms:3000}")
  public void syncPublishedRuns() {
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(iteration -> iteration.getStatus() != null && iteration.getStatus().isActive())
        .forEach(this::syncPublishedRunSafely);
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
                    "Task failed. Add your observations, select the relevant evidence, and generate a patch plan."));
  }

  private PatchLoopIteration createRunIteration(
      NormalizedPayload normalized,
      JsonNode importedPayload,
      String parentIterationId,
      String revisionId,
      Map<String, Object> targetMetadata,
      JsonNode catalogTask) {
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
        objectMapper.valueToTree(buildTaskMetadata(normalized, targetMetadata, catalogTask)));
    iteration.setPromptType(PromptType.ISSUE.name());
    if (revisionId != null && !revisionId.isBlank()) {
      iteration.setMicro6RevisionId(revisionId);
      iteration.setMicro6PatchedAt(LocalDateTime.now());
    }
    return iterationRepository.save(iteration);
  }

  private ScriptExecutionService.Micro6PublishResult publishIteration(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    ScriptExecutionService.Micro6PublishResult publishResult =
        scriptExecutionService.publishMicro6Task("micro6:" + iteration.getScriptName(), taskMetadata);
    applyPublishResult(iteration, taskMetadata, publishResult);
    return publishResult;
  }

  private void applyPublishResult(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6PublishResult publishResult) {
    Map<String, Object> publishMetadata =
        publishResult.published()
            ? buildPublishMetadata(publishResult, LocalDateTime.now())
            : buildPublishFailureMetadata(publishResult, LocalDateTime.now());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));

    if (publishResult.published()) {
      if (iteration.getRunStartTime() == null) {
        iteration.setRunStartTime(LocalDateTime.now());
      }
      iteration.setRunSuccess(null);
      iteration.setRunEndTime(null);
      persistenceService.saveIteration(iteration);
      return;
    }

    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(false);
    persistenceService.markIterationFailed(
        iteration,
        PatchLoopFailureCode.INTERNAL_ERROR,
        firstNonBlank(publishResult.errorMessage(), "Failed to publish task to Micro6"),
        "publishStatus=" + publishResult.publishStatus());
  }

  private void syncPublishedRunSafely(PatchLoopIteration iteration) {
    try {
      syncPublishedRun(iteration);
    } catch (Exception e) {
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.INTERNAL_ERROR,
          "Builder goal status sync failed: " + e.getMessage(),
          stackTrace(e));
    }
  }

  private void syncPublishedRun(PatchLoopIteration iteration) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId == null || entryId <= 0) {
      return;
    }

    ScriptExecutionService.Micro6RunStatusResult statusResult =
        scriptExecutionService.getMicro6RunStatus(taskMetadata, entryId);
    if (!statusResult.found()) {
      recordStatusPollFailure(iteration, taskMetadata, publishMetadata, statusResult.errorMessage());
      if (shouldFailForLostTarget(iteration, publishMetadata)) {
        finalizeLostTargetRun(
            iteration,
            firstNonBlank(
                statusResult.errorMessage(),
                "Micro6 target became unreachable during published run"));
      }
      return;
    }

    String micro6Status = firstNonBlank(statusResult.status(), "UNKNOWN");
    String mappedStatus = mapPublishStatus(micro6Status);
    boolean metadataChanged = false;
    if (!mappedStatus.equals(stringValue(publishMetadata.get("status")))) {
      publishMetadata.put("status", mappedStatus);
      publishMetadata.put("last_status_at", LocalDateTime.now().toString());
      if (statusResult.errorMessage() != null && !statusResult.errorMessage().isBlank()) {
        publishMetadata.put("last_error", statusResult.errorMessage());
      }
      metadataChanged = true;
    }
    metadataChanged |= recordSuccessfulStatusPoll(taskMetadata, publishMetadata);
    if (metadataChanged) {
      taskMetadata.put("micro6_publish", publishMetadata);
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      persistenceService.saveIteration(iteration);
    }

    if ("RUNNING".equalsIgnoreCase(micro6Status) || "PAUSED".equalsIgnoreCase(micro6Status)) {
      if (iteration.getStatus() == PatchLoopStatus.QUEUED) {
        persistenceService.updateIterationStatus(iteration, PatchLoopStatus.RUNNING_SCRIPT);
      }
      return;
    }
    if ("PENDING".equalsIgnoreCase(micro6Status)) {
      return;
    }

    if ("COMPLETED".equalsIgnoreCase(micro6Status)) {
      finalizePublishedRun(iteration, true, null);
      return;
    }

    if ("FAILED".equalsIgnoreCase(micro6Status) || "CANCELLED".equalsIgnoreCase(micro6Status)) {
      finalizePublishedRun(
          iteration,
          false,
          firstNonBlank(statusResult.errorMessage(), micro6Status));
    }
  }

  private void finalizePublishedRun(
      PatchLoopIteration iteration, boolean success, String failureReason) {
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(success);
    resolveRunSession(iteration);
    tryCollectEvidence(iteration);

    if (success) {
      iteration.setFailureCode(null);
      iteration.setFailureReason(null);
      persistenceService.saveIteration(iteration);
      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
      return;
    }

    persistenceService.markIterationFailed(
        iteration,
        PatchLoopFailureCode.SCRIPT_EXCEPTION,
        firstNonBlank(failureReason, "Micro6 task failed"),
        "publishStatus="
            + stringValue(readPublishMetadata(readTaskMetadata(iteration)).get("status")));
    moveToAwaitingOperatorPrompt(
        iteration,
        "Task failed. Add your observations, select the relevant evidence, and generate a patch plan.");
  }

  private Map<String, Object> buildImportResponse(NormalizedPayload normalized, JsonNode catalogTask) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("title", normalized.displayName());
    response.put("taskKey", normalized.taskKey());
    response.put("settings", normalized.settings());
    response.put("settingsCount", normalized.settings().size());

    if (catalogTask == null || catalogTask.isMissingNode() || catalogTask.isNull()) {
      response.put("catalogAvailable", false);
      response.put("runnableNow", false);
      response.put("error", "Task was not found in the Micro6 catalog");
      response.put("matchedSettingsCount", 0);
      response.put("ignoredSettingsCount", normalized.settings().size());
      response.put("matchedSettings", List.of());
      response.put("ignoredSettings", new ArrayList<>(normalized.settings().keySet()));
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

    response.put("catalogAvailable", true);
    response.put("runnableNow", catalogTask.path("runnable").asBoolean(true));
    response.put("displayName", catalogTask.path("display_name").asText(normalized.displayName()));
    response.put("panelDisplayName", catalogTask.path("panel_display_name").asText(normalized.displayName()));
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
    response.put("taskKey", normalized.taskKey());
    response.put("displayName", normalized.displayName());
    response.put("settings", normalized.settings());
    response.put("source", stringValue(taskMetadata.get("source")));
    response.put("iterationId", iteration.getIterationId());
    response.put("createdAt", iteration.getCreatedAt());
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

  private String determineRerunBlockedReason(PatchLoopIteration iteration) {
    if (!hasStoredConfig(iteration)) {
      return "This run does not have a saved launch config";
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
    if (!selectedAccount.get().enabled()) {
      return "Saved launcher account is disabled in launcher control";
    }
    if (isBannedStatus(selectedAccount.get().status())) {
      return "Saved launcher account is banned in launcher control";
    }
    try {
      BuilderGoalLauncherControlService.LauncherMachine machine = resolveSelectedMachine(launcherMachineUuid);
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
        : taskKey.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
  }

  private PatchLoopIteration loadBuilderRun(String iterationId) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationId(iterationId)
            .orElseThrow(() -> new IllegalArgumentException("Builder goal run not found: " + iterationId));
    if (!SOURCE_TYPE_BUILDER_GOAL.equals(iteration.getSourceType())) {
      throw new IllegalArgumentException("Iteration is not a builder goal run: " + iterationId);
    }
    return iteration;
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
    return findTaskInCatalog(
        taskKey,
        micro6ExecutorCatalogService.getCatalog(false));
  }

  private JsonNode findTaskInCatalog(String taskKey, JsonNode catalog) {
    if (catalog == null || catalog.isNull()) {
      return null;
    }
    JsonNode tasks = catalog.path("tasks");
    if (!tasks.isArray()) {
      return null;
    }

    String normalizedTarget = normalizeTaskKey(taskKey);
    for (JsonNode task : tasks) {
      String candidate = task.path("task_key").asText(null);
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
    return new NormalizedPayload(iteration.getScriptName(), displayName, new LinkedHashMap<>(settings));
  }

  private Map<String, Object> readTaskMetadata(PatchLoopIteration iteration) {
    if (iteration.getTaskMetadataJson() == null || iteration.getTaskMetadataJson().isNull()) {
      return new LinkedHashMap<>();
    }
    return objectMapper.convertValue(
        iteration.getTaskMetadataJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private Map<String, Object> buildTaskMetadata(
      NormalizedPayload normalized, Map<String, Object> targetMetadata, JsonNode catalogTask) {
    Map<String, Object> metadata = new LinkedHashMap<>();
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
    if (targetMetadata != null && !targetMetadata.isEmpty()) {
      metadata.put("micro6_target", targetMetadata);
    }
    return metadata;
  }

  private CatalogValidationResult resolvePublishCatalogValidation(String taskKey, String targetBaseUrl) {
    JsonNode targetCatalog = micro6ExecutorCatalogService.peekCatalogForBaseUrl(targetBaseUrl);
    JsonNode targetTask = findTaskInCatalog(taskKey, targetCatalog);
    if (targetTask != null) {
      if (!targetTask.path("runnable").asBoolean(true)) {
        return CatalogValidationResult.reject(
            "REJECTED_CACHED_MISS",
            "Task is not runnable in cached Micro6 catalog: "
                + taskKey
                + " ("
                + targetTask.path("disabled_reason").asText("unknown")
                + ")",
            targetTask);
      }
      return CatalogValidationResult.validated("VALIDATED_CACHED_TARGET", targetTask);
    }

    JsonNode cachedDefaultCatalog = micro6ExecutorCatalogService.peekCatalog();
    JsonNode cachedDefaultTask = findTaskInCatalog(taskKey, cachedDefaultCatalog);
    if (cachedDefaultTask != null) {
      if (!cachedDefaultTask.path("runnable").asBoolean(true)) {
        return CatalogValidationResult.reject(
            "REJECTED_CACHED_MISS",
            "Task is not runnable in cached Micro6 catalog: "
                + taskKey
                + " ("
                + cachedDefaultTask.path("disabled_reason").asText("unknown")
                + ")",
            cachedDefaultTask);
      }
      return CatalogValidationResult.validated("VALIDATED_CACHED", cachedDefaultTask);
    }

    boolean hadCachedCatalog =
        hasCatalogPayload(targetCatalog) || hasCatalogPayload(cachedDefaultCatalog);
    if (hadCachedCatalog) {
      return CatalogValidationResult.reject(
          "REJECTED_CACHED_MISS",
          "Task is not available in cached Micro6 catalog: " + taskKey,
          null);
    }

    return CatalogValidationResult.skipped("SKIPPED_NO_CACHE");
  }

  private boolean hasCatalogPayload(JsonNode catalog) {
    return catalog != null && !catalog.isNull() && catalog.path("tasks").isArray();
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
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private NormalizedPayload normalizePayload(JsonNode payload) {
    if (payload == null || payload.isNull() || !payload.isObject()) {
      throw new IllegalArgumentException("Builder payload must be a JSON object");
    }

    JsonNode builderExecution = objectNode(payload.path("builder_execution"));
    JsonNode queuePreview = objectNode(payload.path("queue_payload_preview"));
    JsonNode builderMetadata = objectNode(builderExecution.path("task_metadata"));
    JsonNode queueMetadata = objectNode(queuePreview.path("task_metadata"));

    String taskKey =
        firstNonBlank(
            textValue(builderExecution.path("task_key")),
            textValue(queuePreview.path("task_key")),
            textValue(payload.path("task_key")));
    if (taskKey == null) {
      throw new IllegalArgumentException("Builder payload is missing task_key");
    }

    JsonNode settingsNode =
        builderMetadata.isObject()
            ? builderMetadata
            : queueMetadata.isObject() ? queueMetadata : objectNode(payload.path("settings"));
    if (!settingsNode.isObject()) {
      settingsNode = payload;
    }

    Map<String, Object> settings =
        objectMapper.convertValue(
            settingsNode, new TypeReference<LinkedHashMap<String, Object>>() {});

    String displayName =
        firstNonBlank(
            textValue(payload.path("node_context").path("title")),
            textValue(queuePreview.path("title")),
            textValue(queuePreview.path("name")),
            textValue(payload.path("title")),
            taskKey);

    return new NormalizedPayload(taskKey, displayName, settings);
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
      context = context + " Debug session context: " + objectMapper.valueToTree(sessionContext.get());
    }
    return new AnalysisPromptData(prompt, context);
  }

  private void updateRecoveryState(
      PatchLoopIteration iteration, Map<String, Object> taskMetadata, String phase, String message) {
    Map<String, Object> recovery = new LinkedHashMap<>();
    recovery.put("phase", phase);
    recovery.put("message", message);
    recovery.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("recovery", recovery);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private void updatePreparationState(
      PatchLoopIteration iteration, Map<String, Object> taskMetadata, String state, String message) {
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
    Integer requestedStart = intValue(payload != null ? payload.get("timeRangeStartSeconds") : null);
    Integer requestedEnd = intValue(payload != null ? payload.get("timeRangeEndSeconds") : null);
    int startSeconds =
        clampRangeValue(
            requestedStart != null ? requestedStart : existingInput.timeRangeStartSeconds(), 0, maxSeconds);
    int endSeconds =
        clampRangeValue(
            requestedEnd != null ? requestedEnd : existingInput.timeRangeEndSeconds(), maxSeconds, maxSeconds);
    if (endSeconds <= startSeconds) {
      endSeconds = maxSeconds;
      startSeconds = 0;
    }
    return new TimeRangeSelection(startSeconds, endSeconds, maxSeconds);
  }

  private int computeMaxRangeSeconds(AnalysisV1EvidenceBundle evidence) {
    if (evidence == null || evidence.getTimeWindow() == null || !evidence.getTimeWindow().isValid()) {
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
    LocalDateTime absoluteStart = evidence.getTimeWindow().getStart().plusSeconds(selection.startSeconds());
    LocalDateTime absoluteEnd = evidence.getTimeWindow().getStart().plusSeconds(selection.endSeconds());

    AnalysisV1EvidenceBundle clipped = new AnalysisV1EvidenceBundle();
    clipped.setScriptName(evidence.getScriptName());
    clipped.setTimeWindow(new com.chromascape.web.debug.patchloop.dto.TimeWindow(absoluteStart, absoluteEnd));
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
        clipped.setTickWindow(new com.chromascape.web.debug.patchloop.dto.TickWindow(minTick, maxTick));
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

  private boolean recordSuccessfulStatusPoll(
      Map<String, Object> taskMetadata, Map<String, Object> publishMetadata) {
    String now = LocalDateTime.now().toString();
    boolean changed = false;
    changed |= putIfChanged(publishMetadata, "last_poll_at", now);
    changed |= putIfChanged(publishMetadata, "last_successful_poll_at", now);
    changed |= putIfChanged(publishMetadata, "consecutive_poll_failures", 0);
    if (publishMetadata.remove("last_error") != null) {
      changed = true;
    }
    if (changed) {
      taskMetadata.put("micro6_publish", publishMetadata);
    }
    return changed;
  }

  private void recordStatusPollFailure(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      Map<String, Object> publishMetadata,
      String errorMessage) {
    int failures = Math.max(0, intValue(publishMetadata.get("consecutive_poll_failures")) == null
            ? 0
            : intValue(publishMetadata.get("consecutive_poll_failures")))
        + 1;
    publishMetadata.put("consecutive_poll_failures", failures);
    publishMetadata.put("last_poll_at", LocalDateTime.now().toString());
    if (errorMessage != null && !errorMessage.isBlank()) {
      publishMetadata.put("last_error", errorMessage);
    }
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  private boolean shouldFailForLostTarget(
      PatchLoopIteration iteration, Map<String, Object> publishMetadata) {
    Integer failures = intValue(publishMetadata.get("consecutive_poll_failures"));
    if (failures == null || failures < Math.max(1, maxConsecutiveStatusFailures)) {
      return false;
    }
    LocalDateTime reference =
        parseLocalDateTime(stringValue(publishMetadata.get("last_successful_poll_at")));
    if (reference == null) {
      reference = parseLocalDateTime(stringValue(publishMetadata.get("published_at")));
    }
    if (reference == null) {
      reference = iteration.getRunStartTime();
    }
    if (reference == null) {
      reference = iteration.getCreatedAt();
    }
    return reference != null
        && Duration.between(reference, LocalDateTime.now()).toMillis()
            >= Math.max(1_000L, statusFailureGraceMs);
  }

  private void finalizeLostTargetRun(PatchLoopIteration iteration, String failureReason) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    publishMetadata.put("status", "FAILED");
    publishMetadata.put("last_status_at", LocalDateTime.now().toString());
    publishMetadata.put("last_error", failureReason);
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(false);
    resolveRunSession(iteration);
    tryCollectEvidence(iteration);
    persistenceService.markIterationFailed(
        iteration,
        PatchLoopFailureCode.TARGET_UNREACHABLE,
        firstNonBlank(failureReason, "Micro6 target became unreachable during published run"),
        "publishEntryId=" + intValue(publishMetadata.get("entry_id")));
    moveToAwaitingOperatorPrompt(
        iteration,
        "Target became unreachable. Add your observations, select the relevant evidence, and generate a patch plan.");
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
    String displayStatus = deriveDisplayStatus(iteration, publishStatus);
    boolean analysisAvailable =
        iteration.getAnalysisResponseJson() != null && !iteration.getAnalysisResponseJson().isNull();
    boolean patchDraftAvailable = iteration.getPatchId() != null && !iteration.getPatchId().isBlank();
    boolean approvalRequired =
        iteration.getStatus() == PatchLoopStatus.FAILED
            && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus);
    boolean approvalAvailable =
        approvalRequired
            && analysisAvailable
            && patchDraftAvailable
            && RECOVERY_PATCH_PLAN_READY.equals(stringValue(recoveryMetadata.get("phase")));
    boolean canAnalyze =
        iteration.getStatus() == PatchLoopStatus.FAILED && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus);
    boolean canFixAndRelaunch = approvalAvailable;
    boolean configAvailable = hasStoredConfig(iteration);
    String rerunBlockedReason = determineRerunBlockedReason(iteration);
    boolean canRerun = rerunBlockedReason == null;
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("iterationId", iteration.getIterationId());
    response.put("status", displayStatus);
    response.put("iterationStatus", iteration.getStatus() != null ? iteration.getStatus().name() : null);
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
    response.put("runSuccess", iteration.getRunSuccess());
    response.put("failureCode", iteration.getFailureCode());
    response.put("failureReason", iteration.getFailureReason());
    response.put("publishStatus", publishStatus);
    response.put("publishEntryId", intValue(publishMetadata.get("entry_id")));
    response.put("publishAttachedToExisting", Boolean.TRUE.equals(publishMetadata.get("attached_to_existing")));
    response.put("publishedAt", stringValue(publishMetadata.get("published_at")));
    response.put("publishError", stringValue(publishMetadata.get("last_error")));
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
    response.put(
        "accountName",
        firstNonBlank(
            stringValue(accountMetadata.get("account_name")),
            stringValue(targetMetadata.get("account_name"))));
    response.put("accountSource", stringValue(accountMetadata.get("source")));
    response.put("accountOnline", Boolean.TRUE.equals(accountMetadata.get("online")));
    response.put("accountRunnable", Boolean.TRUE.equals(accountMetadata.get("runnable")));
    response.put("accountDisabledReason", stringValueAllowBlank(accountMetadata.get("disabled_reason")));
    response.put("configAvailable", configAvailable);
    response.put("canRerun", canRerun);
    response.put("rerunBlockedReason", rerunBlockedReason);
    response.put("targetClientId", stringValue(targetMetadata.get("client_id")));
    response.put("targetAccountName", stringValue(targetMetadata.get("account_name")));
    response.put("targetBaseUrl", stringValue(targetMetadata.get("advertised_base_url")));
    response.put("targetStatus", stringValue(targetMetadata.get("status")));
    response.put("agentServerBaseUrl", stringValue(targetPreparationMetadata.get("agent_server_base_url")));
    response.put("launchProfileName", null);
    response.put("launchArgsPreview", stringValue(targetPreparationMetadata.get("launch_args_preview")));
    response.put("launchArgsConfigured", targetPreparationMetadata.get("launch_args_preview") != null);
    response.put("launcherMachineUuid", firstNonBlank(
        stringValue(targetPreparationMetadata.get("launcher_machine_uuid")),
        stringValue(accountMetadata.get("launcher_machine_uuid"))));
    response.put("launcherMachineName", firstNonBlank(
        stringValue(targetPreparationMetadata.get("launcher_machine_name")),
        stringValue(accountMetadata.get("launcher_machine_name"))));
    response.put("launcherBaseUrl", firstNonBlank(
        stringValue(targetPreparationMetadata.get("launcher_base_url")),
        stringValue(accountMetadata.get("launcher_base_url"))));
    response.put("launcherCommandId", stringValue(targetPreparationMetadata.get("launcher_command_id")));
    response.put("launcherCommandStatus", stringValue(targetPreparationMetadata.get("launcher_command_status")));
    response.put("launchMode", stringValue(targetPreparationMetadata.get("launch_mode")));
    response.put("launcherPid", longValue(targetPreparationMetadata.get("launcher_pid")));
    response.put("launchLogFile", stringValue(targetPreparationMetadata.get("launcher_log_file")));
    response.put("targetPreparationState", stringValue(targetPreparationMetadata.get("state")));
    response.put("targetPreparationMessage", stringValueAllowBlank(targetPreparationMetadata.get("message")));
    response.put("analysisCustomized", analysisInputMetadata.customized());
    response.put("analysisMode", analysisInputMetadata.mode());
    response.put("analysisSelectedEvidenceCount", analysisInputMetadata.selectedEvidenceCount());
    response.put("analysisTotalEvidenceCount", analysisInputMetadata.totalEvidenceCount());
    response.put("analysisTimeRangeStartSeconds", analysisInputMetadata.timeRangeStartSeconds());
    response.put("analysisTimeRangeEndSeconds", analysisInputMetadata.timeRangeEndSeconds());
    response.put("recoveryPhase", stringValue(recoveryMetadata.get("phase")));
    response.put("recoveryMessage", stringValueAllowBlank(recoveryMetadata.get("message")));
    response.put("recoveryUpdatedAt", stringValue(recoveryMetadata.get("updated_at")));
    response.put("lastSuccessfulPollAt", stringValue(publishMetadata.get("last_successful_poll_at")));
    response.put("lastPollAt", stringValue(publishMetadata.get("last_poll_at")));
    response.put("consecutivePollFailures", intValue(publishMetadata.get("consecutive_poll_failures")));
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
    return response;
  }

  private String determineAnalyzeBlockedReason(PatchLoopIteration iteration, String publishStatus) {
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return "Run must fail before it can be analyzed";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "Publish failed; there is no task run to analyze";
    }
    return null;
  }

  private String determineApprovalBlockedReason(
      PatchLoopIteration iteration,
      String publishStatus,
      Map<String, Object> recoveryMetadata) {
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return "Run must fail before it can be recovered";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "Publish failed; there is no runnable task instance to relaunch";
    }
    if (iteration.getAnalysisResponseJson() == null || iteration.getAnalysisResponseJson().isNull()) {
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
                && (timeRange.startSeconds() > 0 || timeRange.endSeconds() < timeRange.maxSeconds()));
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
    List<Map<String, Object>> decisionItems = (List<Map<String, Object>>) decisionsGroup.get("items");

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
        default -> {
        }
      }
      if (!selected) {
        continue;
      }
      switch (item.groupKey()) {
        case "logs" -> selectedLogs.add((AnalysisV1EvidenceBundle.LogEvidence) item.source());
        case "tickLogs" ->
            selectedTickLogs.add((AnalysisV1EvidenceBundle.TickLogEvidence) item.source());
        case "decisions" ->
            selectedDecisions.add((AnalysisV1EvidenceBundle.DecisionEvidence) item.source());
        default -> {
        }
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
              "%s • %s • %s", safe(log.getTimestamp()), safe(log.getLevel()), safe(log.getSource())));
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
    target.put("advertised_base_url", targetClient.advertisedBaseUrl());
    target.put("executor_bind_addr", targetClient.executorBindAddr());
    target.put("source_client_type", targetClient.sourceClientType());
    target.put("executor_profile", targetClient.executorProfile());
    target.put("status", targetClient.status());
    target.put("current_task", targetClient.currentTask());
    return target;
  }

  private Map<String, Object> buildRequestedTargetMetadataFromAccount(
      BuilderGoalAccountCatalogService.AccountDefinition account,
      BuilderGoalLauncherControlService.LauncherMachine machine) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("requested_account_id", account.accountId());
    target.put("requested_account_hash", account.accountHash());
    target.put("requested_account_name", account.displayName());
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
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("account_id", account.accountId());
    metadata.put("account_hash", account.accountHash());
    metadata.put("account_name", account.displayName());
    metadata.put("display_name", account.displayName());
    metadata.put("email", account.email());
    metadata.put("has_password", account.password() != null);
    metadata.put("source", account.source());
    metadata.put("enabled", account.enabled());
    metadata.put("status", account.status());
    metadata.put("launcher_machine_uuid", machine != null ? machine.machineUuid() : null);
    metadata.put("launcher_machine_name", machine != null ? machine.displayName() : null);
    metadata.put("launcher_base_url", account.metadata().get("launcherBaseUrl"));
    metadata.put("online", account.metadata().get("online"));
    metadata.put("runnable", account.metadata().get("runnable"));
    metadata.put("disabled_reason", account.metadata().get("disabledReason"));
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
    return accountCatalogService.findAccount(accountId).map(BuilderGoalAccountCatalogService.AccountDefinition::password).orElse(null);
  }

  private BuilderGoalLauncherControlService.LauncherMachine resolveSelectedMachine(String machineUuid) {
    String normalizedMachineUuid = normalizeOptionalString(machineUuid);
    if (normalizedMachineUuid == null) {
      throw new IllegalArgumentException("Select a launcher machine first");
    }
    return launcherControlService.findMachineByUuid(normalizedMachineUuid)
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

  private BuilderGoalAccountCatalogService.AccountDefinition resolveSelectedAccount(String accountId) {
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
      ScriptExecutionService.Micro6PublishResult publishResult, LocalDateTime publishedAt) {
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("status", "PUBLISH_FAILED");
    publish.put("published_at", publishedAt.toString());
    publish.put("last_error", publishResult.errorMessage());
    publish.put("micro6_base_url", publishResult.micro6BaseUrl());
    return publish;
  }

  private String mapPublishStatus(String micro6Status) {
    if (micro6Status == null || micro6Status.isBlank()) {
      return "PUBLISHED";
    }
    if ("FAILED".equalsIgnoreCase(micro6Status) || "CANCELLED".equalsIgnoreCase(micro6Status)) {
      return "FAILED";
    }
    if ("COMPLETED".equalsIgnoreCase(micro6Status)) {
      return "COMPLETED";
    }
    if ("RUNNING".equalsIgnoreCase(micro6Status) || "PAUSED".equalsIgnoreCase(micro6Status)) {
      return "RUNNING";
    }
    return "PUBLISHED";
  }

  private String deriveDisplayStatus(PatchLoopIteration iteration, String publishStatus) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String preparationState = stringValue(readTargetPreparationMetadata(taskMetadata).get("state"));
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "PUBLISH_FAILED";
    }
    if (iteration.getStatus() == PatchLoopStatus.COMPLETED || Boolean.TRUE.equals(iteration.getRunSuccess())) {
      return "COMPLETED";
    }
    if (iteration.getStatus() == PatchLoopStatus.FAILED || Boolean.FALSE.equals(iteration.getRunSuccess())) {
      return "FAILED";
    }
    if (preparationState != null
        && (publishStatus == null || publishStatus.isBlank())
        && !PREPARATION_STATE_READY_TO_PUBLISH.equals(preparationState)) {
      return preparationState;
    }
    return firstNonBlank(publishStatus, iteration.getStatus() != null ? iteration.getStatus().name() : null, "QUEUED");
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

  private record NormalizedPayload(
      String taskKey, String displayName, Map<String, Object> settings) {}

  private record AnalysisPromptData(String prompt, String context) {}

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
      JsonNode catalogTask) {

    private static CatalogValidationResult validated(String status, JsonNode catalogTask) {
      return new CatalogValidationResult(status, false, false, null, catalogTask);
    }

    private static CatalogValidationResult skipped(String status) {
      return new CatalogValidationResult(status, true, false, null, null);
    }

    private static CatalogValidationResult reject(
        String status, String errorMessage, JsonNode catalogTask) {
      return new CatalogValidationResult(status, false, true, errorMessage, catalogTask);
    }
  }
}
