package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.aiplayer.routine.source.SourceManifestBindingService;
import com.chromascape.web.aiplayer.routine.source.SourceManifestFetchService;
import com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies an external recorder result only while the claimed operation attempt is still owner. */
@Service
public class CaptureOperationResultService {
  private static final Logger logger = LoggerFactory.getLogger(CaptureOperationResultService.class);

  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator identities;
  private final RoutineCaptureOperationRepository operations;
  private final RoutineCaptureRecordRepository captures;
  private final CurrentSliceRunService runs;
  private final SourceManifestBindingService sourceBindings;
  /** Remote fetch + verification; must never be invoked while a transaction is active. */
  private final SourceManifestFetchService sourceFetch;
  private final TransactionTemplate transactions;

  @org.springframework.beans.factory.annotation.Autowired
  public CaptureOperationResultService(
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities,
      RoutineCaptureOperationRepository operations,
      RoutineCaptureRecordRepository captures,
      CurrentSliceRunService runs,
      SourceManifestBindingService sourceBindings,
      SourceManifestFetchService sourceFetch,
      PlatformTransactionManager transactionManager) {
    this.mapper = mapper;
    this.identities = identities;
    this.operations = operations;
    this.captures = captures;
    this.runs = runs;
    this.sourceBindings = sourceBindings;
    this.sourceFetch = sourceFetch;
    this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
  }

  /** Compatibility constructor for isolated capture-operation tests. */
  public CaptureOperationResultService(
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities,
      RoutineCaptureOperationRepository operations,
      RoutineCaptureRecordRepository captures,
      CurrentSliceRunService runs) {
    this(mapper, identities, operations, captures, runs, null, null, null);
  }

  @Transactional
  public void applyStart(Long operationId, String attemptId, CaptureRecordingGateway.StartResult result) {
    RoutineCaptureOperation operation = owned(operationId, attemptId);
    if (operation == null) return;
    RoutineCaptureRecord capture = captures.findWithLockBySessionId(operation.getSessionId()).orElse(null);
    if (capture == null) {
      finish(operation, "FAILED", Map.of("error", "CAPTURE_SESSION_NOT_FOUND"), "CAPTURE_SESSION_NOT_FOUND");
      return;
    }
    if (result.success()) {
      capture.setDebugSessionId(result.debugSessionId());
      capture.setRecordingId(result.recordingId());
      capture.setCompletionStatus("RECORDING");
      capture.setCompletionError(null);
      capture.setOperation("START");
      capture.setOperationState("SUCCEEDED");
      capture.setOperationCorrelationId(operation.getCorrelationId());
      captures.save(capture);
      finish(operation, "SUCCEEDED", Map.of("debug_session_id", result.debugSessionId(), "recording_id", result.recordingId()), null);
    } else {
      capture.setCompletionStatus("UNCERTAIN");
      capture.setCompletionError(truncate(result.error(), 500));
      capture.setOperation("START");
      capture.setOperationState("UNCERTAIN");
      capture.setOperationCorrelationId(operation.getCorrelationId());
      captures.save(capture);
      finish(operation, "UNCERTAIN", Map.of("error", result.error() == null ? "CAPTURE_START_UNCERTAIN" : result.error()), result.error());
    }
  }

  /**
   * Capture-completion flow, split so the remote source fetch never runs inside a database
   * transaction:
   *
   * <ol>
   *   <li>phase 1 (no transaction): read the operation/capture identity and, when source binding
   *       applies, fetch and verify the Micro6 manifest via {@link SourceManifestFetchService};
   *   <li>phase 2 (one transaction): re-lock the operation/capture, revalidate that the identity
   *       did not change, then persist/reuse the manifest and recording binding and update the run
   *       identity, capture identity, completion state and operation state — committing together.
   * </ol>
   *
   * <p>Any failure rolls back all phase-2 writes; reconcilable UNCERTAIN state is recorded
   * afterwards in its own separate short transaction.
   */
  public void applyComplete(Long operationId, String attemptId, CaptureRecordingGateway.CompleteResult result) {
    if (transactions == null) {
      throw new IllegalStateException("capture completion requires a transaction manager");
    }
    PreparedSource prepared;
    try {
      prepared = prepareSource(operationId, attemptId, result);
    } catch (Exception exception) {
      recordCompleteUncertain(operationId, attemptId, exception);
      return;
    }
    try {
      transactions.executeWithoutResult(status ->
          applyCompleteInTransaction(operationId, attemptId, result, prepared));
    } catch (CompletionIdentityConflictException exception) {
      recordCompleteConflict(operationId, attemptId, exception);
    } catch (Exception exception) {
      recordCompleteUncertain(operationId, attemptId, exception);
    }
  }

  /**
   * The already-verified source for this completion plus the exact capture identity JSON it was
   * prepared against, so phase 2 can revalidate that the identity did not change.
   */
  private record PreparedSource(SourceManifestFetchService.FetchedSource fetched, String identityJson) {}

  /**
   * Phase 1: decides whether source binding applies and fetches/verifies the manifest with no
   * transaction active. Returns {@code null} when no binding applies; throws when binding applies
   * but the source is unavailable or invalid (the caller records reconcilable UNCERTAIN state).
   */
  private PreparedSource prepareSource(Long operationId, String attemptId,
      CaptureRecordingGateway.CompleteResult result) {
    if (sourceFetch == null) return null;
    RoutineCaptureOperation operation = operations.findById(operationId).orElse(null);
    if (operation == null || !"LEASED".equals(operation.getState())
        || !attemptId.equals(operation.getAttemptId())) return null;
    RoutineCaptureRecord capture = captures.findBySessionId(operation.getSessionId()).orElse(null);
    if (capture == null || capture.getIdentityJson() == null) return null;
    try {
      CurrentSliceIdentity base = mapper.readValue(capture.getIdentityJson(), CurrentSliceIdentity.class);
      if (!result.success() || !shouldBindSource(base)) return null;
      String runId = base.run().runId();
      String taskKey = base.task().taskKey();
      SourceManifestFetchService.FetchedSource fetched = sourceFetch.fetchForRun(runId, taskKey, base);
      return new PreparedSource(fetched, capture.getIdentityJson());
    } catch (Exception exception) {
      throw new IllegalStateException(
          exception.getMessage() == null ? "CAPTURE_SOURCE_UNAVAILABLE" : exception.getMessage(),
          exception);
    }
  }

  /** Phase 2: one transaction covering source binding and all capture/run/operation writes. */
  private void applyCompleteInTransaction(Long operationId, String attemptId,
      CaptureRecordingGateway.CompleteResult result, PreparedSource prepared) {
    RoutineCaptureOperation operation = owned(operationId, attemptId);
    if (operation == null) return;
    RoutineCaptureRecord capture = captures.findWithLockBySessionId(operation.getSessionId()).orElse(null);
    if (capture == null) {
      finish(operation, "FAILED", Map.of("error", "CAPTURE_SESSION_NOT_FOUND"), "CAPTURE_SESSION_NOT_FOUND");
      return;
    }
    if (capture.isCompleted()) {
      if (sameCompletedResult(capture, result)) {
        // A retry after the original commit must return the immutable completion identity. Do not
        // rebuild the recording hash from the already-enriched capture identity and do not touch
        // the source/run rows a second time.
        finish(operation, "SUCCEEDED", Map.of(
            "recording_id", capture.getRecordingId(),
            "recording_hash", capture.getRecordingHash(),
            "video_sha256", capture.getVideoSha256()), null);
        return;
      }
      throw new CompletionIdentityConflictException(
          "CAPTURE_COMPLETION_IDENTITY_CONFLICT");
    }
    if (!result.success()) {
      capture.setCompletionStatus("UNCERTAIN");
      capture.setCompletionError(truncate(result.error(), 500));
      capture.setOperation("COMPLETE");
      capture.setOperationState("UNCERTAIN");
      capture.setOperationCorrelationId(operation.getCorrelationId());
      captures.save(capture);
      finish(operation, "UNCERTAIN", Map.of("error", result.error() == null ? "CAPTURE_COMPLETE_UNCERTAIN" : result.error()), result.error());
      return;
    }
    try {
      Map<String, Object> request = readMap(operation.getRequestJson());
      String expectedVideoHash = String.valueOf(request.getOrDefault("video_sha256", ""));
      if (!expectedVideoHash.isBlank() && !expectedVideoHash.equals(result.videoSha256())) {
        throw new IllegalStateException("VIDEO_HASH_MISMATCH");
      }
      ObjectNode recordingContent = mapper.createObjectNode();
      recordingContent.set("identity", readJson(capture.getIdentityJson()));
      recordingContent.set("ticks", readJson(capture.getTicksJson()));
      recordingContent.set("actions", readJson(capture.getActionsJson()));
      recordingContent.put("video_sha256", result.videoSha256());
      String recordingHash = CanonicalJsonHasher.hash(recordingContent, null);
      CurrentSliceIdentity base = identities.validate(mapper.readValue(capture.getIdentityJson(), CurrentSliceIdentity.class));
      if (prepared != null && !Objects.equals(prepared.identityJson(), capture.getIdentityJson())) {
        // The capture identity changed between the remote fetch and this transaction; fail closed
        // and roll back instead of binding a source to a different identity.
        throw new IllegalStateException("CAPTURE_IDENTITY_CHANGED");
      }
      CurrentSliceIdentity.Source source = base.source();
      if (prepared != null) {
        String runId = base.run().runId();
        String taskKey = base.task().taskKey();
        TaskSourceManifestVerifier.Verified verified = sourceBindings.verifyAndPersist(
            taskKey, prepared.fetched().manifestJson(), prepared.fetched().expected());
        source = sourceBindings.identitySource(verified);
        sourceBindings.bindRun(runId, result.recordingId(), taskKey, verified);
      }
      CurrentSliceIdentity enriched = new CurrentSliceIdentity(
          base.schema(), base.task(), base.run(), base.client(),
          new CurrentSliceIdentity.Capture(capture.getSessionId(), result.recordingId(), recordingHash),
          source, base.graph(), base.model(), base.candidate(), false);
      if (base.run() != null && base.run().runId() != null) {
        // The capture and run identity are joined to this result transaction. A conflict leaves
        // both rows unchanged and the operation UNCERTAIN for explicit reconciliation.
        if (!runs.tryAdvanceIdentity(base.run().runId(), enriched, "CAPTURED")) {
          throw new IllegalStateException("CAPTURE_RUN_IDENTITY_CONFLICT");
        }
      }
      int finalTick = ((Number) request.getOrDefault("final_tick", 0)).intValue();
      capture.setRecordingId(result.recordingId());
      capture.setRecordingHash(recordingHash);
      capture.setVideoPath(result.videoPath());
      capture.setVideoSha256(result.videoSha256());
      capture.setFinalTick(finalTick);
      capture.setIdentityJson(write(enriched));
      capture.setCompleted(true);
      capture.setCompletionStatus("COMPLETED");
      capture.setCompletionError(null);
      capture.setOperation("COMPLETE");
      capture.setOperationState("SUCCEEDED");
      capture.setOperationCorrelationId(operation.getCorrelationId());
      captures.save(capture);
      finish(operation, "SUCCEEDED", Map.of("recording_id", result.recordingId(), "recording_hash", recordingHash, "video_sha256", result.videoSha256()), null);
    } catch (RuntimeException exception) {
      // Roll back every write of this transaction; reconcilable state is recorded separately.
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException(
          exception.getMessage() == null ? "CAPTURE_COMPLETION_UNCERTAIN" : exception.getMessage(),
          exception);
    }
  }

  /**
   * Records reconcilable UNCERTAIN state in its own short transaction after a rollback. Ordinary
   * local database failures are not compensated here: only the UNCERTAIN markers are written, and
   * only when the operation is still owned by this attempt.
   */
  private void recordCompleteUncertain(Long operationId, String attemptId, Exception exception) {
    logger.warn("Capture completion {} (operation {}) entered UNCERTAIN: {}",
        attemptId, operationId, exception.getMessage());
    try {
      transactions.executeWithoutResult(status -> {
        RoutineCaptureOperation operation = owned(operationId, attemptId);
        if (operation == null) return;
        RoutineCaptureRecord capture = captures.findWithLockBySessionId(operation.getSessionId()).orElse(null);
        if (capture == null) {
          finish(operation, "FAILED", Map.of("error", "CAPTURE_SESSION_NOT_FOUND"), "CAPTURE_SESSION_NOT_FOUND");
          return;
        }
        capture.setCompletionStatus("UNCERTAIN");
        capture.setCompletionError(truncate(exception.getMessage(), 500));
        capture.setOperation("COMPLETE");
        capture.setOperationState("UNCERTAIN");
        capture.setOperationCorrelationId(operation.getCorrelationId());
        captures.save(capture);
        finish(operation, "UNCERTAIN",
            Map.of("error", exception.getMessage() == null ? "CAPTURE_COMPLETION_UNCERTAIN" : exception.getMessage()),
            exception.getMessage());
      });
    } catch (RuntimeException failure) {
      logger.error("Could not record UNCERTAIN state for capture operation {}: {}",
          operationId, failure.getMessage());
    }
  }

  /**
   * A completed capture is immutable. A late callback with a different external identity must not
   * downgrade that durable success to UNCERTAIN or overwrite its recording fields; only the
   * retried operation is failed so the mismatch remains visible to reconciliation.
   */
  private void recordCompleteConflict(
      Long operationId, String attemptId, CompletionIdentityConflictException exception) {
    logger.warn("Capture completion identity conflict for operation {}: {}",
        operationId, exception.getMessage());
    try {
      transactions.executeWithoutResult(status -> {
        RoutineCaptureOperation operation = owned(operationId, attemptId);
        if (operation == null) return;
        RoutineCaptureRecord capture = captures.findWithLockBySessionId(operation.getSessionId())
            .orElse(null);
        if (capture != null && capture.isCompleted()) {
          finish(operation, "FAILED", Map.of("error", exception.getMessage()), exception.getMessage());
        } else {
          // The conflict should only be raised for an already completed row. Preserve the normal
          // recovery behavior if a concurrent state change makes that assumption false.
          if (capture != null) {
            capture.setCompletionStatus("UNCERTAIN");
            capture.setCompletionError(truncate(exception.getMessage(), 500));
            captures.save(capture);
          }
          finish(operation, "UNCERTAIN", Map.of("error", exception.getMessage()), exception.getMessage());
        }
      });
    } catch (RuntimeException failure) {
      logger.error("Could not record capture completion identity conflict for operation {}: {}",
          operationId, failure.getMessage());
    }
  }

  private static boolean sameCompletedResult(
      RoutineCaptureRecord capture, CaptureRecordingGateway.CompleteResult result) {
    return result != null && result.success()
        && Objects.equals(capture.getRecordingId(), result.recordingId())
        && Objects.equals(capture.getVideoPath(), result.videoPath())
        && Objects.equals(capture.getVideoSha256(), result.videoSha256());
  }

  private RoutineCaptureOperation owned(Long operationId, String attemptId) {
    RoutineCaptureOperation operation = operations.findWithLockById(operationId).orElse(null);
    if (operation == null || !"LEASED".equals(operation.getState())
        || !attemptId.equals(operation.getAttemptId())) return null;
    return operation;
  }

  private void finish(RoutineCaptureOperation operation, String state, Map<String, Object> result, String error) {
    operation.setState(state);
    operation.setResultJson(write(result));
    operation.setLastError(truncate(error, 1000));
    operation.setLeaseExpiresAt(null);
    operation.setAttemptId(null);
    operation.setNextAttemptAt(Instant.now().plusSeconds("UNCERTAIN".equals(state) ? 30 : 3600));
    operations.save(operation);
  }

  private JsonNode readJson(String value) { try { return mapper.readTree(value); } catch (Exception e) { throw new IllegalStateException("CAPTURE_JSON_INVALID", e); } }
  @SuppressWarnings("unchecked") private Map<String, Object> readMap(String value) { try { return mapper.readValue(value, Map.class); } catch (Exception e) { throw new IllegalStateException("CAPTURE_REQUEST_INVALID", e); } }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("CAPTURE_RESULT_INVALID", e); } }
  private static String truncate(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }

  private static boolean shouldBindSource(CurrentSliceIdentity identity) {
    return identity != null && identity.run() != null && identity.run().runId() != null
        && identity.task() != null
        && (identity.source() != null || identity.client() != null
            && "MICRO6_MANAGED".equals(identity.client().clientKind()));
  }

  private static final class CompletionIdentityConflictException extends RuntimeException {
    private CompletionIdentityConflictException(String message) {
      super(message);
    }
  }
}
