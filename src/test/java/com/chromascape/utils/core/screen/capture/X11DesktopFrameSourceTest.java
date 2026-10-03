package com.chromascape.utils.core.screen.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the desktop capture pipeline against a fake ffmpeg script, so no display is needed: the
 * script answers the version probe, prints one ffmpeg-style stream line on stderr, then emits three
 * 64x48 rgb24 frames on stdout and sleeps with the pipe open.
 */
class X11DesktopFrameSourceTest {

  @TempDir Path temporaryDirectory;

  @Test
  void deliversDownscaledFramesAndClosesCleanly() throws Exception {
    Path fakeFfmpeg = fakeFfmpeg();
    X11DesktopFrameSource source = new X11DesktopFrameSource(fakeFfmpeg.toString(), ":9", 320, 2);
    try {
      CapturedFrame frame = awaitFrame(source);
      BufferedImage image = frame.image();
      assertEquals(320, image.getWidth());
      assertEquals(240, image.getHeight());
      assertNotNull(frame.observedAt());
      assertEquals(FrameFreshness.NEW, frame.freshness());

      CapturedFrame second = awaitFrame(source);
      assertEquals(FrameFreshness.CHANGED, second.freshness());
      assertTrue(source.getDeliveredCount() >= 2);
      assertNull(source.getFailure());
      assertEquals(":9", source.getDisplay());
      assertEquals(2, source.getFramesPerSecond());
    } finally {
      source.close();
      source.close();
    }
    assertThrows(IllegalStateException.class, source::captureFrame);
  }

  @Test
  void rejectsMissingBinaryAndBlankDisplay() {
    IllegalStateException missing =
        assertThrows(
            IllegalStateException.class,
            () -> new X11DesktopFrameSource("/nonexistent/ffmpeg-for-tests", ":9", 64, 2));
    assertTrue(missing.getMessage().contains("ffmpeg"));

    assertThrows(
        IllegalArgumentException.class, () -> new X11DesktopFrameSource("ffmpeg", "  ", 64, 2));
  }

  private CapturedFrame awaitFrame(X11DesktopFrameSource source) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    while (true) {
      try {
        return source.captureFrame();
      } catch (IllegalStateException noFrameYet) {
        if (Instant.now().isAfter(deadline)) {
          throw noFrameYet;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while waiting for a frame", interrupted);
        }
      }
    }
  }

  private Path fakeFfmpeg() throws Exception {
    Path script = temporaryDirectory.resolve("ffmpeg");
    // The 64x48 desktop scales to the 320 target width, so each frame is 320x240x3 bytes.
    String body =
        "#!/bin/bash\n"
            + "if [ \"$1\" = \"-version\" ]; then echo \"ffmpeg version test-fake\"; exit 0; fi\n"
            + "echo \"Stream #0:0: Video: wrapped_avframe, rgb0, 64x48 [SAR 1:1 DAR 4:3], 2 fps\""
            + " >&2\n"
            + "head -c 230400 /dev/zero\n"
            + "head -c 230400 /dev/zero\n"
            + "head -c 230400 /dev/zero\n"
            + "sleep 30\n";
    Files.writeString(script, body);
    script.toFile().setExecutable(true);
    return script;
  }
}
