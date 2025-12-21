package com.chromascape.web.image;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller to serve image files from the server.
 *
 * <p>Provides endpoints to retrieve the original and modified images as PNG byte arrays. If the
 * requested image is not found in the output directory, a default fallback image from resources is
 * returned.
 */
@RestController
@RequestMapping("/api")
public class ImageController {

  private final ModifyImage modifyImage;

  /**
   * Constructor for the ImageController class.
   *
   * @param modifyImage The dependency injected Spring service class that does image operations for
   *     the frontend.
   */
  public ImageController(ModifyImage modifyImage) {
    this.modifyImage = modifyImage;
  }

  /**
   * Returns the original image as a PNG byte array.
   *
   * <p>Attempts to read the file "output/original.png" from disk. If the file does not exist, falls
   * back to "resources/images/defaultImage/original.png" on the classpath.
   *
   * @return byte array representing the original PNG image.
   * @throws IOException if the file cannot be read.
   */
  @GetMapping(value = "/originalImage", produces = MediaType.IMAGE_PNG_VALUE)
  public @ResponseBody byte[] originalImage() throws IOException {
    File outputFile = new File("output/original.png");
    try (InputStream in =
        outputFile.exists()
            ? new FileInputStream(outputFile)
            : getClass().getResourceAsStream("/images/defaultImage/original.png")) {
      assert in != null;
      return IOUtils.toByteArray(in);
    }
  }

  /**
   * Returns the modified image as a PNG byte array.
   *
   * <p>Retrieved from the in-memory cache of the {@link ModifyImage} service. If the current
   * screenshot is newer than the cache, the original image is returned instead.
   *
   * @return byte array representing the modified or original PNG image.
   * @throws IOException if the file(s) cannot be read.
   */
  @GetMapping(value = "/modifiedImage", produces = MediaType.IMAGE_PNG_VALUE)
  public @ResponseBody byte[] modifiedImage() throws IOException {
    return modifyImage.getModifiedImageBytes();
  }
}
