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
