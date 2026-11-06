package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic synthetic golden case for the visual producer. The fixture contains a static span,
 * a low-motion pause, a hard cut and a static tail; assertions check source ordinals, honest cursor
 * unknowns and repeatability rather than tuning against held-out labels.
 */
class MouseVideoVisualAnalyzerTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoVisualAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    properties = new MouseVideoProperties();
    properties.setPauseMinMillis(250L);
    properties.setPauseMaxDelta(0.01d);
    properties.setCutMinDelta(0.2d);
    properties.setActivityMinDelta(0.05d);
    properties.setMaxVisualFrames(200);
    analyzer = new MouseVideoVisualAnalyzer(properties);
  }

  @Test
  void stopsMidPassWhenTheStopConditionTurnsTrue() throws IOException {
    // The passes run in the JVM, so a cancellation or lost lease can only stop them through this
    // check; before it, a fenced job kept analysing to the end of the source.
    List<MouseVideoFrameIndexService.Frame> index = new ArrayList<>();
    Path frames = root.resolve("frames");
    Files.createDirectories(frames);
    for (int position = 0; position < 30; position++) {
      writeFrame(
          frames.resolve(String.format("%06d.png", position + 1)),
          position % 2 == 0 ? new Color(20, 20, 20) : new Color(200, 200, 200));
      index.add(
          new MouseVideoFrameIndexService.Frame(
              Integer.toString(position),
              String.format(java.util.Locale.ROOT, "%.2f", position * 0.1d),
              "0.10",
              position == 0));
    }
    AtomicInteger checks = new AtomicInteger();

    assertThatThrownBy(
            () ->
                analyzer.analyze(
                    index, frames, 1, 320, 180, 3.0d, null, () -> checks.incrementAndGet() > 40))
        .isInstanceOf(InterruptedException.class);
    // One full pass is 30 checks; it stopped on the 41st, inside the second pass, not at the end.
    assertThat(checks.get()).isEqualTo(41);
  }

  @Test
  void derivesPauseAndCutAtExactSourceOrdinalsWithHonestCursorUnknown() throws IOException {
    List<MouseVideoFrameIndexService.Frame> index = new ArrayList<>();
    Path frames = root.resolve("frames");
    Files.createDirectories(frames);
    for (int position = 0; position < 30; position++) {
      Color colour;
      if (position == 18) {
        colour = Color.WHITE;
      } else if (position >= 10 && position < 18) {
        colour = new Color(60, 60, 60);
      } else if (position < 10) {
        colour = position % 2 == 0 ? new Color(20, 20, 20) : new Color(200, 200, 200);
      } else {
        colour = new Color(60, 60, 60);
      }
      writeFrame(frames.resolve(String.format("%06d.png", position + 1)), colour);
      index.add(
          new MouseVideoFrameIndexService.Frame(
              Integer.toString(position),
              String.format(java.util.Locale.ROOT, "%.2f", position * 0.1d),
              "0.10",
              position == 0));
    }

    MouseVideoVisualAnalyzer.Result first =
        analyzer.analyze(index, frames, 1, 320, 180, 3.0d, null);
    MouseVideoVisualAnalyzer.Result second =
        analyzer.analyze(index, frames, 1, 320, 180, 3.0d, null);

    assertThat(first.observations()).isEqualTo(second.observations());
    assertThat(first.quality()).isEqualTo(second.quality());
    assertThat(
            first.observations().stream()
                .filter(observation -> "PAUSE".equals(observation.get("kind")))
                .findFirst())
        .isPresent()
        .get()
        .satisfies(
            observation ->
                assertThat(((Map<?, ?>) observation.get("evidence")).get("startFrameOrdinal"))
                    .isEqualTo(10L));
    assertThat(
            first.observations().stream()
                .filter(observation -> "SCENE_CUT".equals(observation.get("kind")))
                .map(observation -> observation.get("frameOrdinal"))
                .toList())
        .contains(18L);
    assertThat(
            first.observations().stream()
                .filter(observation -> "CURSOR_VISIBILITY".equals(observation.get("kind")))
                .map(observation -> observation.get("visibility")))
        .contains("UNSUPPORTED");
    @SuppressWarnings("unchecked")
    List<String> unknowns = (List<String>) first.quality().get("unknowns");
    assertThat(unknowns)
        .anyMatch(unknown -> unknown.contains("cursor"))
        .anyMatch(unknown -> unknown.contains("native-ticks"))
        .anyMatch(unknown -> unknown.contains("physical-input"));
    assertThat(first.cursorTemplateUsed()).isFalse();
  }

  @Test
  void samplingStepAndFrameCapBoundDecodedFrames() throws IOException {
    List<MouseVideoFrameIndexService.Frame> index = new ArrayList<>();
    Path frames = root.resolve("bounded");
    Files.createDirectories(frames);
    for (int position = 0; position < 1200; position++) {
      index.add(
          new MouseVideoFrameIndexService.Frame(
              Integer.toString(position),
              String.format(java.util.Locale.ROOT, "%.3f", position / 30.0d),
              "0.033",
              position == 0));
    }
    for (int position = 0; position < 60; position++) {
      writeFrame(frames.resolve(String.format("%06d.png", position + 1)), Color.DARK_GRAY);
    }
    MouseVideoVisualAnalyzer.Result result =
        analyzer.analyze(index, frames, 20, 320, 180, 40.0d, null);
    assertThat(result.sampledFrames()).isEqualTo(60);
    assertThat(result.step()).isEqualTo(20);
  }

  private static void writeFrame(Path path, Color colour) throws IOException {
    BufferedImage image = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(colour);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.dispose();
    ImageIO.write(image, "png", path.toFile());
  }
}
