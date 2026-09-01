package com.chromascape.web.replay.capability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Strict fail-closed contract for the xRSPS capability handshake. EVERY missing, null, wrong-typed,
 * forbidden, or unsupported field must produce a validation problem — a missing field can never
 * inherit a safe-looking default during record conversion.
 */
class XrspsStartupValidatorCapabilityContractTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final XrspsStartupValidator validator =
      new XrspsStartupValidator(new ReplayProperties(), new ReplayCapabilityService(), mapper);

  private JsonNode doc(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A fully supported certifiable handshake document. */
  private String valid() {
    return """
        {
          "schemaVersion": 1,
          "controlAllowed": false,
          "openRuneAvailable": false,
          "certifiable": true,
          "modes": {"observedPlayback": false, "policyReplay": false, "causalSimulation": true},
          "scenarioSchemaVersions": [1],
          "semanticActionVersions": [1],
          "snapshotVersions": [1],
          "projectionVersions": [1],
          "hashVersions": ["sha256-canonical-json-v1"],
          "fidelity": "SYNTHETIC_CONTRACT",
          "supportedActions": ["WAIT_UNTIL_TICK", "INTERACT_NPC"],
          "xrsps": {
            "adapterVersion": "0.1.0",
            "buildCommit": "%s",
            "cacheRevision": "sha256:%s",
            "contentRevision": "sha256:%s"
          }
        }
        """
        .formatted("a".repeat(40), "b".repeat(64), "c".repeat(64));
  }

  @Test
  void completeSupportedDocumentPassesRawValidation() {
    List<String> problems = validator.validateRawDocument(doc(valid()));
    assertTrue(problems.isEmpty(), () -> "unexpected problems: " + problems);
  }

  @Test
  void nonObjectDocumentFails() {
    assertFalse(validator.validateRawDocument(doc("[]")).isEmpty());
    assertFalse(validator.validateRawDocument(doc("null")).isEmpty());
    assertFalse(validator.validateRawDocument(doc("\"x\"")).isEmpty());
  }

  // ── missing fields ──

  @Test
  void eachMissingTopLevelFieldIsNamedAsAProblem() throws Exception {
    ObjectMapper m = new ObjectMapper();
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(valid());
    for (String field :
        new String[] {
          "schemaVersion",
          "certifiable",
          "controlAllowed",
          "openRuneAvailable",
          "modes",
          "scenarioSchemaVersions",
          "semanticActionVersions",
          "snapshotVersions",
          "projectionVersions",
          "hashVersions",
          "fidelity",
          "supportedActions",
          "xrsps"
        }) {
      ObjectNode copy = root.deepCopy();
      copy.remove(field);
      List<String> problems = validator.validateRawDocument(copy);
      assertTrue(
          problems.stream().anyMatch(p -> p.contains(field)),
          () -> "missing " + field + " must be reported, got: " + problems);
      // The missing field must NEVER be silently defaulted into an accepted manifest.
      CapabilityManifest converted = validator.parseManifest(copy);
      assertNotNull(converted);
      if (!field.equals("modes") && !field.equals("xrsps")) {
        assertFalse(
            new ReplayCapabilityService().isCausalSimulationCertifiable(),
            "converted manifest from a broken document must not be certifiable");
      }
    }
  }

  @Test
  void missingModeFieldsFail() throws Exception {
    ObjectMapper m = new ObjectMapper();
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(valid());
    for (String mode : new String[] {"observedPlayback", "policyReplay", "causalSimulation"}) {
      ObjectNode modes = (ObjectNode) root.path("modes").deepCopy();
      modes.remove(mode);
      ObjectNode copy = root.deepCopy();
      copy.set("modes", modes);
      List<String> problems =
          validator.validateRawDocument(copy).stream()
              .filter(p -> p.contains("modes." + mode))
              .toList();
      assertFalse(problems.isEmpty(), () -> "missing modes." + mode + " must be reported");
    }
  }

  @Test
  void missingIdentityFieldsFail() throws Exception {
    ObjectMapper m = new ObjectMapper();
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(valid());
    for (String field :
        new String[] {"adapterVersion", "buildCommit", "cacheRevision", "contentRevision"}) {
      ObjectNode xrsps = (ObjectNode) root.path("xrsps").deepCopy();
      xrsps.remove(field);
      ObjectNode copy = root.deepCopy();
      copy.set("xrsps", xrsps);
      List<String> problems =
          validator.validateRawDocument(copy).stream()
              .filter(p -> p.contains("xrsps." + field))
              .toList();
      assertFalse(problems.isEmpty(), () -> "missing xrsps." + field + " must be reported");
    }
  }

  // ── null fields ──

  @Test
  void explicitNullSchemaVersionFails() throws Exception {
    ObjectMapper m = new ObjectMapper();
    var copy = (ObjectNode) m.readTree(valid());
    copy.putNull("schemaVersion");
    assertFalse(
        validator.validateRawDocument(copy).stream()
            .filter(p -> p.contains("schemaVersion"))
            .toList()
            .isEmpty());
  }

  // ── wrong types ──

  @Test
  void wrongTypesFail() throws Exception {
    ObjectMapper m = new ObjectMapper();

    var boolAsString = (ObjectNode) m.readTree(valid());
    boolAsString.put("certifiable", "true");
    assertTrue(
        validator.validateRawDocument(boolAsString).stream()
            .anyMatch(p -> p.contains("certifiable must be a boolean")));

    var modesNotObject = (ObjectNode) m.readTree(valid());
    modesNotObject.put("modes", "all-on");
    assertTrue(
        validator.validateRawDocument(modesNotObject).stream()
            .anyMatch(p -> p.contains("modes is missing or not an object")));

    var versionsAsString = (ObjectNode) m.readTree(valid());
    versionsAsString.put("hashVersions", "v1");
    assertTrue(
        validator.validateRawDocument(versionsAsString).stream()
            .anyMatch(p -> p.contains("hashVersions must be an array")));

    var revisionAsNumber = (ObjectNode) m.readTree(valid());
    ((ObjectNode) revisionAsNumber.path("xrsps")).put("cacheRevision", 12345);
    assertTrue(
        validator.validateRawDocument(revisionAsNumber).stream()
            .anyMatch(p -> p.contains("cacheRevision must be a string")));
  }

  // ── forbidden values ──

  @Test
  void forbiddenTrueCapabilitiesFailTheHandshake() throws Exception {
    ObjectMapper m = new ObjectMapper();

    var control = (ObjectNode) m.readTree(valid());
    control.put("controlAllowed", true);
    assertTrue(
        validator.validateRawDocument(control).stream()
            .anyMatch(p -> p.contains("controlAllowed=true is forbidden")));

    var openRune = (ObjectNode) m.readTree(valid());
    openRune.put("openRuneAvailable", true);
    assertTrue(
        validator.validateRawDocument(openRune).stream()
            .anyMatch(p -> p.contains("openRuneAvailable=true is forbidden")));

    var policyReplay = (ObjectNode) m.readTree(valid());
    ((ObjectNode) policyReplay.path("modes")).put("policyReplay", true);
    assertTrue(
        validator.validateRawDocument(policyReplay).stream()
            .anyMatch(p -> p.contains("modes.policyReplay=true is forbidden")));

    var notCertifiable = (ObjectNode) m.readTree(valid());
    notCertifiable.put("certifiable", false);
    assertTrue(
        validator.validateRawDocument(notCertifiable).stream()
            .anyMatch(p -> p.contains("certifiable=false is forbidden")));
  }

  // ── unsupported values ──

  @Test
  void unsupportedValuesFail() throws Exception {
    ObjectMapper m = new ObjectMapper();

    var schemaV2 = (ObjectNode) m.readTree(valid());
    schemaV2.put("schemaVersion", 2);
    assertTrue(
        validator.validateRawDocument(schemaV2).stream().anyMatch(p -> p.contains("unsupported")));

    var noCausal = (ObjectNode) m.readTree(valid());
    ((ObjectNode) noCausal.path("modes")).put("causalSimulation", false);
    assertTrue(
        validator.validateRawDocument(noCausal).stream()
            .anyMatch(p -> p.contains("modes.causalSimulation=false is forbidden")));

    var extraVersions = (ObjectNode) m.readTree(valid());
    extraVersions.set("snapshotVersions", m.createArrayNode().add(1).add(2));
    assertTrue(
        validator.validateRawDocument(extraVersions).stream()
            .anyMatch(p -> p.contains("snapshotVersions must be exactly [1]")));

    var unknownHash = (ObjectNode) m.readTree(valid());
    unknownHash.set("hashVersions", m.createArrayNode().add("sha256-canonical-json-v2"));
    assertTrue(
        validator.validateRawDocument(unknownHash).stream()
            .anyMatch(p -> p.contains("hashVersions must contain exactly")));

    var devIdentity = (ObjectNode) m.readTree(valid());
    ((ObjectNode) devIdentity.path("xrsps")).put("buildCommit", "dev");
    assertTrue(
        validator.validateRawDocument(devIdentity).stream()
            .anyMatch(p -> p.contains("non-certifiable placeholder")));

    var unprefixedRevision = (ObjectNode) m.readTree(valid());
    ((ObjectNode) unprefixedRevision.path("xrsps")).put("contentRevision", "d".repeat(64));
    assertTrue(
        validator.validateRawDocument(unprefixedRevision).stream()
            .anyMatch(p -> p.contains("contentRevision is not a sha256 content address")));
  }

  @Test
  void fidelityAndSupportedActionsAreClosedSetFields() throws Exception {
    ObjectMapper m = new ObjectMapper();

    var missingFidelity = (ObjectNode) m.readTree(valid());
    missingFidelity.remove("fidelity");
    assertTrue(
        validator.validateRawDocument(missingFidelity).stream()
            .anyMatch(p -> p.contains("fidelity is missing")));

    var unknownFidelity = (ObjectNode) m.readTree(valid());
    unknownFidelity.put("fidelity", "BEST_EFFORT");
    assertTrue(
        validator.validateRawDocument(unknownFidelity).stream()
            .anyMatch(p -> p.contains("fidelity=") && p.contains("unsupported")));

    var missingActions = (ObjectNode) m.readTree(valid());
    missingActions.remove("supportedActions");
    assertTrue(
        validator.validateRawDocument(missingActions).stream()
            .anyMatch(p -> p.contains("supportedActions is missing")));

    var unknownAction = (ObjectNode) m.readTree(valid());
    unknownAction.set(
        "supportedActions", m.createArrayNode().add("WAIT_UNTIL_TICK").add("TELEPORT"));
    assertTrue(
        validator.validateRawDocument(unknownAction).stream()
            .anyMatch(p -> p.contains("supportedActions must be exactly")));

    var observedPlayback = (ObjectNode) m.readTree(valid());
    ((ObjectNode) observedPlayback.path("modes")).put("observedPlayback", true);
    assertTrue(
        validator.validateRawDocument(observedPlayback).stream()
            .anyMatch(p -> p.contains("modes.observedPlayback=true is forbidden")));
  }

  // ── the unavailable manifest itself ──

  @Test
  void unavailableManifestNeverAdvertisesAnyExecutableCapability() {
    ReplayCapabilityService service = new ReplayCapabilityService();
    CapabilityManifest unavailable = service.getManifest();
    assertNotNull(unavailable);
    assertFalse(unavailable.modes().policyReplay(), "unavailable must never offer policy replay");
    assertFalse(unavailable.modes().causalSimulation());
    assertFalse(unavailable.modes().observedPlayback());
    assertFalse(unavailable.controlAllowed());
    assertFalse(unavailable.openRuneAvailable());
    assertFalse(unavailable.certifiable());
    assertFalse(service.isCausalSimulationCertifiable());
    assertFalse(service.isAvailable());
  }

  @Test
  void serverProducedManifestHardcodesForbiddenCapabilitiesOff() {
    CapabilityManifest manifest =
        CapabilityManifest.current(
            new CapabilityManifest.CapabilityModes(false, false, true),
            new CapabilityManifest.XrspsIdentity(
                "0.1.0", "a".repeat(40), "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64)),
            true);
    assertFalse(manifest.controlAllowed(), "controlAllowed must be hardcoded false");
    assertFalse(manifest.openRuneAvailable(), "openRuneAvailable must be hardcoded false");
  }

  @Test
  void capabilityServiceRejectsSyntheticAndContradictoryPlaybackClaims() {
    ReplayCapabilityService service = new ReplayCapabilityService();
    CapabilityManifest synthetic =
        CapabilityManifest.current(
            new CapabilityManifest.CapabilityModes(false, false, true),
            new CapabilityManifest.XrspsIdentity(
                "0.1.0", "a".repeat(40), "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64)),
            true);
    service.updateFromHandshake(synthetic);
    assertFalse(
        service.isCausalSimulationCertifiable(),
        "synthetic contract fidelity cannot authorize production simulation");

    CapabilityManifest contradictory =
        new CapabilityManifest(
            synthetic.schemaVersion(),
            synthetic.controlAllowed(),
            new CapabilityManifest.CapabilityModes(true, false, true),
            synthetic.scenarioSchemaVersions(),
            synthetic.semanticActionVersions(),
            synthetic.snapshotVersions(),
            synthetic.projectionVersions(),
            synthetic.hashVersions(),
            synthetic.xrsps(),
            synthetic.openRuneAvailable(),
            synthetic.certifiable(),
            CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE,
            synthetic.supportedActions());
    service.updateFromHandshake(contradictory);
    assertFalse(
        service.isCausalSimulationCertifiable(),
        "observed playback must never be treated as causal simulation");
  }
}
