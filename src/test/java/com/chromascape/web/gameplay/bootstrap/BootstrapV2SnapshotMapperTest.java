package com.chromascape.web.gameplay.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The mapper is the only place Micro6's wire becomes contract objects. Every mapped value must come
 * from an attested source, and an incomplete or disagreeing wire must be refused rather than
 * defaulted.
 */
class BootstrapV2SnapshotMapperTest {
  private static final Instant CAPTURED = Instant.parse("2026-11-10T04:00:00Z");

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final BootstrapV2SnapshotMapper snapshotMapper = new BootstrapV2SnapshotMapper();

  @Test
  void mapsTheCatalogSnapshotFromTheAttestedCatalog() {
    JsonNode snapshot = snapshotMapper.catalogSnapshot(catalogWire(), CAPTURED);

    assertThat(snapshot.path("contractVersion").asText()).isEqualTo("sc-1429/v2");
    assertThat(snapshot.path("catalogRevision").asLong()).isEqualTo(7);
    assertThat(snapshot.path("executorBuildId").asText()).isEqualTo("build-abc");
    assertThat(snapshot.path("executorArtifactId").asText()).isEqualTo("micro6-accountbuilder");
    assertThat(snapshot.path("pluginId").asText()).isEqualTo("plugin-accountbuilder");
    assertThat(snapshot.path("capabilitySnapshotDigest").asText()).startsWith("sha256:");
    assertThat(snapshot.path("tasks")).hasSize(1);
    assertThat(snapshot.path("tasks").get(0).path("taskKey").asText())
        .isEqualTo("FISHING_TRAINING");
    assertThat(snapshot.path("tasks").get(0).path("verifierIds").get(0).asText())
        .isEqualTo("fishing-xp-v1");
    // The digest is over the object without its own digest member.
    ObjectNode withoutDigest = snapshot.deepCopy();
    withoutDigest.remove("catalogDigest");
    assertThat(snapshot.path("catalogDigest").asText())
        .isEqualTo(GameplayV2EligibilityFilter.canonicalDigest(mapper, withoutDigest));
  }

  @Test
  void refusesACatalogThatDoesNotPublishTheTaskDescriptor() {
    ObjectNode catalog = (ObjectNode) catalogWire().deepCopy();
    ((ObjectNode) catalog.path("tasks").get(0)).remove("descriptor");

    assertThatThrownBy(() -> snapshotMapper.catalogSnapshot(catalog, CAPTURED))
        .hasMessageContaining("task descriptor");
  }

  @Test
  void refusesADisabledCatalog() {
    ObjectNode catalog = (ObjectNode) catalogWire().deepCopy();
    catalog.put("enabled", false);

    assertThatThrownBy(() -> snapshotMapper.catalogSnapshot(catalog, CAPTURED))
        .hasMessageContaining("not enabled");
  }

  @Test
  void mapsTheSemanticObservationFromBothAttestations() {
    JsonNode catalog = snapshotMapper.catalogSnapshot(catalogWire(), CAPTURED);
    JsonNode observation =
        snapshotMapper.semanticObservation(
            observeWire(), observationWire(), identities(), catalog, "sha256:" + "f".repeat(64));

    assertThat(observation.path("revision").asLong()).isEqualTo(11);
    assertThat(observation.path("taskRevision").asLong()).isEqualTo(13);
    assertThat(observation.path("capturedAt").asText()).isEqualTo("2026-11-10T04:00:00Z");
    assertThat(observation.path("capturedTick").asLong()).isEqualTo(4242);
    assertThat(observation.path("catalogDigest").asText())
        .isEqualTo(catalog.path("catalogDigest").asText());
    assertThat(observation.path("policyDigest").asText())
        .isEqualTo(com.chromascape.web.gameplay.contract.v2.GameplayV2BootstrapPolicy.digest(mapper));
    assertThat(observation.path("freshness").path("status").asText()).isEqualTo("CURRENT");
    assertThat(observation.path("observationComplete").asBoolean()).isTrue();
    assertThat(observation.path("player").path("animation").asInt()).isEqualTo(808);
  }

  @Test
  void refusesWhenTheTwoAttestationsDisagreeOnTheClient() {
    ObjectNode observe = (ObjectNode) observeWire().deepCopy();
    observe.put("clientId", "client-other");
    JsonNode catalog = snapshotMapper.catalogSnapshot(catalogWire(), CAPTURED);

    assertThatThrownBy(
            () ->
                snapshotMapper.semanticObservation(
                    observe, observationWire(), identities(), catalog, "sha256:" + "f".repeat(64)))
        .hasMessageContaining("disagree on the client");
  }

  @Test
  void refusesAnIncompleteLiveObservation() {
    ObjectNode observation = (ObjectNode) observationWire().deepCopy();
    observation.remove("inventory");
    JsonNode catalog = snapshotMapper.catalogSnapshot(catalogWire(), CAPTURED);

    assertThatThrownBy(
            () ->
                snapshotMapper.semanticObservation(
                    observeWire(),
                    observation,
                    identities(),
                    catalog,
                    "sha256:" + "f".repeat(64)))
        .hasMessageContaining("inventory");
  }

  @Test
  void planningInputBindsTheObservationAndOffersOnlyCatalogEligibleTasks() {
    JsonNode catalog = snapshotMapper.catalogSnapshot(catalogWire(), CAPTURED);
    JsonNode observation =
        snapshotMapper.semanticObservation(
            observeWire(), observationWire(), identities(), catalog, "sha256:" + "f".repeat(64));
    JsonNode planning =
        snapshotMapper.planningInput(
            catalog,
            observation,
            mapper.createObjectNode().put("maxActions", 1).put("maxAttempts", 1)
                .put("maxElapsedMs", 300000),
            Instant.parse("2026-11-10T04:10:00Z"),
            identities());

    assertThat(planning.path("observationDigest").asText())
        .isEqualTo(GameplayV2EligibilityFilter.canonicalDigest(mapper, observation));
    assertThat(planning.path("catalogDigest").asText())
        .isEqualTo(catalog.path("catalogDigest").asText());
    assertThat(planning.path("eligibleTaskKeys")).hasSize(1);
    assertThat(planning.path("eligibleTaskKeys").get(0).asText()).isEqualTo("FISHING_TRAINING");
    assertThat(planning.path("promptSchemaVersion").asText()).isEqualTo("v2");
  }

  private JsonNode catalogWire() {
    ObjectNode descriptor = mapper.createObjectNode();
    descriptor.put("taskKey", "FISHING_TRAINING");
    descriptor.set("requiredCapabilities", strings("FISHING_SEMANTIC_ACTION"));
    descriptor.set("prerequisites", strings("ACCOUNT_IDLE"));
    descriptor.set("preconditions", strings("OBSERVATION_CURRENT", "ACCOUNT_IDLE"));
    descriptor.set("expectedTransitions", strings("FISHING_XP_INCREASED"));
    descriptor.set("verifierIds", strings("fishing-xp-v1"));
    ObjectNode bounds = mapper.createObjectNode();
    bounds.put("maxActions", 3);
    bounds.put("maxAttempts", 3);
    bounds.put("maxElapsedMs", 900000);
    descriptor.set("maxPolicyBounds", bounds);

    ObjectNode task = mapper.createObjectNode();
    task.put("taskKey", "FISHING_TRAINING");
    task.put("eligible", true);
    task.put("schemaDigest", "sha256:" + "b".repeat(64));
    task.set("descriptor", descriptor);

    ObjectNode catalog = mapper.createObjectNode();
    catalog.put("contractVersion", "sc-1429/v2");
    catalog.put("enabled", true);
    catalog.put("executorArtifactId", "micro6-accountbuilder");
    catalog.put("pluginId", "plugin-accountbuilder");
    catalog.put("buildId", "build-abc");
    catalog.put("catalogHash", "sha256:" + "c".repeat(64));
    catalog.put("catalogRevision", 7);
    catalog.put("capabilityDigest", "sha256:" + "a".repeat(64));
    catalog.put("observationRevision", 11);
    catalog.put("taskRevision", 13);
    catalog.set("tasks", mapper.createArrayNode().add(task));
    return catalog;
  }

  private JsonNode observeWire() {
    return mapper.createObjectNode()
        .put("contractVersion", "sc-1429/v2")
        .put("clientId", "client-abc")
        .put("buildId", "build-abc")
        .put("catalogHash", "sha256:" + "c".repeat(64))
        .put("catalogRevision", 7)
        .put("capabilityDigest", "sha256:" + "a".repeat(64))
        .put("observationRevision", 11)
        .put("taskRevision", 13);
  }

  private JsonNode observationWire() {
    ObjectNode player = mapper.createObjectNode();
    player.put("worldPoint", "3200,3200,0");
    player.put("animation", 808);
    player.putNull("interactingTarget");
    ObjectNode skills = mapper.createObjectNode();
    skills.put("fishingLevel", 40);
    skills.put("fishingXp", 37224);
    ObjectNode taskState = mapper.createObjectNode();
    taskState.put("revision", 13);
    taskState.put("status", "IDLE");
    taskState.put("taskKey", "FISHING_TRAINING");

    ObjectNode observation = mapper.createObjectNode();
    observation.put("capturedAt", "2026-11-10T04:00:00Z");
    observation.put("capturedTick", 4242);
    observation.put("revision", 11);
    observation.put("accountId", "account-abc");
    observation.put("clientId", "client-abc");
    observation.put("environmentId", "native-windows-local");
    observation.put("buildId", "build-abc");
    observation.set("player", player);
    observation.set("inventory", mapper.createArrayNode());
    observation.set("skills", skills);
    observation.set("eligibleTargets", strings("FISHING_TRAINING"));
    observation.set("taskState", taskState);
    return observation;
  }

  private JsonNode identities() {
    return mapper
        .createObjectNode()
        .put("accountId", "account-abc")
        .put("clientId", "client-abc")
        .put("environmentId", "native-windows-local")
        .put("runId", "run-1")
        .put("taskId", "task-1")
        .put("sessionId", "session-1")
        .put("recordingId", "recording-1")
        .put("sourceId", "source-1");
  }

  private com.fasterxml.jackson.databind.node.ArrayNode strings(String... values) {
    var array = mapper.createArrayNode();
    for (String value : values) {
      array.add(value);
    }
    return array;
  }
}
