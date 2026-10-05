package com.chromascape.web.debug.buildergoal.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Read-only operator diagnostics; this page cannot select, launch or dispatch a client. */
@Controller
public class BuilderGoalTopologyPageController {
  @GetMapping("/debugger/topology")
  public String topology() {
    return "builder-goal-topology";
  }
}
