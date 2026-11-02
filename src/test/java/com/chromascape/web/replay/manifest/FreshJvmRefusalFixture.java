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
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SC-274 negative matrix (row 3) executed in one isolated process. Each case compiles a golden
 * Fishing scenario with exactly one injected defect and records the stable error payload, so two
 * independent JVMs can be compared byte for byte.
 *
 * <p>Every case must refuse with the same {@code ReplayErrorCode} and the same source reference on
 * every run; nothing here is allowed to depend on process state.
 */
final class FreshJvmRefusalFixture {

  static final String RECORDING_ID = "recording-fishing-refusal";

  /** One refusal observation: the stable code, HTTP status and source-linked safe message. */
  record Refusal(String id, String errorCode, int httpStatus, String safeMessage) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FreshJvmRefusalFixture() {}

  /** Runs the whole negative matrix and returns the canonical payload bytes. */
  static byte[] refusalPayload() {
    ArrayNode cases = MAPPER.createArrayNode();
    for (Refusal refusal : refusals()) {
      ObjectNode entry = cases.addObject();
      entry.put("case", refusal.id());
      entry.put("errorCode", refusal.errorCode());
      entry.put("httpStatus", refusal.httpStatus());
      entry.put("safeMessage", refusal.safeMessage());
    }
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("schema", "sc274-refusal-matrix/v1");
    payload.put("compilerVersion", ScenarioManifestV2Compiler.COMPILER_VERSION);
    payload.set("refusals", cases);
    CanonicalProjectionService projection = new CanonicalProjectionService();
    return projection.canonicalJsonBytes(payload);
  }

  static List<Refusal> refusals() {
    List<Refusal> refusals = new ArrayList<>();
    refusals.add(
        observe(
            "tick-gap",
            () ->
                compile(
                    List.of(snapshot(0), snapshot(2)),
                    conversion(action("net", 0, 0)),
                    capability())));
    refusals.add(
        observe(
            "action-outside-window",
            () -> compile(List.of(snapshot(0)), conversion(action("net", 1, 0)), capability())));
    refusals.add(
        observe(
            "duplicate-action",
            () ->
                compile(
                    List.of(snapshot(0)),
                    conversion(action("net", 0, 0), action("net", 0, 0)),
                    capability())));
    refusals.add(
        observe(
            "reordered-action",
            () ->
                compile(
                    List.of(snapshot(0), snapshot(1)),
                    conversion(action("net", 1, 0), action("net", 0, 1)),
                    capability())));
    refusals.add(
        observe(
            "missing-player-state",
            () -> {
              TickSnapshot snapshot = snapshot(0);
              snapshot.setPlayerState(null);
              compile(List.of(snapshot), conversion(action("net", 0, 0)), capability());
            }));
    refusals.add(
        observe(
            "incompatible-cache-revision",
            () ->
                compile(
                    List.of(snapshot(0)),
                    conversion(action("net", 0, 0)),
                    CapabilityManifest.current(
                        new CapabilityManifest.CapabilityModes(true, true, true),
                        new CapabilityManifest.XrspsIdentity(
                            "1.0.0", "build-golden", "", "sha256:content"),
                        false))));
    refusals.add(
        observe(
            "incompatible-collision-revision",
            () -> {
              TickSnapshot snapshot = snapshot(0);
              ((ObjectNode) snapshot.getPlayerState())
                  .put("collisionRevision", "different-collision-rev-2");
              compile(List.of(snapshot), conversion(action("net", 0, 0)), capability());
            }));
    refusals.add(
        observe(
            "missing-region-identity",
            () -> {
              TickSnapshot snapshot = snapshot(0);
              ((ObjectNode) snapshot.getPlayerState()).remove("regionId");
              compile(List.of(snapshot), conversion(action("net", 0, 0)), capability());
            }));
    refusals.add(
        observe(
            "missing-world-identity",
            () -> {
              TickSnapshot snapshot = snapshot(0);
              ((ObjectNode) snapshot.getPlayerState()).remove("worldId");
              compile(List.of(snapshot), conversion(action("net", 0, 0)), capability());
            }));
    return List.copyOf(refusals);
  }

  private interface CaseRun {
    void run();
  }

  private static Refusal observe(String id, CaseRun run) {
    try {
      run.run();
      throw new IllegalStateException("case " + id + " did not refuse");
    } catch (ReplayException refusal) {
      return new Refusal(
          id,
          refusal.getErrorCode().name(),
          refusal.getHttpStatus().value(),
          refusal.getSafeMessage());
    }
  }

  private static void compile(
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability) {
    DebugSession session = session();
    ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
    when(resolver.resolve(RECORDING_ID))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(recording(session), session));

    TickSnapshotRepository repository = mock(TickSnapshotRepository.class);
    when(repository.findMinTickBySessionId(anyString()))
        .thenReturn(Optional.of(snapshots.get(0).getTick()));
    when(repository.findMaxTickBySessionId(anyString()))
        .thenReturn(Optional.of(snapshots.get(snapshots.size() - 1).getTick()));
    when(repository.findBySessionIdAndTickBetweenOrderByTick(anyString(), anyLong(), anyLong()))
        .thenReturn(snapshots);

    ReplayActionConversionService converter = mock(ReplayActionConversionService.class);
    when(converter.convert(anyString(), anyLong(), anyLong(), any(ConversionMode.class)))
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

    ReplayCapabilityService capabilities = mock(ReplayCapabilityService.class);
    when(capabilities.getManifest()).thenReturn(capability);

    new ScenarioManifestV2Compiler(
            resolver,
            repository,
            converter,
            curations,
            curatedActions,
            manifests,
            capabilities,
            new CanonicalProjectionService(),
            MAPPER)
        .compile(RECORDING_ID, ConversionMode.AUTO, null, null);
  }

  private static DebugSession session() {
    DebugSession session = new DebugSession();
    session.setSessionId("session-fishing-refusal");
    session.setStartTime(LocalDateTime.of(2026, 8, 31, 22, 0));
    session.setEndTime(LocalDateTime.of(2026, 8, 31, 22, 1));
    ObjectNode client = MAPPER.createObjectNode();
    client
        .put("tickBatchVersion", 3)
        .putObject("client")
        .put("schema", "debugger-client-identity/v1")
        .put("clientBuildId", "authored-client-build");
    session.bindCaptureIdentity(client);
    return session;
  }

  private static RecordingSession recording(DebugSession session) {
    RecordingSession recording = new RecordingSession();
    recording.setRecordingId(RECORDING_ID);
    ObjectNode context = MAPPER.createObjectNode();
    context.put("schemaVersion", 1);
    context.put("runId", "20ad634d-15df-44b3-8597-44fb018ef703");
    context.put("taskKey", "FISHING_TRAINING");
    context.put("behaviorProfileId", "golden-fishing");
    context.put("behaviorProfileVersion", 1);
    context.put("semanticContractDigest", "sha256:" + "a".repeat(64));
    context.put("settingsHash", "sha256:" + "b".repeat(64));
    context.put("graphRevision", "sha256:" + "c".repeat(64));
    ObjectNode bound =
        new BehaviorRunContextService(null, null, null, null, MAPPER)
            .bind(context, RECORDING_ID, session.getSessionId());
    recording.bindRunContext(bound, "CAPTURED");
    return recording;
  }

  private static TickSnapshot snapshot(long tick) {
    ObjectNode playerState = MAPPER.createObjectNode();
    playerState.put("worldId", 301);
    playerState.put("regionId", 12850);
    playerState.put("collisionRevision", "sha256:content");
    playerState.putObject("position").put("x", 3200).put("y", 3200).put("plane", 0);
    playerState.putObject("skills").putObject("fishing").put("level", 1).put("xp", 0);

    TickSnapshot snapshot =
        new TickSnapshot(
            "session-fishing-refusal", tick, LocalDateTime.of(2026, 8, 31, 22, 0), playerState);
    snapshot.setSchemaVersion(1);
    snapshot.setInventory(MAPPER.createArrayNode());
    snapshot.setEquipment(MAPPER.createArrayNode());
    ArrayNode npcs = MAPPER.createArrayNode();
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
    snapshot.setGameObjects(MAPPER.createArrayNode());
    snapshot.setGroundItems(MAPPER.createArrayNode());
    return snapshot;
  }

  private static RecordingAction action(String option, long tick, int sequence) {
    RecordingAction action = new RecordingAction();
    action.setSchema(RecordingAction.SCHEMA);
    action.setSessionId("session-fishing-refusal");
    action.setTick(tick);
    action.setSequence(sequence);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", "npc:1518:42"));
    action.setArguments(Map.of("option", option));
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-08-31T22:00:00Z");
    return action;
  }

  private static ReplayActionConversionResult conversion(RecordingAction... actions) {
    long start = actions.length == 0 ? 0 : actions[0].getTick();
    long end = 0;
    for (RecordingAction action : actions) {
      end = Math.max(end, action.getTick());
    }
    List<ConvertedReplayAction> converted = new ArrayList<>();
    for (RecordingAction action : actions) {
      converted.add(
          new ConvertedReplayAction(
              action, ActionProvenance.CAPTURED, "refusal-typed", null, null));
    }
    return new ReplayActionConversionResult(
        "session-fishing-refusal",
        start,
        end,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        List.copyOf(converted),
        List.of(),
        List.of(),
        actions.length,
        0,
        100.0,
        "sha256:refusal-conversion");
  }

  private static CapabilityManifest capability() {
    return CapabilityManifest.current(
        new CapabilityManifest.CapabilityModes(true, true, true),
        new CapabilityManifest.XrspsIdentity(
            "1.0.0", "build-golden", "sha256:cache", "sha256:content"),
        false);
  }
}
