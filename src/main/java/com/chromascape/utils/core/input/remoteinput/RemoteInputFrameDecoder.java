package com.chromascape.utils.core.input.remoteinput;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/** Pure conversion from RemoteInput's top-down BGRA bytes to an owned Java BGR image. */
final class RemoteInputFrameDecoder {
  static final int BYTES_PER_PIXEL = 4;
  static final int MAX_DIMENSION = 16_384;
  static final int MAX_FRAME_BYTES = 512 * 1024 * 1024;

  private RemoteInputFrameDecoder() {}

  static int checkedByteCount(int width, int height) {
    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
      throw new IllegalArgumentException("Frame dimensions are outside the supported bound");
    }
    long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), BYTES_PER_PIXEL);
    if (bytes > MAX_FRAME_BYTES) {
      throw new IllegalArgumentException("Frame byte count is outside the supported bound");
    }
    return Math.toIntExact(bytes);
  }

  static BufferedImage decode(byte[] bgra, int width, int height) {
    int expected = checkedByteCount(width, height);
    if (bgra == null || bgra.length != expected) {
      throw new IllegalArgumentException("BGRA buffer length does not match frame dimensions");
    }

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    for (int source = 0, destination = 0; source < bgra.length; source += 4) {
      bgr[destination++] = bgra[source];
      bgr[destination++] = bgra[source + 1];
      bgr[destination++] = bgra[source + 2];
    }
    return image;
  }
}
