package com.chromascape.utils.core.screen.viewport;

import org.bytedeco.opencv.opencv_core.Mat;

/**
 * A singleton manager that holds the active {@link Viewport} instance.
 *
 * <p>This class ensures that core utilities can send visual data without knowing the specific
 * implementation details (e.g. whether it's running headless or via websockets).
 */
public class ViewportManager {

  /** The current active viewport instance. Defaults to a no-op implementation. */
  private static Viewport instance = new NoOpViewport();

  /** Private constructor to prevent instantiation. */
  private ViewportManager() {}

  /**
   * Retrieves the current viewport instance.
   *
   * @return The active {@link Viewport}.
   */
  public static Viewport getInstance() {
    return instance;
  }

  /**
   * Sets the active viewport instance.
   *
   * <p>This is typically called by the Spring application startup to inject the websocket-based
   * implementation.
   *
   * @param viewport The new {@link Viewport} implementation to use.
   */
  public static void setInstance(Viewport viewport) {
    instance = viewport;
  }

  /**
   * A default no-operation implementation of the Viewport interface.
   *
   * <p>This is used when the application is running in headless mode or otherwise has no mechanism
   * to display visual data. It simply discards updates to prevent errors and overhead.
   */
  private static class NoOpViewport implements Viewport {

    /**
     * Discards the update as this is a no-op implementation.
     *
     * @param image The image to discard.
     */
    @Override
    public void updateState(Mat image) {
      // Do nothing
    }
  }
}
