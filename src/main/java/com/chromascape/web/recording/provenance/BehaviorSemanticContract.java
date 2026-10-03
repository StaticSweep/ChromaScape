package com.chromascape.web.recording.provenance;

import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Artifact-derived semantic IDs/order/dimensions/meanings/masks; no independent consumer counts.
 */
public final class BehaviorSemanticContract {
  private static final JsonNode CONTRACT = load();

  private BehaviorSemanticContract() {}

  private static JsonNode load() {
    try (var input =
        BehaviorSemanticContract.class.getResourceAsStream(
            "/contracts/behavior-semantic-v1.json")) {
      return new ObjectMapper().readTree(input);
    } catch (Exception e) {
      throw new IllegalStateException("Missing canonical semantic contract", e);
    }
  }

  public static JsonNode definition() {
    return CONTRACT.deepCopy();
  }

  public static String digest() {
    return Micro6CanonicalHash.sha256WithPrefix(CONTRACT);
  }

  public static List<String> actionIds() {
    return StreamSupport.stream(CONTRACT.path("actions").spliterator(), false)
        .map(x -> x.path("id").asText())
        .toList();
  }

  public static void conform(JsonNode candidate) {
    if (!CONTRACT.equals(candidate)) throw new ProvenanceException("SEMANTIC_CONTRACT_MISMATCH");
  }

  public static void requireDigest(String digest) {
    if (!digest().equals(digest)) throw new ProvenanceException("SEMANTIC_CONTRACT_MISMATCH");
  }
}
