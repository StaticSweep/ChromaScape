package com.chromascape.web.replay.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Compiler identity: checksums cover ACTUAL snapshot evidence content, so changing recorded
 * evidence changes the manifest even when metadata is unchanged; identical inputs stay
 * byte-identical (idempotent publish).
 */
@ExtendWith(MockitoExtension.class)
class ScenarioCompilerEvidenceHashingTest {

  @Mock private ReplayRecordingResolver resolver;
  @Mock private TickSnapshotRepository snapshotRepository;
  @Mock private RecordingCurationRepository curationRepository;
  @Mock private CuratedActionRepository curatedActionRepository;
  @Mock private ScenarioManifestRepository manifestRepository;
  @Mock private ReplayCapabilityService capabilityService;
  @Mock private ScenarioManifestInsertService insertService;
  @Mock private ScenarioManifestWinnerReader winnerReader;

  private final CanonicalProjectionService projection = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  private ScenarioCompiler compiler;
  private DebugSession session;
  private RecordingCuration curation;

  @BeforeEach
  void setUp() {
    compiler =
        new ScenarioCompiler(
            resolver,
            snapshotRepository,
            curationRepository,
            curatedActionRepository,
            manifestRepository,
            capabilityService,
            projection,
            mapper,
            insertService,
            winnerReader);

    session = new DebugSession();
    session.setSessionId("sess-1");
    session.setStartTime(LocalDateTime.of(2026, 1, 1, 0, 0));
    session.setEndTime(LocalDateTime.of(2026, 1, 1, 0, 5));

    curation = new RecordingCuration();
    curation.setId(UUID.randomUUID());
    curation.setRecordingId("rec-1");
    curation.setDebugSessionId("sess-1");
    curation.setState(CurationState.PUBLISHED);
    curation.setContentChecksum("sha256:" + "a".repeat(64));

    lenient().when(curationRepository.findById(curation.getId())).thenReturn(Optional.of(curation));
  }

  private TickSnapshot snapshot(long tick, String fishingXp) throws Exception {
    TickSnapshot snap =
        new TickSnapshot(
            "sess-1",
            tick,
            LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(tick),
            mapper.readTree(
                """
                        {"position":{"x":3200,"y":3400,"plane":0},
                         "world":{"worldId":330},
                         "collisionRevision":"collision-v7",
                         "skills":{"fishing":{"level":40,"xp":%s}}}"""
                    .formatted(fishingXp)));
    snap.setInventory(mapper.readTree("[{\"slot\":0,\"itemId\":303,\"quantity\":1}]"));
    snap.setEquipment(mapper.readTree("[]"));
    snap.setNpcs(
        mapper.readTree(
            """
                [{"stableId":"npc:sess-1:0","npcId":331,"position":{"x":3201,"y":3402,"plane":0},
                  "spotType":"net"}]"""));
    snap.setSchemaVersion(1);
    return snap;
  }

  private void stubHappyPath(TickSnapshot first, List<TickSnapshot> all) {
    when(resolver.resolve("rec-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    when(snapshotRepository.findMinTickBySessionId("sess-1")).thenReturn(Optional.of(10L));
    when(snapshotRepository.findMaxTickBySessionId("sess-1")).thenReturn(Optional.of(11L));
    when(snapshotRepository.countBySessionId("sess-1")).thenReturn(2L);
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            eq("sess-1"), anyLong(), anyLong()))
        .thenReturn(all);
    when(curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(curation.getId()))
        .thenReturn(List.of());
    lenient().when(manifestRepository.currentTimestamp()).thenReturn(LocalDateTime.now());
    CapabilityManifest.XrspsIdentity xrsps =
        new CapabilityManifest.XrspsIdentity(
            "adapter-1", "commit-1", "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64));
    when(capabilityService.isCausalSimulationCertifiable()).thenReturn(true);
    when(capabilityService.getManifest())
        .thenReturn(
            CapabilityManifest.current(
                new CapabilityManifest.CapabilityModes(true, false, true), xrsps, true));
    lenient().when(insertService.insert(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void identicalEvidenceProducesByteIdenticalManifest() throws Exception {
    TickSnapshot s10 = snapshot(10, "1000");
    TickSnapshot s11 = snapshot(11, "1150");
    stubHappyPath(s10, List.of(s10, s11));

    ScenarioManifest first = compiler.compile("rec-1", curation.getId(), null);
    ScenarioManifest second = compiler.compile("rec-1", curation.getId(), null);

    assertEquals(
        first.getManifestChecksum(),
        second.getManifestChecksum(),
        "same inputs compile to the same manifest identity");
    assertEquals(
        first.getManifestJson().toString(),
        second.getManifestJson().toString(),
        "manifest documents are byte-identical after canonicalization");
  }

  @Test
  void changingSnapshotContentChangesTheRecordingIdentity() throws Exception {
    // Identical metadata (ids, range, count, schema version) — only the
    // recorded XP value inside one snapshot differs.
    TickSnapshot s10a = snapshot(10, "1000");
    TickSnapshot s11a = snapshot(11, "1150");
    stubHappyPath(s10a, List.of(s10a, s11a));
    ScenarioManifest baseline = compiler.compile("rec-1", curation.getId(), null);

    TickSnapshot s10b = snapshot(10, "1000");
    TickSnapshot s11b = snapshot(11, "1200"); // evidence changed here
    when(resolver.resolve("rec-1"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    when(snapshotRepository.findMinTickBySessionId("sess-1")).thenReturn(Optional.of(10L));
    when(snapshotRepository.findMaxTickBySessionId("sess-1")).thenReturn(Optional.of(11L));
    when(snapshotRepository.countBySessionId("sess-1")).thenReturn(2L);
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
            eq("sess-1"), anyLong(), anyLong()))
        .thenReturn(List.of(s10b, s11b));
    when(curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(curation.getId()))
        .thenReturn(List.of());

    ScenarioManifest altered = compiler.compile("rec-1", curation.getId(), null);

    assertNotEquals(
        baseline.getRecordingChecksum(),
        altered.getRecordingChecksum(),
        "recording checksum must cover snapshot content, not just metadata");
    assertNotEquals(
        baseline.getDebugSessionChecksum(),
        altered.getDebugSessionChecksum(),
        "session checksum must cover snapshot content");
    assertNotEquals(
        baseline.getManifestChecksum(),
        altered.getManifestChecksum(),
        "manifest identity must change with evidence");
  }
}
