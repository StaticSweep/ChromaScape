package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DebuggerCaptureIdentityTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void canonicalizesOptionalUnknownsAndDoesNotTrustMissingProducerDiagnostics() throws Exception {
    var client =
        json.readTree(
            """
        {"schema":"debugger-client-identity/v1",
         "cacheRevisionSource":"CLIENT_GET_REVISION","qualityCodes":[]}
        """);
    var identity = DebuggerCaptureIdentity.validateClient(client);
    assertThat(identity.get("artifactSha256").isNull()).isTrue();
    assertThat(identity.path("qualityCodes").toString())
        .contains("CLIENT_ARTIFACT_MISSING", "CLIENT_BUILD_MISSING", "CLIENT_REVISION_MISSING");
    var snapshot = DebuggerCaptureIdentity.snapshot(identity, 2);
    identity.put("clientBuildId", "mutated");
    assertThat(snapshot.path("client").get("clientBuildId").isNull()).isTrue();
    assertThat(snapshot.path("server").path("artifactSha256").asText()).matches("[a-f0-9]{64}");
    assertThat(snapshot.path("server").path("artifactKind").asText())
        .isEqualTo("APPLICATION_CLASS_DIRECTORY");
  }

  @Test
  void rejectsUnknownFieldsSecretsMalformedAndOversizedIdentity() throws Exception {
    var valid =
        json.createObjectNode()
            .put("schema", "debugger-client-identity/v1")
            .put("cacheRevisionSource", "CLIENT_GET_REVISION")
            .put("gameRevision", 241)
            .put("cacheRevision", 241);
    valid.putArray("qualityCodes");
    for (String field : java.util.List.of("token", "artifactSha256", "clientBuildId")) {
      var bad = valid.deepCopy().put(field, "secret spaces");
      assertThatThrownBy(() -> DebuggerCaptureIdentity.validateClient(bad))
          .hasMessageContaining("INVALID_CAPTURE_IDENTITY");
    }
    assertThatThrownBy(
            () ->
                DebuggerCaptureIdentity.validateClient(
                    valid.deepCopy().put("clientVersion", "x".repeat(9000))))
        .hasMessageContaining("INVALID_CAPTURE_IDENTITY");
    assertThatThrownBy(
            () -> DebuggerCaptureIdentity.validateClient(valid.deepCopy().put("gameRevision", 242)))
        .hasMessageContaining("INVALID_CAPTURE_IDENTITY");
    assertThat(DebuggerCaptureIdentity.validateClient(null)).isNull();
  }

  @Test
  void keepsTheReportedWorldAndOmitsItWhenAnOlderClientReportsNone() throws Exception {
    var client =
        json.createObjectNode()
            .put("schema", "debugger-client-identity/v1")
            .put("cacheRevisionSource", "CLIENT_GET_REVISION")
            .put("gameRevision", 241)
            .put("cacheRevision", 241);
    client.putArray("qualityCodes");
    // An identity bound before worldId existed must canonicalize exactly as it did then.
    assertThat(DebuggerCaptureIdentity.validateClient(client).has("worldId")).isFalse();
    assertThat(
            DebuggerCaptureIdentity.validateClient(client.deepCopy().putNull("worldId"))
                .has("worldId"))
        .isFalse();
    assertThat(
            DebuggerCaptureIdentity.validateClient(client.deepCopy().put("worldId", 301))
                .path("worldId")
                .asInt())
        .isEqualTo(301);
    for (var bad :
        java.util.List.of(
            client.deepCopy().put("worldId", 0),
            client.deepCopy().put("worldId", 70000),
            client.deepCopy().put("worldId", "301"))) {
      assertThatThrownBy(() -> DebuggerCaptureIdentity.validateClient(bad))
          .hasMessageContaining("INVALID_CAPTURE_IDENTITY");
    }
  }
}
