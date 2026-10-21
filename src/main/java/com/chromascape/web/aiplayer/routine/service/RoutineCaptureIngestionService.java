package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.event.RoutineCaptureBatchAccepted;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureBatchRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional capture preparation and ingestion; recorder I/O is owned by the outbox dispatcher.
 */
@Service
public class RoutineCaptureIngestionService {
  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator identities;
  private final RoutineCaptureRecordRepository repository;
  private final RoutineCaptureOperationRepository operations;
  private final RoutineCaptureBatchRepository batches;
  private final CurrentSliceRunRepository runs;
  private final CaptureOperationDispatcher dispatcher;
  private final TickSnapshotRepository snapshots;
  private final ApplicationEventPublisher events;

  public RoutineCaptureIngestionService(
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities,
      RoutineCaptureRecordRepository repository,
      RoutineCaptureOperationRepository operations,
      RoutineCaptureBatchRepository batches,
      CurrentSliceRunRepository runs,
      CaptureOperationDispatcher dispatcher,
      TickSnapshotRepository snapshots,
      ApplicationEventPublisher events) {
    this.mapper = mapper;
    this.identities = identities;
    this.repository = repository;
    this.operations = operations;
    this.batches = batches;
    this.runs = runs;
    this.dispatcher = dispatcher;
    this.snapshots = snapshots;
    this.events = events;
  }

  /**
   * Commits a capture row and immutable START operation before the dispatcher can call the
   * recorder.
   */
  @Transactional
  public Map<String, Object> start(
      RoutineApiDtos.CaptureSessionRequest request, String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    if (request == null || request.identity() == null) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "IDENTITY_FIELD_MISSING",
          "capture identity is required");
    }
    com.chromascape.web.aiplayer.contract.CurrentWireContract.require(
        request.schema(), "routine-capture-session/v1");
    identities.requireOperation(request.identity(), "capture");
    if (!"CHROMASCAPE_WINDOW".equals(request.videoCapture())) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "VIDEO_CAPTURE_UNSUPPORTED",
          "capture must bind CHROMASCAPE_WINDOW");
    }
    identities.requireOperation(request.identity(), "video");
    String requestHash =
        CanonicalJsonHasher.hash(
            mapper.valueToTree(Map.of("endpoint", "/capture/sessions", "request", request)), null);
    RoutineCaptureRecord existing =
        repository.findWithLockByStartIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      if (!requestHash.equals(existing.getStartRequestHash()))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return response(existing);
    }
    CurrentSliceIdentity identity = identities.validate(request.identity());
    identities.requireOperation(identity, "capture");
    if (identity.run() == null
        || identity.run().runId() == null
        || identity.run().runId().isBlank()) {
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "RUN_BINDING_REQUIRED",
          "capture requires a bound run identity");
    }
    CurrentSliceRun boundRun =
        runs.findWithLockByRunId(identity.run().runId()).orElseThrow(() -> notFound("run"));
    // The first idempotency lookup cannot lock a row that does not exist.  Serialize competing
    // creates on the already-bound run, then re-check the unique key after that lock is acquired;
    // otherwise two concurrent first requests could both pass the initial lookup and one would
    // lose with a unique-constraint error instead of receiving the original accepted response.
    existing = repository.findWithLockByStartIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      if (!requestHash.equals(existing.getStartRequestHash()))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return response(existing);
    }
    identities.requireUnchanged(readIdentity(boundRun.getIdentityJson()), identity);

    RoutineCaptureRecord entity = new RoutineCaptureRecord();
    entity.setSessionId("capture_" + UUID.randomUUID().toString().replace("-", ""));
    entity.setStartIdempotencyKey(idempotencyKey);
    entity.setStartRequestHash(requestHash);
    entity.setIdentityJson(write(identity));
    Map<String, String> idempotencies = new LinkedHashMap<>();
    idempotencies.put(idempotencyKey, requestHash);
    entity.setRequestIdempotencyJson(writeJson(idempotencies));
    entity.setStartedAt(
        request.startedAt() == null || request.startedAt().isBlank()
            ? Instant.now()
            : Instant.parse(request.startedAt()));
    entity.setStartTick(request.startTick());
    entity.setCompletionStatus("STARTING");
    repository.save(entity);

    RoutineCaptureOperation operation =
        newOperation(
            entity.getSessionId(),
            "START",
            requestHash,
            startOperationJson(entity, identity, request, idempotencyKey),
            "capture-start-" + entity.getSessionId());
    operations.save(operation);
    entity.setOperation("START");
    entity.setOperationState("PENDING");
    entity.setOperationRequestHash(requestHash);
    entity.setOperationCorrelationId(operation.getCorrelationId());
    repository.save(entity);
    dispatcher.dispatchAfterCommit(operation.getId());
    return response(entity);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> status(String sessionId) {
    RoutineCaptureRecord entity = session(sessionId);
    return response(entity);
  }

  @Transactional
  public Map<String, Object> ingestTicks(
      String sessionId, RoutineApiDtos.BatchRequest request, String key) {
    return ingest(sessionId, request, request == null ? null : request.ticks(), true, key);
  }

  @Transactional
  public Map<String, Object> ingestActions(
      String sessionId, RoutineApiDtos.BatchRequest request, String key) {
    return ingest(sessionId, request, request == null ? null : request.actions(), false, key);
  }

  @Transactional
  public Map<String, Object> reconcile(String sessionId) {
    return reconcile(sessionId, null);
  }

  @Transactional
  public Map<String, Object> reconcile(String sessionId, String idempotencyKey) {
    RoutineCaptureRecord entity =
        repository.findWithLockBySessionId(sessionId).orElseThrow(() -> notFound("session"));
    identities.requireOperation(readIdentity(entity.getIdentityJson()), "capture");
    RoutineCaptureOperation operation =
        operations
            .findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
            .flatMap(candidate -> operations.findWithLockById(candidate.getId()))
            .orElse(null);
    if (operation == null) return response(entity);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Map<String, String> known = globalIdempotencies(entity);
      String requestHash = known.get(idempotencyKey);
      if (requestHash == null || !requestHash.equals(operation.getRequestHash())) {
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "reconcile key is not the original operation key");
      }
    }
    if ("SUCCEEDED".equals(operation.getState())) return response(entity);
    if ("LEASED".equals(operation.getState())
        && operation.getLeaseExpiresAt() != null
        && operation.getLeaseExpiresAt().isAfter(Instant.now())) return response(entity);
    operation.setState("PENDING");
    operation.setAttemptId(null);
    operation.setLeaseExpiresAt(null);
    operation.setNextAttemptAt(Instant.now());
    operations.save(operation);
    dispatcher.reconcile(operation.getId());
    return response(entity);
  }

  /** Completion blocks ingestion and commits a COMPLETE operation; stop I/O happens afterwards. */
  @Transactional
  public Map<String, Object> complete(
      String sessionId, RoutineApiDtos.CompleteRequest request, String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    if (request == null)
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "CAPTURE_REQUEST_INVALID",
          "completion request is required");
    com.chromascape.web.aiplayer.contract.CurrentWireContract.require(
        request.schema(), "routine-capture-complete/v1");
    RoutineCaptureRecord entity =
        repository.findWithLockBySessionId(sessionId).orElseThrow(() -> notFound("session"));
    identities.requireOperation(readIdentity(entity.getIdentityJson()), "capture");
    ObjectNode requestNode = mapper.createObjectNode();
    requestNode.put("endpoint", "/capture/sessions/" + sessionId + "/complete");
    requestNode.put("session_id", sessionId);
    requestNode.put("final_tick", request.finalTick());
    requestNode.put("tick_sequence", request.tickSequence());
    requestNode.put("action_sequence", request.actionSequence());
    requestNode.put("video_sha256", request.videoSha256() == null ? "" : request.videoSha256());
    String completionHash = CanonicalJsonHasher.hash(requestNode, null);
    Map<String, String> all = globalIdempotencies(entity);
    String previous = all.get(idempotencyKey);
    if (previous != null) {
      if (!completionHash.equals(previous))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return response(entity);
    }
    if (entity.isCompleted()) {
      if (!completionHash.equals(entity.getCompletionRequestHash()))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "completion content differs");
      return response(entity);
    }
    if (!"RECORDING".equals(entity.getCompletionStatus())) {
      conflict("CAPTURE_NOT_RECORDING", "capture must be RECORDING before completion");
    }
    if (request.tickSequence() != entity.getNextTickSequence() - 1
        || request.actionSequence() != entity.getNextActionSequence() - 1) {
      conflict("CAPTURE_SEQUENCE_GAP", "final sequence does not match accepted batches");
    }
    all.put(idempotencyKey, completionHash);
    entity.setRequestIdempotencyJson(writeJson(all));
    entity.setCompletionRequestHash(completionHash);
    entity.setCompletionStatus("COMPLETING");
    entity.setFinalTick(request.finalTick());
    repository.save(entity);

    Map<String, Object> operationRequest = new LinkedHashMap<>();
    operationRequest.put("endpoint", "/capture/sessions/" + sessionId + "/complete");
    operationRequest.put("session_id", sessionId);
    operationRequest.put("identity", readIdentity(entity.getIdentityJson()));
    operationRequest.put("recording_id", entity.getRecordingId());
    operationRequest.put("debug_session_id", entity.getDebugSessionId());
    operationRequest.put("final_tick", request.finalTick());
    operationRequest.put("tick_sequence", request.tickSequence());
    operationRequest.put("action_sequence", request.actionSequence());
    operationRequest.put(
        "video_sha256", request.videoSha256() == null ? "" : request.videoSha256());
    operationRequest.put("idempotency_key", idempotencyKey);
    RoutineCaptureOperation operation =
        newOperation(
            sessionId,
            "COMPLETE",
            completionHash,
            writeJson(operationRequest),
            "capture-complete-" + sessionId);
    operations.save(operation);
    entity.setOperation("COMPLETE");
    entity.setOperationState("PENDING");
    entity.setOperationRequestHash(completionHash);
    entity.setOperationCorrelationId(operation.getCorrelationId());
    repository.save(entity);
    dispatcher.dispatchAfterCommit(operation.getId());
    return response(entity);
  }

  public void requireRecordingOperation(String recordingId, String operation) {
    RoutineCaptureRecord entity =
        repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    identities.requireOperation(readIdentity(entity.getIdentityJson()), operation);
  }

  /** Current finalized recording provenance used by dataset/model consumers. No mutation occurs. */
  public CurrentSliceIdentity recordingIdentity(
      String recordingId, String operation, String debugSessionId) {
    RoutineCaptureRecord entity =
        repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    CurrentSliceIdentity identity = readIdentity(entity.getIdentityJson());
    identities.requireOperation(identity, operation);
    if (!entity.isCompleted()
        || identity.capture() == null
        || !recordingId.equals(identity.capture().recordingId())
        || !java.util.Objects.equals(entity.getRecordingHash(), identity.capture().recordingHash())
        || (debugSessionId != null && !debugSessionId.equals(entity.getDebugSessionId()))) {
      throw new com.chromascape.web.aiplayer.contract.CurrentSliceContractException(
          com.chromascape.web.aiplayer.contract.CurrentSliceFailureCode.IDENTITY_MISMATCH,
          "finalized recording identity differs from its capture binding");
    }
    return identity;
  }

  /** Current live observation binding; a generic debug session cannot masquerade as a capture. */
  public CurrentSliceIdentity debugSessionIdentity(String debugSessionId, String operation) {
    RoutineCaptureRecord entity =
        repository
            .findByDebugSessionId(debugSessionId)
            .orElseThrow(() -> notFound("current capture session"));
    CurrentSliceIdentity identity = readIdentity(entity.getIdentityJson());
    identities.requireOperation(identity, operation);
    return identity;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> recording(String recordingId) {
    RoutineCaptureRecord entity =
        repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    return response(entity);
  }

  @Transactional(readOnly = true)
  public List<JsonNode> ticks(String recordingId) {
    return values(
        repository
            .findByRecordingId(recordingId)
            .orElseThrow(() -> notFound("recording"))
            .getTicksJson());
  }

  @Transactional(readOnly = true)
  public List<JsonNode> actions(String recordingId) {
    return values(
        repository
            .findByRecordingId(recordingId)
            .orElseThrow(() -> notFound("recording"))
            .getActionsJson());
  }

  @Transactional(readOnly = true)
  public List<JsonNode> actionsForSession(String sessionId) {
    return values(session(sessionId).getActionsJson());
  }

  private Map<String, Object> ingest(
      String sessionId,
      RoutineApiDtos.BatchRequest request,
      List<JsonNode> incoming,
      boolean ticks,
      String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    if (request == null)
      throw error(
          HttpStatus.UNPROCESSABLE_ENTITY, "CAPTURE_REQUEST_INVALID", "batch request is required");
    com.chromascape.web.aiplayer.contract.CurrentWireContract.require(
        request.schema(), ticks ? "routine-tick-batch/v1" : "routine-action-batch/v1");
    RoutineCaptureRecord entity =
        repository.findWithLockBySessionId(sessionId).orElseThrow(() -> notFound("session"));
    identities.requireOperation(readIdentity(entity.getIdentityJson()), "capture");
    if (request.sessionId() != null && !sessionId.equals(request.sessionId()))
      conflict("IDENTITY_MISMATCH", "batch session_id does not match path");
    if (!"RECORDING".equals(entity.getCompletionStatus()))
      conflict("CAPTURE_BOUNDARY_UNCERTAIN", "ticks/actions are accepted only in RECORDING");
    if (entity.getDebugSessionId() == null
        || entity.getRecordingId() == null
        || entity.getDebugSessionId().isBlank())
      conflict("CAPTURE_NOT_RECORDING", "capture has no bound recording");
    if (incoming == null || incoming.isEmpty())
      conflict("CAPTURE_SEQUENCE_GAP", "batch values are required");
    if (incoming.size() > 500) conflict("CAPTURE_BATCH_LIMIT", "batch exceeds 500 values");
    List<JsonNode> retained = values(ticks ? entity.getTicksJson() : entity.getActionsJson());
    if (retained.size() + incoming.size() > 50_000)
      conflict("CAPTURE_TOTAL_LIMIT", "capture exceeds 50,000 values");
    int sequence = request.sequence();
    String content =
        CanonicalJsonHasher.canonical(
            mapper.valueToTree(
                Map.of(
                    "method",
                    "POST",
                    "endpoint",
                    "/api/v1/ai-routine/capture/sessions/"
                        + sessionId
                        + (ticks ? "/ticks" : "/actions"),
                    "session_id",
                    sessionId,
                    "sequence",
                    sequence,
                    "values",
                    incoming)));
    Map<String, String> allIdempotencies = globalIdempotencies(entity);
    String globalPrior = allIdempotencies.get(idempotencyKey);
    if (globalPrior != null) {
      if (!content.equals(globalPrior))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return batchResponse(entity, sequence, incoming.size(), content);
    }
    Map<String, String> idempotencies = idempotencies(entity, ticks);
    String priorKey = idempotencies.get(idempotencyKey);
    if (priorKey != null) {
      if (!content.equals(priorKey))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return batchResponse(entity, sequence, incoming.size(), content);
    }
    Map<String, String> seen = sequences(entity, ticks);
    String prior = seen.get(Integer.toString(sequence));
    if (prior != null) {
      if (!content.equals(prior))
        conflict("DUPLICATE_SEQUENCE_CONFLICT", "sequence content differs");
      return batchResponse(entity, sequence, incoming.size(), content);
    }
    int expected = ticks ? entity.getNextTickSequence() : entity.getNextActionSequence();
    if (sequence != expected) conflict("CAPTURE_SEQUENCE_GAP", "expected sequence " + expected);
    if (allIdempotencies.size() >= 2000)
      conflict("CAPTURE_IDEMPOTENCY_LIMIT", "capture idempotency keys exceed 2000");
    seen.put(Integer.toString(sequence), content);
    idempotencies.put(idempotencyKey, content);
    allIdempotencies.put(idempotencyKey, content);
    entity.setRequestIdempotencyJson(writeJson(allIdempotencies));
    if (ticks) {
      List<JsonNode> all = values(entity.getTicksJson());
      all.addAll(incoming);
      entity.setTicksJson(writeJson(all));
      entity.setTickSequencesJson(writeJson(seen));
      entity.setTickIdempotencyJson(writeJson(idempotencies));
      entity.setNextTickSequence(expected + 1);
      persistDebuggerTicks(entity.getDebugSessionId(), incoming);
    } else {
      List<JsonNode> all = values(entity.getActionsJson());
      all.addAll(incoming);
      entity.setActionsJson(writeJson(all));
      entity.setActionSequencesJson(writeJson(seen));
      entity.setActionIdempotencyJson(writeJson(idempotencies));
      entity.setNextActionSequence(expected + 1);
    }
    repository.save(entity);
    com.chromascape.web.aiplayer.routine.entity.RoutineCaptureBatch accepted =
        new com.chromascape.web.aiplayer.routine.entity.RoutineCaptureBatch();
    accepted.setSessionId(entity.getSessionId());
    accepted.setKind(ticks ? "TICKS" : "ACTIONS");
    accepted.setSequence(sequence);
    accepted.setValuesJson(writeJson(incoming));
    batches.save(accepted);
    events.publishEvent(
        new RoutineCaptureBatchAccepted(
            entity.getSessionId(), ticks ? "TICKS" : "ACTIONS", sequence, incoming));
    return batchResponse(entity, sequence, incoming.size(), content);
  }

  private RoutineCaptureOperation newOperation(
      String sessionId,
      String kind,
      String requestHash,
      String requestJson,
      String correlationPrefix) {
    RoutineCaptureOperation operation = new RoutineCaptureOperation();
    operation.setSessionId(sessionId);
    operation.setOperation(kind);
    operation.setRequestHash(requestHash);
    operation.setRequestJson(requestJson);
    operation.setCorrelationId(correlationPrefix + "-" + UUID.randomUUID());
    operation.setState("PENDING");
    operation.setNextAttemptAt(Instant.now());
    operation.touch();
    return operation;
  }

  private String startOperationJson(
      RoutineCaptureRecord entity,
      CurrentSliceIdentity identity,
      RoutineApiDtos.CaptureSessionRequest request,
      String idempotencyKey) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("endpoint", "/capture/sessions");
    value.put("session_id", entity.getSessionId());
    value.put("task_key", identity.task().taskKey());
    value.put("identity", identity);
    value.put("started_at", request.startedAt());
    value.put("start_tick", request.startTick());
    value.put("video_capture", request.videoCapture());
    value.put("idempotency_key", idempotencyKey);
    return writeJson(value);
  }

  private void persistDebuggerTicks(String debugSessionId, List<JsonNode> values) {
    if (debugSessionId == null || debugSessionId.isBlank()) return;
    for (JsonNode tick : values) {
      JsonNode player = tick.get("player");
      if (player == null) player = mapper.createObjectNode();
      TickSnapshot snapshot =
          new TickSnapshot(
              debugSessionId, tick.path("tick").asLong(), java.time.LocalDateTime.now(), player);
      snapshot.setInventory(tick.get("inventory"));
      snapshot.setNpcs(tick.get("nearby_npcs"));
      snapshot.setSchemaVersion(2);
      snapshots.save(snapshot);
    }
  }

  private Map<String, String> globalIdempotencies(RoutineCaptureRecord entity) {
    return stringMap(entity.getRequestIdempotencyJson());
  }

  private Map<String, String> idempotencies(RoutineCaptureRecord entity, boolean ticks) {
    return stringMap(ticks ? entity.getTickIdempotencyJson() : entity.getActionIdempotencyJson());
  }

  private Map<String, String> sequences(RoutineCaptureRecord entity, boolean ticks) {
    return stringMap(ticks ? entity.getTickSequencesJson() : entity.getActionSequencesJson());
  }

  private Map<String, String> stringMap(String json) {
    Map<String, String> result = new LinkedHashMap<>();
    readJson(json)
        .fields()
        .forEachRemaining(field -> result.put(field.getKey(), field.getValue().asText()));
    return result;
  }

  private Map<String, Object> batchResponse(
      RoutineCaptureRecord entity, int sequence, int count, String content) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("session_id", entity.getSessionId());
    result.put("sequence", sequence);
    result.put("accepted", count);
    result.put("content", content);
    result.put("next_tick_sequence", entity.getNextTickSequence());
    result.put("next_action_sequence", entity.getNextActionSequence());
    result.put("control_allowed", false);
    return result;
  }

  private Map<String, Object> response(RoutineCaptureRecord entity) {
    CurrentSliceIdentity identity = readIdentity(entity.getIdentityJson());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("session_id", entity.getSessionId());
    result.put("recording_id", entity.getRecordingId());
    result.put("recording_hash", entity.getRecordingHash());
    result.put("status", statusOf(entity));
    result.put(
        "completion_status",
        entity.getCompletionStatus() == null ? "STARTING" : entity.getCompletionStatus());
    result.put("completion_error", entity.getCompletionError());
    result.put("next_tick_sequence", entity.getNextTickSequence());
    result.put("next_action_sequence", entity.getNextActionSequence());
    result.put("tick_count", values(entity.getTicksJson()).size());
    result.put("action_count", values(entity.getActionsJson()).size());
    result.put("final_tick", entity.getFinalTick());
    result.put("identity", identity);
    result.put("operation", operationResponse(entity.getSessionId()));
    result.put("control_allowed", false);
    return result;
  }

  private Map<String, Object> operationResponse(String sessionId) {
    RoutineCaptureOperation operation =
        operations.findFirstBySessionIdOrderByCreatedAtDesc(sessionId).orElse(null);
    if (operation == null) return null;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", operation.getOperation());
    result.put("state", operation.getState());
    result.put("correlation_id", operation.getCorrelationId());
    result.put("attempts", operation.getAttempts());
    result.put("last_error", operation.getLastError());
    return result;
  }

  private String statusOf(RoutineCaptureRecord entity) {
    String status = entity.getCompletionStatus();
    if (status == null || status.isBlank()) return entity.isCompleted() ? "COMPLETED" : "STARTING";
    return status;
  }

  private RoutineCaptureRecord session(String id) {
    return repository.findBySessionId(id).orElseThrow(() -> notFound("session"));
  }

  private JsonNode readJson(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("persisted capture JSON is invalid", e);
    }
  }

  private String write(CurrentSliceIdentity identity) {
    return writeJson(mapper.valueToTree(identity));
  }

  private String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("invalid capture JSON", e);
    }
  }

  private CurrentSliceIdentity readIdentity(String json) {
    try {
      return identities.validate(mapper.readValue(json, CurrentSliceIdentity.class));
    } catch (com.chromascape.web.aiplayer.contract.CurrentSliceContractException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("persisted identity is invalid", e);
    }
  }

  private List<JsonNode> values(String json) {
    List<JsonNode> result = new ArrayList<>();
    readJson(json).elements().forEachRemaining(result::add);
    return result;
  }

  private static String truncate(String value, int max) {
    return value == null ? null : value.length() <= max ? value : value.substring(0, max);
  }

  private static void requireIdempotency(String key) {
    if (key == null || key.isBlank())
      conflict("IDENTITY_FIELD_MISSING", "Idempotency-Key is required");
  }

  private static void conflict(String code, String message) {
    throw error(HttpStatus.CONFLICT, code, message);
  }

  private static RoutineCaptureException error(HttpStatus status, String code, String message) {
    return new RoutineCaptureException(status, code, message);
  }

  private static RoutineCaptureException notFound(String resource) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " not found");
  }

  public static final class RoutineCaptureException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public RoutineCaptureException(HttpStatus status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public HttpStatus status() {
      return status;
    }

    public String code() {
      return code;
    }
  }
}
