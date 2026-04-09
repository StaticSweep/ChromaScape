package com.chromascape.utils.core.screen.window;

import com.chromascape.utils.core.input.remoteinput.RemoteInput;
import com.sun.jna.Pointer;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Utility class for capturing screen regions and retrieving window bounds. Screen capture utilities
 * are intended to be used with colour contour extraction and template matching.
 */
public class ScreenManager {

  private static RemoteInput remoteInput;

  private static Pointer screenBuffer = null;

  /**
   * Captures a {@link Rectangle} region on the client screen, intended to be used when
   * screenshotting zones for template matching and or colour extraction.
   *
   * @param zone The rectangle area in client relative screen co-ordinates
   * @return A {@link BufferedImage} of the captured area
   */
  public static BufferedImage captureZone(Rectangle zone) {
    BufferedImage screen = captureWindow();
    if (screen == null) {
      throw new RuntimeException("Screen could not be captured");
    }
    return screen.getSubimage(zone.x, zone.y, zone.width, zone.height);
  }

  /**
   * Grabs the latest rendered frame of the target application, regardless of if the client is
   * maximised, minimised, partially or fully covered. This is to be used with template matching and
   * {@link com.chromascape.utils.core.screen.topology.ChromaObj} detection.
   *
   * @return A {@link BufferedImage} of the client's screen
   */
  public static synchronized BufferedImage captureWindow() {
    Rectangle dims = remoteInput.getTargetDimensions();
    int width = dims.width;
    int height = dims.height;

    if (width <= 0 || height <= 0) {
      return null;
    }

    if (screenBuffer == null) {
      screenBuffer = remoteInput.getImageBuffer();
    }

    int bufferSize = width * height * 4;
    byte[] data = screenBuffer.getByteArray(0, bufferSize);

    return createBufferedImage(data, width, height);
  }

  /**
   * Internal helper to create a buffered image from a C++ style byte array of pixels in BGRA
   * format.
   *
   * @param pixels The byte array of pixel data in BGRA format
   * @param width The width of the client in pixels
   * @param height The height of the client in pixels
   * @return A {@link BufferedImage} representing the image
   */
  private static BufferedImage createBufferedImage(byte[] pixels, int width, int height) {
    DataBufferByte buffer = new DataBufferByte(pixels, pixels.length);
    WritableRaster raster =
        Raster.createInterleavedRaster(
            buffer, width, height, width * 4, 4, new int[] {2, 1, 0, 3}, null);

    ColorModel cm =
        new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            new int[] {8, 8, 8, 8},
            true,
            false,
            Transparency.TRANSLUCENT,
            DataBuffer.TYPE_BYTE);

    return new BufferedImage(cm, raster, false, null);
  }

  /**
   * Gets the bounds of the (game view) RuneLite AWT Canvas object.
   *
   * @return A {@link Rectangle} representing the size of RuneLite's client area, excluding possible
   *     window borders, title or scrollbars.
   */
  public static Rectangle getWindowBounds() {
    return remoteInput.getTargetDimensions();
  }

  /**
   * Sets the RemoteInput object in the ScreenManager, allowing it to access to the client's screen
   * buffer.
   *
   * @param remoteInput The {@link RemoteInput} object
   */
  public static void setRemoteInput(RemoteInput remoteInput) {
    ScreenManager.remoteInput = remoteInput;
  }
}
