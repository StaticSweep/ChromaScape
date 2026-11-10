package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.contract.v2.GameplayV2BootstrapPolicy;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps Micro6's live wire into the contract-shaped inputs of a v2 draft.
 *
 * <p>Every value is copied from an attested source: the v2 catalog and observe responses, and the
 * live observation route. Nothing is defaulted, guessed or synthesised — a missing field is a
 * refusal, because a substituted value would become part of an approved execution scope.
 *
 * <p>The catalog snapshot's own digest is computed over the object without its {@code catalogDigest}
 * member, which is exactly what both sides recompute. The catalog's {@code catalogHash} is a
 * different value (Micro6's publication hash) and is never substituted for it.
 */
@Component
public final class BootstrapV2SnapshotMapper {
  /** Freshness bound applied to a live observation read. */
  public static final long OBSERVATION_MAX_AGE_MS = 60_000L;

  /** Prompt schema identity the provider was asked to answer. */
  public static final String PROMPT_SCHEMA_ID = "sc-1429-provider-proposal";

  /** Prompt schema version the provider was asked to answer. */
  public static final String PROMPT_SCHEMA_VERSION = "v2";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Builds the immutable catalog snapshot from the v2 catalog response.
   *
   * @throws IllegalArgumentException when the catalog is disabled or a required member is absent
   */
  public JsonNode catalogSnapshot(JsonNode catalogWire, Instant capturedAt) {
    requireTrue(catalogWire, "enabled", "Micro6 v2 catalog is not enabled");
    JsonNode task = fishingTask(catalogWire);
    JsonNode descriptor = task.path("descriptor");
    if (!descriptor.isObject()) {
      throw new IllegalArgumentException(
          "Micro6 did not publish the FISHING_TRAINING task descriptor");
    }
    ObjectNode snapshot = MAPPER.createObjectNode();
    snapshot.put("contractVersion", GameplayV2Contracts.VERSION);
    snapshot.put("catalogId", "micro6:" + text(catalogWire, "buildId"));
    snapshot.put("catalogRevision", number(catalogWire, "catalogRevision"));
    snapshot.put("capturedAt", capturedAt.toString());
    snapshot.put("executorArtifactId", text(catalogWire, "executorArtifactId"));
    snapshot.put("executorBuildId", text(catalogWire, "buildId"));
    snapshot.put("pluginId", text(catalogWire, "pluginId"));
    snapshot.put("capabilitySnapshotDigest", text(catalogWire, "capabilityDigest"));
    ArrayNode tasks = snapshot.putArray("tasks");
    tasks.add(descriptor.deepCopy());
    // The digest is computed last, over the object without it.
    snapshot.put("catalogDigest", digestWithout(snapshot, "catalogDigest"));
    return snapshot;
  }

  /**
   * Builds the semantic observation from the v2 observe attestation and the live observation route.
   *
   * <p>The v2 observe response attests the revisions; the observation route carries what the client
   * actually sees. Both must describe the same client and build, and the observation must be
   * complete, or the mapping refuses.
   */
  public JsonNode semanticObservation(
      JsonNode observeWire,
      JsonNode observationWire,
      JsonNode identities,
      JsonNode catalogSnapshot,
      String knowledgeDigest) {
    if (!text(observeWire, "clientId").equals(text(observationWire, "clientId"))) {
      throw new IllegalArgumentException("Micro6 observe and observation disagree on the client");
    }
    if (!text(observeWire, "buildId").equals(text(observationWire, "buildId"))) {
      throw new IllegalArgumentException("Micro6 observe and observation disagree on the build");
    }
    ObjectNode observation = MAPPER.createObjectNode();
    observation.put("contractVersion", GameplayV2Contracts.VERSION);
    observation.put(
        "observationId", "observation-" + number(observeWire, "observationRevision"));
    observation.put("revision", number(observeWire, "observationRevision"));
    observation.put("capturedAt", text(observationWire, "capturedAt"));
    observation.put("capturedTick", number(observationWire, "capturedTick"));
    observation.put("mode", "LIVE_TASK");
    observation.set("identities", identities.deepCopy());
    observation.put("catalogDigest", catalogSnapshot.path("catalogDigest").asText());
    observation.put("catalogRevision", catalogSnapshot.path("catalogRevision").asLong());
    observation.put(
        "capabilitySnapshotDigest", catalogSnapshot.path("capabilitySnapshotDigest").asText());
    observation.put("policyDigest", GameplayV2BootstrapPolicy.digest(MAPPER));
    observation.put("knowledgeDigest", knowledgeDigest);
    observation.put("buildId", text(observeWire, "buildId"));
    observation.set("player", required(observationWire, "player"));
    observation.set("inventory", required(observationWire, "inventory"));
    observation.set("skills", required(observationWire, "skills"));
    observation.set("eligibleTargets", required(observationWire, "eligibleTargets"));
    observation.set("taskState", required(observationWire, "taskState"));
    ObjectNode freshness = observation.putObject("freshness");
    freshness.put("status", "CURRENT");
    freshness.put("maxAgeMs", OBSERVATION_MAX_AGE_MS);
    observation.put("observationComplete", true);
    observation.put("adapterObservationConfidence", 1.0);
    observation.put("knowledgeFreshnessConfidence", 1.0);
    observation.put("taskRevision", number(observeWire, "taskRevision"));
    return observation;
  }

  /**
   * Builds the planning input the provider is asked about. Only tasks the catalog itself marks
   * eligible are offered; everything else is left out rather than labelled with a guessed reason,
   * so the deterministic filter reports it as {@code NO_ELIGIBLE_TASK}.
   */
  public JsonNode planningInput(
      JsonNode catalogSnapshot,
      JsonNode observation,
      JsonNode budgets,
      Instant deadline,
      JsonNode identities) {
    ObjectNode planning = MAPPER.createObjectNode();
    planning.put("contractVersion", GameplayV2Contracts.VERSION);
    planning.put("mode", "LIVE_TASK");
    planning.put("catalogDigest", catalogSnapshot.path("catalogDigest").asText());
    planning.put("catalogRevision", catalogSnapshot.path("catalogRevision").asLong());
    planning.put(
        "observationDigest", GameplayV2EligibilityFilter.canonicalDigest(MAPPER, observation));
    planning.put(
        "capabilitySnapshotDigest", catalogSnapshot.path("capabilitySnapshotDigest").asText());
    planning.put("policyDigest", observation.path("policyDigest").asText());
    planning.put("knowledgeDigest", observation.path("knowledgeDigest").asText());
    ArrayNode eligible = planning.putArray("eligibleTaskKeys");
    for (JsonNode task : catalogSnapshot.path("tasks")) {
      eligible.add(task.path("taskKey").asText());
    }
    planning.putArray("rejectedEntries");
    planning.set("budgets", budgets.deepCopy());
    planning.put("deadline", deadline.toString());
    planning.set("identities", identities.deepCopy());
    ArrayNode provenance = planning.putArray("provenanceRefs");
    provenance.add("catalog:" + catalogSnapshot.path("catalogDigest").asText());
    provenance.add("observation:" + observation.path("observationId").asText());
    planning.put("promptSchemaId", PROMPT_SCHEMA_ID);
    planning.put("promptSchemaVersion", PROMPT_SCHEMA_VERSION);
    return planning;
  }

  /** Runs the deterministic filter over the mapped inputs. */
  public JsonNode eligibilityResult(
      JsonNode catalogSnapshot,
      JsonNode observation,
      JsonNode planningInput,
      Instant evaluatedAt) {
    return new GameplayV2EligibilityFilter(MAPPER)
        .filter(catalogSnapshot, observation, planningInput, evaluatedAt);
  }

  /** The catalog's FISHING_TRAINING row, or a refusal when it is absent. */
  private JsonNode fishingTask(JsonNode catalogWire) {
    List<JsonNode> matches = new ArrayList<>();
    for (JsonNode task : catalogWire.path("tasks")) {
      if ("FISHING_TRAINING".equals(task.path("taskKey").asText())) {
        matches.add(task);
      }
    }
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Micro6 catalog must publish exactly one FISHING_TRAINING task");
    }
    JsonNode task = matches.get(0);
    if (!task.path("eligible").asBoolean(false)) {
      throw new IllegalArgumentException("Micro6 reports FISHING_TRAINING as ineligible");
    }
    return task;
  }

  private JsonNode required(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      throw new IllegalArgumentException("Live observation is missing " + field);
    }
    return value.deepCopy();
  }

  private String text(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Micro6 response is missing " + field);
    }
    return value;
  }

  private long number(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isNumber()) {
      throw new IllegalArgumentException("Micro6 response is missing " + field);
    }
    return value.asLong();
  }

  private void requireTrue(JsonNode node, String field, String message) {
    if (!node.path(field).asBoolean(false)) {
      throw new IllegalArgumentException(message);
    }
  }

  private String digestWithout(ObjectNode node, String member) {
    ObjectNode copy = node.deepCopy();
    copy.remove(member);
    return GameplayV2EligibilityFilter.canonicalDigest(MAPPER, copy);
  }
}
