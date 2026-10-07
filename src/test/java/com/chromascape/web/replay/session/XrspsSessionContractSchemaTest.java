package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XrspsSessionContractSchemaTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode load(String resource) throws Exception {
    try (var input = getClass().getResourceAsStream(resource)) {
      assertNotNull(input, "contract schema missing: " + resource);
      return mapper.readTree(input);
    }
  }

  @Test
  void sessionSchemaPinsLifecycleAndIdentity() throws Exception {
    JsonNode schema = load("/contracts/replay/xrsps-session-v1.schema.json");
    assertEquals("xRSPS Session", schema.path("title").asText());
    Set<String> required = new HashSet<>();
    schema.path("required").forEach(node -> required.add(node.asText()));
    for (String field :
        new String[] {
          "id",
          "mode",
          "status",
          "tick",
          "observationRevision",
          "epoch",
          "idempotencyKey",
          "scenarioRef",
          "leaseId"
        }) {
      assertTrue(required.contains(field), "session schema must require " + field);
    }
    Set<String> modes = new HashSet<>();
    schema.path("properties").path("mode").path("enum").forEach(node -> modes.add(node.asText()));
    assertEquals(Set.of("RECORDED_ACTIONS", "AI_TASK"), modes);
    Set<String> statuses = new HashSet<>();
    schema
        .path("properties")
        .path("status")
        .path("enum")
        .forEach(node -> statuses.add(node.asText()));
    assertEquals(
        Set.of(
            "CREATING",
            "READY",
            "RUNNING",
            "PAUSED",
            "RECONCILING",
            "SUCCEEDED",
            "FAILED",
            "CANCELLED"),
        statuses);
  }

  @Test
  void preflightSchemaPinsBlockCodes() throws Exception {
    JsonNode schema = load("/contracts/replay/xrsps-session-preflight-v1.schema.json");
    assertEquals("xRSPS Session Preflight", schema.path("title").asText());
    Set<String> codes = new HashSet<>();
    schema
        .path("properties")
        .path("reasons")
        .path("items")
        .path("properties")
        .path("code")
        .path("enum")
        .forEach(node -> codes.add(node.asText()));
    for (XrspsSessionPreflight.BlockCode code : XrspsSessionPreflight.BlockCode.values()) {
      assertTrue(codes.contains(code.name()), "preflight schema must cover " + code);
    }
  }
}
