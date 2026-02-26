package com.chromascape.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller responsible for serving main web pages.
 *
 * <p>Handles requests for the index page and the colour picker page.
 */
@Controller
public class ServePages {

  /**
   * Handles GET requests for the root ("/") URL. Sets the AWT system property to non-headless mode,
   * then returns the view name for the index page.
   *
   * @return the logical view name "index"
   */
  @GetMapping("/")
  public String serveIndexPage() {
    System.setProperty("java.awt.headless", "false");
    return "index";
  }

  /**
   * Handles GET requests for the "/colour" URL. Sets the AWT system property to non-headless mode,
   * then returns the view name for the colour picker page.
   *
   * @return the logical view name "colour"
   */
  @GetMapping("/colour")
  public String serveColourPickerPage() {
    System.setProperty("java.awt.headless", "false");
    return "colour";
  }

  /**
   * Handles GET requests for the "/debugger" URL. Sets the AWT system property to non-headless
   * mode, then returns the view name for the debugger page.
   *
   * @return the logical view name "debugger"
   */
  @GetMapping("/debugger")
  public String serveDebuggerPage() {
    System.setProperty("java.awt.headless", "false");
    return "debugger";
  }
}
