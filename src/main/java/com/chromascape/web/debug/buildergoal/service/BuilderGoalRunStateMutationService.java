package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional mutation service for Builder Goal run state. Every public method acquires a
 * pessimistic write lock on the iteration row so publish/stop/recovery mutations are serialised and
 * never overwrite each other. Callers must NOT hold a pre-network snapshot of the metadata
 * document; the service reloads the latest persisted state inside the transaction.
 *
 * <p>This keeps locking logic out of {@link BuilderGoalRunnerService} so Spring transaction proxies
 * are effective.
 */
@Service
public class BuilderGoalRunStateMutationService {

  private static final Logger logger =
      LoggerFactory.getLogger(BuilderGoalRunStateMutationService.class);

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopPersistenceService persistenceService;
  private final ObjectMapper objectMapper;
  private final long runTimeoutMs;
  private final int maxConsecutiveStatusFailures;
  private final long statusFailureGraceMs;

  /** Convenience constructor retained for focused unit tests and callers that use defaults. */
  public BuilderGoalRunStateMutationService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopPersistenceService persistenceService,
      ObjectMapper objectMapper) {
    this(iterationRepository, persistenceService, objectMapper, 300_000L, 3, 15_000L);
  }

  @Autowired
  public BuilderGoalRunStateMutationService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopPersistenceService persistenceService,
      ObjectMapper objectMapper,
      @Value("${builder-goal.run-timeout-ms:300000}") long runTimeoutMs,
      @Value("${builder-goal.status-sync-max-consecutive-failures:3}")
          int maxConsecutiveStatusFailures,
      @Value("${builder-goal.status-sync-failure-grace-ms:15000}") long statusFailureGraceMs) {
    this.iterationRepository = iterationRepository;
    this.persistenceService = persistenceService;
    this.objectMapper = objectMapper;
    this.runTimeoutMs = Math.max(1L, runTimeoutMs);
    this.maxConsecutiveStatusFailures = Math.max(1, maxConsecutiveStatusFailures);
    this.statusFailureGraceMs = Math.max(1_000L, statusFailureGraceMs);
  }

  // ------------------------------------------------------------------
  // Public mutation API
  // ------------------------------------------------------------------

  /** Persists an auto-detected repair incident without replacing other task metadata partitions. */
  @Transactional
  public void recordRepairIncident(String iterationId, Map<String, Object> incident) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("repair_incident", deepCopy(incident));

    Object recoveryValue = taskMetadata.get("recovery");
    Map<String, Object> recovery =
        recoveryValue instanceof Map<?, ?>
            ? objectMapper.convertValue(
                recoveryValue, new TypeReference<LinkedHashMap<String, Object>>() {})
            : new LinkedHashMap<>();
    if (recovery.get("phase") == null) {
      recovery.put("phase", "AWAITING_OPERATOR_PROMPT");
      recovery.put("message", "Repair incident detected; review evidence before generating a patch plan");
      recovery.put("updated_at", LocalDateTime.now().toString());
      taskMetadata.put("recovery", recovery);
    }

    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  /** Records a patch-only export on the incident while preserving the active source worktree. */
  @Transactional
  public void recordRepairPatchExport(String iterationId, Map<String, Object> artifact) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Object value = taskMetadata.get("repair_incident");
    Map<String, Object> incident =
        value instanceof Map<?, ?>
            ? objectMapper.convertValue(
                value, new TypeReference<LinkedHashMap<String, Object>>() {})
            : new LinkedHashMap<>();
    incident.put("state", "PATCH_EXPORTED");
    incident.put("patch_export", deepCopy(artifact));
    incident.put("updated_at", LocalDateTime.now().toString());
    taskMetadata.put("repair_incident", incident);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    persistenceService.saveIteration(iteration);
  }

  /**
   * Reserves a publish slot for the given iteration. Locks the row, rejects terminal or already-
   * stopped runs, persists entry_id and PUBLISHING status, and returns the freshly-loaded metadata
   * snapshot so the caller can proceed with the remote execute call without holding a database
   * lock.
   *
   * @throws IllegalStateException if the run is terminal, stopped, or already reserved
   */
  @Transactional
  public PublishReservation reservePublish(
      String iterationId, int entryId, LocalDateTime reservedAt) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    PatchLoopStatus status = iteration.getStatus();
    if (status != null && status.isTerminal()) {
      throw new IllegalStateException(
          "Cannot reserve publish for terminal run " + iterationId + ": " + status);
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);

    if (isStopRequested(taskMetadata, publishMetadata)) {
      throw new IllegalStateException("Cannot reserve publish for stopped run " + iterationId);
    }

    Integer existingEntryId = intValue(publishMetadata.get("entry_id"));
    if (existingEntryId != null && existingEntryId > 0) {
      throw new IllegalStateException(
          "Publish already reserved for run " + iterationId + " (entry " + existingEntryId + ")");
    }

    // Persist the reservation inside the locked transaction.
    publishMetadata.put("entry_id", entryId);
    publishMetadata.put("status", "PUBLISHING");
    publishMetadata.put("published_at", reservedAt.toString());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);

    // Snapshot metadata before the caller mutates anything.
    return new PublishReservation(
        iterationId, iteration.getStatus(), deepCopy(taskMetadata), deepCopy(publishMetadata));
  }

  /**
   * Applies the outcome of a remote publish call to the iteration's metadata, merging only the
   * {@code micro6_publish} fields. All other metadata partitions (stop, recovery, preparation,
   * recording, evidence) are preserved exactly as they were persisted.
   *
   * <p>Stop precedence: if a stop was requested between reserve and apply, the publish status is
   * forced to STOPPING and the run is NOT marked failed. Terminal runs are never revived. Returns
   * whether the caller must issue executor cancellation.
   */
  @Transactional
  public ApplyOutcomeResult applyPublishOutcome(
      String iterationId, int entryId, ScriptExecutionService.Micro6PublishResult result) {
    return applyPublishOutcome(iterationId, entryId, result, null);
  }

  /**
   * Applies a publish result and, when supplied, the health snapshot taken after a failed publish
   * in the same locked transaction. Keeping the health merge here prevents a stale runner snapshot
   * from overwriting a concurrent stop or status-poll mutation.
   */
  @Transactional
  public ApplyOutcomeResult applyPublishOutcome(
      String iterationId,
      int entryId,
      ScriptExecutionService.Micro6PublishResult result,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    // Never revive a terminal/cancelled run.
    PatchLoopStatus status = iteration.getStatus();
    if (status != null && status.isTerminal()) {
      logger.info(
          "Publish outcome arrived for already-terminal run {} ({}); ignoring.",
          iterationId,
          status);
      return new ApplyOutcomeResult(
          iterationId, entryId, Map.of(), false, false, false, false, null);
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);

    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      throw new IllegalStateException(
          "Entry id mismatch for run "
              + iterationId
              + ": expected "
              + entryId
              + " but stored "
              + storedEntryId);
    }

    // Check stop before applying status — stop always wins.
    boolean stopRequested = isStopRequested(taskMetadata, publishMetadata);

    // Merge only the micro6_publish fields; leave every other partition intact.
    Map<String, Object> mergedPublish = deepCopy(publishMetadata);
    if (stopRequested) {
      mergedPublish.put("status", "STOPPING");
    } else {
      mergedPublish.put("status", result.publishStatus());
    }
    mergedPublish.put("attached_to_existing", result.attachedToExisting());
    mergedPublish.put("micro6_base_url", result.micro6BaseUrl());
    if (result.published()) {
      if (result.settingsReceipt() == null && requiresSettingsReceipt(taskMetadata)) {
        // Never fabricate executor acceptance metadata. A missing receipt is ambiguous and must be
        // reconciled through the authoritative Micro6 status path.
        mergedPublish.put("status", "RECONCILING");
        mergedPublish.put(
            "last_error", "Micro6 accepted a publish without a verifiable settings receipt");
      } else if (result.settingsReceipt() != null) {
        mergedPublish.put("settings_receipt", toSettingsReceiptMap(result.settingsReceipt()));
        mergedPublish.putIfAbsent("accepted_at", result.settingsReceipt().acceptedAt());
      }
    }
    if (result.executorStatus() != null) {
      mergedPublish.put("executor_status", result.executorStatus());
    }
    if (result.activationState() != null) {
      mergedPublish.put("activation_state", result.activationState());
    }
    if (result.errorMessage() != null) {
      mergedPublish.put("last_error", result.errorMessage());
    }
    taskMetadata.put("micro6_publish", mergedPublish);
    if (healthResult != null) {
      mergeHealthMetadata(taskMetadata, healthResult, result.errorMessage());
    }

    // Update run timestamps and success consistently.
    if (!stopRequested && "RECONCILING".equalsIgnoreCase(result.publishStatus())) {
      // Ambiguous timeout: never mark failed.
      iteration.setRunEndTime(null);
      iteration.setRunSuccess(null);
    } else if (!stopRequested
        && !result.published()
        && !"RECONCILING".equalsIgnoreCase(result.publishStatus())) {
      iteration.setRunEndTime(LocalDateTime.now());
      iteration.setRunSuccess(false);
    }

    // Persist the merged publish metadata before applying failure state so error
    // details are visible in the stored task metadata regardless of which path we take.
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);

    // Apply failure state atomically for non-reconciling publish failures.
    boolean becameTerminal = false;
    boolean applyFailureMetadata = false;
    if (!stopRequested
        && !result.published()
        && !"RECONCILING".equalsIgnoreCase(result.publishStatus())) {
      applyFailureMetadata = true;
      becameTerminal = true;
      iteration.setRunEndTime(LocalDateTime.now());
      iteration.setRunSuccess(false);
      String errorMsg =
          result.errorMessage() != null && !result.errorMessage().isBlank()
              ? result.errorMessage()
              : "Failed to publish task to Micro6";
      persistenceService.markIterationFailed(
          iteration,
          PatchLoopFailureCode.INTERNAL_ERROR,
          errorMsg,
          "publishStatus=" + result.publishStatus());
    }

    boolean needsCancel = stopRequested && storedEntryId != null && storedEntryId > 0;
    String finalPublishStatus = stringValue(mergedPublish.get("status"));
    return new ApplyOutcomeResult(
        iterationId,
        entryId,
        mergedPublish,
        stopRequested,
        needsCancel,
        becameTerminal,
        applyFailureMetadata,
        finalPublishStatus);
  }

  /**
   * Records a stop request for the iteration. If the run has an entry id, it is preserved so the
   * executor can still be cancelled / status-reconciled. Subsequent publish-outcome applies must
   * not overwrite the stop.
   *
   * <p>Returns the stored entry id (may be {@code null}) so the caller can issue an executor
   * cancel.
   */
  @Transactional
  public StopRequestResult requestStop(String iterationId, String reason) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    PatchLoopStatus status = iteration.getStatus();
    if (status != null && status.isTerminal()) {
      throw new IllegalStateException(
          "Run is already in a terminal state and cannot be stopped: " + iterationId);
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);

    String now = LocalDateTime.now().toString();
    String normalizedReason = reason != null ? reason : "Operator requested stop";
    taskMetadata.put("stop_requested_at", now);
    taskMetadata.put("stop_reason", normalizedReason);

    Integer preservedEntryId = intValue(publishMetadata.get("entry_id"));
    // When a publish entry exists, set the publish status to STOPPING so reconciliation
    // and status mapping reflect the stop rather than a stale publish status.
    if (preservedEntryId != null && preservedEntryId > 0) {
      publishMetadata.put("status", "STOPPING");
      publishMetadata.put("stop_requested_at", now);
      publishMetadata.put("stop_reason", normalizedReason);
      publishMetadata.put("last_status_at", now);
      taskMetadata.put("micro6_publish", publishMetadata);
    }

    boolean isPrePublish = preservedEntryId == null || preservedEntryId <= 0;
    if (isPrePublish) {
      // The pre-publish cancellation is deliberately part of this same locked transaction. A
      // separate cancel call would leave a window in which publish could reserve an entry after
      // stop had inspected the row.
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iteration.setRunEndTime(LocalDateTime.now());
      iteration.setRunSuccess(null);
      iteration.setFailureCode(null);
      iteration.setFailureReason(null);
      iteration.setFailureContext(null);
      iteration.transitionTo(PatchLoopStatus.CANCELLED);
      iterationRepository.save(iteration);
    } else {
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iterationRepository.save(iteration);
    }

    String publishStatus =
        preservedEntryId != null && preservedEntryId > 0
            ? stringValue(publishMetadata.get("status"))
            : null;
    // Return a deep copy of the metadata snapshot so caller mutations don't
    // affect the persisted state.
    Map<String, Object> metadataSnapshot = deepCopy(taskMetadata);
    return new StopRequestResult(
        iterationId, preservedEntryId, isPrePublish, metadataSnapshot, publishStatus);
  }

  /** Records a delivered control request without guessing the executor's resulting status. */
  @Transactional
  public void applyRequestedControl(String iterationId, int entryId, String action) {
    recordControlRequested(iterationId, entryId, action);
  }

  @Transactional
  public void recordControlRequested(String iterationId, int entryId, String action) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(() -> new IllegalArgumentException("Builder Goal iteration not found: " + iterationId));
    if (iteration.getStatus() != null && iteration.getStatus().isTerminal()) {
      throw new IllegalStateException("Run is already in a terminal state: " + iterationId);
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      throw new IllegalStateException("Entry id mismatch for run " + iterationId);
    }
    String normalizedAction = action == null ? null : action.toLowerCase(java.util.Locale.ENGLISH);
    if (!"pause".equals(normalizedAction) && !"resume".equals(normalizedAction)) {
      throw new IllegalArgumentException("Unsupported control action: " + action);
    }
    publishMetadata.put("control_requested", true);
    publishMetadata.put("control_action", normalizedAction.toUpperCase(java.util.Locale.ENGLISH));
    publishMetadata.put("control_requested_at", LocalDateTime.now().toString());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);
  }

  /**
   * Reloads the iteration and returns a snapshot of the current metadata without acquiring a write
   * lock. Suitable for read-only caller pre-checks.
   */
  @Transactional(readOnly = true)
  public MetadataSnapshot readMetadata(String iterationId) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationId(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    return new MetadataSnapshot(iterationId, iteration.getStatus(), taskMetadata, publishMetadata);
  }

  // ------------------------------------------------------------------
  // Stop delivery recording API
  // ------------------------------------------------------------------

  /**
   * Records that stop delivery to the executor failed, preserving the entry id so the operator can
   * retry. Must be called inside a transaction.
   */
  @Transactional
  public void recordStopDeliveryFailure(String iterationId, int entryId, String error) {
    PatchLoopIteration iteration =
        iterationRepository.findByIterationIdForUpdate(iterationId).orElse(null);
    if (iteration == null) {
      logger.warn("Cannot record stop delivery failure for {}: iteration not found", iterationId);
      return;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      logger.warn(
          "Ignoring stop delivery failure for {}: entry id {} does not match stored {}",
          iterationId,
          entryId,
          storedEntryId);
      return;
    }
    publishMetadata.put("stop_send_error", error);
    publishMetadata.put("stop_send_error_at", LocalDateTime.now().toString());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);
  }

  /** Clears a previously-recorded stop delivery failure. Must be called inside a transaction. */
  @Transactional
  public void clearStopDeliveryFailure(String iterationId, int entryId) {
    PatchLoopIteration iteration =
        iterationRepository.findByIterationIdForUpdate(iterationId).orElse(null);
    if (iteration == null) {
      return;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      logger.warn(
          "Ignoring stop delivery success for {}: entry id {} does not match stored {}",
          iterationId,
          entryId,
          storedEntryId);
      return;
    }
    publishMetadata.remove("stop_send_error");
    publishMetadata.remove("stop_send_error_at");
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);
  }

  /** Cancels a pre-publish run that has no executor entry. Must be called inside a transaction. */
  @Transactional
  public void cancelPrePublish(String iterationId, String reason) {
    PatchLoopIteration iteration =
        iterationRepository.findByIterationIdForUpdate(iterationId).orElse(null);
    if (iteration == null || iteration.getStatus() == null || iteration.getStatus().isTerminal()) {
      return;
    }
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("stop_requested_at", LocalDateTime.now().toString());
    taskMetadata.put("stop_reason", reason != null ? reason : "Cancelled before publish");
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(null);
    iteration.setFailureCode(null);
    iteration.setFailureReason(null);
    iteration.transitionTo(PatchLoopStatus.CANCELLED);
    iterationRepository.save(iteration);
  }

  /**
   * Fails an active run whose launch worker recorded that the Micro6 process exited before
   * readiness. The scheduler passes only the iteration id because its entity snapshot may already
   * be stale by the time this check runs; this method reloads the current row under a write lock
   * and re-checks terminal/stop state before mutating it.
   *
   * @return {@code true} when the current row was transitioned to {@link PatchLoopStatus#FAILED}
   */
  @Transactional
  public boolean failStaleLaunchIfActive(String iterationId) {
    if (iterationId == null || iterationId.isBlank()) {
      return false;
    }
    PatchLoopIteration iteration =
        iterationRepository.findByIterationIdForUpdate(iterationId).orElse(null);
    if (iteration == null || iteration.getStatus() == null || iteration.getStatus().isTerminal()) {
      return false;
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (isStopRequested(taskMetadata, null)) {
      return false;
    }
    Map<String, Object> targetPreparation = readTargetPreparationMetadata(taskMetadata);
    String preparationState = stringValue(targetPreparation.get("state"));
    if (!Micro6TargetRelaunchService.PHASE_PROCESS_EXITED.equalsIgnoreCase(preparationState)
        && !Micro6TargetRelaunchService.PHASE_BUILD_FAILED.equalsIgnoreCase(preparationState)) {
      return false;
    }

    String reason =
        firstNonBlank(
            stringValue(targetPreparation.get("message")),
            stringValue(targetPreparation.get("readiness_reason")),
            "Micro6 relaunch process exited before the local executor became ready");
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(false);
    iteration.markFailed(
        PatchLoopFailureCode.SCRIPT_START_FAILED,
        reason,
        "stale preparation state="
            + preparationState
            + ", iterationId="
            + firstNonBlank(iteration.getIterationId(), "(unknown)"));
    iterationRepository.save(iteration);
    return true;
  }

  /**
   * Applies one executor status response while holding the iteration row lock. Network polling is
   * intentionally performed by the runner before calling this method; all decisions that can change
   * lifecycle state happen here so a concurrent stop or publish cannot be overwritten by a stale
   * poll snapshot.
   */
  @Transactional
  public StatusApplyResult applyExecutorStatus(
      String iterationId,
      int entryId,
      ScriptExecutionService.Micro6RunStatusResult statusResult,
      LocalDateTime observedAt) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    PatchLoopStatus currentStatus = iteration.getStatus();
    if (currentStatus != null && currentStatus.isTerminal()) {
      return new StatusApplyResult(
          iterationId,
          entryId,
          true,
          false,
          false,
          false,
          false,
          iteration.getRunSuccess(),
          iteration.getFailureReason(),
          iteration);
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      throw new IllegalStateException(
          "Entry id mismatch for run "
              + iterationId
              + ": expected "
              + entryId
              + " but stored "
              + storedEntryId);
    }

    LocalDateTime observed = observedAt != null ? observedAt : LocalDateTime.now();
    boolean stopRequested = isStopRequested(taskMetadata, publishMetadata);

    // A missing/failed poll is not proof that the task vanished. Keep the row active until the
    // configured consecutive-failure and grace-period thresholds are both met.
    if (statusResult == null || !statusResult.found()) {
      int failures =
          intValue(publishMetadata.get("consecutive_poll_failures")) == null
              ? 0
              : Math.max(0, intValue(publishMetadata.get("consecutive_poll_failures")));
      publishMetadata.put("consecutive_poll_failures", failures + 1);
      publishMetadata.put("last_poll_at", observed.toString());
      String error = statusResult != null ? statusResult.errorMessage() : null;
      if (error != null && !error.isBlank()) {
        publishMetadata.put("last_error", error);
      }
      if (stopRequested) {
        publishMetadata.put("status", "STOPPING");
      }
      taskMetadata.put("micro6_publish", publishMetadata);

      if (!stopRequested
          && error != null
          && "SETTINGS_RECEIPT_MISMATCH".equalsIgnoreCase(error)) {
        publishMetadata.put("status", "SETTINGS_RECEIPT_MISMATCH");
        publishMetadata.put("last_status_at", observed.toString());
        publishMetadata.put("last_error", error);
        taskMetadata.put("micro6_publish", publishMetadata);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        iterationRepository.save(iteration);
        return new StatusApplyResult(
            iterationId,
            entryId,
            false,
            false,
            false,
            false,
            true,
            iteration.getRunSuccess(),
            error,
            iteration);
      }

      if (!stopRequested && shouldFailForLostTarget(iteration, publishMetadata, observed)) {
        publishMetadata.put("status", "FAILED");
        publishMetadata.put("last_status_at", observed.toString());
        publishMetadata.put(
            "last_error",
            firstNonBlank(error, "Micro6 target became unreachable during published run"));
        taskMetadata.put("micro6_publish", publishMetadata);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        iteration.setRunEndTime(observed);
        iteration.setRunSuccess(false);
        iteration.setFailureCode(PatchLoopFailureCode.TARGET_UNREACHABLE);
        iteration.setFailureReason(
            firstNonBlank(error, "Micro6 target became unreachable during published run"));
        iteration.setFailureContext("publishEntryId=" + entryId);
        iteration.transitionTo(PatchLoopStatus.FAILED);
        iterationRepository.save(iteration);
        return new StatusApplyResult(
            iterationId,
            entryId,
            true,
            true,
            true,
            true,
            false,
            false,
            iteration.getFailureReason(),
            iteration);
      }

      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iterationRepository.save(iteration);
      return new StatusApplyResult(
          iterationId,
          entryId,
          false,
          false,
          false,
          false,
          false,
          iteration.getRunSuccess(),
          null,
          iteration);
    }

    String executorStatus = firstNonBlank(statusResult.status(), "UNKNOWN");
    String requestedControl = stringValue(publishMetadata.get("control_action"));
    if (("PAUSE".equalsIgnoreCase(requestedControl) && "PAUSED".equalsIgnoreCase(executorStatus))
        || ("RESUME".equalsIgnoreCase(requestedControl)
            && ("RECEIVED".equalsIgnoreCase(executorStatus)
                || "QUEUED".equalsIgnoreCase(executorStatus)
                || "ASSIGNED".equalsIgnoreCase(executorStatus)
                || "RUNNING".equalsIgnoreCase(executorStatus)))) {
      publishMetadata.remove("control_requested");
      publishMetadata.remove("control_action");
      publishMetadata.remove("control_requested_at");
    }
    if (statusResult.settingsReceipt() != null) {
      publishMetadata.put("settings_receipt", toSettingsReceiptMap(statusResult.settingsReceipt()));
    }
    boolean metadataChanged = recordSuccessfulPoll(publishMetadata, observed);
    metadataChanged |= applyTaskStartFields(iteration, taskMetadata, publishMetadata, statusResult);

    if (stopRequested) {
      if ("COMPLETED".equalsIgnoreCase(executorStatus)) {
        publishMetadata.put("status", "COMPLETED");
        publishMetadata.put("last_status_at", observed.toString());
        taskMetadata.put("micro6_publish", publishMetadata);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        return finalizeAsCompleted(iteration, entryId, observed);
      }
      if ("FAILED".equalsIgnoreCase(executorStatus)) {
        publishMetadata.put("status", "FAILED");
        publishMetadata.put("last_status_at", observed.toString());
        if (statusResult.errorMessage() != null && !statusResult.errorMessage().isBlank()) {
          publishMetadata.put("last_error", statusResult.errorMessage());
        }
        taskMetadata.put("micro6_publish", publishMetadata);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        return finalizeAsFailed(iteration, entryId, observed, statusResult.errorMessage());
      }
      if ("CANCELLED".equalsIgnoreCase(executorStatus)) {
        publishMetadata.put("status", "CANCELLED");
        publishMetadata.put("activation_state", "CANCELLED");
        publishMetadata.put("last_status_at", observed.toString());
        taskMetadata.put("micro6_publish", publishMetadata);
        iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
        return finalizeAsCancelled(
            iteration,
            entryId,
            observed,
            firstNonBlank(statusResult.errorMessage(), "Micro6 confirmed cancellation"));
      }
      publishMetadata.put("status", "STOPPING");
      publishMetadata.put("last_status_at", observed.toString());
      taskMetadata.put("micro6_publish", publishMetadata);
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iterationRepository.save(iteration);
      return new StatusApplyResult(
          iterationId,
          entryId,
          false,
          false,
          false,
          false,
          false,
          iteration.getRunSuccess(),
          null,
          iteration);
    }

    String mappedStatus = mapPublishStatus(executorStatus);
    if (!mappedStatus.equalsIgnoreCase(stringValue(publishMetadata.get("status")))) {
      metadataChanged = true;
    }
    publishMetadata.put("status", mappedStatus);
    publishMetadata.put("last_status_at", observed.toString());
    if (statusResult.errorMessage() != null && !statusResult.errorMessage().isBlank()) {
      publishMetadata.put("last_error", statusResult.errorMessage());
    }
    taskMetadata.put("micro6_publish", publishMetadata);

    if ("COMPLETED".equalsIgnoreCase(executorStatus)) {
      // Persist the status metadata before the terminal transition helper. The helper reloads
      // task metadata when it adds terminal timestamps; without this write a RUNNING/QUEUED
      // publish status could survive beside a COMPLETED iteration row.
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      return finalizeAsCompleted(iteration, entryId, observed);
    }
    if ("FAILED".equalsIgnoreCase(executorStatus)) {
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      return finalizeAsFailed(iteration, entryId, observed, statusResult.errorMessage());
    }
    if ("CANCELLED".equalsIgnoreCase(executorStatus)) {
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      return finalizeAsCancelled(
          iteration,
          entryId,
          observed,
          firstNonBlank(statusResult.errorMessage(), "Micro6 task was cancelled"));
    }

    // PAUSED is an activated-but-suspended executor state.  The executor may legitimately omit
    // task_started/debug_session_id while the task is suspended (for example when it was paused
    // before the debug binding callback completed).  Do not reinterpret that state as a queued
    // task that never started: preserve PAUSED, keep the iteration active, and allow later resume
    // or stop reconciliation to operate on the same entry.
    boolean paused = "PAUSED".equalsIgnoreCase(executorStatus);
    boolean taskStarted = paused || isTaskStarted(statusResult);
    boolean taskStartConfirmed = paused || isTaskStartConfirmed(statusResult);
    if (paused && !Boolean.TRUE.equals(taskMetadata.get("task_start_confirmed"))) {
      // applyTaskStartFields mirrors the optional wire field and may have just written false;
      // PAUSED itself is the authoritative activation evidence for this status.
      taskMetadata.put("task_start_confirmed", true);
      metadataChanged = true;
    }
    boolean waitingForStart = !taskStartConfirmed;
    if (("RUNNING".equalsIgnoreCase(executorStatus) || "PAUSED".equalsIgnoreCase(executorStatus))
        && taskStartConfirmed
        && iteration.getStatus() == PatchLoopStatus.QUEUED) {
      iteration.transitionTo(PatchLoopStatus.RUNNING_SCRIPT);
      metadataChanged = true;
    }

    if (waitingForStart && hasTimedOut(iteration, publishMetadata, observed)) {
      StatusApplyResult timeout =
          finalizeTaskStartTimeout(iteration, entryId, publishMetadata, statusResult, observed);
      return timeout;
    }

    if (metadataChanged || taskStarted) {
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iterationRepository.save(iteration);
    }
    return new StatusApplyResult(
        iterationId,
        entryId,
        false,
        false,
        false,
        false,
        false,
        iteration.getRunSuccess(),
        null,
        iteration);
  }

  private StatusApplyResult finalizeAsCompleted(
      PatchLoopIteration iteration, int entryId, LocalDateTime observedAt) {
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(true);
    iteration.setFailureCode(null);
    iteration.setFailureReason(null);
    iteration.transitionTo(PatchLoopStatus.COMPLETED);
    iterationRepository.save(iteration);
    return new StatusApplyResult(
        iteration.getIterationId(), entryId, true, true, true, false, false, true, null, iteration);
  }

  private StatusApplyResult finalizeAsFailed(
      PatchLoopIteration iteration, int entryId, LocalDateTime observedAt, String failureReason) {
    iteration.setRunEndTime(observedAt != null ? observedAt : LocalDateTime.now());
    iteration.setRunSuccess(false);
    iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_EXCEPTION);
    iteration.setFailureReason(firstNonBlank(failureReason, "Micro6 task failed"));
    iteration.transitionTo(PatchLoopStatus.FAILED);
    iterationRepository.save(iteration);
    return new StatusApplyResult(
        iteration.getIterationId(),
        entryId,
        true,
        true,
        true,
        true,
        false,
        false,
        iteration.getFailureReason(),
        iteration);
  }

  private StatusApplyResult finalizeAsCancelled(
      PatchLoopIteration iteration, int entryId, LocalDateTime observedAt, String reason) {
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (stringValue(taskMetadata.get("stop_requested_at")) == null) {
      taskMetadata.put("stop_requested_at", observedAt.toString());
    }
    if (stringValue(taskMetadata.get("stop_reason")) == null
        && reason != null
        && !reason.isBlank()) {
      taskMetadata.put("stop_reason", reason);
    }
    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    publishMetadata.put("activation_state", "CANCELLED");
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iteration.setRunEndTime(observedAt != null ? observedAt : LocalDateTime.now());
    iteration.setRunSuccess(null);
    iteration.transitionTo(PatchLoopStatus.CANCELLED);
    iterationRepository.save(iteration);
    return new StatusApplyResult(
        iteration.getIterationId(),
        entryId,
        true,
        false,
        true,
        false,
        false,
        null,
        reason,
        iteration);
  }

  private boolean requiresSettingsReceipt(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return false;
    }
    Object explicit = taskMetadata.get("requires_settings_receipt");
    if (explicit instanceof Boolean && (Boolean) explicit) {
      return true;
    }
    return explicit instanceof String && Boolean.parseBoolean((String) explicit);
  }

  private Map<String, Object> toSettingsReceiptMap(
      ScriptExecutionService.Micro6SettingsReceipt receipt) {
    Map<String, Object> mapped = new LinkedHashMap<>();
    mapped.put("version", receipt.version());
    mapped.put("contract", "builder-settings-canonical-json/v1");
    mapped.put("entry_id", receipt.entryId());
    mapped.put("iteration_id", receipt.iterationId());
    mapped.put("task_key", receipt.taskKey());
    mapped.put("settings", deepCopyJsonValue(receipt.settings()));
    mapped.put("settings_sha256", receipt.settingsSha256());
    mapped.put("settings_schema_version", receipt.settingsSchemaVersion());
    mapped.put("settings_schema_hash", receipt.settingsSchemaHash());
    mapped.put("accepted_at", receipt.acceptedAt());
    mapped.put("configuration_state", receipt.configurationState());
    return mapped;
  }

  private Object deepCopyJsonValue(JsonNode value) {
    return value == null ? null : objectMapper.convertValue(value, Object.class);
  }

  private boolean recordSuccessfulPoll(
      Map<String, Object> publishMetadata, LocalDateTime observedAt) {
    boolean changed = false;
    String now = observedAt.toString();
    if (!now.equals(stringValue(publishMetadata.get("last_poll_at")))) {
      publishMetadata.put("last_poll_at", now);
      changed = true;
    }
    if (!now.equals(stringValue(publishMetadata.get("last_successful_poll_at")))) {
      publishMetadata.put("last_successful_poll_at", now);
      changed = true;
    }
    if (intValue(publishMetadata.get("consecutive_poll_failures")) != null
        && intValue(publishMetadata.get("consecutive_poll_failures")) > 0) {
      publishMetadata.put("consecutive_poll_failures", 0);
      changed = true;
    }
    if (publishMetadata.remove("last_error") != null) {
      changed = true;
    }
    return changed;
  }

  private boolean applyTaskStartFields(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      Map<String, Object> publishMetadata,
      ScriptExecutionService.Micro6RunStatusResult statusResult) {
    JsonNode raw = statusResult.rawResponse();
    if (raw == null || raw.isNull()) {
      return false;
    }
    boolean changed = false;
    boolean taskStarted = raw.path("task_started").asBoolean(false);
    if (taskStarted != Boolean.TRUE.equals(taskMetadata.get("task_start_confirmed"))) {
      taskMetadata.put("task_start_confirmed", taskStarted);
      changed = true;
    }
    String activationState = textValue(raw.path("activation_state"));
    if (!Objects.equals(activationState, stringValue(publishMetadata.get("activation_state")))) {
      if (activationState != null) {
        publishMetadata.put("activation_state", activationState);
      } else {
        publishMetadata.remove("activation_state");
      }
      changed = true;
    }
    String currentTask = textValue(raw.path("current_task"));
    if (!Objects.equals(currentTask, stringValue(taskMetadata.get("current_task")))) {
      if (currentTask != null) {
        taskMetadata.put("current_task", currentTask);
      } else {
        taskMetadata.remove("current_task");
      }
      changed = true;
    }
    String startedAt = textValue(raw.path("started_at"));
    if (!Objects.equals(startedAt, stringValue(taskMetadata.get("task_started_at")))) {
      if (startedAt != null) {
        taskMetadata.put("task_started_at", startedAt);
      } else {
        taskMetadata.remove("task_started_at");
      }
      changed = true;
    }
    String debugSessionId = textValue(raw.path("debug_session_id"));
    if (debugSessionId != null && !Objects.equals(debugSessionId, iteration.getRunSessionId())) {
      iteration.setRunSessionId(debugSessionId);
      changed = true;
    }
    String recordingId = textValue(raw.path("recording_id"));
    if (recordingId != null && !Objects.equals(recordingId, iteration.getRunRecordingId())) {
      iteration.setRunRecordingId(recordingId);
      changed = true;
    }
    if (debugSessionId != null
        && !Objects.equals(debugSessionId, stringValue(publishMetadata.get("debug_session_id")))) {
      publishMetadata.put("debug_session_id", debugSessionId);
      changed = true;
    }
    if (recordingId != null
        && !Objects.equals(recordingId, stringValue(publishMetadata.get("recording_id")))) {
      publishMetadata.put("recording_id", recordingId);
      changed = true;
    }
    return changed;
  }

  private boolean isTaskStarted(ScriptExecutionService.Micro6RunStatusResult statusResult) {
    JsonNode raw = statusResult.rawResponse();
    return raw != null && !raw.isNull() && raw.path("task_started").asBoolean(false);
  }

  private boolean isTaskStartConfirmed(ScriptExecutionService.Micro6RunStatusResult statusResult) {
    JsonNode raw = statusResult != null ? statusResult.rawResponse() : null;
    return raw != null
        && !raw.isNull()
        && raw.path("task_started").asBoolean(false)
        && textValue(raw.path("debug_session_id")) != null;
  }

  private boolean hasTimedOut(
      PatchLoopIteration iteration, Map<String, Object> publishMetadata, LocalDateTime observedAt) {
    String publishedAt = stringValue(publishMetadata.get("published_at"));
    if (publishedAt == null) {
      return false;
    }
    LocalDateTime published = parseLocalDateTime(publishedAt);
    if (published == null) {
      return false;
    }
    long elapsed = java.time.Duration.between(published, observedAt).toMillis();
    return elapsed >= runTimeoutMs;
  }

  private StatusApplyResult finalizeTaskStartTimeout(
      PatchLoopIteration iteration,
      int entryId,
      Map<String, Object> publishMetadata,
      ScriptExecutionService.Micro6RunStatusResult statusResult,
      LocalDateTime observedAt) {
    publishMetadata.put("status", "FAILED");
    publishMetadata.put("last_status_at", observedAt.toString());
    publishMetadata.put("last_error", "Task start timed out");
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iteration.setRunEndTime(LocalDateTime.now());
    iteration.setRunSuccess(false);
    iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_START_FAILED);
    // Preserve the last observed activation state and any executor-reported reason so the operator
    // can distinguish an accepted-but-queued task from a confirmed start.
    String lastActivationState = stringValue(publishMetadata.get("activation_state"));
    String lastExecutorReason =
        statusResult != null
                && statusResult.errorMessage() != null
                && !statusResult.errorMessage().isBlank()
            ? statusResult.errorMessage()
            : stringValue(publishMetadata.get("last_error"));
    String timeoutDetail = "Task start timed out";
    if (lastActivationState != null && !lastActivationState.isBlank()) {
      timeoutDetail += " (last activation state: " + lastActivationState + ")";
    }
    if (lastExecutorReason != null && !lastExecutorReason.isBlank()) {
      timeoutDetail += " (executor: " + lastExecutorReason + ")";
    }
    iteration.setFailureReason(timeoutDetail);
    iteration.transitionTo(PatchLoopStatus.FAILED);
    iterationRepository.save(iteration);
    return new StatusApplyResult(
        iteration.getIterationId(),
        entryId,
        true,
        true,
        true,
        true,
        false,
        false,
        timeoutDetail,
        iteration);
  }

  private boolean shouldFailForLostTarget(
      PatchLoopIteration iteration, Map<String, Object> publishMetadata, LocalDateTime observedAt) {
    Integer failures = intValue(publishMetadata.get("consecutive_poll_failures"));
    if (failures == null || failures < maxConsecutiveStatusFailures) {
      return false;
    }
    String lastGood = stringValue(publishMetadata.get("last_successful_poll_at"));
    if (lastGood == null) {
      lastGood = stringValue(publishMetadata.get("published_at"));
    }
    if (lastGood == null && iteration != null && iteration.getRunStartTime() != null) {
      lastGood = iteration.getRunStartTime().toString();
    }
    if (lastGood == null && iteration != null && iteration.getCreatedAt() != null) {
      lastGood = iteration.getCreatedAt().toString();
    }
    if (lastGood != null) {
      LocalDateTime lastGoodTime = parseLocalDateTime(lastGood);
      if (lastGoodTime != null) {
        long elapsed = java.time.Duration.between(lastGoodTime, observedAt).toMillis();
        return elapsed >= statusFailureGraceMs;
      }
    }
    return false;
  }

  private String mapPublishStatus(String micro6Status) {
    if (micro6Status == null || micro6Status.isBlank()) {
      return "RECONCILING";
    }
    if ("QUEUED".equalsIgnoreCase(micro6Status)) {
      return "QUEUED";
    }
    if ("ACCEPTED".equalsIgnoreCase(micro6Status) || "RECEIVED".equalsIgnoreCase(micro6Status)) {
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
      // PAUSED is an activated-but-suspended state that must survive status reconciliation and
      // summary rendering as PAUSED rather than being collapsed into RUNNING.
      return "PAUSED";
    }
    if ("PENDING".equalsIgnoreCase(micro6Status)) {
      return "ACCEPTED";
    }
    // An unrecognized executor status is genuinely ambiguous, never a confirmation of a start.
    return "RECONCILING";
  }

  // ------------------------------------------------------------------
  // Late reconciliation API
  // ------------------------------------------------------------------

  /**
   * Begins a late-reconcile cycle: locks the iteration, persists last_reconcile_attempt_at so
   * rotation is fair, and returns the stored entry id for Micro6 polling. Returns empty when the
   * iteration is no longer eligible (terminal, stopped, or no valid entry id).
   */
  @Transactional
  public Optional<ReconcileSnapshot> beginLateReconcile(String iterationId) {
    PatchLoopIteration iteration =
        iterationRepository.findByIterationIdForUpdate(iterationId).orElse(null);
    if (iteration == null) {
      return Optional.empty();
    }

    PatchLoopStatus status = iteration.getStatus();
    if (status == null || !status.isTerminal()) {
      return Optional.empty();
    }
    if (status != PatchLoopStatus.FAILED) {
      return Optional.empty();
    }

    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (isStopRequested(taskMetadata, null)) {
      return Optional.empty();
    }

    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer entryId = intValue(publishMetadata.get("entry_id"));
    if (entryId == null || entryId <= 0) {
      return Optional.empty();
    }
    String publishStatus = stringValue(publishMetadata.get("status"));
    if (!"PUBLISH_FAILED".equalsIgnoreCase(publishStatus)) {
      return Optional.empty();
    }

    // Persist attempt timestamp BEFORE polling so rotation is fair.
    publishMetadata.put("last_reconcile_attempt_at", LocalDateTime.now().toString());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);

    return Optional.of(
        new ReconcileSnapshot(
            iterationId,
            entryId,
            stringValue(taskMetadata.get("stop_requested_at")),
            deepCopy(taskMetadata),
            deepCopy(publishMetadata)));
  }

  /**
   * Applies a late-reconcile result. Verifies identity (entry id, iteration id, canonical task
   * key), checks stop/cancellation, and accurately maps the executor status. Returns whether the
   * run was revived.
   */
  @Transactional
  public ReconcileApplyResult applyLateReconcile(
      String iterationId,
      int entryId,
      String executorIterationId,
      String executorTaskKey,
      ScriptExecutionService.Micro6RunStatusResult result) {
    PatchLoopIteration iteration =
        iterationRepository
            .findByIterationIdForUpdate(iterationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Builder Goal iteration not found: " + iterationId));

    // Verify no stop request or cancellation exists.
    Map<String, Object> taskMetadata = readTaskMetadata(iteration);
    if (isStopRequested(taskMetadata, null)) {
      return new ReconcileApplyResult(iterationId, false, "stop_requested");
    }
    PatchLoopStatus status = iteration.getStatus();
    if (status == PatchLoopStatus.CANCELLED) {
      return new ReconcileApplyResult(iterationId, false, "already_cancelled");
    }

    Map<String, Object> publishMetadata = readPublishMetadata(taskMetadata);
    Integer storedEntryId = intValue(publishMetadata.get("entry_id"));
    if (storedEntryId == null || storedEntryId != entryId) {
      return new ReconcileApplyResult(iterationId, false, "entry_id_mismatch");
    }

    // Identity validation: verify iteration and task match the executor payload.
    String storedIterationId = stringValue(publishMetadata.get("iteration_id"));
    if (storedIterationId == null) {
      storedIterationId = iterationId;
    }
    if (executorIterationId == null || executorIterationId.isBlank()) {
      return new ReconcileApplyResult(iterationId, false, "missing_executor_iteration_id");
    }
    // Iteration IDs are opaque ownership tokens. Do not trim or case-fold a response before
    // comparing it: a near-match could attach an executor entry to a different persisted run.
    if (!executorIterationId.equals(storedIterationId)) {
      return new ReconcileApplyResult(iterationId, false, "iteration_id_mismatch");
    }

    String storedTaskKey = stringValue(publishMetadata.get("task_key"));
    if (storedTaskKey == null) {
      storedTaskKey = stringValue(taskMetadata.get("task_key"));
    }
    if (storedTaskKey == null) {
      storedTaskKey = stringValue(iteration.getScriptName());
    }
    if (executorTaskKey == null || executorTaskKey.isBlank()) {
      return new ReconcileApplyResult(iterationId, false, "missing_executor_task_key");
    }
    // Task keys are canonical catalog identities. Do not trim, case-fold, or otherwise normalize
    // an executor response during late reconciliation: an alias or near-match could attach a
    // different task to this persisted run.
    if (storedTaskKey == null || !executorTaskKey.equals(storedTaskKey)) {
      return new ReconcileApplyResult(iterationId, false, "task_key_mismatch");
    }

    // Check if the executor found the task.
    if (result == null || !result.found()) {
      return new ReconcileApplyResult(iterationId, false, "executor_not_found");
    }
    if (result.entryId() != null && result.entryId() != entryId) {
      return new ReconcileApplyResult(iterationId, false, "executor_entry_id_mismatch");
    }

    String executorStatus = result.status();
    if (result.settingsReceipt() != null) {
      publishMetadata.put("settings_receipt", toSettingsReceiptMap(result.settingsReceipt()));
    }
    if (executorStatus == null || executorStatus.isBlank()) {
      return new ReconcileApplyResult(iterationId, false, "empty_executor_status");
    }

    // Apply status accurately.
    if ("COMPLETED".equalsIgnoreCase(executorStatus)) {
      publishMetadata.put("status", "COMPLETED");
      iteration.setRunEndTime(LocalDateTime.now());
      iteration.setRunSuccess(true);
      publishStatusUpdate(iteration, taskMetadata, publishMetadata, PatchLoopStatus.COMPLETED);
      return new ReconcileApplyResult(iterationId, true, "completed");
    }

    if ("FAILED".equalsIgnoreCase(executorStatus)) {
      return new ReconcileApplyResult(iterationId, false, "executor_failed");
    }

    if ("CANCELLED".equalsIgnoreCase(executorStatus)) {
      publishMetadata.put("status", "CANCELLED");
      publishMetadata.put("activation_state", "CANCELLED");
      iteration.setRunEndTime(LocalDateTime.now());
      iteration.setRunSuccess(false);
      publishStatusUpdate(iteration, taskMetadata, publishMetadata, PatchLoopStatus.CANCELLED);
      return new ReconcileApplyResult(iterationId, true, "cancelled");
    }

    // Active/queued states: revive.
    boolean isActive =
        "RECEIVED".equalsIgnoreCase(executorStatus)
            || "PENDING".equalsIgnoreCase(executorStatus)
            || "ACCEPTED".equalsIgnoreCase(executorStatus)
            || "QUEUED".equalsIgnoreCase(executorStatus)
            || "ASSIGNED".equalsIgnoreCase(executorStatus);
    boolean isRunning =
        "RUNNING".equalsIgnoreCase(executorStatus) || "PAUSED".equalsIgnoreCase(executorStatus);

    if (isActive || isRunning) {
      publishMetadata.put("status", executorStatus);
      iteration.setRunEndTime(null);
      iteration.setRunSuccess(null);
      iteration.setFailureCode(null);
      iteration.setFailureReason(null);
      iteration.setFailureContext(null);
      taskMetadata.put("micro6_publish", publishMetadata);
      iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
      iterationRepository.save(iteration);
      if (isRunning) {
        iteration.transitionTo(PatchLoopStatus.RUNNING_SCRIPT);
      } else {
        iteration.transitionTo(PatchLoopStatus.QUEUED);
      }
      iterationRepository.save(iteration);
      return new ReconcileApplyResult(
          iterationId, true, isRunning ? "revived_running" : "revived_queued");
    }

    return new ReconcileApplyResult(
        iterationId, false, "unhandled_executor_status: " + executorStatus);
  }

  private void publishStatusUpdate(
      PatchLoopIteration iteration,
      Map<String, Object> taskMetadata,
      Map<String, Object> publishMetadata,
      PatchLoopStatus newStatus) {
    publishMetadata.put("last_status_at", LocalDateTime.now().toString());
    taskMetadata.put("micro6_publish", publishMetadata);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(taskMetadata));
    iterationRepository.save(iteration);
    iteration.transitionTo(newStatus);
    iterationRepository.save(iteration);
  }

  private void mergeHealthMetadata(
      Map<String, Object> taskMetadata,
      ScriptExecutionService.Micro6ExecutorHealthResult healthResult,
      String fallbackReason) {
    Map<String, Object> preparation = new LinkedHashMap<>();
    Object existing = taskMetadata.get("target_preparation");
    if (existing instanceof Map<?, ?> existingMap) {
      for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
        if (entry.getKey() != null) {
          preparation.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
    }
    preparation.put("micro6_health_status", healthResult.readinessPhase());
    preparation.put(
        "micro6_health_reason", firstNonBlank(healthResult.readinessReason(), fallbackReason));
    preparation.put("local_executor_base_url", healthResult.micro6BaseUrl());
    preparation.put("readiness_phase", healthResult.readinessPhase());
    preparation.put(
        "readiness_reason", firstNonBlank(healthResult.readinessReason(), fallbackReason));
    preparation.put("resolved_account_name", healthResult.resolvedAccountName());
    preparation.put("resolved_account_hash", healthResult.resolvedAccountHash());
    preparation.put("account_hash_scheme", AccountIdentityContract.HASH_SCHEME);
    preparation.put("requested_account_name", healthResult.requestedAccountName());
    preparation.put("requested_account_hash", healthResult.requestedAccountHash());
    preparation.put("dispatch_hold", healthResult.dispatchHold());
    preparation.put("dispatch_hold_reason", healthResult.dispatchHoldReason());
    preparation.put("startup_state", healthResult.startupState());
    preparation.put("login_state", healthResult.loginState());
    preparation.put("login_attempt", healthResult.loginAttempt());
    preparation.put("login_attempt_max", healthResult.loginAttemptMax());
    preparation.put("login_terminal_reason", healthResult.loginTerminalReason());
    preparation.put("requested_world", healthResult.requestedWorld());
    preparation.put("current_world", healthResult.currentWorld());
    preparation.put("world_matched", healthResult.worldMatched());
    preparation.put("pathfinder_ready", healthResult.pathfinderReady());
    preparation.put("script_lifecycle_state", healthResult.scriptLifecycleState());
    preparation.put("script_loop_alive", healthResult.scriptLoopAlive());
    taskMetadata.put("target_preparation", preparation);
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String s = value.toString().trim();
    return s.isEmpty() ? null : s;
  }

  private String textValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return stringValue(node.asText(null));
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private LocalDateTime parseLocalDateTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(value);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  public record ReconcileSnapshot(
      String iterationId,
      int entryId,
      String stopRequestedAt,
      Map<String, Object> taskMetadata,
      Map<String, Object> publishMetadata) {}

  public record ReconcileApplyResult(String iterationId, boolean revived, String reason) {}

  // ------------------------------------------------------------------
  // Value-object records
  // ------------------------------------------------------------------

  public record PublishReservation(
      String iterationId,
      PatchLoopStatus statusBeforeReserve,
      Map<String, Object> taskMetadataSnapshot,
      Map<String, Object> publishMetadataSnapshot) {}

  public record ApplyOutcomeResult(
      String iterationId,
      int entryId,
      Map<String, Object> mergedPublishMetadata,
      boolean stopRequested,
      boolean needsExecutorCancel,
      boolean becameTerminal,
      boolean applyFailureMetadata,
      String publishStatus) {}

  public record StopRequestResult(
      String iterationId,
      Integer preservedEntryId,
      boolean isPrePublish,
      Map<String, Object> taskMetadataSnapshot,
      String publishStatus) {}

  public record MetadataSnapshot(
      String iterationId,
      PatchLoopStatus status,
      Map<String, Object> taskMetadata,
      Map<String, Object> publishMetadata) {}

  // ------------------------------------------------------------------
  // Normal status sync API
  // ------------------------------------------------------------------

  /**
   * Result of applying an executor status poll to the iteration. Describes what post-lock actions
   * the runner must perform (evidence collection, session resolution, recovery, executor
   * cancellation).
   */
  public record StatusApplyResult(
      String iterationId,
      int entryId,
      boolean terminal,
      boolean collectEvidence,
      boolean resolveSession,
      boolean moveToRecovery,
      boolean sendExecutorCancel,
      Boolean runSuccess,
      String failureReason,
      PatchLoopIteration iterationForEvidence) {}

  // ------------------------------------------------------------------
  // Internal helpers
  // ------------------------------------------------------------------

  private Map<String, Object> readTaskMetadata(PatchLoopIteration iteration) {
    if (iteration.getTaskMetadataJson() == null || iteration.getTaskMetadataJson().isNull()) {
      return new LinkedHashMap<>();
    }
    return objectMapper.convertValue(
        iteration.getTaskMetadataJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
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

  private Integer intValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> deepCopy(Map<String, Object> source) {
    if (source == null) {
      return new LinkedHashMap<>();
    }
    JsonNode node = objectMapper.valueToTree(source);
    return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
  }
}
