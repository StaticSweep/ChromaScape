package com.chromascape.web.integration.micro6;

import com.chromascape.web.integration.micro6.service.Micro6MouseTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives micro6 "Bot Mouse" trace batches (SC-2150 FA12 native labels) and stores them next to
 * the active recording. Accepted only from the local machine: the trace describes the client this
 * host is recording.
 */
@RestController
@RequestMapping("/api/micro6")
public class Micro6MouseTraceController {
  private final Micro6MouseTraceService traceService;

  /** Creates the controller over the trace store. */
  public Micro6MouseTraceController(Micro6MouseTraceService traceService) {
    this.traceService = traceService;
  }

  /** Stores one batch; returns which recording it was attached to, if any. */
  @PostMapping("/mouse-trace")
  public ResponseEntity<Map<String, Object>> receive(
      @RequestBody JsonNode batch, HttpServletRequest request) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    if (!loopback(request.getRemoteAddr())) {
      body.put("success", false);
      body.put("message", "Mouse traces are accepted from the local machine only");
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
    try {
      Micro6MouseTraceService.Result result = traceService.accept(batch);
      body.put("success", true);
      body.put("recordingId", result.recordingId());
      body.put("stored", result.stored());
      body.put("rejected", result.rejected());
      body.put("capped", result.capped());
      return ResponseEntity.ok(body);
    } catch (IllegalArgumentException e) {
      body.put("success", false);
      body.put("message", e.getMessage());
      return ResponseEntity.badRequest().body(body);
    }
  }

  static boolean loopback(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    try {
      // Literal addresses only; never resolve a hostname.
      return (address.indexOf(':') >= 0 || Character.isDigit(address.charAt(0)))
          && InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
