package com.chromascape.web.replay.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelProtocolCodecTest {

    private final KernelProtocolCodec codec = new KernelProtocolCodec(new ObjectMapper());

    @Test
    void encodeProducesSingleLineJsonWithId() {
        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("expectedTick", 5);
        String line = codec.encode("42", "step", params);
        assertEquals('}', line.charAt(line.length() - 2), "newline only at end");
        assertTrue(line.contains("\"id\":\"42\""));
        assertTrue(line.contains("\"method\":\"step\""));
    }

    @Test
    void decodeRoundTripsResponse() {
        KernelProtocolCodec.KernelMessage msg = codec.decode(
                "{\"id\":\"7\",\"ok\":true,\"result\":{\"initialTick\":0}}");
        assertEquals("7", msg.id());
        assertTrue(msg.isResponse());
        assertFalse(msg.isError());
        assertEquals(0, msg.result().path("initialTick").asInt());
    }

    @Test
    void decodeSurfacesMalformedJsonAsFatalInsteadOfDropping() {
        KernelProtocolCodec.KernelMessage msg = codec.decode("this is not json");
        assertNotNull(msg);
        assertTrue(msg.isError());
        assertTrue(msg.fatal());
    }

    @Test
    void decodeErrorMessageCarriesCode() {
        KernelProtocolCodec.KernelMessage msg = codec.decode(
                "{\"id\":\"3\",\"ok\":false,\"error\":{\"code\":\"TICK_MISMATCH\",\"message\":\"x\",\"retryable\":false}}");
        assertTrue(msg.isError());
        assertEquals("TICK_MISMATCH", msg.error().path("code").asText());
    }
}
