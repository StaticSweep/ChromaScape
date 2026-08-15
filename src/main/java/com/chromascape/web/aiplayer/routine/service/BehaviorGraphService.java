package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BehaviorGraphService {
  private final ObjectMapper mapper;
  private final RoutineCaptureIngestionService captures;
  private final RoutineCurationService curation;
  private final BehaviorGraphDefinitionRepository repository;
  private final CurrentSliceRunRepository runs;

  public BehaviorGraphService(
      ObjectMapper mapper,
      RoutineCaptureIngestionService captures,
      RoutineCurationService curation,
      BehaviorGraphDefinitionRepository repository,
      CurrentSliceRunRepository runs) {
    this.mapper = mapper;
    this.captures = captures;
    this.curation = curation;
    this.repository = repository;
    this.runs = runs;
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
    updateRunIdentity(graphIdentity, "GRAPH_DRAFT");
    return response(entity);
  }

  private void updateRunIdentity(CurrentSliceIdentity identity, String stage) {
    if (identity.run() == null || identity.run().runId() == null) return;
    runs.findByRunId(identity.run().runId()).ifPresent(run -> { run.setIdentityJson(write(identity)); run.setStage(stage); runs.save(run); });
  }

  private Map<String, Object> node(String type, int ordinal, Map<String, Object> semantic) {
    String hash = CanonicalJsonHasher.hash(mapper.valueToTree(semantic), null);
    Map<String, Object> node = new LinkedHashMap<>(semantic);
    node.put("id", "node_" + ordinal + "_" + hash.substring(7, 19));
    node.put("ordinal", ordinal);
    node.put("type", type);
    return node;
  }

  @Transactional
  public Map<String, Object> createAssistantDraft(String baseGraphId, Map<String, Object> diff) {
    BehaviorGraphDefinition base = repository.findByGraphId(baseGraphId).orElseThrow(() -> error("NOT_FOUND", "base graph not found"));
    if (!"PUBLISHED".equals(base.getState())) throw error("GRAPH_INVALID", "assistant drafts require a published base graph");
    if (!base.getGraphHash().equals(String.valueOf(diff.get("base_graph_hash")))) throw error("IDENTITY_MISMATCH", "assistant diff base graph hash differs");
    Map<String, Object> definition = response(base);
    applyOperations(definition, diff);
    definition.put("assistant_diff", diff);
    definition.put("state", "DRAFT");
    definition.remove("graph_id");
    definition.remove("graph_hash");
    ObjectNode content = mapper.valueToTree(definition);
    String hash = CanonicalJsonHasher.hash(content, null);
    String graphId = "graph_" + hash.substring(7, 31);
    CurrentSliceIdentity baseIdentity;
    try { baseIdentity = mapper.readValue(base.getIdentityJson(), CurrentSliceIdentity.class); }
    catch (Exception e) { throw error("GRAPH_INVALID", "base graph identity is invalid"); }
    CurrentSliceIdentity identity = new CurrentSliceIdentity(baseIdentity.schema(), baseIdentity.task(), baseIdentity.run(), baseIdentity.client(), baseIdentity.capture(), baseIdentity.source(), new CurrentSliceIdentity.Graph(graphId, hash), baseIdentity.model(), baseIdentity.candidate(), false);
    definition.put("schema", "behavior-graph-definition/v1"); definition.put("graph_id", graphId); definition.put("graph_hash", hash); definition.put("identity", identity); definition.put("control_allowed", false);
    BehaviorGraphDefinition draft = new BehaviorGraphDefinition(); draft.setGraphId(graphId); draft.setGraphHash(hash); draft.setIdentityJson(write(identity)); draft.setDefinitionJson(write(definition));    draft.setState("DRAFT"); repository.save(draft);
    updateRunIdentity(identity, "GRAPH_DRAFT");
    return response(draft);
  }

  @SuppressWarnings("unchecked")
  private void applyOperations(Map<String, Object> definition, Map<String, Object> diff) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    Object existingNodes = definition.get("nodes");
    if (existingNodes instanceof List<?> list) for (Object node : list) if (node instanceof Map<?, ?> value) nodes.add(new LinkedHashMap<>((Map<String, Object>) value));
    List<Map<String, Object>> edges = new ArrayList<>();
    Object existingEdges = definition.get("edges");
    if (existingEdges instanceof List<?> list) for (Object edge : list) if (edge instanceof Map<?, ?> value) edges.add(new LinkedHashMap<>((Map<String, Object>) value));
    Object operations = diff.get("operations");
    if (operations instanceof List<?> list) for (Object value : list) {
      if (!(value instanceof Map<?, ?> raw)) throw error("GRAPH_INVALID", "assistant operation is invalid");
      Map<String, Object> operation = (Map<String, Object>) raw;
      String op = String.valueOf(operation.get("op"));
      if ("add_node".equals(op)) { Object node = operation.get("node"); if (!(node instanceof Map<?, ?> rawNode)) throw error("GRAPH_INVALID", "add_node requires node"); nodes.add(new LinkedHashMap<>((Map<String, Object>) rawNode)); }
      else if ("remove_node".equals(op)) { String id = String.valueOf(operation.get("node_id")); boolean removed = nodes.removeIf(node -> id.equals(String.valueOf(node.get("id")))); if (!removed) throw error("GRAPH_INVALID", "remove_node target is missing"); edges.removeIf(edge -> id.equals(String.valueOf(edge.get("from"))) || id.equals(String.valueOf(edge.get("to")))); }
      else if ("replace_semantics".equals(op)) { String id = String.valueOf(operation.get("node_id")); Map<String, Object> semantics = operation.get("node") instanceof Map<?, ?> rawNode ? (Map<String, Object>) rawNode : Map.of(); Map<String, Object> target = nodes.stream().filter(node -> id.equals(String.valueOf(node.get("id")))).findFirst().orElseThrow(() -> error("GRAPH_INVALID", "replace_semantics target is missing")); target.putAll(semantics); Map<String, Object> semanticOnly = new LinkedHashMap<>(target); semanticOnly.remove("id"); semanticOnly.remove("ordinal"); semanticOnly.remove("type"); target.put("id", "node_" + target.get("ordinal") + "_" + CanonicalJsonHasher.hash(mapper.valueToTree(semanticOnly), null).substring(7, 19)); }
      else if ("add_edge".equals(op)) { Object edge = operation.get("edge"); if (!(edge instanceof Map<?, ?> rawEdge)) throw error("GRAPH_INVALID", "add_edge requires edge"); edges.add(new LinkedHashMap<>((Map<String, Object>) rawEdge)); }
      else if ("remove_edge".equals(op)) { String id = String.valueOf(operation.get("edge_id")); boolean removed = edges.removeIf(edge -> id.equals(String.valueOf(edge.get("id")))); if (!removed) throw error("GRAPH_INVALID", "remove_edge target is missing"); }
      else throw error("GRAPH_INVALID", "unsupported assistant operation");
    }
    if (nodes.size() > 256 || edges.size() > 255) throw error("GRAPH_LIMIT_EXCEEDED", "assistant draft exceeds graph limits");
    Set<String> nodeIds = new java.util.HashSet<>();
    for (Map<String, Object> node : nodes) {
      String nodeId = String.valueOf(node.get("id"));
      String type = String.valueOf(node.get("type"));
      if (!nodeIds.add(nodeId) || !Set.of("START", "ACTION", "GATE", "ABSTAIN", "RECOVERY", "TERMINAL").contains(type)) throw error("GRAPH_INVALID", "assistant draft contains invalid or duplicate node");
    }
    Set<String> edgeIds = new java.util.HashSet<>();
    Map<String, List<String>> adjacency = new java.util.HashMap<>();
    for (Map<String, Object> edge : edges) {
      String edgeId = String.valueOf(edge.get("id")); String from = String.valueOf(edge.get("from")); String to = String.valueOf(edge.get("to"));
      if (!edgeIds.add(edgeId) || !nodeIds.contains(from) || !nodeIds.contains(to)) throw error("GRAPH_INVALID", "assistant draft contains dangling or duplicate edge");
      adjacency.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
    }
    if (hasCycle(adjacency, nodeIds)) throw error("GRAPH_INVALID", "assistant draft contains a cycle");
    definition.put("nodes", nodes); definition.put("edges", edges);
  }

  private boolean hasCycle(Map<String, List<String>> adjacency, Set<String> nodes) {
    Set<String> visiting = new java.util.HashSet<>(); Set<String> visited = new java.util.HashSet<>();
    for (String node : nodes) if (cycleFrom(node, adjacency, visiting, visited)) return true;
    return false;
  }
  private boolean cycleFrom(String node, Map<String, List<String>> adjacency, Set<String> visiting, Set<String> visited) {
    if (visiting.contains(node)) return true; if (visited.contains(node)) return false;
    visiting.add(node); for (String next : adjacency.getOrDefault(node, List.of())) if (cycleFrom(next, adjacency, visiting, visited)) return true; visiting.remove(node); visited.add(node); return false;
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
