package com.chromascape.utils.core.screen.viewport;

import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Interface that defines the contract for a viewport implementation.
 *
 * <p>A viewport is responsible for visualising the bot's sensor data (such as masks or templates)
 * to an external observer, usually via a web interface.
 */
public interface Viewport {

  /**
   * Updates the visual state of the viewport with a new image.
   *
   * @param image The matrix (image) to be displayed in the viewport.
   */
  void updateState(Mat image);
}
