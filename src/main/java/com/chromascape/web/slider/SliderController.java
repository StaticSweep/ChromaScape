package com.chromascape.web.slider;

import com.chromascape.web.image.ModifyImage;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller responsible for handling slider input changes from the frontend UI.
 *
 * <p>This controller updates the internal HSV slider state based on user input and triggers
 * downstream image modification logic in real time.
 */
@RestController
@RequestMapping("/api")
public class SliderController {

  /** Holds the current state of all slider values (e.g., hueMin, satMax). */
  private final CurrentSliderState sliderState;

  /** Applies visual changes based on updated HSV thresholds. */
  private final ModifyImage modifyImage;

  /**
   * Constructs a new {@code SliderController}.
   *
   * @param sliderState shared singleton bean tracking live slider positions
   * @param modifyImage service for applying image modifications
   */
  public SliderController(CurrentSliderState sliderState, ModifyImage modifyImage) {
    this.sliderState = sliderState;
    this.modifyImage = modifyImage;
  }

  /**
   * Updates the server-side HSV slider state based on frontend input and applies the updated
   * thresholds to the live image preview.
   *
   * @param config the updated slider configuration (name and value)
   * @return HTTP 200 with success message upon update
   * @throws IOException if image processing fails during application of changes
   */
  @PostMapping("/slider")
  public ResponseEntity<?> updateSlider(@RequestBody SliderConfig config) throws IOException {
    String id = config.getSliderName();
    int val = config.getSliderValue();

    sliderState.set(id, val);
    modifyImage.applySliderChanges(sliderState);

    return ResponseEntity.ok(Map.of("status", "success"));
  }

  /**
   * Retrieves the current configuration of all sliders.
   *
   * @return a map containing the current values for all HSV sliders.
   */
  @GetMapping("/slider")
  public ResponseEntity<Map<String, Integer>> getSliderState() {
    return ResponseEntity.ok(sliderState.getAll());
  }
}
