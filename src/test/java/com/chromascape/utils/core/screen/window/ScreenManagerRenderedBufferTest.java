package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.FrameFreshness;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScreenManagerRenderedBufferTest {
  private static final ProcessIdentity IDENTITY =
      new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");
  private String originalOsName;
  private StubSource source;

  @BeforeEach
  void setUp() {
    originalOsName = System.getProperty("os.name");
    System.setProperty("os.name", "Linux");
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        image.setRGB(x, y, 0xFF000000 | (x << 16) | (y << 8));
      }
    }
    source = new StubSource(image);
    TargetFrameRuntime.install(source);
    WindowSelectionService selection = new WindowSelectionService();
    selection.setSelectedWindow(
        new WindowInfo("1", null, null, null, 42, true, new Rectangle(10, 20, 4, 3), IDENTITY));
    ScreenManager.setWindowSelectionService(selection);
  }

  @AfterEach
  void tearDown() {
    TargetFrameRuntime.uninstall(source);
    ScreenManager.setWindowSelectionService(null);
    if (originalOsName == null) {
      System.clearProperty("os.name");
    } else {
      System.setProperty("os.name", originalOsName);
    }
  }

  @Test
  void preservesScreenSpaceCoordinatesAndClampsPartialCrop() {
    assertEquals(new Rectangle(10, 20, 4, 3), ScreenManager.getWindowBounds());
    assertEquals(new Point(2, 1), ScreenManager.toClientCoords(new Point(12, 21)));

    BufferedImage cropped = ScreenManager.captureZone(new Rectangle(8, 19, 4, 3));

    assertEquals(2, cropped.getWidth());
    assertEquals(2, cropped.getHeight());
    assertEquals(source.image.getRGB(0, 0), cropped.getRGB(0, 0));
    assertNotSame(source.image, cropped);
  }

  @Test
  void rejectsEmptyAndDisjointCrops() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ScreenManager.captureZone(new Rectangle(10, 20, 0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ScreenManager.captureZone(new Rectangle(100, 100, 1, 1)));
  }

  @Test
  void linuxCaptureDoesNotNeedWin32LibraryPath() {
    String previous = System.getProperty("jna.library.path");
    System.setProperty("jna.library.path", "missing-win32-libraries");
    try {
      assertEquals(4, ScreenManager.captureWindow().getWidth());
    } finally {
      if (previous == null) {
        System.clearProperty("jna.library.path");
      } else {
        System.setProperty("jna.library.path", previous);
      }
    }
  }

  private static final class StubSource implements TargetFrameSource {
    private final BufferedImage image;

    private StubSource(BufferedImage image) {
      this.image = image;
    }

    @Override
    public CapturedFrame captureFrame() {
      Instant now = Instant.parse("2026-09-18T10:00:00Z");
      return new CapturedFrame(image, FrameFreshness.NEW, now, now, IDENTITY, 1);
    }

    @Override
    public ProcessIdentity identity() {
      return IDENTITY;
    }

    @Override
    public long selectionGeneration() {
      return 1;
    }

    @Override
    public Rectangle targetBounds() {
      return new Rectangle(0, 0, image.getWidth(), image.getHeight());
    }
  }
}
