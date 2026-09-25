package com.chromascape.utils.core.screen.topology;

import java.awt.Rectangle;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Represents a detected object in the ChromaScape pipeline with a unique ID, its contour as an
 * OpenCV {@link Mat}, and a bounding box for interaction.
 *
 * @param id A unique identifier assigned based on the object's index among detected contours.
 * @param contour The OpenCV matrix representing the object's contour.
 * @param boundingBox The bounding rectangle used to sample interaction points.
 */
public record ChromaObj(int id, Mat contour, Rectangle boundingBox) {
  /** Releases the native OpenCV memory associated with the contour. */
  public void release() {
    if (contour != null) {
      contour.release();
    }
  }
}
