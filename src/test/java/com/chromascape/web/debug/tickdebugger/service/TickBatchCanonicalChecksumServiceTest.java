package com.chromascape.web.debug.tickdebugger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class TickBatchCanonicalChecksumServiceTest {
  private static final String EXPECTED_CANONICAL =
      "{\"batchId\":\"00000000-0000-4000-8000-000000001199\",\"playerState\":{\"array\":[null,2,0,123456789012345678901234567890.12345],\"html\":\"<>&'=\",\"nested\":{\"n\":1,\"payloadChecksum\":\"retain\"},\"nullValue\":null,\"unicode\":\"café\"},\"schemaVersion\":2,\"sessionId\":\"session-1199\",\"tick\":42,\"timestamp\":\"2026-09-03T00:00:00Z\",\"type\":\"TICK_BATCH\"}";
  private static final String EXPECTED_CHECKSUM =
      "sha256:97421289e3c98d56255aedb307751eb203944be5694b6c40587c2843f464c214";

  @Test
  void goldenVectorMatchesCanonicalDocumentAndChecksum() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    TickBatchCanonicalChecksumService service = new TickBatchCanonicalChecksumService(mapper);
    String wire = readResource("tick-batch-v2-golden.json");
    JsonNode document = service.parseWireJson(wire);

    assertEquals(EXPECTED_CANONICAL, service.canonicalJson(document));
    assertEquals(EXPECTED_CHECKSUM, service.checksum(document));
    assertEquals(EXPECTED_CHECKSUM, service.checksumForWireJson(wire));
    assertTrue(service.canonicalJson(document).contains("\"payloadChecksum\":\"retain\""));
  }

  @Test
  void canonicalVectorsMatchIndependentExpectations() throws IOException {
    TickBatchCanonicalChecksumService service = service();
    JsonNode document = vectors("tick-batch-canonical-vectors.json");
    int asserted = 0;
    Iterator<JsonNode> vectors = document.get("vectors").elements();
    while (vectors.hasNext()) {
      JsonNode vector = vectors.next();
      String name = vector.get("name").asText();
      String wire = vector.get("wire").asText();
      JsonNode parsed = service.parseWireJson(wire);
      assertEquals(
          vector.get("canonical").asText(),
          service.canonicalJson(parsed),
          "canonical mismatch: " + name);
      assertEquals(
          vector.get("checksum").asText(), service.checksum(parsed), "checksum mismatch: " + name);
      assertEquals(
          vector.get("checksum").asText(),
          service.checksumForWireJson(wire),
          "wire checksum mismatch: " + name);
      asserted++;
    }
    assertTrue(asserted >= 20, "expected at least 20 canonical vectors, saw " + asserted);
  }

  @Test
  void decimalAndZeroFormsCanonicalizeIdentically() {
    TickBatchCanonicalChecksumService service = service();
    assertEquals(
        service.checksumForWireJson("{\"v\":1}"), service.checksumForWireJson("{\"v\":1.0}"));
    assertEquals(
        service.checksumForWireJson("{\"v\":1}"), service.checksumForWireJson("{\"v\":1.0000}"));
    assertEquals(
        service.checksumForWireJson("{\"v\":0}"), service.checksumForWireJson("{\"v\":-0}"));
    assertEquals(
        service.checksumForWireJson("{\"v\":0}"), service.checksumForWireJson("{\"v\":-0.0}"));
    assertEquals(
        service.checksumForWireJson("{\"v\":0.000000123}"),
        service.checksumForWireJson("{\"v\":1.23e-7}"));
  }

  @Test
  void nestedOrderingCanonicalizesIdenticallyWhileArrayOrderIsPreserved() {
    TickBatchCanonicalChecksumService service = service();
    assertEquals(
        service.checksumForWireJson("{\"z\":{\"b\":1,\"a\":2},\"a\":{\"y\":[3,2],\"x\":1}}"),
        service.checksumForWireJson("{\"a\":{\"x\":1,\"y\":[3,2]},\"z\":{\"a\":2,\"b\":1}}"));
    assertTrue(
        !service
            .checksumForWireJson("{\"arr\":[1,2,3]}")
            .equals(service.checksumForWireJson("{\"arr\":[3,2,1]}")),
        "array order must be preserved");
  }

  @Test
  void rootPayloadChecksumIsRemovedButNestedValuesArePreserved() {
    TickBatchCanonicalChecksumService service = service();
    String wire =
        "{\"payloadChecksum\":\"sha256:"
            + "0".repeat(64)
            + "\",\"nested\":{\"payloadChecksum\":\"retain\",\"n\":1}}";
    String canonical = service.canonicalJson(service.parseWireJson(wire));
    assertEquals("{\"nested\":{\"n\":1,\"payloadChecksum\":\"retain\"}}", canonical);
  }

  @Test
  void malformedSurrogateInputIsRejected() {
    TickBatchCanonicalChecksumService service = service();
    assertThrows(
        IllegalArgumentException.class, () -> service.checksumForWireJson("{\"s\":\"\\ud800\"}"));
  }

  @Test
  void numbersNeverPassThroughDouble() {
    TickBatchCanonicalChecksumService service = service();
    String canonical =
        service.canonicalJson(
            service.parseWireJson("{\"v\":123456789012345678901234567890.12345}"));
    assertEquals("{\"v\":123456789012345678901234567890.12345}", canonical);
  }

  @Test
  void realisticFishingBatchMatchesIndependentChecksum() throws IOException {
    TickBatchCanonicalChecksumService service = service();
    String wire = readResource("tick-batch-fishing-golden.json");
    JsonNode document = service.parseWireJson(wire);
    assertEquals(document.get("payloadChecksum").asText(), service.checksum(document));
  }

  @Test
  void actionVectorsMatchProductionChecksum() throws IOException {
    TickBatchCanonicalChecksumService service = service();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode document = vectors("recording-action-checksum-vectors.json");
    int asserted = 0;
    Iterator<JsonNode> vectors = document.get("vectors").elements();
    while (vectors.hasNext()) {
      JsonNode vector = vectors.next();
      String name = vector.get("name").asText();
      // Rebuild the recording-action/v1 content document through the production field set and
      // run the shared canonical checksum: the digest must equal the fixture checksum.
      JsonNode canonical = mapper.readTree(vector.get("canonical").asText());
      ObjectNode content = mapper.createObjectNode();
      content.put("schema", canonical.get("schema").asText());
      if (canonical.get("recordingId").isNull()) content.putNull("recordingId");
      else content.put("recordingId", canonical.get("recordingId").asText());
      content.put("sessionId", canonical.get("sessionId").asText());
      content.put("tick", canonical.get("tick").asLong());
      content.put("sequence", canonical.get("sequence").asInt());
      content.put("actionType", canonical.get("actionType").asText());
      content.set("target", canonical.get("target"));
      content.set("arguments", canonical.get("arguments"));
      content.put("source", canonical.get("source").asText());
      content.put("provenance", canonical.get("provenance").asText());
      content.put("confidence", canonical.get("confidence").decimalValue());
      content.put("capturedAt", canonical.get("capturedAt").asText());
      assertEquals(
          vector.get("checksum").asText(),
          service.checksum(content),
          "production checksum mismatch: " + name);
      asserted++;
    }
    assertTrue(asserted >= 4, "expected at least 4 action vectors, saw " + asserted);
  }

  @Test
  void fixtureManifestListsIdenticalCrossRepositoryHashes() throws Exception {
    // Portable certification: each repository asserts the FIXED expected bytes of every shared
    // fixture instead of probing the sibling checkout. A separate explicit script compares the
    // two checkouts and fails when either path is missing.
    JsonNode manifest = vectors("tick-batch-fixture-manifest.json");
    int asserted = 0;
    Iterator<JsonNode> fixtures = manifest.get("fixtures").elements();
    while (fixtures.hasNext()) {
      JsonNode fixture = fixtures.next();
      String name = fixture.get("name").asText();
      assertEquals(
          fixture.get("sha256").asText(),
          sha256(readResource(name)),
          "fixture " + name + " must match its manifest hash");
      asserted++;
    }
    assertTrue(asserted >= 4, "expected at least 4 fixtures in the manifest, saw " + asserted);
  }

  @Test
  void checksumDocumentShapeMatchesMicro6ActionContract() {
    TickBatchCanonicalChecksumService service = service();
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode content = mapper.createObjectNode();
    content.put("schema", "recording-action/v1");
    content.put("recordingId", "recording-1");
    content.put("sessionId", "session-1");
    content.put("tick", 12L);
    content.put("sequence", 3);
    content.put("actionType", "INTERACT_NPC");
    content.set(
        "target", mapper.valueToTree(Map.of("kind", "NPC", "stableId", "npc:1518:3200:3200:0")));
    content.set("arguments", mapper.valueToTree(Map.of("option", "net")));
    content.put("source", "MICRO6_SEMANTIC_DISPATCH");
    content.put("provenance", "CAPTURED");
    content.put("confidence", new java.math.BigDecimal("1"));
    content.put("capturedAt", "2026-08-31T22:00:00Z");
    assertEquals(
        "sha256:4ef4c963fe183e1c91ac29e8d612a3e61e4a2f6dd3033fe109e0a9afc97e3547",
        service.checksum(content));
    ObjectNode nulled = content.deepCopy();
    nulled.putNull("recordingId");
    assertEquals(
        "sha256:64b8c288a09d24a49f20048b03d17282e0a258bf94e8436fb129d0cc1ce0e6f9",
        service.checksum(nulled));
  }

  private static TickBatchCanonicalChecksumService service() {
    return new TickBatchCanonicalChecksumService(new ObjectMapper());
  }

  private static JsonNode vectors(String name) throws IOException {
    return new ObjectMapper().readTree(readResource(name));
  }

  private static String readResource(String name) throws IOException {
    return new String(
        Objects.requireNonNull(
                TickBatchCanonicalChecksumServiceTest.class.getResourceAsStream("/" + name))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }

  private static String sha256(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(digest.length * 2);
    for (byte item : digest) {
      result.append(String.format("%02x", item & 0xff));
    }
    return result.toString();
  }
}
