package com.chromascape.web.observe;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * View-only desktop observation for the debugger Observe panel.
 *
 * <p>Starting a watch streams the local desktop into the live viewport without opening an input
 * session: nothing attaches, injects, or drives any process. The endpoints are loopback-only, like
 * the qualification harness, because watching the desktop is a local-operator action.
 */
@RestController
@RequestMapping("/api/observe")
public class ObserveController {

  private static final Logger logger = LogManager.getLogger(ObserveController.class);

  private final ObserveService observeService;

  public ObserveController(ObserveService observeService) {
    this.observeService = observeService;
  }

  /**
   * Starts the desktop watch. The optional body carries display, targetWidth, and framesPerSecond;
   * every field falls back to its default when absent.
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> start(
      HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    try {
      Map<String, Object> payload = body == null ? Map.of() : body;
      Map<String, Object> status =
          observeService.start(
              text(payload.get("display")),
              number(payload.get("targetWidth")),
              number(payload.get("framesPerSecond")));
      return ResponseEntity.ok(status);
    } catch (ObserveException failure) {
      return observeFailure(failure);
    }
  }

  /** Stops the desktop watch. */
  @PostMapping("/stop")
  public ResponseEntity<Map<String, Object>> stop(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    try {
      return ResponseEntity.ok(observeService.stop());
    } catch (ObserveException failure) {
      return observeFailure(failure);
    }
  }

  /** Returns the watch state for the debugger UI. */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> refusal = requireLoopback(request);
    if (refusal != null) {
      return refusal;
    }
    return ResponseEntity.ok(observeService.status());
  }

  private ResponseEntity<Map<String, Object>> observeFailure(ObserveException failure) {
    HttpStatus status =
        switch (failure.getCode()) {
          case ObserveException.CONFLICT,
              ObserveException.ALREADY_ACTIVE,
              ObserveException.NOT_ACTIVE -> HttpStatus.CONFLICT;
          case ObserveException.FFMPEG_MISSING -> HttpStatus.SERVICE_UNAVAILABLE;
          default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    if (status.is5xxServerError()) {
      logger.warn("Desktop watch failed: {}", failure.getMessage());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", failure.getCode());
    body.put("message", failure.getMessage());
    return ResponseEntity.status(status).body(body);
  }

  private static ResponseEntity<Map<String, Object>> requireLoopback(HttpServletRequest request) {
    if (isLoopbackAddress(request.getRemoteAddr())) {
      return null;
    }
    logger.warn("Refused non-loopback observe request from {}", request.getRemoteAddr());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", "NON_LOOPBACK_REFUSED");
    body.put("message", "Desktop observation is loopback-only");
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
  }

  /** Whether the address text denotes the local host; package-private for focused tests. */
  static boolean isLoopbackAddress(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException failure) {
      return false;
    }
  }

  private static String text(Object value) {
    return value instanceof String text ? text : "";
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }
}
