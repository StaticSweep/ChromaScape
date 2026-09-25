package com.chromascape.utils.core.screen.capture;

import static org.bytedeco.opencv.global.opencv_core.CV_8U;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import org.bytedeco.opencv.opencv_core.Mat;

/** Detached, stride-safe conversion adapters between Java images and OpenCV matrices. */
public final class FrameImageAdapter {
  private FrameImageAdapter() {}

  /** Converts any non-null Java image to an independently owned, continuous 8-bit BGR matrix. */
  public static Mat toMat(BufferedImage image) {
    if (image == null) {
      throw new IllegalArgumentException("Image must not be null");
    }
    BufferedImage bgr = bgrImage(image.getWidth(), image.getHeight());
    Graphics2D graphics = bgr.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
    Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CV_8UC3);
    mat.data().put(pixels);
    return mat;
  }

  /**
   * Converts a non-empty one-, three-, or four-channel 8-bit matrix to an independently owned Java
   * image. Four-channel OpenCV BGRA is reordered to Java ABGR.
   */
  public static BufferedImage toBufferedImage(Mat mat) {
    if (mat == null || mat.empty()) {
      throw new IllegalArgumentException("Matrix must not be null or empty");
    }
    if (mat.depth() != CV_8U) {
      throw new IllegalArgumentException("Only 8-bit matrices are supported");
    }
    Mat continuous = mat.isContinuous() ? mat : mat.clone();
    try {
      int channels = continuous.channels();
      int width = continuous.cols();
      int height = continuous.rows();
      int pixels = Math.multiplyExact(width, height);
      int byteCount = Math.multiplyExact(pixels, channels);
      byte[] source = new byte[byteCount];
      continuous.data().get(source);
      BufferedImage image;
      byte[] destination;
      if (channels == 1) {
        image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        destination = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(source, 0, destination, 0, source.length);
      } else if (channels == 3) {
        image = bgrImage(width, height);
        destination = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(source, 0, destination, 0, source.length);
      } else if (channels == 4) {
        image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        destination = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int index = 0; index < source.length; index += 4) {
          destination[index] = source[index + 3];
          destination[index + 1] = source[index];
          destination[index + 2] = source[index + 1];
          destination[index + 3] = source[index + 2];
        }
      } else {
        throw new IllegalArgumentException("Unsupported matrix channel count: " + channels);
      }
      return image;
    } finally {
      if (continuous != mat) {
        continuous.release();
      }
    }
  }

  private static BufferedImage bgrImage(int width, int height) {
    return new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
  }
}
