package com.chromascape.web.logs;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller for serving the log replay interface.
 *
 * <p>Provides a web-based interface for viewing historical logs with filters and search.
 */
@Controller
public class LogReplayViewController {

  /**
   * Serves the log replay interface page.
   *
   * @return The log-replay template name
   */
  @GetMapping("/log-replay")
  public String logReplayPage() {
    return "log-replay";
  }
}
