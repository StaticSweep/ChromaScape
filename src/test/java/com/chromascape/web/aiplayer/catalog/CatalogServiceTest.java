package com.chromascape.web.aiplayer.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.integration.micro6.service.CatalogFixture;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private Micro6ExecutorCatalogService upstream;
  private CatalogStore store;
  private AiPlayerCatalogService service;

  @BeforeEach
  void initialize() {
    upstream = mock(Micro6ExecutorCatalogService.class);
    store = mock(CatalogStore.class);
    service = new AiPlayerCatalogService(upstream, store, json);
  }

  private ObjectNode reply(ObjectNode raw, String state) {
    ObjectNode out = json.createObjectNode().put("catalog_state", state);
    out.set("catalog", raw);
    out.putArray("reasons");
    return out;
  }

  private ObjectNode request() {
    ObjectNode out =
        json.createObjectNode()
            .put("name", "My fishing")
            .put("endpointRef", "fixture-ref")
            .put("taskKey", "FISHING_TRAINING")
            .put("catalogHash", CatalogFixture.GOLDEN_CATALOG_HASH)
            .put("schemaHash", CatalogFixture.GOLDEN_SETTINGS_SCHEMA_HASH);
    out.putObject("settings");
    return out;
  }

  @Test
  void browsingFetchesOnlyLightweightAndSelectionLazilyFetchesFull() {
    when(upstream.getCatalogForExecutor("fixture-ref", "lightweight", false))
        .thenReturn(reply(CatalogFixture.lightweight(), "READY"));
    service.catalog("fixture-ref", false);
    verify(upstream, never()).getCatalogForExecutor(anyString(), eq("full"), anyBoolean());
    when(upstream.getCatalogForExecutor("fixture-ref", "full", false))
        .thenReturn(reply(CatalogFixture.full(), "READY"));
    assertEquals(
        "AVAILABLE",
        service
            .detail("fixture-ref", "FISHING_TRAINING", CatalogFixture.GOLDEN_CATALOG_HASH, false)
            .path("schema_state")
            .asText());
  }

  @Test
  void missingSchemaIsExplicitAndBadHashCannotProduceEditableSettings() {
    ObjectNode full = CatalogFixture.full();
    ((ObjectNode) full.path("tasks").get(0)).remove("args_schema");
    when(upstream.getCatalogForExecutor("fixture-ref", "full", false))
        .thenReturn(reply(full, "READY"));
    assertEquals(
        "INVALID",
        service
            .detail("fixture-ref", "FISHING_TRAINING", null, false)
            .path("schema_state")
            .asText());
    ((ObjectNode) full.path("tasks").get(0)).put("settings_schema_state", "NOT_APPLICABLE");
    assertEquals(
        "UNAVAILABLE",
        service
            .detail("fixture-ref", "FISHING_TRAINING", null, false)
            .path("schema_state")
            .asText());
    assertEquals(
        "CATALOG_CHANGED",
        assertThrows(
                CatalogProblem.class,
                () -> service.detail("fixture-ref", "FISHING_TRAINING", "old", false))
            .code());
  }

  @Test
  void durableCacheSurvivesTransientOutageButNeverIdentityOrAuthorizationFailure() {
    ObjectNode cached = CatalogNormalizer.normalize(CatalogFixture.lightweight(), false);
    when(store.cached("fixture-ref", "lightweight")).thenReturn(cached);
    ObjectNode failure =
        json.createObjectNode()
            .put("catalog_state", "UNAVAILABLE")
            .put("reason_code", "REGISTRY_FAILURE");
    when(upstream.getCatalogForExecutor("fixture-ref", "lightweight", false)).thenReturn(failure);
    assertEquals("STALE", service.catalog("fixture-ref", false).path("state").asText());
    for (String permanent :
        java.util.List.of(
            "IDENTITY_UNVERIFIED",
            "CONTRACT_INVALID",
            "RUNTIME_CHANGED",
            "RUNTIME_NOT_AUTHORIZED")) {
      failure.put("reason_code", permanent);
      assertEquals("UNAVAILABLE", service.catalog("fixture-ref", false).path("state").asText());
    }
    verify(store, times(1)).cached("fixture-ref", "lightweight");
  }

  @Test
  void savingRevalidatesAndStoresOnlyServerOwnedDefaultsAndMetadata() {
    when(upstream.getCatalogForExecutor("fixture-ref", "full", true))
        .thenReturn(reply(CatalogFixture.full(), "READY"));
    when(store.append(isNull(), eq("My fishing"), any(), any())).thenAnswer(a -> a.getArgument(3));
    ObjectNode saved = service.save(request());
    assertEquals("TROUT", saved.path("targetFish").asText());
    assertEquals(50, saved.path("stopConditionValue").asInt());
    verify(upstream).getCatalogForExecutor("fixture-ref", "full", true);
    ObjectNode unknown = request();
    unknown.putObject("requirements");
    assertEquals(
        "INVALID_PROFILE", assertThrows(CatalogProblem.class, () -> service.save(unknown)).code());
  }

  @Test
  void staleCatalogSchemaDriftAndInvalidFieldsPreventAnyAppend() {
    when(upstream.getCatalogForExecutor("fixture-ref", "full", true))
        .thenReturn(reply(CatalogFixture.full(), "STALE"));
    assertEquals(
        "CATALOG_STALE", assertThrows(CatalogProblem.class, () -> service.save(request())).code());
    when(upstream.getCatalogForExecutor("fixture-ref", "full", true))
        .thenReturn(reply(CatalogFixture.full(), "READY"));
    ObjectNode drift = request().put("schemaHash", "old");
    assertEquals(
        "SCHEMA_CHANGED", assertThrows(CatalogProblem.class, () -> service.save(drift)).code());
    ObjectNode invalid = request();
    invalid.with("settings").put("stopConditionValue", 100);
    assertTrue(
        assertThrows(CatalogProblem.class, () -> service.save(invalid))
            .errors()
            .containsKey("stopConditionValue"));
    verify(store, never()).append(any(), any(), any(), any());
  }

  @Test
  void unknownAndExpiredFactsRemainUnknownAndSourceReadSitesAreNeverInvented() {
    ObjectNode full = CatalogFixture.full();
    when(upstream.getCatalogForExecutor("fixture-ref", "full", false))
        .thenReturn(reply(full, "READY"));
    ObjectNode first = service.detail("fixture-ref", "FISHING_TRAINING", null, false);
    assertEquals("UNKNOWN", first.path("method_facts").path("state").asText());
    assertFalse(first.path("source_summary").has("settings_read_sites"));
    ObjectNode facts = ((ObjectNode) full.path("tasks").get(0)).putObject("method_facts");
    facts
        .put("version", "1")
        .put("observed_at", "2026-01-01T00:00:00Z")
        .put("valid_until", "2026-01-02T00:00:00Z")
        .put("uncertainty", "Fixture only");
    facts.putArray("citations").add("https://example.org/fixture");
    assertEquals(
        "UNKNOWN_STALE",
        service
            .detail("fixture-ref", "FISHING_TRAINING", null, false)
            .path("method_facts")
            .path("state")
            .asText());
  }

  @Test
  void exactSuppliedSourceSummaryIsPreservedWithoutCurrentBranchFallback() {
    ObjectNode full = CatalogFixture.full();
    ObjectNode source = ((ObjectNode) full.path("tasks").get(0)).putObject("source_summary");
    source
        .put("entry_class", "example.FishingTask")
        .put("module", "fixture-module")
        .put("commit_id", "c".repeat(40))
        .put("source_snapshot_id", "srcsnap_" + "b".repeat(24))
        .put("source_manifest_hash", "sha256:" + "b".repeat(64))
        .put("availability", "REDACTED");
    source.putArray("components").add("fixture-configuration");
    when(upstream.getCatalogForExecutor("fixture-ref", "full", false))
        .thenReturn(reply(full, "READY"));
    ObjectNode result = service.detail("fixture-ref", "FISHING_TRAINING", null, false);
    assertEquals("example.FishingTask", result.path("source_summary").path("entry_class").asText());
    assertEquals("REDACTED", result.path("source_summary").path("availability").asText());
    assertEquals(source.path("components"), result.path("source_summary").path("components"));
    assertFalse(result.path("source_summary").has("settings_read_sites"));
  }

  @Test
  void uncitedOrUnversionedFactsDoNotBecomeTrustedPlanningValues() {
    ObjectNode full = CatalogFixture.full();
    ObjectNode facts = ((ObjectNode) full.path("tasks").get(0)).putObject("method_facts");
    facts.put("throughput", 999999);
    when(upstream.getCatalogForExecutor("fixture-ref", "full", false))
        .thenReturn(reply(full, "READY"));
    assertEquals(
        "INVALID_METHOD_FACTS",
        assertThrows(
                CatalogProblem.class,
                () -> service.detail("fixture-ref", "FISHING_TRAINING", null, false))
            .code());
  }

  @Test
  void profileScalarFieldsCannotBeCoercedFromNumbersOrObjects() {
    ObjectNode invalid = request().put("name", 123);
    assertTrue(
        assertThrows(CatalogProblem.class, () -> service.save(invalid))
            .errors()
            .containsKey("name"));
    verifyNoInteractions(upstream, store);
  }
}
