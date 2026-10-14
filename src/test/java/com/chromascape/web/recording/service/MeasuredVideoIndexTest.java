package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeasuredVideoIndexTest {
  @Test
  void selectsCaptureTimeInsteadOfStalePresentationAndPrefersOriginalCopy() {
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "r",
            "hash",
            true,
            List.of(),
            List.of(
                new MeasuredVideoIndex.PhysicalFrame(0, 0, 1000, 1000, 0, 0),
                new MeasuredVideoIndex.PhysicalFrame(1, 100000, 1000, 1100, 0, 100),
                new MeasuredVideoIndex.PhysicalFrame(2, 150000, 1145, 1150, 0, 5),
                new MeasuredVideoIndex.PhysicalFrame(3, 200000, 1145, 1200, 0, 55)),
            250000);
    var mapping = MeasuredVideoIndex.map(index, 1110, 10);
    assertThat(mapping.status()).isEqualTo("MEASURED");
    assertThat(mapping.frameIndex()).isEqualTo(2);
    assertThat(MeasuredVideoIndex.map(index, 1145, 10).frameIndex()).isEqualTo(2);
  }

  @Test
  void captureCallbacksMayBeOutOfOrderAndTiesAreDeterministic() {
    var index =
        new MeasuredVideoIndex.Index(
            "measured-video-index/v1",
            "r",
            "hash",
            true,
            List.of(),
            List.of(
                new MeasuredVideoIndex.PhysicalFrame(0, 0, 1000, 1000, 0, 0),
                new MeasuredVideoIndex.PhysicalFrame(1, 100000, 1100, 1100, 0, 0),
                new MeasuredVideoIndex.PhysicalFrame(2, 150000, 1050, 1150, 0, 100)));
    assertThat(MeasuredVideoIndex.map(index, 1055, 7).frameIndex()).isEqualTo(2);
    assertThat(MeasuredVideoIndex.map(index, 1075, 7).frameIndex()).isEqualTo(2);
    assertThat(MeasuredVideoIndex.map(index, Double.NaN, 7).qualityCodes())
        .contains("CLOCK_UNCERTAINTY_EXCEEDED");
  }

  @Test
  void sanitizedActualTimingsRecoverSelectorFailuresWithoutRelaxingRealImageGaps()
      throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    try (var in =
        getClass().getResourceAsStream("/replay/contracts/sc1976-real-frame-timings.json")) {
      var root = json.readTree(in);
      for (var capture : root.path("captures")) {
        var frames = new java.util.ArrayList<MeasuredVideoIndex.PhysicalFrame>();
        for (var frame : capture.path("frames")) {
          frames.add(
              new MeasuredVideoIndex.PhysicalFrame(
                  frame.get(0).asInt(),
                  frame.get(1).asLong(),
                  frame.get(2).asDouble(),
                  frame.get(3).asDouble(),
                  frame.get(4).asDouble(),
                  frame.get(5).asDouble()));
        }
        var index =
            new MeasuredVideoIndex.Index(
                "measured-video-index/v1",
                "timing-only",
                "unused",
                true,
                List.of(),
                frames,
                capture.path("mediaDurationMicros").asLong());
        for (var tick : capture.path("ticks")) {
          var mapping =
              MeasuredVideoIndex.map(
                  index,
                  tick.path("serverMillis").asDouble(),
                  tick.path("uncertaintyMillis").asDouble());
          assertThat(mapping.status())
              .as("%s tick %s", capture.path("source"), tick.path("tick"))
              .isEqualTo(tick.path("expectedStatus").asText());
          if ("MEASURED".equals(mapping.status())) {
            assertThat(Math.abs(mapping.residualMillis()) + mapping.uncertaintyMillis())
                .isLessThanOrEqualTo(100);
          }
        }
        // Derived lookup is not written to the digest-bound timing sidecar.
        assertThat(json.readTree(json.writeValueAsString(index)).has("captureFrames")).isFalse();
      }
    }
  }

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
