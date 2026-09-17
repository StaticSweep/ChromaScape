package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels;
import com.chromascape.web.gameplay.orchestration.accountbuilder.ProviderDecisionOrchestrationSource;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionState;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class ProviderRuntimeHardeningTest {
  @TempDir java.nio.file.Path temporary;

  @Test
  void turnLifecyclePredicatesAreFailClosed() {
    for (TurnState state :
        List.of(
            TurnState.DECIDED,
            TurnState.ABSTAINED,
            TurnState.INVALID_OUTPUT,
            TurnState.TIMEOUT,
            TurnState.BUDGET_EXHAUSTED,
            TurnState.CANCELLED,
            TurnState.PROVIDER_UNAVAILABLE,
            TurnState.NEEDS_RECONCILIATION,
            TurnState.INTERNAL_FAILURE)) {
      assertTrue(state.terminal(), state.name());
      assertFalse(state.submitted(), state.name());
    }
    assertFalse(TurnState.CREATED.submitted());
    assertFalse(TurnState.CREATED.terminal());
    assertFalse(TurnState.RESERVED.submitted());
    assertFalse(TurnState.RESERVED.terminal());
    assertTrue(TurnState.SUBMITTED.submitted());
    assertTrue(TurnState.STREAMING.submitted());
    assertTrue(TurnState.NORMALIZING.submitted());
  }

  @Test
  void sessionLifecycleCoversRequiredTerminalStates() {
    assertEquals(
        List.of("CREATED", "ACTIVE", "COMPLETED", "CANCELLED", "FAILED", "NEEDS_RECONCILIATION"),
        java.util.Arrays.stream(SessionState.values()).map(Enum::name).toList());
  }

  @Test
  void sessionStateAggregationKeepsParallelWorkNonTerminal() {
    assertEquals(
        SessionState.ACTIVE,
        JpaProviderRuntimeStore.aggregateSessionState(
            List.of(TurnState.DECIDED, TurnState.STREAMING)));
    assertEquals(
        SessionState.NEEDS_RECONCILIATION,
        JpaProviderRuntimeStore.aggregateSessionState(
            List.of(TurnState.DECIDED, TurnState.NEEDS_RECONCILIATION)));
  }

  @Test
  void sessionStateAggregationUsesStableTerminalPrecedence() {
    assertEquals(
        SessionState.FAILED,
        JpaProviderRuntimeStore.aggregateSessionState(
            List.of(TurnState.DECIDED, TurnState.INVALID_OUTPUT)));
    assertEquals(
        SessionState.COMPLETED,
        JpaProviderRuntimeStore.aggregateSessionState(
            List.of(TurnState.DECIDED, TurnState.CANCELLED)));
    assertEquals(
        SessionState.CANCELLED,
        JpaProviderRuntimeStore.aggregateSessionState(
            List.of(TurnState.CANCELLED, TurnState.CANCELLED)));
    assertEquals(
        SessionState.FAILED,
        JpaProviderRuntimeStore.aggregateSessionState(List.of(TurnState.BUDGET_EXHAUSTED)));
  }

  @Test
  void transcriptDigestMatchesExactStoredBytes() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore store =
        new RedactedTranscriptStore(properties, new CanonicalGameplayJson(new ObjectMapper()));

    RedactedTranscriptStore.Artifact artifact = store.store("result=ok");
    java.nio.file.Path target =
        temporary.resolve(
            "transcripts/" + artifact.reference().substring("sha256:".length()) + ".json");
    byte[] stored = Files.readAllBytes(target);
    String expected =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored));
    assertEquals(expected, artifact.reference());
    assertEquals(stored.length, artifact.byteLength());
  }

  @Test
  void transcriptDuplicateContentIsContentAddressed() {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore store =
        new RedactedTranscriptStore(properties, new CanonicalGameplayJson(new ObjectMapper()));

    RedactedTranscriptStore.Artifact first = store.store("duplicate-content");
    RedactedTranscriptStore.Artifact second = store.store("duplicate-content");
    assertEquals(first.reference(), second.reference());
    assertEquals(first.byteLength(), second.byteLength());
  }

  @Test
  void transcriptRejectsTamperedExistingArtifact() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore store =
        new RedactedTranscriptStore(properties, new CanonicalGameplayJson(new ObjectMapper()));

    RedactedTranscriptStore.Artifact artifact = store.store("trusted-content");
    java.nio.file.Path target =
        temporary.resolve(
            "transcripts/" + artifact.reference().substring("sha256:".length()) + ".json");
    Files.writeString(target, "tampered-content");

    ProviderRuntimeException failure =
        assertThrows(ProviderRuntimeException.class, () -> store.store("trusted-content"));
    assertEquals(FailureCode.TRANSCRIPT_PERSISTENCE_FAILED.name(), failure.failureCode());
  }

  @Test
  void transcriptRedactsRepresentativeSecrets() throws Exception {
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setTranscriptRoot(temporary.resolve("transcripts"));
    RedactedTranscriptStore store =
        new RedactedTranscriptStore(properties, new CanonicalGameplayJson(new ObjectMapper()));

    RedactedTranscriptStore.Artifact artifact =
        store.store("api_key=super-secret-value password: hunter2 token abc123 result=ok");
    String stored =
        Files.readString(
            temporary.resolve(
                "transcripts/" + artifact.reference().substring("sha256:".length()) + ".json"));
    assertTrue(stored.contains("[REDACTED]"));
    assertFalse(stored.contains("super-secret-value"));
    assertFalse(stored.contains("hunter2"));
  }

  @Test
  void httpStatusMappingIsStable() {
    ProviderRuntimeController controller = new ProviderRuntimeController(null, null);
    assertEquals(
        HttpStatus.UNAUTHORIZED,
        controller
            .providerError(new ProviderRuntimeException("OPERATOR_UNAUTHORIZED", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        controller
            .providerError(new ProviderRuntimeException("SESSION_NOT_FOUND", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        controller
            .providerError(new ProviderRuntimeException("IDEMPOTENCY_CONFLICT", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        controller
            .providerError(new ProviderRuntimeException("UNKNOWN_PROVIDER_OUTCOME", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.CONFLICT,
        controller
            .providerError(new ProviderRuntimeException("NEEDS_RECONCILIATION", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.UNPROCESSABLE_ENTITY,
        controller
            .providerError(new ProviderRuntimeException("BUDGET_EXHAUSTED", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.SERVICE_UNAVAILABLE,
        controller
            .providerError(new ProviderRuntimeException("RUNTIME_DISABLED", "x"))
            .getStatusCode());
    assertEquals(
        HttpStatus.GATEWAY_TIMEOUT,
        controller.providerError(new ProviderRuntimeException("TIMEOUT", "x")).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .providerError(new ProviderRuntimeException("INVALID_OUTPUT", "x"))
            .getStatusCode());
  }

  @Test
  void budgetLimitsRejectUnboundedOrInvertedWindows() {
    assertThrows(
        IllegalArgumentException.class, () -> new BudgetLimits(10, 10, 1, 0, 1, 120_000, 60_000));
    assertThrows(
        IllegalArgumentException.class, () -> new BudgetLimits(0, 10, 1, 0, 1, 1_000, 2_000));
  }

  @Test
  void handoffExposesOnlyReadOnlyBind() throws Exception {
    for (Method method : ProviderDecisionOrchestrationSource.class.getDeclaredMethods()) {
      String name = method.getName().toLowerCase();
      assertFalse(name.contains("reserve"), "handoff must not reserve");
      assertFalse(name.contains("start"), "handoff must not start");
      assertFalse(name.contains("mutat"), "handoff must not mutate");
      assertFalse(name.contains("rawinput"), "handoff must not expose raw input");
      assertFalse(name.contains("client"), "handoff must not expose client");
      assertFalse(name.contains("micro6"), "handoff must not expose micro6");
    }
    ProviderDecisionHandoff empty = (sessionId, turnId) -> Optional.empty();
    ProviderDecisionOrchestrationSource source = new ProviderDecisionOrchestrationSource(empty);
    AccountBuilderOrchestrationModels.OrchestrationRequest request =
        new AccountBuilderOrchestrationModels.OrchestrationRequest(
            "orchestration-1",
            "request-1",
            "idempotency-1",
            "sha256:" + "a".repeat(64),
            AccountBuilderOrchestrationModels.Mode.SHADOW,
            "PROVIDER:CLAUDE",
            null,
            null,
            new AccountBuilderOrchestrationModels.SnapshotBindings(
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64),
                "sha256:" + "e".repeat(64),
                List.of("sha256:" + "f".repeat(64)),
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64),
                "build-1",
                1,
                1),
            null,
            null,
            java.time.Instant.now().plusSeconds(60));
    assertThrows(RuntimeException.class, () -> source.bind("session-1", "turn-1", request));
  }
}
