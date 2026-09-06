package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BehaviorGraphSourceProvenanceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private static final String HASH = "sha256:" + "a".repeat(64);

  private JsonNode generate() throws Exception {
    var captures = mock(RoutineCaptureIngestionService.class);
    var repository = mock(BehaviorGraphDefinitionRepository.class);
    var source = new CurrentSliceIdentity.Source("srcsnap_" + "a".repeat(24), HASH,
        "micro6", "b".repeat(40), "c".repeat(40), "b".repeat(40), HASH, true);
    var identity = new CurrentSliceIdentity("current-slice-identity/v1",
        new CurrentSliceIdentity.Task("FISHING_TRAINING", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run("run-1", "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client-1", "MICRO6_MANAGED", "build-1", HASH,
            "micro6-builder/v2", "MICRO6"),
        new CurrentSliceIdentity.Capture("capture-1", "recording-1", HASH),
        source, null, null, null, false);
    when(captures.recording("recording-1")).thenReturn(Map.of(
        "status", "COMPLETED", "recording_hash", HASH, "identity", identity));
    when(captures.actions("recording-1")).thenReturn(List.of(mapper.readTree(
        "{\"tick\":139,\"option\":\"Small Net\",\"target\":\"Fishing spot\"}")));
    when(repository.findByGraphId(any())).thenReturn(Optional.empty());
    when(repository.save(any(BehaviorGraphDefinition.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var service = new BehaviorGraphService(mapper, captures, mock(RoutineCurationService.class),
        repository, mock(CurrentSliceRunService.class), new GraphSchemaValidator());
    return mapper.valueToTree(service.generate("recording-1"));
  }

  @Test
  void generatedAndPersistedGraphRetainsFullVerifiedSourceIdentity() throws Exception {
    JsonNode response = generate();
    JsonNode source = response.at("/definition/identity/source");
    assertThat(source.size()).isEqualTo(8);
    assertThat(source.path("repository_id").asText()).isEqualTo("micro6");
    assertThat(source.path("signature_verified").asBoolean()).isTrue();
    assertThat(response.path("control_allowed").asBoolean()).isFalse();
    new GraphSchemaValidator().validateResponse(response);
  }

  @Test
  void graphSourceSchemaMatchesCanonicalIdentitySourceSchema() throws Exception {
    try (var graph = getClass().getResourceAsStream("/contracts/behavior-graph-definition-v1.schema.json");
         var identity = getClass().getResourceAsStream("/contracts/current-slice-identity-v1.schema.json")) {
      assertThat(mapper.readTree(graph).at("/$defs/source"))
          .isEqualTo(mapper.readTree(identity).at("/$defs/source"));
    }
  }

  @Test
  void stillRejectsUnknownFieldsAndMalformedProvenance() throws Exception {
    JsonNode original = generate().path("definition");
    var validator = new GraphSchemaValidator();
    for (String field : List.of("unknown_authority", "commit_id", "tree_id", "artifact_digest", "signature_verified")) {
      ObjectNode changed = original.deepCopy();
      ((ObjectNode) changed.at("/identity/source")).put(field, "invalid");
      assertThatThrownBy(() -> validator.validateDefinition(changed))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GRAPH_SCHEMA_INVALID");
    }
  }
}
