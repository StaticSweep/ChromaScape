package com.chromascape.web.replay.manifest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.provenance.BehaviorRunContextService;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver.ResolvedRecording;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler.Compilation;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-contained golden Fishing compile used by the fresh-JVM determinism test.
 *
 * <p>The fixture is intentionally independent of Spring: it wires the compiler with Mockito so a
 * plain child JVM can run the real {@link ScenarioManifestV2Compiler}, including the persistence
 * path that publishes the conversion curation and manifest.
 */
final class FreshJvmCompileFixture {

  static final String RECORDING_ID = "recording-fishing-fresh-jvm";

  record Result(
      byte[] manifestBytes,
      String manifestChecksum,
      String compilerVersion,
      String publishedCurationChecksum,
      int actionCount,
      int unsupportedActionCount) {}

  private FreshJvmCompileFixture() {}

  static Result compile() {
    ObjectMapper objectMapper = new ObjectMapper();
    DebugSession session = session(objectMapper);
    TickSnapshot snapshot = snapshot(objectMapper);
    ReplayActionConversionResult conversion = conversion(objectMapper);
    CapabilityManifest capability = capability();

    ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
    when(resolver.resolve(RECORDING_ID))
        .thenReturn(new ResolvedRecording(recording(objectMapper, session), session));

    TickSnapshotRepository snapshots = mock(TickSnapshotRepository.class);
    when(snapshots.findMinTickBySessionId(anyString())).thenReturn(Optional.of(snapshot.getTick()));
    when(snapshots.findMaxTickBySessionId(anyString())).thenReturn(Optional.of(snapshot.getTick()));
    when(snapshots.findBySessionIdAndTickBetweenOrderByTick(anyString(), anyLong(), anyLong()))
        .thenReturn(List.of(snapshot));

    ReplayActionConversionService conversionService = mock(ReplayActionConversionService.class);
    when(conversionService.convert(anyString(), anyLong(), anyLong(), any(ConversionMode.class)))
        .thenReturn(conversion);

    AtomicReference<RecordingCuration> published = new AtomicReference<>();
    RecordingCurationRepository curations = mock(RecordingCurationRepository.class);
    when(curations.findByContentChecksum(anyString())).thenReturn(Optional.empty());
    when(curations.findById(any())).thenReturn(Optional.empty());
    when(curations.findByRecordingIdOrderByVersionDesc(anyString())).thenReturn(List.of());
    when(curations.findByRecordingIdAndCreatedByOrderByVersionDesc(anyString(), anyString()))
        .thenReturn(List.of());
    when(curations.saveAndFlush(any(RecordingCuration.class)))
        .thenAnswer(
            invocation -> {
              RecordingCuration saved = invocation.getArgument(0);
              published.set(saved);
              return saved;
            });

    CuratedActionRepository curatedActions = mock(CuratedActionRepository.class);
    when(curatedActions.existsById(any())).thenReturn(true);

    ScenarioManifestRepository manifests = mock(ScenarioManifestRepository.class);
    when(manifests.findByManifestChecksum(anyString())).thenReturn(Optional.empty());
    when(manifests.saveAndFlush(any(ScenarioManifest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReplayCapabilityService capabilityService = mock(ReplayCapabilityService.class);
    when(capabilityService.getManifest()).thenReturn(capability);

    ScenarioManifestV2Compiler compiler =
        new ScenarioManifestV2Compiler(
            resolver,
            snapshots,
            conversionService,
            curations,
            curatedActions,
            manifests,
            capabilityService,
            new CanonicalProjectionService(),
            objectMapper);

    Compilation compilation = compiler.compile(RECORDING_ID, ConversionMode.AUTO, null, null);
    CanonicalProjectionService projection = new CanonicalProjectionService();
    byte[] manifestBytes = projection.canonicalJsonBytes(compilation.manifest().getManifestJson());
    String compilerVersion =
        compilation.manifest().getManifestJson().path("compiler").path("version").asText();
    int actionCount = compilation.manifest().getManifestJson().path("actions").size();
    RecordingCuration curation = published.get();
    return new Result(
        manifestBytes,
        compilation.manifest().getManifestChecksum(),
        compilerVersion,
        curation == null ? null : curation.getContentChecksum(),
        actionCount,
        compilation.unsupportedActions().size());
  }

  private static DebugSession session(ObjectMapper objectMapper) {
    DebugSession session = new DebugSession();
    session.setSessionId("session-fishing-fresh-jvm");
    session.setStartTime(LocalDateTime.of(2026, 8, 31, 22, 0));
    session.setEndTime(LocalDateTime.of(2026, 8, 31, 22, 1));
    ObjectNode client = objectMapper.createObjectNode();
    client
        .put("tickBatchVersion", 3)
        .putObject("client")
        .put("schema", "debugger-client-identity/v1")
        .put("clientBuildId", "authored-client-build");
    session.bindCaptureIdentity(client);
    return session;
  }

  private static RecordingSession recording(ObjectMapper objectMapper, DebugSession session) {
    RecordingSession recording = new RecordingSession();
    recording.setRecordingId(RECORDING_ID);
    ObjectNode context = objectMapper.createObjectNode();
    context.put("schemaVersion", 1);
    context.put("runId", "20ad634d-15df-44b3-8597-44fb018ef703");
    context.put("taskKey", "FISHING_TRAINING");
    context.put("behaviorProfileId", "golden-fishing");
    context.put("behaviorProfileVersion", 1);
    context.put("semanticContractDigest", "sha256:" + "a".repeat(64));
    context.put("settingsHash", "sha256:" + "b".repeat(64));
    context.put("graphRevision", "sha256:" + "c".repeat(64));
    ObjectNode bound =
        new BehaviorRunContextService(null, null, null, null, objectMapper)
            .bind(context, RECORDING_ID, session.getSessionId());
    recording.bindRunContext(bound, "CAPTURED");
    return recording;
  }

  private static TickSnapshot snapshot(ObjectMapper objectMapper) {
    ObjectNode playerState = objectMapper.createObjectNode();
    playerState.put("worldId", 301);
    playerState.put("regionId", 12850);
    // Must equal the capability's pinned content revision; a contradicting capture is refused.
    playerState.put("collisionRevision", "sha256:content");
    playerState.putObject("position").put("x", 3200).put("y", 3200).put("plane", 0);
    playerState.putObject("skills").putObject("fishing").put("level", 1).put("xp", 0);

    TickSnapshot snapshot =
        new TickSnapshot(
            "session-fishing-fresh-jvm", 0L, LocalDateTime.of(2026, 8, 31, 22, 0), playerState);
    snapshot.setSchemaVersion(1);
    snapshot.setInventory(objectMapper.createArrayNode());
    snapshot.setEquipment(objectMapper.createArrayNode());
    ArrayNode npcs = objectMapper.createArrayNode();
    npcs.addObject()
        .put("stableId", "npc:guard:7")
        .putObject("position")
        .put("x", 3199)
        .put("y", 3200)
        .put("plane", 0);
    npcs.addObject()
        .put("stableId", "npc:1518:42")
        .putObject("position")
        .put("x", 3201)
        .put("y", 3200)
        .put("plane", 0);
    snapshot.setNpcs(npcs);
    snapshot.setGameObjects(objectMapper.createArrayNode());
    snapshot.setGroundItems(objectMapper.createArrayNode());
    return snapshot;
  }

  private static ReplayActionConversionResult conversion(ObjectMapper objectMapper) {
    RecordingAction action = new RecordingAction();
    action.setSchema(RecordingAction.SCHEMA);
    action.setSessionId("session-1");
    action.setTick(0);
    action.setSequence(0);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", "npc:1518:42"));
    action.setArguments(Map.of("option", "net"));
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-08-31T22:00:00Z");
    return new ReplayActionConversionResult(
        "session-fishing-fresh-jvm",
        0,
        0,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        List.of(
            new ConvertedReplayAction(
                action, ActionProvenance.CAPTURED, "fresh-jvm-typed", null, null)),
        List.of(),
        List.of(),
        1,
        1,
        100.0,
        "sha256:fresh-jvm-conversion");
  }

  private static CapabilityManifest capability() {
    return CapabilityManifest.current(
        new CapabilityManifest.CapabilityModes(true, true, true),
        new CapabilityManifest.XrspsIdentity(
            "1.0.0", "build-golden", "sha256:cache", "sha256:content"),
        false);
  }
}
