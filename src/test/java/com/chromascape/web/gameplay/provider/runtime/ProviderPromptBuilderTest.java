package com.chromascape.web.gameplay.provider.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.BudgetLimits;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A turn is only asked for the decision shape its session declared. A v2 session must be asked for
 * the GoalPlan-bearing decision; a v1 session must keep receiving exactly the prompt it received
 * before, because its persisted decisions and digests are frozen.
 */
class ProviderPromptBuilderTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final ProviderPromptBuilder builder = new ProviderPromptBuilder(objectMapper);

  @Test
  void v2SessionIsAskedForTheGoalPlanBearingDecision() throws Exception {
    String prompt = builder.build(session("sc-1429/v2"), turn());

    assertThat(prompt).contains("sc-1429/v2 providerDecision");
    assertThat(prompt).contains("\"proposalSchema\"");
    JsonNode input = input(prompt);
    assertThat(input.path("gameplayContractVersion").asText()).isEqualTo("sc-1429/v2");
    assertThat(input.path("proposalSchema").asText()).contains("\"goalPlan\"");
    assertThat(input.path("proposalSchema").asText()).contains("\"eligibleInputDigest\"");
  }

  @Test
  void v1SessionKeepsTheFrozenV1Prompt() throws Exception {
    String prompt = builder.build(session("sc-1429/v1"), turn());

    assertThat(prompt).contains("sc-1429/v1 providerProposal");
    JsonNode input = input(prompt);
    assertThat(input.path("gameplayContractVersion").asText()).isEqualTo("sc-1429/v1");
    assertThat(input.has("proposalSchema")).isFalse();
  }

  @Test
  void legacySessionRequestDefaultsToV1() {
    SessionRequest legacy =
        new SessionRequest(
            "session-legacy",
            Provider.CODEX,
            "gpt-5",
            "task",
            "run",
            "recording",
            DIGEST,
            DIGEST,
            DIGEST,
            DIGEST,
            List.of(DIGEST),
            Instant.now().plusSeconds(300),
            new BudgetLimits(10_000, 10_000, 4, 0, 4, 60_000, 240_000),
            true);

    assertThat(legacy.gameplayContractVersion()).isEqualTo("sc-1429/v1");
    assertThat(legacy.v2()).isFalse();
  }

  private JsonNode input(String prompt) throws Exception {
    int index = prompt.indexOf("INPUT:\n");
    assertThat(index).isGreaterThanOrEqualTo(0);
    return objectMapper.readTree(prompt.substring(index + "INPUT:\n".length()));
  }

  private SessionRequest session(String gameplayContractVersion) {
    return new SessionRequest(
        "session-1",
        Provider.CODEX,
        "gpt-5",
        "task",
        "run",
        "recording",
        DIGEST,
        DIGEST,
        DIGEST,
        DIGEST,
        List.of(DIGEST),
        Instant.now().plusSeconds(300),
        new BudgetLimits(10_000, 10_000, 4, 0, 4, 60_000, 240_000),
        true,
        gameplayContractVersion);
  }

  private TurnRequest turn() {
    return new TurnRequest(
        "turn-1",
        "request-1",
        "idempotency-1",
        DIGEST,
        objectMapper.createObjectNode().put("revision", 1),
        DIGEST,
        objectMapper.createArrayNode(),
        DIGEST,
        objectMapper.createObjectNode().put("accountId", "account-1"),
        "source-1",
        1000,
        1000,
        Instant.now().plusSeconds(300),
        true);
  }
}
