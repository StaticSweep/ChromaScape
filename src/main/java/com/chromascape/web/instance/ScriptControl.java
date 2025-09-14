package com.chromascape.web.instance;

import java.lang.reflect.InvocationTargetException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that handles starting and stopping of scripts, and querying their running state.
 *
 * <p>Provides endpoints to submit a run configuration, start a script instance, stop the currently
 * running script, and check if a script is running. All responses include appropriate HTTP status
 * codes and messages.
 */
@RestController
@RequestMapping("/api")
public class ScriptControl {

  private static final Logger logger = LogManager.getLogger(ScriptControl.class.getName());
  private final WebSocketStateHandler stateHandler;

  /**
   * Constructs the script controller with a state handler.
   *
   * @param webSocketStateHandler state handler used to send state to the client via web-socket.
   */
  public ScriptControl(WebSocketStateHandler webSocketStateHandler) {
    this.stateHandler = webSocketStateHandler;
  }

  /**
   * Starts a script based on the provided run configuration.
   *
   * <p>Validates the input configuration fields: script name, duration, and window style. If valid,
   * it attempts to instantiate and start the script. Logs relevant information and returns HTTP
   * status codes accordingly.
   *
   * @param config the RunConfig object containing script parameters (JSON in request body)
   * @return ResponseEntity with status and message indicating success or error details
   */
  @PostMapping(path = "/runConfig", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> getRunConfig(@RequestBody RunConfig config) {
    try {
      // Validation checks
      if (config.getScript() == null || config.getScript().isEmpty()) {
        logger.error("No script is selected");
        return ResponseEntity.badRequest().body("Script must be specified.");
      }

      if (config.isFixed() == null) {
        logger.error("No window style selected");
        return ResponseEntity.badRequest().body("Window style (Fixed?) must be specified.");
      }

      logger.info("Config valid: attempting to run script");

      // Instantiate and start the script instance
      ScriptInstance instance = new ScriptInstance(config, stateHandler);
      ScriptInstanceManager.getInstance().setInstance(instance);
      instance.start();

      return ResponseEntity.ok("Script started successfully.");

    } catch (ClassNotFoundException e) {
      logger.error("Script class not found: {}", e.getMessage());
      return ResponseEntity.badRequest().body("Script class not found.");
    } catch (NoSuchMethodException e) {
      logger.error("Script constructor not found: {}", e.getMessage());
      return ResponseEntity.badRequest().body("Script constructor not valid.");
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      logger.error("Failed to instantiate script: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to start script.");
    } catch (Exception e) {
      logger.error("Unexpected error: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Unexpected error: " + e.getMessage());
    }
  }

  /**
   * Stops the currently running script instance.
   *
   * <p>Logs the stop request and interrupts the running script thread.
   *
   * @return ResponseEntity with HTTP 200 OK status after attempting to stop the script
   */
  @PostMapping(path = "/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> stopScript() {
    logger.info("Received stop request");
    ScriptInstanceManager.getInstance().getInstanceRef().stop();
    return ResponseEntity.ok().build();
  }
}
