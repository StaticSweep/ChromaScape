package com.chromascape.utils.core.screen.capture;

import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.awt.Rectangle;

/** A lifecycle-bound source of rendered target frames. */
public interface TargetFrameSource {

  /** Captures one detached frame or fails closed with a stable input-session diagnostic. */
  CapturedFrame captureFrame();

  /** Identity to which this source is fenced. */
  ProcessIdentity identity();

  /** Selection generation to which this source is fenced. */
  long selectionGeneration();

  /** Current client-relative target bounds. */
  Rectangle targetBounds();
}
