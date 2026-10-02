package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationIssue;
import com.chromascape.web.aiplayer.routine.model.BehaviorGraphValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the server's semantic validation to the editor's vocabulary. The operator sees the server's
 * result on save and the local result while editing, so a graph the browser renders as VALID must
 * not be rejected by publish with a code the editor has never heard of.
 */
class BehaviorGraphV2ValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final BehaviorGraphV2Validator validator = new BehaviorGraphV2Validator();

  @Test
  void missingStartUsesTheEditorCode() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                ""));
    assertThat(codes(result)).contains("MISSING_START").doesNotContain("START_COUNT_INVALID");
  }

  @Test
  void multipleStartsUseTheEditorCode() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"other\",\"type\":\"START\",\"label\":\"Other\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("finish", "start", "done")));
    assertThat(codes(result)).contains("MULTIPLE_START_NODES");
  }

  @Test
  void semanticActionWithoutAKeyIsUnknownAction() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"act\",\"type\":\"SEMANTIC_ACTION\",\"label\":\"Act\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "act") + "," + edge("b", "act", "done")));
    assertThat(codes(result)).contains("UNKNOWN_ACTION").doesNotContain("ACTION_NOT_ALLOWED");
    assertThat(issues(result, "UNKNOWN_ACTION").get(0).nodeId()).isEqualTo("act");
  }

  @Test
  void fishingVocabularyIsAcceptedWithoutACatalog() throws Exception {
    // The old hard-coded allowlist held only fishing/interact_npc/wait_until_tick, so publishing
    // the
    // fishing fixture failed. With no catalog the server now requires a key, not a specific key.
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"validate_client\",\"type\":\"SEMANTIC_ACTION\",\"label\":\"Validate\",\"semantic_key\":\"validate_client\",\"configuration\":{}},"
                    + "{\"id\":\"bank_inventory\",\"type\":\"SEMANTIC_ACTION\",\"label\":\"Bank\",\"semantic_key\":\"bank_inventory\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "validate_client")
                    + ","
                    + edge("b", "validate_client", "bank_inventory")
                    + ","
                    + edge("c", "bank_inventory", "done")));
    assertThat(codes(result)).doesNotContain("UNKNOWN_ACTION");
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void anActionCatalogRejectsUnknownKeys() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"act\",\"type\":\"SEMANTIC_ACTION\",\"label\":\"Act\",\"semantic_key\":\"not_in_catalog\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "act") + "," + edge("b", "act", "done")),
            BehaviorGraphV2Validator.ValidationContext.of(
                null, null, null, Set.of("begin_fishing")));
    assertThat(codes(result)).contains("UNKNOWN_ACTION");
  }

  @Test
  void twoUnconditionalTransitionsAtOnePriorityAreAmbiguous() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "done") + "," + edge("b", "start", "done")));
    assertThat(codes(result)).contains("AMBIGUOUS_TRANSITION");
  }

  @Test
  void aChainDeeperThanTheLimitIsRejected() throws Exception {
    StringBuilder nodes = new StringBuilder();
    StringBuilder edges = new StringBuilder();
    boolean firstEdge = true;
    int depth = 70;
    for (int index = 0; index < depth; index++) {
      String type = index == 0 ? "START" : index == depth - 1 ? "SUCCESS" : "STATE";
      if (index > 0) nodes.append(',');
      nodes
          .append("{\"id\":\"n")
          .append(index)
          .append("\",\"type\":\"")
          .append(type)
          .append("\",\"label\":\"N")
          .append(index)
          .append("\",\"configuration\":{}}");
      if (index > 0) {
        if (!firstEdge) edges.append(',');
        firstEdge = false;
        edges.append(edge("e" + index, "n" + (index - 1), "n" + index));
      }
    }
    BehaviorGraphValidationResult result =
        validator.validate(graph(nodes.toString(), edges.toString()));
    assertThat(codes(result)).contains("GRAPH_TOO_DEEP");
  }

  @Test
  void draftIdentityIsEnforcedWhenExpectationsAreSupplied() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "done")),
            BehaviorGraphV2Validator.ValidationContext.of(
                "MINING_TRAINING", "fishing-default", "v1", null));
    assertThat(codes(result)).contains("TASK_NOT_FOUND");
  }

  @Test
  void wireFormCarriesNodeIdentityInSnakeCase() throws Exception {
    BehaviorGraphValidationResult result =
        validator.validate(
            graph(
                "{\"id\":\"start\",\"type\":\"START\",\"label\":\"Start\",\"configuration\":{}},"
                    + "{\"id\":\"act\",\"type\":\"SEMANTIC_ACTION\",\"label\":\"Act\",\"configuration\":{}},"
                    + "{\"id\":\"done\",\"type\":\"SUCCESS\",\"label\":\"Done\",\"configuration\":{}}",
                edge("a", "start", "act") + "," + edge("b", "act", "done")));
    assertThat(issues(result, "UNKNOWN_ACTION").get(0).toWire())
        .containsEntry("code", "UNKNOWN_ACTION")
        .containsEntry("severity", "ERROR")
        .containsEntry("node_id", "act")
        .doesNotContainKey("edge_id");
  }

  private java.util.List<String> codes(BehaviorGraphValidationResult result) {
    return java.util.stream.Stream.concat(result.errors().stream(), result.warnings().stream())
        .map(BehaviorGraphValidationIssue::code)
        .toList();
  }

  private java.util.List<BehaviorGraphValidationIssue> issues(
      BehaviorGraphValidationResult result, String code) {
    return result.errors().stream().filter(issue -> issue.code().equals(code)).toList();
  }

  private static String edge(String id, String from, String to) {
    return "{\"id\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"priority\":0,\"source_order\":0}"
        .formatted(id, from, to);
  }

  private JsonNode graph(String nodes, String edges) throws Exception {
    return mapper.readTree(
        """
        {"schema":"behavior-graph-definition/v2","schema_version":"behavior-graph-definition/v2",
         "graph_id":"test_graph",
         "graph_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "task":{"task_key":"FISHING_TRAINING"},
         "profile":{"profile_id":"fishing-default","profile_version":"v1"},
         "nodes":[%s],
         "edges":[%s],
         "authority":{"can_write_source":false,"can_approve_patch":false,"can_publish_graph":false,"can_activate_model":false,"can_dispatch_input":false},
         "control_allowed":false}
        """
            .formatted(nodes, edges));
  }
}
