package com.chromascape.web.recording.capture;

import com.chromascape.utils.core.screen.FrameEvent;
import java.awt.image.BufferedImage;

/**
 * Boundary abstraction over single-shot window frame capture.
 *
 * <p>Implemented by {@link ScreenManagerWindowFrameCapture} in production; unit tests provide
 * deterministic sequences of failures and frames without invoking Win32 APIs. Retry and readiness
 * policy belongs to {@code RecordingService}, not implementations of this interface.
 */
public interface WindowFrameCapture {
  /**
   * Captures the current game window frame.
   *
   * @return the captured frame, or {@code null} if no frame could be captured
   * @throws RuntimeException when the window is unavailable or its dimensions are invalid
   */
  BufferedImage capture();

  /**
   * Captures a frame event including target binding when the provider can supply it.
   *
   * <p>The default keeps existing test and custom implementations source-compatible.
   */
  default FrameEvent captureEvent() {
    BufferedImage image = capture();
    return image == null
        ? null
        : new FrameEvent(image, System.nanoTime(), image.getWidth(), image.getHeight());
  }
}
