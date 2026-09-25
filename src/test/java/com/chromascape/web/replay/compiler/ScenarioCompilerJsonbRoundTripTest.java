package com.chromascape.web.replay.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

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
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Compiler idempotency against PostgreSQL jsonb semantics: key reordering inside the stored
 * document can NEVER cause a false checksum collision, and a genuine concurrent-insert race
 * resolves through canonical comparison — identical bytes win; different bytes fail closed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScenarioCompilerJsonbRoundTripTest {

  @Mock ReplayRecordingResolver resolver;
  @Mock TickSnapshotRepository snapshotRepository;
  @Mock RecordingCurationRepository curationRepository;
  @Mock CuratedActionRepository curatedActionRepository;
  @Mock ScenarioManifestRepository manifestRepository;
  @Mock ReplayCapabilityService capabilityService;
  @Mock ScenarioManifestInsertService insertService;
  @Mock ScenarioManifestWinnerReader winnerReader;

  private final CanonicalProjectionService projection = new CanonicalProjectionService();
  private final ObjectMapper mapper = new ObjectMapper();

  private ScenarioCompiler compiler;
  private RecordingCuration curation;

  private static final String CACHE_REV = "sha256:" + "1".repeat(64);
  private static final String CONTENT_REV = "sha256:" + "2".repeat(64);

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

    DebugSession session = new DebugSession();
    session.setSessionId("sess-rt");
    session.setStartTime(LocalDateTime.of(2026, 1, 1, 0, 0));
    session.setEndTime(LocalDateTime.of(2026, 1, 1, 0, 5));

    curation = new RecordingCuration();
    curation.setId(UUID.randomUUID());
    curation.setRecordingId("rec-rt");
    curation.setDebugSessionId("sess-rt");
    curation.setState(CurationState.PUBLISHED);
    curation.setContentChecksum("sha256:" + "a".repeat(64));

    TickSnapshot snap = snapshot();
    org.mockito.Mockito.lenient()
        .when(curationRepository.findById(curation.getId()))
        .thenReturn(Optional.of(curation));
    org.mockito.Mockito.lenient()
        .when(resolver.resolve("rec-rt"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    org.mockito.Mockito.lenient()
        .when(snapshotRepository.findMinTickBySessionId("sess-rt"))
        .thenReturn(Optional.of(10L));
    org.mockito.Mockito.lenient()
        .when(snapshotRepository.findMaxTickBySessionId("sess-rt"))
        .thenReturn(Optional.of(10L));
    org.mockito.Mockito.lenient()
        .when(snapshotRepository.countBySessionId("sess-rt"))
        .thenReturn(1L);
    org.mockito.Mockito.lenient()
        .when(
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
                eq("sess-rt"), anyLong(), anyLong()))
        .thenReturn(List.of(snap));
    org.mockito.Mockito.lenient()
        .when(curatedActionRepository.findByCurationIdOrderByTickAscSequenceAsc(curation.getId()))
        .thenReturn(List.of());
    org.mockito.Mockito.lenient()
        .when(manifestRepository.currentTimestamp())
        .thenReturn(LocalDateTime.now());

    CapabilityManifest.XrspsIdentity xrsps =
        new CapabilityManifest.XrspsIdentity("adapter-9", "c".repeat(40), CACHE_REV, CONTENT_REV);
    org.mockito.Mockito.lenient()
        .when(capabilityService.isCausalSimulationCertifiable())
        .thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(capabilityService.getManifest())
        .thenReturn(
            CapabilityManifest.current(
                new CapabilityManifest.CapabilityModes(true, false, true), xrsps, true));
  }

  private TickSnapshot snapshot() {
    try {
      TickSnapshot snap =
          new TickSnapshot(
              "sess-rt",
              10L,
              LocalDateTime.of(2026, 1, 1, 0, 0),
              mapper.readTree(
                  """
                                {"position":{"x":3200,"y":3400,"plane":0},
                                 "world":{"worldId":330},
                                 "collisionRevision":"collision-v7"}"""));
      snap.setInventory(mapper.readTree("[{\"slot\":0,\"itemId\":303,\"quantity\":1}]"));
      snap.setEquipment(mapper.readTree("[]"));
      snap.setNpcs(
          mapper.readTree(
              """
                [{"stableId":"spot:rt:1","npcId":331,"position":{"x":3201,"y":3402,"plane":0},
                  "spotType":"net"}]"""));
      snap.setSchemaVersion(1);
      return snap;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Simulates what PostgreSQL does to a stored jsonb value on read-back: object keys may come back
   * in a DIFFERENT order than inserted while remaining semantically identical.
   */
  private ScenarioManifest reorderedWinner(byte[] canonicalBytes) throws Exception {
    ObjectMapper paged = new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode doc =
        (com.fasterxml.jackson.databind.node.ObjectNode) paged.readTree(canonicalBytes);
    // Rebuild every object node with reversed key insertion order.
    reorder(doc);
    ScenarioManifest winner = new ScenarioManifest();
    winner.setManifestChecksum(doc.path("manifestChecksum").asText());
    winner.setManifestJson(doc);
    return winner;
  }

  @SuppressWarnings("unchecked")
  private void reorder(com.fasterxml.jackson.databind.node.ObjectNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    java.util.Collections.reverse(names);
    java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> copy =
        new java.util.LinkedHashMap<>();
    for (String name : names) {
      copy.put(name, node.get(name));
    }
    node.removeAll();
    copy.forEach(node::set);
    node.fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().isObject()) {
                reorder((com.fasterxml.jackson.databind.node.ObjectNode) e.getValue());
              }
            });
  }

  @Test
  void jsonbKeyReorderingRemainsIdempotentInsteadOfColliding() throws Exception {
    // First compile inserts and returns its entity.
    byte[] produced = captureCanonicalBytesOfFirstCompile();
    ScenarioManifest stored = reorderedWinner(produced);
    org.mockito.Mockito.when(
            manifestRepository.findByManifestChecksum(stored.getManifestChecksum()))
        .thenReturn(Optional.of(stored));

    ScenarioManifest second = compiler.compile("rec-rt", curation.getId(), null);

    assertEquals(
        stored.getManifestChecksum(),
        second.getManifestChecksum(),
        "a semantically identical stored manifest is returned, never a false collision");
  }

  /** Compiles once with an empty store and captures the exact canonical bytes produced. */
  private byte[] captureCanonicalBytesOfFirstCompile() throws Exception {
    org.mockito.Mockito.when(manifestRepository.findByManifestChecksum(any()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.when(insertService.insert(any()))
        .thenAnswer(
            inv -> {
              ScenarioManifest m = inv.getArgument(0);
              return m;
            });
    ScenarioManifest first = compiler.compile("rec-rt", curation.getId(), null);
    return projection.canonicalJsonBytes(first.getManifestJson());
  }

  @Test
  void concurrentIdenticalCompilesReturnTheCommittedWinner() throws Exception {
    byte[] produced = captureCanonicalBytesOfFirstCompile();
    // Fast path misses; the INSERT loses the unique race; the fresh reader finds a winner
    // whose jsonb-normalized content is IDENTICAL.
    org.mockito.Mockito.when(manifestRepository.findByManifestChecksum(any()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.when(insertService.insert(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate manifest_checksum"));
    ScenarioManifest committed = reorderedWinner(produced);
    org.mockito.Mockito.when(winnerReader.findByChecksum(committed.getManifestChecksum()))
        .thenReturn(Optional.of(committed));

    ScenarioManifest result = compiler.compile("rec-rt", curation.getId(), null);

    assertEquals(committed.getManifestChecksum(), result.getManifestChecksum());
  }

  @Test
  void syntheticCollisionWithDifferentCanonicalContentFailsClosed() throws Exception {
    byte[] produced = captureCanonicalBytesOfFirstCompile();
    org.mockito.Mockito.when(manifestRepository.findByManifestChecksum(any()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.when(insertService.insert(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate manifest_checksum"));
    // Same checksum, TAMPERED content: a true collision must fail closed.
    ScenarioManifest tampered = reorderedWinner(produced);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.getManifestJson())
        .put("seed", "tampered");
    org.mockito.Mockito.when(winnerReader.findByChecksum(tampered.getManifestChecksum()))
        .thenReturn(Optional.of(tampered));

    ReplayException e =
        assertThrows(
            ReplayException.class, () -> compiler.compile("rec-rt", curation.getId(), null));
    assertEquals(ReplayErrorCode.CHECKSUM_MISMATCH, e.getErrorCode());
  }

  @Test
  void recordedEvidenceMissingWorldIdentityBlocksCompilation() throws Exception {
    TickSnapshot incomplete = snapshot();
    incomplete.setPlayerState(mapper.readTree("{\"position\":{\"x\":1,\"y\":2,\"plane\":0}}"));

    org.mockito.Mockito.when(
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
                eq("sess-rt"), anyLong(), anyLong()))
        .thenReturn(List.of(incomplete));

    ReplayException e =
        assertThrows(
            ReplayException.class, () -> compiler.compile("rec-rt", curation.getId(), null));
    assertEquals(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE, e.getErrorCode());
  }

  @Test
  void npcsWithoutExplicitStableIdsBlockCompilation() throws Exception {
    TickSnapshot anonymous = snapshot();
    anonymous.setNpcs(
        mapper.readTree(
            "[{\"npcId\":331,\"position\":{\"x\":1,\"y\":2,\"plane\":0},\"spotType\":\"net\"}]"));

    org.mockito.Mockito.when(
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(
                eq("sess-rt"), anyLong(), anyLong()))
        .thenReturn(List.of(anonymous));

    assertThrows(ReplayException.class, () -> compiler.compile("rec-rt", curation.getId(), null));
  }
}
