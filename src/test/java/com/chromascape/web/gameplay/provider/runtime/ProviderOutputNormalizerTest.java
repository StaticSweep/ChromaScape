package com.chromascape.web.gameplay.provider.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderOutputNormalizerTest {
  private ObjectMapper mapper;
  private CanonicalGameplayJson canonical;
  private ProviderOutputNormalizer normalizer;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper();
    canonical = new CanonicalGameplayJson(mapper);
    normalizer =
        new ProviderOutputNormalizer(mapper, new GameplayContractValidator(mapper), canonical);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v1.json")) {
      fixtures = mapper.readTree(input);
    }
  }

  @Test
  void normalizesClaudeAndCodexGoldenProposalsToEquivalentDecisions() throws Exception {
    TurnRequest request = requestWith(fixtures.path("semanticAction"));
    String claudeWrapper =
        mapper.writeValueAsString(
            mapper.createObjectNode().set("structured_output", fixtures.path("claudeProposal")));

    var claude = normalizer.normalize(claudeWrapper, Provider.CLAUDE, "claude-sonnet-4", request);
    var codex =
        normalizer.normalize(
            mapper.writeValueAsString(fixtures.path("codexProposal")),
            Provider.CODEX,
            "gpt-5",
            request);

    assertEquals(TurnState.DECIDED, claude.state());
    assertEquals(TurnState.DECIDED, codex.state());
    assertEquals(
        canonical.digest(claude.decision().semanticAction()),
        canonical.digest(codex.decision().semanticAction()));
    assertEquals(Provider.CLAUDE, claude.decision().provider());
    assertEquals(Provider.CODEX, codex.decision().provider());
  }

  @Test
  void extractsOneClaudeTerminalResultFromBoundedTransportEvents() throws Exception {
    ArrayNode events = mapper.createArrayNode();
    events.add(mapper.createObjectNode().put("type", "system").put("subtype", "init"));
    events.add(mapper.createObjectNode().put("type", "assistant"));
    events.add(
        mapper
            .createObjectNode()
            .put("type", "result")
            .set("structured_output", fixtures.path("claudeProposal")));

    var result =
        normalizer.normalize(
            mapper.writeValueAsString(events),
            Provider.CLAUDE,
            "sonnet",
            requestWith(fixtures.path("semanticAction")));

    assertEquals(TurnState.DECIDED, result.state());
  }

  @Test
  void acceptsOnlyThePinnedAbstainAction() throws Exception {
    JsonNode abstain = fixtures.path("abstainProposal").path("semanticAction");
    var result =
        normalizer.normalize(
            mapper.writeValueAsString(fixtures.path("abstainProposal")),
            Provider.CLAUDE,
            "sonnet",
            requestWith(abstain));
    assertEquals(TurnState.ABSTAINED, result.state());
  }

  @Test
  void rejectsProseMultipleDecisionsAndUnsupportedActions() throws Exception {
    TurnRequest request = requestWith(fixtures.path("semanticAction"));
    ProviderRuntimeException prose =
        assertThrows(
            ProviderRuntimeException.class,
            () -> normalizer.normalize("I would fish.", Provider.CODEX, "gpt-5", request));
    assertEquals("INVALID_OUTPUT", prose.failureCode());

    ArrayNode multiple = mapper.createArrayNode();
    multiple.add(fixtures.path("codexProposal"));
    multiple.add(fixtures.path("codexProposal"));
    ProviderRuntimeException many =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                normalizer.normalize(
                    mapper.writeValueAsString(multiple), Provider.CODEX, "gpt-5", request));
    assertEquals("MULTIPLE_DECISIONS", many.failureCode());

    JsonNode changed = fixtures.path("codexProposal").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("semanticAction"))
        .with("settings")
        .put("targetLevel", 3);
    ProviderRuntimeException unsupported =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                normalizer.normalize(
                    mapper.writeValueAsString(changed), Provider.CODEX, "gpt-5", request));
    assertEquals("UNSUPPORTED_ACTION", unsupported.failureCode());
  }

  @Test
  void rejectsEvidenceOutsidePinnedInput() throws Exception {
    JsonNode changed = fixtures.path("codexProposal").deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) changed.path("evidenceRefs"))
        .set(0, mapper.getNodeFactory().textNode("external:untrusted"));
    ProviderRuntimeException exception =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                normalizer.normalize(
                    mapper.writeValueAsString(changed),
                    Provider.CODEX,
                    "gpt-5",
                    requestWith(fixtures.path("semanticAction"))));
    assertEquals("INVALID_EVIDENCE_REFERENCE", exception.failureCode());
  }

  @Test
  void normalizesAValidV2DecisionAndDerivesItsDigests() throws Exception {
    JsonNode fixturesV2 = v2Fixtures();
    JsonNode decision = fixturesV2.path("claudeDecision").deepCopy();
    TurnRequest request = requestWith(fixturesV2.path("semanticAction"));
    bindDecision(decision, request);

    var normalized =
        normalizer.normalizeV2(
            mapper.writeValueAsString(decision), Provider.CLAUDE, decision.path("modelVersion").asText(), request);

    assertEquals(TurnState.DECIDED, normalized.state());
    assertEquals(canonical.digest(decision), normalized.decisionDigest());
    org.junit.jupiter.api.Assertions.assertTrue(
        normalized.semanticActionDigest().matches("sha256:[0-9a-f]{64}"));
    org.junit.jupiter.api.Assertions.assertTrue(
        normalized.decisionDigest().matches("sha256:[0-9a-f]{64}"));
  }

  @Test
  void rejectsAV2DecisionThatDidNotBindTheShownEligibilityDigest() throws Exception {
    JsonNode fixturesV2 = v2Fixtures();
    JsonNode decision = fixturesV2.path("claudeDecision").deepCopy();
    TurnRequest request = requestWith(fixturesV2.path("semanticAction"));
    bindDecision(decision, request);
    ((com.fasterxml.jackson.databind.node.ObjectNode) decision)
        .put("eligibleInputDigest", "sha256:" + "9".repeat(64));

    ProviderRuntimeException exception =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                normalizer.normalizeV2(
                    mapper.writeValueAsString(decision),
                    Provider.CLAUDE,
                    decision.path("modelVersion").asText(),
                    request));
    assertEquals(ProviderRuntimeModels.FailureCode.INVALID_OUTPUT.name(), exception.failureCode());
  }

  @Test
  void rejectsAV2DecisionWhoseGoalPlanDigestDoesNotMatchItsBytes() throws Exception {
    JsonNode fixturesV2 = v2Fixtures();
    JsonNode decision = fixturesV2.path("claudeDecision").deepCopy();
    TurnRequest request = requestWith(fixturesV2.path("semanticAction"));
    bindDecision(decision, request);
    ((com.fasterxml.jackson.databind.node.ObjectNode) decision.path("goalPlan"))
        .put("terminalObjective", "TAMPERED");

    ProviderRuntimeException exception =
        assertThrows(
            ProviderRuntimeException.class,
            () ->
                normalizer.normalizeV2(
                    mapper.writeValueAsString(decision),
                    Provider.CLAUDE,
                    decision.path("modelVersion").asText(),
                    request));
    assertEquals(ProviderRuntimeModels.FailureCode.INVALID_OUTPUT.name(), exception.failureCode());
  }

  private JsonNode v2Fixtures() throws Exception {
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      return mapper.readTree(input);
    }
  }

  /** Binds the fixture decision to the request's pinned eligibility digest and evidence. */
  private void bindDecision(JsonNode decision, TurnRequest request) {
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) decision;
    node.put("eligibleInputDigest", request.eligibilityDigest());
    var evidence = mapper.createArrayNode();
    evidence.add(request.observationDigest());
    node.set("evidenceRefs", evidence);
  }

  private TurnRequest requestWith(JsonNode action) {
    ArrayNode eligible = mapper.createArrayNode().add(action);
    JsonNode observation = fixtures.path("observation");
    return new TurnRequest(
        "turn-1",
        "request-1",
        "idempotency-1",
        "sha256:" + "1".repeat(64),
        observation,
        canonical.digest(observation),
        eligible,
        canonical.digest(eligible),
        observation.path("identities"),
        "source-sc1429",
        100,
        100,
        Instant.now().plusSeconds(60),
        true);
  }
}
