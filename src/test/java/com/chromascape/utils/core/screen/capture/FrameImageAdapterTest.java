package com.chromascape.utils.core.screen.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.Test;

class FrameImageAdapterTest {

  @Test
  void bgrImageRoundTripsThroughDetachedMat() {
    BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_3BYTE_BGR);
    source.setRGB(0, 0, 0xFF123456);
    source.setRGB(1, 0, 0xFFABCDEF);

    Mat mat = FrameImageAdapter.toMat(source);
    try {
      source.setRGB(0, 0, 0xFF000000);
      final BufferedImage restored = FrameImageAdapter.toBufferedImage(mat);

      assertEquals(2, mat.cols());
      assertEquals(1, mat.rows());
      assertEquals(3, mat.channels());
      assertEquals(0xFF123456, restored.getRGB(0, 0));
      assertEquals(0xFFABCDEF, restored.getRGB(1, 0));
    } finally {
      mat.release();
    }
  }

  @Test
  void rejectsNullInputs() {
    assertThrows(IllegalArgumentException.class, () -> FrameImageAdapter.toMat(null));
    assertThrows(IllegalArgumentException.class, () -> FrameImageAdapter.toBufferedImage(null));
  }
}
