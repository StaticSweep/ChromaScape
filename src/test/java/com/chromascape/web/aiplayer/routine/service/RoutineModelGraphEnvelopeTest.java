package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.entity.RoutineModel;
import com.chromascape.web.aiplayer.routine.repository.RoutineModelRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineReplayTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutineModelGraphEnvelopeTest {
  private static final String HASH = "sha256:" + "a".repeat(64);

  @Test
  void buildsModelFromNestedGraphDefinitionAndStoresOnlyTheDefinition() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    BehaviorGraphService graphs = mock(BehaviorGraphService.class);
    RoutineModelRepository models = mock(RoutineModelRepository.class);
    CurrentSliceRunService runs = mock(CurrentSliceRunService.class);
    CurrentSliceIdentity requested = identity(null);
    CurrentSliceIdentity graphIdentity =
        identity(new CurrentSliceIdentity.Graph("graph_" + "b".repeat(24), HASH));
    Map<String, Object> definition =
        Map.of(
            "identity", graphIdentity,
            "nodes", List.of(Map.of("type", "ACTION")),
            "edges", List.of());
    when(graphs.get("graph_" + "b".repeat(24)))
        .thenReturn(Map.of("state", "PUBLISHED", "definition", definition));
    when(models.findByModelId(any())).thenReturn(Optional.empty());
    when(models.save(any(RoutineModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    RoutineReplayShadowService service =
        new RoutineReplayShadowService(
            mapper,
            mock(RoutineCaptureIngestionService.class),
            graphs,
            models,
            mock(RoutineReplayTraceRepository.class),
            runs);

    Map<String, Object> result = service.buildModel("graph_" + "b".repeat(24), requested);

    assertThat(result.get("model_id")).asString().startsWith("model_");
    org.mockito.ArgumentCaptor<RoutineModel> captor =
        org.mockito.ArgumentCaptor.forClass(RoutineModel.class);
    org.mockito.Mockito.verify(models).save(captor.capture());
    RoutineModel saved = captor.getValue();
    Map<?, ?> stored = mapper.readValue(saved.getDefinitionJson(), Map.class);
    Map<?, ?> storedGraph = (Map<?, ?>) stored.get("graph");
    assertThat(storedGraph).containsKeys("identity", "nodes", "edges");
    assertThat(storedGraph).doesNotContainKey("definition");
  }

  private CurrentSliceIdentity identity(CurrentSliceIdentity.Graph graph) {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("FISHING_TRAINING", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run("run_1", null, null, null),
        new CurrentSliceIdentity.Client(
            "runelite", "RUNELITE_CAPTURE", "build", HASH, "runelite-capture/v1", "RUNELITE"),
        null,
        null,
        graph,
        null,
        null,
        false);
  }
}
