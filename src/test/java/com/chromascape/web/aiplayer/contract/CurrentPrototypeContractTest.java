package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.prototype.*;
import com.chromascape.web.aiplayer.routine.service.RoutineCaptureIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class CurrentPrototypeContractTest {
  @TempDir Path root;
  private final ObjectMapper mapper = new ObjectMapper();
  private final RoutineCaptureIngestionService captures =
      mock(RoutineCaptureIngestionService.class);
  private final FishingPreflightService preflight = mock(FishingPreflightService.class);
  private final FishingRecordingLoader loader = mock(FishingRecordingLoader.class);

  private FishingDatasetBuilderService store() {
    var store = new FishingDatasetBuilderService(preflight, loader, mapper, captures);
    ReflectionTestUtils.setField(store, "outputDir", root.toString());
    return store;
  }

  private CurrentSliceIdentity identity(String build) throws Exception {
    CurrentSliceIdentity before;
    try (var input = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
      before = mapper.readValue(input, CurrentSliceIdentity.class);
    }
    var c = before.client();
    return new CurrentSliceIdentity(
        before.schema(),
        before.task(),
        null,
        new CurrentSliceIdentity.Client(
            c.clientId(),
            c.clientKind(),
            build,
            c.artifactDigest(),
            c.adapterContract(),
            c.domain()),
        new CurrentSliceIdentity.Capture("capture", "recording", "sha256:" + "a".repeat(64)),
        null,
        null,
        null,
        null,
        false);
  }

  private void writeModel(CurrentSliceIdentity identity) throws Exception {
    var model =
        new FishingModel(
            1,
            "fishing_current",
            FishingDemonstrationPolicy.KIND,
            "now",
            "dataset",
            "recording",
            "debug_current",
            5,
            List.of(),
            List.of(),
            Map.of(),
            0,
            identity);
    var file = root.resolve("fishing/models/fishing_current.json");
    Files.createDirectories(file.getParent());
    mapper.writeValue(file.toFile(), model);
  }

  @Test
  void persistedModelsRequireUnchangedCurrentRecordingIdentity() throws Exception {
    var current = identity("current");
    when(captures.recordingIdentity("recording", "model", "debug_current")).thenReturn(current);
    var store = store();
    writeModel(null);
    assertThatThrownBy(() -> store.loadModel("fishing_current"))
        .hasMessage("model recording identity is required");
    verifyNoInteractions(captures);
    writeModel(identity("stale"));
    assertThatThrownBy(() -> store.loadModel("fishing_current"))
        .isInstanceOf(CurrentSliceContractException.class);
    writeModel(current);
    assertThat(store.loadModel("fishing_current").identity()).isEqualTo(current);
  }

  @Test
  void refusedHeldOutRecordingCannotWriteReplayOrReadModel() {
    doThrow(
            new CurrentSliceContractException(
                CurrentSliceFailureCode.CLIENT_ARTIFACT_MISMATCH, "CLIENT_ARTIFACT_MISMATCH"))
        .when(captures)
        .recordingIdentity("heldout", "replay", null);
    var replay = new FishingReplayService(preflight, loader, store(), mapper);
    ReflectionTestUtils.setField(replay, "aiPlayerEnabled", true);
    ReflectionTestUtils.setField(replay, "outputDir", root.resolve("replay").toString());
    assertThatThrownBy(() -> replay.run("fishing_current", "heldout"))
        .hasMessage("CLIENT_ARTIFACT_MISMATCH");
    assertThat(root.resolve("replay")).doesNotExist();
    verifyNoInteractions(preflight, loader);
  }

  @Test
  void refusedLiveCaptureCannotOpenOrPublishPrototypeShadow() {
    doThrow(
            new CurrentSliceContractException(
                CurrentSliceFailureCode.CLIENT_ARTIFACT_UNVERIFIED, "CLIENT_ARTIFACT_UNVERIFIED"))
        .when(captures)
        .debugSessionIdentity("debug_current", "shadow");
    var active = mock(FishingActiveModelService.class);
    var writer = mock(FishingShadowDecisionWriter.class);
    var shadow = new FishingShadowDecisionService(active, mapper, writer, store());
    ReflectionTestUtils.setField(shadow, "enabled", true);
    ReflectionTestUtils.setField(shadow, "shadowMode", true);
    assertThatThrownBy(() -> shadow.openSession("debug_current", "shadow_current"))
        .hasMessage("CLIENT_ARTIFACT_UNVERIFIED");
    assertThat((Map<?, ?>) ReflectionTestUtils.getField(shadow, "sessions")).isEmpty();
    verifyNoInteractions(writer);
  }

  @Test
  void prototypeContractErrorsRetainActionableHttpCode() {
    var controller = new AiPlayerPrototypeController(preflight, store(), null, null, null);
    var response =
        controller.handleContract(
            new CurrentSliceContractException(
                CurrentSliceFailureCode.CLIENT_ARTIFACT_UNVERIFIED, "private detail"));
    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(((Map<?, ?>) response.getBody()).get("code"))
        .isEqualTo("CLIENT_ARTIFACT_UNVERIFIED");
  }
}
