package com.chromascape.web.image;

import com.chromascape.utils.core.screen.colour.ColourObj;
import com.chromascape.web.slider.CurrentSliderState;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller responsible for handling colour submission requests.
 *
 * <p>This controller receives colour names via POST requests and saves the corresponding HSV colour
 * ranges from the current slider state into persistent storage via AddColour.
 */
@RestController
@RequestMapping("/api")
public class SubmitColour {

  private final CurrentSliderState currentSliderState;

  /**
   * Constructs a SubmitColour controller with the provided current slider state.
   *
   * @param currentSliderState the object holding the current HSV colour range slider values
   */
  public SubmitColour(CurrentSliderState currentSliderState) {
    this.currentSliderState = currentSliderState;
  }

  /**
   * Handles POST requests to submit a new colour.
   *
   * <p>Extracts the current HSV minimum and maximum values from the slider state, creates a
   * ColourData object with the submitted name and HSV ranges, and saves it using the AddColour
   * service.
   *
   * @param name the name of the colour to be added, sent as the raw request body
   * @return a ResponseEntity with HTTP 200 OK status if successful
   * @throws IOException if adding the colour fails due to IO errors
   */
  @PostMapping("/submitColour")
  public ResponseEntity<Void> submitColour(@RequestBody String name) throws IOException {
    ColourObj colourObj = currentSliderState.getColourObj();

    ColourData colour = new ColourData();
    colour.setName(name);

    colour.setMin(
        new int[] {
          (int) colourObj.hsvMin().get(0),
          (int) colourObj.hsvMin().get(1),
          (int) colourObj.hsvMin().get(2),
          (int) colourObj.hsvMin().get(3)
        });

    colour.setMax(
        new int[] {
          (int) colourObj.hsvMax().get(0),
          (int) colourObj.hsvMax().get(1),
          (int) colourObj.hsvMax().get(2),
          (int) colourObj.hsvMax().get(3)
        });

    AddColour addColour = new AddColour();
    addColour.addColour(colour);

    return ResponseEntity.ok().build();
  }
}
