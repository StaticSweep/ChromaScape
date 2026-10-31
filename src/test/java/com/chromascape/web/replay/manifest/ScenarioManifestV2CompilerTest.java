package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler.Compilation;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
  void fishingNpcOptionPreservesAdvertisedNpcKernelAction() {
    ScenarioManifestV2Compiler compiler = compiler();

    ObjectNode kernelAction =
        compiler.toKernelAction(conversion(npcAction("npc:1518:42", "net", 0)).actions().get(0));

    assertThat(kernelAction.path("type").asText()).isEqualTo("INTERACT_NPC");
    assertThat(kernelAction.path("targetStableId").asText()).isEqualTo("npc:1518:42");
    assertThat(kernelAction.path("option").asText()).isEqualTo("net");
    assertThat(kernelAction.path("mappingVersion").asText())
        .isEqualTo(ScenarioManifestV2Compiler.ACTION_MAPPING_VERSION);
    assertThat(kernelAction.path("provenance").asText())
        .isEqualTo(ActionProvenance.CAPTURED.name());
    assertThat(kernelAction.path("sourceActionTick").asInt()).isEqualTo(0);
    assertThat(kernelAction.path("sourceActionSequence").asInt()).isEqualTo(0);
  }

  @Test
  void nonFishingNpcOptionKeepsInteractNpcKernelAction() {
    ScenarioManifestV2Compiler compiler = compiler();

    ObjectNode kernelAction =
        compiler.toKernelAction(
            conversion(npcAction("npc:1518:42", "Talk-to", 0)).actions().get(0));

    assertThat(kernelAction.path("type").asText()).isEqualTo("INTERACT_NPC");
    assertThat(kernelAction.path("targetStableId").asText()).isEqualTo("npc:1518:42");
    assertThat(kernelAction.path("option").asText()).isEqualTo("Talk-to");
    assertThat(kernelAction.path("mappingVersion").asText())
        .isEqualTo(ScenarioManifestV2Compiler.ACTION_MAPPING_VERSION);
  }

  @Test
  void fishingNpcKernelActionIsByteIdenticalAcrossCompilations() throws Exception {
    ScenarioManifestV2Compiler compiler = compiler();
    ConvertedReplayAction converted =
        conversion(npcAction("npc:1518:42", "cage", 0)).actions().get(0);

    ObjectNode first = compiler.toKernelAction(converted);
    ObjectNode second = compiler.toKernelAction(converted);

    assertThat(first.path("type").asText()).isEqualTo("INTERACT_NPC");
    CanonicalProjectionService projection = new CanonicalProjectionService();
    assertThat(projection.sha256Hex(projection.canonicalJsonBytes(first)))
        .isEqualTo(projection.sha256Hex(projection.canonicalJsonBytes(second)));
  }

  @Test
  void capabilitySurfaceIncludesAdvertisedNpcKernelAction() {
    assertThat(com.chromascape.web.replay.capability.CapabilityManifest.V1_SUPPORTED_ACTIONS)
        .contains("INTERACT_NPC");
  }

  @Test
  void manualObservationFrameEvidenceSurvivesKernelMappingWithoutDispatchRelabeling() {
    var source = npcAction("npc:1526:3100:3430:0", "Lure", 0);
    source.setSource("MICRO6_CLIENT_OBSERVATION");
    var evidence =
        Map.of(
            "frameDigest",
            "a".repeat(64),
            "frameSequence",
            "2",
            "beforeFrameDigest",
            "b".repeat(64),
            "beforeFrameSequence",
            "1",
            "eventId",
            "00000000-0000-4000-8000-000000000001");
    source.setArguments(Map.of("option", "Lure", "observationEvidence", evidence));
    var action = compiler().toKernelAction(conversion(source).actions().get(0));
    assertThat(action.path("sourceEvidence").path("clientObservation"))
        .isEqualTo(objectMapper.valueToTree(evidence));
    assertThat(action.path("type").asText()).isEqualTo("INTERACT_NPC");
    assertThat(action.path("option").asText()).isEqualTo("Lure");
    assertThat(action.has("inputs")).isFalse();
    assertThat(source.getSource()).isEqualTo("MICRO6_CLIENT_OBSERVATION");
  }

  @Test
  void exactNativeItemAndWidgetInputsSurviveCompilerMapping() throws Exception {
    ArrayNode exported = objectMapper.createArrayNode();
    for (var fixture :
        List.of(
            Map.entry(
                "INTERACT_OBJECT",
                Map.<String, Object>of(
                    "option", "use", "sourceItem", Map.of("slot", 2, "itemId", 952))),
            Map.entry(
                "INTERACT_INVENTORY_ITEM",
                Map.<String, Object>of(
                    "option",
                    "use",
                    "slot",
                    1,
                    "itemId",
                    1775,
                    "sourceSlot",
                    0,
                    "sourceItemId",
                    1785)),
            Map.entry(
                "INTERACT_WIDGET",
                Map.<String, Object>of(
                    "option",
                    "make",
                    "widgetId",
                    17694740,
                    "widgetIndex",
                    2,
                    "productId",
                    567,
                    "quantity",
                    2)),
            Map.entry(
                "INTERACT_WIDGET",
                Map.<String, Object>of(
                    "option",
                    "withdraw-x",
                    "widgetId",
                    786444,
                    "widgetIndex",
                    1,
                    "input",
                    Map.of("kind", "quantity", "value", 18))),
            Map.entry(
                "INTERACT_WIDGET",
                Map.<String, Object>of(
                    "option",
                    "confirm",
                    "input",
                    Map.of(
                        "kind",
                        "exchange-offer",
                        "side",
                        "buy",
                        "itemId",
                        303,
                        "quantity",
                        1,
                        "price",
                        100))),
            Map.entry(
                "CAST_SPELL",
                Map.<String, Object>of("spellId", 9111, "slot", 3, "itemId", 1205)))) {
      RecordingAction source = npcAction("fixture:exact-target", "unused", 0);
      source.setActionType(fixture.getKey());
      source.setArguments(fixture.getValue());
      ObjectNode actual = compiler().toKernelAction(conversion(source).actions().get(0));
      exported.add(actual);
      for (var field : fixture.getValue().entrySet()) {
        assertThat(actual.get(field.getKey()))
            .isEqualTo(objectMapper.valueToTree(field.getValue()));
      }
      assertThat(actual.path("targetStableId").asText()).isEqualTo("fixture:exact-target");
      assertThat(source.getArguments()).isEqualTo(fixture.getValue());
    }
    var output = java.nio.file.Path.of("build", "sc274-contract", "native-actions.json");
    java.nio.file.Files.createDirectories(output.getParent());
    var document = objectMapper.createObjectNode().put("fixtureOrigin", "AUTHORED_TEST_ONLY");
    document.set("actions", exported);
    java.nio.file.Files.writeString(
        output, objectMapper.writeValueAsString(document), java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void compilerNeverTruncatesCoercesOrSilentlyDropsProvidedNativePins() {
    for (Object malformed : List.of(1.5, 4294967297L, "1", BigDecimal.valueOf(1.5), Double.NaN)) {
      RecordingAction source = npcAction("inventory:1:1775", "use", 0);
      source.setActionType("INTERACT_INVENTORY_ITEM");
      source.setArguments(
          Map.of("slot", 1, "itemId", 1775, "sourceSlot", malformed, "sourceItemId", 1785));
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () -> compiler().toKernelAction(conversion(source).actions().get(0)));
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
    }
    RecordingAction source = npcAction("fixture:object", "use", 0);
    source.setActionType("INTERACT_OBJECT");
    source.setArguments(Map.of("option", "use", "sourceItem", "not-an-object"));
    assertThrows(
        ReplayException.class,
        () -> compiler().toKernelAction(conversion(source).actions().get(0)));
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

  /**
   * Expected checksum of the golden Fishing manifest. Pinned so any nondeterminism in the
   * recording-to-manifest path fails loudly; recompute only alongside an intended compiler,
   * fixture, or contract change. This pin includes the separately bound RecordingSession
   * BehaviorRunContext and client capture identity, rather than conflating those sources, and
   * explicit supported snapshot schema pins rather than the old null fixture schema. The 2.4.0
   * compiler revision binds explicit termination policy defaults and provenance, intentionally
   * revising this pin (and compiler-version-derived IDs and seed).
   */
  private static final String EXPECTED_GOLDEN_MANIFEST_CHECKSUM =
      "sha256:2976e2bd3668831fb091ca2900e78d091326472257650ecb69f18f857e912cf1";

  @Test
  void goldenFishingCompileProducesExpectedChecksum() {
    Compilation first = goldenCompile();
    Compilation second = goldenCompile();

    assertThat(first.manifest().getManifestChecksum()).isEqualTo(EXPECTED_GOLDEN_MANIFEST_CHECKSUM);
    assertThat(second.manifest().getManifestChecksum())
        .isEqualTo(EXPECTED_GOLDEN_MANIFEST_CHECKSUM);
  }

  @Test
  void goldenFishingManifestCarriesSingleNpcAction() {
    Compilation compilation = goldenCompile();

    JsonNode actions = compilation.manifest().getManifestJson().path("actions");
    assertThat(actions.size()).isEqualTo(1);
    assertThat(actions.get(0).path("type").asText()).isEqualTo("INTERACT_NPC");
    assertThat(actions.get(0).path("targetStableId").asText()).isEqualTo("npc:1518:42");
    assertThat(actions.get(0).path("option").asText()).isEqualTo("net");
    assertThat(actions.get(0).path("mappingVersion").asText())
        .isEqualTo(ScenarioManifestV2Compiler.ACTION_MAPPING_VERSION);
    assertThat(compilation.unsupportedActions()).isEmpty();
  }

  @Test
  void goldenFishingManifestIsByteIdenticalAcrossCompilations() {
    Compilation first = goldenCompile();
    Compilation second = goldenCompile();

    CanonicalProjectionService projection = new CanonicalProjectionService();
    assertThat(projection.canonicalJson(first.manifest().getManifestJson()))
        .isEqualTo(projection.canonicalJson(second.manifest().getManifestJson()));
  }

  @Test
  void compilerEmitsChecksumBoundRecordingRelativeLimitsWithExplicitProvenance() {
    JsonNode document = goldenCompile().manifest().getManifestJson();
    assertThat(document.path("compiler").path("version").asText()).isEqualTo("2.4.0");
    JsonNode limits = document.path("execution");
    assertThat(limits.size()).isEqualTo(4);
    assertThat(limits.path("schema").asText()).isEqualTo(ExecutionLimitContract.SCHEMA);
    assertThat(limits.path("maximumTicks").asLong())
        .isEqualTo(
            document.path("recording").path("endTick").asLong()
                - document.path("recording").path("startTick").asLong());
    assertThat(limits.path("maximumActions").asInt()).isEqualTo(10_000);
    assertThat(limits.path("maximumSteps").asInt()).isEqualTo(100_000);
    JsonNode fields = document.path("compilationEvidence").path("fieldProvenance");
    for (String field : List.of("schema", "maximumTicks", "maximumActions", "maximumSteps")) {
      JsonNode provenance = fields.path("/execution/" + field);
      assertThat(provenance.path("provenance").asText())
          .isEqualTo(field.equals("maximumTicks") ? "DERIVED" : "DEFAULTED");
      assertThat(provenance.path("sourceRef").asText())
          .isEqualTo("compiler:2.4.0/execution-limits/v1");
    }
    ObjectNode tampered = document.deepCopy();
    ((ObjectNode) tampered.path("execution")).put("maximumActions", 1);
    assertThrows(ReplayException.class, () -> ScenarioManifestImportValidator.validate(tampered));
  }

  @Test
  void compilerDefaultsAreExplicitAndNeverInferredFromFinalExpectations() {
    JsonNode document = goldenCompile().manifest().getManifestJson();
    assertThat(document.path("termination"))
        .isEqualTo(TerminationPolicyContract.defaults(objectMapper));
    assertThat(document.path("expectations").isEmpty()).isFalse();
    for (String pointer :
        List.of(
            "/termination",
            "/termination/schema",
            "/termination/success",
            "/termination/failure",
            "/termination/stop")) {
      JsonNode source = document.path("compilationEvidence").path("fieldProvenance").path(pointer);
      assertThat(source.path("provenance").asText()).isEqualTo("DEFAULTED");
      assertThat(source.path("sourceRef").asText())
          .isEqualTo("compiler:2.4.0/termination-policy/v1");
    }
  }

  @Test
  void selectedPublishedConditionsAreImmutableDeterministicAndNotYetSimulationEligible() {
    var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.HELD_OUT);
    ObjectNode policy = TerminationPolicyContract.defaults(objectMapper);
    policy
        .withArray("success")
        .addObject()
        .put("id", "fish-ready")
        .putObject("predicate")
        .put("op", "exists")
        .put("pointer", "/player/inventory");
    ((ObjectNode) operator.getContentJson()).set("termination", policy);
    JsonNode before = operator.getContentJson().deepCopy();
    Compilation first =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            null);
    Compilation second =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            null);
    JsonNode document = first.manifest().getManifestJson();
    assertThat(document).isEqualTo(second.manifest().getManifestJson());
    assertThat(document.path("termination")).isEqualTo(policy);
    assertThat(document.path("curation").path("datasetRole").asText()).isEqualTo("HELD_OUT");
    for (String suffix :
        List.of(
            "",
            "/schema",
            "/success",
            "/success/0",
            "/success/0/id",
            "/success/0/predicate",
            "/success/0/predicate/op",
            "/success/0/predicate/pointer",
            "/failure",
            "/stop")) {
      JsonNode source =
          document
              .path("compilationEvidence")
              .path("fieldProvenance")
              .path("/termination" + suffix);
      assertThat(source.path("provenance").asText()).isEqualTo("OPERATOR_SUPPLIED");
      assertThat(source.path("sourceRef").asText())
          .isEqualTo("curation:" + operator.getId() + "/content/termination" + suffix);
    }
    ScenarioManifestImportValidator.validate(document);
    assertThat(first.isSimulationAllowed()).isFalse();
    assertThrows(ReplayException.class, first::requireSimulationAllowed);
    policy.withArray("success").removeAll();
    assertThat(document.path("termination").path("success").size()).isEqualTo(1);
    assertThat(document.path("curation").path("sourceCuration").path("contentSnapshot"))
        .isEqualTo(before);
  }

  @Test
  void selectedMalformedConditionsAreRejectedRatherThanDropped() {
    var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
    ((ObjectNode) operator.getContentJson()).putObject("termination").put("script", "untrusted");
    assertThrows(
        ReplayException.class,
        () ->
            compileWith(
                "recording-fishing-golden",
                goldenSession(),
                List.of(goldenSnapshot()),
                goldenConversion(),
                goldenCapability(),
                List.of(operator),
                null));
  }

  @Test
  void goldenFishingManifestHasNoRawInputDependency() {
    Compilation compilation = goldenCompile();

    String canonical =
        new CanonicalProjectionService().canonicalJson(compilation.manifest().getManifestJson());
    assertThat(compilation.manifest().getManifestJson().path("actions").size()).isEqualTo(1);
    assertThat(canonical.toLowerCase())
        .doesNotContain("mouse", "keyboard", "packet", "websocket", "remoteinput");
  }

  @Test
  void goldenFishingManifestSatisfiesImportSchema() {
    Compilation compilation = goldenCompile();

    byte[] canonical =
        new CanonicalProjectionService()
            .canonicalJsonBytes(compilation.manifest().getManifestJson());
    JsonNode document = ScenarioManifestImportValidator.validate(canonical);

    assertThat(document.path("manifestChecksum").asText())
        .isEqualTo(compilation.manifest().getManifestChecksum());
  }

  @Test
  void exportsBoundedAuthoredFishingScenarioWithoutRewritingTheEmittedManifest() throws Exception {
    JsonNode suppliedIdentity =
        objectMapper.readTree(
            System.getenv().getOrDefault("SC274_NATIVE_RUNTIME_IDENTITY_JSON", "null"));
    CapabilityManifest capability = goldenCapability();
    if (suppliedIdentity != null && !suppliedIdentity.isNull()) {
      for (String field :
          List.of("adapterVersion", "buildCommit", "cacheRevision", "contentRevision")) {
        assertThat(suppliedIdentity.path(field).isTextual()).isTrue();
        assertThat(suppliedIdentity.path(field).asText()).isNotBlank();
      }
      capability =
          CapabilityManifest.current(
              new CapabilityManifest.CapabilityModes(true, true, true),
              new CapabilityManifest.XrspsIdentity(
                  suppliedIdentity.path("adapterVersion").asText(),
                  suppliedIdentity.path("buildCommit").asText(),
                  suppliedIdentity.path("cacheRevision").asText(),
                  suppliedIdentity.path("contentRevision").asText()),
              false);
    }
    List<TickSnapshot> snapshots = new java.util.ArrayList<>();
    for (int tick = 0; tick <= 64; tick++) {
      TickSnapshot snapshot = goldenSnapshot();
      snapshot.setTick((long) tick);
      var player = (ObjectNode) snapshot.getPlayerState();
      player.putObject("position").put("x", 3080).put("y", 3227).put("plane", 0);
      player.putObject("skills").putObject("fishing").put("level", 20).put("xp", 4470);
      var inventory = objectMapper.createArrayNode();
      inventory.addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
      snapshot.setInventory(inventory);
      // This is deliberately authored native-content test input, not a repaired live capture.
      var npcs = objectMapper.createArrayNode();
      var npc =
          npcs.addObject()
              .put("stableId", "authored:sc274:fishing")
              .put("npcId", 42)
              .put("npcTypeId", 1530)
              .put("size", 1)
              .put("spotId", "sea_small_net")
              .put("spotType", "net");
      npc.putObject("position").put("x", 3081).put("y", 3227).put("plane", 0);
      npc.putArray("actions").add("net");
      snapshot.setNpcs(npcs);
      snapshot.setGameObjects(objectMapper.createArrayNode());
      snapshot.setGroundItems(objectMapper.createArrayNode());
      snapshots.add(snapshot);
    }
    RecordingAction action = tickedAction("authored:sc274:fishing", "net", 0, 0);
    action.setSessionId("session-fishing-golden");
    Compilation compilation =
        compileWith(
            "authored-sc274-native-fishing",
            goldenSession(),
            snapshots,
            rangedConversion(0, 64, action),
            capability,
            List.of(),
            null);
    compilation.requireSimulationAllowed();
    JsonNode limits = compilation.manifest().getManifestJson().path("execution");
    assertThat(limits.path("maximumTicks").asInt()).isEqualTo(64);
    assertThat(limits.path("maximumActions").asInt()).isEqualTo(10_000);
    assertThat(limits.path("maximumSteps").asInt()).isEqualTo(100_000);
    var export =
        objectMapper
            .createObjectNode()
            .put("fixtureOrigin", "AUTHORED_TEST_ONLY")
            .put(
                "runtimeIdentitySource",
                suppliedIdentity == null || suppliedIdentity.isNull()
                    ? "AUTHORED_PLACEHOLDER"
                    : "ACTUAL_RUNTIME_INPUT")
            .put("maximumTicks", 64)
            .put("initialFishingXp", 4470);
    export.set("manifest", compilation.manifest().getManifestJson());
    var output = java.nio.file.Path.of("build", "sc274-contract", "native-fishing.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(
        output,
        new CanonicalProjectionService().canonicalJson(export),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void tickGapInSnapshotsFailsClosed() {
    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-fishing-golden",
                    goldenSession(),
                    List.of(snapshotAt(0), snapshotAt(2)),
                    goldenConversion(),
                    goldenCapability(),
                    List.of(),
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.TICK_GAP_DETECTED);
  }

  @Test
  void actionTickOutsideRangeFailsClosed() {
    ReplayActionConversionResult conversion = conversion(tickedAction("npc:1518:42", "net", 1, 0));

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-fishing-golden",
                    goldenSession(),
                    List.of(goldenSnapshot()),
                    conversion,
                    goldenCapability(),
                    List.of(),
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.TICK_RANGE_INVALID);
  }

  @Test
  void duplicateActionSequenceFailsClosed() {
    ReplayActionConversionResult conversion =
        conversion(
            tickedAction("npc:1518:42", "net", 0, 0), tickedAction("npc:1518:42", "net", 0, 0));

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-fishing-golden",
                    goldenSession(),
                    List.of(goldenSnapshot()),
                    conversion,
                    goldenCapability(),
                    List.of(),
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_SEQUENCE_INVALID);
  }

  @Test
  void reorderedActionsFailClosed() {
    ReplayActionConversionResult conversion =
        conversion(
            tickedAction("npc:1518:42", "net", 1, 0), tickedAction("npc:1518:42", "net", 0, 1));

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-fishing-golden",
                    goldenSession(),
                    List.of(snapshotAt(0), snapshotAt(1)),
                    conversion,
                    goldenCapability(),
                    List.of(),
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_SEQUENCE_INVALID);
  }

  @Test
  void missingPlayerStateFailsClosed() {
    ScenarioManifestV2Compiler compiler = compiler();
    TickSnapshot first = initialSnapshot();
    first.setPlayerState(null);

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compiler.buildInitialState(first, conversion(npcAction("npc:1518:42", "net", 0))));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.PLAYER_STATE_MISSING);
  }

  @Test
  void incompatibleCacheRevisionFailsClosed() {
    CapabilityManifest capability =
        CapabilityManifest.current(
            new CapabilityManifest.CapabilityModes(true, true, true),
            new CapabilityManifest.XrspsIdentity("1.0.0", "build-golden", "", "sha256:content"),
            false);

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-fishing-golden",
                    goldenSession(),
                    List.of(goldenSnapshot()),
                    goldenConversion(),
                    capability,
                    List.of(),
                    null));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE);
  }

  @Test
  void gapsBlockSimulationButManifestRemainsPlayable() {
    Compilation compilation =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            gappedConversion(),
            goldenCapability(),
            List.of(),
            null);

    // The artifact itself is complete and usable for playback and policy replay.
    assertThat(compilation.manifest().getManifestJson().path("actions").size()).isEqualTo(1);
    assertThat(compilation.manifest().getManifestChecksum()).isNotBlank();
    assertThat(compilation.manifest().getCompleteness()).isEqualTo("INCOMPLETE");
    assertThat(compilation.isSimulationAllowed()).isFalse();
    assertThat(compilation.simulationBlockedReasons()).isNotEmpty();

    ReplayException exception =
        assertThrows(ReplayException.class, compilation::requireSimulationAllowed);

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  @Test
  void goldenCompilationAllowsSimulation() {
    Compilation compilation = goldenCompile();
    ScenarioManifestImportValidator.requireSimulationAllowed(
        compilation.manifest().getManifestJson());

    assertThat(compilation.manifest().getCompleteness()).isEqualTo("COMPLETE");
    assertThat(compilation.isSimulationAllowed()).isTrue();
    compilation.requireSimulationAllowed();
  }

  @Test
  void compilationGateRechecksContextAndChecksumInsteadOfTrustingCompleteFlags() {
    Compilation absent = new Compilation(null, null, null, List.of());
    assertThat(absent.isSimulationAllowed()).isFalse();
    assertThat(assertThrows(ReplayException.class, absent::requireSimulationAllowed).getErrorCode())
        .isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
    for (String mutation : List.of("context", "checksum", "audit")) {
      Compilation compilation = goldenCompile();
      ObjectNode document = (ObjectNode) compilation.manifest().getManifestJson();
      if (mutation.equals("context")) {
        ((ObjectNode) document.path("compilationEvidence").path("behaviorRunContext"))
            .put("taskKey", "tampered-source");
        document.remove("manifestChecksum");
        var canonical = new CanonicalProjectionService();
        document.put(
            "manifestChecksum", canonical.sha256Hex(canonical.canonicalJsonBytes(document)));
      } else if (mutation.equals("checksum")) {
        document.put("manifestChecksum", "sha256:" + "0".repeat(64));
      } else {
        document.remove("compilationEvidence");
      }
      assertThat(compilation.manifest().getCompleteness()).isEqualTo("COMPLETE");
      assertThat(document.path("conversion").path("gaps")).isEmpty();
      assertThat(compilation.isSimulationAllowed()).isFalse();
      assertThrows(ReplayException.class, compilation::requireSimulationAllowed);
    }
  }

  @Test
  void conversionCurationPreservesOperatorRole() {
    AtomicReference<RecordingCuration> saved = new AtomicReference<>();
    RecordingCuration operator =
        operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);

    compileWith(
        "recording-fishing-golden",
        goldenSession(),
        List.of(goldenSnapshot()),
        goldenConversion(),
        goldenCapability(),
        List.of(operator),
        saved);

    assertThat(saved.get()).isNotNull();
    assertThat(saved.get().getRole()).isEqualTo(CurationRole.TRAIN);
    assertThat(saved.get().getVersion()).isEqualTo(4);
  }

  @Test
  void compilationConsumesPublishedSnapshotAndPinsOperatorFieldAndActionSources() {
    var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
    var content = (ObjectNode) operator.getContentJson();
    content.with("initialStateOverrides").putObject("player").put("prayer", 17);
    var label =
        content
            .withArray("actionLabels")
            .addObject()
            .put("tick", 0)
            .put("sequence", 0)
            .put("status", "ACCEPTED_LABEL")
            .put("semanticActionType", "INTERACT_NPC")
            .put("targetStableId", "npc:1518:42");
    label.putObject("argumentsJson").put("option", "net");
    label.putObject("evidenceRef").put("fixture", "authored-compiler-test-only");
    JsonNode original = content.deepCopy();
    Compilation result =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            null);
    JsonNode document = result.manifest().getManifestJson();
    assertThat(document.path("initialState").path("player").path("prayer").asInt()).isEqualTo(17);
    assertThat(document.path("actions").get(0).path("provenance").asText())
        .isEqualTo("OPERATOR_SUPPLIED");
    assertThat(document.path("curation").path("sourceCuration").path("contentSnapshot"))
        .isEqualTo(original);
    assertThat(document.path("curation").path("datasetRole").asText()).isEqualTo("TRAIN");
    JsonNode provenance = document.path("compilationEvidence").path("fieldProvenance");
    assertThat(provenance.path("/initialState/player/prayer").path("provenance").asText())
        .isEqualTo("OPERATOR_SUPPLIED");
    assertThat(provenance.path("/initialState/player/prayer").path("sourceRef").asText())
        .endsWith("/content/initialStateOverrides/player/prayer");
    assertThat(provenance.path("/actions/0/option").path("sourceRef").asText())
        .endsWith("/content/actionLabels/0");
    assertThat(operator.getContentJson()).isEqualTo(original);
  }

  @Test
  void conversionCurationDefaultsHeldOutWithoutOperatorCuration() {
    AtomicReference<RecordingCuration> saved = new AtomicReference<>();

    compileWith(
        "recording-fishing-golden",
        goldenSession(),
        List.of(goldenSnapshot()),
        goldenConversion(),
        goldenCapability(),
        List.of(),
        saved);

    assertThat(saved.get()).isNotNull();
    assertThat(saved.get().getRole()).isEqualTo(CurationRole.HELD_OUT);
  }

  @Test
  void
      selectedPreviewAndExecutionUseIdenticalPublishedTrimLabelsAndOverridesWithoutPreviewWrites() {
    var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
    var content = (ObjectNode) operator.getContentJson();
    content.putObject("tickRange").put("start", 1).put("end", 1);
    content.with("initialStateOverrides").putObject("player").put("prayer", 17);
    var label =
        content
            .withArray("actionLabels")
            .addObject()
            .put("tick", 1)
            .put("sequence", 0)
            .put("status", "ACCEPTED_LABEL")
            .put("semanticActionType", "INTERACT_NPC")
            .put("targetStableId", "npc:1518:42");
    label.putObject("argumentsJson").put("option", "bait");
    label.putObject("evidenceRef").put("fixture", "authored-selected-preview-test");
    JsonNode immutable = content.deepCopy();
    TickSnapshot outside = snapshotAt(0);
    outside.setPlayerState(null);
    var snapshots = List.of(outside, snapshotAt(1));
    var conversion = rangedConversion(1, 1, tickedAction("npc:1518:42", "net", 1, 0));
    var preview =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            snapshots,
            conversion,
            goldenCapability(),
            List.of(operator),
            null,
            goldenContext(),
            true,
            operator.getId(),
            "operator-review");
    var executed =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            snapshots,
            conversion,
            goldenCapability(),
            List.of(operator),
            null,
            goldenContext(),
            false,
            operator.getId(),
            "operator-review");
    preview.requireSimulationAllowed();
    executed.requireSimulationAllowed();
    assertThat(preview.manifest().getManifestJson())
        .isEqualTo(executed.manifest().getManifestJson());
    assertThat(preview.manifest().getStartTick()).isEqualTo(1);
    assertThat(preview.manifest().getManifestJson().path("actions").get(0).path("option").asText())
        .isEqualTo("bait");
    assertThat(
            preview
                .manifest()
                .getManifestJson()
                .path("initialState")
                .path("player")
                .path("prayer")
                .asInt())
        .isEqualTo(17);
    assertThat(operator.getContentJson()).isEqualTo(immutable);
    assertThat(outside.getPlayerState()).isNull();
  }

  @Test
  void authorizedInitialFixtureFillsMissingStateBeforeValidationWithoutRewritingTheCapture() {
    TickSnapshot captured = goldenSnapshot();
    captured.setInventory(null);
    captured.setEquipment(null);
    ((ObjectNode) captured.getPlayerState()).remove("collisionRevision");
    var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.HELD_OUT);
    ObjectNode overrides = (ObjectNode) operator.getContentJson().path("initialStateOverrides");
    overrides.put("collisionRevision", "audited-fixture-collision");
    ObjectNode player = overrides.putObject("player");
    var slots = player.putArray("inventory");
    slots.addObject().put("slot", 2).put("itemId", 314).put("quantity", 50);
    slots.addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
    player.putArray("equipment");
    Compilation preview =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(captured),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            null,
            goldenContext(),
            true,
            operator.getId(),
            "operator-review");
    Compilation execution =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(captured),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            null,
            goldenContext(),
            false,
            operator.getId(),
            "operator-review");
    var document = preview.manifest().getManifestJson();
    assertThat(document).isEqualTo(execution.manifest().getManifestJson());
    assertThat(preview.isSimulationAllowed()).isTrue();
    assertThat(document.path("curation").path("datasetRole").asText()).isEqualTo("HELD_OUT");
    assertThat(
            document
                .path("initialState")
                .path("player")
                .path("inventory")
                .get(0)
                .path("slot")
                .asInt())
        .isZero();
    var provenance =
        document
            .path("compilationEvidence")
            .path("fieldProvenance")
            .path("/initialState/player/inventory/0/itemId");
    assertThat(provenance.path("provenance").asText()).isEqualTo("OPERATOR_SUPPLIED");
    assertThat(provenance.path("sourceRef").asText())
        .endsWith("/initialStateOverrides/player/inventory/1/itemId");
    assertThat(captured.getInventory()).isNull();
    assertThat(captured.getEquipment()).isNull();
    assertThat(captured.getPlayerState().has("collisionRevision")).isFalse();
  }

  @Test
  void operatorProjectionCannotReplaceOrCoerceValidatedNativeNpcPins() {
    for (boolean malformed : List.of(true, false)) {
      TickSnapshot captured = goldenSnapshot();
      ObjectNode npc = ((ObjectNode) captured.getNpcs().get(1)).deepCopy();
      npc.put("npcId", 42).put("npcTypeId", 1518).put("size", 1).put("spotId", "fixture-spot");
      captured.setNpcs(objectMapper.createArrayNode().add(npc));
      var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.HELD_OUT);
      ObjectNode supplied = npc.deepCopy();
      if (malformed) supplied.put("npcId", "not-an-integer");
      else supplied.put("npcId", 43);
      ((ObjectNode) operator.getContentJson().path("initialStateOverrides"))
          .putArray("npcs")
          .add(supplied);
      var raw = goldenConversion();
      var certified =
          new ReplayActionConversionResult(
              raw.sessionId(),
              raw.startTick(),
              raw.endTick(),
              raw.mode(),
              ConversionQuality.CERTIFIED,
              raw.actions(),
              raw.gaps(),
              raw.approximations(),
              raw.typedActionCount(),
              raw.legacyLogCount(),
              raw.actionCoveragePct(),
              raw.conversionChecksum());
      var error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "recording-fishing-golden",
                      goldenSession(),
                      List.of(captured),
                      certified,
                      goldenCapability(),
                      List.of(operator),
                      null,
                      goldenContext(),
                      true,
                      operator.getId(),
                      "operator-review"));
      assertThat(error.getErrorCode())
          .isIn(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE, ReplayErrorCode.ACTION_AMBIGUOUS);
      assertThat(captured.getNpcs().get(0).path("npcId").asInt()).isEqualTo(42);
    }
  }

  @Test
  void malformedInitialFixtureCannotBypassTheNormalStateValidation() {
    for (int variant = 0; variant < 4; variant++) {
      var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
      ObjectNode overrides = (ObjectNode) operator.getContentJson().path("initialStateOverrides");
      if (variant < 2) {
        ObjectNode item = overrides.putObject("player").putArray("inventory").addObject();
        item.put("slot", variant == 0 ? 28 : 0).put("itemId", 303);
        if (variant == 0) item.put("quantity", 1);
      } else if (variant == 2) overrides.put("objects", "not-a-list");
      else overrides.putObject("player").putObject("position").put("plane", 4);
      var error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "recording-fishing-golden",
                      goldenSession(),
                      List.of(goldenSnapshot()),
                      goldenConversion(),
                      goldenCapability(),
                      List.of(operator),
                      null,
                      goldenContext(),
                      true,
                      operator.getId(),
                      "operator-review"));
      assertThat(error.getErrorCode())
          .isIn(
              ReplayErrorCode.INVENTORY_SLOT_MISSING,
              ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
              ReplayErrorCode.POSITION_MISSING);
    }
  }

  @Test
  void authenticatedCompilationRequiresAnOwnedPublishedSourceEvenWithoutAnExplicitSelection() {
    for (boolean preview : List.of(true, false)) {
      var foreign = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
      var error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "recording-fishing-golden",
                      goldenSession(),
                      List.of(goldenSnapshot()),
                      goldenConversion(),
                      goldenCapability(),
                      List.of(foreign),
                      null,
                      goldenContext(),
                      preview,
                      null,
                      "other-owner"));
      assertThat(error.getHttpStatus().value()).isEqualTo(404);
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.CURATION_NOT_PUBLISHED);
    }
  }

  @Test
  void previewOfExistingConversionNeverRepairsChildRowsOrRepublishesIt() {
    AtomicReference<RecordingCuration> saved = new AtomicReference<>();
    var executed =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(),
            saved);
    var preview =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(saved.get()),
            null,
            goldenContext(),
            true,
            null,
            null);
    assertThat(preview.manifest().getManifestJson())
        .isEqualTo(executed.manifest().getManifestJson());
  }

  @Test
  void missingOrUnsupportedSnapshotSchemaFailsInPreviewAndCompilation() {
    for (Integer schema : java.util.Arrays.asList(null, 0, 3, 5)) {
      for (boolean preview : List.of(true, false)) {
        TickSnapshot snapshot = goldenSnapshot();
        snapshot.setSchemaVersion(schema);
        var error =
            assertThrows(
                ReplayException.class,
                () ->
                    compileWith(
                        "recording-fishing-golden",
                        goldenSession(),
                        List.of(snapshot),
                        goldenConversion(),
                        goldenCapability(),
                        List.of(),
                        null,
                        goldenContext(),
                        preview,
                        null,
                        null));
        assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED);
        assertThat(snapshot.getSchemaVersion()).isEqualTo(schema);
      }
    }
  }

  @Test
  void completeModernSnapshotProjectionsCompileDeterministicallyWithoutCoercingSource() {
    for (int schema : List.of(2, 4)) {
      TickSnapshot snapshot = goldenSnapshot();
      snapshot.setSchemaVersion(schema);
      var first =
          compileWith(
              "recording-fishing-golden",
              goldenSession(),
              List.of(snapshot),
              goldenConversion(),
              goldenCapability(),
              List.of(),
              null);
      var second =
          compileWith(
              "recording-fishing-golden",
              goldenSession(),
              List.of(snapshot),
              goldenConversion(),
              goldenCapability(),
              List.of(),
              null);
      assertThat(first.manifest().getManifestJson()).isEqualTo(second.manifest().getManifestJson());
      assertThat(snapshot.getSchemaVersion()).isEqualTo(schema);
      ScenarioManifestImportValidator.validate(first.manifest().getManifestJson());
    }
  }

  @Test
  void selectedPreviewRejectsForeignOrUnpublishedCurationsBeforeAnyPublication() {
    for (boolean foreign : List.of(true, false)) {
      var operator = operatorCuration("recording-fishing-golden", 3, CurationRole.TRAIN);
      if (!foreign) operator.setState(CurationState.DRAFT);
      var error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "recording-fishing-golden",
                      goldenSession(),
                      List.of(goldenSnapshot()),
                      goldenConversion(),
                      goldenCapability(),
                      List.of(operator),
                      null,
                      goldenContext(),
                      true,
                      operator.getId(),
                      foreign ? "foreign-owner" : "operator-review"));
      assertThat(error.getHttpStatus().value()).isEqualTo(404);
    }
  }

  @Test
  void missingFrozenIdentityBlocksEvenAtFullCapturedRowCoverage() {
    DebugSession session = new DebugSession();
    session.setSessionId("session-fishing-golden");
    session.setStartTime(goldenSession().getStartTime());
    session.setEndTime(goldenSession().getEndTime());
    // A valid-looking context in the CLIENT identity is not recording provenance.
    session.bindCaptureIdentity(goldenContext());
    Compilation result =
        compileWith(
            "recording-fishing-golden",
            session,
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(),
            null,
            null);
    assertThat(result.manifest().getActionCoveragePct()).isEqualTo(100.0);
    assertThat(result.manifest().getCompleteness()).isEqualTo("INCOMPLETE");
    assertThat(result.simulationBlockedReasons())
        .anyMatch(reason -> reason.contains("SOURCE_IDENTITY_INCOMPLETE"));
    assertThrows(ReplayException.class, result::requireSimulationAllowed);
    ScenarioManifestImportValidator.validate(result.manifest().getManifestJson());
  }

  @Test
  void laterFishingTargetAcrossRegionsAndPlanesIsNeverInjectedIntoInitialWorld() {
    TickSnapshot first = snapshotAt(0);
    first.setNpcs(objectMapper.createArrayNode());
    TickSnapshot later = snapshotAt(1);
    ((ObjectNode) later.getPlayerState().path("position"))
        .put("x", 3300)
        .put("y", 3400)
        .put("plane", 1);
    RecordingAction action = tickedAction("npc:1518:42", "net", 1, 0);
    ReplayActionConversionResult travel = rangedConversion(0, 1, action);
    Compilation result =
        compileWith(
            "recording-travel",
            goldenSession(),
            List.of(first, later),
            travel,
            goldenCapability(),
            List.of(),
            null);
    Compilation repeat =
        compileWith(
            "recording-travel",
            goldenSession(),
            List.of(first, later),
            travel,
            goldenCapability(),
            List.of(),
            null);
    JsonNode doc = result.manifest().getManifestJson();
    assertThat(doc.path("initialState").path("npcs")).isEmpty();
    assertThat(
            doc.path("compilationEvidence")
                .path("world")
                .path("actionTargets")
                .get(0)
                .path("tick")
                .asLong())
        .isEqualTo(1);
    assertThat(result.simulationBlockedReasons())
        .anyMatch(reason -> reason.contains("NATIVE_WORLD_SUPPORT_REQUIRED"));
    assertThat(
            doc.path("compilationEvidence").path("world").path("requiredNativeBindings").toString())
        .contains("REGION_OR_PLANE_TRANSITION", "snapshot:");
    assertThat(result.manifest().getManifestChecksum())
        .isEqualTo(repeat.manifest().getManifestChecksum());
    assertThrows(
        ReplayException.class, () -> ScenarioManifestImportValidator.requireSimulationAllowed(doc));
  }

  @Test
  void compilesAndExportsAuthoredBankTravelFishingRouteWithoutPromotingEligibility()
      throws Exception {
    String inputPath = System.getenv("SC274_NATIVE_BANK_TRAVEL_INPUT");
    JsonNode supplied =
        inputPath == null
            ? objectMapper.nullNode()
            : objectMapper.readTree(
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of(inputPath)));
    CapabilityManifest capability = goldenCapability();
    if (!supplied.isNull()) {
      assertThat(supplied.path("origin").asText())
          .isEqualTo("ACTUAL_STATIC_NATIVE_INPUTS_NOT_A_RECORDING");
      JsonNode identity = supplied.path("runtime");
      capability =
          CapabilityManifest.current(
              new CapabilityManifest.CapabilityModes(true, true, true),
              new CapabilityManifest.XrspsIdentity(
                  identity.path("adapterVersion").asText(),
                  identity.path("buildCommit").asText(),
                  identity.path("cacheRevision").asText(),
                  identity.path("contentRevision").asText()),
              false);
    }
    ObjectNode bankObject =
        supplied.isNull()
            ? objectMapper
                .createObjectNode()
                .put("stableId", "loc:10355:3091:3243:0")
                .put("locId", 10355)
                .put("shape", 10)
                .put("rotation", 1)
            : (ObjectNode) supplied.path("bankObject").deepCopy();
    if (supplied.isNull())
      bankObject.putObject("position").put("x", 3091).put("y", 3243).put("plane", 0);
    ObjectNode world =
        supplied.isNull()
            ? objectMapper
                .createObjectNode()
                .put("schema", "xrsps-native-fishing-world/v1")
                .put("catalogChecksum", "sha256:" + "a".repeat(64))
            : (ObjectNode) supplied.path("world").deepCopy();
    if (supplied.isNull()) world.putArray("spawnIndices").add(0);
    var sourceAudit =
        new ScenarioCompilationEvidence(objectMapper, new CanonicalProjectionService());
    List<TickSnapshot> snapshots = new java.util.ArrayList<>();
    for (int tick = 0; tick <= 64; tick++) {
      TickSnapshot snapshot = snapshotAt(tick);
      ObjectNode player = (ObjectNode) snapshot.getPlayerState();
      player
          .putObject("position")
          .put("x", tick < 6 ? 3090 : tick < 16 ? 2520 : 2517)
          .put("y", tick < 6 ? 3243 : tick < 16 ? 3571 : 3575)
          .put("plane", 0);
      player.putObject("skills").putObject("fishing").put("level", 20).put("xp", 4470);
      snapshot.setGameObjects(objectMapper.createArrayNode().add(bankObject.deepCopy()));
      ArrayNode widgets = objectMapper.createArrayNode();
      if (tick == 1 || tick == 2)
        widgets
            .addObject()
            .put("stableId", tick == 1 ? "container:bank:0:303" : "container:bank:1:3867")
            .put("widgetId", 786444)
            .put("widgetIndex", tick - 1);
      if (tick == 3) widgets.addObject().put("stableId", "widget:786434").put("widgetId", 786434);
      snapshot.setWidgets(widgets);
      ArrayNode inventory = objectMapper.createArrayNode();
      if (tick >= 2) inventory.addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
      if (tick >= 3 && tick <= 5)
        inventory.addObject().put("slot", 1).put("itemId", 3867).put("quantity", 1);
      snapshot.setInventory(inventory);
      ObjectNode dialogue = objectMapper.createObjectNode();
      if (tick == 5) {
        dialogue.put("stableId", "jewelry:1:3867");
        dialogue.putArray("options").add("Barbarian Assault");
      }
      snapshot.setDialogue(dialogue);
      ArrayNode npcs = objectMapper.createArrayNode();
      if (tick >= 16) {
        ObjectNode npc =
            npcs.addObject()
                .put("stableId", "authored:sc274:travel-source")
                .put("npcId", 42)
                .put("npcTypeId", 1544)
                .put("size", 1)
                .put("spotId", "sea_small_net")
                .put("spotType", "net");
        npc.putArray("actions").add("net");
        npc.putObject("position").put("x", 2516).put("y", 3575).put("plane", 0);
      }
      snapshot.setNpcs(npcs);
      snapshots.add(snapshot);
    }
    var sourceRefs = snapshots.stream().map(sourceAudit::snapshotRef).toList();
    var operator = operatorCuration("authored-sc274-bank-travel", 3, CurationRole.HELD_OUT);
    operator.setId(UUID.fromString("7c3da96e-109f-4653-b1f2-2765b5b5c8f1"));
    ObjectNode content = (ObjectNode) operator.getContentJson();
    ((ObjectNode) content.path("tickRange")).put("end", 64);
    ObjectNode overrides = (ObjectNode) content.path("initialStateOverrides");
    overrides.put("travelProfile", "jewelry-v1").put("bankOpen", false);
    ObjectNode bank =
        overrides
            .putObject("bank")
            .put("capacity", 800)
            .put("withdrawNotes", false)
            .put("insertMode", false)
            .put("placeholderMode", false)
            .put("quantityMode", 0)
            .put("customQuantity", 0)
            .put("currentTab", 0)
            .put("tabDisplayMode", 0);
    ArrayNode slots = bank.putArray("slots");
    for (int index = 0; index < 2; index++)
      slots
          .addObject()
          .put("slot", index)
          .put("itemId", index == 0 ? 303 : 3867)
          .put("quantity", 2)
          .put("placeholder", false)
          .put("filler", false)
          .put("tab", 0);
    overrides.set("nativeFishingWorld", world);
    ObjectNode binding =
        overrides
            .putObject("nativeNpcBindings")
            .put("schema", "xrsps-native-npc-bindings/v1")
            .put("catalogChecksum", world.path("catalogChecksum").asText())
            .putArray("bindings")
            .addObject()
            .put("actionIndex", 7)
            .put("tick", 16)
            .put("sequence", 0)
            .put("sourceStableId", "authored:sc274:travel-source")
            .put("spawnIndex", world.path("spawnIndices").get(0).asInt())
            .put("npcTypeId", 1544)
            .put("sourceSnapshotRef", sourceRefs.get(16));
    binding.putObject("position").put("x", 2516).put("y", 3575).put("plane", 0);
    operator.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(content)));
    JsonNode originalContent = content.deepCopy();
    RecordingAction[] actions = {
      routeAction(
          "INTERACT_OBJECT", bankObject.path("stableId").asText(), 0, Map.of("option", "bank")),
      routeAction(
          "INTERACT_WIDGET",
          "container:bank:0:303",
          1,
          Map.of("option", "withdraw-1", "widgetId", 786444, "widgetIndex", 0)),
      routeAction(
          "INTERACT_WIDGET",
          "container:bank:1:3867",
          2,
          Map.of("option", "withdraw-1", "widgetId", 786444, "widgetIndex", 1)),
      routeAction(
          "INTERACT_WIDGET", "widget:786434", 3, Map.of("option", "close", "widgetId", 786434)),
      routeAction(
          "INTERACT_INVENTORY_ITEM",
          "inventory:1",
          4,
          Map.of("option", "rub", "slot", 1, "itemId", 3867)),
      routeAction(
          "SELECT_DIALOGUE_OPTION", "jewelry:1:3867", 5, Map.of("option", "Barbarian Assault")),
      routeAction(
          "WALK_TO",
          "authored:destination",
          6,
          Map.of("destination", Map.of("x", 2517, "y", 3575, "plane", 0))),
      tickedAction("authored:sc274:travel-source", "net", 16, 0)
    };
    var conversion = rangedConversion(0, 64, actions);
    var result =
        compileWith(
            "authored-sc274-bank-travel",
            goldenSession(),
            snapshots,
            conversion,
            capability,
            List.of(operator),
            null);
    var repeat =
        compileWith(
            "authored-sc274-bank-travel",
            goldenSession(),
            snapshots,
            conversion,
            capability,
            List.of(operator),
            null);
    JsonNode document = result.manifest().getManifestJson();
    assertThat(new CanonicalProjectionService().canonicalJsonBytes(document))
        .containsExactly(
            new CanonicalProjectionService()
                .canonicalJsonBytes(repeat.manifest().getManifestJson()));
    assertThat(result.manifest().getCompleteness()).isEqualTo("INCOMPLETE");
    assertThat(result.simulationBlockedReasons())
        .anyMatch(reason -> reason.contains("NATIVE_WORLD_SUPPORT_REQUIRED"));
    assertThrows(ReplayException.class, result::requireSimulationAllowed);
    ScenarioManifestImportValidator.validate(document);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(document.path("actions")).hasSize(8);
    assertThat(document.path("initialState").path("npcs")).isEmpty();
    assertThat(document.path("initialState").path("player").path("inventory")).isEmpty();
    assertThat(document.path("initialState").path("bank")).isEqualTo(bank);
    assertThat(
            document
                .path("initialState")
                .path("nativeNpcBindings")
                .path("bindings")
                .get(0)
                .path("sourceSnapshotRef")
                .asText())
        .isEqualTo(sourceRefs.get(16));
    var export =
        objectMapper
            .createObjectNode()
            .put("fixtureOrigin", "AUTHORED_TEST_ONLY")
            .put("compilerEligibility", "INCOMPLETE_BLOCKED")
            .put(
                "runtimeIdentitySource",
                supplied.isNull() ? "AUTHORED_PLACEHOLDER" : "ACTUAL_RUNTIME_INPUT")
            .put(
                "nativeWorldSource",
                supplied.isNull()
                    ? "AUTHORED_PLACEHOLDER"
                    : "ACTUAL_STATIC_NATIVE_INPUTS_NOT_A_RECORDING")
            .put("certification", false)
            .put("genuineRecordingEvidence", false)
            .put("productionLauncherQualified", false)
            .put("maximumTicks", 64)
            .put("initialFishingXp", 4470);
    export.set("manifest", document);
    var output = java.nio.file.Path.of("build", "sc274-contract", "native-bank-travel.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(output, new CanonicalProjectionService().canonicalJson(export));
    CapabilityManifest pinned = capability;
    ObjectNode widget = (ObjectNode) snapshots.get(2).getWidgets().get(0);
    widget.put("widgetIndex", 0);
    ReplayException stale =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "authored-sc274-bank-travel",
                    goldenSession(),
                    snapshots,
                    conversion,
                    pinned,
                    List.of(operator),
                    null));
    assertThat(stale.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_AMBIGUOUS);
    assertThat(stale.getMessage()).contains("source=snapshot:");
    widget.put("widgetIndex", 1);
    ((ArrayNode) snapshots.get(2).getWidgets()).add(widget.deepCopy());
    ReplayException ambiguous =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "authored-sc274-bank-travel",
                    goldenSession(),
                    snapshots,
                    conversion,
                    pinned,
                    List.of(operator),
                    null));
    assertThat(ambiguous.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_AMBIGUOUS);
    ((ArrayNode) snapshots.get(2).getWidgets()).remove(1);
    ((ObjectNode) binding.path("position")).put("plane", 1);
    ReplayException plane =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "authored-sc274-bank-travel",
                    goldenSession(),
                    snapshots,
                    conversion,
                    pinned,
                    List.of(operator),
                    null));
    assertThat(plane.getMessage()).startsWith("NATIVE_NPC_BINDING_INVALID:");
    ((ObjectNode) binding.path("position")).put("plane", 0);
    assertThat(operator.getContentJson()).isEqualTo(originalContent);
    assertThat(snapshots.stream().map(sourceAudit::snapshotRef).toList()).isEqualTo(sourceRefs);
  }

  private RecordingAction routeAction(
      String type, String target, long tick, Map<String, Object> arguments) {
    RecordingAction action = tickedAction(target, "unused", tick, 0);
    action.setActionType(type);
    action.setArguments(arguments);
    return action;
  }

  @Test
  void compilesAndExportsClosedPublishedNativeBindingWithoutInventingWorldEligibility()
      throws Exception {
    JsonNode suppliedIdentity =
        objectMapper.readTree(
            System.getenv().getOrDefault("SC274_NATIVE_RUNTIME_IDENTITY_JSON", "null"));
    JsonNode suppliedWorld =
        objectMapper.readTree(
            System.getenv().getOrDefault("SC274_NATIVE_BINDING_WORLD_JSON", "null"));
    CapabilityManifest capability = goldenCapability();
    if (suppliedIdentity != null && !suppliedIdentity.isNull()) {
      capability =
          CapabilityManifest.current(
              new CapabilityManifest.CapabilityModes(true, true, true),
              new CapabilityManifest.XrspsIdentity(
                  suppliedIdentity.path("adapterVersion").asText(),
                  suppliedIdentity.path("buildCommit").asText(),
                  suppliedIdentity.path("cacheRevision").asText(),
                  suppliedIdentity.path("contentRevision").asText()),
              false);
    }
    ObjectNode world =
        suppliedWorld != null && suppliedWorld.isObject()
            ? (ObjectNode) suppliedWorld.deepCopy()
            : objectMapper
                .createObjectNode()
                .put("schema", "xrsps-native-fishing-world/v1")
                .put("catalogChecksum", "sha256:" + "a".repeat(64));
    if (!world.has("spawnIndices")) world.putArray("spawnIndices").add(0);
    List<TickSnapshot> snapshots = new java.util.ArrayList<>();
    for (int tick = 0; tick <= 32; tick++) {
      TickSnapshot snapshot = snapshotAt(tick);
      ((ObjectNode) snapshot.getPlayerState())
          .putObject("position")
          .put("x", 2517)
          .put("y", 3575)
          .put("plane", 0);
      ((ObjectNode) snapshot.getPlayerState())
          .putObject("skills")
          .putObject("fishing")
          .put("level", 20)
          .put("xp", 4470);
      snapshot.setInventory(
          objectMapper
              .createArrayNode()
              .add(
                  objectMapper
                      .createObjectNode()
                      .put("slot", 0)
                      .put("itemId", 303)
                      .put("quantity", 1)));
      var npcs = objectMapper.createArrayNode();
      if (tick > 0) {
        var npc =
            npcs.addObject()
                .put("stableId", "authored:sc274:native-source")
                .put("npcId", 42)
                .put("npcTypeId", 1544)
                .put("size", 1)
                .put("spotId", "sea_small_net")
                .put("spotType", "net");
        npc.putArray("actions").add("net");
        npc.putObject("position").put("x", 2516).put("y", 3575).put("plane", 0);
      }
      snapshot.setNpcs(npcs);
      snapshots.add(snapshot);
    }
    var operator = operatorCuration("authored-sc274-native-binding", 3, CurationRole.HELD_OUT);
    operator.setId(UUID.fromString("8c3da96e-109f-4653-b1f2-2765b5b5c8f1"));
    var content = (ObjectNode) operator.getContentJson();
    ((ObjectNode) content.path("tickRange")).put("end", 32);
    var overrides = (ObjectNode) content.path("initialStateOverrides");
    overrides.set("nativeFishingWorld", world);
    var row =
        overrides
            .putObject("nativeNpcBindings")
            .put("schema", "xrsps-native-npc-bindings/v1")
            .put("catalogChecksum", world.path("catalogChecksum").asText())
            .putArray("bindings")
            .addObject()
            .put("actionIndex", 0)
            .put("tick", 16)
            .put("sequence", 0)
            .put("sourceStableId", "authored:sc274:native-source")
            .put("spawnIndex", world.path("spawnIndices").get(0).asInt())
            .put("npcTypeId", 1544)
            .put(
                "sourceSnapshotRef",
                new ScenarioCompilationEvidence(objectMapper, new CanonicalProjectionService())
                    .snapshotRef(snapshots.get(16)));
    row.putObject("position").put("x", 2516).put("y", 3575).put("plane", 0);
    operator.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(content)));
    JsonNode original = content.deepCopy();
    var conversion =
        rangedConversion(0, 32, tickedAction("authored:sc274:native-source", "net", 16, 0));
    Compilation result =
        compileWith(
            "authored-sc274-native-binding",
            goldenSession(),
            snapshots,
            conversion,
            capability,
            List.of(operator),
            null);
    Compilation repeat =
        compileWith(
            "authored-sc274-native-binding",
            goldenSession(),
            snapshots,
            conversion,
            capability,
            List.of(operator),
            null);
    JsonNode document = result.manifest().getManifestJson();
    assertThat(document).isEqualTo(repeat.manifest().getManifestJson());
    assertThat(document.path("initialState").path("npcs")).isEmpty();
    assertThat(document.path("initialState").path("nativeNpcBindings"))
        .isEqualTo(overrides.path("nativeNpcBindings"));
    assertThat(operator.getContentJson()).isEqualTo(original);
    assertThat(result.manifest().getCompleteness()).isEqualTo("INCOMPLETE");
    assertThat(result.simulationBlockedReasons())
        .anyMatch(reason -> reason.contains("NATIVE_WORLD_SUPPORT_REQUIRED"));
    assertThat(
            document
                .path("compilationEvidence")
                .path("world")
                .path("requiredNativeBindings")
                .toString())
        .contains("NATIVE_FISHING_WORLD_CONTRACT");
    assertThat(
            document
                .path("compilationEvidence")
                .path("fieldProvenance")
                .path("/initialState/nativeNpcBindings/bindings/0/sourceSnapshotRef")
                .path("sourceRef")
                .asText())
        .endsWith("/content/initialStateOverrides/nativeNpcBindings/bindings/0/sourceSnapshotRef");
    assertThrows(ReplayException.class, result::requireSimulationAllowed);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    var export =
        objectMapper
            .createObjectNode()
            .put("fixtureOrigin", "AUTHORED_TEST_ONLY")
            .put("compilerEligibility", "INCOMPLETE_BLOCKED")
            .put(
                "runtimeIdentitySource",
                suppliedIdentity == null || suppliedIdentity.isNull()
                    ? "AUTHORED_PLACEHOLDER"
                    : "ACTUAL_RUNTIME_INPUT")
            .put(
                "nativeWorldSource",
                suppliedWorld == null || suppliedWorld.isNull()
                    ? "AUTHORED_PLACEHOLDER"
                    : "ACTUAL_CATALOGUE_INPUT")
            .put("sourceActionIndex", 0)
            .put("sourceTick", 16)
            .put("maximumTicks", 32)
            .put("initialFishingXp", 4470);
    export.set("manifest", document);
    var output = java.nio.file.Path.of("build", "sc274-contract", "native-npc-binding.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(
        output,
        new CanonicalProjectionService().canonicalJson(export),
        java.nio.charset.StandardCharsets.UTF_8);
    // A separate authored fixture exercises repeated payloads at one source tick
    // and a later source action. Do not rewrite the original export or evidence.
    ObjectNode scheduledContent = content.deepCopy();
    var scheduledRows =
        (ArrayNode)
            scheduledContent
                .path("initialStateOverrides")
                .path("nativeNpcBindings")
                .path("bindings");
    scheduledRows.add(row.deepCopy().put("actionIndex", 1).put("sequence", 1));
    scheduledRows.add(
        row.deepCopy()
            .put("actionIndex", 2)
            .put("tick", 20)
            .put(
                "sourceSnapshotRef",
                new ScenarioCompilationEvidence(objectMapper, new CanonicalProjectionService())
                    .snapshotRef(snapshots.get(20))));
    operator.setContentJson(scheduledContent);
    operator.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(scheduledContent)));
    var scheduledConversion =
        rangedConversion(
            0,
            32,
            tickedAction("authored:sc274:native-source", "net", 16, 0),
            tickedAction("authored:sc274:native-source", "net", 16, 1),
            tickedAction("authored:sc274:native-source", "net", 20, 0));
    Compilation scheduled =
        compileWith(
            "authored-sc274-native-binding",
            goldenSession(),
            snapshots,
            scheduledConversion,
            capability,
            List.of(operator),
            null);
    Compilation scheduledRepeat =
        compileWith(
            "authored-sc274-native-binding",
            goldenSession(),
            snapshots,
            scheduledConversion,
            capability,
            List.of(operator),
            null);
    assertThat(scheduled.manifest().getManifestJson())
        .isEqualTo(scheduledRepeat.manifest().getManifestJson());
    assertThat(scheduled.manifest().getManifestJson().path("actions")).hasSize(3);
    assertThat(scheduled.manifest().getCompleteness()).isEqualTo("INCOMPLETE");
    assertThrows(ReplayException.class, scheduled::requireSimulationAllowed);
    ObjectNode scheduledExport = export.deepCopy();
    scheduledExport.set("manifest", scheduled.manifest().getManifestJson());
    scheduledExport.remove(List.of("sourceActionIndex", "sourceTick"));
    scheduledExport.putArray("sourceTicks").add(16).add(16).add(20);
    java.nio.file.Files.writeString(
        output.resolveSibling("native-source-schedule.json"),
        new CanonicalProjectionService().canonicalJson(scheduledExport),
        java.nio.charset.StandardCharsets.UTF_8);
    operator.setContentJson(content);
    operator.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(content)));
    for (String field : List.of("npcTypeId", "sourceSnapshotRef", "position")) {
      ObjectNode badContent = content.deepCopy();
      ObjectNode badRow =
          (ObjectNode)
              badContent
                  .path("initialStateOverrides")
                  .path("nativeNpcBindings")
                  .path("bindings")
                  .get(0);
      if (field.equals("position")) ((ObjectNode) badRow.path("position")).put("plane", 1);
      else if (field.equals("npcTypeId")) badRow.put(field, 1530);
      else badRow.put(field, "snapshot:sha256:" + "f".repeat(64));
      operator.setContentJson(badContent);
      CapabilityManifest pinned = capability;
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "authored-sc274-native-binding",
                      goldenSession(),
                      snapshots,
                      conversion,
                      pinned,
                      List.of(operator),
                      null));
      assertThat(error.getMessage()).startsWith("NATIVE_NPC_BINDING_INVALID:");
    }
    operator.setContentJson((ObjectNode) original);
  }

  @Test
  void targetMustExistUniquelyAtExecutionTickNotJustInitially() {
    for (boolean duplicate : List.of(false, true)) {
      TickSnapshot later = snapshotAt(1);
      ArrayNode npcs = objectMapper.createArrayNode();
      if (duplicate) {
        npcs.add(goldenSnapshot().getNpcs().get(1).deepCopy());
        npcs.add(goldenSnapshot().getNpcs().get(1).deepCopy());
      }
      later.setNpcs(npcs);
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () ->
                  compileWith(
                      "recording-travel",
                      goldenSession(),
                      List.of(snapshotAt(0), later),
                      rangedConversion(0, 1, tickedAction("npc:1518:42", "net", 1, 0)),
                      goldenCapability(),
                      List.of(),
                      null));
      assertThat(error.getErrorCode())
          .isEqualTo(
              duplicate
                  ? ReplayErrorCode.ACTION_AMBIGUOUS
                  : ReplayErrorCode.ACTION_TARGET_UNRESOLVED);
      assertThat(error.getMessage()).contains("source=snapshot:");
    }
  }

  @Test
  void ambiguousWidgetIdentityFailsWithSourceReference() {
    TickSnapshot first = goldenSnapshot();
    ArrayNode widgets = objectMapper.createArrayNode();
    widgets.addObject().put("stableId", "bank-widget").put("widgetId", 12);
    first.setWidgets(widgets);
    RecordingAction action = npcAction("bank-widget", "withdraw", 0);
    action.setActionType("INTERACT_WIDGET");
    action.setArguments(Map.of("widgetId", 13, "option", "withdraw"));
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () ->
                compileWith(
                    "recording-bank",
                    goldenSession(),
                    List.of(first),
                    rangedConversion(0, 0, action),
                    goldenCapability(),
                    List.of(),
                    null));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.ACTION_AMBIGUOUS);
    assertThat(error.getMessage()).contains("snapshot:");
  }

  @Test
  void missingQuantityDuplicateSlotAndMissingPlaneAreNotDefaulted() {
    for (int variant = 0; variant < 3; variant++) {
      TickSnapshot snapshot = goldenSnapshot();
      ArrayNode inventory = objectMapper.createArrayNode();
      if (variant == 0) inventory.addObject().put("slot", 0).put("itemId", 303);
      if (variant == 1) {
        inventory.addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
        inventory.addObject().put("slot", 0).put("itemId", 303).put("quantity", 1);
      }
      snapshot.setInventory(inventory);
      if (variant == 2) ((ObjectNode) snapshot.getPlayerState().path("position")).remove("plane");
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () -> compiler().buildInitialState(snapshot, goldenConversion()));
      assertThat(error.getErrorCode())
          .isEqualTo(
              variant == 2
                  ? ReplayErrorCode.POSITION_MISSING
                  : ReplayErrorCode.INVENTORY_SLOT_MISSING);
    }
  }

  @Test
  void changedRoleOrRecordingProducesDistinctImmutableIdentity() {
    RecordingCuration operator =
        operatorCuration("recording-fishing-golden", 1, CurationRole.TRAIN);
    operator.setId(UUID.fromString("91e1dfd4-37db-4359-93e6-f226ca677ac4"));
    AtomicReference<RecordingCuration> first = new AtomicReference<>();
    Compilation train =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            first);
    operator.setRole(CurationRole.HELD_OUT);
    AtomicReference<RecordingCuration> second = new AtomicReference<>();
    Compilation heldOut =
        compileWith(
            "recording-fishing-golden",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(operator),
            second);
    Compilation otherRecording =
        compileWith(
            "recording-other",
            goldenSession(),
            List.of(goldenSnapshot()),
            goldenConversion(),
            goldenCapability(),
            List.of(),
            null);
    assertThat(first.get().getRole()).isEqualTo(CurationRole.TRAIN);
    assertThat(second.get().getRole()).isEqualTo(CurationRole.HELD_OUT);
    assertThat(first.get().getId()).isNotEqualTo(second.get().getId());
    assertThat(train.manifest().getId()).isNotEqualTo(heldOut.manifest().getId());
    assertThat(train.manifest().getId()).isNotEqualTo(otherRecording.manifest().getId());
  }

  @Test
  void everyOutputFieldOutsideProvenanceIndexHasAnExplicitSource() {
    JsonNode document = goldenCompile().manifest().getManifestJson();
    JsonNode fields = document.path("compilationEvidence").path("fieldProvenance");
    checkProvenance(document, "", fields);
    assertThat(document.path("compilationEvidence").path("behaviorRunContext"))
        .isEqualTo(
            fixtureRecording("recording-fishing-golden", goldenSession(), goldenContext())
                .getRunContext());
  }

  private void checkProvenance(JsonNode value, String path, JsonNode fields) {
    if (path.equals("/compilationEvidence")
        || path.startsWith("/compilationEvidence/fieldProvenance")) {
      if (path.equals("/compilationEvidence")) {
        value
            .fields()
            .forEachRemaining(
                field -> checkProvenance(field.getValue(), path + "/" + field.getKey(), fields));
      }
      return;
    }
    assertThat(fields.path(path).path("sourceRef").asText()).isNotBlank();
    assertThat(fields.path(path).path("provenance").asText())
        .isIn("OBSERVED", "DERIVED", "OPERATOR_SUPPLIED", "DEFAULTED");
    assertThat(fields.path(path).path("outputPointer").asText()).isEqualTo(path);
    if (value.isObject())
      value
          .fields()
          .forEachRemaining(
              field ->
                  checkProvenance(
                      field.getValue(),
                      path + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
                      fields));
    if (value.isArray())
      for (int index = 0; index < value.size(); index++) {
        checkProvenance(value.get(index), path + "/" + index, fields);
      }
  }

  private ReplayActionConversionResult rangedConversion(
      long start, long end, RecordingAction... actions) {
    return new ReplayActionConversionResult(
        "session-fishing-golden",
        start,
        end,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        java.util.Arrays.stream(actions)
            .map(
                action ->
                    new ConvertedReplayAction(
                        action, ActionProvenance.CAPTURED, "synthetic-test-fixture", null, null))
            .toList(),
        List.of(),
        List.of(),
        actions.length,
        0,
        100.0,
        "sha256:travel-fixture");
  }

  @Test
  void preflightDistinguishesLaterNativeSupportFromAnUnresolvedInitialTarget() {
    TickSnapshot first = snapshotAt(0);
    first.setNpcs(objectMapper.createArrayNode());
    TickSnapshot later = snapshotAt(1);
    var response =
        preflight(
            goldenSession(),
            List.of(first, later),
            rangedConversion(0, 1, tickedAction("npc:1518:42", "net", 1, 0)));
    assertThat(response.simulationEligible()).isFalse();
    assertThat(response.playbackEligible()).isTrue();
    assertThat(response.policyReplayEligible()).isTrue();
    assertThat(response.blockingReasons())
        .anyMatch(reason -> reason.message().contains("NATIVE_WORLD_SUPPORT_REQUIRED"));
    assertThat(response.blockingReasons())
        .noneMatch(reason -> reason.code() == ReplayErrorCode.ACTION_TARGET_UNRESOLVED);
  }

  @Test
  void preflightFullCoverageCannotOverrideAnIncompleteStream() {
    var response = preflight(goldenSession(), List.of(goldenSnapshot()), gappedConversion());
    assertThat(response.actionCoveragePct()).isEqualTo(100.0);
    assertThat(response.simulationEligible()).isFalse();
    assertThat(response.blockingReasons())
        .anyMatch(reason -> reason.code() == ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  private com.chromascape.web.replay.api.dto.XrspsPreflightResponse preflight(
      DebugSession session, List<TickSnapshot> snapshots, ReplayActionConversionResult conversion) {
    var resolver = mock(ReplayRecordingResolver.class);
    when(resolver.resolve(anyString()))
        .thenReturn(
            new ResolvedRecording(
                fixtureRecording("recording-test", session, goldenContext()), session));
    var integrity = mock(com.chromascape.web.replay.catalog.RecordingIntegrityService.class);
    when(integrity.evaluate(any()))
        .thenReturn(
            new com.chromascape.web.replay.catalog.dto.ReplayEligibility(
                true, true, true, List.of()));
    var repository = mock(TickSnapshotRepository.class);
    when(repository.findMinTickBySessionId(anyString()))
        .thenReturn(Optional.of(snapshots.get(0).getTick()));
    when(repository.findMaxTickBySessionId(anyString()))
        .thenReturn(Optional.of(snapshots.get(snapshots.size() - 1).getTick()));
    when(repository.findBySessionIdAndTickBetweenOrderByTick(anyString(), any(), any()))
        .thenReturn(snapshots);
    var converter = mock(ReplayActionConversionService.class);
    when(converter.convert(anyString(), anyLong(), anyLong(), any())).thenReturn(conversion);
    var capability = mock(ReplayCapabilityService.class);
    when(capability.getManifest()).thenReturn(goldenCapability());
    return new com.chromascape.web.replay.catalog.ReplayV2PreflightService(
            resolver, integrity, repository, converter, capability, null)
        .preflight("recording-test", ConversionMode.AUTO);
  }

  @Test
  void initialBankObjectDoesNotImplyExactBankSemanticsOrExecutionEligibility() {
    TickSnapshot first = goldenSnapshot();
    ArrayNode objects = objectMapper.createArrayNode();
    objects.addObject().put("stableId", "bank:1").put("objectId", 100);
    first.setGameObjects(objects);
    RecordingAction action = npcAction("bank:1", "bank", 0);
    action.setActionType("INTERACT_OBJECT");
    Compilation result =
        compileWith(
            "recording-bank",
            goldenSession(),
            List.of(first),
            rangedConversion(0, 0, action),
            goldenCapability(),
            List.of(),
            null);
    assertThat(result.isSimulationAllowed()).isFalse();
    assertThat(result.manifest().getManifestJson().path("conversion").path("gaps").toString())
        .contains("NATIVE_WORLD_SUPPORT_REQUIRED", "snapshot:");
  }

  @Test
  void advertisedNativeMethodNameNeverDischargesMissingSourceOrWorldEvidence() throws Exception {
    var base = goldenCapability();
    var nativeCapability =
        new CapabilityManifest(
            base.schemaVersion(),
            base.controlAllowed(),
            base.modes(),
            base.scenarioSchemaVersions(),
            base.semanticActionVersions(),
            base.snapshotVersions(),
            base.projectionVersions(),
            base.hashVersions(),
            base.xrsps(),
            base.openRuneAvailable(),
            base.certifiable(),
            base.fidelity(),
            base.supportedActions(),
            base.viewerTraceSchema(),
            List.of(
                new com.chromascape.web.replay.capability.ReplayMethodCapability(
                    "bank:containers-v1",
                    null,
                    List.of("bank", "objects"),
                    null,
                    null,
                    List.of("INTERACT_OBJECT", "INTERACT_WIDGET", "WALK_TO"))));
    TickSnapshot first = goldenSnapshot();
    ArrayNode objects = objectMapper.createArrayNode();
    objects.addObject().put("stableId", "bank:1").put("objectId", 100);
    first.setGameObjects(objects);
    RecordingAction action = npcAction("bank:1", "bank", 0);
    action.setActionType("INTERACT_OBJECT");
    var sourceAudit =
        new ScenarioCompilationEvidence(objectMapper, new CanonicalProjectionService());
    var original = sourceAudit.world(List.of(first), rangedConversion(0, 0, action), base);
    assertThat(sourceAudit.world(List.of(first), rangedConversion(0, 0, action), nativeCapability))
        .isEqualTo(original);
    Compilation result =
        compileWith(
            "recording-bank",
            goldenSession(),
            List.of(first),
            rangedConversion(0, 0, action),
            nativeCapability,
            List.of(),
            null);
    assertThat(result.isSimulationAllowed()).isFalse();
    assertThat(result.manifest().getManifestJson().path("conversion").path("gaps").toString())
        .contains("NATIVE_WORLD_SUPPORT_REQUIRED", "snapshot:");
  }

  private Compilation goldenCompile() {
    return compileWith(
        "recording-fishing-golden",
        goldenSession(),
        List.of(goldenSnapshot()),
        goldenConversion(),
        goldenCapability(),
        List.of(),
        null);
  }

  private Compilation compileWith(
      String recordingId,
      DebugSession session,
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability,
      List<RecordingCuration> priorCurations,
      AtomicReference<RecordingCuration> savedCuration) {
    return compileWith(
        recordingId,
        session,
        snapshots,
        conversion,
        capability,
        priorCurations,
        savedCuration,
        goldenContext());
  }

  private Compilation compileWith(
      String recordingId,
      DebugSession session,
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability,
      List<RecordingCuration> priorCurations,
      AtomicReference<RecordingCuration> savedCuration,
      ObjectNode context) {
    return compileWith(
        recordingId,
        session,
        snapshots,
        conversion,
        capability,
        priorCurations,
        savedCuration,
        context,
        false,
        null,
        null);
  }

  private Compilation compileWith(
      String recordingId,
      DebugSession session,
      List<TickSnapshot> snapshots,
      ReplayActionConversionResult conversion,
      CapabilityManifest capability,
      List<RecordingCuration> priorCurations,
      AtomicReference<RecordingCuration> savedCuration,
      ObjectNode context,
      boolean preview,
      UUID selectedId,
      String ownerId) {
    long minTick = snapshots.stream().mapToLong(snapshot -> snapshot.getTick()).min().orElseThrow();
    long maxTick = snapshots.stream().mapToLong(snapshot -> snapshot.getTick()).max().orElseThrow();

    ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
    when(resolver.resolve(recordingId))
        .thenReturn(
            new ResolvedRecording(fixtureRecording(recordingId, session, context), session));

    TickSnapshotRepository snapshotRepository = mock(TickSnapshotRepository.class);
    when(snapshotRepository.findMinTickBySessionId(anyString())).thenReturn(Optional.of(minTick));
    when(snapshotRepository.findMaxTickBySessionId(anyString())).thenReturn(Optional.of(maxTick));
    when(snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(anyString(), any(), any()))
        .thenAnswer(
            invocation ->
                snapshots.stream()
                    .filter(
                        snapshot ->
                            snapshot.getTick() >= (long) invocation.getArgument(1)
                                && snapshot.getTick() <= (long) invocation.getArgument(2))
                    .toList());

    ReplayActionConversionService conversionService = mock(ReplayActionConversionService.class);
    when(conversionService.convert(anyString(), anyLong(), anyLong(), any(ConversionMode.class)))
        .thenReturn(conversion);

    RecordingCurationRepository curations = mock(RecordingCurationRepository.class);
    when(curations.findByContentChecksum(anyString())).thenReturn(Optional.empty());
    when(curations.findById(any(UUID.class)))
        .thenAnswer(
            invocation ->
                priorCurations.stream()
                    .filter(curation -> curation.getId().equals(invocation.getArgument(0)))
                    .findFirst());
    when(curations.findByRecordingIdOrderByVersionDesc(anyString())).thenReturn(priorCurations);
    when(curations.findByRecordingIdAndCreatedByOrderByVersionDesc(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                priorCurations.stream()
                    .filter(curation -> curation.getCreatedBy().equals(invocation.getArgument(1)))
                    .toList());
    when(curations.saveAndFlush(any(RecordingCuration.class)))
        .thenAnswer(
            invocation -> {
              RecordingCuration saved = invocation.getArgument(0);
              if (savedCuration != null) {
                savedCuration.set(saved);
              }
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
            snapshotRepository,
            conversionService,
            curations,
            curatedActions,
            manifests,
            capabilityService,
            new CanonicalProjectionService(),
            objectMapper);
    Compilation result =
        preview
            ? compiler.preview(recordingId, ConversionMode.AUTO, selectedId, ownerId)
            : compiler.compile(recordingId, ConversionMode.AUTO, selectedId, ownerId);
    if (preview) {
      org.mockito.Mockito.verify(curations, org.mockito.Mockito.never()).saveAndFlush(any());
      org.mockito.Mockito.verify(curatedActions, org.mockito.Mockito.never()).save(any());
      org.mockito.Mockito.verify(manifests, org.mockito.Mockito.never()).saveAndFlush(any());
    }
    return result;
  }

  private TickSnapshot snapshotAt(long tick) {
    TickSnapshot snapshot = goldenSnapshot();
    snapshot.setTick(tick);
    return snapshot;
  }

  private RecordingCuration operatorCuration(String recordingId, int version, CurationRole role) {
    RecordingCuration curation = new RecordingCuration();
    curation.setId(UUID.randomUUID());
    curation.setRecordingId(recordingId);
    curation.setDebugSessionId("session-fishing-golden");
    curation.setVersion(version);
    curation.setState(CurationState.PUBLISHED);
    curation.setRole(role);
    curation.setCreatedBy("operator-review");
    ObjectNode content = objectMapper.createObjectNode();
    content.putObject("tickRange").put("start", 0).put("end", 0);
    content.putObject("initialStateOverrides");
    content.putArray("actionLabels");
    curation.setContentJson(content);
    curation.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(content)));
    return curation;
  }

  private ReplayActionConversionResult gappedConversion() {
    RecordingAction action = npcAction("npc:1518:42", "net", 0);
    return new ReplayActionConversionResult(
        "session-fishing-golden",
        0,
        0,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        List.of(
            new ConvertedReplayAction(
                action, ActionProvenance.CAPTURED, "golden-typed", null, null)),
        List.of(
            new ReplayGap(
                0,
                "TYPED_ACTION_STREAM_INCOMPLETE",
                "legacy evidence retained as gap",
                ActionProvenance.DEFAULTED,
                null)),
        List.of(),
        1,
        1,
        100.0,
        "sha256:golden-conversion-gapped");
  }

  private DebugSession goldenSession() {
    DebugSession session = new DebugSession();
    session.setSessionId("session-fishing-golden");
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

  private ObjectNode goldenContext() {
    ObjectNode context = objectMapper.createObjectNode();
    context.put("schemaVersion", 1);
    context.put("runId", "20ad634d-15df-44b3-8597-44fb018ef703");
    context.put("taskKey", "FISHING_TRAINING");
    context.put("behaviorProfileId", "golden-fishing");
    context.put("behaviorProfileVersion", 1);
    context.put("semanticContractDigest", "sha256:" + "a".repeat(64));
    context.put("settingsHash", "sha256:" + "b".repeat(64));
    context.put("graphRevision", "sha256:" + "c".repeat(64));
    return context;
  }

  private RecordingSession fixtureRecording(
      String recordingId, DebugSession session, ObjectNode context) {
    var recording = new RecordingSession();
    recording.setRecordingId(recordingId);
    if (context != null) {
      ObjectNode bound =
          new com.chromascape.web.recording.provenance.BehaviorRunContextService(
                  null, null, null, null, objectMapper)
              .bind(context, recordingId, session.getSessionId());
      recording.bindRunContext(bound, "CAPTURED");
    }
    return recording;
  }

  private TickSnapshot goldenSnapshot() {
    TickSnapshot snapshot = initialSnapshot();
    snapshot.setSessionId("session-fishing-golden");
    ((ObjectNode) snapshot.getPlayerState()).put("collisionRevision", "golden-collision-rev-1");
    return snapshot;
  }

  private ReplayActionConversionResult goldenConversion() {
    RecordingAction action = npcAction("npc:1518:42", "net", 0);
    return new ReplayActionConversionResult(
        "session-fishing-golden",
        0,
        0,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        List.of(
            new ConvertedReplayAction(
                action, ActionProvenance.CAPTURED, "golden-typed", null, null)),
        List.of(),
        List.of(),
        1,
        1,
        100.0,
        "sha256:golden-conversion");
  }

  private CapabilityManifest goldenCapability() {
    return CapabilityManifest.current(
        new CapabilityManifest.CapabilityModes(true, true, true),
        new CapabilityManifest.XrspsIdentity(
            "1.0.0", "build-golden", "sha256:cache", "sha256:content"),
        false);
  }

  private RecordingAction tickedAction(String stableId, String option, long tick, int sequence) {
    RecordingAction action = npcAction(stableId, option, sequence);
    action.setTick(tick);
    return action;
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
    playerState.put("collisionRevision", "test-collision-rev-1");
    playerState.putObject("position").put("x", 3200).put("y", 3200).put("plane", 0);
    playerState.putObject("skills").putObject("fishing").put("level", 1).put("xp", 0);

    TickSnapshot snapshot =
        new TickSnapshot("session-1", 0L, LocalDateTime.of(2026, 8, 31, 22, 0), playerState);
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
}
