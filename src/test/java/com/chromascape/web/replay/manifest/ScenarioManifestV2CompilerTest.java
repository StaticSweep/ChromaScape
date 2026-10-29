package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioManifestV2CompilerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void nearbyNonFishingNpcDoesNotBlockReferencedFishingTarget() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();

    ReplayActionConversionResult conversion = conversion(npcAction("npc:1518:42", "net", 0));

    ObjectNode state = compiler.buildInitialState(first, conversion);

    assertThat(state.path("npcs").size()).isEqualTo(1);
    assertThat(state.path("npcs").get(0).path("stableId").asText()).isEqualTo("npc:1518:42");
    assertThat(state.path("npcs").get(0).path("spotType").asText()).isEqualTo("net");
  }

  @Test
  void missingReferencedNpcFailsClosed() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ArrayNode npcs = objectMapper.createArrayNode();
    npcs.add(first.getNpcs().get(0));
    first.setNpcs(npcs);

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(first, conversion(npcAction("npc:1518:42", "net", 0))));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_TARGET_UNRESOLVED);
  }

  @Test
  void conflictingRecordedSpotTypeFailsClosed() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ((ObjectNode) first.getNpcs().get(1)).put("spotType", "bait");

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(first, conversion(npcAction("npc:1518:42", "net", 0))));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_UNSUPPORTED);
  }

  @Test
  void conflictingActionsForOneNpcAreAmbiguous() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(
                    first,
                    conversion(
                        npcAction("npc:1518:42", "net", 0), npcAction("npc:1518:42", "bait", 1))));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_AMBIGUOUS);
  }

  @Test
  void certifiedInitialStateRequiresAndPreservesAuthoritativeNpcPins() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ObjectNode targetNpc = (ObjectNode) first.getNpcs().get(1);
    targetNpc.put("npcId", 42);
    targetNpc.put("npcTypeId", 1518);
    targetNpc.put("size", 1);
    targetNpc.put("spotId", "sea_small_net");
    targetNpc.put("spotType", "net");

    ObjectNode target = objectMapper.createObjectNode();
    target.put("kind", "NPC");
    target.put("stableId", "npc:1518:42");
    target.put("npcTypeId", 1518);
    target.put("size", 1);
    target.put("spotId", "sea_small_net");
    RecordingAction action = npcAction("npc:1518:42", "net", 0);
    action.setTarget(objectMapper.convertValue(target, Map.class));

    ObjectNode state = compiler.buildInitialState(first, certifiedConversion(action));

    assertThat(state.path("npcs").get(0).path("npcId").asInt()).isEqualTo(42);
    assertThat(state.path("npcs").get(0).path("npcTypeId").asInt()).isEqualTo(1518);
    assertThat(state.path("npcs").get(0).path("size").asInt()).isEqualTo(1);
    assertThat(state.path("npcs").get(0).path("spotId").asText()).isEqualTo("sea_small_net");
  }

  @Test
  void certifiedInitialStateRejectsMissingNpcPin() {
    ScenarioManifestV2Compiler compiler = compiler();

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(
                    initialSnapshot(), certifiedConversion(npcAction("npc:1518:42", "net", 0))));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE);
  }

  private RecordingAction npcAction(String stableId, String option, int sequence) {
    RecordingAction action = new RecordingAction();
    action.setSchema(RecordingAction.SCHEMA);
    action.setSessionId("session-1");
    action.setTick(0);
    action.setSequence(sequence);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", stableId));
    action.setArguments(Map.of("option", option));
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-08-31T22:00:00Z");
    return action;
  }

  private ReplayActionConversionResult conversion(RecordingAction... actions) {
    return new ReplayActionConversionResult(
        "session-1",
        0,
        0,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        java.util.Arrays.stream(actions)
            .map(
                action ->
                    new ConvertedReplayAction(
                        action, ActionProvenance.CAPTURED, "typed", null, null))
            .toList(),
        List.of(),
        List.of(),
        actions.length,
        actions.length,
        100.0,
        "sha256:conversion");
  }

  private ReplayActionConversionResult certifiedConversion(RecordingAction... actions) {
    return new ReplayActionConversionResult(
        "session-1",
        0,
        0,
        ConversionMode.CAPTURED_ONLY,
        ConversionQuality.CERTIFIED,
        java.util.Arrays.stream(actions)
            .map(
                action ->
                    new ConvertedReplayAction(
                        action, ActionProvenance.CAPTURED, "typed", null, null))
            .toList(),
        List.of(),
        List.of(),
        actions.length,
        0,
        100.0,
        "sha256:certified-conversion");
  }

  private ScenarioManifestV2Compiler compiler() {
    return new ScenarioManifestV2Compiler(
        mock(ReplayRecordingResolver.class),
        mock(TickSnapshotRepository.class),
        mock(ReplayActionConversionService.class),
        mock(RecordingCurationRepository.class),
        mock(CuratedActionRepository.class),
        mock(ScenarioManifestRepository.class),
        mock(ReplayCapabilityService.class),
        new CanonicalProjectionService(),
        objectMapper);
  }

  @Test
  void aRecordedStartTakesItsWorldFromTheSessionAndItsCollisionModelFromTheWorker() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ObjectNode playerState = (ObjectNode) first.getPlayerState();
    playerState.remove("worldId");
    playerState.remove("collisionRevision");
    var recordedStart =
        new ScenarioManifestV2Compiler.RecordedStart(301, 241, "xrsps-collision/none@0.1.0");

    ObjectNode state =
        compiler.buildInitialState(
            first, conversion(npcAction("npc:1518:42", "net", 0)), recordedStart);

    assertThat(state.path("worldId").asInt()).isEqualTo(301);
    assertThat(state.path("collisionRevision").asText()).isEqualTo("xrsps-collision/none@0.1.0");
  }

  @Test
  void withoutABoundWorldARecordingStillFailsClosed() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ((ObjectNode) first.getPlayerState()).remove("worldId");

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(
                    first,
                    conversion(npcAction("npc:1518:42", "net", 0)),
                    new ScenarioManifestV2Compiler.RecordedStart(
                        null, 241, "xrsps-collision/none@0.1.0")));
    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE);
  }

  @Test
  void recordedStartEvidenceNamesTheStartTickForEveryFieldAndTheBoundIdentity() {
    ObjectNode evidence =
        compiler()
            .recordedStartEvidence(
                "rec-1",
                4100L,
                new ScenarioManifestV2Compiler.RecordedStart(
                    301, null, "xrsps-collision/none@0.1.0"));

    assertThat(evidence.path("schema").asText()).isEqualTo("recorded-start/v1");
    assertThat(evidence.path("recordingId").asText()).isEqualTo("rec-1");
    assertThat(evidence.path("startTick").asLong()).isEqualTo(4100L);
    assertThat(evidence.path("sourceTicks").size()).isEqualTo(5);
    evidence.path("sourceTicks").forEach(tick -> assertThat(tick.asLong()).isEqualTo(4100L));
    assertThat(evidence.path("captureIdentity").path("worldId").asInt()).isEqualTo(301);
    // An unbound revision is stated, so the worker refuses it rather than assuming one.
    assertThat(evidence.path("captureIdentity").get("gameRevision").isNull()).isTrue();
  }

  @Test
  void recordedSkillsAreRenamedForTheWorkerWithEveryValueKept() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ObjectNode skills = ((ObjectNode) first.getPlayerState()).putObject("skills");
    skills.putObject("FISHING").put("xp", 12922).put("real", 29).put("boosted", 29);

    ObjectNode state =
        compiler.buildInitialState(first, conversion(npcAction("npc:1518:42", "net", 0)));

    ObjectNode fishing = (ObjectNode) state.path("player").path("skills").path("fishing");
    assertThat(fishing.path("level").asInt()).isEqualTo(29);
    assertThat(fishing.path("xp").asInt()).isEqualTo(12922);
    assertThat(fishing.path("boosted").asInt()).isEqualTo(29);
    assertThat(state.path("player").path("skills").has("FISHING")).isFalse();
  }

  @Test
  void aStartOnlyWorldIsTheRecordedFishingSpotsExactlyAsCaptured() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ObjectNode spot =
        ((ArrayNode) first.getNpcs())
            .addObject()
            .put("stableId", "npc:1525:3085:3230:0")
            .put("npcId", 1525)
            .put("npcTypeId", 1525)
            .put("size", 1)
            .put("name", "Fishing spot")
            .put("fishingSpot", true);
    spot.putObject("position").put("x", 3085).put("y", 3230).put("plane", 0);
    spot.putArray("actions").add("Small Net").add("Bait");
    var recordedStart =
        new ScenarioManifestV2Compiler.RecordedStart(556, 241, "xrsps-collision/none@0.1.0");

    ObjectNode state = compiler.buildInitialState(first, null, recordedStart);

    assertThat(state.path("npcs").size()).isEqualTo(1);
    ObjectNode npc = (ObjectNode) state.path("npcs").get(0);
    assertThat(npc.path("stableId").asText()).isEqualTo("npc:1525:3085:3230:0");
    assertThat(npc.path("npcTypeId").asInt()).isEqualTo(1525);
    assertThat(npc.path("actions").toString()).isEqualTo("[\"Small Net\",\"Bait\"]");
    // Nothing is inferred for a start: no spot type or spot id the recorder did not capture.
    assertThat(npc.has("spotType")).isFalse();
    assertThat(npc.has("spotId")).isFalse();
  }

  @Test
  void aStartOnlySpotWithoutItsCacheTypeIsRefused() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    ObjectNode spot =
        ((ArrayNode) first.getNpcs())
            .addObject()
            .put("stableId", "npc:spot")
            .put("size", 1)
            .put("fishingSpot", true);
    spot.putObject("position").put("x", 3085).put("y", 3230).put("plane", 0);

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(
                    first,
                    null,
                    new ScenarioManifestV2Compiler.RecordedStart(
                        556, 241, "xrsps-collision/none@0.1.0")));
    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE);
  }

  @Test
  void aStartOnlyManifestCarriesOnlyWhatTheWorkerModelsAndNamesTheRest() {
    ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
    TickSnapshotRepository snapshots = mock(TickSnapshotRepository.class);
    ReplayCapabilityService capabilities = mock(ReplayCapabilityService.class);
    ScenarioManifestV2Compiler compiler =
        new ScenarioManifestV2Compiler(
            resolver,
            snapshots,
            mock(ReplayActionConversionService.class),
            mock(RecordingCurationRepository.class),
            mock(CuratedActionRepository.class),
            mock(ScenarioManifestRepository.class),
            capabilities,
            new CanonicalProjectionService(),
            objectMapper);
    com.chromascape.web.debug.tickdebugger.entity.DebugSession session =
        new com.chromascape.web.debug.tickdebugger.entity.DebugSession();
    session.setSessionId("session-1");
    session.setEndTime(LocalDateTime.of(2026, 10, 29, 19, 9));
    ObjectNode identity = objectMapper.createObjectNode();
    identity.putObject("client").put("worldId", 368).put("gameRevision", 241);
    session.bindCaptureIdentity(identity);
    org.mockito.Mockito.when(resolver.resolve("rec-1"))
        .thenReturn(
            new ReplayRecordingResolver.ResolvedRecording(
                new com.chromascape.web.recording.entity.RecordingSession(), session));
    org.mockito.Mockito.when(capabilities.getManifest())
        .thenReturn(
            com.chromascape.web.replay.capability.CapabilityManifest.current(
                new com.chromascape.web.replay.capability.CapabilityManifest.CapabilityModes(
                    false, false, true),
                new com.chromascape.web.replay.capability.CapabilityManifest.XrspsIdentity(
                    "0.1.0", "commit", "sha256:cache", "sha256:content"),
                true));
    org.mockito.Mockito.when(capabilities.isAuthoritativeCertifiable()).thenReturn(true);
    TickSnapshot first = initialSnapshot();
    ObjectNode player = (ObjectNode) first.getPlayerState();
    player.remove("worldId");
    player.remove("collisionRevision");
    player.put("hp", 99).put("animation", -1);
    ObjectNode spot =
        ((ArrayNode) first.getNpcs())
            .addObject()
            .put("stableId", "npc:1525:3085:3230:0")
            .put("npcId", 1525)
            .put("npcTypeId", 1525)
            .put("size", 1)
            .put("fishingSpot", true);
    spot.putObject("position").put("x", 3085).put("y", 3230).put("plane", 0);
    ((ArrayNode) first.getGameObjects()).addObject().put("id", 1189).put("x", 1).put("y", 2);
    org.mockito.Mockito.when(snapshots.findMinTickBySessionId("session-1"))
        .thenReturn(java.util.Optional.of(0L));
    org.mockito.Mockito.when(snapshots.findMaxTickBySessionId("session-1"))
        .thenReturn(java.util.Optional.of(20L));
    org.mockito.Mockito.when(
            snapshots.findBySessionIdAndTickBetweenOrderByTick("session-1", 0L, 0L))
        .thenReturn(List.of(first));

    ObjectNode manifest = compiler.compileStart("rec-1");

    ObjectNode state = (ObjectNode) manifest.path("initialState");
    assertThat(state.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("worldId", "collisionRevision", "player", "npcs");
    assertThat(state.path("worldId").asInt()).isEqualTo(368);
    assertThat(state.path("collisionRevision").asText()).isEqualTo("xrsps-collision/none@0.1.0");
    assertThat(state.path("player").has("hp")).isFalse();
    assertThat(state.path("player").path("animation").asInt()).isEqualTo(-1);
    assertThat(manifest.path("actions").size()).isZero();
    JsonNode conversion = manifest.path("conversion");
    assertThat(conversion.path("mode").asText()).isEqualTo("START_ONLY");
    assertThat(conversion.path("unmodelledPlayerFields").toString()).contains("\"hp\"");
    assertThat(conversion.path("unmodelledWorldFields").toString()).contains("\"objects\"");
    assertThat(conversion.path("recordedStart").path("captureIdentity").path("worldId").asInt())
        .isEqualTo(368);
    assertThat(manifest.path("fidelityRequirement").asText()).isEqualTo("XRSPS_AUTHORITATIVE");
    // Deterministic: compiling the same start again yields the same content address.
    assertThat(compiler.compileStart("rec-1").path("manifestChecksum"))
        .isEqualTo(manifest.path("manifestChecksum"));
  }

  private TickSnapshot initialSnapshot() {
    ObjectNode playerState = objectMapper.createObjectNode();
    playerState.put("worldId", 301);
    playerState.put("collisionRevision", "collision-v7");
    playerState.putObject("position").put("x", 3200).put("y", 3200).put("plane", 0);
    playerState.putObject("skills").putObject("fishing").put("level", 1).put("xp", 0);

    TickSnapshot snapshot =
        new TickSnapshot("session-1", 0L, LocalDateTime.of(2026, 8, 31, 22, 0), playerState);
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
}
