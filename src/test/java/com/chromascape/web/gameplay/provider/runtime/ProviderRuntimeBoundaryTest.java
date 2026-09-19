package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderRuntimeBoundaryTest {
  @TempDir java.nio.file.Path temporary;

  @Test
  void runtimeIsDefaultOffAndModelPatternsAreClosed() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    assertFalse(properties.isEnabled());
    assertTrue(properties.getClaude().allowsModel("claude-sonnet-4-6"));
    assertFalse(properties.getClaude().allowsModel("gpt-5"));
    assertTrue(properties.getCodex().allowsModel("gpt-5"));
    assertFalse(properties.getCodex().allowsModel("claude-opus-4"));
  }

  @Test
  void enabledRuntimeRequiresLongOperatorKey() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setEnabled(true);
    properties.setOperatorKey("short");
    assertThrows(IllegalStateException.class, properties::validate);
  }

  @Test
  void facadeRejectsUnknownToolsAndChangedArguments() {
    ObjectMapper mapper = new ObjectMapper();
    CanonicalGameplayJson canonical = new CanonicalGameplayJson(mapper);
    DecisionToolFacade facade = new DecisionToolFacade(canonical);
    JsonNode observation = mapper.createObjectNode().put("value", "pinned");
    JsonNode actions =
        mapper.createArrayNode().add(mapper.createObjectNode().put("kind", "ABSTAIN"));
    TurnRequest request =
        new TurnRequest(
            "turn",
            "request",
            "idempotency",
            "sha256:" + "a".repeat(64),
            observation,
            canonical.digest(observation),
            actions,
            canonical.digest(actions),
            mapper.createObjectNode(),
            "source",
            1,
            1,
            Instant.now().plusSeconds(60),
            true);

    ProviderRuntimeException unknown =
        assertThrows(
            ProviderRuntimeException.class,
            () -> facade.invoke("shell", Map.of("digest", request.observationDigest()), request));
    assertEquals("TOOL_NOT_ALLOWED", unknown.failureCode());
    ProviderRuntimeException changed =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                facade.invoke(
                    DecisionToolFacade.OBSERVATION, Map.of("digest", "sha256:bad"), request));
    assertEquals("TOOL_ARGUMENT_MISMATCH", changed.failureCode());
  }

  @Test
  void transcriptsAreContentAddressedAndSecretsAreRedacted() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary);
    RedactedTranscriptStore store =
        new RedactedTranscriptStore(properties, new CanonicalGameplayJson(new ObjectMapper()));
    RedactedTranscriptStore.Artifact artifact =
        store.store("Authorization: Bearer sensitive-value\nresult=ok");

    String stored =
        Files.readString(
            temporary.resolve(artifact.reference().substring("sha256:".length()) + ".json"));
    assertTrue(stored.contains("[REDACTED]"));
    assertFalse(stored.contains("sensitive-value"));
  }

  @Test
  void proposalSchemaIsValidJson() throws Exception {
    JsonNode schema = new ObjectMapper().readTree(ProviderProposalSchema.JSON);
    assertEquals("object", schema.path("type").asText());
    assertEquals(
        List.of(
            "contractVersion", "decisionId", "policyConfidence", "semanticAction", "evidenceRefs"),
        new ObjectMapper().convertValue(schema.path("required"), List.class));
  }

  @Test
  void processRunnerBoundsOutputAndClassifiesTimeoutAsUncertain() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setWorkRoot(temporary.resolve("work"));
    properties.setMaxStdoutBytes(4096);
    properties.setMaxStderrBytes(4096);
    SafeProviderProcessRunner runner = new SafeProviderProcessRunner(properties);
    List<String> echo = fixtureCommand("echo");

    var success =
        runner.run("turn-success", echo, List.of(), "decision", Instant.now().plusSeconds(10));
    assertTrue(success.submitted(), () -> "expected submission: " + success);
    assertTrue(success.terminal(), () -> "expected terminal result: " + success);
    assertEquals("decision", success.stdout());

    var overflow =
        runner.run(
            "turn-overflow", echo, List.of(), "x".repeat(5000), Instant.now().plusSeconds(10));
    assertEquals(ProviderRuntimeModels.FailureCode.INVALID_OUTPUT, overflow.failureCode());

    var firstEventTimeout =
        runner.run(
            "turn-timeout", fixtureCommand("sleep"), List.of(), "", Instant.now().plusMillis(100));
    assertTrue(firstEventTimeout.submitted());
    assertEquals(
        ProviderRuntimeModels.FailureCode.FIRST_EVENT_TIMEOUT, firstEventTimeout.failureCode());

    var turnTimeout =
        runner.run(
            "turn-total-timeout",
            fixtureCommand("event-then-sleep"),
            List.of(),
            "",
            Instant.now().plusMillis(1_000));
    assertTrue(turnTimeout.submitted());
    assertEquals(
        ProviderRuntimeModels.FailureCode.UNKNOWN_PROVIDER_OUTCOME, turnTimeout.failureCode());
  }

  private List<String> fixtureCommand(String mode) {
    List<String> command = new ArrayList<>();
    command.add(
        java.nio.file.Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString());
    command.add("-cp");
    command.add(fixtureClasspath());
    command.add(ProviderProcessFixture.class.getName());
    command.add(mode);
    return command;
  }

  /**
   * The fixture only needs its own class directory. Passing the worker's full classpath as a
   * command-line argument exceeds the Windows 32767-character limit and fails with CreateProcess
   * error=206, so scope it to the compiled test classes that contain the fixture.
   */
  private String fixtureClasspath() {
    try {
      java.net.URL location =
          ProviderProcessFixture.class.getProtectionDomain().getCodeSource().getLocation();
      return java.nio.file.Path.of(location.toURI()).toString();
    } catch (java.net.URISyntaxException exception) {
      throw new IllegalStateException("fixture class location is not a usable path", exception);
    }
  }

  private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
