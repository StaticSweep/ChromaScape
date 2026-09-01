package com.chromascape.web.replay.kernel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes and decodes newline-delimited JSON-RPC messages for the xRSPS kernel stdio protocol.
 *
 * <p>Response envelope contract (exactly one shape, matching the compiled TypeScript worker):
 *
 * <pre>
 * {"id":"1","ok":true,"result":{...}}
 * {"id":"1","ok":false,"error":{"code":"...","message":"...","retryable":false}}
 * </pre>
 *
 * <p>Validation rules enforced on decode:
 *
 * <ul>
 *   <li>{@code id} must be present and textual.
 *   <li>{@code ok} must be present and boolean.
 *   <li>{@code ok=true} requires {@code result} and forbids {@code error}.
 *   <li>{@code ok=false} requires {@code error} and forbids {@code result}.
 *   <li>Any violation produces a fatal message (XRSPS_PROTOCOL_ERROR), never a silently dropped
 *       line or a mis-shaped result node.
 * </ul>
 */
public class KernelProtocolCodec {

  private static final Logger log = LoggerFactory.getLogger(KernelProtocolCodec.class);
  public static final String PROTOCOL_ERROR_CODE = "XRSPS_PROTOCOL_ERROR";

  private final ObjectMapper mapper;

  public KernelProtocolCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String encode(String id, String method, JsonNode params) {
    var msg = mapper.createObjectNode();
    msg.put("id", id);
    msg.put("method", method);
    if (params != null) {
      msg.set("params", params);
    }
    try {
      return mapper.writeValueAsString(msg) + "\n";
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to encode kernel message", e);
    }
  }

  /**
   * Decodes one response line. Returns null only for blank lines. Malformed envelopes surface as
   * fatal messages so callers fail loudly.
   */
  public KernelMessage decode(String line) {
    if (line == null || line.isBlank()) {
      return null;
    }
    JsonNode node;
    try {
      node = mapper.readTree(line);
    } catch (Exception e) {
      log.warn(
          "Failed to decode kernel message; treating as protocol violation: {}", e.getMessage());
      return fatal(PROTOCOL_ERROR_CODE, "Malformed JSON from kernel");
    }
    if (!node.isObject()) {
      log.warn("Kernel emitted a non-object JSON line; treating as protocol violation");
      return fatal(PROTOCOL_ERROR_CODE, "Non-object response line");
    }
    if (!node.hasNonNull("id")
        || !node.get("id").isTextual()
        || node.get("id").asText().isBlank()) {
      return fatal(PROTOCOL_ERROR_CODE, "Response is missing a textual id");
    }
    String id = node.get("id").asText();
    if (!node.hasNonNull("ok") || !node.get("ok").isBoolean()) {
      return fatal(id, PROTOCOL_ERROR_CODE, "Response is missing boolean ok");
    }
    boolean ok = node.get("ok").asBoolean();
    JsonNode result = node.has("result") ? node.get("result") : null;
    JsonNode error = node.has("error") ? node.get("error") : null;
    if (ok) {
      if (result == null || error != null) {
        return fatal(id, PROTOCOL_ERROR_CODE, "ok=true response must carry result and no error");
      }
      return new KernelMessage(id, null, result, null, false, false);
    }
    if (error == null || result != null || !error.isObject()) {
      return fatal(
          id, PROTOCOL_ERROR_CODE, "ok=false response must carry an error object and no result");
    }
    return new KernelMessage(id, null, null, error, false, false);
  }

  private static KernelMessage fatal(String code, String message) {
    return fatal(null, code, message);
  }

  private static KernelMessage fatal(String id, String code, String message) {
    ObjectMapper m = new ObjectMapper();
    var err = m.createObjectNode();
    err.put("code", code);
    err.put("message", message);
    err.put("retryable", false);
    return new KernelMessage(id, null, null, err, true, false);
  }

  /** One decoded kernel message. Fatal messages terminate request waits. */
  public record KernelMessage(
      String id,
      String method,
      JsonNode result,
      JsonNode error,
      boolean fatal,
      boolean processExited) {
    public boolean isResponse() {
      return result != null || error != null || fatal || processExited;
    }

    public boolean isError() {
      return error != null || fatal || processExited;
    }

    static KernelMessage fatal(String code, String message) {
      ObjectMapper m = new ObjectMapper();
      var err = m.createObjectNode();
      err.put("code", code);
      err.put("message", message);
      err.put("retryable", false);
      return new KernelMessage(null, null, null, err, true, false);
    }

    static KernelMessage exitWithoutResponse() {
      ObjectMapper m = new ObjectMapper();
      var err = m.createObjectNode();
      err.put("code", "INTERNAL_ERROR");
      err.put("message", "Kernel process exited without sending a response");
      err.put("retryable", false);
      return new KernelMessage(null, null, null, err, true, true);
    }
  }
}
