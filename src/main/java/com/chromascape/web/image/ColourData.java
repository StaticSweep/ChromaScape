package com.chromascape.web.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a color range in OpenCV's HSV color space with a name identifier and minimum and
 * maximum HSV bounds.
 *
 * <p>The minimum and maximum arrays define the inclusive range of HSV values that this color
 * covers. Each array is expected to have exactly three elements representing Hue (0-180),
 * Saturation (0-255), and Value (0-255) respectively. Note: there is a last value of 0 to conform
 * to JavaCV's scalar.
 */
public class ColourData {

  /** The name identifying this HSV color range. */
  private String name;

  /**
   * The minimum HSV bounds for this color range. Expected format: [Hue, Saturation, Value, 0]. Hue
   * ranges 0-179, Saturation and Value range 0-255.
   */
  private int[] min;

  /**
   * The maximum HSV bounds for this color range. Expected format: [Hue, Saturation, Value, 0]. Hue
   * ranges 0-179, Saturation and Value range 0-255.
   */
  private int[] max;

  /** Optional RGB metadata for this colour represented as [Red, Green, Blue]. */
  @JsonProperty("RGB")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private int[] rgb;

  /**
   * Returns the name of this HSV color range.
   *
   * @return the color name.
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the minimum HSV values defining this color range.
   *
   * @return an int array of length 4: [Hue, Saturation, Value, 0].
   */
  public int[] getMin() {
    return min;
  }

  /**
   * Returns the maximum HSV values defining this color range.
   *
   * @return an int array of length 4: [Hue, Saturation, Value, 0].
   */
  public int[] getMax() {
    return max;
  }

  /**
   * Returns the optional RGB values for this colour.
   *
   * @return an int array of length 3 representing [Red, Green, Blue], or null if unset.
   */
  @JsonProperty("RGB")
  public int[] getRgb() {
    return rgb;
  }

  /**
   * Sets the name of this HSV color range.
   *
   * @param name the color name to set.
   */
  public void setName(String name) {
    this.name = name;
  }

  /**
   * Sets the minimum HSV bounds for this color range.
   *
   * @param min an int array of length 4 representing [Hue, Saturation, Value, 0].
   */
  public void setMin(int[] min) {
    this.min = min;
  }

  /**
   * Sets the maximum HSV bounds for this color range.
   *
   * @param max an int array of length 4 representing [Hue, Saturation, Value, 0].
   */
  public void setMax(int[] max) {
    this.max = max;
  }

  /**
   * Sets the optional RGB metadata for this colour.
   *
   * @param rgb an int array of length 3 representing [Red, Green, Blue].
   */
  @JsonProperty("RGB")
  public void setRgb(int[] rgb) {
    this.rgb = rgb;
  }
}
