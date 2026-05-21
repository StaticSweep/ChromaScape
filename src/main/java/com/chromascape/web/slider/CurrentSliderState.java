package com.chromascape.web.slider;

import com.chromascape.utils.core.screen.colour.ColourObj;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.springframework.stereotype.Component;

/**
 * Stores the current HSV slider state for color selection in the UI.
 *
 * <p>This class is a thread-safe singleton Spring component used to manage real-time updates to
 * hue, saturation, and value (HSV) bounds, which can be retrieved as a {@link ColourObj} for image
 * processing or color detection.
 */
@Component
public class CurrentSliderState {
  private final Map<String, Integer> sliderValues = new ConcurrentHashMap<>();

  /** Constructs a new {@code CurrentSliderState} with default HSV bounds. */
  public CurrentSliderState() {
    reset();
  }

  /**
   * Updates a specific slider's value by ID.
   *
   * @param id the slider identifier (e.g. "hueMin", "satMax")
   * @param value the new slider value
   */
  public void set(String id, int value) {
    sliderValues.put(id, value);
  }

  /**
   * Retrieves the current value of a slider by ID.
   *
   * @param id the slider identifier
   * @return the current value, or 0 if not present
   */
  public int get(String id) {
    return sliderValues.getOrDefault(id, 0);
  }

  /**
   * Retrieves all current slider values as a map.
   *
   * @return a map of slider IDs to their integer values.
   */
  public Map<String, Integer> getAll() {
    return new ConcurrentHashMap<>(sliderValues);
  }

  /**
   * Converts the current slider state into a {@link ColourObj} using OpenCV HSV bounds.
   *
   * @return a {@link ColourObj} representing the selected HSV range
   */
  public ColourObj getColourObj() {
    Scalar min =
        new Scalar(
            sliderValues.getOrDefault("hueMin", 0),
            sliderValues.getOrDefault("satMin", 0),
            sliderValues.getOrDefault("valMin", 0),
            0);
    Scalar max =
        new Scalar(
            sliderValues.getOrDefault("hueMax", 179),
            sliderValues.getOrDefault("satMax", 255),
            sliderValues.getOrDefault("valMax", 255),
            0);
    return new ColourObj("custom-slider-colour", min, max);
  }

  /** Resets all sliders to default HSV values: hue [0–179], saturation [0–255], value [0–255]. */
  public void reset() {
    sliderValues.put("hueMin", 0);
    sliderValues.put("hueMax", 179);
    sliderValues.put("satMin", 0);
    sliderValues.put("satMax", 255);
    sliderValues.put("valMin", 0);
    sliderValues.put("valMax", 255);
  }
}
