package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineCaptureIngestionService {
  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator identities;
  private final RoutineCaptureRecordRepository repository;
  private final CurrentSliceRunRepository runs;
  private final DebugSessionService debugSessions;
  private final RecordingSessionRepository recordingSessions;
  private final TickSnapshotRepository snapshots;

  public RoutineCaptureIngestionService(
      ObjectMapper mapper,
      CurrentSliceIdentityValidator identities,
      RoutineCaptureRecordRepository repository,
      CurrentSliceRunRepository runs,
      DebugSessionService debugSessions,
      RecordingSessionRepository recordingSessions,
      TickSnapshotRepository snapshots) {
    this.mapper = mapper;
    this.identities = identities;
    this.repository = repository;
    this.runs = runs;
    this.debugSessions = debugSessions;
    this.recordingSessions = recordingSessions;
    this.snapshots = snapshots;
  }

  @Transactional
  public synchronized Map<String, Object> start(RoutineApiDtos.CaptureSessionRequest request, String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    String requestHash = CanonicalJsonHasher.hash(mapper.valueToTree(Map.of("endpoint", "/capture/sessions", "request", request)), null);
    RoutineCaptureRecord existing = repository.findByStartIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      if (!requestHash.equals(existing.getStartRequestHash())) conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return response(existing, readIdentity(existing.getIdentityJson()));
    }
    CurrentSliceIdentity identity = identities.validate(request.identity());
    RoutineCaptureRecord entity = new RoutineCaptureRecord();
    entity.setSessionId("capture_" + UUID.randomUUID().toString().replace("-", ""));
    entity.setStartIdempotencyKey(idempotencyKey);
    entity.setStartRequestHash(requestHash);
    entity.setIdentityJson(write(identity));
    Map<String, String> startIdempotencies = new LinkedHashMap<>(); startIdempotencies.put(idempotencyKey, requestHash); entity.setRequestIdempotencyJson(writeJson(startIdempotencies));
    entity.setStartedAt(request.startedAt() == null || request.startedAt().isBlank() ? Instant.now() : Instant.parse(request.startedAt()));
    entity.setStartTick(request.startTick());
    if (request.videoCapture() == null || !"CHROMASCAPE_WINDOW".equals(request.videoCapture())) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_UNSUPPORTED", "capture must bind CHROMASCAPE_WINDOW");
    }
    SessionStartResponse debug = debugSessions.startSession(
        "AI_ROUTINE_CAPTURE", identity.task().taskKey(), true,
        com.chromascape.web.recording.config.RecordingTriggerSource.MANUAL,
        entity.getSessionId());
    if (debug.getRecordingId() == null || debug.getSessionId() == null) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_UNAVAILABLE", "Chroma window recording could not be started");
    }
    entity.setDebugSessionId(debug.getSessionId());
    entity.setRecordingId(debug.getRecordingId());
    return response(repository.save(entity), identity);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> status(String sessionId) {
    RoutineCaptureRecord entity = session(sessionId);
    return response(entity, readIdentity(entity.getIdentityJson()));
  }

  public Map<String, Object> ingestTicks(String sessionId, RoutineApiDtos.BatchRequest request, String key) {
    return ingest(sessionId, request, request.ticks(), true, key);
  }

  public Map<String, Object> ingestActions(String sessionId, RoutineApiDtos.BatchRequest request, String key) {
    return ingest(sessionId, request, request.actions(), false, key);
  }

  @Transactional
  private synchronized Map<String, Object> ingest(
      String sessionId, RoutineApiDtos.BatchRequest request, List<JsonNode> values, boolean ticks, String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    RoutineCaptureRecord entity = session(sessionId);
    if (request.sessionId() != null && !sessionId.equals(request.sessionId())) conflict("IDENTITY_MISMATCH", "batch session_id does not match path");
    if (entity.isCompleted()) conflict("CAPTURE_BOUNDARY_UNCERTAIN", "session is complete");
    if (values == null) conflict("CAPTURE_SEQUENCE_GAP", "batch values are required");
    int sequence = request.sequence();
    String content = CanonicalJsonHasher.canonical(mapper.valueToTree(Map.of("method", "POST", "endpoint", "/api/v1/ai-routine/capture/sessions/" + sessionId + (ticks ? "/ticks" : "/actions"), "session_id", sessionId, "sequence", request.sequence(), "values", values)));
    Map<String, String> allIdempotencies = globalIdempotencies(entity);
    String globalPrior = allIdempotencies.get(idempotencyKey);
    if (globalPrior != null) { if (!content.equals(globalPrior)) conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs"); return batchResponse(entity, sequence, values.size(), content); }
    Map<String, String> idempotencies = idempotencies(entity, ticks);
    String priorKey = idempotencies.get(idempotencyKey);
    if (priorKey != null) {
      if (!content.equals(priorKey)) conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs");
      return batchResponse(entity, sequence, values.size(), content);
    }
    Map<String, String> seen = sequences(entity, ticks);
    String prior = seen.get(Integer.toString(sequence));
    if (prior != null) {
      if (!content.equals(prior)) conflict("DUPLICATE_SEQUENCE_CONFLICT", "sequence content differs");
      return batchResponse(entity, sequence, values.size(), content);
    }
    int expected = ticks ? entity.getNextTickSequence() : entity.getNextActionSequence();
    if (sequence != expected) conflict("CAPTURE_SEQUENCE_GAP", "expected sequence " + expected);
    seen.put(Integer.toString(sequence), content);
    idempotencies.put(idempotencyKey, content);
    allIdempotencies.put(idempotencyKey, content);
    entity.setRequestIdempotencyJson(writeJson(allIdempotencies));
    if (ticks) {
      List<JsonNode> all = values(entity.getTicksJson());
      all.addAll(values);
      entity.setTicksJson(writeJson(all));
      entity.setTickSequencesJson(writeJson(seen));
      entity.setTickIdempotencyJson(writeJson(idempotencies));
      entity.setNextTickSequence(expected + 1);
      persistDebuggerTicks(entity.getDebugSessionId(), values);
    } else {
      List<JsonNode> all = values(entity.getActionsJson());
      all.addAll(values);
      entity.setActionsJson(writeJson(all));
      entity.setActionSequencesJson(writeJson(seen));
      entity.setActionIdempotencyJson(writeJson(idempotencies));
      entity.setNextActionSequence(expected + 1);
    }
    repository.save(entity);
    return batchResponse(entity, sequence, values.size(), content);
  }

  @Transactional
  public synchronized Map<String, Object> complete(String sessionId, RoutineApiDtos.CompleteRequest request, String idempotencyKey) {
    requireIdempotency(idempotencyKey);
    RoutineCaptureRecord entity = session(sessionId);
    ObjectNode completionRequest = mapper.createObjectNode();
    completionRequest.put("endpoint", "/capture/sessions/" + sessionId + "/complete");
    completionRequest.put("session_id", sessionId);
    completionRequest.put("final_tick", request.finalTick());
    completionRequest.put("tick_sequence", request.tickSequence());
    completionRequest.put("action_sequence", request.actionSequence());
    completionRequest.put("video_sha256", request.videoSha256() == null ? "" : request.videoSha256());
    String completionHash = CanonicalJsonHasher.hash(completionRequest, null);
    Map<String, String> allIdempotencies = globalIdempotencies(entity);
    String priorCompletion = allIdempotencies.get(idempotencyKey);
    if (priorCompletion != null) { if (!completionHash.equals(priorCompletion)) conflict("DUPLICATE_SEQUENCE_CONFLICT", "idempotency key content differs"); return response(entity, readIdentity(entity.getIdentityJson())); }
    allIdempotencies.put(idempotencyKey, completionHash); entity.setRequestIdempotencyJson(writeJson(allIdempotencies));
    if (entity.isCompleted()) {
      if (!completionHash.equals(entity.getCompletionRequestHash())) conflict("DUPLICATE_SEQUENCE_CONFLICT", "completion content differs");
      return response(entity, readIdentity(entity.getIdentityJson()));
    }
    if (request.tickSequence() != entity.getNextTickSequence() - 1
        || request.actionSequence() != entity.getNextActionSequence() - 1) {
      conflict("CAPTURE_SEQUENCE_GAP", "final sequence does not match accepted batches");
    }
    if (entity.getDebugSessionId() == null || entity.getRecordingId() == null) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_UNAVAILABLE", "capture has no bound Chroma recording");
    }
    debugSessions.stopSession(entity.getDebugSessionId(), (long) request.finalTick(), true);
    RecordingSession video = recordingSessions.findByRecordingId(entity.getRecordingId())
        .orElseThrow(() -> new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_MISSING", "recording metadata was not persisted"));
    if (video.getVideoPath() == null || video.getVideoPath().isBlank() || !Files.isRegularFile(Path.of(video.getVideoPath()))) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_MISSING", "finalized Chroma video is missing");
    }
    String actualVideoHash = sha256(Path.of(video.getVideoPath()));
    if (request.videoSha256() != null && !request.videoSha256().isBlank() && !request.videoSha256().equals(actualVideoHash)) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_HASH_MISMATCH", "supplied video hash differs from finalized video");
    }
    ObjectNode payload = mapper.createObjectNode();
    payload.set("identity", readJson(entity.getIdentityJson()));
    payload.set("ticks", readJson(entity.getTicksJson()));
    payload.set("actions", readJson(entity.getActionsJson()));
    payload.put("video_sha256", actualVideoHash);
    String recordingHash = CanonicalJsonHasher.hash(payload, null);
    entity.setCompletionRequestHash(completionHash);
    entity.setRecordingHash(recordingHash);
    entity.setVideoPath(video.getVideoPath());
    CurrentSliceIdentity baseIdentity = readIdentity(entity.getIdentityJson());
    CurrentSliceIdentity enrichedIdentity = new CurrentSliceIdentity(
        baseIdentity.schema(), baseIdentity.task(), baseIdentity.run(), baseIdentity.client(),
        new CurrentSliceIdentity.Capture(entity.getSessionId(), entity.getRecordingId(), recordingHash),
        baseIdentity.source(), baseIdentity.graph(), baseIdentity.model(), baseIdentity.candidate(), false);
    entity.setIdentityJson(write(enrichedIdentity));
    entity.setVideoSha256(actualVideoHash);
    entity.setFinalTick(request.finalTick());
    entity.setCompleted(true);
    repository.save(entity);
    if (baseIdentity.run() != null && baseIdentity.run().runId() != null) {
      CurrentSliceRun run = runs.findByRunId(baseIdentity.run().runId()).orElseThrow(() -> notFound("run"));
      CurrentSliceIdentityValidator identityValidator = identities;
      identityValidator.requireUnchanged(readIdentity(run.getIdentityJson()), enrichedIdentity);
      run.setIdentityJson(write(enrichedIdentity)); run.setStage("CAPTURED"); runs.save(run);
    }
    return response(entity, readIdentity(entity.getIdentityJson()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> recording(String recordingId) {
    RoutineCaptureRecord entity = repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    return response(entity, readIdentity(entity.getIdentityJson()));
  }

  @Transactional(readOnly = true)
  public List<JsonNode> ticks(String recordingId) {
    RoutineCaptureRecord entity = repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    return values(entity.getTicksJson());
  }

  @Transactional(readOnly = true)
  public List<JsonNode> actions(String recordingId) {
    RoutineCaptureRecord entity = repository.findByRecordingId(recordingId).orElseThrow(() -> notFound("recording"));
    return values(entity.getActionsJson());
  }

  @Transactional(readOnly = true)
  public List<JsonNode> actionsForSession(String sessionId) {
    return values(session(sessionId).getActionsJson());
  }

  private void persistDebuggerTicks(String debugSessionId, List<JsonNode> ticks) {
    if (debugSessionId == null || debugSessionId.isBlank()) return;
    for (JsonNode tick : ticks) {
      JsonNode player = tick.get("player");
      if (player == null) player = mapper.createObjectNode();
      TickSnapshot snapshot = new TickSnapshot(debugSessionId, tick.path("tick").asLong(), java.time.LocalDateTime.now(), player);
      snapshot.setInventory(tick.get("inventory")); snapshot.setNpcs(tick.get("nearby_npcs")); snapshot.setSchemaVersion(2);
      snapshots.save(snapshot);
    }
  }

  private Map<String, String> globalIdempotencies(RoutineCaptureRecord entity) {
    JsonNode node = readJson(entity.getRequestIdempotencyJson());
    Map<String, String> result = new LinkedHashMap<>(); node.fields().forEachRemaining(field -> result.put(field.getKey(), field.getValue().asText())); return result;
  }

  private Map<String, String> idempotencies(RoutineCaptureRecord entity, boolean ticks) {
    JsonNode node = readJson(ticks ? entity.getTickIdempotencyJson() : entity.getActionIdempotencyJson());
    Map<String, String> result = new LinkedHashMap<>();
    node.fields().forEachRemaining(field -> result.put(field.getKey(), field.getValue().asText()));
    return result;
  }

  private Map<String, String> sequences(RoutineCaptureRecord entity, boolean ticks) {
    JsonNode node = readJson(ticks ? entity.getTickSequencesJson() : entity.getActionSequencesJson());
    Map<String, String> result = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      result.put(field.getKey(), field.getValue().asText());
    }
    return result;
  }

  private Map<String, Object> batchResponse(RoutineCaptureRecord entity, int sequence, int count, String content) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("session_id", entity.getSessionId());
    response.put("sequence", sequence);
    response.put("accepted", count);
    response.put("content", content);
    response.put("control_allowed", false);
    return response;
  }

  private Map<String, Object> response(RoutineCaptureRecord entity, CurrentSliceIdentity identity) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("session_id", entity.getSessionId());
    result.put("recording_id", entity.getRecordingId());
    result.put("recording_hash", entity.getRecordingHash());
    result.put("status", entity.isCompleted() ? "COMPLETED" : "RECORDING");
    result.put("next_tick_sequence", entity.getNextTickSequence());
    result.put("next_action_sequence", entity.getNextActionSequence());
    result.put("final_tick", entity.getFinalTick());
    result.put("identity", identity);
    result.put("control_allowed", false);
    return result;
  }

  private RoutineCaptureRecord session(String id) {
    return repository.findBySessionId(id).orElseThrow(() -> notFound("session"));
  }

  private String canonicalValues(List<JsonNode> values) {
    return CanonicalJsonHasher.canonical(mapper.valueToTree(values));
  }

  private String write(CurrentSliceIdentity identity) { return writeJson(mapper.valueToTree(identity)); }
  private String writeJson(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalArgumentException("invalid capture JSON", e); }
  }
  private JsonNode readJson(String json) {
    try { return mapper.readTree(json); }
    catch (Exception e) { throw new IllegalStateException("persisted capture JSON is invalid", e); }
  }
  private CurrentSliceIdentity readIdentity(String json) {
    try { return identities.validate(mapper.readValue(json, CurrentSliceIdentity.class)); }
    catch (Exception e) { throw new IllegalStateException("persisted identity is invalid", e); }
  }
  private List<JsonNode> values(String json) {
    List<JsonNode> result = new ArrayList<>();
    readJson(json).elements().forEachRemaining(result::add);
    return result;
  }
  private String sha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
      }
      StringBuilder hex = new StringBuilder(64);
      for (byte value : digest.digest()) hex.append(String.format("%02x", value));
      return "sha256:" + hex;
    } catch (Exception e) {
      throw new RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_CAPTURE_MISSING", "video hash could not be calculated");
    }
  }

  private static void requireIdempotency(String key) { if (key == null || key.isBlank()) conflict("IDENTITY_FIELD_MISSING", "Idempotency-Key is required"); }
  private static void conflict(String code, String message) { throw new RoutineCaptureException(HttpStatus.CONFLICT, code, message); }
  private static RoutineCaptureException notFound(String resource) { return new RoutineCaptureException(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " not found"); }

  public static final class RoutineCaptureException extends RuntimeException {
    final HttpStatus status; final String code;
    public RoutineCaptureException(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    public HttpStatus status() { return status; }
    public String code() { return code; }
  }
}
