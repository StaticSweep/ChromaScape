package com.chromascape.web.replay.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class XrspsStartupValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final XrspsStartupValidator validator =
      new XrspsStartupValidator(new ReplayProperties(), new ReplayCapabilityService(), mapper);

  @Test
  void parseManifestPreservesSecurityAndCapabilityFields() throws Exception {
    CapabilityManifest manifest = validator.parseManifest(mapper.readTree(validCapabilityJson()));

    assertTrue(manifest.controlAllowed());
    assertTrue(manifest.openRuneAvailable());
    assertEquals(CapabilityManifest.Fidelity.XRSPS_AUTHORITATIVE, manifest.fidelity());
    assertTrue(manifest.certifiable());
    assertEquals(List.of("WAIT_UNTIL_TICK", "INTERACT_NPC"), manifest.supportedActions());
    assertEquals("xrsps-replay-trace/v1", manifest.viewerTraceSchema());
  }

  @Test
  void verifyVersionsRejectsForbiddenCapabilitiesInsteadOfMaskingThem() throws Exception {
    CapabilityManifest manifest = validator.parseManifest(mapper.readTree(validCapabilityJson()));

    List<String> problems = validator.verifyVersions(manifest, new ReplayProperties().getXrsps());

    assertTrue(problems.contains("control must be disallowed"));
    assertTrue(problems.contains("OpenRune must be unavailable"));
  }

  private static String validCapabilityJson() {
    return """
                {
                  "schemaVersion": 1,
                  "controlAllowed": true,
                  "openRuneAvailable": true,
                  "modes": {"observedPlayback": false, "policyReplay": false, "causalSimulation": true},
                  "scenarioSchemaVersions": [1, 2],
                  "semanticActionVersions": [1],
                  "snapshotVersions": [1],
                  "projectionVersions": [1],
                  "hashVersions": ["sha256-canonical-json-v1"],
                  "xrsps": {
                    "adapterVersion": "0.1.0",
                    "buildCommit": "0123456789abcdef0123456789abcdef01234567",
                    "cacheRevision": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                    "contentRevision": "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                  },
                  "certifiable": true,
                  "fidelity": "XRSPS_AUTHORITATIVE",
                  "supportedActions": ["WAIT_UNTIL_TICK", "INTERACT_NPC"],
                  "viewerTraceSchema": "xrsps-replay-trace/v1"
                }
                """;
  }
}
