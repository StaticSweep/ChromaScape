package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.recording.provenance.ProvenanceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bounded producer-reported identity. This is provenance, not signed runtime attestation. */
public final class DebuggerCaptureIdentity {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "clientBuildId",
          "clientVersion",
          "artifactSha256",
          "gameRevision",
          "cacheRevision",
          "cacheRevisionSource",
          "qualityCodes");

  private DebuggerCaptureIdentity() {}

  /** Validates and canonicalizes optional producer identity without retaining arbitrary fields. */
  public static ObjectNode validateClient(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()
        || value.toString().getBytes(StandardCharsets.UTF_8).length > 8192
        || !"debugger-client-identity/v1".equals(value.path("schema").asText())) {
      throw invalid();
    }
    value
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!FIELDS.contains(key)) {
                throw invalid();
              }
            });
    for (String key : Set.of("clientBuildId", "clientVersion", "artifactSha256")) {
      JsonNode field = value.get(key);
      if (field != null
          && !field.isNull()
          && (!field.isTextual() || !field.asText().matches("[A-Za-z0-9._+:/-]{1,160}"))) {
        throw invalid();
      }
    }
    if (value.hasNonNull("artifactSha256")
        && !value.get("artifactSha256").asText().matches("[a-f0-9]{64}")) {
      throw invalid();
    }
    for (String key : Set.of("gameRevision", "cacheRevision")) {
      JsonNode field = value.get(key);
      if (field != null
          && !field.isNull()
          && (!field.isIntegralNumber()
              || !field.canConvertToInt()
              || field.intValue() < 1
              || field.intValue() > 1000000)) {
        throw invalid();
      }
    }
    if (!"CLIENT_GET_REVISION".equals(value.path("cacheRevisionSource").asText())
        || !value.path("gameRevision").equals(value.path("cacheRevision"))) {
      throw invalid();
    }
    JsonNode codes = value.get("qualityCodes");
    if (codes == null || !codes.isArray() || codes.size() > 16) {
      throw invalid();
    }
    for (JsonNode code : codes) {
      if (!code.isTextual() || !code.asText().matches("[A-Z_]{1,80}")) {
        throw invalid();
      }
    }
    // Canonicalize omitted and explicit-null optional fields for reconnect equality.
    ObjectNode result = JSON.createObjectNode();
    result.put("schema", "debugger-client-identity/v1");
    for (String key :
        Set.of(
            "clientBuildId",
            "clientVersion",
            "artifactSha256",
            "gameRevision",
            "cacheRevision",
            "cacheRevisionSource")) {
      result.set(key, value.has(key) ? value.get(key).deepCopy() : JSON.nullNode());
    }
    var reasons = new java.util.TreeSet<String>();
    if (!result.hasNonNull("clientBuildId")) {
      reasons.add("CLIENT_BUILD_MISSING");
    }
    if (!result.hasNonNull("artifactSha256")) {
      reasons.add("CLIENT_ARTIFACT_MISSING");
    }
    if (!result.hasNonNull("gameRevision")) {
      reasons.add("CLIENT_REVISION_MISSING");
    }
    // Preserve bounded producer diagnostics as well as independently derived missing fields.
    java.util.stream.StreamSupport.stream(codes.spliterator(), false)
        .map(JsonNode::asText)
        .forEach(reasons::add);
    var outputCodes = result.putArray("qualityCodes");
    reasons.forEach(outputCodes::add);
    return result;
  }

  /** Freezes runtime/schema identity when the actual recorder first binds the session. */
  public static ObjectNode snapshot(ObjectNode client, int tickBatchVersion) {
    ObjectNode result = JSON.createObjectNode();
    result.put("schema", "debugger-recording-identity/v1");
    result.set("client", client.deepCopy());
    result.set("server", DebuggerServerBuildIdentity.snapshot());
    result.put("tickBatchVersion", tickBatchVersion);
    result.put("combatRowSchema", "combat-tick-row/v1");
    result.put("captureQualitySchema", "capture-quality/v1");
    result.put("videoIndexSchema", "measured-video-index/v1");
    return result;
  }

  private static ProvenanceException invalid() {
    return new ProvenanceException("INVALID_CAPTURE_IDENTITY");
  }
}
