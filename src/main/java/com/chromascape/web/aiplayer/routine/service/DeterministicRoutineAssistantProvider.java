package com.chromascape.web.aiplayer.routine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Proposes the minimal deterministic routine graph implied by one captured demonstration.
 *
 * <p>A recording is a linear trace of everything the operator did, incidental clicks included. The
 * routine the evidence actually supports is narrower: the task interaction alone. So the proposal
 * is, deterministically and from the graph itself:
 *
 * <ul>
 *   <li>bind the task interaction, by writing the task key and the observed interaction into that
 *       node's {@code semantics.parameters} while leaving {@code recorded_action} untouched, so the
 *       node states what it is rather than merely what was clicked;
 *   <li>drop every other ACTION node as incidental, bridging the chain so the survivors stay
 *       reachable from START and retain a path to TERMINAL.
 * </ul>
 *
 * <p>Both are derived from the evidence, never invented, and the same graph always yields the same
 * diff. The binding also guarantees the draft differs from its base: graph ids are content hashes,
 * so a proposal that changed nothing would hash to the base id and collide on it.
 */
@Component
public class DeterministicRoutineAssistantProvider implements RoutineAssistantProvider {

  private static final String TASK_KEY = "FISHING_TRAINING";

  @Override
  public Map<String, Object> propose(
      String conversationId, String message, Map<String, Object> context) {
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("schema", "behavior-graph-diff/v1");
    diff.put("base_graph_id", context == null ? null : context.get("graph_id"));
    diff.put("base_graph_hash", context == null ? null : context.get("graph_hash"));
    diff.put("operations", operations(context));
    diff.put("reason", message == null ? "deterministic review" : message);
    diff.put(
        "authority",
        Map.of(
            "can_write_source", false,
            "can_approve_patch", false,
            "can_publish_graph", false,
            "can_activate_model", false,
            "can_dispatch_input", false));
    diff.put("control_allowed", false);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("proposal_id", "proposal_" + conversationId);
    result.put("diff", diff);
    result.put("status", "PROPOSED");
    result.put("control_allowed", false);
    return result;
  }

  private List<Map<String, Object>> operations(Map<String, Object> context) {
    List<Map<String, Object>> operations = new ArrayList<>();
    List<Map<String, Object>> nodes = nodesOf(context);
    if (nodes.isEmpty()) {
      return operations;
    }

    Map<String, Object> taskNode = null;
    List<Map<String, Object>> incidental = new ArrayList<>();
    for (Map<String, Object> node : nodes) {
      if (!"ACTION".equals(String.valueOf(node.get("type")))) {
        continue;
      }
      if (taskNode == null && isTaskInteraction(node)) {
        taskNode = node;
      } else {
        incidental.add(node);
      }
    }
    if (taskNode == null) {
      // Nothing in this capture demonstrates the task. Propose no change and let the acceptance
      // report the real problem -- the recording -- rather than inventing a routine for it.
      return operations;
    }

    // Removals first: they take their incident edges with them, and the bridging edges added next
    // are rewritten along with the originals when replace_semantics renames the task node.
    for (Map<String, Object> node : incidental) {
      Map<String, Object> operation = new LinkedHashMap<>();
      operation.put("op", "remove_node");
      operation.put("node_id", String.valueOf(node.get("id")));
      operations.add(operation);
    }

    List<Map<String, Object>> survivors = new ArrayList<>(nodes);
    survivors.removeAll(incidental);
    survivors.sort((left, right) -> Long.compare(ordinal(left), ordinal(right)));
    for (int i = 0; i < survivors.size() - 1; i++) {
      Map<String, Object> from = survivors.get(i);
      Map<String, Object> to = survivors.get(i + 1);
      // The generated graph is a chain in ordinal order, so consecutive survivors are already
      // joined unless something between them was removed.
      if (ordinal(to) - ordinal(from) == 1) {
        continue;
      }
      Map<String, Object> edge = new LinkedHashMap<>();
      // An edge id is the hash of its own content, which BehaviorGraphService recomputes for every
      // edge once the operations have been applied. The diff schema still requires a well-formed id
      // here, so carry the real ordinals and zero the hash, the same placeholder idiom the graph
      // itself uses for graph_id and graph_hash before it knows them.
      edge.put("id", "edge_" + ordinal(from) + "_" + ordinal(to) + "_" + "0".repeat(12));
      edge.put("from", String.valueOf(from.get("id")));
      edge.put("to", String.valueOf(to.get("id")));
      Map<String, Object> operation = new LinkedHashMap<>();
      operation.put("op", "add_edge");
      operation.put("edge", edge);
      operations.add(operation);
    }

    Map<String, Object> semantics = new LinkedHashMap<>(semanticsOf(taskNode));
    Map<String, Object> recordedAction = mapAt(semantics, "recorded_action");
    Map<String, Object> parameters = new LinkedHashMap<>(mapAt(semantics, "parameters"));
    parameters.put("task_key", TASK_KEY);
    parameters.put("interaction_option", String.valueOf(recordedAction.get("option")));
    parameters.put("interaction_target", String.valueOf(recordedAction.get("target")));
    semantics.put("parameters", parameters);
    Map<String, Object> bind = new LinkedHashMap<>();
    bind.put("op", "replace_semantics");
    bind.put("node_id", String.valueOf(taskNode.get("id")));
    bind.put("semantics", semantics);
    operations.add(bind);
    return operations;
  }

  private boolean isTaskInteraction(Map<String, Object> node) {
    Map<String, Object> recordedAction = mapAt(semanticsOf(node), "recorded_action");
    String option = String.valueOf(recordedAction.get("option")).toLowerCase(Locale.ROOT);
    String target = String.valueOf(recordedAction.get("target")).toLowerCase(Locale.ROOT);
    return (option.contains("net") || option.contains("fish")) && target.contains("fishing");
  }

  private static long ordinal(Map<String, Object> node) {
    Object value = node.get("ordinal");
    return value instanceof Number number ? number.longValue() : Long.MAX_VALUE;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> nodesOf(Map<String, Object> context) {
    Object definition = context == null ? null : context.get("graph_definition");
    Object nodes = definition instanceof Map<?, ?> map ? ((Map<String, Object>) map).get("nodes") : null;
    List<Map<String, Object>> result = new ArrayList<>();
    if (nodes instanceof List<?> list) {
      for (Object node : list) {
        if (node instanceof Map<?, ?> value) {
          result.add((Map<String, Object>) value);
        }
      }
    }
    return result;
  }

  private static Map<String, Object> semanticsOf(Map<String, Object> node) {
    return mapAt(node, "semantics");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapAt(Map<String, Object> owner, String key) {
    Object value = owner == null ? null : owner.get(key);
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }
}
