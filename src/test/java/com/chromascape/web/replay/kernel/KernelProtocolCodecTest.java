package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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
    KernelProtocolCodec.KernelMessage msg =
        codec.decode("{\"id\":\"7\",\"ok\":true,\"result\":{\"initialTick\":0}}");
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
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"3\",\"ok\":false,\"error\":{\"code\":\"TICK_MISMATCH\",\"message\":\"x\",\"retryable\":false}}");
    assertTrue(msg.isError());
    assertEquals("TICK_MISMATCH", msg.error().path("code").asText());
  }

  // ── exact TypeScript worker envelope contract ──

  @Test
  void capabilitiesEnvelopeFromCompiledWorkerDecodesAtResultRoot() {
    // Exact shape emitted by request-router.ts: {id, ok, result:{capabilities}}
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"1\",\"ok\":true,\"result\":{\"capabilities\":{"
                + "\"modes\":{\"causalSimulation\":true,\"policyReplay\":false},"
                + "\"xrsps\":{\"adapterVersion\":\"1.0.0\",\"buildCommit\":\"abc\","
                + "\"cacheRevision\":\"r1\",\"contentRevision\":\"c1\"}}}}");
    assertFalse(msg.isError());
    assertEquals("1", msg.id());
    // Result is read at the ROOT — never through a nested "result" node.
    assertTrue(
        msg.result().path("capabilities").path("modes").path("causalSimulation").asBoolean(false));
    assertEquals(
        "1.0.0", msg.result().path("capabilities").path("xrsps").path("adapterVersion").asText());
  }

  @Test
  void resetEnvelopeCarriesInitialTickAndEventsAtResultRoot() {
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"2\",\"ok\":true,\"result\":{\"initialTick\":0,"
                + "\"initialObservation\":{\"player\":{\"position\":{\"x\":1}}},\"events\":[]}}");
    assertFalse(msg.isError());
    assertEquals(0, msg.result().path("initialTick").asLong(-1));
    assertTrue(msg.result().path("events").isArray());
    assertTrue(
        msg.result().path("result").isMissingNode(),
        "no nested result node exists in the canonical envelope");
  }

  @Test
  void stepEnvelopeCarriesTerminalTickObservationEvents() {
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"3\",\"ok\":true,\"result\":{\"terminalTick\":5,"
                + "\"observation\":{},\"events\":[],\"actionApplied\":true}}");
    assertEquals(5, msg.result().path("terminalTick").asLong());
    assertTrue(msg.result().path("observation").isObject());
    assertTrue(msg.result().path("events").isArray());
  }

  @Test
  void observeSnapshotTraceEnvelopesDecodeAtResultRoot() {
    KernelProtocolCodec.KernelMessage observe =
        codec.decode("{\"id\":\"4\",\"ok\":true,\"result\":{\"observation\":{\"player\":{}}}}");
    assertTrue(observe.result().path("observation").isObject());

    KernelProtocolCodec.KernelMessage snapshot =
        codec.decode(
            "{\"id\":\"5\",\"ok\":true,\"result\":{\"snapshot\":{\"snapshotVersion\":1,\"tick\":9,"
                + "\"eventQueue\":{\"nextOrdinal\":7},\"checksum\":\"sha256:aa\"}}}");
    assertTrue(snapshot.result().path("snapshot").isObject());
    assertEquals(
        7, snapshot.result().path("snapshot").path("eventQueue").path("nextOrdinal").asLong());

    KernelProtocolCodec.KernelMessage trace =
        codec.decode(
            "{\"id\":\"6\",\"ok\":true,\"result\":{\"ordinals\":{\"first\":0,\"last\":3},\"events\":[]}}");
    assertTrue(trace.result().has("ordinals"));
    assertTrue(trace.result().has("events"));
  }

  // ── protocol violations fail closed ──

  @Test
  void missingIdIsFatalProtocolError() {
    KernelProtocolCodec.KernelMessage msg = codec.decode("{\"ok\":true,\"result\":{}}");
    assertTrue(msg.fatal());
    assertEquals(KernelProtocolCodec.PROTOCOL_ERROR_CODE, msg.error().path("code").asText());
  }

  @Test
  void nonTextualIdIsFatal() {
    KernelProtocolCodec.KernelMessage msg = codec.decode("{\"id\":17,\"ok\":true,\"result\":{}}");
    assertTrue(msg.fatal());
  }

  @Test
  void okTrueWithoutResultIsFatal() {
    KernelProtocolCodec.KernelMessage msg = codec.decode("{\"id\":\"1\",\"ok\":true}");
    assertTrue(msg.fatal());
    assertEquals(KernelProtocolCodec.PROTOCOL_ERROR_CODE, msg.error().path("code").asText());
  }

  @Test
  void okFalseWithoutErrorIsFatal() {
    KernelProtocolCodec.KernelMessage msg = codec.decode("{\"id\":\"1\",\"ok\":false}");
    assertTrue(msg.fatal());
  }

  @Test
  void okTrueWithErrorIsFatal() {
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"1\",\"ok\":true,\"result\":{},\"error\":{\"code\":\"X\",\"message\":\"y\"}}");
    assertTrue(msg.fatal());
    assertEquals(KernelProtocolCodec.PROTOCOL_ERROR_CODE, msg.error().path("code").asText());
  }

  @Test
  void okFalseWithResultIsFatal() {
    KernelProtocolCodec.KernelMessage msg =
        codec.decode(
            "{\"id\":\"1\",\"ok\":false,\"result\":{},\"error\":{\"code\":\"X\",\"message\":\"y\"}}");
    assertTrue(msg.fatal());
  }

  @Test
  void missingOkFlagIsFatal() {
    KernelProtocolCodec.KernelMessage msg = codec.decode("{\"id\":\"1\",\"result\":{}}");
    assertTrue(msg.fatal());
    assertEquals(KernelProtocolCodec.PROTOCOL_ERROR_CODE, msg.error().path("code").asText());
  }

  @Test
  void blankLineReturnsNullWithoutNoise() {
    assertNull(codec.decode(""));
    assertNull(codec.decode(null));
    assertNull(codec.decode("   "));
  }
}
