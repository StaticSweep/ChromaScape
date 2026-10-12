package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeasuredVideoIndexTest {
  @Test
  void variableFrameIntervalsUsePhysicalIndicesAndPreserveEncodeLag() {
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "r",
            "hash",
            true,
            List.of(),
            List.of(
                new MeasuredVideoIndex.PhysicalFrame(0, 0, 1000, 1000, 400, 0),
                new MeasuredVideoIndex.PhysicalFrame(1, 60000, 1060, 1060, 340, 0),
                new MeasuredVideoIndex.PhysicalFrame(2, 180000, 1180, 1180, 220, 0)));
    var m = MeasuredVideoIndex.map(index, 1055, 7);
    assertThat(m.status()).isEqualTo("MEASURED");
    assertThat(m.frameIndex()).isEqualTo(1);
    assertThat(m.mediaTimeMillis()).isEqualTo(60);
    assertThat(m.residualMillis()).isEqualTo(-5);
    assertThat(m.encodeLagMillis()).isEqualTo(340);
    assertThat(MeasuredVideoIndex.map(index, 999, 7).qualityCodes())
        .contains("TICK_OUTSIDE_VIDEO_RANGE");
    assertThat(MeasuredVideoIndex.map(index, 1300, 7).status()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void finalFrameUsesMeasuredContainerDurationAndStillEnforcesImageAge() {
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "r",
            "hash",
            true,
            List.of(),
            List.of(
                new MeasuredVideoIndex.PhysicalFrame(0, 0, 1000, 1000, 0, 0),
                new MeasuredVideoIndex.PhysicalFrame(1, 100000, 1100, 1100, 0, 0)),
            200000);
    assertThat(MeasuredVideoIndex.map(index, 1110, 7).status()).isEqualTo("MEASURED");
    assertThat(MeasuredVideoIndex.map(index, 1201, 7).qualityCodes())
        .contains("TICK_OUTSIDE_VIDEO_RANGE");
  }

  @Test
  void staleDuplicateMissingUnfinalizedAndBadClockRemainUnavailable() {
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "r",
            "hash",
            true,
            List.of(),
            List.of(
                new MeasuredVideoIndex.PhysicalFrame(0, 0, 1000, 1000, 0, 0),
                new MeasuredVideoIndex.PhysicalFrame(1, 300000, 1000, 1300, 300, 300)));
    assertThat(MeasuredVideoIndex.map(index, 1300, 7).qualityCodes())
        .contains("FRAME_ALIGNMENT_UNCERTAINTY");
    assertThat(MeasuredVideoIndex.map(index, 1000, 51).status()).isEqualTo("UNAVAILABLE");
    assertThat(MeasuredVideoIndex.map(null, 1000, 7).status()).isEqualTo("UNAVAILABLE");
  }

  @Test
  void missingCorruptAndMismatchedMediaIndexCannotBeRead() throws Exception {
    Path video = Files.createTempFile("fake-media", ".mp4");
    Files.writeString(video, "bytes");
    assertThatThrownBy(() -> MeasuredVideoIndex.read(video, "r")).isInstanceOf(Exception.class);
    Files.writeString(MeasuredVideoIndex.sidecar(video), "{corrupt");
    assertThatThrownBy(() -> MeasuredVideoIndex.read(video, "r")).isInstanceOf(Exception.class);
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1", "r", "wrong-hash", true, List.of(), List.of());
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValue(MeasuredVideoIndex.sidecar(video).toFile(), index);
    assertThatThrownBy(() -> MeasuredVideoIndex.read(video, "r")).hasMessageContaining("binding");
  }
}
