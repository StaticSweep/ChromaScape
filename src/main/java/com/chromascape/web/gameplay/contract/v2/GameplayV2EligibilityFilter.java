package com.chromascape.web.gameplay.contract.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.EligibilityEntry;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.EligibilityStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic capability and prerequisite eligibility filter. Runs strictly before any provider
 * adapter; adapters only ever see its closed eligible output and cannot resurrect a filtered task.
 * No eligibility result grants execution authority.
 */
@Component
public final class GameplayV2EligibilityFilter {
  private static final double MINIMUM_KNOWLEDGE_CONFIDENCE = 0.5;
  private static final double MINIMUM_OBSERVATION_CONFIDENCE = 0.5;
  private static final java.util.Set<String> SUPPORTED_CAPABILITIES =
      java.util.Set.of("FISHING_SEMANTIC_ACTION", "CLIENT_OBSERVATION");

  /**
   * Concrete immutable capability snapshot for the bounded Fishing bootstrap. The snapshot object
   * itself is part of the golden fixtures; its canonical digest is the only accepted {@code
   * capabilitySnapshotDigest} across catalog, observation, planning input and envelope. This binds
   * the hard-coded executor set to an independently provable snapshot instead of an opaque digest.
   */
  public static final java.util.List<String> CANONICAL_CAPABILITIES =
      java.util.List.of("CLIENT_OBSERVATION", "FISHING_SEMANTIC_ACTION");

  private final ObjectMapper objectMapper;

  public GameplayV2EligibilityFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Emits one closed-status entry per catalog task. Checks run in a fixed order: identity, stale
   * observation, stale knowledge, then per-task capability, policy, budget, eligibility, and
   * prerequisite checks.
   */
  public JsonNode filter(
      JsonNode catalog, JsonNode observation, JsonNode planningInput, Instant evaluatedAt) {
    String reason = globalRejectionReason(catalog, observation, planningInput, evaluatedAt);
    EligibilityStatus global = reason == null ? null : EligibilityStatus.valueOf(reason);
    ObjectNode result = objectMapper.createObjectNode();
    result.put("contractVersion", GameplayV2Contracts.VERSION);
    result.put("mode", planningInput.path("mode").asText());
    result.put("catalogDigest", planningInput.path("catalogDigest").asText());
    result.put("observationDigest", planningInput.path("observationDigest").asText());
    result.put("evaluatedAt", evaluatedAt.toString());
    ArrayNode entries = result.putArray("entries");
    for (JsonNode task : catalog.path("tasks")) {
      String taskKey = task.path("taskKey").asText();
      EligibilityStatus status =
          global != null ? global : taskStatus(task, observation, planningInput);
      ObjectNode entry = entries.addObject();
      entry.put("taskKey", taskKey);
      entry.put("status", status.name());
    }
    return result;
  }

  private String globalRejectionReason(
      JsonNode catalog, JsonNode observation, JsonNode planningInput, Instant evaluatedAt) {
    // Incomplete or low-confidence observations fail closed before any task reasoning.
    if (!observation.path("observationComplete").asBoolean(false)) {
      return EligibilityStatus.STALE_OBSERVATION.name();
    }
    if (observation.path("adapterObservationConfidence").asDouble(0.0)
        < MINIMUM_OBSERVATION_CONFIDENCE) {
      return EligibilityStatus.STALE_OBSERVATION.name();
    }
    JsonNode freshness = observation.path("freshness");
    if ("STALE".equals(freshness.path("status").asText())
        || Instant.parse(observation.path("capturedAt").asText())
            .plusMillis(freshness.path("maxAgeMs").asLong())
            .isBefore(evaluatedAt)) {
      return EligibilityStatus.STALE_OBSERVATION.name();
    }
    if (observation.path("knowledgeFreshnessConfidence").asDouble() < MINIMUM_KNOWLEDGE_CONFIDENCE
        || !planningInput
            .path("knowledgeDigest")
            .asText()
            .equals(observation.path("knowledgeDigest").asText())) {
      return EligibilityStatus.STALE_KNOWLEDGE.name();
    }
    if (!planningInput
        .path("policyDigest")
        .asText()
        .equals(observation.path("policyDigest").asText())) {
      return EligibilityStatus.POLICY_DENIED.name();
    }
    // Planning deadline is a hard planning-input validity bound.
    try {
      if (!Instant.parse(planningInput.path("deadline").asText("")).isAfter(evaluatedAt)) {
        return EligibilityStatus.POLICY_DENIED.name();
      }
    } catch (Exception exception) {
      return EligibilityStatus.IDENTITY_MISMATCH.name();
    }
    // Catalog revision must agree across catalog, observation, and planning input.
    long catalogRevision = catalog.path("catalogRevision").asLong(-1);
    if (observation.path("catalogRevision").asLong(-2) != catalogRevision
        || planningInput.path("catalogRevision").asLong(-3) != catalogRevision) {
      return EligibilityStatus.IDENTITY_MISMATCH.name();
    }
    // Planning and observation identities must be exactly the same run.
    if (!canonicalDigest(objectMapper, planningInput.path("identities"))
        .equals(canonicalDigest(objectMapper, observation.path("identities")))) {
      return EligibilityStatus.IDENTITY_MISMATCH.name();
    }
    // Contradictory planning inputs fail closed: the same task cannot be both
    // eligible and rejected, and a rejected entry must never claim ELIGIBLE.
    java.util.Set<String> eligibleKeys = new java.util.HashSet<>();
    for (JsonNode key : planningInput.path("eligibleTaskKeys")) {
      eligibleKeys.add(key.asText());
    }
    for (JsonNode rejected : planningInput.path("rejectedEntries")) {
      String taskKey = rejected.path("taskKey").asText();
      String reason = rejected.path("reason").asText();
      if ("ELIGIBLE".equals(reason) || eligibleKeys.contains(taskKey)) {
        return EligibilityStatus.IDENTITY_MISMATCH.name();
      }
    }
    if (!planningInput.path("mode").asText().equals(observation.path("mode").asText())
        || !planningInput
            .path("catalogDigest")
            .asText()
            .equals(observation.path("catalogDigest").asText())
        || !planningInput
            .path("catalogDigest")
            .asText()
            .equals(catalog.path("catalogDigest").asText())
        || !planningInput
            .path("observationDigest")
            .asText()
            .equals(observationDigest(objectMapper, observation))
        || !planningInput
            .path("capabilitySnapshotDigest")
            .asText()
            .equals(catalog.path("capabilitySnapshotDigest").asText())
        || !planningInput
            .path("capabilitySnapshotDigest")
            .asText()
            .equals(observation.path("capabilitySnapshotDigest").asText())) {
      return EligibilityStatus.IDENTITY_MISMATCH.name();
    }
    // The capability digest must provably represent the concrete immutable snapshot:
    // every carrier must match the canonical digest of the snapshot object itself,
    // not merely agree with each other.
    String pinnedCapabilities = capabilitySnapshotDigest(objectMapper);
    if (!pinnedCapabilities.equals(catalog.path("capabilitySnapshotDigest").asText())
        || !pinnedCapabilities.equals(observation.path("capabilitySnapshotDigest").asText())
        || !pinnedCapabilities.equals(planningInput.path("capabilitySnapshotDigest").asText())) {
      return EligibilityStatus.IDENTITY_MISMATCH.name();
    }
    return null;
  }

  private EligibilityStatus taskStatus(
      JsonNode task, JsonNode observation, JsonNode planningInput) {
    String taskKey = task.path("taskKey").asText();
    // Deterministic capability gate: every required capability must be executor-supported.
    for (JsonNode capability : task.path("requiredCapabilities")) {
      if (!SUPPORTED_CAPABILITIES.contains(capability.asText())) {
        return EligibilityStatus.UNSUPPORTED_CAPABILITY;
      }
    }
    // Catalog prerequisites are evaluated against the live observation.
    for (JsonNode prerequisite : task.path("prerequisites")) {
      if (!prerequisiteSatisfied(prerequisite.asText(), observation)) {
        return EligibilityStatus.PREREQUISITE_UNMET;
      }
    }
    // Catalog preconditions are evaluated the same way; an unmet precondition
    // is a prerequisite failure, not an eligibility grant.
    for (JsonNode precondition : task.path("preconditions")) {
      if (!prerequisiteSatisfied(precondition.asText(), observation)) {
        return EligibilityStatus.PREREQUISITE_UNMET;
      }
    }
    // A planning input that already rejected this task cannot be resurrected here.
    for (JsonNode rejected : planningInput.path("rejectedEntries")) {
      if (taskKey.equals(rejected.path("taskKey").asText())) {
        try {
          return EligibilityStatus.valueOf(rejected.path("reason").asText());
        } catch (Exception exception) {
          return EligibilityStatus.IDENTITY_MISMATCH;
        }
      }
    }
    boolean planned = false;
    for (JsonNode key : planningInput.path("eligibleTaskKeys")) {
      if (taskKey.equals(key.asText())) {
        planned = true;
        break;
      }
    }
    if (!planned) {
      return EligibilityStatus.NO_ELIGIBLE_TASK;
    }
    boolean listed = false;
    for (JsonNode target : observation.path("eligibleTargets")) {
      if (taskKey.equals(target.asText())) {
        listed = true;
        break;
      }
    }
    if (!listed) {
      return EligibilityStatus.NO_ELIGIBLE_TASK;
    }
    if (!"IDLE".equals(observation.path("taskState").path("status").asText())) {
      return EligibilityStatus.PREREQUISITE_UNMET;
    }
    JsonNode budgets = planningInput.path("budgets");
    JsonNode bounds = task.path("maxPolicyBounds");
    if (budgets.path("maxActions").asInt() > bounds.path("maxActions").asInt()
        || budgets.path("maxAttempts").asInt() > bounds.path("maxAttempts").asInt()
        || budgets.path("maxElapsedMs").asLong() > bounds.path("maxElapsedMs").asLong()) {
      return EligibilityStatus.BUDGET_EXHAUSTED;
    }
    String environment =
        planningInput.path("identities").path("environmentId").asText("").toUpperCase();
    if ("LIVE_TASK".equals(planningInput.path("mode").asText())
        && (environment.contains("SIM") || environment.contains("XRSPS"))) {
      return EligibilityStatus.POLICY_DENIED;
    }
    return EligibilityStatus.ELIGIBLE;
  }

  private boolean prerequisiteSatisfied(String prerequisite, JsonNode observation) {
    return switch (prerequisite) {
      case "ACCOUNT_IDLE" -> "IDLE".equals(observation.path("taskState").path("status").asText());
      case "OBSERVATION_CURRENT" -> "CURRENT"
              .equals(observation.path("freshness").path("status").asText())
          && observation.path("observationComplete").asBoolean(false)
          && observation.path("adapterObservationConfidence").asDouble(0.0)
              >= MINIMUM_OBSERVATION_CONFIDENCE;
      default -> false;
    };
  }

  /** Concrete capability snapshot object whose digest is the pinned snapshot digest. */
  public static JsonNode capabilitySnapshot(ObjectMapper objectMapper) {
    ObjectNode snapshot = objectMapper.createObjectNode();
    snapshot.put("contractVersion", GameplayV2Contracts.VERSION);
    ArrayNode capabilities = snapshot.putArray("capabilities");
    CANONICAL_CAPABILITIES.stream().sorted().forEach(capabilities::add);
    return snapshot;
  }

  /** Canonical digest of the concrete capability snapshot; the only accepted snapshot digest. */
  public static String capabilitySnapshotDigest(ObjectMapper objectMapper) {
    return canonicalDigest(objectMapper, capabilitySnapshot(objectMapper));
  }

  /**
   * The observation digest is the canonical digest of the observation itself; the planning input
   * must bind exactly the digest of the observation it was built from.
   */
  public static String observationDigest(ObjectMapper objectMapper, JsonNode observation) {
    return canonicalDigest(objectMapper, observation);
  }

  /**
   * Canonical UTF-8 JSON digest: recursively lexicographic object keys, preserved array order, no
   * insignificant whitespace. Self-contained so the frozen v1 canonicalizer stays untouched.
   */
  public static String canonicalDigest(ObjectMapper objectMapper, JsonNode value) {
    try {
      byte[] bytes = canonicalDigestBytes(objectMapper, value);
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to create canonical v2 gameplay digest", exception);
    }
  }

  /** Canonical UTF-8 bytes of {@code value} (sorted keys, no insignificant whitespace). */
  public static byte[] canonicalDigestBytes(ObjectMapper objectMapper, JsonNode value) {
    try {
      return objectMapper
          .writeValueAsString(canonicalize(objectMapper, objectMapper.valueToTree(value)))
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to canonicalize v2 gameplay value", exception);
    }
  }

  private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isArray()) {
      ArrayNode array = objectMapper.createArrayNode();
      node.forEach(item -> array.add(canonicalize(objectMapper, item)));
      return array;
    }
    ObjectNode object = objectMapper.createObjectNode();
    List<java.util.Map.Entry<String, JsonNode>> fields = new ArrayList<>();
    node.fields().forEachRemaining(fields::add);
    fields.sort(java.util.Map.Entry.comparingByKey());
    fields.forEach(
        field -> object.set(field.getKey(), canonicalize(objectMapper, field.getValue())));
    return object;
  }

  /** Convenience accessor used by tests and adapters. */
  public static List<String> eligibleTaskKeys(JsonNode eligibilityResult) {
    List<String> keys = new ArrayList<>();
    for (JsonNode entry : eligibilityResult.path("entries")) {
      if (EligibilityStatus.ELIGIBLE.name().equals(entry.path("status").asText())) {
        keys.add(entry.path("taskKey").asText());
      }
    }
    return keys;
  }

  /** Immutable row view of a filter result, used by adapters to fail closed. */
  public static List<EligibilityEntry> entries(JsonNode eligibilityResult) {
    List<EligibilityEntry> rows = new ArrayList<>();
    for (JsonNode entry : eligibilityResult.path("entries")) {
      rows.add(
          new EligibilityEntry(
              entry.path("taskKey").asText(),
              EligibilityStatus.valueOf(entry.path("status").asText())));
    }
    return rows;
  }
}
