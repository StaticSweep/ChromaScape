package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

class ScenarioManifestImportValidatorTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void simulationRejectsRehashedContextFromAnotherRecordingOrSession() {
    for (String field : List.of("recordingId", "debugSessionId", "schemaVersion")) {
      ObjectNode document = completeManifest();
      ScenarioManifestImportValidator.requireSimulationAllowed(document);
      ObjectNode context =
          (ObjectNode) document.path("compilationEvidence").path("behaviorRunContext");
      if (field.equals("schemaVersion")) context.put(field, 2);
      else context.put(field, "different-source");
      context.remove("contextHash");
      context.put(
          "contextHash",
          com.chromascape.web.integration.micro6.service.Micro6CanonicalHash.sha256WithPrefix(
              context));
      seal(document);
      ScenarioManifestImportValidator.validate(document);
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
    }
  }

  @Test
  void simulationRejectsContextTamperingEvenWithAValidOuterManifestChecksum() {
    ObjectNode document = completeManifest();
    ((ObjectNode) document.path("compilationEvidence").path("behaviorRunContext"))
        .put("taskKey", "tampered-task");
    seal(document);
    ScenarioManifestImportValidator.validate(document);
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  @Test
  void acceptsMinimalValidFishingManifest() {
    JsonNode document =
        ScenarioManifestImportValidator.validate(validManifestBytes(validManifest()));

    assertThat(document.path("manifestVersion").asInt()).isEqualTo(2);
    assertThat(document.path("actions").get(0).path("type").asText()).isEqualTo("INTERACT_NPC");
  }

  @Test
  void compiler240RequiresPolicyAndRehashedPolicyCannotSeverSelectedCurationBinding() {
    ObjectNode document = completeManifest();
    ((ObjectNode) document.path("compiler")).put("version", "2.4.0");
    document.set("execution", ExecutionLimitContract.defaults(objectMapper, 0, 0));
    document.set("termination", TerminationPolicyContract.defaults(objectMapper));
    populateProvenance(document);
    seal(document);
    ScenarioManifestImportValidator.requireSimulationAllowed(document);
    ObjectNode falseProvenance = document.deepCopy();
    ((ObjectNode)
            falseProvenance
                .path("compilationEvidence")
                .path("fieldProvenance")
                .path("/termination/schema"))
        .put("provenance", "OBSERVED");
    seal(falseProvenance);
    assertThrows(
        ReplayException.class, () -> ScenarioManifestImportValidator.validate(falseProvenance));
    ObjectNode absent = document.deepCopy();
    absent.remove("termination");
    seal(absent);
    assertThrows(ReplayException.class, () -> ScenarioManifestImportValidator.validate(absent));
    ObjectNode injected = document.deepCopy();
    ((ObjectNode) injected.path("termination"))
        .withArray("success")
        .addObject()
        .put("id", "invented")
        .putObject("predicate")
        .put("op", "exists")
        .put("pointer", "/tick");
    seal(injected);
    assertThrows(ReplayException.class, () -> ScenarioManifestImportValidator.validate(injected));

    ((ObjectNode) document.path("curation")).put("datasetRole", "HELD_OUT");
    ObjectNode selected = ((ObjectNode) document.path("curation")).putObject("sourceCuration");
    selected
        .put("id", "00000000-0000-0000-0000-000000000001")
        .put("version", 1)
        .put("checksum", "legacy-pin")
        .put("role", document.path("curation").path("datasetRole").asText());
    ObjectNode content = selected.putObject("contentSnapshot");
    content.set("termination", injected.path("termination").deepCopy());
    var canonical = new com.chromascape.web.replay.projection.CanonicalProjectionService();
    selected.put(
        "canonicalContentChecksum", canonical.sha256Hex(canonical.canonicalJsonBytes(content)));
    document.set("termination", content.path("termination").deepCopy());
    populateProvenance(document);
    seal(document);
    ScenarioManifestImportValidator.validate(document);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    ObjectNode dropped = document.deepCopy();
    dropped.remove("termination");
    ((ObjectNode) dropped.path("compiler")).put("version", "2.3.0");
    seal(dropped);
    assertThrows(ReplayException.class, () -> ScenarioManifestImportValidator.validate(dropped));
    ((ObjectNode) document.path("termination").path("success").get(0)).put("id", "rewritten");
    seal(document);
    assertThrows(ReplayException.class, () -> ScenarioManifestImportValidator.validate(document));
  }

  @Test
  void limitsAreOptionalForLegacyImportsButRequiredForCompiler230() {
    ObjectNode legacy = validManifest();
    ScenarioManifestImportValidator.validate(validManifestBytes(legacy));
    ObjectNode current = completeManifest();
    ((ObjectNode) current.path("compiler")).put("version", "2.3.0");
    current.set("execution", ExecutionLimitContract.defaults(objectMapper, 0, 0));
    populateProvenance(current);
    seal(current);
    ScenarioManifestImportValidator.validate(validManifestBytes(current));
    ObjectNode original = current.deepCopy();
    ScenarioManifestImportValidator.validate(current);
    assertThat(current).isEqualTo(original);
    current.remove("execution");
    seal(current);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.validate(validManifestBytes(current)));
  }

  @Test
  void rechecksummedImportsCannotRelaxClosedOrRecordingRelativeExecutionLimits() {
    for (java.util.function.Consumer<ObjectNode> mutate :
        List.<java.util.function.Consumer<ObjectNode>>of(
            limits -> limits.put("schema", "remote:policy"),
            limits -> limits.put("maximumTicks", 5),
            limits -> limits.put("maximumTicks", -1),
            limits -> limits.put("maximumTicks", 1.5),
            limits -> limits.put("maximumActions", 0),
            limits -> limits.put("maximumActions", 10_001),
            limits -> limits.put("maximumSteps", 100_001),
            limits -> limits.put("maximumSteps", "1"),
            limits -> limits.remove("maximumSteps"),
            limits -> limits.put("callback", "untrusted"))) {
      ObjectNode document = validManifest();
      ((ObjectNode) document.path("recording")).put("startTick", 20).put("endTick", 24);
      document.set("execution", ExecutionLimitContract.defaults(objectMapper, 20, 24));
      mutate.accept((ObjectNode) document.path("execution"));
      seal(document);
      ObjectNode before = document.deepCopy();
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () -> ScenarioManifestImportValidator.validate(validManifestBytes(document)));
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
      assertThat(document).isEqualTo(before);
    }
  }

  @Test
  void nativeBankAndTravelAreTypedAtImportEvenWithAValidOuterChecksum() throws Exception {
    ObjectNode manifest = validManifest();
    ObjectNode initial = (ObjectNode) manifest.path("initialState");
    initial.put("bankOpen", false).put("travelProfile", "jewelry-v1");
    initial.set(
        "bank",
        objectMapper.readTree(
            """
        {"capacity":800,"slots":[{"slot":0,"itemId":303,"quantity":2,
        "placeholder":false,"filler":false,"tab":0}],"withdrawNotes":false,
        "insertMode":false,"placeholderMode":false,"quantityMode":0,
        "customQuantity":0,"currentTab":0,"tabDisplayMode":0}
        """));
    seal(manifest);
    ScenarioManifestImportValidator.validate(validManifestBytes(manifest));
    for (java.util.function.Consumer<ObjectNode> mutation :
        List.<java.util.function.Consumer<ObjectNode>>of(
            state -> ((ObjectNode) state.path("bank")).remove("customQuantity"),
            state -> ((ObjectNode) state.path("bank")).put("unknownScript", "untrusted"),
            state -> ((ObjectNode) state.path("bank")).put("capacity", 1411),
            state -> ((ObjectNode) state.path("bank")).put("withdrawNotes", "false"),
            state -> ((ObjectNode) state.path("bank").path("slots").get(0)).put("quantity", 0),
            state ->
                ((com.fasterxml.jackson.databind.node.ArrayNode) state.path("bank").path("slots"))
                    .add(state.path("bank").path("slots").get(0).deepCopy()),
            state -> state.remove("objects"),
            state -> state.remove("bank"),
            state -> state.put("bankOpen", 1),
            state -> state.put("travelProfile", "generic"),
            state -> state.put("questProfile", "rune_mysteries"))) {
      ObjectNode bad = manifest.deepCopy();
      mutation.accept((ObjectNode) bad.path("initialState"));
      seal(bad);
      assertThrows(
          ReplayException.class,
          () -> ScenarioManifestImportValidator.validate(validManifestBytes(bad)));
    }
    // Schema validation and cross-field checks are read-only, including failed imports.
    ObjectNode original = manifest.deepCopy();
    ScenarioManifestImportValidator.validate(validManifestBytes(manifest));
    assertThat(manifest).isEqualTo(original);
  }

  @Test
  void nativeItemCraftingBankAndExchangeInputsMatchRuntimeContract() throws Exception {
    for (String action :
        List.of(
            "{\"type\":\"INTERACT_OBJECT\",\"targetStableId\":\"object:1\",\"option\":\"use\",\"sourceItem\":{\"slot\":2,\"itemId\":952}}",
            "{\"type\":\"INTERACT_INVENTORY_ITEM\",\"targetStableId\":\"inventory:1:1775\",\"slot\":1,\"itemId\":1775,\"option\":\"use\",\"sourceSlot\":0,\"sourceItemId\":1785}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"widget:17694740:2\",\"option\":\"make\",\"widgetId\":17694740,\"widgetIndex\":2,\"productId\":567,\"quantity\":2}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"container:bank:1:314\",\"option\":\"withdraw-x\",\"input\":{\"kind\":\"quantity\",\"value\":18}}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"exchange:slot:0\",\"option\":\"confirm\",\"input\":{\"kind\":\"exchange-offer\",\"side\":\"buy\",\"itemId\":303,\"quantity\":1,\"price\":100}}",
            "{\"type\":\"CAST_SPELL\",\"targetStableId\":\"inventory:3:1205\",\"spellId\":9111,\"slot\":3,\"itemId\":1205}")) {
      ObjectNode manifest = validManifest();
      ObjectNode value = (ObjectNode) objectMapper.readTree(action);
      value.put("mappingVersion", "1.1.0");
      manifest.putArray("actions").add(value);
      seal(manifest);
      ScenarioManifestImportValidator.validate(validManifestBytes(manifest));
    }
  }

  @Test
  void rejectsIncompatibleMappingIncompletePairsMixedInputsAndOverflowBeforeExecution()
      throws Exception {
    for (String action :
        List.of(
            "{\"type\":\"INTERACT_NPC\",\"targetStableId\":\"npc:1\",\"option\":\"net\",\"mappingVersion\":\"1.1.1\"}",
            "{\"type\":\"INTERACT_INVENTORY_ITEM\",\"targetStableId\":\"inventory:1\",\"slot\":1,\"itemId\":1775,\"option\":\"use\",\"sourceSlot\":0}",
            "{\"type\":\"INTERACT_INVENTORY_ITEM\",\"targetStableId\":\"inventory:1\",\"slot\":1,\"itemId\":1775,\"option\":\"use\",\"sourceSlot\":28,\"sourceItemId\":1785}",
            "{\"type\":\"INTERACT_OBJECT\",\"targetStableId\":\"object:1\",\"option\":\"use\",\"sourceItem\":{\"slot\":1}}",
            "{\"type\":\"CAST_SPELL\",\"targetStableId\":\"inventory:1\",\"spellId\":9111,\"itemId\":1205}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"widget:1\",\"option\":\"make\",\"productId\":567}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"widget:1\",\"option\":\"make\",\"productId\":567,\"quantity\":29}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"widget:1\",\"option\":\"make\",\"productId\":567,\"quantity\":2,\"input\":{\"kind\":\"quantity\",\"value\":2}}",
            "{\"type\":\"INTERACT_NPC\",\"targetStableId\":\"npc:1\",\"option\":\"net\",\"input\":{\"kind\":\"quantity\",\"value\":2}}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"exchange:slot:0\",\"option\":\"confirm\",\"input\":{\"kind\":\"exchange-offer\",\"side\":\"buy\",\"itemId\":303,\"quantity\":2147483647,\"price\":2}}",
            "{\"type\":\"INTERACT_WIDGET\",\"targetStableId\":\"widget:1\",\"option\":\"confirm\",\"input\":{\"kind\":\"quantity\",\"value\":2,\"script\":\"no\"}}")) {
      ObjectNode manifest = validManifest();
      manifest.putArray("actions").add(objectMapper.readTree(action));
      seal(manifest);
      assertThrows(
          ReplayException.class,
          () -> ScenarioManifestImportValidator.validate(validManifestBytes(manifest)),
          action);
    }
  }

  @Test
  void selectedCurationRoleAndCanonicalContentCannotDriftAfterImport() {
    ObjectNode manifest = validManifest();
    ObjectNode curation = (ObjectNode) manifest.path("curation");
    curation.put("datasetRole", "HELD_OUT");
    ObjectNode source =
        curation
            .putObject("sourceCuration")
            .put("id", "fixture-selected")
            .put("version", 1)
            .put("checksum", "legacy-address")
            .put("role", "HELD_OUT");
    ObjectNode snapshot = source.putObject("contentSnapshot").put("fixture", "authored-test-only");
    var canonical = new com.chromascape.web.replay.projection.CanonicalProjectionService();
    source.put(
        "canonicalContentChecksum", canonical.sha256Hex(canonical.canonicalJsonBytes(snapshot)));
    seal(manifest);
    ScenarioManifestImportValidator.validate(validManifestBytes(manifest));
    source.put("role", "TRAIN");
    seal(manifest);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.validate(validManifestBytes(manifest)));
    source.put("role", "HELD_OUT");
    snapshot.put("changed", true);
    seal(manifest);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.validate(validManifestBytes(manifest)));
  }

  @Test
  void rejectsOversizeManifest() {
    byte[] oversize = new byte[ScenarioManifestImportValidator.MAX_MANIFEST_BYTES + 1];

    ReplayException exception =
        assertThrows(
            ReplayException.class, () -> ScenarioManifestImportValidator.validate(oversize));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void rejectsEmptyManifest() {
    ReplayException exception =
        assertThrows(
            ReplayException.class, () -> ScenarioManifestImportValidator.validate(new byte[0]));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
  }

  @Test
  void rejectsMalformedJson() {
    byte[] content = "{not json".getBytes(StandardCharsets.UTF_8);

    ReplayException exception =
        assertThrows(
            ReplayException.class, () -> ScenarioManifestImportValidator.validate(content));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
  }

  @Test
  void rejectsSchemaViolations() {
    ObjectNode manifest = validManifest();
    manifest.remove("manifestId");

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.validate(validManifestBytes(manifest)));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
  }

  @Test
  void rejectsCodeAndNetworkRouteFields() {
    // The closed contract admits no executable or remote-loading surface: unknown fields such
    // as code entry points or endpoint URLs are rejected at the schema boundary, never loaded.
    ObjectNode manifest = validManifest();
    manifest.put("code", "run()");
    manifest.put("scriptUrl", "https://example.invalid/payload.js");
    manifest.put("endpoint", "https://example.invalid/hook");
    ((ObjectNode) manifest.path("actions").get(0)).put("entrypoint", "https://example.invalid/x");

    ReplayException exception =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.validate(validManifestBytes(manifest)));

    assertThat(exception.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
  }

  @Test
  void schemaResolvesNoRemoteReferences() throws Exception {
    // Static guarantee behind the "loads no network routes" criterion: every $ref in the
    // checked-in contract is a local #/$defs/… pointer, so validation never fetches a URL.
    JsonNode schema;
    try (var input =
        new ClassPathResource(ScenarioManifestImportValidator.RESOURCE).getInputStream()) {
      schema = objectMapper.readTree(input);
    }

    List<String> refs = new ArrayList<>();
    collectRefs(schema, refs);

    assertThat(refs).isNotEmpty();
    assertThat(refs).allMatch(ref -> ref.startsWith("#"));
  }

  @Test
  void rejectsTrailingJsonAndDuplicateKeys() {
    byte[] original = validManifestBytes(validManifest());
    String source = new String(original, StandardCharsets.UTF_8);
    for (String input :
        List.of(
            source + " {}",
            source.replace(
                "\"manifestVersion\":2", "\"manifestVersion\":2,\"manifestVersion\":2"))) {
      assertThrows(
          ReplayException.class,
          () -> ScenarioManifestImportValidator.validate(input.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  void treeEntryPointCannotBypassByteBound() {
    ObjectNode document = validManifest();
    ((ObjectNode) document.path("initialState")).put("capturedText", "x".repeat(300_000));
    ReplayException error =
        assertThrows(
            ReplayException.class, () -> ScenarioManifestImportValidator.validate(document));
    assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void rejectsDeeplyNestedInput() {
    String nested = "[".repeat(70) + "0" + "]".repeat(70);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.validate(nested.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void sourceGapsAndNativeRequirementsCannotBeOverriddenAtExecutionBoundary() {
    ObjectNode document = completeManifest();
    ScenarioManifestImportValidator.requireSimulationAllowed(document);
    ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("conversion").path("gaps"))
        .addObject()
        .put("tick", 0)
        .put("code", "TYPED_ACTION_STREAM_INCOMPLETE")
        .put("message", "missing dispatch")
        .put("provenance", "OBSERVED");
    seal(document);
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
    assertThat(ScenarioManifestImportValidator.validate(validManifestBytes(document))).isNotNull();

    ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("conversion").path("gaps"))
        .removeAll();
    ObjectNode world = (ObjectNode) document.path("compilationEvidence").path("world");
    world.putArray("requiredNativeBindings").addObject().put("stableId", "later-spot");
    populateProvenance(document);
    seal(document);
    ReplayException nativeError =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(nativeError.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  @Test
  void missingProvenanceOrInvalidFrozenContextPreventsExecution() {
    ObjectNode document = completeManifest();
    ((ObjectNode) document.path("compilationEvidence").path("fieldProvenance"))
        .remove("/actions/0/option");
    seal(document);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    populateProvenance(document);
    ((ObjectNode) document.path("compilationEvidence").path("behaviorRunContext"))
        .put("settingsHash", "not-a-pin");
    seal(document);
    assertThrows(
        ReplayException.class,
        () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
  }

  @Test
  @SuppressWarnings("unchecked")
  void durableSessionChecksDocumentBeforeOpeningWorkerEvenIfDatabaseFlagsSayComplete() {
    var repository =
        org.mockito.Mockito.mock(com.chromascape.web.replay.session.XrspsSessionRepository.class);
    var manifests =
        org.mockito.Mockito.mock(
            com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository.class);
    var workers =
        org.mockito.Mockito.mock(com.chromascape.web.replay.session.XrspsSessionWorker.class);
    var source = new com.chromascape.web.replay.manifest.entity.ScenarioManifest();
    source.setId(java.util.UUID.randomUUID());
    source.setManifestVersion(2);
    source.setPublishState("PUBLISHED");
    source.setCompleteness("COMPLETE");
    source.setGapCount(0);
    source.setFidelity("XRSPS_AUTHORITATIVE");
    ObjectNode document = completeManifest();
    ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("conversion").path("gaps"))
        .addObject()
        .put("tick", 0)
        .put("code", "CAPTURE_INCOMPLETE")
        .put("message", "missing source action")
        .put("provenance", "OBSERVED");
    populateProvenance(document);
    seal(document);
    source.setManifestJson(document);
    org.mockito.Mockito.when(manifests.findById(source.getId()))
        .thenReturn(java.util.Optional.of(source));
    org.mockito.Mockito.when(
            repository.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              java.util.function.Function<
                      com.chromascape.web.replay.session.XrspsSession,
                      com.chromascape.web.replay.session.XrspsSessionDocument>
                  initialize = invocation.getArgument(4);
              initialize.apply(
                  new com.chromascape.web.replay.session.XrspsSession(
                      java.util.UUID.randomUUID(),
                      com.chromascape.web.replay.session.XrspsSessionMode.RECORDED_ACTIONS,
                      "gate-test",
                      source.getId().toString(),
                      "alice"));
              throw new AssertionError("incomplete scenario was accepted");
            });
    var service =
        new com.chromascape.web.replay.session.XrspsDurableSessionService(
            repository,
            manifests,
            workers,
            objectMapper,
            new com.chromascape.web.replay.projection.CanonicalProjectionService(),
            org.mockito.Mockito.mock(
                com.chromascape.web.replay.security.ReplayOwnershipService.class));
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () ->
                service.create(
                    com.chromascape.web.replay.session.XrspsSessionMode.RECORDED_ACTIONS,
                    "gate-test",
                    source.getId().toString(),
                    "alice"));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
    org.mockito.Mockito.verifyNoInteractions(workers);
  }

  @Test
  void contentMutationInvalidatesChecksum() {
    ObjectNode document = validManifest();
    ((ObjectNode) document.path("determinism")).put("seed", "altered");
    ReplayException error =
        assertThrows(
            ReplayException.class, () -> ScenarioManifestImportValidator.validate(document));
    assertThat(error.getMessage()).contains("checksum");
  }

  private ObjectNode nativeManifest() {
    ObjectNode document = completeManifest();
    ObjectNode state = (ObjectNode) document.path("initialState");
    state
        .putObject("nativeFishingWorld")
        .put("schema", "xrsps-native-fishing-world/v1")
        .put("catalogChecksum", "sha256:" + "a".repeat(64))
        .putArray("spawnIndices")
        .add(7);
    ObjectNode row =
        state
            .putObject("nativeNpcBindings")
            .put("schema", "xrsps-native-npc-bindings/v1")
            .put("catalogChecksum", "sha256:" + "a".repeat(64))
            .putArray("bindings")
            .addObject()
            .put("actionIndex", 0)
            .put("tick", 0)
            .put("sequence", 0)
            .put("sourceStableId", "npc:1518:42")
            .put("spawnIndex", 7)
            .put("npcTypeId", 1530)
            .put("sourceSnapshotRef", "snapshot:sha256:" + "b".repeat(64));
    row.putObject("position").put("x", 3081).put("y", 3227).put("plane", 0);
    ((ObjectNode) document.path("actions").get(0))
        .put("sourceActionTick", 0)
        .put("sourceActionSequence", 0);
    ((ObjectNode) document.path("compilationEvidence").path("world"))
        .putArray("actionTargets")
        .addObject()
        .put("kind", "NPC")
        .put("tick", 0)
        .put("sequence", 0)
        .put("stableId", "npc:1518:42")
        .put("sourceRef", "snapshot:sha256:" + "b".repeat(64) + "/action:0");
    populateProvenance(document);
    seal(document);
    return document;
  }

  @Test
  void nativeBindingsRemainInertReadableInputsNotSimulationPermissionEvenIfGapsAreResealedAway() {
    ObjectNode document = nativeManifest();
    assertThat(ScenarioManifestImportValidator.validate(validManifestBytes(document)))
        .isEqualTo(document);
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  @Test
  void nativeBindingSchemaAndCrossReferencesCannotHideBehindAdditionalInitialStateFields() {
    List<java.util.function.Consumer<ObjectNode>> changes =
        List.of(
            d ->
                ((ObjectNode) d.path("initialState").path("nativeFishingWorld"))
                    .put("endpoint", "https://example.invalid"),
            d ->
                ((ObjectNode) d.path("initialState").path("nativeNpcBindings"))
                    .put("aliases", "unchecked"),
            d ->
                ((ObjectNode)
                        d.path("initialState").path("nativeNpcBindings").path("bindings").get(0))
                    .put("actionIndex", 1),
            d ->
                ((ObjectNode)
                        d.path("compilationEvidence").path("world").path("actionTargets").get(0))
                    .put("tick", "0"),
            d -> ((ObjectNode) d.path("initialState")).remove("nativeFishingWorld"),
            d -> ((ObjectNode) d.path("initialState")).put("nativeNpcBindings", "not-an-object"));
    for (var change : changes) {
      ObjectNode document = nativeManifest();
      change.accept(document);
      populateProvenance(document);
      seal(document);
      assertThrows(
          ReplayException.class,
          () -> ScenarioManifestImportValidator.validate(validManifestBytes(document)));
    }
  }

  @Test
  void legacyImportsRemainReadableButRequireRecompilationBeforeSimulation() {
    ObjectNode document = validManifest();
    ScenarioManifestImportValidator.validate(document);
    ReplayException error =
        assertThrows(
            ReplayException.class,
            () -> ScenarioManifestImportValidator.requireSimulationAllowed(document));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
  }

  private void collectRefs(JsonNode node, List<String> refs) {
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if ("$ref".equals(field.getKey())) {
          refs.add(field.getValue().asText());
        } else {
          collectRefs(field.getValue(), refs);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectRefs(child, refs);
      }
    }
  }

  private byte[] validManifestBytes(ObjectNode manifest) {
    try {
      return objectMapper.writeValueAsBytes(manifest);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ObjectNode validManifest() {
    ObjectNode manifest = objectMapper.createObjectNode();
    manifest.put("manifestVersion", 2);
    manifest.put("manifestId", "a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    ObjectNode recording = manifest.putObject("recording");
    recording.put("recordingId", "recording-fishing-golden");
    recording.put("recordingChecksum", "sha256:recording");
    recording.put("debugSessionId", "session-fishing-golden");
    recording.put("debugSessionChecksum", "sha256:session");
    recording.put("startTick", 0);
    recording.put("endTick", 0);

    ObjectNode curation = manifest.putObject("curation");
    curation.put("curationId", "curation-fishing-golden");
    curation.put("version", 1);
    curation.put("checksum", "sha256:curation");

    ObjectNode compiler = manifest.putObject("compiler");
    compiler.put("version", "2.0.0");
    compiler.put("schemaVersion", 2);

    ObjectNode runtime = manifest.putObject("runtime");
    runtime.put("xrspsAdapterVersion", "1.0.0");
    runtime.put("xrspsBuildCommit", "build-golden");
    runtime.put("cacheRevision", "sha256:cache");
    runtime.put("contentRevision", "sha256:content");

    ObjectNode determinism = manifest.putObject("determinism");
    determinism.put("seed", "0123456789abcdef");
    determinism.put("clockOrigin", "2020-01-01T00:00:00Z");
    determinism.put("tickDurationMs", 600);
    determinism.put("idNamespace", "replay-v2");

    ObjectNode initialState = manifest.putObject("initialState");
    initialState.put("worldId", 301);
    ObjectNode player = initialState.putObject("player");
    player.putObject("position").put("x", 3200).put("y", 3200).put("plane", 0);
    player.putArray("inventory");
    player.putArray("equipment");
    initialState.putArray("npcs");
    initialState.putArray("objects");
    initialState.putArray("groundItems");

    ObjectNode action = manifest.putArray("actions").addObject();
    action.put("type", "INTERACT_NPC");
    action.put("targetStableId", "npc:1518:42");
    action.put("option", "net");
    action.put("mappingVersion", "1.0.0");

    manifest.putObject("expectations");
    manifest.putObject("tolerances");

    ObjectNode conversion = manifest.putObject("conversion");
    conversion.put("mode", "AUTO");
    conversion.put("quality", "BEST_EFFORT");
    conversion.put("conversionChecksum", "sha256:conversion");
    conversion.put("typedActionCount", 1);
    conversion.put("legacyLogCount", 0);
    conversion.put("actionCoveragePct", 100.0);
    conversion.putArray("gaps");
    conversion.putArray("approximations");

    manifest.put("fidelity", "SYNTHETIC_CONTRACT");
    manifest.put("fidelityRequirement", "BEST_EFFORT");
    manifest.put("controlAllowed", false);
    seal(manifest);
    return manifest;
  }

  private void seal(ObjectNode manifest) {
    manifest.remove("manifestChecksum");
    var canonical = new com.chromascape.web.replay.projection.CanonicalProjectionService();
    manifest.put("manifestChecksum", canonical.sha256Hex(canonical.canonicalJsonBytes(manifest)));
  }

  private ObjectNode completeManifest() {
    ObjectNode document = validManifest();
    ObjectNode audit = document.putObject("compilationEvidence");
    ObjectNode context = audit.putObject("behaviorRunContext");
    context
        .put("schemaVersion", 1)
        .put("runId", "fixture-run")
        .put("taskKey", "fixture-fishing")
        .put("behaviorProfileId", "fixture-profile")
        .put("behaviorProfileVersion", 1)
        .put("semanticContractDigest", "sha256:" + "a".repeat(64))
        .put("settingsHash", "sha256:" + "b".repeat(64))
        .put("graphRevision", "sha256:" + "c".repeat(64))
        .put("recordingId", document.path("recording").path("recordingId").asText())
        .put("debugSessionId", document.path("recording").path("debugSessionId").asText());
    context.put(
        "contextHash",
        com.chromascape.web.integration.micro6.service.Micro6CanonicalHash.sha256WithPrefix(
            context));
    ObjectNode world = audit.putObject("world");
    world.put("initialSnapshotRef", "fixture:initial-snapshot");
    world.putObject("staticContent");
    world.putArray("actionTargets");
    world.putArray("requiredNativeBindings");
    populateProvenance(document);
    seal(document);
    return document;
  }

  private void populateProvenance(ObjectNode document) {
    ObjectNode fields =
        ((ObjectNode) document.path("compilationEvidence")).putObject("fieldProvenance");
    indexFields(document, "", fields);
    if (document.has("termination")
        && "2.4.0".equals(document.path("compiler").path("version").asText())) {
      JsonNode selected = document.path("curation").path("sourceCuration");
      boolean supplied = selected.path("contentSnapshot").has("termination");
      for (var entries = fields.fields(); entries.hasNext(); ) {
        var entry = entries.next();
        if (entry.getKey().equals("/termination") || entry.getKey().startsWith("/termination/")) {
          ((ObjectNode) entry.getValue())
              .put("provenance", supplied ? "OPERATOR_SUPPLIED" : "DEFAULTED")
              .put(
                  "sourceRef",
                  supplied
                      ? "curation:" + selected.path("id").asText() + "/content" + entry.getKey()
                      : "compiler:2.4.0/termination-policy/v1");
        }
      }
    }
  }

  private void indexFields(JsonNode node, String pointer, ObjectNode index) {
    if (pointer.equals("/compilationEvidence/fieldProvenance")) return;
    index
        .putObject(pointer)
        .put("outputPointer", pointer)
        .put("provenance", "OPERATOR_SUPPLIED")
        .put("sourceRef", "test-fixture:ScenarioManifestImportValidatorTest");
    if (node.isObject())
      node.fields()
          .forEachRemaining(
              field ->
                  indexFields(
                      field.getValue(),
                      pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
                      index));
    if (node.isArray())
      for (int i = 0; i < node.size(); i++) indexFields(node.get(i), pointer + "/" + i, index);
  }
}
