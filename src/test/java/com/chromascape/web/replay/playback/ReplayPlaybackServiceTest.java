package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReplayPlaybackServiceTest {

  @Mock private ReplayPlaybackCatalogRepository catalogRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private TickSnapshotRepository tickSnapshotRepository;
  @Mock private ReplayRecordingResolver recordingResolver;

  private ReplayPlaybackService service;
  private ReplayPlaybackCursorCodec catalogCursorCodec;
  private ReplayPlaybackFrameCursorCodec frameCursorCodec;
  private DebugSession session;

  @BeforeEach
  void setUp() {
    catalogCursorCodec = new ReplayPlaybackCursorCodec(new ObjectMapper());
    frameCursorCodec = new ReplayPlaybackFrameCursorCodec(new ObjectMapper());
    service =
        new ReplayPlaybackService(
            catalogRepository,
            tickSnapshotRepository,
            tickLogRepository,
            recordingResolver,
            catalogCursorCodec,
            frameCursorCodec);
    session = new DebugSession();
    session.setSessionId("debug-1");
    session.setRecordingId("recording-1");
    session.setAccountName("operator");
    session.setScriptName("fishing");
    session.setStartTick(10L);
    session.setEndTick(12L);
  }

  @Test
  void catalogReportsObservedCoverageWithoutSimulatorDependencies() {
    LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
    when(catalogRepository.findCatalogRows(null, null, null, 51, 0))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  7L,
                  "recording-1",
                  "debug-1",
                  "operator",
                  "fishing",
                  start,
                  null,
                  10L,
                  12L,
                  3L,
                  2L,
                  4L
                }));

    var response = service.catalog(null, 50, (String) null);

    assertEquals(1, response.getEntries().size());
    assertEquals("recording-1", response.getEntries().get(0).getRecordingId());
    assertEquals(2, response.getEntries().get(0).getPositionFrameCount());
    assertEquals(4, response.getEntries().get(0).getLogCount());
    assertEquals(66.666, response.getEntries().get(0).getPositionCoveragePct(), 0.01);
    assertTrue(response.getEntries().get(0).isReplayable());
    assertEquals("replay-playback-catalog/v1", response.getSchema());
  }

  @Test
  void catalogTrimsAccountsAndClampsLimitBounds() {
    when(catalogRepository.findCatalogRows("account-a", null, null, 201, 0))
        .thenReturn(List.of());
    service.catalog("  account-a  ", Integer.MAX_VALUE, null);
    verify(catalogRepository).findCatalogRows("account-a", null, null, 201, 0);

    clearInvocations(catalogRepository);
    when(catalogRepository.findCatalogRows(null, null, null, 2, 0)).thenReturn(List.of());
    service.catalog("   ", Integer.MIN_VALUE, null);
    verify(catalogRepository).findCatalogRows(null, null, null, 2, 0);
  }

  @Test
  void catalogUsesLimitPlusOneAndReturnsTheFinalRowCursor() {
    LocalDateTime firstTime = LocalDateTime.of(2026, 1, 3, 0, 0);
    LocalDateTime secondTime = firstTime.minusDays(1);
    LocalDateTime thirdTime = secondTime.minusDays(1);
    when(catalogRepository.findCatalogRows(null, null, null, 3, 0))
        .thenReturn(
            List.of(
                catalogRow(7L, firstTime, "recording-1"),
                catalogRow(6L, secondTime, "recording-2"),
                catalogRow(5L, thirdTime, "recording-3")));

    var firstPage = service.catalog(null, 2, null);

    assertEquals(2, firstPage.getEntries().size());
    assertTrue(firstPage.isHasMore());
    ReplayPlaybackCursorCodec.Cursor cursor = catalogCursorCodec.decode(firstPage.getNextCursor());
    assertEquals(secondTime, cursor.startTime());
    assertEquals(6L, cursor.id());

    when(catalogRepository.findCatalogRows(null, secondTime, 6L, 3, 0))
        .thenReturn(List.<Object[]>of(catalogRow(5L, thirdTime, "recording-3")));
    var finalPage = service.catalog(null, 2, firstPage.getNextCursor());

    assertEquals(1, finalPage.getEntries().size());
    assertFalse(finalPage.isHasMore());
    assertNull(finalPage.getNextCursor());
    verify(catalogRepository).findCatalogRows(null, secondTime, 6L, 3, 0);
  }

  @Test
  void canonicalCatalogPathIgnoresOffsetInputByUsingCursorOnlyOverload() {
    when(catalogRepository.findCatalogRows(null, null, null, 2, 0)).thenReturn(List.of());

    service.catalog(null, 1, null);

    verify(catalogRepository).findCatalogRows(null, null, null, 2, 0);
  }

  @Test
  void framesUseBoundedPaginationAndCanonicalizeRecorderAliases() {
    when(recordingResolver.resolve("recording-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    TickSnapshot first = snapshot(101L, 10L, "{\"worldX\":3200,\"worldY\":3201}");
    TickSnapshot second = snapshot(102L, 11L, "{\"position\":{\"x\":3202,\"y\":3203}}");
    second.setQuestHelper(new ObjectMapper().createObjectNode().put("step", 2));
    TickSnapshot extra = snapshot(103L, 12L, "{\"x\":3204,\"z\":3205}");
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(10L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(first, second, extra)));

    TickLog action = log(21L, 10L, "Combat", "HitNpc", "hit");
    TickLog dialogue = log(22L, 11L, "dialogue", "ChatMessage", "talk");
    TickLog varbit = log(23L, 11L, "Game", "VarBitChanged", "changed");
    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAscIdAsc(
            "debug-1", 10L, 11L))
        .thenReturn(List.of(action, dialogue, varbit));

    ReplayPlaybackFramePageResponse response = service.frames("recording-1", 10L, 2);

    assertEquals(2, response.getFrames().size());
    assertTrue(response.isHasMore());
    ReplayPlaybackFrameCursorCodec.Cursor nextCursor =
        frameCursorCodec.decode(response.getNextCursor());
    assertEquals(11L, nextCursor.tick());
    assertEquals(102L, nextCursor.id());
    assertEquals(3200, response.getFrames().get(0).getPlayerState().path("x").asInt());
    assertEquals(3203, response.getFrames().get(1).getPlayerState().path("z").asInt());
    assertEquals(2, response.getFrames().get(1).getQuestHelper().path("step").asInt());
    assertEquals(1, response.getFrames().get(0).getLogs().size());
    assertEquals(List.of("ACTION"), response.getFrames().get(0).getLogs().get(0).getCategories());
    assertEquals(2, response.getFrames().get(1).getLogs().size());
    assertEquals(
        List.of("ACTION", "DIALOGUE"),
        response.getFrames().get(1).getLogs().get(0).getCategories());
    assertEquals(
        List.of("ACTION", "VARBIT"),
        response.getFrames().get(1).getLogs().get(1).getCategories());
    verify(tickSnapshotRepository)
        .findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(eq("debug-1"), eq(10L), any());
    verify(tickLogRepository)
        .findBySessionIdAndTickBetweenOrderByTickAscIdAsc("debug-1", 10L, 11L);
  }

  @Test
  void frameCursorContinuesThroughDuplicateTicksWithoutRepeatingRows() {
    when(recordingResolver.resolve("recording-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    TickSnapshot first = snapshot(201L, 4L, "{\"x\":1}");
    TickSnapshot second = snapshot(202L, 4L, "{\"x\":2}");
    TickSnapshot third = snapshot(203L, 4L, "{\"x\":3}");
    TickSnapshot fourth = snapshot(204L, 5L, "{\"x\":4}");
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(first, second, third)));
    when(tickSnapshotRepository.findBySessionIdAndAfterCursor(
            eq("debug-1"), eq(4L), eq(202L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(third, fourth)));

    ReplayPlaybackFramePageResponse firstPage = service.frames("recording-1", null, null, 2);
    ReplayPlaybackFrameCursorCodec.Cursor cursor =
        frameCursorCodec.decode(firstPage.getNextCursor());
    ReplayPlaybackFramePageResponse secondPage =
        service.frames("recording-1", null, firstPage.getNextCursor(), 2);

    assertEquals(List.of(4L, 4L), firstPage.getFrames().stream().map(f -> f.getTick()).toList());
    assertEquals(List.of(4L, 5L), secondPage.getFrames().stream().map(f -> f.getTick()).toList());
    assertEquals(4L, cursor.tick());
    assertEquals(202L, cursor.id());
    assertFalse(secondPage.isHasMore());
    assertNull(secondPage.getNextCursor());
    verify(tickSnapshotRepository)
        .findBySessionIdAndAfterCursor(eq("debug-1"), eq(4L), eq(202L), any(Pageable.class));
  }

  @Test
  void frameInputClampsFromTickAndRejectsMixedContinuationParameters() {
    when(recordingResolver.resolve("recording-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    ReplayPlaybackFramePageResponse response = service.frames("recording-1", -100L, 501);

    assertFalse(response.isHasMore());
    assertTrue(response.getFrames().isEmpty());
    assertNull(response.getNextCursor());
    verify(tickSnapshotRepository)
        .findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(eq("debug-1"), eq(0L), any());
    var pageable = forClass(Pageable.class);
    verify(tickSnapshotRepository)
        .findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), pageable.capture());
    assertEquals(501, pageable.getValue().getPageSize());

    clearInvocations(tickSnapshotRepository);
    service.frames("recording-1", null, null, 0);
    var minimumPageable = forClass(Pageable.class);
    verify(tickSnapshotRepository)
        .findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), minimumPageable.capture());
    assertEquals(2, minimumPageable.getValue().getPageSize());

    assertEquals(
        "VALIDATION_ERROR",
        assertThrows(
                ReplayException.class,
                () ->
                    service.frames(
                        "recording-1", 0L, frameCursorCodec.encode(4L, 202L), 100))
            .getErrorCode()
            .name());
  }

  @Test
  void emptyFramePageHasEmptyFramesAndDoesNotQueryLogs() {
    when(recordingResolver.resolve("recording-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    ReplayPlaybackFramePageResponse response = service.frames("recording-1", null, null, 100);

    assertTrue(response.getFrames().isEmpty());
    assertFalse(response.isHasMore());
    assertNull(response.getNextCursor());
    org.mockito.Mockito.verifyNoInteractions(tickLogRepository);
  }

  @Test
  void nestedAndScalarPlayerFieldsRemainCanonicalAndPreserved() {
    when(recordingResolver.resolve("recording-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    TickSnapshot snapshot =
        snapshot(
            301L,
            1L,
            "{"
                + "\"x\":900,\"z\":901,\"worldX\":1,\"worldY\":2,\"y\":3,"
                + "\"hp\":77,\"currentHp\":5,\"prayer\":44,\"currentPrayer\":6,"
                + "\"position\":{\"x\":10,\"y\":11,\"z\":12},"
                + "\"questState\":{\"step\":3}"
                + "}");
    TickSnapshot nestedStats =
        snapshot(
            302L,
            2L,
            "{"
                + "\"position\":{\"x\":20,\"y\":21},"
                + "\"hp\":{\"current\":55,\"max\":99},"
                + "\"prayer\":{\"current\":33,\"max\":50}"
                + "}");
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
            eq("debug-1"), eq(0L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(snapshot, nestedStats)));

    ReplayPlaybackFramePageResponse response = service.frames("recording-1", null, null, 100);

    JsonNode firstPlayer = response.getFrames().get(0).getPlayerState();
    assertEquals(900, firstPlayer.path("x").asInt());
    assertEquals(901, firstPlayer.path("z").asInt());
    assertEquals(77, firstPlayer.path("hp").asInt());
    assertEquals(44, firstPlayer.path("prayer").asInt());
    assertEquals(10, firstPlayer.path("position").path("x").asInt());
    assertEquals(12, firstPlayer.path("position").path("z").asInt());
    assertEquals(3, firstPlayer.path("questState").path("step").asInt());

    JsonNode nestedPlayer = response.getFrames().get(1).getPlayerState();
    assertEquals(20, nestedPlayer.path("x").asInt());
    assertEquals(21, nestedPlayer.path("z").asInt());
    assertEquals(55, nestedPlayer.path("hp").asInt());
    assertEquals(33, nestedPlayer.path("prayer").asInt());
  }

  @Test
  void framesFailWithStableErrorWhenRecordingHasNoDebugSession() {
    when(recordingResolver.resolve("missing"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, null));

    ReplayException error =
        assertThrows(ReplayException.class, () -> service.frames("missing", 0L, 100));

    assertEquals("DEBUG_SESSION_NOT_FOUND", error.getErrorCode().name());
  }

  private Object[] catalogRow(long id, LocalDateTime startTime, String recordingId) {
    return new Object[] {
      id,
      recordingId,
      "debug-" + id,
      "operator",
      "fishing",
      startTime,
      null,
      0L,
      1L,
      1L,
      1L,
      1L
    };
  }

  private TickSnapshot snapshot(long id, long tick, String playerState) {
    try {
      TickSnapshot snapshot = new TickSnapshot(
          "debug-1",
          tick,
          LocalDateTime.of(2026, 1, 1, 0, 0),
          new ObjectMapper().readTree(playerState));
      snapshot.setId(id);
      return snapshot;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private TickLog log(long id, long tick, String subsystem, String eventType, String message) {
    TickLog log =
        new TickLog(
            "debug-1",
            tick,
            LocalDateTime.of(2026, 1, 1, 0, 0),
            subsystem,
            "INFO",
            message);
    log.setId(id);
    log.setEventType(eventType);
    return log;
  }
}
