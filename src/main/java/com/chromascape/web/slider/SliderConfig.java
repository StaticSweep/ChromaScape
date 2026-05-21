package com.chromascape.web.slider;

/**
 * DTO representing an individual slider update sent from the frontend.
 *
 * <p>This class is used as the {@code @RequestBody} for slider update POST requests in the HSV
 * filter tuning UI.
 *
 * <p>Each instance represents a single slider (e.g., "hueMin") and its corresponding value.
 */
public class SliderConfig {

  /** The name of the slider being adjusted (e.g., "hueMin", "satMax"). */
  private String sliderName;

  /** The updated value of the slider. */
  private int sliderValue;

  /** Default constructor required for JSON deserialization. */
  public SliderConfig() {}

  /**
   * Constructs a {@code SliderConfig} with a name and value.
   *
   * @param sliderName the identifier of the slider
   * @param sliderValue the new value of the slider
   */
  public SliderConfig(final String sliderName, final int sliderValue) {
    this.sliderName = sliderName;
    this.sliderValue = sliderValue;
  }

  /**
   * Returns the slider's name (e.g., "hueMin").
   *
   * @return the slider name
   */
  public String getSliderName() {
    return sliderName;
  }

  /**
   * Returns the new value of the slider.
   *
   * @return the slider value
   */
  public int getSliderValue() {
    return sliderValue;
  }
}
