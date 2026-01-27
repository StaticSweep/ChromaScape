package com.chromascape.utils.core.screen;

/**
 * Interface for receiving captured screen frames from the unified capture service.
 *
 * <p>Implementations receive frames asynchronously via the {@link #onFrameCaptured(FrameEvent)}
 * method. Listeners can control whether they want to receive frames by implementing {@link
 * #isActive()}.
 *
 * <p>Frame delivery is non-blocking - each listener receives frames on a separate executor thread
 * to prevent slow listeners from affecting others.
 */
public interface FrameListener {

  /**
   * Called when a new frame has been captured.
   *
   * <p>This method is called asynchronously on a background thread. Implementations should process
   * the frame quickly or queue it for later processing to avoid blocking frame delivery.
   *
   * @param event The captured frame with metadata
   */
  void onFrameCaptured(FrameEvent event);

  /**
   * Returns whether this listener is currently interested in receiving frames.
   *
   * <p>When this returns false, the unified capture service will skip delivering frames to this
   * listener. This allows pausing frame delivery without unregistering.
   *
   * @return true if the listener wants to receive frames, false to skip delivery
   */
  boolean isActive();
}
