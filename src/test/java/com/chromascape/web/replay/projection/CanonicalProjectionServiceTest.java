package com.chromascape.web.replay.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Golden-vector parity with the TypeScript canonical JSON + hashing implementation
 * (replay-kernel/src/observation/*). If either side changes its canonical form these vectors must
 * be regenerated on both sides.
 */
class CanonicalProjectionServiceTest {

  private final CanonicalProjectionService service = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void canonicalJsonMatchesTypeScriptGoldenVector() throws Exception {
    String tsCanonical =
        "{\"npcs\":[{\"npcId\":1531,\"position\":{\"plane\":0,\"x\":110,\"y\":105},"
            + "\"stableId\":\"spot:b\"},{\"npcId\":1530,\"position\":{\"plane\":0,\"x\":103,\"y\":101},"
            + "\"stableId\":\"spot:a\"}],\"player\":{\"equipment\":[],\"inventory\":"
            + "[{\"itemId\":317,\"quantity\":1,\"slot\":0}],\"position\":{\"plane\":0,\"x\":3080,\"y\":3227},"
            + "\"skills\":{\"fishing\":{\"level\":20,\"xp\":4000}}},\"projectionVersion\":1,\"tick\":42}";
    JsonNode observation =
        mapper.readTree(
            String.join(
                "",
                "{",
                "\"tick\":42,",
                "\"player\":{",
                "\"position\":{\"x\":3080,\"y\":3227,\"plane\":0},",
                "\"skills\":{\"fishing\":{\"level\":20,\"xp\":4000}},",
                "\"inventory\":[{\"slot\":0,\"itemId\":317,\"quantity\":1}],",
                "\"equipment\":[]",
                "},",
                "\"npcs\":[{\"stableId\":\"spot:b\",\"npcId\":1531,\"position\":{\"x\":110,\"y\":105,\"plane\":0}},",
                "{\"stableId\":\"spot:a\",\"npcId\":1530,\"position\":{\"x\":103,\"y\":101,\"plane\":0}}],",
                "\"projectionVersion\":1",
                "}"));
    assertEquals(tsCanonical, service.canonicalJson(observation));
  }

  @Test
  void stateHashMatchesTypeScriptGoldenVector() throws Exception {
    JsonNode observation =
        mapper.readTree(
            String.join(
                "",
                "{",
                "\"tick\":42,",
                "\"player\":{",
                "\"position\":{\"x\":3080,\"y\":3227,\"plane\":0},",
                "\"skills\":{\"fishing\":{\"level\":20,\"xp\":4000}},",
                "\"inventory\":[{\"slot\":0,\"itemId\":317,\"quantity\":1}],",
                "\"equipment\":[]",
                "},",
                "\"npcs\":[{\"stableId\":\"spot:b\",\"npcId\":1531,\"position\":{\"x\":110,\"y\":105,\"plane\":0}},",
                "{\"stableId\":\"spot:a\",\"npcId\":1530,\"position\":{\"x\":103,\"y\":101,\"plane\":0}}],",
                "\"projectionVersion\":1",
                "}"));
    assertEquals(
        "sha256:5ec9f48e80e8f9e6b0b1e667edaad5999b795435a23610b4c060cd6821c81f56",
        service.stateHash(observation));
  }

  @Test
  void chainHashIsByteConcatenationPerSpec() {
    String prev = "sha256:" + "11".repeat(32);
    String action = "sha256:" + "22".repeat(32);
    String state = "sha256:" + "33".repeat(32);
    // Golden vector from the TypeScript implementation.
    assertEquals(
        "sha256:8cfa0e55b092114ec1d274d633d317f8dbce497239ae76722c0add142278ff5e",
        service.chainHash(prev, action, state));
  }

  @Test
  void chainHashFailsClosedOnMalformedInput() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.chainHash(
                "not-a-hash", "sha256:" + "22".repeat(32), "sha256:" + "33".repeat(32)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.chainHash(
                "sha256:short", "sha256:" + "22".repeat(32), "sha256:" + "33".repeat(32)));
  }

  @Test
  void keyOrderIndependence() throws Exception {
    JsonNode a = mapper.readTree("{\"x\":1,\"y\":{\"p\":true,\"q\":\"s\"}}");
    JsonNode b = mapper.readTree("{\"y\":{\"q\":\"s\",\"p\":true},\"x\":1}");
    assertEquals(service.canonicalJson(a), service.canonicalJson(b));
  }

  @ParameterizedTest
  @CsvSource({
    "13034448.5,13034448.5", "-13034448.5,-13034448.5", "10000000.25,10000000.25",
    "9999999.25,9999999.25", "0.000001,0.000001", "0.0000001,1e-7",
    "-0.0000001,-1e-7", "0.000000123,1.23e-7", "0.000000001,1e-9",
    "0.0000000001,0", "-0.0,0", "17.5,17.5"
  })
  void nativeXpAndSmallFractionNumbersMatchTypeScriptV1Notation(String input, String canonical)
      throws Exception {
    assertEquals(canonical, service.canonicalJson(mapper.readTree(input)));
  }
}
