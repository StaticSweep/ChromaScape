package com.chromascape.web.recording.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for serving the replay interface.
 *
 * <p>Provides a web-based interface for recording game sessions and replaying them with
 * synchronized logs.
 */
@Controller
public class ReplayViewController {

  /**
   * Serves the replay interface page.
   *
   * @return The replay template name
   */
  @GetMapping("/replay")
  public String replayPage() {
    return "replay";
  }
}
