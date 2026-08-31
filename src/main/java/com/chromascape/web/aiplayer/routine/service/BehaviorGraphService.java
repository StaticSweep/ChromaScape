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
  private final CurrentSliceRunService runService;
  private final GraphSchemaValidator schemaValidator;

  public BehaviorGraphService(
      ObjectMapper mapper,
      RoutineCaptureIngestionService captures,
      RoutineCurationService curation,
      BehaviorGraphDefinitionRepository repository,
      CurrentSliceRunService runService,
      GraphSchemaValidator schemaValidator) {
    this.mapper = mapper;
    this.captures = captures;
    this.curation = curation;
    this.repository = repository;
    this.runService = runService;
    this.schemaValidator = schemaValidator;
  }

  @Transactional
  public Map<String, Object> generate(String recordingId) {
    Map<String, Object> recording = captures.recording(recordingId);
    // Graph generation is permitted only for completed captures with finalized recording/video hashes.
    if (!"COMPLETED".equals(recording.get("status"))) {
      throw error("GRAPH_INVALID", "graph generation requires a completed capture");
    }
    if (recording.get("recording_hash") == null || String.valueOf(recording.get("recording_hash")).isBlank()) {
      throw error("GRAPH_INVALID", "capture has no finalized recording hash");
    }
    CurrentSliceIdentity identity = (CurrentSliceIdentity) recording.get("identity");
    List<JsonNode> actions = curatedActions(recordingId, captures.actions(recordingId));
    if (actions.isEmpty()) throw error("GRAPH_INVALID", "recording has no curated actions");
    if (actions.size() > 254) throw error("GRAPH_LIMIT_EXCEEDED", "flat graph exceeds 256 node limit");

    List<Map<String, Object>> nodes = new ArrayList<>();
    List<Map<String, Object>> edges = new ArrayList<>();
    nodes.add(node("START", 0, Map.of("semantics", Map.of("kind", "start"))));
    for (int i = 0; i < actions.size(); i++) {
      JsonNode action = actions.get(i);
      long tick = action.path("tick").asLong(i);
      Map<String, Object> actionSemantics = new LinkedHashMap<>();
      actionSemantics.put("kind", "recorded_action");
      actionSemantics.put("recorded_action", action);
      Map<String, Object> actionNode = new LinkedHashMap<>();
      actionNode.put("semantics", actionSemantics);
      actionNode.put("source_tick_range", Map.of("start", tick, "end", tick));
      actionNode.put("evidence_ids", List.of(recordingId + ":tick:" + tick));
      nodes.add(node("ACTION", i + 1, actionNode));
    }
    nodes.add(node("TERMINAL", actions.size() + 1, Map.of("semantics", Map.of("kind", "terminal"))));
    for (int i = 0; i < nodes.size() - 1; i++) {
      Map<String, Object> edge = new LinkedHashMap<>();
      edge.put("from", nodes.get(i).get("id"));
      edge.put("to", nodes.get(i + 1).get("id"));
      ObjectNode edgeNode = mapper.valueToTree(edge);
      edge.put("id", "edge_" + i + "_" + (i + 1) + "_" + CanonicalJsonHasher.hash(edgeNode, null).substring(7, 19));
      edges.add(edge);
    }

    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("schema", "behavior-graph-definition/v1");
    definition.put("graph_id", "graph_000000000000000000000000");
    definition.put("graph_hash", "sha256:" + "0".repeat(64));
    definition.put("recording_id", recordingId);
    definition.put("identity", identity);
    definition.put("nodes", nodes);
    definition.put("edges", edges);
    definition.put("trace_modes", List.of("RECORDED", "POLICY_REPLAY", "SHADOW"));
    definition.put("authority", disabledAuthority());
    definition.put("control_allowed", false);
    String graphHash = graphHash(mapper.valueToTree(definition));
    String graphId = "graph_" + graphHash.substring(7, 31);
    if (repository.findByGraphId(graphId).isPresent()) return get(graphId);
    CurrentSliceIdentity graphIdentity = new CurrentSliceIdentity(
        identity.schema(), identity.task(), identity.run(), identity.client(), identity.capture(),
        identity.source(), new CurrentSliceIdentity.Graph(graphId, graphHash),
        identity.model(), identity.candidate(), false);

    definition.put("graph_id", graphId);
    definition.put("graph_hash", graphHash);
    definition.put("identity", graphIdentity);
    validateDefinitionOrThrow(definition);
    if (!graphHash.equals(graphHash(mapper.valueToTree(definition)))) {
      throw error("GRAPH_HASH_INVALID", "stored graph hash does not match finalized definition");
    }

    BehaviorGraphDefinition entity = new BehaviorGraphDefinition();
    entity.setGraphId(graphId);
    entity.setGraphHash(graphHash);
    entity.setIdentityJson(write(graphIdentity));
    entity.setDefinitionJson(write(definition));
    entity.setState("DRAFT");
    repository.save(entity);
    return response(entity);
  }

  private com.chromascape.web.aiplayer.contract.CurrentSliceIdentity readIdentity(String json) {
    try { return mapper.readValue(json, com.chromascape.web.aiplayer.contract.CurrentSliceIdentity.class); }
    catch (Exception e) { throw error("GRAPH_INVALID", "persisted graph identity is invalid"); }
  }

  private Map<String, Object> node(String type, int ordinal, Map<String, Object> fields) {
    String hash = CanonicalJsonHasher.hash(mapper.valueToTree(fields), null);
    Map<String, Object> node = new LinkedHashMap<>(fields);
    node.put("id", "node_" + ordinal + "_" + hash.substring(7, 19));
    node.put("ordinal", ordinal);
    node.put("type", type);
    return node;
  }

  /**
   * Graph hashing rule: hash the graph content after removing ONLY {@code graph_id},
   * {@code graph_hash} and {@code identity.graph}; everything else (nodes, edges, remaining
   * identity groups) participates in the hash. The graph id is derived from that hash.
   */
  private String graphHash(JsonNode content) {
    ObjectNode copy = content.isObject() ? (ObjectNode) content.deepCopy() : (ObjectNode) content;
    copy.remove("graph_id");
    copy.remove("graph_hash");
    if (copy.has("identity") && copy.get("identity").isObject()) {
      ((ObjectNode) copy.get("identity")).remove("graph");
    }
    return CanonicalJsonHasher.hash(copy, null);
  }

  private void validateDefinitionOrThrow(Map<String, Object> definition) {
    try {
      schemaValidator.validateDefinition(mapper.valueToTree(definition));
    } catch (IllegalArgumentException e) {
      throw error("GRAPH_SCHEMA_INVALID", e.getMessage());
    }
  }

  @Transactional
  public Map<String, Object> createAssistantDraft(String baseGraphId, Map<String, Object> diff) {
    // Validate the assistant diff against its strict schema before touching the base graph.
    try {
      schemaValidator.validateDiff(mapper.valueToTree(diff));
    } catch (IllegalArgumentException e) {
      throw error("GRAPH_DIFF_INVALID", e.getMessage());
    }
    // Hold the published base row while materializing the successor so a concurrent publication
    // cannot change the lineage being copied underneath this transaction.
    BehaviorGraphDefinition base = repository.findWithLockByGraphId(baseGraphId)
        .orElseThrow(() -> error("NOT_FOUND", "base graph not found"));
    if (!"PUBLISHED".equals(base.getState())) throw error("GRAPH_INVALID", "assistant drafts require a published base graph");
    if (!base.getGraphHash().equals(String.valueOf(diff.get("base_graph_hash")))) throw error("IDENTITY_MISMATCH", "assistant diff base graph hash differs");
    Map<String, Object> definition = readDefinition(base);
    definition.putIfAbsent("authority", disabledAuthority());
    applyOperations(definition, diff);
    // Assistant diff metadata and transient response state live OUTSIDE the schema-validated graph
    // document. Remove any transient fields before hashing and persisting so the persisted JSON
    // satisfies the behavior-graph-definition/v1 contract (additionalProperties=false).
    definition.remove("assistant_diff");
    definition.remove("state");
    definition.put("graph_id", "graph_000000000000000000000000");
    definition.put("graph_hash", "sha256:" + "0".repeat(64));
    ObjectNode content = mapper.valueToTree(definition);
    String hash = graphHash(content);
    String graphId = "graph_" + hash.substring(7, 31);
    CurrentSliceIdentity baseIdentity;
    try { baseIdentity = mapper.readValue(base.getIdentityJson(), CurrentSliceIdentity.class); }
    catch (Exception e) { throw error("GRAPH_INVALID", "base graph identity is invalid"); }
    CurrentSliceIdentity identity = new CurrentSliceIdentity(baseIdentity.schema(), baseIdentity.task(), baseIdentity.run(), baseIdentity.client(), baseIdentity.capture(), baseIdentity.source(), new CurrentSliceIdentity.Graph(graphId, hash), baseIdentity.model(), baseIdentity.candidate(), false);
    String recordingId = String.valueOf(readDefinition(base).get("recording_id"));
    definition.put("schema", "behavior-graph-definition/v1");
    definition.put("graph_id", graphId);
    definition.put("graph_hash", hash);
    definition.put("recording_id", recordingId);
    definition.put("identity", identity);
    definition.put("control_allowed", false);
    validateDefinitionOrThrow(definition);
    if (!hash.equals(graphHash(mapper.valueToTree(definition)))) {
      throw error("GRAPH_HASH_INVALID", "stored assistant graph hash does not match finalized definition");
    }
    // A graph id is its content hash, so a diff that changes nothing produces the base graph's own
    // id and the insert dies on the unique index -- a 500 for what is really a rejected proposal.
    // Say so instead.
    if (repository.findByGraphId(graphId).isPresent()) {
      throw error("GRAPH_UNCHANGED", "assistant draft is identical to its base graph");
    }
    BehaviorGraphDefinition draft = new BehaviorGraphDefinition(); draft.setGraphId(graphId); draft.setGraphHash(hash); draft.setIdentityJson(write(identity)); draft.setDefinitionJson(write(definition)); draft.setAssistantDiffJson(write(diff)); draft.setSupersedesGraphId(baseGraphId); draft.setState("DRAFT"); repository.save(draft);
    // The run's core graph identity is immutable; an assistant revision is recorded as an explicit
    // supersession edge (supersedes_graph_id) instead of overwriting identity.graph. The run graph
    // may advance only when the successor is published through that lineage path.
    return response(draft);
  }

  private Map<String, Object> readDefinition(BehaviorGraphDefinition entity) {
    try {
      Map<String, Object> definition = mapper.readValue(entity.getDefinitionJson(), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
      definition.remove("state");
      return definition;
    } catch (Exception e) {
      throw error("GRAPH_INVALID", "persisted graph definition is invalid");
    }
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
      else if ("replace_semantics".equals(op)) {
        String id = String.valueOf(operation.get("node_id"));
        Map<String, Object> semantics = operation.get("semantics") instanceof Map<?, ?> rawSemantics
            ? new LinkedHashMap<>((Map<String, Object>) rawSemantics) : Map.of();
        Map<String, Object> target = nodes.stream().filter(node -> id.equals(String.valueOf(node.get("id")))).findFirst().orElseThrow(() -> error("GRAPH_INVALID", "replace_semantics target is missing"));
        target.put("semantics", semantics);
        Map<String, Object> semanticOnly = new LinkedHashMap<>(target); semanticOnly.remove("id"); semanticOnly.remove("ordinal"); semanticOnly.remove("type");
        String replacementId = "node_" + target.get("ordinal") + "_" + CanonicalJsonHasher.hash(mapper.valueToTree(semanticOnly), null).substring(7, 19);
        if (!id.equals(replacementId)) {
          // Rewrite every incident edge to the replacement node id, preserving edge semantics.
          for (Map<String, Object> edge : edges) {
            if (id.equals(String.valueOf(edge.get("from")))) edge.put("from", replacementId);
            if (id.equals(String.valueOf(edge.get("to")))) edge.put("to", replacementId);
          }
        }
        target.put("id", replacementId);
      }
      else if ("add_edge".equals(op)) { Object edge = operation.get("edge"); if (!(edge instanceof Map<?, ?> rawEdge)) throw error("GRAPH_INVALID", "add_edge requires edge"); edges.add(new LinkedHashMap<>((Map<String, Object>) rawEdge)); }
      else if ("remove_edge".equals(op)) { String id = String.valueOf(operation.get("edge_id")); boolean removed = edges.removeIf(edge -> id.equals(String.valueOf(edge.get("id")))); if (!removed) throw error("GRAPH_INVALID", "remove_edge target is missing"); }
      else throw error("GRAPH_INVALID", "unsupported assistant operation");
    }
    // Node rewrites change the endpoint preimage. Recompute every affected edge ID after all
    // operations so no stale edge identifier survives a replace_semantics operation.
    for (Map<String, Object> edge : edges) {
      edge.put("id", edgeId(edge, nodes));
    }
    if (nodes.size() > 256 || edges.size() > 255) throw error("GRAPH_LIMIT_EXCEEDED", "assistant draft exceeds graph limits");
    Set<String> nodeIds = new java.util.HashSet<>();
    Set<Long> ordinals = new java.util.HashSet<>();
    int startCount = 0;
    int terminalCount = 0;
    for (Map<String, Object> node : nodes) {
      String nodeId = String.valueOf(node.get("id"));
      String type = String.valueOf(node.get("type"));
      if (!nodeIds.add(nodeId) || !Set.of("START", "ACTION", "GATE", "ABSTAIN", "RECOVERY", "TERMINAL").contains(type)) throw error("GRAPH_INVALID", "assistant draft contains invalid or duplicate node");
      Object ordinalValue = node.get("ordinal");
      if (ordinalValue == null || !(ordinalValue instanceof Number) || !ordinals.add(((Number) ordinalValue).longValue())) {
        throw error("GRAPH_INVALID", "assistant draft contains duplicate or malformed ordinal");
      }
      if ("START".equals(type)) startCount++;
      if ("TERMINAL".equals(type)) terminalCount++;
    }
    if (startCount != 1) throw error("GRAPH_INVALID", "assistant draft must contain exactly one START");
    if (terminalCount != 1) throw error("GRAPH_INVALID", "assistant draft must contain exactly one TERMINAL");
    Set<String> edgeIds = new java.util.HashSet<>();
    Map<String, List<String>> adjacency = new java.util.HashMap<>();
    Map<String, List<String>> reverse = new java.util.HashMap<>();
    for (Map<String, Object> edge : edges) {
      String edgeId = String.valueOf(edge.get("id")); String from = String.valueOf(edge.get("from")); String to = String.valueOf(edge.get("to"));
      if (!edgeIds.add(edgeId) || !nodeIds.contains(from) || !nodeIds.contains(to)) throw error("GRAPH_INVALID", "assistant draft contains dangling or duplicate edge");
      adjacency.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
      reverse.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from);
    }
    if (hasCycle(adjacency, nodeIds)) throw error("GRAPH_INVALID", "assistant draft contains a cycle");
    String startNode = nodes.stream().filter(node -> "START".equals(String.valueOf(node.get("type")))).map(node -> String.valueOf(node.get("id"))).findFirst().orElseThrow();
    String terminalNode = nodes.stream().filter(node -> "TERMINAL".equals(String.valueOf(node.get("type")))).map(node -> String.valueOf(node.get("id"))).findFirst().orElseThrow();
    if (!reachableFrom(startNode, adjacency, nodeIds)) throw error("GRAPH_INVALID", "assistant draft nodes must be reachable from START");
    if (!reachableFrom(terminalNode, reverse, nodeIds)) throw error("GRAPH_INVALID", "assistant draft nodes must have a path to TERMINAL");
    definition.put("nodes", nodes); definition.put("edges", edges);
  }

  private String edgeId(Map<String, Object> edge, List<Map<String, Object>> nodes) {
    int fromOrdinal = ordinalFor(nodes, String.valueOf(edge.get("from")));
    int toOrdinal = ordinalFor(nodes, String.valueOf(edge.get("to")));
    Map<String, Object> content = new LinkedHashMap<>(edge);
    content.remove("id");
    String hash = CanonicalJsonHasher.hash(mapper.valueToTree(content), null);
    return "edge_" + fromOrdinal + "_" + toOrdinal + "_" + hash.substring(7, 19);
  }

  private int ordinalFor(List<Map<String, Object>> nodes, String nodeId) {
    return nodes.stream()
        .filter(node -> nodeId.equals(String.valueOf(node.get("id"))))
        .map(node -> ((Number) node.get("ordinal")).intValue())
        .findFirst()
        .orElseThrow(() -> error("GRAPH_INVALID", "edge endpoint is missing"));
  }

  private boolean reachableFrom(String root, Map<String, List<String>> adjacency, Set<String> nodes) {
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    Set<String> seen = new java.util.HashSet<>();
    queue.add(root);
    seen.add(root);
    while (!queue.isEmpty()) {
      String current = queue.poll();
      for (String next : adjacency.getOrDefault(current, List.of())) {
        if (seen.add(next)) queue.add(next);
      }
    }
    return seen.size() == nodes.size();
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
    BehaviorGraphDefinition entity = repository.findWithLockByGraphId(id).orElseThrow(() -> error("NOT_FOUND", "graph not found"));
    if ("PUBLISHED".equals(entity.getState())) return response(entity);
    if (!"DRAFT".equals(entity.getState())) throw error("GRAPH_INVALID", "only a draft graph can be published");
    String superseded = entity.getSupersedesGraphId();
    CurrentSliceIdentity identity = readIdentity(entity.getIdentityJson());
    if (identity.run() == null || identity.run().runId() == null) {
      throw error("IDENTITY_MISMATCH", "graph run identity is required for publication");
    }
    if (superseded != null && !superseded.isBlank()) {
      // A published successor may replace the run graph only through the explicit supersession
      // edge; the run must currently reference the superseded graph.
      runService.advanceIdentityGraph(identity.run().runId(), superseded,
          new CurrentSliceIdentity.Graph(entity.getGraphId(), entity.getGraphHash()), "GRAPH_PUBLISHED");
    } else {
      // Publishing a root (generated) graph advances the run graph identity only when the run does
      // not currently reference any graph.
      runService.advanceIdentityRootGraph(identity.run().runId(),
          new CurrentSliceIdentity.Graph(entity.getGraphId(), entity.getGraphHash()), "GRAPH_PUBLISHED");
    }
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
      Map<String, Object> definition = mapper.readValue(entity.getDefinitionJson(), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
      validateDefinitionOrThrow(definition);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema", "behavior-graph-response/v1");
      result.put("definition", definition);
      result.put("state", entity.getState());
      result.put("supersedes_graph_id", entity.getSupersedesGraphId());
      Map<String, Object> assistantDiff = entity.getAssistantDiffJson() == null
          ? null
          : mapper.readValue(entity.getAssistantDiffJson(), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
      if (assistantDiff != null) schemaValidator.validateDiff(mapper.valueToTree(assistantDiff));
      result.put("assistant_diff", assistantDiff);
      result.put("control_allowed", false);
      schemaValidator.validateResponse(mapper.valueToTree(result));
      return result;
    } catch (Exception e) {
      throw error("GRAPH_INVALID", "persisted graph definition is invalid");
    }
  }

  private Map<String, Boolean> disabledAuthority() {
    return Map.of(
        "can_write_source", false,
        "can_approve_patch", false,
        "can_publish_graph", false,
        "can_activate_model", false,
        "can_dispatch_input", false);
  }

  private String write(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception e) { throw error("GRAPH_INVALID", "graph definition is not serializable"); }
  }

  private static RoutineCaptureIngestionService.RoutineCaptureException error(String code, String message) {
    return new RoutineCaptureIngestionService.RoutineCaptureException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }
}
