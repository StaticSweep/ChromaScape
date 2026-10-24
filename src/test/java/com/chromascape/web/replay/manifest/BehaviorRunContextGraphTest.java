package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.catalog.CatalogStore;
import com.chromascape.web.aiplayer.routine.service.BehaviorGraphV2Canonicalizer;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.recording.provenance.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BehaviorRunContextGraphTest {
  private final ObjectMapper json = new ObjectMapper();
  private final CatalogStore profiles = mock(CatalogStore.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final BehaviorRunContextService service =
      new BehaviorRunContextService(profiles, null, null, jdbc, json);
  private final RecordingProvenance provenance =
      RecordingProvenance.capture("FISHING_TRAINING", json.createObjectNode());
  private static final String SQL =
      "SELECT graph_hash,definition_schema,definition_json FROM behavior_graph_publications WHERE"
          + " graph_id=? AND graph_version=?";

  private ObjectNode reference() {
    var profile =
        json.createObjectNode()
            .put("profile_id", "profile")
            .put("profile_version", 1)
            .put("task_key", "FISHING_TRAINING")
            .put("settings_hash", provenance.settingsHash());
    profile.set("settings_json", json.createObjectNode());
    when(profiles.profile("profile", 1)).thenReturn(profile);
    var ref =
        json.createObjectNode()
            .put("runId", "00000000-0000-4000-8000-000000000002")
            .put("allocationId", "allocation")
            .put("executorId", "executor")
            .put("clientInstanceId", "client")
            .put("behaviorProfileId", "profile")
            .put("behaviorProfileVersion", 1)
            .put("semanticContractDigest", BehaviorSemanticContract.digest());
    ref.putObject("privacy").put("policyVersion", "authored-test-only").putArray("redactedFields");
    return ref;
  }

  private ObjectNode graph() {
    var graph =
        json.createObjectNode()
            .put("schema", "behavior-graph-definition/v2")
            .put("schema_version", "behavior-graph-definition/v2")
            .put("graph_id", "fixture_graph")
            .put("control_allowed", false);
    graph.putObject("task").put("task_key", "FISHING_TRAINING");
    graph.putObject("profile").put("profile_id", "profile").put("profile_version", "1");
    var nodes = graph.putArray("nodes");
    nodes
        .addObject()
        .put("id", "start")
        .put("type", "START")
        .put("label", "Start")
        .putObject("configuration");
    nodes
        .addObject()
        .put("id", "success")
        .put("type", "SUCCESS")
        .put("label", "Success")
        .putObject("configuration");
    graph
        .putArray("edges")
        .addObject()
        .put("id", "finish")
        .put("from", "start")
        .put("to", "success")
        .put("priority", 0)
        .put("source_order", 0);
    var authority = graph.putObject("authority");
    for (String field :
        List.of(
            "can_write_source",
            "can_approve_patch",
            "can_publish_graph",
            "can_activate_model",
            "can_dispatch_input")) authority.put(field, false);
    graph.put("graph_hash", new BehaviorGraphV2Canonicalizer().graphHash(graph));
    return graph;
  }

  private void select(ObjectNode ref, ObjectNode graph) {
    ref.put("graphId", "fixture_graph")
        .put("graphVersion", 2)
        .put("graphRevision", graph.path("graph_hash").asText());
    when(jdbc.queryForList(SQL, "fixture_graph", 2L))
        .thenReturn(
            List.of(
                Map.of(
                    "graph_hash",
                    graph.path("graph_hash").asText(),
                    "definition_schema",
                    "behavior-graph-definition/v2",
                    "definition_json",
                    graph.toString())));
  }

  @Test
  void freezesExactPublicationAndItsTaskProfileHashAndSchemaBeforeCapture() {
    var ref = reference();
    var graph = graph();
    select(ref, graph);
    var frozen = service.snapshot(ref, provenance, "authored-account");
    assertThat(frozen.path("graphRevision")).isEqualTo(graph.path("graph_hash"));
    assertThat(frozen.path("graphVersion").asInt()).isEqualTo(2);
    assertThat(frozen.path("graphSnapshot")).isEqualTo(graph);
    verify(jdbc).queryForList(SQL, "fixture_graph", 2L);
    var bound = service.bind(frozen, "recording", "session");
    assertThat(ScenarioCompilationEvidence.missingIdentityFields(bound, "recording", "session"))
        .isEmpty();
    assertThat(ScenarioCompilationEvidence.missingIdentityFields(bound, "another", "session"))
        .containsExactly("recordingId");
    assertThat(ScenarioCompilationEvidence.missingIdentityFields(bound, "recording", "another"))
        .containsExactly("debugSessionId");
    String hash = bound.path("contextHash").asText();
    bound.remove("contextHash");
    assertThat(Micro6CanonicalHash.sha256WithPrefix(bound)).isEqualTo(hash);
    graph.put("graph_id", "later_local_mutation");
    assertThat(frozen.path("graphSnapshot").path("graph_id").asText()).isEqualTo("fixture_graph");
  }

  @Test
  void absentHistoricalGraphIsNotInventedOrQueried() {
    var frozen = service.snapshot(reference(), provenance, "authored-account");
    assertThat(frozen.has("graphRevision")).isFalse();
    verifyNoInteractions(jdbc);
    assertThat(ScenarioCompilationEvidence.missingIdentityFields(frozen)).contains("graphRevision");
  }

  @Test
  void incompleteReferenceWrongHashWrongProfileAndMissingPublicationFailBeforeCapture() {
    for (int variant = 0; variant < 5; variant++) {
      var ref = reference();
      var graph = graph();
      if (variant == 2) {
        ((ObjectNode) graph.path("profile")).put("profile_id", "different");
        graph.put("graph_hash", new BehaviorGraphV2Canonicalizer().graphHash(graph));
      }
      select(ref, graph);
      if (variant == 0) ref.remove("graphVersion");
      if (variant == 1) ref.put("graphRevision", "sha256:" + "a".repeat(64));
      if (variant == 3) when(jdbc.queryForList(SQL, "fixture_graph", 2L)).thenReturn(List.of());
      if (variant == 4) {
        graph.put("graph_id", "tampered_without_rehash");
        select(ref, graph);
      }
      assertThrows(
          ProvenanceException.class, () -> service.snapshot(ref, provenance, "authored-account"));
    }
  }
}
