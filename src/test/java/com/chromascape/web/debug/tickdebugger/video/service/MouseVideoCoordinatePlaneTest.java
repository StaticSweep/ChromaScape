package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoCoordinatePlaneTest {

  @TempDir Path root;

  private final ObjectMapper mapper = new ObjectMapper();
  private final MouseVideoByteStore store = mock(MouseVideoByteStore.class);
  private final MouseVideoCoordinatePlane planes = new MouseVideoCoordinatePlane(store, mapper);

  private MouseVideoAnalysis analysis(String manifestChecksum) {
    MouseVideoAnalysis analysis =
        new MouseVideoAnalysis(
            UUID.randomUUID(), "owner", "sha256:media", "probe/v1", null, "config", "tools");
    analysis.complete("complete", manifestChecksum);
    return analysis;
  }

  @Test
  void probeFactsBecomeTheCanonicalDisplayPlane() throws Exception {
    Path facts = root.resolve("probe-facts.json");
    Files.writeString(facts, "{\"width\":1920,\"height\":1080,\"rotationDegrees\":0}");
    when(store.locate("sha256:facts")).thenReturn(Optional.of(facts));
    var identity = planes.forAnalysis(analysis("sha256:facts")).orElseThrow();
    assertThat(identity.document())
        .containsEntry("contract", "coordinate-plane/v1")
        .containsEntry("codedWidth", 1920)
        .containsEntry("codedHeight", 1080)
        .containsEntry("displayWidth", 1920)
        .containsEntry("displayHeight", 1080)
        .containsEntry("origin", "top-left")
        .containsEntry("pixelCenter", true);
    assertThat(identity.digest()).startsWith("sha256:").hasSize(71);
  }

  @Test
  void quarterTurnSwapsDisplayDimensions() throws Exception {
    Path facts = root.resolve("rotated.json");
    Files.writeString(
        facts,
        "{\"coordinatePlane\":{\"contract\":\"coordinate-plane/v1\",\"codedWidth\":1080,"
            + "\"codedHeight\":1920,\"rotationDegrees\":90}}");
    when(store.locate("sha256:rotated")).thenReturn(Optional.of(facts));
    var identity = planes.forAnalysis(analysis("sha256:rotated")).orElseThrow();
    assertThat(identity.document())
        .containsEntry("displayWidth", 1920)
        .containsEntry("displayHeight", 1080)
        .containsEntry("rotationDegrees", 90);
  }

  @Test
  void missingMalformedOrOutOfRangeEvidenceIsUnknown() throws Exception {
    when(store.locate("sha256:absent")).thenReturn(Optional.empty());
    assertThat(planes.forAnalysis(analysis("sha256:absent"))).isEmpty();

    Path malformed = root.resolve("malformed.json");
    Files.writeString(malformed, "{\"width\":0,\"height\":1080}");
    when(store.locate("sha256:malformed")).thenReturn(Optional.of(malformed));
    assertThat(planes.forAnalysis(analysis("sha256:malformed"))).isEmpty();

    Path rotated = root.resolve("invalid-rotation.json");
    Files.writeString(
        rotated,
        "{\"coordinatePlane\":{\"contract\":\"coordinate-plane/v1\","
            + "\"codedWidth\":1920,\"codedHeight\":1080,\"rotationDegrees\":45}}");
    when(store.locate("sha256:invalid")).thenReturn(Optional.of(rotated));
    assertThat(planes.forAnalysis(analysis("sha256:invalid"))).isEmpty();

    assertThat(planes.forAnalysis(analysis(null))).isEmpty();
  }
}
