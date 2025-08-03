package com.chromascape.utils.core.input.mouse;

import com.chromascape.utils.core.screen.window.ScreenManager;
import java.awt.Rectangle;
import java.util.Map;

/**
 * Utility class for estimating physical monitor characteristics and calculating scaling factors
 * based on monitor resolution and size.
 *
 * <p>Used to adjust input parameters dynamically depending on the physical screen size and pixel
 * density for more consistent behavior across different display setups.
 */
public class PhysicalScaling {

  /**
   * A mapping of common screen resolutions (width x height) to their typical diagonal sizes in
   * inches.
   */
  private static final Map<String, Double> COMMON_MONITOR_SIZES =
      Map.of(
          "1920x1080", 24.0, // Most common 24" 1080p
          "2560x1440", 27.0, // Most common 27" 1440p
          "3440x1440", 34.0, // Standard 34" ultrawide
          "3840x2160", 28.0, // Common 28" 4K
          "2560x1080", 29.0, // 29" ultrawide 1080p
          "1366x768", 15.6 // Common laptop size
          );

  /**
   * Estimates the physical diagonal size of the current monitor in inches. Uses the monitor's
   * current resolution to guess a typical physical size based on a predefined mapping of common
   * resolutions to monitor sizes.
   *
   * <p>If the resolution is unknown, defaults to 24 inches and logs a warning.
   *
   * @return the estimated physical diagonal size in inches.
   */
  public static double estimatePhysicalDiagonal() {
    Rectangle rect = ScreenManager.getMonitorBounds();
    String resolution = rect.width + "x" + rect.height;

    double diagonal = COMMON_MONITOR_SIZES.getOrDefault(resolution, 24.0);

    if (!COMMON_MONITOR_SIZES.containsKey(resolution)) {
      System.out.println("Unknown resolution " + resolution + ", assuming 24\" monitor");
    }

    return diagonal;
  }

  /**
   * Calculates the physical pixels per inch (PPI) of the current monitor. This is computed by
   * dividing the pixel diagonal length by the estimated physical diagonal in inches.
   *
   * @return the calculated monitor pixels per inch (PPI).
   */
  public static double calculatePhysicalPpi() {
    Rectangle rect = ScreenManager.getMonitorBounds();
    double diagonal = estimatePhysicalDiagonal();
    double pixelDiagonal = Math.sqrt(rect.width * rect.width + rect.height * rect.height);

    return pixelDiagonal / diagonal;
  }

  /**
   * Calculates a tuning factor used to normalize input scaling parameters across different physical
   * monitors.
   *
   * <p>This is defined as the ratio of the calculated physical PPI to a baseline PPI of 91.78,
   * which represents a reference monitor.
   *
   * @return the tuning factor for scaling inputs based on monitor PPI.
   */
  public static double calculateTuningFactor() {
    return calculatePhysicalPpi() / 91.78;
  }
}
