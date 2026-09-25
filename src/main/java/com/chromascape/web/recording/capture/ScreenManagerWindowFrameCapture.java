package com.chromascape.web.recording.capture;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import java.awt.image.BufferedImage;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production {@link WindowFrameCapture} backed by {@link ScreenManager#captureWindow()}.
 *
 * <p>Retry and readiness policy belongs to {@code RecordingService}; this component only performs a
 * single capture attempt.
 */
@Component
public class ScreenManagerWindowFrameCapture implements WindowFrameCapture {

  private final WindowSelectionService selectionService;

  /** Creates the production provider with access to the exact selected target identity. */
  @Autowired
  public ScreenManagerWindowFrameCapture(WindowSelectionService selectionService) {
    this.selectionService = selectionService;
  }

  /** Compatibility constructor for non-Spring callers. */
  public ScreenManagerWindowFrameCapture() {
    this(null);
  }

  @Override
  public BufferedImage capture() {
    return ScreenManager.captureWindow();
  }

  @Override
  public FrameEvent captureEvent() {
    if (ProcessManagerFactory.currentPlatform() == ProcessManagerFactory.Platform.LINUX) {
      CapturedFrame captured = ScreenManager.captureFrame();
      return FrameEvent.from(captured, System.nanoTime());
    }

    long generationBefore =
        selectionService != null ? selectionService.getSelectedGeneration() : -1;
    ProcessIdentity identityBefore =
        selectionService != null ? selectionService.getSelectedIdentity() : null;
    FrameSourceBinding bindingBefore = binding(identityBefore, generationBefore);
    BufferedImage image = capture();
    long generationAfter = selectionService != null ? selectionService.getSelectedGeneration() : -1;
    ProcessIdentity identityAfter =
        selectionService != null ? selectionService.getSelectedIdentity() : null;
    FrameSourceBinding bindingAfter = binding(identityAfter, generationAfter);
    requireStableSelection(generationBefore, bindingBefore, generationAfter, bindingAfter);
    long captureTimeNanos = System.nanoTime();
    return image == null
        ? null
        : new FrameEvent(
            image,
            captureTimeNanos,
            image.getWidth(),
            image.getHeight(),
            bindingBefore,
            null,
            null,
            null);
  }

  private static FrameSourceBinding binding(ProcessIdentity identity, long generation) {
    return identity != null ? FrameSourceBinding.from(identity, generation) : null;
  }

  /** Refuses to associate Windows pixels with metadata from a changed selection. */
  static void requireStableSelection(
      long generationBefore,
      FrameSourceBinding bindingBefore,
      long generationAfter,
      FrameSourceBinding bindingAfter) {
    if (generationBefore != generationAfter || !Objects.equals(bindingBefore, bindingAfter)) {
      throw new IllegalStateException("Target selection changed while capturing a frame");
    }
  }
}
