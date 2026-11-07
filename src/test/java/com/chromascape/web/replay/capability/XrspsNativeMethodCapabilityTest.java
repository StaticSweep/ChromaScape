package com.chromascape.web.replay.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Method requirements are transported exactly; they never waive identity or source-audit gates. */
class XrspsNativeMethodCapabilityTest {
  private final ObjectMapper json = new ObjectMapper();
  private final JsonNodeFactory nodes = JsonNodeFactory.instance;
  private final XrspsStartupValidator validator =
      new XrspsStartupValidator(new ReplayProperties(), new ReplayCapabilityService(), json);

  private ObjectNode document() throws Exception {
    ObjectNode result =
        (ObjectNode)
            json.readTree(
                """
                {"schemaVersion":1,"controlAllowed":false,"openRuneAvailable":false,
                 "certifiable":true,"fidelity":"XRSPS_AUTHORITATIVE",
                 "modes":{"observedPlayback":false,"policyReplay":false,"causalSimulation":true},
                 "scenarioSchemaVersions":[1,2],"semanticActionVersions":[1],
                 "snapshotVersions":[1],"projectionVersions":[1],
                 "hashVersions":["sha256-canonical-json-v1"],
                 "viewerTraceSchema":"xrsps-replay-trace/v1",
                 "supportedActions":["INTERACT_NPC","INTERACT_OBJECT","INTERACT_INVENTORY_ITEM",
                  "SELECT_DIALOGUE_OPTION","WALK_TO","INTERACT_WIDGET","USE_ITEM_ON_NPC","CAST_SPELL"],
                 "supportedMethods":[
                  {"method":"bank:containers-v1","requiredInitialStateFields":["bank","objects"],
                   "actions":["INTERACT_OBJECT","INTERACT_WIDGET","WALK_TO"]},
                  {"method":"travel:jewelry-v1","requiredProfile":"jewelry-v1",
                   "requiredInitialStateFields":["travelProfile","player.inventory"],
                   "actions":["INTERACT_INVENTORY_ITEM","SELECT_DIALOGUE_OPTION","INTERACT_WIDGET"]},
                  {"method":"npc:minnow-exchange","requiredNpcTypeIds":[7727,7728],"option":"exchange",
                   "actions":["INTERACT_NPC"]},
                  {"method":"quest:rune_mysteries","requiredProfile":"rune_mysteries",
                   "actions":["USE_ITEM_ON_NPC","SELECT_DIALOGUE_OPTION","WALK_TO"]}
                 ]}
                """);
    result
        .putObject("xrsps")
        .put("adapterVersion", "0.1.0")
        .put("buildCommit", "a".repeat(40))
        .put("cacheRevision", "sha256:" + "b".repeat(64))
        .put("contentRevision", "sha256:" + "c".repeat(64));
    return result;
  }

  @Test
  void preservesEveryMethodRequirementThroughHandshakeCacheAndJson() throws Exception {
    ObjectNode input = document();
    assertThat(validator.validateRawDocument(input)).isEmpty();
    CapabilityManifest parsed = validator.parseManifest(input);
    assertThat(validator.verifyVersions(parsed, new ReplayProperties().getXrsps())).isEmpty();
    ReplayCapabilityService service = new ReplayCapabilityService();
    service.updateFromHandshake(parsed);
    assertThat(service.getManifest().supportedMethods()).hasSize(4);
    ReplayMethodCapability travel = parsed.supportedMethods().get(1);
    assertThat(travel.requiredProfile()).isEqualTo("jewelry-v1");
    assertThat(travel.requiredInitialStateFields())
        .containsExactly("travelProfile", "player.inventory");
    assertThat(travel.actions())
        .containsExactly("INTERACT_INVENTORY_ITEM", "SELECT_DIALOGUE_OPTION", "INTERACT_WIDGET");
    assertThat(parsed.supportedMethods().get(2).requiredNpcTypeIds()).containsExactly(7727, 7728);
    assertThat(parsed.supportedMethods().get(2).option()).isEqualTo("exchange");
    assertThat(validator.parseManifest(json.valueToTree(parsed))).isEqualTo(parsed);
    input.withArray("supportedMethods").removeAll();
    assertThat(parsed.supportedMethods()).hasSize(4);
    assertThatThrownBy(() -> parsed.supportedMethods().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> travel.actions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void oldWorkerWithoutMethodsCannotAcquireInferredMethodCoverage() throws Exception {
    ObjectNode input = document();
    input.remove("supportedMethods");
    assertThat(validator.validateRawDocument(input)).isEmpty();
    assertThat(validator.parseManifest(input).supportedMethods()).isEmpty();
    CapabilityManifest oldCaller =
        CapabilityManifest.current(
            new CapabilityManifest.CapabilityModes(false, false, true),
            validator.parseManifest(input).xrsps(),
            true);
    assertThat(oldCaller.supportedMethods()).isEmpty();
    assertThat(oldCaller.supportedActions()).doesNotContain("USE_ITEM_ON_NPC");
    assertThat(CapabilityManifest.isSupportedAction("USE_ITEM_ON_NPC")).isTrue();
    assertThat(CapabilityManifest.isSupportedAction("EXECUTE_CODE")).isFalse();
  }

  @Test
  void nullWrongTypeOversizedAndDuplicateMethodDeclarationsFailClosed() throws Exception {
    ObjectNode input = document();
    for (JsonNode malformed :
        List.of(nodes.nullNode(), json.createObjectNode(), nodes.textNode("all"))) {
      ObjectNode copy = input.deepCopy();
      copy.set("supportedMethods", malformed);
      assertInvalid(copy);
    }
    ObjectNode duplicate = input.deepCopy();
    duplicate
        .withArray("supportedMethods")
        .add(duplicate.path("supportedMethods").get(0).deepCopy());
    assertInvalid(duplicate);
    ObjectNode oversized = input.deepCopy();
    ArrayNode array = oversized.putArray("supportedMethods");
    for (int index = 0; index < 65; index++) {
      array.addObject().put("method", "method:" + index).putArray("actions").add("INTERACT_NPC");
    }
    assertInvalid(oversized);
    ObjectNode notObject = input.deepCopy();
    notObject.withArray("supportedMethods").add("bank");
    assertInvalid(notObject);
  }

  @Test
  void methodFieldsAreTypedBoundedClosedAndNeverCoerced() throws Exception {
    for (String field : List.of("method", "requiredProfile", "option")) {
      for (JsonNode bad :
          List.of(
              nodes.nullNode(),
              nodes.numberNode(1),
              nodes.textNode(""),
              nodes.textNode(" padded "),
              nodes.textNode("x".repeat(129)),
              nodes.textNode("a\nb"))) {
        ObjectNode input = document();
        ((ObjectNode) input.path("supportedMethods").get(0)).set(field, bad);
        assertInvalid(input);
      }
    }
    ObjectNode missing = document();
    ((ObjectNode) missing.path("supportedMethods").get(0)).remove("method");
    assertInvalid(missing);
    ObjectNode code = document();
    ((ObjectNode) code.path("supportedMethods").get(0)).put("modulePath", "file:///unsafe");
    assertInvalid(code);
  }

  @Test
  void methodActionsMustBeNonemptyUniqueKnownAndAdvertisedBySameWorker() throws Exception {
    for (JsonNode bad :
        List.of(
            nodes.nullNode(),
            nodes.textNode("INTERACT_NPC"),
            json.createArrayNode(),
            json.createArrayNode().add("INTERACT_NPC").add("INTERACT_NPC"),
            json.createArrayNode().add("EXECUTE_CODE"),
            json.createArrayNode().add(1),
            json.createArrayNode().add("EQUIP_ITEM"))) {
      ObjectNode input = document();
      ((ObjectNode) input.path("supportedMethods").get(0)).set("actions", bad);
      assertInvalid(input);
    }
    ObjectNode missing = document();
    ((ObjectNode) missing.path("supportedMethods").get(0)).remove("actions");
    assertInvalid(missing);
  }

  @Test
  void initialFieldRequirementsAreInertUniqueBoundedNames() throws Exception {
    for (JsonNode bad :
        List.of(
            nodes.nullNode(),
            nodes.textNode("bank"),
            json.createArrayNode().add(1),
            json.createArrayNode().add("bank").add("bank"),
            json.createArrayNode().add("../bank"),
            json.createArrayNode().add("https://example.invalid"),
            json.createArrayNode().add("player[0]"))) {
      ObjectNode input = document();
      ((ObjectNode) input.path("supportedMethods").get(0)).set("requiredInitialStateFields", bad);
      assertInvalid(input);
    }
    ObjectNode input = document();
    ArrayNode fields =
        ((ObjectNode) input.path("supportedMethods").get(0)).putArray("requiredInitialStateFields");
    for (int index = 0; index < 65; index++) fields.add("field" + index);
    assertInvalid(input);
  }

  @Test
  void npcRequirementsAreExactBoundedUniqueIntegers() throws Exception {
    for (JsonNode bad :
        List.of(
            nodes.nullNode(),
            nodes.numberNode(7727),
            json.createArrayNode().add("7727"),
            json.createArrayNode().add(7727.0),
            json.createArrayNode().add(0),
            json.createArrayNode().add(65535),
            json.createArrayNode().add(4294975023L),
            json.createArrayNode().add(7727).add(7727))) {
      ObjectNode input = document();
      ((ObjectNode) input.path("supportedMethods").get(0)).set("requiredNpcTypeIds", bad);
      assertInvalid(input);
    }
    ObjectNode input = document();
    ArrayNode types =
        ((ObjectNode) input.path("supportedMethods").get(0)).putArray("requiredNpcTypeIds");
    for (int index = 1; index <= 65; index++) types.add(index);
    assertInvalid(input);
  }

  @Test
  void inProcessConstructionCannotBypassMethodValidationOrMutateRequirements() {
    List<String> actions = new ArrayList<>(List.of("INTERACT_NPC"));
    List<String> fields = new ArrayList<>(List.of("npcs"));
    ReplayMethodCapability method =
        new ReplayMethodCapability("fishing", null, fields, null, "net", actions);
    fields.clear();
    actions.clear();
    assertThat(method.requiredInitialStateFields()).containsExactly("npcs");
    assertThat(method.actions()).containsExactly("INTERACT_NPC");
    assertThatThrownBy(
            () -> ReplayMethodCapability.validateDeclarations(List.of(method), List.of("WALK_TO")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ReplayMethodCapability.validateDeclarations(
                    List.of(method, method), List.of("INTERACT_NPC")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReplayMethodCapability(
                    "fishing", null, null, List.of(0), null, List.of("INTERACT_NPC")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ReplayMethodCapability(
                    "fishing", null, null, null, null, List.of("EXECUTE_CODE")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void actualNativeDeclarationsRemainNoncertifyingWhenBuildIsDirty() throws Exception {
    // Optional exact external input makes the same non-skipping contract test usable against the
    // fresh native --capabilities output. No identity rewriting is permitted on that path.
    String nativeJson = System.getenv("SC274_NATIVE_CAPABILITY_JSON");
    ObjectNode input;
    if (nativeJson == null) {
      input = document();
      input.put("certifiable", false).put("fidelity", "SYNTHETIC_CONTRACT");
    } else {
      input = (ObjectNode) json.readTree(nativeJson);
      assertThat(input.path("certifiable").asBoolean()).isFalse();
    }
    assertThat(validator.validateRawDocument(input))
        .containsExactly("certifiable=false is forbidden; expected true");
    CapabilityManifest parsed = validator.parseManifest(input);
    assertThat(parsed.supportedMethods()).isNotEmpty();
    assertThat(parsed.supportedMethods()).isEqualTo(ReplayMethodCapability.parse(input));
    assertThat(parsed.supportedActions()).contains("USE_ITEM_ON_NPC");
    ReplayCapabilityService service = new ReplayCapabilityService();
    service.updateFromHandshake(parsed);
    assertThat(service.isCausalSimulationCertifiable()).isFalse();
    assertThat(service.isAuthoritativeCertifiable()).isFalse();
    assertThat(service.isAvailable()).isFalse();
    var evidence = json.createObjectNode();
    evidence.put("actualNativeInput", nativeJson != null);
    evidence.put("certification", false);
    evidence.set("nativeInput", input);
    evidence.set("normalizedSupportedMethods", json.valueToTree(parsed.supportedMethods()));
    evidence.set("rawValidationProblems", json.valueToTree(validator.validateRawDocument(input)));
    var output = java.nio.file.Path.of("build", "sc274-contract", "native-method-handshake.json");
    java.nio.file.Files.createDirectories(output.getParent());
    java.nio.file.Files.writeString(
        output, json.writeValueAsString(evidence), java.nio.charset.StandardCharsets.UTF_8);
  }

  private void assertInvalid(ObjectNode document) {
    assertThat(validator.validateRawDocument(document))
        .anyMatch(problem -> problem.startsWith("supportedMethods"));
    assertThatThrownBy(() -> validator.parseManifest(document))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
