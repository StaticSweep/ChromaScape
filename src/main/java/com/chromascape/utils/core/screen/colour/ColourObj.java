package com.chromascape.utils.core.screen.colour;

import org.bytedeco.opencv.opencv_core.Scalar;

/**
 * This record class stores the name, min threshold, and max threshold of an HSV colour. Note: The
 * fourth channel (alpha) is always zero and unused due to how JavaCV handles Scalar.
 *
 * @param name Name of the colour.
 * @param hsvMin Minimum HSV threshold; alpha channel is ignored (always zero).
 * @param hsvMax Maximum HSV threshold; alpha channel is ignored (always zero).
 */
public record ColourObj(String name, Scalar hsvMin, Scalar hsvMax) {

  /**
   * Constructs a ColourObj with copies of the provided HSV scalar bounds. This ensures immutability
   * by duplicating the passed Scalar objects. The fourth (alpha) channel is preserved from input
   * but unused in HSV processing.
   *
   * @param name The name identifier for the colour.
   * @param hsvMin The lower HSV bound (inclusive).
   * @param hsvMax The upper HSV bound (inclusive).
   */
  public ColourObj(String name, Scalar hsvMin, Scalar hsvMax) {
    this.name = name;
    this.hsvMin = new Scalar(hsvMin.get(0), hsvMin.get(1), hsvMin.get(2), hsvMin.get(3));
    this.hsvMax = new Scalar(hsvMax.get(0), hsvMax.get(1), hsvMax.get(2), hsvMax.get(3));
  }

  /**
   * Fetches the minimum HSV values of this colour.
   *
   * @return A copy of the internal hsvMin to avoid mutability. The 4th channel is always zero.
   */
  @Override
  public Scalar hsvMin() {
    return new Scalar(hsvMin.get(0), hsvMin.get(1), hsvMin.get(2), hsvMin.get(3));
  }

  /**
   * Fetches the maximum HSV values of this colour.
   *
   * @return A copy of the internal hsvMax to avoid mutability. The 4th channel is always zero.
   */
  @Override
  public Scalar hsvMax() {
    return new Scalar(hsvMax.get(0), hsvMax.get(1), hsvMax.get(2), hsvMax.get(3));
  }
}
