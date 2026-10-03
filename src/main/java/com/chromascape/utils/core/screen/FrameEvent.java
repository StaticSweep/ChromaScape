package com.chromascape.utils.core.screen;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.FrameFreshness;
import java.awt.image.BufferedImage;
import java.time.Instant;

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
  private final FrameSourceBinding sourceBinding;
  private final FrameFreshness freshness;
  private final Instant observedAt;
  private final Instant contentChangedAt;

  /**
   * Creates a new frame event.
   *
   * @param frame The captured frame image (treat as immutable/read-only)
   * @param captureTimeNanos Timestamp when the frame was captured (from System.nanoTime())
   * @param sourceWidth Width of the source window
   * @param sourceHeight Height of the source window
   */
  public FrameEvent(BufferedImage frame, long captureTimeNanos, int sourceWidth, int sourceHeight) {
    this(frame, captureTimeNanos, sourceWidth, sourceHeight, null, null, null, null);
  }

  /** Creates a frame event with an exact source binding and rendered-buffer freshness metadata. */
  public FrameEvent(
      BufferedImage frame,
      long captureTimeNanos,
      int sourceWidth,
      int sourceHeight,
      FrameSourceBinding sourceBinding,
      FrameFreshness freshness,
      Instant observedAt,
      Instant contentChangedAt) {
    this.frame = frame;
    this.captureTimeNanos = captureTimeNanos;
    this.sourceWidth = sourceWidth;
    this.sourceHeight = sourceHeight;
    this.sourceBinding = sourceBinding;
    this.freshness = freshness;
    this.observedAt = observedAt;
    this.contentChangedAt = contentChangedAt;
  }

  /** Converts a detached rendered-buffer capture without discarding its source metadata. */
  public static FrameEvent from(CapturedFrame captured, long captureTimeNanos) {
    BufferedImage image = captured.image();
    return new FrameEvent(
        image,
        captureTimeNanos,
        image.getWidth(),
        image.getHeight(),
        FrameSourceBinding.from(captured.identity(), captured.selectionGeneration()),
        captured.freshness(),
        captured.observedAt(),
        captured.contentChangedAt());
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

  /** Exact host/process/generation binding, or {@code null} for a legacy producer. */
  public FrameSourceBinding getSourceBinding() {
    return sourceBinding;
  }

  /** Rendered-buffer freshness, or {@code null} for legacy/Windows capture. */
  public FrameFreshness getFreshness() {
    return freshness;
  }

  /** Wall-clock observation time, or {@code null} for a legacy producer. */
  public Instant getObservedAt() {
    return observedAt;
  }

  /** Last content-change time, or {@code null} for a legacy producer. */
  public Instant getContentChangedAt() {
    return contentChangedAt;
  }
}
