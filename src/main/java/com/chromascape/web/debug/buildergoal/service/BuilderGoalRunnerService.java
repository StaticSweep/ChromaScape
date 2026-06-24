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

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopPersistenceService persistenceService;
  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopSessionResolverService sessionResolverService;
  private final ScriptExecutionService scriptExecutionService;
  private final AnalysisProvider analysisProvider;
  private final Micro6ExecutorCatalogService micro6ExecutorCatalogService;
  private final Micro6OnlineClientRegistryService clientRegistryService;
  private final Micro6TaskSourcePatchService micro6TaskSourcePatchService;
  private final ObjectMapper objectMapper;
  private final Executor patchLoopExecutor;
  private final long runTimeoutMs;

  public BuilderGoalRunnerService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopPersistenceService persistenceService,
      PatchLoopEvidenceService evidenceService,
      PatchLoopSessionResolverService sessionResolverService,
      ScriptExecutionService scriptExecutionService,
      AnalysisProvider analysisProvider,
      Micro6ExecutorCatalogService micro6ExecutorCatalogService,
      Micro6OnlineClientRegistryService clientRegistryService,
      Micro6TaskSourcePatchService micro6TaskSourcePatchService,
      ObjectMapper objectMapper,
      @Qualifier("patchLoopExecutor") Executor patchLoopExecutor,
      @Value("${builder-goal.run-timeout-ms:300000}") long runTimeoutMs) {
    this.iterationRepository = iterationRepository;
    this.persistenceService = persistenceService;
    this.evidenceService = evidenceService;
    this.sessionResolverService = sessionResolverService;
    this.scriptExecutionService = scriptExecutionService;
    this.analysisProvider = analysisProvider;
    this.micro6ExecutorCatalogService = micro6ExecutorCatalogService;
    this.clientRegistryService = clientRegistryService;
    this.micro6TaskSourcePatchService = micro6TaskSourcePatchService;
    this.objectMapper = objectMapper;
    this.patchLoopExecutor = patchLoopExecutor;
    this.runTimeoutMs = runTimeoutMs;
  }

  public Map<String, Object> importPayload(JsonNode payload) {
    NormalizedPayload normalized = normalizePayload(payload);
    JsonNode catalogTask = resolveCatalogTask(normalized.taskKey());
    return buildImportResponse(normalized, catalogTask);
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
    SelectionResult selectionResult =
        buildSelectionResult(evidence, existingInput.selectedEvidenceIds(), true);

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
  public Map<String, Object> startRun(JsonNode payload, String targetClientId) {
    if (targetClientId == null || targetClientId.isBlank()) {
      throw new IllegalArgumentException("targetClientId is required");
    }
    NormalizedPayload normalized = normalizePayload(payload);
    Micro6OnlineClientRegistryService.TargetClient targetClient =
        clientRegistryService
            .getRunnableClient(targetClientId)
            .orElseThrow(() -> new IllegalStateException("Selected Micro6 client is offline or stale"));
    CatalogValidationResult catalogValidation =
        resolvePublishCatalogValidation(normalized.taskKey(), targetClient.advertisedBaseUrl());
    if (catalogValidation.rejectPublish()) {
      throw new IllegalArgumentException(catalogValidation.errorMessage());
    }

    PatchLoopIteration iteration =
        createRunIteration(
            normalized,
            payload,
            null,
            null,
            buildTargetMetadata(targetClient),
            catalogValidation.catalogTask());
    applyCatalogValidationMetadata(iteration, catalogValidation);
    ScriptExecutionService.Micro6PublishResult publishResult = publishIteration(iteration);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("run", toRunSummary(iteration));
    response.put("import", buildImportResponse(normalized, catalogValidation.catalogTask()));
    response.put("published", publishResult.published());
    response.put("entryId", publishResult.entryId());
    response.put("targetClientId", targetClient.clientId());
    response.put("targetAccountName", targetClient.accountName());
    response.put("publishStatus", publishResult.publishStatus());
    response.put("publishError", publishResult.errorMessage());
    response.put("catalogValidation", catalogValidation.status());
    response.put("catalogValidationSkipped", catalogValidation.skipped());
    return response;
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId) throws Exception {
    return analyzeFailure(iterationId, null);
  }

  @Transactional
  public Map<String, Object> analyzeFailure(String iterationId, JsonNode payload) throws Exception {
    PatchLoopIteration iteration = loadBuilderRun(iterationId);
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    String publishStatus = stringValue(readPublishMetadata(taskMetadata).get("status"));
    String blockedReason = determineAnalyzeBlockedReason(iteration, publishStatus);
    if (blockedReason != null) {
      throw new IllegalStateException(blockedReason);
    }

    AnalysisV1EvidenceBundle evidence = evidenceService.collectEvidence(iteration, null);
    AnalysisPromptData defaults = buildDefaultAnalysisPromptData(iteration);
    AnalysisInputMetadata existingInput = readAnalysisInputMetadata(taskMetadata);

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
        buildSelectionResult(evidence, requestedIds, defaultSelectAll);
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
        defaults);
    persistenceService.saveAnalysisRequest(iteration, objectMapper.valueToTree(request));
    AnalysisV2Response response = analysisProvider.analyzeEvidence(request);
    if (response.getStatus() == AnalysisStatus.FAILURE) {
      String errorMessage =
          response.getError() != null ? response.getError().getMessage() : "Analysis failed";
      throw new IllegalStateException(errorMessage);
    }

    persistenceService.saveAnalysisResults(
        iteration, response, objectMapper.valueToTree(response));
    return toRunSummary(iterationRepository.findByIterationId(iterationId).orElseThrow());
  }

  @Transactional
  public Map<String, Object> fixAndRelaunch(String iterationId) throws Exception {
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

    Micro6TaskSourcePatchService.PatchApplyResult patchResult =
        micro6TaskSourcePatchService.applyPatch(
            resolveTargetBaseUrl(taskMetadata), taskKey, iteration.getIterationId(), patchDraft);
    iteration.setMicro6RevisionId(patchResult.revisionId());
    iteration.setMicro6PatchedAt(LocalDateTime.now());
    persistenceService.saveIteration(iteration);

    JsonNode importedPayload = iteration.getImportedPayloadJson();
    NormalizedPayload normalized = normalizeStoredIteration(iteration);
    PatchLoopIteration relaunched =
        createRunIteration(
            normalized,
            importedPayload,
            iteration.getIterationId(),
            patchResult.revisionId(),
            readTargetMetadata(taskMetadata),
            null);
    ScriptExecutionService.Micro6PublishResult publishResult = publishIteration(relaunched);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("patched", true);
    response.put("patchId", patchDraft.getPatchId());
    response.put("revisionId", patchResult.revisionId());
    response.put("message", patchResult.message());
    response.put("published", publishResult.published());
    response.put("publishStatus", publishResult.publishStatus());
    response.put("publishError", publishResult.errorMessage());
    response.put("run", toRunSummary(relaunched));
    return response;
  }

  @Scheduled(fixedDelayString = "${builder-goal.status-sync-interval-ms:3000}")
  public void syncPublishedRuns() {
    iterationRepository.findBySourceTypeOrderByCreatedAtDesc(SOURCE_TYPE_BUILDER_GOAL).stream()
        .filter(iteration -> iteration.getStatus() != null && iteration.getStatus().isActive())
        .forEach(this::syncPublishedRunSafely);
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
      return;
    }

    String micro6Status = firstNonBlank(statusResult.status(), "UNKNOWN");
    String mappedStatus = mapPublishStatus(micro6Status);
    if (!mappedStatus.equals(stringValue(publishMetadata.get("status")))) {
      publishMetadata.put("status", mappedStatus);
      publishMetadata.put("last_status_at", LocalDateTime.now().toString());
      if (statusResult.errorMessage() != null && !statusResult.errorMessage().isBlank()) {
        publishMetadata.put("last_error", statusResult.errorMessage());
      }
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
        "Analyze the failed builder goal task '"
            + firstNonBlank(displayName, taskKey, iteration.getScriptName())
            + "'";
    String context =
        "Patch target: "
            + firstNonBlank(iteration.getPatchTarget(), PATCH_TARGET_MICRO6_TASK_SOURCE)
            + ". Apply fixes against the Micro6 task source for task_key="
            + firstNonBlank(taskKey, iteration.getScriptName())
            + ". Failure reason: "
            + firstNonBlank(iteration.getFailureReason(), "unknown");

    Optional<com.chromascape.web.debug.patchloop.dto.SessionContextDto> sessionContext =
        sessionResolverService.readSessionContext(iteration);
    if (sessionContext.isPresent()) {
      context = context + " Debug session context: " + objectMapper.valueToTree(sessionContext.get());
    }
    return new AnalysisPromptData(prompt, context);
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
    Map<String, Object> targetMetadata = readTargetMetadata(taskMetadata);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    AnalysisInputMetadata analysisInputMetadata = readAnalysisInputMetadata(taskMetadata);
    String publishStatus = stringValue(publishMetadata.get("status"));
    String displayStatus = deriveDisplayStatus(iteration, publishStatus);
    boolean analysisAvailable =
        iteration.getAnalysisResponseJson() != null && !iteration.getAnalysisResponseJson().isNull();
    boolean patchDraftAvailable = iteration.getPatchId() != null && !iteration.getPatchId().isBlank();
    boolean canAnalyze =
        iteration.getStatus() == PatchLoopStatus.FAILED && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus);
    boolean canFixAndRelaunch =
        iteration.getStatus() == PatchLoopStatus.FAILED
            && !"PUBLISH_FAILED".equalsIgnoreCase(publishStatus)
            && analysisAvailable
            && patchDraftAvailable;
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
    response.put("targetClientId", stringValue(targetMetadata.get("client_id")));
    response.put("targetAccountName", stringValue(targetMetadata.get("account_name")));
    response.put("targetBaseUrl", stringValue(targetMetadata.get("advertised_base_url")));
    response.put("targetStatus", stringValue(targetMetadata.get("status")));
    response.put("analysisCustomized", analysisInputMetadata.customized());
    response.put("analysisSelectedEvidenceCount", analysisInputMetadata.selectedEvidenceCount());
    response.put("analysisTotalEvidenceCount", analysisInputMetadata.totalEvidenceCount());
    response.put("canAnalyze", canAnalyze);
    response.put("canFixAndRelaunch", canFixAndRelaunch);
    response.put("analyzeBlockedReason", determineAnalyzeBlockedReason(iteration, publishStatus));
    response.put(
        "fixBlockedReason",
        determineFixBlockedReason(iteration, publishStatus, analysisAvailable, patchDraftAvailable));
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

  private String determineFixBlockedReason(
      PatchLoopIteration iteration,
      String publishStatus,
      boolean analysisAvailable,
      boolean patchDraftAvailable) {
    if (iteration.getStatus() != PatchLoopStatus.FAILED) {
      return "Run must fail before it can be fixed";
    }
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "Publish failed; there is no runnable task instance to relaunch";
    }
    if (!analysisAvailable) {
      return "Analyze Failure must be run first";
    }
    if (!patchDraftAvailable) {
      return "Analysis did not produce a patch draft";
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private AnalysisInputMetadata readAnalysisInputMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return new AnalysisInputMetadata(null, null, List.of(), false, null, null);
    }
    Object value = taskMetadata.get("analysis_input");
    if (!(value instanceof Map<?, ?> mapValue)) {
      return new AnalysisInputMetadata(null, null, List.of(), false, null, null);
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
        intValue(metadata.get("selected_evidence_count")),
        intValue(metadata.get("total_evidence_count")));
  }

  private void updateAnalysisInputMetadata(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      String prompt,
      String context,
      List<String> selectedEvidenceIds,
      int selectedEvidenceCount,
      int totalEvidenceCount,
      AnalysisPromptData defaults) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("prompt", prompt);
    metadata.put("context", context);
    metadata.put("selected_evidence_ids", selectedEvidenceIds);
    metadata.put("selected_evidence_count", selectedEvidenceCount);
    metadata.put("total_evidence_count", totalEvidenceCount);
    boolean customized =
        !Objects.equals(prompt, defaults.prompt())
            || !Objects.equals(context, defaults.context())
            || selectedEvidenceCount != totalEvidenceCount;
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

  private String normalizeTaskKey(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "_");
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

  private String stringValueAllowBlank(Object value) {
    return value == null ? null : value.toString();
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
    if ("PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return "PUBLISH_FAILED";
    }
    if (iteration.getStatus() == PatchLoopStatus.COMPLETED || Boolean.TRUE.equals(iteration.getRunSuccess())) {
      return "COMPLETED";
    }
    if (iteration.getStatus() == PatchLoopStatus.FAILED || Boolean.FALSE.equals(iteration.getRunSuccess())) {
      return "FAILED";
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
      Integer selectedEvidenceCount,
      Integer totalEvidenceCount) {}

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
