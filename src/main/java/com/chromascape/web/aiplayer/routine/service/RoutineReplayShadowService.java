package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.entity.RoutineModel;
import com.chromascape.web.aiplayer.routine.entity.RoutineReplayTrace;
import com.chromascape.web.aiplayer.routine.repository.RoutineModelRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineReplayTraceRepository;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineReplayShadowService {
  private final ObjectMapper mapper;
  private final RoutineCaptureIngestionService captures;
  private final BehaviorGraphService graphs;
  private final RoutineModelRepository models;
  private final RoutineReplayTraceRepository traces;
  private final CurrentSliceRunService runService;

  public RoutineReplayShadowService(
      ObjectMapper mapper,
      RoutineCaptureIngestionService captures,
      BehaviorGraphService graphs,
      RoutineModelRepository models,
      RoutineReplayTraceRepository traces,
      CurrentSliceRunService runService) {
    this.mapper = mapper;
    this.captures = captures;
    this.graphs = graphs;
    this.models = models;
    this.traces = traces;
    this.runService = runService;
  }

  @Transactional
  public Map<String, Object> buildModel(String graphId, CurrentSliceIdentity requestedIdentity) {
    Map<String, Object> graph = graphs.get(graphId);
    if (!"PUBLISHED".equals(graph.get("state"))) {
      throw error("GRAPH_NOT_PUBLISHED", "model creation requires a published graph");
    }
    CurrentSliceIdentity graphIdentity = mapper.convertValue(graph.get("identity"), CurrentSliceIdentity.class);
    requestedIdentity.requireSameIdentity(graphIdentity);

    ObjectNode modelContent = mapper.createObjectNode();
    modelContent.put("schema", "fishing-model/v1");
    modelContent.put("graph_id", graphId);
    modelContent.set("graph", mapper.valueToTree(graph));
    modelContent.set("identity", mapper.valueToTree(graphIdentity));
    String modelHash = CanonicalJsonHasher.hash(modelContent, null);
    String modelId = "model_" + modelHash.substring(7, 31);
    CurrentSliceIdentity modelIdentity = new CurrentSliceIdentity(
        graphIdentity.schema(), graphIdentity.task(), graphIdentity.run(), graphIdentity.client(), graphIdentity.capture(),
        graphIdentity.source(), graphIdentity.graph(),
        new CurrentSliceIdentity.Model(modelId, modelHash), graphIdentity.candidate(), false);

    RoutineModel entity = models.findByModelId(modelId).orElseGet(RoutineModel::new);
    entity.setModelId(modelId);
    entity.setGraphId(graphId);
    entity.setModelHash(modelHash);
    entity.setIdentityJson(write(modelIdentity));
    entity.setDefinitionJson(write(Map.of("schema", "fishing-model/v1", "graph", graph, "identity", modelIdentity)));
    models.save(entity);
    if (modelIdentity.run() != null && modelIdentity.run().runId() != null) {
      runService.advanceIdentity(modelIdentity.run().runId(), modelIdentity, "MODEL_BOUND");
    }
    return response(entity, modelIdentity);
  }

  @Transactional
  public Map<String, Object> replay(String modelId, String recordingId) {
    RoutineModel model = model(modelId);
    CurrentSliceIdentity modelIdentity = readIdentity(model.getIdentityJson());
    Map<String, Object> recording = captures.recording(recordingId);
    CurrentSliceIdentity recordingIdentity = mapper.convertValue(recording.get("identity"), CurrentSliceIdentity.class);
    recordingIdentity.requireSameIdentity(modelIdentity);
    if (recordingIdentity.capture() == null || !recordingId.equals(recordingIdentity.capture().recordingId())) {
      throw error("IDENTITY_MISMATCH", "replay recording does not match model capture");
    }

    Map<String, Object> definition = readMap(model.getDefinitionJson());
    List<JsonNode> expected = actionNodes(definition);
    List<JsonNode> actual = captures.actions(recordingId);
    List<Map<String, Object>> decisions = new ArrayList<>();
    int matched = 0;
    int count = Math.max(expected.size(), actual.size());
    for (int i = 0; i < count; i++) {
      JsonNode expectedAction = i < expected.size() ? expected.get(i) : null;
      JsonNode actualAction = i < actual.size() ? actual.get(i) : null;
      boolean same = expectedAction != null && actualAction != null
          && CanonicalJsonHasher.canonical(expectedAction).equals(CanonicalJsonHasher.canonical(actualAction));
      if (same) matched++;
      Map<String, Object> decision = new LinkedHashMap<>();
      decision.put("ordinal", i);
      decision.put("tick", actualAction == null ? null : actualAction.path("tick").asLong());
      decision.put("expected", expectedAction);
      decision.put("observed", actualAction);
      decision.put("status", same ? "MATCHED" : "ABSTAINED");
      decision.put("control_allowed", false);
      decisions.add(decision);
    }
    String status = expected.size() == actual.size() && matched == expected.size() ? "PASSED" : "FAILED";
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "routine-replay-result/v1");
    result.put("status", status);
    result.put("model_id", modelId);
    result.put("recording_id", recordingId);
    result.put("identity", modelIdentity);
    result.put("decision_count", decisions.size());
    result.put("matched_decision_count", matched);
    result.put("decisions", decisions);
    result.put("deterministic", true);
    result.put("control_allowed", false);
    return persistTrace(model, recordingId, "POLICY_REPLAY", modelIdentity, result);
  }

  @Transactional
  public Map<String, Object> shadow(String modelId, String sessionId) {
    RoutineModel model = model(modelId);
    CurrentSliceIdentity modelIdentity = readIdentity(model.getIdentityJson());
    Map<String, Object> session = captures.status(sessionId);
    CurrentSliceIdentity sessionIdentity = mapper.convertValue(session.get("identity"), CurrentSliceIdentity.class);
    sessionIdentity.requireSameIdentity(modelIdentity);
    List<JsonNode> observed = captures.actionsForSession(sessionId);
    List<Map<String, Object>> recommendations = new ArrayList<>();
    if (!observed.isEmpty()) {
      JsonNode latest = observed.get(observed.size() - 1);
      recommendations.add(Map.of("tick", latest.path("tick").asLong(), "action", latest, "mode", "OBSERVE_ONLY", "control_allowed", false));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "routine-shadow-trace/v1");
    result.put("status", "SHADOW");
    result.put("model_id", modelId);
    result.put("session_id", sessionId);
    result.put("identity", modelIdentity);
    result.put("recommendations", recommendations);
    result.put("abstentions", List.of(Map.of("reason", recommendations.isEmpty() ? "NO_ACTION_OBSERVED" : "READ_ONLY", "control_allowed", false)));
    result.put("control_allowed", false);
    return persistTrace(model, sessionId, "SHADOW", modelIdentity, result);
  }

  private List<JsonNode> actionNodes(Map<String, Object> definition) {
    List<JsonNode> actions = new ArrayList<>();
    JsonNode nodes = mapper.valueToTree(definition.get("graph") instanceof Map ? ((Map<?, ?>) definition.get("graph")).get("nodes") : definition.get("nodes"));
    if (nodes != null && nodes.isArray()) {
      for (JsonNode node : nodes) {
        if ("ACTION".equals(node.path("type").asText())) {
          actions.add(node.path("semantics").path("recorded_action"));
        }
      }
    }
    return actions;
  }

  private Map<String, Object> persistTrace(RoutineModel model, String recordingId, String mode,
      CurrentSliceIdentity identity, Map<String, Object> result) {
    String contentHash = CanonicalJsonHasher.hash(mapper.valueToTree(result), null);
    String traceId = "trace_" + contentHash.substring(7, 31);
    RoutineReplayTrace entity = traces.findByTraceId(traceId).orElseGet(RoutineReplayTrace::new);
    entity.setTraceId(traceId);
    entity.setModelId(model.getModelId());
    entity.setRecordingId(recordingId);
    entity.setTraceMode(mode);
    entity.setContentHash(contentHash);
    entity.setIdentityJson(write(identity));
    entity.setResultJson(write(result));
    traces.save(entity);
    result.put("trace_id", traceId);
    result.put("content_hash", contentHash);
    return result;
  }

  private RoutineModel model(String modelId) {
    return models.findByModelId(modelId).orElseThrow(() -> error("NOT_FOUND", "model not found"));
  }
  private CurrentSliceIdentity readIdentity(String json) { try { return mapper.readValue(json, CurrentSliceIdentity.class); } catch (Exception e) { throw error("IDENTITY_MISMATCH", "persisted model identity is invalid"); } }
  @SuppressWarnings("unchecked") private Map<String, Object> readMap(String json) { try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw error("MODEL_INVALID", "persisted model is invalid"); } }
  private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw error("MODEL_INVALID", "model artifact is not serializable"); } }
  private Map<String, Object> response(RoutineModel entity, CurrentSliceIdentity identity) { return Map.of("schema", "fishing-model/v1", "model_id", entity.getModelId(), "graph_id", entity.getGraphId(), "model_hash", entity.getModelHash(), "identity", identity, "control_allowed", false); }
  private static RoutineCaptureIngestionService.RoutineCaptureException error(String code, String message) { return new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
}
