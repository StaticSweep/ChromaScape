package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.entity.RoutineModel;
import com.chromascape.web.aiplayer.routine.entity.RoutineReplayTrace;
import com.chromascape.web.aiplayer.routine.entity.RoutineShadowDecision;
import com.chromascape.web.aiplayer.routine.entity.RoutineShadowRun;
import com.chromascape.web.aiplayer.routine.event.RoutineCaptureBatchAccepted;
import com.chromascape.web.aiplayer.routine.repository.RoutineModelRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineReplayTraceRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineShadowDecisionRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineShadowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns durable, read-only shadow state. It has no control or input-dispatch dependency. */
@Service
public class RoutineShadowService {
  private final ObjectMapper mapper;
  private final RoutineModelRepository models;
  private final RoutineReplayTraceRepository traces;
  private final RoutineShadowRunRepository runs;
  private final RoutineShadowDecisionRepository decisions;
  private final RoutineCaptureIngestionService captures;
  private final RoutineShadowCompatibilityValidator compatibility = new RoutineShadowCompatibilityValidator();

  public RoutineShadowService(ObjectMapper mapper, RoutineModelRepository models,
      RoutineReplayTraceRepository traces, RoutineShadowRunRepository runs,
      RoutineShadowDecisionRepository decisions, RoutineCaptureIngestionService captures) {
    this.mapper = mapper;
    this.models = models;
    this.traces = traces;
    this.runs = runs;
    this.decisions = decisions;
    this.captures = captures;
  }

  @Transactional
  public Map<String, Object> create(String modelId, String sessionId) {
    if (modelId == null || modelId.isBlank() || sessionId == null || sessionId.isBlank()) {
      throw error("SHADOW_REQUEST_INVALID", "model_id and session_id are required");
    }
    RoutineShadowRun existing = runs.findByModelIdAndSessionId(modelId, sessionId).orElse(null);
    if (existing != null) return response(existing);

    Map<String, Object> session = captures.status(sessionId);
    if (!"RECORDING".equals(session.get("status"))) {
      throw error("CAPTURE_NOT_RECORDING", "shadow sessions must be opened while capture is RECORDING");
    }
    RoutineModel model = models.findByModelId(modelId).orElseThrow(() -> error("NOT_FOUND", "model not found"));
    RoutineReplayTrace replay = traces.findFirstByModelIdAndTraceModeOrderByCreatedAtDesc(modelId, "POLICY_REPLAY")
        .orElseThrow(() -> error("MODEL_REPLAY_REQUIRED", "model has no policy replay"));
    if (!"PASSED".equals(readNode(replay.getResultJson()).path("status").asText())) {
      throw error("MODEL_REPLAY_REQUIRED", "model policy replay did not pass");
    }

    CurrentSliceIdentity modelIdentity = read(model.getIdentityJson());
    CurrentSliceIdentity sessionIdentity = read(map(session.get("identity")));
    compatibility.requireCompatible(modelIdentity, sessionIdentity);
    RoutineShadowRun run = new RoutineShadowRun();
    run.setShadowRunId("shadow_" + UUID.randomUUID().toString().replace("-", ""));
    run.setModelId(modelId);
    run.setSessionId(sessionId);
    run.setModelIdentityJson(write(modelIdentity));
    run.setSessionIdentityJson(write(sessionIdentity));
    run.setState("ACTIVE");
    run.setGraphCursor(0);
    run.setStartedAt(Instant.now());
    runs.save(run);
    return response(run);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> status(String shadowRunId) {
    RoutineShadowRun run = runs.findByShadowRunId(shadowRunId)
        .orElseThrow(() -> error("NOT_FOUND", "shadow run not found"));
    Map<String, Object> value = response(run);
    value.put("decisions", decisions.findByShadowRunIdOrderByDecisionOrdinal(shadowRunId)
        .stream().map(this::decision).toList());
    return value;
  }

  /**
   * Seals only after capture completion and after all accepted values have been consumed by the
   * after-commit coordinator. The caller may retry this operation while the coordinator catches up.
   */
  @Transactional
  public Map<String, Object> complete(String shadowRunId) {
    RoutineShadowRun run = runs.findWithLockByShadowRunId(shadowRunId)
        .orElseThrow(() -> error("NOT_FOUND", "shadow run not found"));
    if ("COMPLETED".equals(run.getState())) return response(run);
    if (!"ACTIVE".equals(run.getState())) throw error("SHADOW_NOT_ACTIVE", "shadow run is not active");
    Map<String, Object> capture = captures.status(run.getSessionId());
    if (!"COMPLETED".equals(capture.get("status"))) {
      throw error("CAPTURE_NOT_COMPLETED", "capture must be completed before shadow completion");
    }
    int expected = number(capture.get("tick_count")) + number(capture.get("action_count"));
    List<RoutineShadowDecision> values = decisions.findByShadowRunIdOrderByDecisionOrdinal(shadowRunId);
    if (values.isEmpty()) throw error("SHADOW_DECISION_MISSING", "shadow run has no persisted decisions");
    if (values.size() < expected) throw error("SHADOW_PROCESSING", "accepted capture batches are still being evaluated");

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "routine-shadow-result/v1");
    result.put("shadow_run_id", shadowRunId);
    result.put("model_id", run.getModelId());
    result.put("session_id", run.getSessionId());
    result.put("decision_count", values.size());
    result.put("matched_count", values.stream().filter(v -> "MATCHED".equals(v.getOutcome())).count());
    result.put("diverged_count", values.stream().filter(v -> "DIVERGED".equals(v.getOutcome())).count());
    result.put("abstained_count", values.stream().filter(v -> "ABSTAINED".equals(v.getStatus())).count());
    result.put("model_identity", read(run.getModelIdentityJson()));
    result.put("session_identity", read(run.getSessionIdentityJson()));
    result.put("control_allowed", false);
    String json = write(result);
    run.setState("COMPLETED");
    run.setCompletedAt(Instant.now());
    run.setResultJson(json);
    run.setResultHash(CanonicalJsonHasher.hash(mapper.valueToTree(result), null));
    runs.save(run);
    return response(run);
  }

  /** Runs inside a transaction started by the coordinator after capture commit. */
  @Transactional
  public void process(RoutineCaptureBatchAccepted event) {
    for (RoutineShadowRun candidate : active(event.sessionId())) {
      RoutineShadowRun run = runs.findWithLockByShadowRunId(candidate.getShadowRunId()).orElse(null);
      if (run == null || !"ACTIVE".equals(run.getState())) continue;
      List<JsonNode> expectedActions = expectedActions(run);
      List<RoutineShadowDecision> existing = decisions.findByShadowRunIdOrderByDecisionOrdinal(run.getShadowRunId());
      for (int index = 0; index < event.values().size(); index++) {
        if (decisions.findByShadowRunIdAndSourceEventKindAndSourceBatchSequenceAndSourceEventIndex(
            run.getShadowRunId(), event.kind(), event.sequence(), index).isPresent()) continue;
        JsonNode value = event.values().get(index);
        long tick = value.path("tick").asLong(-1);
        boolean validTick = tick >= 0;
        if (!validTick) tick = 0;

        RoutineShadowDecision decision = new RoutineShadowDecision();
        decision.setShadowRunId(run.getShadowRunId());
        decision.setDecisionOrdinal(run.getNextDecisionOrdinal());
        decision.setSourceEventKind(event.kind());
        decision.setSourceBatchSequence(event.sequence());
        decision.setSourceEventIndex(index);
        decision.setTick(tick);
        RoutineShadowDecision pending = latestPending(existing);
        JsonNode expected = run.getGraphCursor() < expectedActions.size() ? expectedActions.get(run.getGraphCursor()) : null;
        String abstention = !validTick ? "INVALID_TICK_STATE" : expected == null ? "MODEL_CURSOR_TERMINAL" : null;
        if ("TICKS".equals(event.kind())) {
          decision.setStatus(abstention == null ? "PREDICTED" : "ABSTAINED");
          decision.setOutcome(abstention == null ? "PENDING" : "NOT_APPLICABLE");
          decision.setRecommendationJson(abstention == null
              ? write(Map.of("mode", "OBSERVE_ONLY", "action", expected, "control_allowed", false))
              : write(Map.of("mode", "OBSERVE_ONLY", "abstention_reason", abstention, "control_allowed", false)));
        } else {
          boolean matched = pending != null && expectedAction(pending) != null
              && CanonicalJsonHasher.canonical(expectedAction(pending)).equals(CanonicalJsonHasher.canonical(value));
          decision.setStatus(pending == null ? "ABSTAINED" : "PREDICTED");
          decision.setOutcome(pending == null ? "NOT_APPLICABLE" : matched ? "MATCHED" : "DIVERGED");
          decision.setRecommendationJson(pending == null
              ? write(Map.of("mode", "OBSERVE_ONLY", "abstention_reason", "NO_APPLICABLE_ACTION", "control_allowed", false))
              : pending.getRecommendationJson());
          decision.setObservedActionJson(write(value));
          if (pending != null) {
            pending.setOutcome(matched ? "MATCHED" : "DIVERGED");
            pending.setObservedActionJson(write(value));
            pending.setDecisionContentHash(identityHash(write(decisionContent(pending, value))));
            decisions.save(pending);
            if (matched) run.setGraphCursor(Math.min(run.getGraphCursor() + 1, expectedActions.size()));
          }
        }
        decision.setModelIdentityHash(identityHash(run.getModelIdentityJson()));
        decision.setSessionIdentityHash(identityHash(run.getSessionIdentityJson()));
        decision.setControlAllowed(false);
        run.setNextDecisionOrdinal(run.getNextDecisionOrdinal() + 1);
        existing.add(decision);
        decision.setDecisionContentHash(identityHash(write(decisionContent(decision, value))));
        decisions.save(decision);
        run.setLastProcessedTick(tick);
      }
      runs.save(run);
    }
  }

  /** Records a durable processor failure without touching the accepted capture row. */
  @Transactional
  public void failForSession(String sessionId, String code, String message) {
    for (RoutineShadowRun run : active(sessionId)) {
      run.setState("FAILED");
      run.setFailureCode(code == null || code.isBlank() ? "SHADOW_PROCESSING_FAILED" : code);
      run.setFailureMessage(message == null ? "shadow processing failed" : message.substring(0, Math.min(500, message.length())));
      runs.save(run);
    }
  }

  public List<RoutineShadowRun> active(String sessionId) {
    return runs.findBySessionIdAndState(sessionId, "ACTIVE");
  }

  private Map<String, Object> response(RoutineShadowRun run) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", "routine-shadow-run/v1");
    value.put("shadow_run_id", run.getShadowRunId());
    value.put("model_id", run.getModelId());
    value.put("session_id", run.getSessionId());
    value.put("state", run.getState());
    value.put("graph_cursor", run.getGraphCursor());
    value.put("decision_count", decisions.findByShadowRunIdOrderByDecisionOrdinal(run.getShadowRunId()).size());
    value.put("model_identity", read(run.getModelIdentityJson()));
    value.put("session_identity", read(run.getSessionIdentityJson()));
    value.put("result_hash", run.getResultHash());
    value.put("result", run.getResultJson() == null ? null : readNode(run.getResultJson()));
    value.put("failure_code", run.getFailureCode());
    value.put("failure_message", run.getFailureMessage());
    value.put("control_allowed", false);
    return value;
  }

  private Map<String, Object> decision(RoutineShadowDecision value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("decision_ordinal", value.getDecisionOrdinal());
    result.put("source_event_kind", value.getSourceEventKind());
    result.put("source_batch_sequence", value.getSourceBatchSequence());
    result.put("source_event_index", value.getSourceEventIndex());
    result.put("tick", value.getTick());
    result.put("status", value.getStatus());
    result.put("recommendation", readNode(value.getRecommendationJson()));
    result.put("observed_action", readNode(value.getObservedActionJson()));
    result.put("outcome", value.getOutcome());
    result.put("model_identity_hash", value.getModelIdentityHash());
    result.put("session_identity_hash", value.getSessionIdentityHash());
    result.put("decision_content_hash", value.getDecisionContentHash());
    result.put("control_allowed", false);
    return result;
  }

  private Map<String, Object> decisionContent(RoutineShadowDecision value, JsonNode observed) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ordinal", value.getDecisionOrdinal());
    result.put("source_event_kind", value.getSourceEventKind());
    result.put("source_batch_sequence", value.getSourceBatchSequence());
    result.put("source_event_index", value.getSourceEventIndex());
    result.put("tick", value.getTick());
    result.put("status", value.getStatus());
    result.put("outcome", value.getOutcome());
    result.put("observed", observed);
    result.put("control_allowed", false);
    return result;
  }

  private RoutineShadowDecision latestPending(List<RoutineShadowDecision> values) {
    for (int index = values.size() - 1; index >= 0; index--) {
      RoutineShadowDecision value = values.get(index);
      if ("PREDICTED".equals(value.getStatus()) && "PENDING".equals(value.getOutcome())) return value;
    }
    return null;
  }

  private JsonNode expectedAction(RoutineShadowDecision decision) {
    JsonNode recommendation = readNode(decision.getRecommendationJson());
    JsonNode action = recommendation.get("action");
    return action == null || action.isNull() ? null : action;
  }

  private List<JsonNode> expectedActions(RoutineShadowRun run) {
    RoutineModel model = models.findByModelId(run.getModelId()).orElseThrow(() -> error("MODEL_NOT_FOUND", "shadow model not found"));
    Map<String, Object> definition = readMap(model.getDefinitionJson());
    JsonNode graph = mapper.valueToTree(definition.get("graph"));
    JsonNode nodes = graph != null && graph.isObject() && graph.has("nodes") ? graph.get("nodes") : mapper.valueToTree(definition.get("nodes"));
    List<JsonNode> actions = new ArrayList<>();
    if (nodes != null && nodes.isArray()) {
      for (JsonNode node : nodes) if ("ACTION".equals(node.path("type").asText())) actions.add(node.path("semantics").path("recorded_action"));
    }
    return actions;
  }

  private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
  private String identityHash(String json) { return CanonicalJsonHasher.hash(readNode(json), null); }
  private JsonNode readNode(String value) { try { return value == null ? mapper.nullNode() : mapper.readTree(value); } catch (Exception e) { throw error("SHADOW_JSON_INVALID", "shadow JSON is invalid"); } }
  private CurrentSliceIdentity read(String value) { try { CurrentSliceIdentity result = mapper.readValue(value, CurrentSliceIdentity.class); result.validate(); return result; } catch (Exception e) { throw error("IDENTITY_MISMATCH", "shadow identity is invalid"); } }
  private String map(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error("IDENTITY_MISMATCH", "session identity is invalid"); } }
  @SuppressWarnings("unchecked") private Map<String, Object> readMap(String value) { try { return mapper.readValue(value, Map.class); } catch (Exception e) { throw error("SHADOW_JSON_INVALID", "shadow model JSON is invalid"); } }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error("SHADOW_RESULT_INVALID", "shadow result is not serializable"); } }
  private static RoutineCaptureIngestionService.RoutineCaptureException error(String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
}
