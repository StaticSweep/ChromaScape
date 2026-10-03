package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Recomputes the digest of every vector in {@code contracts/behavior-graph-hash-vectors-v1.json}.
 * The same file is vendored by the editor as {@code
 * src/features/ai-routine/fixtures/behavior-graph-hash-vectors-v1.json} and recomputed by its own
 * suite, so a digest change here means the two runtimes can no longer agree.
 */
class BehaviorGraphHashVectorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final BehaviorGraphV2Canonicalizer graphs = new BehaviorGraphV2Canonicalizer();
  private final BehaviorGraphLayoutCanonicalizer layouts = new BehaviorGraphLayoutCanonicalizer();

  @Test
  void crossRuntimeGoldenVectorsMatch() throws Exception {
    JsonNode suite = suite();
    assertThat(suite.path("version").asInt()).isEqualTo(1);
    assertThat(suite.path("vectors").size()).isGreaterThanOrEqualTo(10);
    for (JsonNode vector : suite.path("vectors")) {
      assertThat(hashOf(vector))
          .as("vector %s", vector.path("name").asText())
          .isEqualTo(vector.path("expected_hash").asText());
    }
  }

  @Test
  void fractionalCoordinatesAndIntegerValuedFloatsAreHashable() throws Exception {
    // The old identity hasher rejected every floating-point number, so a dragged node could satisfy
    // the layout schema and then fail while computing layout_hash.
    assertThat(
            layouts.layoutHash(
                layout("\"x\": 10.5, \"y\": -20.25", "{\"x\": 1.5, \"y\": 0, \"zoom\": 0.62}")))
        .as("fractional layout")
        .startsWith("sha256:");
    assertThat(
            layouts.layoutHash(
                layout("\"x\": 10.0, \"y\": 0.0", "{\"x\": 0, \"y\": 0, \"zoom\": 1}")))
        .as("integer-valued floats serialize without a trailing .0")
        .isEqualTo(
            layouts.layoutHash(layout("\"x\": 10, \"y\": 0", "{\"x\": 0, \"y\": 0, \"zoom\": 1}")));
  }

  @Test
  void nestedContainerArraysKeepTheirDeclaredOrder() throws Exception {
    // Only the root nodes/edges/groups arrays are ordered. A nested array that happens to be named
    // `nodes` is behavior-significant data, so reordering it must change the hash.
    assertThat(graphs.graphHash(graph("{\"nodes\": [{\"id\": \"alpha\"}, {\"id\": \"zeta\"}]}")))
        .isNotEqualTo(
            graphs.graphHash(graph("{\"nodes\": [{\"id\": \"zeta\"}, {\"id\": \"alpha\"}]}")));
  }

  @Test
  void missingGroupsAndEmptyGroupsShareOneHash() throws Exception {
    assertThat(layouts.layoutHash(layoutWithoutGroups(false)))
        .isEqualTo(layouts.layoutHash(layoutWithoutGroups(true)));
  }

  @Test
  void crlfAndLfLabelsShareOneHash() throws Exception {
    assertThat(graphs.graphHash(graphWithEscapedLabel("Start\r\nsecond line")))
        .isEqualTo(graphs.graphHash(graphWithEscapedLabel("Start\nsecond line")));
  }

  private JsonNode suite() throws Exception {
    try (InputStream in =
        new ClassPathResource("contracts/behavior-graph-hash-vectors-v1.json").getInputStream()) {
      return mapper.readTree(in);
    }
  }

  private String hashOf(JsonNode vector) {
    JsonNode document = vector.path("document");
    return "graph".equals(vector.path("kind").asText())
        ? graphs.graphHash(document)
        : layouts.layoutHash(document);
  }

  private JsonNode layout(String placement, String viewport) throws Exception {
    return mapper.readTree(
        """
        {"schema":"behavior-graph-layout/v1","schema_version":"behavior-graph-layout/v1",
         "graph_id":"vector_graph",
         "layout_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "nodes":[{"node_id":"start",%s},{"node_id":"done","x":280,"y":0}],
         "viewport":%s}
        """
            .formatted(placement, viewport));
  }

  private JsonNode layoutWithoutGroups(boolean groups) throws Exception {
    return mapper.readTree(
        """
        {"schema":"behavior-graph-layout/v1","schema_version":"behavior-graph-layout/v1",
         "graph_id":"vector_graph",
         "layout_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "nodes":[{"node_id":"start","x":10.5,"y":0}],
         "viewport":{"x":0,"y":0,"zoom":1}%s}
        """
            .formatted(groups ? ",\"groups\":[]" : ""));
  }

  private JsonNode graph(String configuration) throws Exception {
    return mapper.readTree(
        """
        {"schema":"behavior-graph-definition/v2","schema_version":"behavior-graph-definition/v2",
         "graph_id":"vector_graph",
         "graph_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "task":{"task_key":"FISHING_TRAINING"},
         "profile":{"profile_id":"fishing-default","profile_version":"v1"},
         "nodes":[
           {"id":"start","type":"START","label":"Start","configuration":%s},
           {"id":"done","type":"SUCCESS","label":"Done","configuration":{}}],
         "edges":[{"id":"e_done","from":"start","to":"done","priority":0,"source_order":0}],
         "authority":{"can_write_source":false,"can_approve_patch":false,"can_publish_graph":false,"can_activate_model":false,"can_dispatch_input":false},
         "control_allowed":false}
        """
            .formatted(configuration));
  }

  /** Serializes the label through Jackson so control characters survive the round trip. */
  private JsonNode graphWithEscapedLabel(String label) throws Exception {
    return mapper.readTree(
        """
        {"schema":"behavior-graph-definition/v2","schema_version":"behavior-graph-definition/v2",
         "graph_id":"vector_graph",
         "graph_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "task":{"task_key":"FISHING_TRAINING"},
         "profile":{"profile_id":"fishing-default","profile_version":"v1"},
         "nodes":[
           {"id":"start","type":"START","label":%s,"configuration":{}},
           {"id":"done","type":"SUCCESS","label":"Done","configuration":{}}],
         "edges":[{"id":"e_done","from":"start","to":"done","priority":0,"source_order":0}],
         "authority":{"can_write_source":false,"can_approve_patch":false,"can_publish_graph":false,"can_activate_model":false,"can_dispatch_input":false},
         "control_allowed":false}
        """
            .formatted(mapper.writeValueAsString(label)));
  }
}
