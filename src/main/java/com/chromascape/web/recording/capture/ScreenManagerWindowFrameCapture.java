package com.chromascape.web.recording.capture;

import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.image.BufferedImage;
import org.springframework.stereotype.Component;

/**
 * Production {@link WindowFrameCapture} backed by {@link ScreenManager#captureWindow()}.
 *
 * <p>Retry and readiness policy belongs to {@code RecordingService}; this component only performs a
 * single capture attempt.
 */
@Component
public class ScreenManagerWindowFrameCapture implements WindowFrameCapture {

  @Override
  public BufferedImage capture() {
    return ScreenManager.captureWindow();
  }
}
