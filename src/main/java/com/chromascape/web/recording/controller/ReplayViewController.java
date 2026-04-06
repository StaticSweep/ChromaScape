package com.chromascape.web.recording.controller;

import com.chromascape.web.simulator.SimulatorVerifierProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for serving the replay interface.
 *
 * <p>Provides a web-based interface for recording game sessions and replaying them with
 * synchronized logs. Also provides simulator certification integration.
 */
@Controller
public class ReplayViewController {

  private final SimulatorVerifierProperties simulatorProperties;

  @Autowired
  public ReplayViewController(SimulatorVerifierProperties simulatorProperties) {
    this.simulatorProperties = simulatorProperties;
  }

  /**
   * Serves the replay interface page.
   *
   * <p>Passes simulator verifier configuration to the template for frontend use.
   *
   * @return The replay template name
   */
  @GetMapping("/replay")
  public String replayPage(org.springframework.ui.Model model) {
    // Pass simulator verifier configuration to template
    Map<String, Object> simulatorConfig = new HashMap<>();
    simulatorConfig.put("enabled", simulatorProperties.isEnabled());
    simulatorConfig.put("defaultBridgeUrl", simulatorProperties.getDefaultBridgeUrl());

    model.addAttribute("simulatorConfig", simulatorConfig);

    return "replay";
  }
}
