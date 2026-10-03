package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationIssue;
import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Semantic validation for behavior-graph-definition/v2.
 *
 * <p>This is a line-for-line mirror of the editor's {@code engine/graph-validator.ts}. The two
 * implementations must agree on both the checks and the issue codes, because the operator sees the
 * server's result on save and the local result while editing: a graph that renders as VALID in the
 * browser must not be rejected by publish. Codes are therefore the editor's vocabulary ({@code
 * MISSING_START}, {@code UNKNOWN_ACTION}, {@code AMBIGUOUS_TRANSITION}, ...), not an independent
 * server dialect.
 *
 * <p>Semantic checks deliberately run after JSON-schema validation so that structural failures keep
 * their own codes and this class only ever reports semantic issues.
 */
@Component
public class BehaviorGraphV2Validator {
  private static final String SCHEMA_VERSION = "behavior-graph-definition/v2";
  private static final String ENTRY_TYPE = "START";
  private static final Set<String> TERMINALS = Set.of("STOP", "SUCCESS", "FAILURE", "ABSTAIN");
  private static final int MAX_NODES = 256;
  private static final int MAX_EDGES = 512;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_LABEL_LENGTH = 120;

  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final Pattern GUARD_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  /**
   * Expectations the server holds independently of the submitted document.
   *
   * @param expectedTaskKey the draft's recorded task key; the definition may not change it
   * @param expectedProfileId the draft's recorded profile id
   * @param expectedProfileVersion the draft's recorded profile version
   * @param actionCatalog allowlisted semantic action keys, or {@code null} when no catalog is
   *     configured. The editor behaves identically: without a catalog it only requires a key.
   */
  public record ValidationContext(
      String expectedTaskKey,
      String expectedProfileId,
      String expectedProfileVersion,
      Set<String> actionCatalog) {
    public static final ValidationContext NONE = new ValidationContext(null, null, null, null);

    public static ValidationContext of(
        String taskKey, String profileId, String profileVersion, Set<String> actionCatalog) {
      return new ValidationContext(taskKey, profileId, profileVersion, actionCatalog);
    }
  }

  public BehaviorGraphValidationResult validate(JsonNode graph) {
    return validate(graph, ValidationContext.NONE);
  }

  public BehaviorGraphValidationResult validate(JsonNode graph, ValidationContext context) {
    List<BehaviorGraphValidationIssue> errors = new ArrayList<>();
    List<BehaviorGraphValidationIssue> warnings = new ArrayList<>();

    if (!SCHEMA_VERSION.equals(graph.path("schema_version").asText())) {
      graphError(
          errors,
          "SCHEMA_UNSUPPORTED",
          "/schema_version",
          "Unsupported schema version: " + graph.path("schema_version").asText());
      return new BehaviorGraphValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    String taskKey = graph.path("task").path("task_key").asText();
    String profileId = graph.path("profile").path("profile_id").asText();
    String profileVersion = graph.path("profile").path("profile_version").asText();
    if (context.expectedTaskKey() != null && !context.expectedTaskKey().equals(taskKey)) {
      graphError(
          errors,
          "TASK_NOT_FOUND",
          "/task/task_key",
          "Task \"" + taskKey + "\" was not found in the workspace catalog.");
    }
    if (context.expectedProfileId() != null
        && (!context.expectedProfileId().equals(profileId)
            || !java.util.Objects.equals(context.expectedProfileVersion(), profileVersion))) {
      graphError(
          errors,
          "PROFILE_MISMATCH",
          "/profile",
          "Behaviour profile identity does not match the selected task profile.");
    }

    List<JsonNode> nodes = new ArrayList<>();
    graph.path("nodes").forEach(nodes::add);
    List<JsonNode> edges = new ArrayList<>();
    graph.path("edges").forEach(edges::add);

    if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES) {
      graphError(
          errors,
          "GRAPH_TOO_LARGE",
          "/nodes",
          "Graph exceeds " + MAX_NODES + " nodes or " + MAX_EDGES + " edges.");
      return new BehaviorGraphValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    Set<String> seenNodeIds = new LinkedHashSet<>();
    for (JsonNode node : nodes) {
      String id = node.path("id").asText();
      if (!seenNodeIds.add(id)) {
        nodeError(errors, "DUPLICATE_NODE_ID", id, "Duplicate node id \"" + id + "\".");
      }
      validateNode(node, context, errors, warnings);
    }

    Set<String> seenEdgeIds = new HashSet<>();
    for (JsonNode edge : edges) {
      String id = edge.path("id").asText();
      if (!seenEdgeIds.add(id)) {
        edgeError(errors, "DUPLICATE_EDGE_ID", id, "Duplicate edge id \"" + id + "\".");
      }
      validateEdge(edge, errors);
      String from = edge.path("from").asText();
      String to = edge.path("to").asText();
      if (!seenNodeIds.contains(from) || !seenNodeIds.contains(to)) {
        edgeError(
            errors,
            "DANGLING_EDGE",
            id,
            "Edge \"" + id + "\" references a node that does not exist.");
      }
    }

    List<JsonNode> startNodes = new ArrayList<>();
    for (JsonNode node : nodes) {
      if (ENTRY_TYPE.equals(node.path("type").asText())) startNodes.add(node);
    }
    if (startNodes.isEmpty()) {
      graphError(errors, "MISSING_START", "/nodes", "Graph has no START node.");
    } else if (startNodes.size() > 1) {
      graphError(
          errors,
          "MULTIPLE_START_NODES",
          "/nodes",
          "Graph has " + startNodes.size() + " START nodes; exactly one is required.");
    }

    boolean hasTerminal = false;
    for (JsonNode node : nodes) {
      if (TERMINALS.contains(node.path("type").asText())) hasTerminal = true;
    }
    if (!hasTerminal) {
      graphError(errors, "MISSING_TERMINAL", "/nodes", "Graph has no terminal node.");
    }

    Map<String, List<String>> adjacency = new LinkedHashMap<>();
    for (JsonNode node : nodes) adjacency.put(node.path("id").asText(), new ArrayList<>());
    for (JsonNode edge : edges) {
      List<String> targets = adjacency.get(edge.path("from").asText());
      if (targets != null) targets.add(edge.path("to").asText());
    }

    for (JsonNode node : nodes) {
      String id = node.path("id").asText();
      List<JsonNode> outgoing = new ArrayList<>();
      for (JsonNode edge : edges) {
        if (id.equals(edge.path("from").asText())) outgoing.add(edge);
      }
      if (outgoing.isEmpty() && !TERMINALS.contains(node.path("type").asText())) {
        nodeWarning(
            warnings, "DEAD_END_NODE", id, "Node \"" + id + "\" has no outgoing transition.");
      }
      Map<Integer, List<JsonNode>> byPriority = new LinkedHashMap<>();
      for (JsonNode edge : outgoing) {
        byPriority
            .computeIfAbsent(edge.path("priority").asInt(), key -> new ArrayList<>())
            .add(edge);
      }
      for (List<JsonNode> group : byPriority.values()) {
        int unconditional = 0;
        for (JsonNode edge : group) {
          JsonNode guard = edge.get("guard");
          if (guard == null || guard.isNull()) unconditional++;
        }
        if (unconditional > 1) {
          nodeError(
              errors,
              "AMBIGUOUS_TRANSITION",
              id,
              "Node \""
                  + id
                  + "\" has "
                  + unconditional
                  + " unconditional transitions at priority "
                  + group.get(0).path("priority").asInt()
                  + ".");
        }
      }
    }

    if (!startNodes.isEmpty()) {
      String startId = startNodes.get(0).path("id").asText();
      Set<String> reached = reachability(adjacency, startId);
      for (JsonNode node : nodes) {
        String id = node.path("id").asText();
        if (id.equals(startId)) continue;
        if (!reached.contains(id)) {
          nodeError(
              errors, "UNREACHABLE_NODE", id, "Node \"" + id + "\" is unreachable from START.");
        }
      }
    }

    List<String> unboundedNodes = new ArrayList<>();
    for (JsonNode node : nodes) {
      if (!nodeBound(node)) unboundedNodes.add(node.path("id").asText());
    }
    if (unboundedNodes.size() > 1 && hasCycle(unboundedNodes, edges)) {
      graphError(
          errors,
          "UNBOUNDED_CYCLE",
          "/edges",
          "Graph contains a cycle with no timeout or retry bound.");
    }

    List<String> nodeIds = new ArrayList<>();
    for (JsonNode node : nodes) nodeIds.add(node.path("id").asText());
    if (longestCondensationPath(nodeIds, edges) > MAX_DEPTH) {
      graphError(
          errors, "GRAPH_TOO_DEEP", "/nodes", "Graph depth exceeds " + MAX_DEPTH + " levels.");
    }

    return new BehaviorGraphValidationResult(List.copyOf(errors), List.copyOf(warnings));
  }

  private void validateNode(
      JsonNode node,
      ValidationContext context,
      List<BehaviorGraphValidationIssue> errors,
      List<BehaviorGraphValidationIssue> warnings) {
    String id = node.path("id").asText();
    if (!IDENTIFIER.matcher(id).matches()) {
      graphError(
          errors,
          "INVALID_IDENTIFIER",
          "/nodes",
          "Node id \"" + id + "\" is not a valid identifier.");
    }
    String label = node.path("label").asText();
    if (label.trim().isEmpty() || label.length() > MAX_LABEL_LENGTH) {
      nodeError(
          errors,
          "INVALID_LABEL",
          id,
          "Node \"" + id + "\" label must be 1-" + MAX_LABEL_LENGTH + " characters.");
    }
    if ("SEMANTIC_ACTION".equals(node.path("type").asText())) {
      JsonNode semanticKey = node.get("semantic_key");
      if (semanticKey == null || semanticKey.isNull() || semanticKey.asText().isEmpty()) {
        nodeError(
            errors,
            "UNKNOWN_ACTION",
            id,
            "Semantic action node \"" + id + "\" has no semantic key.");
      } else if (context.actionCatalog() != null
          && !context.actionCatalog().contains(semanticKey.asText())) {
        nodeError(
            errors,
            "UNKNOWN_ACTION",
            id,
            "Action \"" + semanticKey.asText() + "\" is not in the allowlisted action catalog.");
      }
    }
    JsonNode timeout = node.get("timeout_ticks");
    if (timeout != null
        && !timeout.isNull()
        && (!timeout.isIntegralNumber() || timeout.asInt() < 1 || timeout.asInt() > 100000)) {
      nodeError(
          errors,
          "INVALID_TIMEOUT",
          id,
          "Node \"" + id + "\" timeout must be an integer between 1 and 100000 ticks.");
    }
    JsonNode attempts = node.get("maximum_attempts");
    if (attempts != null
        && !attempts.isNull()
        && (!attempts.isIntegralNumber() || attempts.asInt() < 1 || attempts.asInt() > 64)) {
      nodeError(
          errors,
          "INVALID_RETRY_LIMIT",
          id,
          "Node \"" + id + "\" maximum attempts must be between 1 and 64.");
    }
    JsonNode reasonCode = node.get("reason_code");
    if (reasonCode != null
        && !reasonCode.isNull()
        && !REASON_CODE.matcher(reasonCode.asText()).matches()) {
      nodeError(errors, "INVALID_REASON_CODE", id, "Node \"" + id + "\" reason code is not valid.");
    }
    if ("ABSTAIN".equals(node.path("type").asText())
        && (reasonCode == null || reasonCode.isNull())) {
      nodeWarning(
          warnings, "INVALID_REASON_CODE", id, "Abstain node \"" + id + "\" has no reason code.");
    }
  }

  private void validateEdge(JsonNode edge, List<BehaviorGraphValidationIssue> errors) {
    String id = edge.path("id").asText();
    if (!IDENTIFIER.matcher(id).matches()) {
      graphError(
          errors,
          "INVALID_IDENTIFIER",
          "/edges",
          "Edge id \"" + id + "\" is not a valid identifier.");
    }
    int priority = edge.path("priority").asInt();
    if (!edge.path("priority").isIntegralNumber() || priority < 0 || priority > 1000) {
      edgeError(
          errors,
          "INVALID_PRIORITY",
          id,
          "Edge \"" + id + "\" priority must be an integer between 0 and 1000.");
    }
    int sourceOrder = edge.path("source_order").asInt();
    if (!edge.path("source_order").isIntegralNumber() || sourceOrder < 0 || sourceOrder > 1000) {
      edgeError(
          errors,
          "INVALID_SOURCE_ORDER",
          id,
          "Edge \"" + id + "\" source order must be an integer between 0 and 1000.");
    }
    JsonNode guard = edge.get("guard");
    if (guard != null
        && !guard.isNull()
        && !GUARD_TYPE.matcher(guard.path("type").asText()).matches()) {
      edgeError(errors, "INVALID_GUARD", id, "Edge \"" + id + "\" guard type is not valid.");
    }
    JsonNode reasonCode = edge.get("reason_code");
    if (reasonCode != null
        && !reasonCode.isNull()
        && !REASON_CODE.matcher(reasonCode.asText()).matches()) {
      edgeError(errors, "INVALID_REASON_CODE", id, "Edge \"" + id + "\" reason code is not valid.");
    }
  }

  private static boolean nodeBound(JsonNode node) {
    JsonNode timeout = node.get("timeout_ticks");
    JsonNode attempts = node.get("maximum_attempts");
    return (timeout != null && !timeout.isNull()) || (attempts != null && !attempts.isNull());
  }

  private static Set<String> reachability(Map<String, List<String>> adjacency, String from) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(from);
    while (!queue.isEmpty()) {
      String current = queue.remove();
      for (String next : adjacency.getOrDefault(current, List.of())) {
        if (seen.add(next)) queue.add(next);
      }
    }
    return seen;
  }

  private static boolean hasCycle(List<String> nodeIds, List<JsonNode> edges) {
    Set<String> allowed = new HashSet<>(nodeIds);
    Map<String, List<String>> adjacency = new LinkedHashMap<>();
    for (String id : nodeIds) adjacency.put(id, new ArrayList<>());
    for (JsonNode edge : edges) {
      String from = edge.path("from").asText();
      String to = edge.path("to").asText();
      if (!allowed.contains(from) || !allowed.contains(to)) continue;
      adjacency.get(from).add(to);
    }
    Map<String, String> state = new HashMap<>();
    for (String start : nodeIds) {
      if (state.containsKey(start)) continue;
      Deque<String[]> stack = new ArrayDeque<>();
      stack.push(new String[] {start, "0"});
      state.put(start, "visiting");
      while (!stack.isEmpty()) {
        String[] frame = stack.peek();
        List<String> neighbours = adjacency.getOrDefault(frame[0], List.of());
        int index = Integer.parseInt(frame[1]);
        if (index >= neighbours.size()) {
          state.put(frame[0], "done");
          stack.pop();
          continue;
        }
        String next = neighbours.get(index);
        frame[1] = Integer.toString(index + 1);
        String nextState = state.get(next);
        if ("visiting".equals(nextState)) return true;
        if (nextState != null) continue;
        state.put(next, "visiting");
        stack.push(new String[] {next, "0"});
      }
    }
    return false;
  }

  private static int longestCondensationPath(List<String> nodeIds, List<JsonNode> edges) {
    Map<String, List<String>> adjacency = new LinkedHashMap<>();
    for (String id : nodeIds) adjacency.put(id, new ArrayList<>());
    for (JsonNode edge : edges) {
      List<String> targets = adjacency.get(edge.path("from").asText());
      if (targets != null) targets.add(edge.path("to").asText());
    }

    Map<String, Integer> componentOf = new HashMap<>();
    int componentCount = 0;
    for (String id : nodeIds) {
      if (componentOf.containsKey(id)) continue;
      Set<String> forward = reachability(adjacency, id);
      List<String> members = new ArrayList<>();
      members.add(id);
      for (String other : nodeIds) {
        if (other.equals(id) || componentOf.containsKey(other)) continue;
        if (!forward.contains(other)) continue;
        if (reachability(adjacency, other).contains(id)) members.add(other);
      }
      for (String member : members) componentOf.put(member, componentCount);
      componentCount++;
    }

    List<Set<Integer>> successors = new ArrayList<>();
    for (int index = 0; index < componentCount; index++) successors.add(new LinkedHashSet<>());
    for (JsonNode edge : edges) {
      Integer from = componentOf.get(edge.path("from").asText());
      Integer to = componentOf.get(edge.path("to").asText());
      if (from == null || to == null || from.equals(to)) continue;
      successors.get(from).add(to);
    }

    Map<Integer, Integer> memo = new HashMap<>();
    int longest = 0;
    for (int index = 0; index < componentCount; index++) {
      longest = Math.max(longest, depthOf(index, successors, memo, new HashSet<>()));
    }
    return longest;
  }

  private static int depthOf(
      int component,
      List<Set<Integer>> successors,
      Map<Integer, Integer> memo,
      Set<Integer> guard) {
    Integer cached = memo.get(component);
    if (cached != null) return cached;
    if (!guard.add(component)) return 0;
    int best = 0;
    for (int next : successors.get(component)) {
      best = Math.max(best, depthOf(next, successors, memo, guard));
    }
    guard.remove(component);
    int total = best + 1;
    memo.put(component, total);
    return total;
  }

  private static void nodeError(
      List<BehaviorGraphValidationIssue> out, String code, String nodeId, String message) {
    out.add(
        new BehaviorGraphValidationIssue(code, "ERROR", "/nodes/" + nodeId, message, nodeId, null));
  }

  private static void nodeWarning(
      List<BehaviorGraphValidationIssue> out, String code, String nodeId, String message) {
    out.add(
        new BehaviorGraphValidationIssue(
            code, "WARNING", "/nodes/" + nodeId, message, nodeId, null));
  }

  private static void edgeError(
      List<BehaviorGraphValidationIssue> out, String code, String edgeId, String message) {
    out.add(
        new BehaviorGraphValidationIssue(code, "ERROR", "/edges/" + edgeId, message, null, edgeId));
  }

  private static void graphError(
      List<BehaviorGraphValidationIssue> out, String code, String path, String message) {
    out.add(new BehaviorGraphValidationIssue(code, "ERROR", path, message, null, null));
  }
}
