package com.chromascape.web.viewport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import org.springframework.stereotype.Component;

/** Shared JPEG encoder for viewport and remote streaming paths. */
@Component
public class JpegFrameEncoder {

  /**
   * Encodes a buffered image as JPEG.
   *
   * @param image image to encode
   * @param quality JPEG quality from 1-100
   * @return encoded JPEG bytes
   * @throws IOException if no writer is available or the encode fails
   */
  public byte[] encode(BufferedImage image, int quality) throws IOException {
    if (image == null) {
      throw new IOException("Cannot encode null image");
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
    if (!writers.hasNext()) {
      throw new IOException("No JPEG ImageWriter available");
    }

    ImageWriter writer = writers.next();
    try {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(Math.max(0.01f, Math.min(1.0f, quality / 100.0f)));

      try (var ios = ImageIO.createImageOutputStream(baos)) {
        writer.setOutput(ios);
        writer.write(null, new IIOImage(image, null, null), param);
      }
    } finally {
      writer.dispose();
    }

    return baos.toByteArray();
  }
}
