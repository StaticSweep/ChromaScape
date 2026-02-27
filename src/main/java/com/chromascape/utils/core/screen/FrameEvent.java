package com.chromascape.utils.core.screen;

import java.awt.image.BufferedImage;

/**
 * Immutable container for a captured screen frame with metadata.
 *
 * <p>This class wraps a {@link BufferedImage} along with timing information to enable precise video
 * encoding and frame rate control. The frame should be treated as read-only by consumers.
 */
public class FrameEvent {

  private final BufferedImage frame;
  private final long captureTimeNanos;
  private final int sourceWidth;
  private final int sourceHeight;

  /**
   * Creates a new frame event.
   *
   * @param frame The captured frame image (treat as immutable/read-only)
   * @param captureTimeNanos Timestamp when the frame was captured (from System.nanoTime())
   * @param sourceWidth Width of the source window
   * @param sourceHeight Height of the source window
   */
  public FrameEvent(BufferedImage frame, long captureTimeNanos, int sourceWidth, int sourceHeight) {
    this.frame = frame;
    this.captureTimeNanos = captureTimeNanos;
    this.sourceWidth = sourceWidth;
    this.sourceHeight = sourceHeight;
  }

  /**
   * Returns the captured frame.
   *
   * <p>IMPORTANT: Treat this as read-only. If you need to modify the frame, create a copy first.
   *
   * @return The captured frame image
   */
  public BufferedImage getFrame() {
    return frame;
  }

  /**
   * Returns the timestamp when this frame was captured.
   *
   * <p>Uses System.nanoTime() for precise timing, suitable for video encoding.
   *
   * @return Capture timestamp in nanoseconds
   */
  public long getCaptureTimeNanos() {
    return captureTimeNanos;
  }

  /**
   * Returns the width of the source window.
   *
   * @return Width in pixels
   */
  public int getSourceWidth() {
    return sourceWidth;
  }

  /**
   * Returns the height of the source window.
   *
   * @return Height in pixels
   */
  public int getSourceHeight() {
    return sourceHeight;
  }
}
