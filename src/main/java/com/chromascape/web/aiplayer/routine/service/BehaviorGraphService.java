package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
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
public class BehaviorGraphService {
  private final ObjectMapper mapper;
  private final RoutineCaptureIngestionService captures;
  private final RoutineCurationService curation;
  private final BehaviorGraphDefinitionRepository repository;

  public BehaviorGraphService(
      ObjectMapper mapper,
      RoutineCaptureIngestionService captures,
      RoutineCurationService curation,
      BehaviorGraphDefinitionRepository repository) {
    this.mapper = mapper;
    this.captures = captures;
    this.curation = curation;
    this.repository = repository;
  }

  @Transactional
  public Map<String, Object> generate(String recordingId) {
    Map<String, Object> recording = captures.recording(recordingId);
    CurrentSliceIdentity identity = (CurrentSliceIdentity) recording.get("identity");
    List<JsonNode> actions = curatedActions(recordingId, captures.actions(recordingId));
    if (actions.isEmpty()) throw error("GRAPH_INVALID", "recording has no curated actions");
    if (actions.size() > 254) throw error("GRAPH_LIMIT_EXCEEDED", "flat graph exceeds 256 node limit");

    List<Map<String, Object>> nodes = new ArrayList<>();
    List<Map<String, Object>> edges = new ArrayList<>();
    nodes.add(node("START", 0, Map.of("semantic", "start")));
    for (int i = 0; i < actions.size(); i++) {
      JsonNode action = actions.get(i);
      long tick = action.path("tick").asLong(i);
      Map<String, Object> semantic = new LinkedHashMap<>();
      semantic.put("semantic_action", action);
      semantic.put("source_tick_range", Map.of("start", tick, "end", tick));
      semantic.put("evidence_ids", List.of(recordingId + ":tick:" + tick));
      nodes.add(node("ACTION", i + 1, semantic));
    }
    nodes.add(node("TERMINAL", actions.size() + 1, Map.of("semantic", "terminal")));
    for (int i = 0; i < nodes.size() - 1; i++) {
      Map<String, Object> edge = new LinkedHashMap<>();
      edge.put("from", nodes.get(i).get("id"));
      edge.put("to", nodes.get(i + 1).get("id"));
      ObjectNode edgeNode = mapper.valueToTree(edge);
      edge.put("id", "edge_" + i + "_" + (i + 1) + "_" + CanonicalJsonHasher.hash(edgeNode, null).substring(7, 19));
      edges.add(edge);
    }

    ObjectNode hashPayload = mapper.createObjectNode();
    hashPayload.set("identity", mapper.valueToTree(identity));
    hashPayload.set("nodes", mapper.valueToTree(nodes));
    hashPayload.set("edges", mapper.valueToTree(edges));
    String graphHash = CanonicalJsonHasher.hash(hashPayload, null);
    String graphId = "graph_" + graphHash.substring(7, 31);
    if (repository.findByGraphId(graphId).isPresent()) return get(graphId);
    CurrentSliceIdentity graphIdentity = new CurrentSliceIdentity(
        identity.schema(), identity.task(), identity.run(), identity.client(), identity.capture(),
        identity.source(), new CurrentSliceIdentity.Graph(graphId, graphHash),
        identity.model(), identity.candidate(), false);

    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("schema", "behavior-graph-definition/v1");
    definition.put("graph_id", graphId);
    definition.put("graph_hash", graphHash);
    definition.put("recording_id", recordingId);
    definition.put("identity", graphIdentity);
    definition.put("nodes", nodes);
    definition.put("edges", edges);
    definition.put("trace_modes", List.of("RECORDED", "POLICY_REPLAY", "SHADOW"));
    definition.put("control_allowed", false);

    BehaviorGraphDefinition entity = new BehaviorGraphDefinition();
    entity.setGraphId(graphId);
    entity.setGraphHash(graphHash);
    entity.setIdentityJson(write(graphIdentity));
    entity.setDefinitionJson(write(definition));
    entity.setState("DRAFT");
    repository.save(entity);
    return response(entity);
  }

  private Map<String, Object> node(String type, int ordinal, Map<String, Object> semantic) {
    String hash = CanonicalJsonHasher.hash(mapper.valueToTree(semantic), null);
    Map<String, Object> node = new LinkedHashMap<>(semantic);
    node.put("id", "node_" + ordinal + "_" + hash.substring(7, 19));
    node.put("ordinal", ordinal);
    node.put("type", type);
    return node;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String id) {
    BehaviorGraphDefinition entity = repository.findByGraphId(id).orElseThrow(() -> error("NOT_FOUND", "graph not found"));
    return response(entity);
  }

  @Transactional
  public Map<String, Object> publish(String id) {
    BehaviorGraphDefinition entity = repository.findByGraphId(id).orElseThrow(() -> error("NOT_FOUND", "graph not found"));
    if ("PUBLISHED".equals(entity.getState())) throw error("GRAPH_PUBLISHED_IMMUTABLE", "published graph is immutable");
    entity.setState("PUBLISHED");
    repository.save(entity);
    return response(entity);
  }

  private List<JsonNode> curatedActions(String recordingId, List<JsonNode> actions) {
    RoutineApiDtos.CurationRequest request = curation.get(recordingId);
    if (request == null) return actions;
    List<JsonNode> result = new ArrayList<>();
    for (JsonNode action : actions) {
      long tick = action.path("tick").asLong(-1);
      if (inRanges(tick, request.excludeRanges())) continue;
      if (request.includeRanges() == null || request.includeRanges().isEmpty() || inRanges(tick, request.includeRanges())) result.add(action);
    }
    return result;
  }

  private boolean inRanges(long tick, List<Map<String, Object>> ranges) {
    if (ranges == null) return false;
    for (Map<String, Object> range : ranges) {
      long start = number(range.get("start"), number(range.get("start_tick"), Long.MIN_VALUE));
      long end = number(range.get("end"), number(range.get("end_tick"), Long.MAX_VALUE));
      if (tick >= start && tick <= end) return true;
    }
    return false;
  }

  private long number(Object value, long fallback) {
    return value instanceof Number n ? n.longValue() : fallback;
  }

  private Map<String, Object> response(BehaviorGraphDefinition entity) {
    try {
      Map<String, Object> result = mapper.readValue(entity.getDefinitionJson(), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
      result.put("state", entity.getState());
      return result;
    } catch (Exception e) {
      throw error("GRAPH_INVALID", "persisted graph definition is invalid");
    }
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception e) { throw error("GRAPH_INVALID", "graph definition is not serializable"); }
  }

  private static RoutineCaptureIngestionService.RoutineCaptureException error(String code, String message) {
    return new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }
}
