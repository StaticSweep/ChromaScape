package com.chromascape.web.replay.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.catalog.dto.ReplayPreflightRequest;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Preflight fidelity: causal simulation is rejected whenever recorded evidence is incomplete — no
 * synthesized slots, stable ids, positions, world ids, or equipment are tolerated. The shared
 * {@link ReplayEvidenceValidator} interpretation applies to EVERY tick: gaps, null schema versions,
 * and later-tick curated-action evidence all block before compilation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayPreflightFidelityTest {

  @Mock TickSnapshotRepository snapshotRepository;
  @Mock RecordingCurationRepository curationRepository;

  @Mock
  com.chromascape.web.replay.curation.repository.CuratedActionRepository curatedActionRepository;

  @Mock ReplayCapabilityService capabilityService;
  @Mock ReplayRecordingResolver resolver;

  private RecordingIntegrityService integrity;
  private final ObjectMapper mapper = new ObjectMapper();

  private DebugSession session;

  @BeforeEach
  void setUp() {
    integrity = new RecordingIntegrityService(snapshotRepository);
    session = new DebugSession();
    session.setSessionId("sess-pf");
    session.setStartTime(LocalDateTime.of(2026, 1, 1, 0, 0));
    session.setEndTime(LocalDateTime.of(2026, 1, 1, 0, 5));
    ResolvedRecording resolved = new ResolvedRecording(null, session);
    lenient().when(snapshotRepository.countBySessionId(anyString())).thenReturn(2L);
    lenient()
        .when(snapshotRepository.findMinTickBySessionId(anyString()))
        .thenReturn(Optional.of(0L));
    lenient()
        .when(snapshotRepository.findMaxTickBySessionId(anyString()))
        .thenReturn(Optional.of(1L));
    // Preflight now checks each accepted action against the live capability declaration. The
    // mocked service must expose the same complete v1 action surface as a real handshake.
    lenient()
        .when(capabilityService.getManifest())
        .thenReturn(
            CapabilityManifest.current(
                new CapabilityManifest.CapabilityModes(false, false, true),
                new CapabilityManifest.XrspsIdentity(
                    "diagnostic",
                    "a".repeat(40),
                    "sha256:" + "b".repeat(64),
                    "sha256:" + "c".repeat(64)),
                true));
  }

  /** Complete evidence snapshot at an arbitrary tick with the SUPPORTED schema version. */
  private TickSnapshot completeSnapshot(long tick) throws Exception {
    TickSnapshot snap =
        new TickSnapshot(
            "sess-pf",
            tick,
            LocalDateTime.of(2026, 1, 1, 0, 0),
            mapper.readTree(
                """
                            {"position":{"x":3200,"y":3400,"plane":0},
                             "world":{"worldId":330},
                             "collisionRevision":"collision-v7"}"""));
    snap.setSchemaVersion(ReplayEvidenceValidator.SUPPORTED_SNAPSHOT_SCHEMA_VERSION);
    return snap;
  }

  private TickSnapshot snapshot(String playerStateJson) throws Exception {
    TickSnapshot snap =
        new TickSnapshot(
            "sess-pf", 0L, LocalDateTime.of(2026, 1, 1, 0, 0), mapper.readTree(playerStateJson));
    snap.setSchemaVersion(ReplayEvidenceValidator.SUPPORTED_SNAPSHOT_SCHEMA_VERSION);
    return snap;
  }

  private TickSnapshot completeSpotSnapshot(long tick) throws Exception {
    TickSnapshot snap = completeSnapshot(tick);
    snap.setInventory(mapper.readTree("[{\"slot\":0,\"itemId\":303,\"quantity\":1}]"));
    snap.setEquipment(mapper.readTree("[]"));
    snap.setNpcs(
        mapper.readTree(
            "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"}]"));
    return snap;
  }

  private ResolvedRecording resolved() {
    return new ResolvedRecording(null, session);
  }

  private void stubSnapshots(TickSnapshot... snaps) {
    lenient()
        .when(
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
                anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(List.of(snaps));
  }

  @Test
  void completeEvidenceRemainsEligible() throws Exception {
    stubSnapshots(completeSpotSnapshot(0L), completeSpotSnapshot(1L));

    var eligibility = integrity.evaluate(resolved());

    assertTrue(eligibility.playbackEligible());
    assertFalse(
        eligibility.policyReplayEligible(),
        "POLICY_REPLAY is unavailable in v1 directly from the integrity service");
    assertTrue(eligibility.simulationEligible());
  }

  @Test
  void missingWorldIdentityBlocksSimulation() throws Exception {
    TickSnapshot snap = snapshot("{\"position\":{\"x\":1,\"y\":2,\"plane\":0}}");
    stubSnapshots(snap);
    snap.setInventory(mapper.readTree("[]"));
    snap.setEquipment(mapper.readTree("[]"));
    snap.setNpcs(mapper.readTree("[]"));

    var eligibility = integrity.evaluate(resolved());

    assertFalse(eligibility.simulationEligible());
    assertTrue(
        eligibility.blockingReasons().stream()
            .anyMatch(
                r ->
                    r.mode() == ReplayMode.CAUSAL_SIMULATION
                        && String.valueOf(r.message()).toLowerCase().contains("world")),
        "missing world id/collision revision must produce a causal blocker");
  }

  @Test
  void inventoryItemsWithoutExplicitSlotsBlockSimulation() throws Exception {
    TickSnapshot first = completeSpotSnapshot(0L);
    TickSnapshot second = completeSpotSnapshot(1L);
    // NO explicit slot on the item carried at the INITIAL tick.
    first.setInventory(mapper.readTree("[{\"itemId\":303,\"quantity\":1}]"));
    stubSnapshots(first, second);

    var eligibility = integrity.evaluate(resolved());

    assertFalse(
        eligibility.simulationEligible(), "array position can never stand in for slot evidence");
  }

  @Test
  void missingEquipmentCaptureBlocksSimulation() throws Exception {
    TickSnapshot first = completeSpotSnapshot(0L);
    TickSnapshot second = completeSpotSnapshot(1L);
    second.setEquipment(null); // equipment capture missing entirely on this snapshot set
    first.setEquipment(null);
    stubSnapshots(first, second);

    var eligibility = integrity.evaluate(resolved());

    assertFalse(eligibility.simulationEligible());
  }

  @Test
  void npcsWithoutNumericIdOrPositionBlockSimulation() throws Exception {
    TickSnapshot snap =
        snapshot(
            """
            {"position":{"x":3200,"y":3400,"plane":0},
             "world":{"worldId":330},"collisionRevision":"collision-v7"}""");
    stubSnapshots(snap);
    snap.setInventory(mapper.readTree("[]"));
    snap.setEquipment(mapper.readTree("[]"));
    snap.setNpcs(
        mapper.readTree(
            "[{\"stableId\":\"spot:a:1\",\"spotType\":\"net\",\"position\":{\"x\":1}}]"));

    var eligibility = integrity.evaluate(resolved());

    assertFalse(
        eligibility.simulationEligible(),
        "NPC evidence without numeric id and full position/plane is unusable");
  }

  // ── shared-validator coverage added by the gap-closure plan ──

  @Test
  void tickGapsBlockSimulation() throws Exception {
    // Range [0,1] but only the tick-0 row exists: the tick-1 snapshot is MISSING.
    TickSnapshot only = completeSpotSnapshot(0L);
    stubSnapshots(only);
    lenient().when(snapshotRepository.countBySessionId(anyString())).thenReturn(1L);

    var eligibility = integrity.evaluate(resolved());

    assertFalse(eligibility.simulationEligible(), "a tick gap must block simulation");
    assertTrue(
        eligibility.blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code() == com.chromascape.web.replay.error.ReplayErrorCode.TICK_GAP_DETECTED),
        "the blocker must name TICK_GAP_DETECTED");
  }

  @Test
  void nullSchemaVersionBlocksSimulation() throws Exception {
    TickSnapshot good = completeSpotSnapshot(0L);
    TickSnapshot unversioned = completeSpotSnapshot(1L);
    unversioned.setSchemaVersion(null); // explicit schema version MISSING at a later tick
    stubSnapshots(good, unversioned);

    var eligibility = integrity.evaluate(resolved());

    assertFalse(
        eligibility.simulationEligible(),
        "a null schema version can never inherit the supported version");
    assertTrue(
        eligibility.blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                        == com.chromascape.web.replay.error.ReplayErrorCode
                            .SCHEMA_VERSION_UNSUPPORTED));
  }

  @Test
  void unsupportedSchemaVersionBlocksSimulation() throws Exception {
    TickSnapshot good = completeSpotSnapshot(0L);
    TickSnapshot future = completeSpotSnapshot(1L);
    future.setSchemaVersion(ReplayEvidenceValidator.SUPPORTED_SNAPSHOT_SCHEMA_VERSION + 1);
    stubSnapshots(good, future);

    var eligibility = integrity.evaluate(resolved());

    assertFalse(eligibility.simulationEligible());
  }

  // ── POST preflight curation-version validation ──

  private ReplayPreflightService postPreflightService() {
    // The resolver must hand back the SAME debug session the curations claim.
    lenient().when(resolver.resolve(anyString())).thenReturn(new ResolvedRecording(null, session));
    return new ReplayPreflightService(
        resolver,
        integrity,
        capabilityService,
        curationRepository,
        curatedActionRepository,
        snapshotRepository);
  }

  private RecordingCuration curation(
      int version, String recordingId, String sessionId, boolean published) {
    RecordingCuration curation = new RecordingCuration();
    curation.setId(java.util.UUID.randomUUID());
    curation.setVersion(version);
    curation.setRecordingId(recordingId);
    curation.setDebugSessionId(sessionId);
    curation.setState(
        published
            ? com.chromascape.web.replay.curation.entity.CurationState.PUBLISHED
            : com.chromascape.web.replay.curation.entity.CurationState.DRAFT);
    return curation;
  }

  @Test
  void causalPostWithoutCurationVersionIsBlocked() {
    lenient().when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, null));

    assertFalse(response.eligibility().simulationEligible());
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.mode() == ReplayMode.CAUSAL_SIMULATION
                        && r.message().contains("curationVersion")));
  }

  @Test
  void wrongCurationVersionIsABlockNotASilentSubstitute() {
    lenient().when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);
    lenient()
        .when(curationRepository.findByRecordingIdAndVersion(anyString(), anyInt()))
        .thenReturn(Optional.empty());

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 7));

    assertFalse(
        response.eligibility().simulationEligible(),
        "preflight must resolve EXACTLY the requested version — never fall back");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                        == com.chromascape.web.replay.error.ReplayErrorCode
                            .CURATION_NOT_PUBLISHED));
  }

  @Test
  void unpublishedCurationIsBlocked() {
    lenient().when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);
    lenient()
        .when(curationRepository.findByRecordingIdAndVersion(anyString(), anyInt()))
        .thenReturn(Optional.of(curation(3, "rec-1", "sess-pf", false)));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(response.eligibility().simulationEligible());
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                            == com.chromascape.web.replay.error.ReplayErrorCode
                                .CURATION_NOT_PUBLISHED
                        && r.message().contains("not PUBLISHED")));
  }

  @Test
  void foreignCurationIsBlockedEvenWhenPublishedForAnotherSession() {
    lenient().when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);
    lenient()
        .when(curationRepository.findByRecordingIdAndVersion(anyString(), anyInt()))
        .thenReturn(Optional.of(curation(3, "rec-1", "OTHER-session", true)));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "a published curation for another debug session must never look compile-ready");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                        == com.chromascape.web.replay.error.ReplayErrorCode.CURATION_IMMUTABLE));
  }

  // ── POST preflight validates the SELECTED curation's ACTUAL actions ──

  private com.chromascape.web.replay.curation.entity.CuratedAction action(
      long tick, int seq, String status, String type, String targetId, String argumentsJson) {
    var ca = new com.chromascape.web.replay.curation.entity.CuratedAction();
    ca.setCurationId(java.util.UUID.randomUUID());
    ca.setTick(tick);
    ca.setSequence(seq);
    ca.setStatus(status);
    ca.setSemanticActionType(type);
    ca.setTargetStableId(targetId);
    try {
      ca.setArgumentsJson(mapper.readTree(argumentsJson));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return ca;
  }

  /** A PUBLISHED v3 curation for rec-1/sess-pf whose actions are the given rows. */
  @SafeVarargs
  private void stubPublishedActions(
      com.chromascape.web.replay.curation.entity.CuratedAction... actions) {
    try {
      stubPublishedActionsInner(actions);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SafeVarargs
  private void stubPublishedActionsInner(
      com.chromascape.web.replay.curation.entity.CuratedAction... actions) throws Exception {
    lenient().when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);
    var c = curation(3, "rec-1", "sess-pf", true);
    lenient()
        .when(curationRepository.findByRecordingIdAndVersion(anyString(), anyInt()))
        .thenReturn(Optional.of(c));
    lenient()
        .when(curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(c.getId()))
        .thenReturn(java.util.List.of(actions));
    stubSnapshots(completeSpotSnapshot(0L), completeSpotSnapshot(1L));
  }

  @Test
  void actionWithoutSnapshotAtItsOwnTickBlocksPostPreflight() {
    stubPublishedActions(
        action(5L, 0, "ACCEPTED_LABEL", "INTERACT_NPC", "spot:a:1", "{\"option\":\"net\"}"));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "an action on an uncaptured tick must block preflight before compilation");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                            == com.chromascape.web.replay.error.ReplayErrorCode
                                .ACTION_TARGET_UNRESOLVED
                        && r.message().contains("tick 5")));
  }

  @Test
  void duplicateTargetAtTheActionTickBlocksPostPreflight() throws Exception {
    TickSnapshot duplicated = completeSpotSnapshot(0L);
    duplicated.setNpcs(
        mapper.readTree(
            "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"},"
                + "{\"stableId\":\"spot:a:1\",\"npcId\":332,\"position\":{\"x\":4,\"y\":5,\"plane\":0},\"spotType\":\"net\"}]"));
    stubPublishedActions(
        action(0L, 0, "ACCEPTED_LABEL", "INTERACT_NPC", "spot:a:1", "{\"option\":\"net\"}"));
    // Must run AFTER stubPublishedActions, which re-stubs the default snapshot pair.
    stubSnapshots(duplicated, completeSpotSnapshot(1L));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(response.eligibility().simulationEligible());
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code()
                            == com.chromascape.web.replay.error.ReplayErrorCode
                                .ACTION_TARGET_UNRESOLVED
                        && r.message().contains("2 times")),
        "a duplicated stable target id must be a causal blocker");
  }

  @Test
  void unsupportedAndUnknownActionStatusesBlockWhileIgnoredIsDropped() {
    stubPublishedActions(
        action(0L, 0, "UNSUPPORTED", "INTERACT_NPC", "spot:a:1", "{\"option\":\"net\"}"),
        action(1L, 0, "SOMETHING_ELSE", "INTERACT_NPC", "spot:a:1", "{\"option\":\"net\"}"));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "UNSUPPORTED and unknown label statuses can never look compile-ready");
    long statusBlockers =
        response.eligibility().blockingReasons().stream()
            .filter(
                r ->
                    r.code() == com.chromascape.web.replay.error.ReplayErrorCode.ACTION_UNSUPPORTED)
            .count();
    assertTrue(statusBlockers >= 2, "both bad statuses must be reported");
  }

  @Test
  void explicitlyIgnoredActionsAreDroppedAndDoNotBlock() {
    stubPublishedActions(
        action(0L, 0, "IGNORED", "INTERACT_NPC", "spot:gone:1", "{\"option\":\"net\"}"),
        action(1L, 0, "ACCEPTED_LABEL", "WAIT_UNTIL_TICK", null, "{}"));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertTrue(
        response.eligibility().simulationEligible(),
        "explicitly IGNORED actions are dropped; the surviving WAIT action is evidence-clean");
  }

  @Test
  void referencedGenericNpcWithoutRecordedOptionBlocksPostPreflight() throws Exception {
    TickSnapshot crayfish = completeSpotSnapshot(0L);
    crayfish.setNpcs(
        mapper.readTree(
            "[{\"stableId\":\"spot:a:1\",\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"crayfish\"}]"));
    stubPublishedActions(
        action(0L, 0, "ACCEPTED_LABEL", "INTERACT_NPC", "spot:a:1", "{\"option\":\"net\"}"));
    // Must run AFTER stubPublishedActions, which re-stubs the default snapshot pair.
    stubSnapshots(crayfish, completeSpotSnapshot(1L));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "a referenced generic NPC without captured menu options must block preflight");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code() == com.chromascape.web.replay.error.ReplayErrorCode.ACTION_UNSUPPORTED
                        && r.message().contains("spot:a:1")));
  }

  @Test
  void curatedOptionMismatchingRecordedSpotTypeBlocksPostPreflight() {
    stubPublishedActions(
        action(
            0L,
            0,
            "ACCEPTED_LABEL",
            "INTERACT_NPC",
            "spot:a:1",
            "{\"option\":\"bait\"}")); // recorded spotType is net

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "preflight must catch what compilation would reject: option != recorded spotType");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code() == com.chromascape.web.replay.error.ReplayErrorCode.ACTION_UNSUPPORTED
                        && r.message().contains("'bait'")));
  }

  @Test
  void duplicatedTickRowsBlockPostPreflightEvenWhenCountsMatch() throws Exception {
    stubPublishedActions();
    // Right COUNT for range [0,1], wrong TICKS: 0,0 instead of 0,1.
    stubSnapshots(completeSpotSnapshot(0L), completeSpotSnapshot(0L));

    var response =
        postPreflightService()
            .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));

    assertFalse(
        response.eligibility().simulationEligible(),
        "count equality alone must never pass a duplicated tick sequence");
    assertTrue(
        response.eligibility().blockingReasons().stream()
            .anyMatch(
                r ->
                    r.code() == com.chromascape.web.replay.error.ReplayErrorCode.TICK_GAP_DETECTED
                        && r.message().toLowerCase().contains("duplicate")));
  }

  @Test
  void malformedEquipmentBlocksPostPreflight() throws Exception {
    TickSnapshot badEquipment = completeSpotSnapshot(0L);
    badEquipment.setEquipment(mapper.readTree("[{\"itemId\":1129,\"slot\":99}]"));
    TickSnapshot duplicateSlot = completeSpotSnapshot(0L);
    duplicateSlot.setEquipment(
        mapper.readTree("[{\"itemId\":1129,\"slot\":3},{\"itemId\":1131,\"slot\":3}]"));
    stubPublishedActions();

    for (TickSnapshot broken : List.of(badEquipment, duplicateSlot)) {
      stubSnapshots(broken, completeSpotSnapshot(1L));
      var response =
          postPreflightService()
              .preflight("rec-1", new ReplayPreflightRequest(ReplayMode.CAUSAL_SIMULATION, 3));
      assertFalse(
          response.eligibility().simulationEligible(),
          "out-of-range or duplicate equipment slots must block preflight");
      assertTrue(
          response.eligibility().blockingReasons().stream()
              .anyMatch(
                  r ->
                      r.code()
                              == com.chromascape.web.replay.error.ReplayErrorCode
                                  .SIM_INITIAL_WORLD_INCOMPLETE
                          && r.message().contains("quipment")));
    }
  }
}
