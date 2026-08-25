package com.chromascape.web.replay.kernel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes and decodes newline-delimited JSON-RPC messages for the
 * xRSPS kernel stdio protocol. Malformed lines are surfaced as fatal
 * messages rather than silently dropped.
 */
public class KernelProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(KernelProtocolCodec.class);
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

    public KernelMessage decode(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(line);
            if (!node.isObject()) {
                log.warn("Kernel emitted a non-object JSON line; treating as protocol violation");
                return KernelMessage.fatal("INTERNAL_ERROR", "Non-object response line");
            }
            String id = node.hasNonNull("id") ? node.get("id").asText() : null;
            String method = node.hasNonNull("method") ? node.get("method").asText() : null;
            JsonNode result = node.has("result") ? node.get("result") : null;
            JsonNode error = node.has("error") ? node.get("error") : null;
            return new KernelMessage(id, method, result, error, false, false);
        } catch (Exception e) {
            log.warn("Failed to decode kernel message; treating as protocol violation: {}", e.getMessage());
            return KernelMessage.fatal("INTERNAL_ERROR", "Malformed JSON from kernel");
        }
    }

    /**
     * One decoded kernel message. Fatal messages terminate request waits.
     */
    public record KernelMessage(String id, String method, JsonNode result, JsonNode error,
                                boolean fatal, boolean processExited) {
        public boolean isResponse() { return result != null || error != null || fatal || processExited; }
        public boolean isError() { return error != null || fatal || processExited; }

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
