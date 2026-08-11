package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests recording loading, alignment, and derived validation telemetry. */
class FishingRecordingLoaderTest {

  private ObjectMapper objectMapper;
  private TickLogRepository logRepository;
  private TickSnapshotRepository snapshotRepository;
  private FishingRecordingLoader loader;
  private DebugSession session;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    logRepository = mock(TickLogRepository.class);
    snapshotRepository = mock(TickSnapshotRepository.class);
    loader = new FishingRecordingLoader(logRepository, snapshotRepository, objectMapper);

    session = new DebugSession("debug_1", "acct", "script", LocalDateTime.now(), 0L);
    session.setRecordingId("recording_1");
    session.setEndTick(2000L);
  }

  private TickSnapshot snapshot(
      long tick, int inventoryTotal, long fishingXp, List<String> npcNames) {
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 100);
    position.put("y", 100);
    position.put("z", 100);
    position.put("plane", 0);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("worldX", 100);
    playerState.put("worldY", 100);
    playerState.put("plane", 0);
    playerState.put("isMoving", false);
    playerState.put("isIdle", true);
    playerState.put("animationId", -1);
    Map<String, Object> skills = new LinkedHashMap<>();
    Map<String, Object> fishing = new LinkedHashMap<>();
    fishing.put("xp", fishingXp);
    skills.put("FISHING", fishing);
    playerState.put("skills", skills);

    TickSnapshot snap =
        new TickSnapshot("debug_1", tick, LocalDateTime.now(), objectMapper.valueToTree(playerState));
    List<Map<String, Object>> inventory = new ArrayList<>();
    if (inventoryTotal > 0) {
      inventory.add(Map.of("slot", 0, "itemId", 1, "quantity", inventoryTotal));
    }
    snap.setInventory(objectMapper.valueToTree(inventory));
    List<Map<String, Object>> npcs = new ArrayList<>();
    for (String name : npcNames) {
      npcs.add(Map.of("name", name, "x", 101, "y", 101, "plane", 0));
    }
    snap.setNpcs(objectMapper.valueToTree(npcs));
    return snap;
  }

  private TickLog actionLog(long storedTick, long sourceTick, int sequence, String option) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sourceTick", sourceTick);
    details.put("sequence", sequence);
    details.put("option", option);
    details.put("target", "<col=ffff00>Fishing spot");
    details.put("menuAction", "NPC_FIRST_OPTION");
    TickLog logEntry =
        new TickLog("debug_1", storedTick, LocalDateTime.now(), "PLAYER_ACTION", "Info", option + " Fishing spot");
    logEntry.setEventType("MENU_CLICK");
    logEntry.setDetails(objectMapper.valueToTree(details));
    return logEntry;
  }

  private void stubRepos(List<TickSnapshot> snapshots, List<TickLog> logs) {
    when(snapshotRepository.findBySessionIdOrderByTick("debug_1")).thenReturn(snapshots);
    when(logRepository
            .findBySessionIdAndSubsystemIgnoreCaseAndEventTypeIgnoreCaseOrderByTickAsc(
                "debug_1", "PLAYER_ACTION", "MENU_CLICK"))
        .thenReturn(logs);
    when(logRepository.findBySessionIdAndSubsystemIgnoreCaseOrderByTickAsc("debug_1", "PLAYER_ACTION"))
        .thenReturn(logs);
  }

  @Test
  @DisplayName("exact-tick alignment uses the snapshot at the authoritative sourceTick")
  void exactTickAlignment() {
    TickSnapshot exact = snapshot(1234, 1, 100L, List.of("Fishing spot"));
    stubRepos(List.of(exact), List.of(actionLog(1234, 1234, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.alignedActions()).hasSize(1);
    AlignedAction aligned = ctx.alignedActions().get(0);
    assertThat(aligned.snapshotTick()).isEqualTo(1234L);
    assertThat(aligned.fallback()).isFalse();
    assertThat(ctx.allActionsAligned()).isTrue();
  }

  @Test
  @DisplayName("previous-tick fallback uses the snapshot at sourceTick - 1")
  void previousTickFallback() {
    TickSnapshot previous = snapshot(1233, 1, 100L, List.of("Fishing spot"));
    stubRepos(List.of(previous), List.of(actionLog(1234, 1234, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.alignedActions()).hasSize(1);
    AlignedAction aligned = ctx.alignedActions().get(0);
    assertThat(aligned.snapshotTick()).isEqualTo(1233L);
    assertThat(aligned.fallback()).isTrue();
    assertThat(ctx.alignmentFallbackCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a future snapshot is never used for alignment")
  void futureSnapshotNeverUsed() {
    TickSnapshot future = snapshot(1235, 1, 100L, List.of("Fishing spot"));
    stubRepos(List.of(future), List.of(actionLog(1234, 1234, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.alignedActions()).isEmpty();
    assertThat(ctx.unalignedActionCount()).isEqualTo(1);
    assertThat(ctx.allActionsAligned()).isFalse();
  }

  @Test
  @DisplayName("same-tick actions keep sourceTick and sequence ordering")
  void orderingBySourceTickAndSequence() {
    TickSnapshot snap = snapshot(1000, 1, 100L, List.of("Fishing spot"));
    stubRepos(
        List.of(snap),
        List.of(
            actionLog(1001, 1001, 1, "Net"),
            actionLog(1000, 1000, 0, "Net"),
            actionLog(1000, 1000, 1, "Bait")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.playerActions()).hasSize(3);
    assertThat(ctx.playerActions().get(0).sourceTick()).isEqualTo(1000L);
    assertThat(ctx.playerActions().get(0).sequence()).isEqualTo(0);
    assertThat(ctx.playerActions().get(1).sourceTick()).isEqualTo(1000L);
    assertThat(ctx.playerActions().get(1).sequence()).isEqualTo(1);
    assertThat(ctx.playerActions().get(2).sourceTick()).isEqualTo(1001L);
  }

  @Test
  @DisplayName("catch delta and fishing XP are derived from snapshots")
  void catchDeltaAndXpDerived() {
    TickSnapshot first = snapshot(1000, 1, 100L, List.of("Fishing spot"));
    TickSnapshot second = snapshot(1100, 3, 120L, List.of("Fishing spot"));
    TickSnapshot third = snapshot(1200, 6, 140L, List.of("Fishing spot"));
    stubRepos(List.of(first, second, third), List.of(actionLog(1000, 1000, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.initialInventoryTotal()).isEqualTo(1);
    assertThat(ctx.finalInventoryTotal()).isEqualTo(6);
    assertThat(ctx.catchDelta()).isEqualTo(5);
    assertThat(ctx.positiveInventoryAdditions()).isEqualTo(2);
    assertThat(ctx.firstFishingXp()).isEqualTo(100L);
    assertThat(ctx.lastFishingXp()).isEqualTo(140L);
    assertThat(ctx.fishingXpIncreased()).isTrue();
  }

  @Test
  @DisplayName("unsupported actions are classified and counted")
  void unsupportedActionsCounted() {
    TickSnapshot snap = snapshot(1000, 1, 100L, List.of("Fishing spot"));
    Map<String, Object> walkDetails = new LinkedHashMap<>();
    walkDetails.put("sourceTick", 1010);
    walkDetails.put("sequence", 0);
    walkDetails.put("option", "Walk here");
    walkDetails.put("target", "Somewhere");
    walkDetails.put("menuAction", "WALK");
    TickLog walk =
        new TickLog("debug_1", 1010L, LocalDateTime.now(), "PLAYER_ACTION", "Info", "Walk here");
    walk.setEventType("MENU_CLICK");
    walk.setDetails(objectMapper.valueToTree(walkDetails));

    stubRepos(List.of(snap), List.of(actionLog(1000, 1000, 0, "Net"), walk));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.supportedActions()).hasSize(1);
    assertThat(ctx.unsupportedActions()).hasSize(1);
  }

  @Test
  @DisplayName("action queue overflow is detected")
  void overflowDetected() {
    TickSnapshot snap = snapshot(1000, 1, 100L, List.of("Fishing spot"));
    TickLog overflow =
        new TickLog("debug_1", 1000L, LocalDateTime.now(), "PLAYER_ACTION", "Error", "overflow");
    overflow.setEventType("ACTION_QUEUE_OVERFLOW");

    stubRepos(
        List.of(snap),
        List.of(
            actionLog(1000, 1000, 0, "Net"),
            overflow));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.overflowDetected()).isTrue();
  }

  @Test
  @DisplayName("fishing spot NPC telemetry is detected")
  void npcTelemetryPresent() {
    TickSnapshot snap = snapshot(1000, 1, 100L, List.of("<col=ffff00>Fishing spot"));
    stubRepos(List.of(snap), List.of(actionLog(1000, 1000, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.npcTelemetryPresent()).isTrue();
    assertThat(ctx.playerStatePresent()).isTrue();
    assertThat(ctx.inventoryPresent()).isTrue();
  }

  @Test
  @DisplayName("missing NPC telemetry is reported")
  void npcTelemetryMissing() {
    TickSnapshot snap = snapshot(1000, 1, 100L, List.of("Goblin"));
    stubRepos(List.of(snap), List.of(actionLog(1000, 1000, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.npcTelemetryPresent()).isFalse();
  }

  @Test
  @DisplayName("fishing XP unchanged is detected")
  void fishingXpUnchanged() {
    TickSnapshot first = snapshot(1000, 1, 100L, List.of("Fishing spot"));
    TickSnapshot second = snapshot(1200, 6, 100L, List.of("Fishing spot"));
    stubRepos(List.of(first, second), List.of(actionLog(1000, 1000, 0, "Net")));

    RecordingContext ctx = loader.load(session);
    assertThat(ctx.fishingXpIncreased()).isFalse();
  }
}
