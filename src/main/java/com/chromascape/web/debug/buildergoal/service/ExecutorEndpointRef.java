package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Versioned allocation capability received exclusively over the authenticated registry channel. */
public record ExecutorEndpointRef(
    String id,
    String allocationId,
    long generation,
    String machineUuid,
    String agentSession,
    String executorInstance,
    String artifactDigest,
    String environment,
    Instant issuedAt,
    Instant expiresAt) {
  public static ExecutorEndpointRef from(JsonNode n, String environment) {
    try {
      if (n == null
          || n.path("endpoint_contract_version").asInt() != 1
          || !"leeuwbotapi".equals(n.path("issuer").asText())
          || !"chromascape".equals(n.path("audience").asText())
          || !"micro6".equals(n.path("client_type").asText())
          || !environment.equals(n.path("environment").asText())
          || !n.path("artifact_sha256").asText().matches("sha256:[0-9a-f]{64}")) return null;
      for (String k :
          java.util.List.of(
              "endpoint_ref",
              "allocation_id",
              "machine_uuid",
              "session_id",
              "executor_instance_id"))
        if (!n.path(k).isTextual() || !n.path(k).asText().matches("[A-Za-z0-9_.:-]{1,256}"))
          return null;
      long generation = n.path("fencing_generation").asLong();
      Instant issued = Instant.parse(n.path("issued_at").asText());
      Instant expires = Instant.parse(n.path("expires_at").asText());
      Instant now = Instant.now();
      if (generation < 1
          || issued.isAfter(now.plusSeconds(30))
          || !expires.isAfter(now)
          || expires.isAfter(issued.plusSeconds(120))) return null;
      return new ExecutorEndpointRef(
          n.path("endpoint_ref").asText(),
          n.path("allocation_id").asText(),
          generation,
          n.path("machine_uuid").asText(),
          n.path("session_id").asText(),
          n.path("executor_instance_id").asText(),
          n.path("artifact_sha256").asText(),
          environment,
          issued,
          expires);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  public boolean matches(JsonNode n) {
    return n != null
        && id.equals(n.path("endpoint_ref").asText())
        && allocationId.equals(n.path("allocation_id").asText())
        && generation == n.path("fencing_generation").asLong()
        && artifactDigest.equals(n.path("artifact_sha256").asText());
  }
}
