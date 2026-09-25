package com.chromascape.web.recording.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the recording page and its xRSPS replay debugger. */
@Controller
public class ReplayViewController {

  /** Serves the replay interface page. */
  @GetMapping("/replay")
  public String replayPage() {
    return "replay";
  }
}
