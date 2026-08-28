package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The provider used to propose {@code operations: []} unconditionally, which could never be
 * accepted: a graph id is the hash of its content, so a diff that changes nothing produces the base
 * graph's own id and collides with it on the unique index. These pin the proposal it makes instead.
 */
class DeterministicRoutineAssistantProviderTest {

  private final DeterministicRoutineAssistantProvider provider =
      new DeterministicRoutineAssistantProvider();

  private static Map<String, Object> node(String id, String type, long ordinal, Map<String, Object> semantics) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", id);
    node.put("type", type);
    node.put("ordinal", ordinal);
    node.put("semantics", semantics);
    return node;
  }

  private static Map<String, Object> action(String option, String target) {
    Map<String, Object> recorded = new LinkedHashMap<>();
    recorded.put("option", option);
    recorded.put("target", target);
    Map<String, Object> semantics = new LinkedHashMap<>();
    semantics.put("kind", "recorded_action");
    semantics.put("recorded_action", recorded);
    return semantics;
  }

  private static Map<String, Object> contextWith(List<Map<String, Object>> nodes) {
    Map<String, Object> definition = new LinkedHashMap<>();
    definition.put("nodes", nodes);
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("graph_id", "graph_0123456789abcdef01234567");
    context.put("graph_hash", "sha256:" + "0".repeat(64));
    context.put("graph_definition", definition);
    return context;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> operationsOf(Map<String, Object> proposal) {
    Map<String, Object> diff = (Map<String, Object>) proposal.get("diff");
    return (List<Map<String, Object>>) diff.get("operations");
  }

  @Test
  void bindsTheTaskInteractionWithoutDisturbingTheRecordedAction() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    nodes.add(node("node_0_aaaaaaaaaaaa", "START", 0, Map.of("kind", "start")));
    nodes.add(node("node_1_bbbbbbbbbbbb", "ACTION", 1, action("Net", "Fishing spot")));
    nodes.add(node("node_2_cccccccccccc", "TERMINAL", 2, Map.of("kind", "terminal")));

    List<Map<String, Object>> operations = operationsOf(provider.propose("conv_1", "review", contextWith(nodes)));

    assertThat(operations).hasSize(1);
    Map<String, Object> bind = operations.get(0);
    assertThat(bind).containsEntry("op", "replace_semantics");
    assertThat(bind).containsEntry("node_id", "node_1_bbbbbbbbbbbb");
    @SuppressWarnings("unchecked")
    Map<String, Object> semantics = (Map<String, Object>) bind.get("semantics");
    assertThat(semantics).containsEntry("kind", "recorded_action");
    // The acceptance reads recorded_action off the draft; it must survive verbatim.
    assertThat(semantics.get("recorded_action"))
        .isEqualTo(Map.of("option", "Net", "target", "Fishing spot"));
    @SuppressWarnings("unchecked")
    Map<String, Object> parameters = (Map<String, Object>) semantics.get("parameters");
    assertThat(parameters)
        .containsEntry("task_key", "FISHING_TRAINING")
        .containsEntry("interaction_option", "Net")
        .containsEntry("interaction_target", "Fishing spot");
  }

  @Test
  void dropsIncidentalActionsAndBridgesTheChain() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    nodes.add(node("node_0_aaaaaaaaaaaa", "START", 0, Map.of("kind", "start")));
    nodes.add(node("node_1_bbbbbbbbbbbb", "ACTION", 1, action("Walk here", "Ground")));
    nodes.add(node("node_2_cccccccccccc", "ACTION", 2, action("Net", "Fishing spot")));
    nodes.add(node("node_3_dddddddddddd", "TERMINAL", 3, Map.of("kind", "terminal")));

    List<Map<String, Object>> operations = operationsOf(provider.propose("conv_1", "review", contextWith(nodes)));

    assertThat(operations).hasSize(3);
    assertThat(operations.get(0))
        .containsEntry("op", "remove_node")
        .containsEntry("node_id", "node_1_bbbbbbbbbbbb");
    // START and the surviving action are no longer adjacent, so the chain needs rejoining.
    assertThat(operations.get(1)).containsEntry("op", "add_edge");
    assertThat(operations.get(1).get("edge"))
        .isEqualTo(Map.of("from", "node_0_aaaaaaaaaaaa", "to", "node_2_cccccccccccc"));
    assertThat(operations.get(2))
        .containsEntry("op", "replace_semantics")
        .containsEntry("node_id", "node_2_cccccccccccc");
  }

  @Test
  void proposesNothingWhenNoCapturedActionDemonstratesTheTask() {
    List<Map<String, Object>> nodes = new ArrayList<>();
    nodes.add(node("node_0_aaaaaaaaaaaa", "START", 0, Map.of("kind", "start")));
    nodes.add(node("node_1_bbbbbbbbbbbb", "ACTION", 1, action("Walk here", "Ground")));
    nodes.add(node("node_2_cccccccccccc", "TERMINAL", 2, Map.of("kind", "terminal")));

    // Inventing a routine the evidence does not support would be worse than failing.
    assertThat(operationsOf(provider.propose("conv_1", "review", contextWith(nodes)))).isEmpty();
  }

  @Test
  void keepsTheDiffEnvelopeReadOnly() {
    Map<String, Object> proposal = provider.propose("conv_1", "review", contextWith(List.of()));
    @SuppressWarnings("unchecked")
    Map<String, Object> diff = (Map<String, Object>) proposal.get("diff");
    assertThat(diff).containsEntry("schema", "behavior-graph-diff/v1");
    assertThat(diff).containsEntry("control_allowed", false);
    assertThat(diff.get("authority"))
        .isEqualTo(
            Map.of(
                "can_write_source", false,
                "can_approve_patch", false,
                "can_publish_graph", false,
                "can_activate_model", false,
                "can_dispatch_input", false));
    assertThat(proposal).containsEntry("control_allowed", false);
  }
}
