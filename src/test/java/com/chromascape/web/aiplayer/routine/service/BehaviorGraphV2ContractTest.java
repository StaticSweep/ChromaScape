package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Focused SC-728 contract tests; SC-967 v1 validation remains covered by its existing suite. */
class BehaviorGraphV2ContractTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final BehaviorGraphV2Canonicalizer graphCanonicalizer =
      new BehaviorGraphV2Canonicalizer();
  private final BehaviorGraphLayoutCanonicalizer layoutCanonicalizer =
      new BehaviorGraphLayoutCanonicalizer();

  @Test
  void v2DefinitionRequiresMatchingSchemaVersionAndDisabledAuthority() throws Exception {
    GraphSchemaValidator schemas = new GraphSchemaValidator();
    JsonNode valid = graph();

    schemas.validateDefinitionV2(valid);

    ((com.fasterxml.jackson.databind.node.ObjectNode) valid.path("authority"))
        .put("can_publish_graph", true);
    assertThatThrownBy(() -> schemas.validateDefinitionV2(valid))
        .hasMessageContaining("GRAPH_SCHEMA_INVALID");
  }

  @Test
  void canonicalContentIgnoresSubmittedHashAndBehaviorIndependentOrdering() throws Exception {
    JsonNode first = graph();
    JsonNode reordered = mapper.readTree(graph().toString());
    com.fasterxml.jackson.databind.node.ArrayNode nodes =
        (com.fasterxml.jackson.databind.node.ArrayNode) reordered.path("nodes");
    JsonNode start = nodes.remove(0);
    nodes.add(start);
    ((com.fasterxml.jackson.databind.node.ObjectNode) reordered)
        .put("graph_hash", "sha256:" + "b".repeat(64));

    assertThat(graphCanonicalizer.canonicalDefinition(first))
        .isEqualTo(graphCanonicalizer.canonicalDefinition(reordered));
    assertThat(graphCanonicalizer.graphHash(first))
        .isEqualTo(graphCanonicalizer.graphHash(reordered));
  }

  @Test
  void nestedHashNamedPropertyRemainsBehaviorSignificant() throws Exception {
    JsonNode first = graph();
    JsonNode changed = mapper.readTree(first.toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            changed.path("nodes").get(0).path("configuration"))
        .put("graph_hash", "configuration-value");

    assertThat(graphCanonicalizer.graphHash(first))
        .isNotEqualTo(graphCanonicalizer.graphHash(changed));
  }

  @Test
  void movingPresentationOnlyLayoutDoesNotChangeGraphHashButChangesLayoutHash() throws Exception {
    JsonNode definition = graph();
    JsonNode originalLayout = layout(10);
    JsonNode movedLayout = layout(999);

    String graphHashBeforeLayoutMove = graphCanonicalizer.graphHash(definition);
    layoutCanonicalizer.layoutHash(originalLayout);
    layoutCanonicalizer.layoutHash(movedLayout);
    assertThat(graphCanonicalizer.graphHash(definition)).isEqualTo(graphHashBeforeLayoutMove);
    assertThat(layoutCanonicalizer.layoutHash(originalLayout))
        .isNotEqualTo(layoutCanonicalizer.layoutHash(movedLayout));
  }

  @Test
  void semanticValidationRejectsOneNodeUnboundedSelfLoop() throws Exception {
    JsonNode graph = graph();
    com.fasterxml.jackson.databind.node.ArrayNode edges =
        (com.fasterxml.jackson.databind.node.ArrayNode) graph.path("edges");
    edges.add(
        mapper.readTree(
            "{\"id\":\"loop\",\"from\":\"start\",\"to\":\"start\",\"priority\":0,\"source_order\":1}"));

    assertThat(new BehaviorGraphV2Validator().validate(graph).errors())
        .extracting(issue -> issue.code())
        .contains("UNBOUNDED_CYCLE");
  }

  private JsonNode graph() throws Exception {
    return mapper.readTree(
        """
        {
          "schema":"behavior-graph-definition/v2",
          "schema_version":"behavior-graph-definition/v2",
          "graph_id":"fishing_routine",
          "graph_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "task":{"task_key":"FISHING_TRAINING"},
          "profile":{"profile_id":"fishing","profile_version":"v1"},
          "nodes":[
            {"id":"start","type":"START","label":"Start","configuration":{}},
            {"id":"done","type":"SUCCESS","label":"Done","configuration":{}}
          ],
          "edges":[{"id":"finish","from":"start","to":"done","priority":0,"source_order":0}],
          "authority":{"can_write_source":false,"can_approve_patch":false,"can_publish_graph":false,"can_activate_model":false,"can_dispatch_input":false},
          "control_allowed":false
        }
        """);
  }

  private JsonNode layout(int x) throws Exception {
    return mapper.readTree(
        """
        {
          "schema":"behavior-graph-layout/v1",
          "schema_version":"behavior-graph-layout/v1",
          "graph_id":"fishing_routine",
          "layout_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "nodes":[{"node_id":"start","x":%d,"y":20},{"node_id":"done","x":50,"y":60}],
          "viewport":{"x":0,"y":0,"zoom":1}
        }
        """
            .formatted(x));
  }
}
