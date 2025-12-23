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
   * Handles GET requests for the root ("/") URL.
   *
   * @return the logical view name "index"
   */
  @GetMapping("/")
  public String serveIndexPage() {
    return "index";
  }

  /**
   * Handles GET requests for the "/colour" URL.
   *
   * @return the logical view name "colour"
   */
  @GetMapping("/colour")
  public String serveColourPickerPage() {
    return "colour";
  }
}
