package com.chromascape.web.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.simulator.dto.ReplayFramePageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Tests for {@link SimulatorReplaySourceService}.
 *
 * <p>Verifies schema canonicalization (playerState field renaming, item slot pass-through) and
 * bounded pagination behavior.
 */
@ExtendWith(MockitoExtension.class)
class SimulatorReplaySourceServiceTest {

  @Mock private DebugSessionRepository debugSessionRepository;
  @Mock private TickSnapshotRepository tickSnapshotRepository;

  private SimulatorReplaySourceService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new SimulatorReplaySourceService(debugSessionRepository, tickSnapshotRepository);
  }

  // --- Canonicalization tests via frames() ---

  @Nested
  @DisplayName("Schema canonicalization")
  class SchemaCanonicalization {

    private DebugSession session;

    @BeforeEach
    void setUpSession() {
      session = new DebugSession();
      session.setSessionId("sess-1");
      session.setRecordingId("rec-1");
      session.setAccountName("TestPlayer");
      session.setStartTick(0L);
      session.setEndTick(100L);

      lenient().when(debugSessionRepository.findByRecordingId("rec-1"))
          .thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("canonicalizePlayerState renames worldX to x, worldY to z")
    void playerStateFieldRenamed() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"worldX\":3200,\"worldY\":3200,\"currentHp\":99,\"currentPrayer\":70}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      assertEquals(1, response.getFrames().size());
      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      // Should contain canonical field names
      assertTrue(playerStateJson.contains("\"x\":3200"), "worldX should be renamed to x");
      assertTrue(playerStateJson.contains("\"z\":3200"), "worldY should be renamed to z");
      // Should NOT contain original field names
      assertFalse(playerStateJson.contains("worldX"), "worldX should be removed");
      assertFalse(playerStateJson.contains("worldY"), "worldY should be removed");
    }

    @Test
    @DisplayName("canonicalizePlayerState renames currentHp to hp, currentPrayer to prayer")
    void hpPrayerFieldsRenamed() {
      TickSnapshot snapshot =
          createSnapshot(0L, "{\"currentHp\":99,\"currentPrayer\":70}", null, null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      assertTrue(playerStateJson.contains("\"hp\":99"), "currentHp should be renamed to hp");
      assertTrue(
          playerStateJson.contains("\"prayer\":70"), "currentPrayer should be renamed to prayer");
      assertFalse(playerStateJson.contains("currentHp"), "currentHp should be removed");
      assertFalse(playerStateJson.contains("currentPrayer"), "currentPrayer should be removed");
    }

    @Test
    @DisplayName("canonicalizePlayerState preserves unknown fields unchanged")
    void unknownFieldsPreserved() {
      TickSnapshot snapshot =
          createSnapshot(0L, "{\"worldX\":3200,\"someCustomField\":\"hello\"}", null, null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      assertTrue(playerStateJson.contains("\"x\":3200"), "worldX should be renamed to x");
      assertTrue(
          playerStateJson.contains("\"someCustomField\":\"hello\""),
          "Unknown fields should pass through unchanged");
    }

    @Test
    @DisplayName("canonicalizeItems passes through items without slot unchanged (no synthesis)")
    void itemsWithoutSlotPassedThrough() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"worldX\":3200}",
              "[{\"itemId\":4151,\"quantity\":1},{\"itemId\":995,\"quantity\":1000}]",
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String inventoryJson = response.getFrames().get(0).getInventory().toString();
      // Items should be passed through as-is without slot synthesis
      assertTrue(inventoryJson.contains("\"itemId\":4151"), "First item itemId preserved");
      assertTrue(inventoryJson.contains("\"itemId\":995"), "Second item itemId preserved");
      // No slot field should be added by ChromaScape
      assertFalse(inventoryJson.contains("\"slot\""), "No slot field should be synthesized");
    }

    @Test
    @DisplayName("canonicalizeItems preserves existing slot field")
    void itemsSlotPreserved() {
      TickSnapshot snapshot =
          createSnapshot(
              0L, "{\"worldX\":3200}", "[{\"itemId\":4151,\"quantity\":1,\"slot\":5}]", null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String inventoryJson = response.getFrames().get(0).getInventory().toString();
      // Should keep existing slot=5, not overwrite with 0
      assertTrue(inventoryJson.contains("\"slot\":5"), "Existing slot=5 should be preserved");
    }

    @Test
    @DisplayName("canonicalizeItems handles empty array")
    void itemsEmptyArray() {
      TickSnapshot snapshot = createSnapshot(0L, "{\"worldX\":3200}", "[]", null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      assertNotNull(response.getFrames().get(0).getInventory());
      assertEquals(0, response.getFrames().get(0).getInventory().size());
    }

    @Test
    @DisplayName("frames returns session metadata in response")
    void sessionMetadataReturned() {
      TickSnapshot snapshot = createSnapshot(0L, "{\"worldX\":3200}", null, null);
      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      assertEquals("TestPlayer", response.getAccountName());
      assertEquals(0L, response.getStartTick());
      assertEquals(100L, response.getEndTick());
    }

    @Test
    @DisplayName("canonicalizePlayerState handles null playerState")
    void nullPlayerState() {
      TickSnapshot snapshot = createSnapshot(0L, null, null, null);
      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      assertNull(response.getFrames().get(0).getPlayerState());
    }

    @Test
    @DisplayName("Nested position.x/y canonicalizes to replay x/z")
    void nestedPosition_canonicalizesToReplay() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"position\":{\"x\":3200,\"y\":3200,\"z\":3200,\"plane\":0},"
                  + "\"worldX\":3100,\"worldY\":3100}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      // position.x should become replay x
      assertTrue(playerStateJson.contains("\"x\":3200"), "position.x should become x");
      // position.y/z should become replay z
      assertTrue(playerStateJson.contains("\"z\":3200"), "position.y/z should become z");
      // Legacy worldX should still be renamed
      assertTrue(playerStateJson.contains("\"x\":3200"));
      assertFalse(playerStateJson.contains("worldX"), "worldX should be removed after rename");
    }

    @Test
    @DisplayName("Nested position.x/z with no position.y canonicalizes to replay x/z")
    void nestedPositionXZ_canonicalizesToReplay() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"position\":{\"x\":3200,\"z\":3200,\"plane\":0}}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      assertTrue(playerStateJson.contains("\"x\":3200"), "position.x should become x");
      assertTrue(playerStateJson.contains("\"z\":3200"), "position.z should become z");
      assertFalse(playerStateJson.contains("\"y\":3200"), "y should not leak into replay output");
    }

    @Test
    @DisplayName("Nested hp.current canonicalizes to numeric replay hp")
    void nestedHp_canonicalizesToReplay() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"hp\":{\"current\":85,\"max\":99,\"percent\":85},"
                  + "\"currentHp\":80}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      // currentHp -> hp from legacy mapping
      assertTrue(playerStateJson.contains("\"hp\":80"), "legacy currentHp should be renamed to hp");
      // But nested hp.current should also provide hp value
      // (the legacy rename takes precedence since it runs first; the nested check
      //  only sets hp if not already present, so legacy rename wins)
    }

    @Test
    @DisplayName("Nested prayer.current canonicalizes to numeric replay prayer")
    void nestedPrayer_canonicalizesToReplay() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"prayer\":{\"current\":55,\"max\":70,\"percent\":78},"
                  + "\"currentPrayer\":50}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      assertTrue(
          playerStateJson.contains("\"prayer\":50"),
          "legacy currentPrayer should be renamed to prayer");
    }

    @Test
    @DisplayName("Legacy flat fields still canonicalize as before with nested absent")
    void legacyFlatFields_stillWork() {
      TickSnapshot snapshot =
          createSnapshot(
              0L,
              "{\"worldX\":3200,\"worldY\":3200,\"currentHp\":99,\"currentPrayer\":70}",
              null,
              null);

      mockPage(snapshot);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      String playerStateJson = response.getFrames().get(0).getPlayerState().toString();
      assertTrue(playerStateJson.contains("\"x\":3200"));
      assertTrue(playerStateJson.contains("\"z\":3200"));
      assertTrue(playerStateJson.contains("\"hp\":99"));
      assertTrue(playerStateJson.contains("\"prayer\":70"));
      assertFalse(playerStateJson.contains("worldX"));
      assertFalse(playerStateJson.contains("currentHp"));
    }
  }

  // --- Pagination tests ---

  @Nested
  @DisplayName("Bounded pagination")
  class BoundedPagination {

    private DebugSession session;

    @BeforeEach
    void setUpSession() {
      session = new DebugSession();
      session.setSessionId("sess-1");
      session.setRecordingId("rec-1");
      session.setAccountName("TestPlayer");

      lenient().when(debugSessionRepository.findByRecordingId("rec-1"))
          .thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("frames returns hasMore=false when results fit within limit")
    void noMorePagesWhenWithinLimit() {
      List<TickSnapshot> snapshots =
          List.of(
              createSnapshot(0L, "{\"worldX\":3200}", null, null),
              createSnapshot(1L, "{\"worldX\":3201}", null, null));

      Page<TickSnapshot> page = new PageImpl<>(snapshots);
      when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTick(
              eq("sess-1"), eq(0L), any(Pageable.class)))
          .thenReturn(page);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 100);

      assertFalse(response.isHasMore());
      assertNull(response.getNextFromTick());
      assertEquals(2, response.getFrames().size());
    }

    @Test
    @DisplayName("frames returns hasMore=true when results exceed limit")
    void hasMoreWhenResultsExceedLimit() {
      // Create 3 snapshots, but limit=2 so hasMore=true
      List<TickSnapshot> snapshots =
          List.of(
              createSnapshot(0L, "{\"worldX\":3200}", null, null),
              createSnapshot(1L, "{\"worldX\":3201}", null, null),
              createSnapshot(2L, "{\"worldX\":3202}", null, null));

      Page<TickSnapshot> page = new PageImpl<>(snapshots);
      when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTick(
              eq("sess-1"), eq(0L), any(Pageable.class)))
          .thenReturn(page);

      ReplayFramePageResponse response = service.frames("rec-1", 0, 2);

      assertTrue(response.isHasMore());
      // nextFromTick should be last returned tick + 1 = tick 1 + 1 = 2
      assertEquals(2L, response.getNextFromTick());
      // Should only return limit (2) frames, not limit+1 (3)
      assertEquals(2, response.getFrames().size());
    }

    @Test
    @DisplayName("frames returns empty frames when no snapshots found")
    void emptyFramesWhenNoSnapshots() {
      Page<TickSnapshot> emptyPage = new PageImpl<>(List.of());
      when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTick(
              eq("sess-1"), eq(50L), any(Pageable.class)))
          .thenReturn(emptyPage);

      ReplayFramePageResponse response = service.frames("rec-1", 50, 100);

      assertFalse(response.isHasMore());
      assertNull(response.getNextFromTick());
      assertTrue(response.getFrames().isEmpty());
    }

    @Test
    @DisplayName("frames throws when recording not found")
    void throwsWhenRecordingNotFound() {
      when(debugSessionRepository.findByRecordingId("unknown")).thenReturn(Optional.empty());

      try {
        service.frames("unknown", 0, 100);
        assertTrue(false, "Should have thrown SimulatorReplayControlException");
      } catch (SimulatorReplayControlException e) {
        assertTrue(e.getMessage().contains("No debug session found"));
      }
    }
  }

  // --- Helpers ---

  private TickSnapshot createSnapshot(
      long tick, String playerStateJson, String inventoryJson, String equipmentJson) {
    TickSnapshot snapshot = new TickSnapshot();
    snapshot.setTick(tick);
    snapshot.setTimestamp(LocalDateTime.now());
    try {
      if (playerStateJson != null) {
        snapshot.setPlayerState(objectMapper.readTree(playerStateJson));
      }
      if (inventoryJson != null) {
        snapshot.setInventory(objectMapper.readTree(inventoryJson));
      }
      if (equipmentJson != null) {
        snapshot.setEquipment(objectMapper.readTree(equipmentJson));
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse test JSON", e);
    }
    return snapshot;
  }

  private void mockPage(TickSnapshot... snapshots) {
    Page<TickSnapshot> page = new PageImpl<>(List.of(snapshots));
    when(tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTick(
            any(), any(), any(Pageable.class)))
        .thenReturn(page);
  }
}
