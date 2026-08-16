package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies an external recorder result only while the claimed operation attempt is still owner. */
@Service
public class CaptureOperationResultService {
  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator identities;
  private final RoutineCaptureOperationRepository operations;
  private final RoutineCaptureRecordRepository captures;
  private final CurrentSliceRunService runs;

  public CaptureOperationResultService(
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities,
      RoutineCaptureOperationRepository operations,
      RoutineCaptureRecordRepository captures,
      CurrentSliceRunService runs) {
    this.mapper = mapper;
    this.identities = identities;
    this.operations = operations;
    this.captures = captures;
    this.runs = runs;
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

  @Transactional
  public void applyComplete(Long operationId, String attemptId, CaptureRecordingGateway.CompleteResult result) {
    RoutineCaptureOperation operation = owned(operationId, attemptId);
    if (operation == null) return;
    RoutineCaptureRecord capture = captures.findWithLockBySessionId(operation.getSessionId()).orElse(null);
    if (capture == null) {
      finish(operation, "FAILED", Map.of("error", "CAPTURE_SESSION_NOT_FOUND"), "CAPTURE_SESSION_NOT_FOUND");
      return;
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
      CurrentSliceIdentity enriched = new CurrentSliceIdentity(
          base.schema(), base.task(), base.run(), base.client(),
          new CurrentSliceIdentity.Capture(capture.getSessionId(), result.recordingId(), recordingHash),
          base.source(), base.graph(), base.model(), base.candidate(), false);
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
    } catch (Exception exception) {
      capture.setCompletionStatus("UNCERTAIN");
      capture.setCompletionError(truncate(exception.getMessage(), 500));
      capture.setOperation("COMPLETE");
      capture.setOperationState("UNCERTAIN");
      capture.setOperationCorrelationId(operation.getCorrelationId());
      captures.save(capture);
      finish(operation, "UNCERTAIN", Map.of("error", exception.getMessage() == null ? "CAPTURE_COMPLETION_UNCERTAIN" : exception.getMessage()), exception.getMessage());
    }
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
}
