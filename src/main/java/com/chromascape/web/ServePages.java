package com.chromascape.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller responsible for serving main web pages.
 *
 * <p>Handles requests for the index page and the colour picker page.
 */
@Controller
public class ServePages {

  @Value("${chromascape.patchloop.supervisor-base-url:http://127.0.0.1:8096}")
  private String supervisorBaseUrl;

  /** Handles GET requests for the root ("/") URL. */
  @GetMapping("/")
  public String serveIndexPage() {
    return "index";
  }

  /** Handles GET requests for the "/colour" URL. */
  @GetMapping("/colour")
  public String serveColourPickerPage() {
    return "colour";
  }

  /**
   * Handles GET requests for the "/debugger" URL and supplies its supervisor endpoint.
   *
   * @return the logical view name "debugger"
   */
  @GetMapping({"/debugger", "/debugger/runs"})
  public String serveDebuggerPage(Model model) {
    model.addAttribute("supervisorBaseUrl", supervisorBaseUrl);
    return "debugger";
  }
}
