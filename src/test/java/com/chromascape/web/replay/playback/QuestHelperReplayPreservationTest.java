/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.replay.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse.FrameData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * SC-265 replay preservation: the structured {@code questHelper} payload stored in a {@link
 * TickSnapshot} must reach replay clients as byte-equivalent JSON structure through the production
 * {@link ReplayPlaybackService} mapping — exact step order, requirement buckets, and diagnostics
 * unchanged. ChromaScape must not reorder or lossy-rewrite the payload.
 *
 * <p>Each test drives the public playback service with repository-mocked snapshots, so a regression
 * in {@code ReplayPlaybackService.toFrame} fails here instead of being bypassed by constructing the
 * DTO directly.
 */
class QuestHelperReplayPreservationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SESSION_ID = "sess-1";

  private static JsonNode fixture(String name) throws Exception {
    try (InputStream input =
        QuestHelperReplayPreservationTest.class
            .getClassLoader()
            .getResourceAsStream("contracts/quest/quest-helper-runtime-v1-fixtures.json")) {
      assertThat(input).as("fixture corpus").isNotNull();
      return MAPPER.readTree(input).path("fixtures").path(name);
    }
  }

  private static FrameData replayFrame(JsonNode questHelper) {
    DebugSession session = new DebugSession();
    session.setSessionId(SESSION_ID);

    ReplayRecordingResolver recordingResolver = mock(ReplayRecordingResolver.class);
    when(recordingResolver.resolve(anyString()))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));

    TickSnapshot snapshot = new TickSnapshot();
    snapshot.setSessionId(SESSION_ID);
    snapshot.setTick(12345L);
    snapshot.setTimestamp(LocalDateTime.of(2026, 1, 1, 0, 0));
    snapshot.setQuestHelper(questHelper);
    snapshot.setSchemaVersion(1);

    TickSnapshotRepository tickSnapshotRepository = mock(TickSnapshotRepository.class);
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq(SESSION_ID), anyLong(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(snapshot)));

    TickLogRepository tickLogRepository = mock(TickLogRepository.class);
    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAscIdAsc(
            eq(SESSION_ID), anyLong(), anyLong()))
        .thenReturn(List.of());

    ReplayPlaybackService service =
        new ReplayPlaybackService(
            mock(ReplayPlaybackCatalogRepository.class),
            tickSnapshotRepository,
            tickLogRepository,
            recordingResolver,
            new ReplayPlaybackCursorCodec(MAPPER),
            new ReplayPlaybackFrameCursorCodec(MAPPER));

    var page = service.frames("rec-1", 0L, 10);
    assertThat(page.getFrames()).hasSize(1);
    return page.getFrames().get(0);
  }

  @Test
  void replayFramePageCarriesStructuredPayloadUnchanged() throws Exception {
    JsonNode full = fixture("full");

    JsonNode replay = replayFrame(full).getQuestHelper();

    assertThat(replay).isEqualTo(full);
    assertThat(replay.toString()).isEqualTo(full.toString());
    assertThat(replay.path("steps").get(0).path("stepId").asText())
        .isEqualTo(full.path("steps").get(0).path("stepId").asText());
    assertThat(replay.path("requirements").path("missing").get(0).path("requirementId").asText())
        .isEqualTo(full.path("requirements").path("missing").get(0).path("requirementId").asText());
  }

  @Test
  void degradedDiagnosticsAndOrderSurviveReplayRoundTrip() throws Exception {
    JsonNode degraded = fixture("degraded");

    JsonNode replay = replayFrame(degraded).getQuestHelper();

    assertThat(replay.toString()).isEqualTo(degraded.toString());
    assertThat(replay.path("outcome").asText()).isEqualTo("DEGRADED");
    assertThat(replay.path("diagnostics").size()).isEqualTo(2);
    assertThat(replay.path("diagnostics").get(0).path("code").asText())
        .isEqualTo("REQUIREMENT_UNSUPPORTED");
    assertThat(replay.path("steps").get(0).path("stepId").asText())
        .isEqualTo(degraded.path("steps").get(0).path("stepId").asText());
  }

  @Test
  void liveTickAndReplayCardsReceiveEqualPayloads() throws Exception {
    // The same fixture stored via ingestion must serialize identically whether it reaches a
    // live debugger card (raw batch field) or a replay card (frame page DTO).
    JsonNode fixture = fixture("failed");

    com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage batch =
        new com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage();
    batch.setQuestHelper(
        MAPPER.convertValue(
            fixture,
            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
    JsonNode liveCard = MAPPER.valueToTree(batch).path("questHelper");
    JsonNode replayCard = replayFrame(fixture).getQuestHelper();

    assertThat(liveCard).isEqualTo(replayCard);
    assertThat(liveCard.path("outcome").asText()).isEqualTo("FAILED");
    assertThat(replayCard.path("outcome").asText()).isEqualTo("FAILED");
    assertThat(replayCard.path("diagnostics").get(0).path("severity").asText()).isEqualTo("ERROR");
  }
}
