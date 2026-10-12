/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SC-265 preservation tests: the structured {@code questHelper} payload from the golden fixture
 * corpus must survive ingestion with exact step array order, requirement statuses, and diagnostics
 * unchanged. ChromaScape stores the payload without reordering or lossy rewriting.
 */
@ExtendWith(MockitoExtension.class)
class QuestHelperPayloadPreservationTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DebugSessionService sessionService;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private com.chromascape.web.debug.tickdebugger.entity.TickLogRepository logRepository;

  @Mock
  private com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository overlayRepository;

  @Mock
  private com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository markerRepository;

  @Mock
  private com.chromascape.web.integration.leeuwbot.service.LeeuwBotBridgeService
      leeuwBotBridgeService;

  @Mock private com.chromascape.web.integration.leeuwbot.config.LeeuwBotConfig leeuwBotConfig;

  private TickStreamIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new TickStreamIngestionService(
            sessionService,
            snapshotRepository,
            logRepository,
            overlayRepository,
            markerRepository,
            MAPPER,
            leeuwBotBridgeService,
            leeuwBotConfig);
  }

  private TickBatchMessage batchWithQuestHelper(
      String sessionId, long tick, Map<String, Object> questHelper) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSchemaVersion(2);
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2025-06-15T12:00:00Z");

    Map<String, Object> playerState = new HashMap<>();
    playerState.put("x", 3200);
    playerState.put("z", 3200);
    batch.setPlayerState(playerState);

    batch.setInventory(List.of());
    batch.setEquipment(List.of());
    batch.setThreats(List.of());
    batch.setProjectiles(List.of());
    batch.setCooldowns(new HashMap<>());
    batch.setLogs(new ArrayList<>());
    batch.setOverlays(new ArrayList<>());
    batch.setNpcs(List.of());
    batch.setGameObjects(List.of());
    batch.setGroundObjects(List.of());
    batch.setGroundItems(List.of());
    batch.setPlayers(List.of());
    batch.setWidgets(List.of());

    Map<String, Object> quest = new HashMap<>();
    quest.put("active", false);
    quest.put("changed", false);
    batch.setQuest(quest);
    batch.setQuestHelper(questHelper);
    return batch;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fixtureAsMap(String name) throws Exception {
    try (java.io.InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json")) {
      assertThat(input).as("fixture corpus").isNotNull();
      JsonNode fixture = MAPPER.readTree(input).path("fixtures").path(name);
      return MAPPER.convertValue(fixture, Map.class);
    }
  }

  private TickSnapshot ingest(String sessionId, long tick, Map<String, Object> questHelper) {
    when(sessionService.getSessionForIngestion(sessionId))
        .thenReturn(
            Optional.of(new DebugSession(sessionId, "account", "script", LocalDateTime.now(), 0L)));
    service.processTickBatch(batchWithQuestHelper(sessionId, tick, questHelper));
    ArgumentCaptor<TickSnapshot> captor = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("ingestion preserves exact step array order for the full fixture")
  void fullFixtureStepOrderIsPreserved() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("full");
    TickSnapshot saved = ingest("sc265-full", 100, questHelper);

    assertThat(saved.getQuestHelper()).isNotNull();
    assertThat(saved.getQuestHelper().path("outcome").asText()).isEqualTo("FULL");
    assertThat(saved.getQuestHelper().path("steps").isArray()).isTrue();
    assertThat(saved.getQuestHelper().path("steps").size()).isEqualTo(2);
    assertThat(saved.getQuestHelper().path("steps").get(0).path("stepId").asText())
        .isEqualTo("cooks_assistant:7c63d1a2b4c5");
    assertThat(saved.getQuestHelper().path("steps").get(1).path("stepId").asText())
        .isEqualTo("cooks_assistant:91af2c4d8e01");
    assertThat(saved.getQuestHelper().path("steps").get(0).path("ordinal").asInt()).isEqualTo(0);
  }

  @Test
  @DisplayName("ingestion preserves diagnostics for the degraded fixture")
  void degradedFixtureDiagnosticsArePreserved() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("degraded");
    TickSnapshot saved = ingest("sc265-degraded", 101, questHelper);

    JsonNode diagnostics = saved.getQuestHelper().path("diagnostics");
    assertThat(diagnostics.isArray()).isTrue();
    assertThat(diagnostics.size()).isEqualTo(2);
    assertThat(diagnostics.get(0).path("code").asText()).isEqualTo("REQUIREMENT_UNSUPPORTED");
    assertThat(diagnostics.get(1).path("code").asText()).isEqualTo("CLIENT_UNAVAILABLE");
    assertThat(saved.getQuestHelper().path("outcome").asText()).isEqualTo("DEGRADED");
    assertThat(saved.getQuestHelper().path("usable").asBoolean()).isTrue();
    assertThat(saved.getQuestHelper().path("complete").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("failed fixture does not crash ingestion and keeps error diagnostics")
  void failedFixtureIngestionIsNonFatal() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("failed");
    TickSnapshot saved = ingest("sc265-failed", 102, questHelper);

    assertThat(saved.getQuestHelper().path("outcome").asText()).isEqualTo("FAILED");
    assertThat(saved.getQuestHelper().path("diagnostics").get(0).path("severity").asText())
        .isEqualTo("ERROR");
  }

  @Test
  @DisplayName("unavailable fixture stores empty core sections")
  void unavailableFixtureStoresEmptySections() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("unavailable");
    TickSnapshot saved = ingest("sc265-unavailable", 103, questHelper);

    assertThat(saved.getQuestHelper().path("steps").size()).isZero();
    assertThat(saved.getQuestHelper().path("currentStep").isNull()).isTrue();
  }

  @Test
  @DisplayName("legacy payload without additive keys round-trips unchanged")
  void legacyPayloadRoundTripsUnchanged() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("legacyV1");
    TickSnapshot saved = ingest("sc265-legacy", 104, questHelper);

    JsonNode stored = saved.getQuestHelper();
    assertThat(stored.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(stored.has("schema")).isFalse();
    assertThat(stored.has("outcome")).isFalse();
    assertThat(stored.path("steps").size()).isEqualTo(1);
    assertThat(stored.path("steps").get(0).path("stepId").asText())
        .isEqualTo("cooks_assistant.step_001_talk_to_the_cook");
  }

  @Test
  @DisplayName("requirement statuses survive ingestion grouped per bucket")
  void requirementStatusesSurviveIngestion() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("full");
    TickSnapshot saved = ingest("sc265-reqs", 105, questHelper);

    JsonNode requirements = saved.getQuestHelper().path("requirements");
    assertThat(requirements.path("all").size()).isEqualTo(2);
    assertThat(requirements.path("missing").get(0).path("status").asText()).isEqualTo("MISSING");
    assertThat(requirements.path("met").get(0).path("status").asText()).isEqualTo("MET");
    assertThat(requirements.path("blockedBy").get(0).path("type").asText()).isEqualTo("ITEM");
  }

  @Test
  @DisplayName("synthetic current fixture keeps the CURRENT_STEP_NOT_IN_GRAPH diagnostic")
  void syntheticCurrentDiagnosticSurvives() throws Exception {
    Map<String, Object> questHelper = fixtureAsMap("syntheticCurrent");
    TickSnapshot saved = ingest("sc265-synthetic", 106, questHelper);

    assertThat(saved.getQuestHelper().path("steps").get(1).path("synthetic").asBoolean()).isTrue();
    assertThat(saved.getQuestHelper().path("diagnostics").get(0).path("code").asText())
        .isEqualTo("CURRENT_STEP_NOT_IN_GRAPH");
  }
}
