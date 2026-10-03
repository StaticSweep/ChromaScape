package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class RemoteInputFrameDecoderTest {

  @Test
  void decodesTopDownBgraWithoutFlippingAndIgnoresAlpha() {
    byte[] bgra = {
      0,
      0,
      (byte) 255,
      0,
      0,
      (byte) 255,
      0,
      10,
      (byte) 255,
      0,
      0,
      (byte) 128,
      (byte) 255,
      (byte) 255,
      (byte) 255,
      (byte) 255
    };

    BufferedImage image = RemoteInputFrameDecoder.decode(bgra, 2, 2);

    assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.getType());
    assertEquals(2, image.getWidth());
    assertEquals(2, image.getHeight());
    assertEquals(0xFFFF0000, image.getRGB(0, 0));
    assertEquals(0xFF00FF00, image.getRGB(1, 0));
    assertEquals(0xFF0000FF, image.getRGB(0, 1));
    assertEquals(0xFFFFFFFF, image.getRGB(1, 1));
  }

  @Test
  void rejectsZeroOversizedOverflowingAndTruncatedFrames() {
    assertThrows(
        IllegalArgumentException.class, () -> RemoteInputFrameDecoder.checkedByteCount(0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> RemoteInputFrameDecoder.checkedByteCount(16_385, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> RemoteInputFrameDecoder.checkedByteCount(16_384, 16_384));
    assertThrows(
        IllegalArgumentException.class, () -> RemoteInputFrameDecoder.decode(new byte[15], 2, 2));
  }
}
