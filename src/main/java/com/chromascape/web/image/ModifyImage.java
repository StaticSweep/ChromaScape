package com.chromascape.web.image;

import com.chromascape.utils.core.screen.topology.ColourContours;
import com.chromascape.web.slider.CurrentSliderState;
import java.io.File;
import java.io.IOException;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service responsible for applying modifications to an image based on slider inputs.
 *
 * <p>This service loads an original image from the filesystem, applies colour extraction based on
 * the current slider state, and caches the result in memory. It eliminates the need for disk writes
 * to improve performance.
 */
@Service
public class ModifyImage {

  private static final Logger logger = LoggerFactory.getLogger(ModifyImage.class);
  private static final String ORIGINAL_IMAGE_PATH = "output/original.png";

  /** Cached bytes of the latest processing result. */
  private byte[] cachedModifiedBytes;

  /** Timestamp of the last successful processing. */
  private long lastProcessedTime = 0;

  /** Timestamp of the original file when it was last loaded or checked. */
  private long lastOriginalModTime = 0;

  private Mat cachedOriginalMat;

  /**
   * Applies modifications to the original image based on the given slider state.
   *
   * <p>The method performs the following steps:
   *
   * <ul>
   *   <li>Checks if the original image has been modified on disk since the last load.
   *   <li>Loads the image into memory cache (as OpenCV Mat) if necessary.
   *   <li>Extracts colour contours using the slider's colour object.
   *   <li>Applies the extracted mask to the original image.
   *   <li>Encodes the result to PNG bytes in memory using OpenCV {@code imencode} and updates the
   *       cache.
   * </ul>
   *
   * @param sliderState the current state of the sliders controlling colour extraction.
   * @throws IOException if the original image file is not found or cannot be read.
   */
  public void applySliderChanges(CurrentSliderState sliderState) throws IOException {
    // Load original image from file system with caching
    File originalFile = new File(ORIGINAL_IMAGE_PATH);
    if (!originalFile.exists()) {
      logger.error("Original image file not found at: {}", ORIGINAL_IMAGE_PATH);
      throw new IOException("Original image file not found at: " + ORIGINAL_IMAGE_PATH);
    }

    // Refresh cache if file timestamp changed or cache is empty
    if (cachedOriginalMat == null || originalFile.lastModified() > lastOriginalModTime) {
      logger.info(
          "Reloading original image from disk. File time: {}, Last known: {}",
          originalFile.lastModified(),
          lastOriginalModTime);

      if (cachedOriginalMat != null) {
        cachedOriginalMat.release();
      }

      // Use imread for direct Mat loading
      cachedOriginalMat = opencv_imgcodecs.imread(originalFile.getAbsolutePath());
      if (cachedOriginalMat == null || cachedOriginalMat.empty()) {
        throw new IOException("Failed to read original image from: " + ORIGINAL_IMAGE_PATH);
      }
      lastOriginalModTime = originalFile.lastModified();
    }

    // Apply colour extraction and encode
    try (Mat modifiedMat =
        ColourContours.extractColours(cachedOriginalMat, sliderState.getColourObj())) {
      Mat result = MaskImage.applyMaskToImage(cachedOriginalMat, modifiedMat);

      // Fast encoding to PNG buffer using OpenCV (skips BufferedImage conversion)
      try (BytePointer ext = new BytePointer(".png");
          BytePointer buffer = new BytePointer()) {

        opencv_imgcodecs.imencode(ext, result, buffer);

        // Transfer bytes from native buffer to Java array
        long size = buffer.limit();
        byte[] pngBytes = new byte[(int) size];
        buffer.get(pngBytes);

        this.cachedModifiedBytes = pngBytes;
        this.lastProcessedTime = System.currentTimeMillis();
      }

      // Clean up local Mats
      result.release();
    } catch (Exception e) {
      logger.error("Error processing image in applySliderChanges", e);
      throw new IOException("Error processing image in applySliderChanges", e);
    }
  }

  /**
   * Retrieves the modified image bytes.
   *
   * <p>If the original image on disk is newer than our last processed result (e.g., a new
   * screenshot was taken), this returns the raw bytes of the original image instead. This ensures
   * users don't see stale processing on a new screenshot.
   *
   * @return byte array containing the PNG image data (either modified or original).
   * @throws IOException if reading the original file fails.
   */
  public byte[] getModifiedImageBytes() throws IOException {
    File originalFile = new File(ORIGINAL_IMAGE_PATH);

    // If we have no cached result, or the file on disk is newer than our last
    // process...
    boolean isStale = (originalFile.exists() && originalFile.lastModified() > lastProcessedTime);

    if (cachedModifiedBytes == null || isStale) {
      logger.info(
          "Serving original image (fallback). Cache empty? {}, Original > Processed? {} (Orig: {}, "
              + "Proc: {})",
          cachedModifiedBytes == null,
          isStale,
          originalFile.lastModified(),
          lastProcessedTime);
      // Serve the original image (fallback logic)
      return org.apache.commons.io.FileUtils.readFileToByteArray(originalFile);
    }

    return cachedModifiedBytes;
  }
}
