package com.chromascape.web.gameplay.contract.v2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Executes every named one-field mutation in the v2 golden fixture corpus and asserts both the
 * outcome and the stable failure code. A mutation descriptor is only coverage when this harness
 * applies it and validates the result.
 */
class GameplayV2MutationMatrixTest {
  private static final Instant NOW = Instant.parse("2026-09-03T12:04:00Z");
  private ObjectMapper objectMapper;
  private GameplayV2ContractValidator validator;
  private JsonNode fixtures;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    validator = new GameplayV2ContractValidator(objectMapper);
    try (InputStream input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("contracts/gameplay/sc-1429-golden-fixtures-v2.json")) {
      fixtures = objectMapper.readTree(input);
    }
  }

  @Test
  void everyFixtureCaseIsExecutedAndReconciled() {
    int executed = 0;
    for (JsonNode testCase : fixtures.path("cases")) {
      try {
        executeCase(testCase);
      } catch (RuntimeException exception) {
        throw new AssertionError("case failed: " + testCase.path("id").asText(), exception);
      }
      executed++;
    }
    assertThat(executed).isEqualTo(85);
  }

  private void executeCase(JsonNode testCase) {
    String id = testCase.path("id").asText();
    String sourcePointer = testCase.path("source").asText();
    JsonNode source = sourceOf(sourcePointer);
    JsonNode mutation = testCase.path("mutation");
    String op = mutation.path("op").asText();
    JsonNode mutated =
        applyMutation(source, op, mutation.path("path").asText(), mutation.path("value"));
    String expected = testCase.path("failureCode").asText(null);
    switch (testCase.path("kind").asText()) {
      case "schema" -> assertSchemaViolation(
          id, testCase.path("definition").asText(), mutated, expected);
      case "eligibility" -> assertEligibility(id, sourcePointer, mutated, expected);
      case "goal-plan" -> assertGoalPlan(id, mutated, expected);
      case "provider-catalog" -> assertProviderCatalog(id, mutated, expected);
      case "envelope-rules" -> assertEnvelopeRules(id, mutated, expected);
      case "envelope-full" -> assertEnvelopeFull(id, mutated, expected);
      case "envelope-full-planning" -> assertEnvelopeFullWith(
          id,
          fixtures.path("executionEnvelope"),
          fixtures.path("catalogSnapshot"),
          fixtures.path("semanticObservation"),
          mutated,
          fixtures.path("eligibilityResult"),
          fixtures.path("semanticAction"),
          fixtures.path("goalPlan"),
          fixtures.path("claudeDecision"),
          expected);
      case "envelope-full-eligibility" -> assertEnvelopeFullWith(
          id,
          fixtures.path("executionEnvelope"),
          fixtures.path("catalogSnapshot"),
          fixtures.path("semanticObservation"),
          fixtures.path("planningInput"),
          mutated,
          fixtures.path("semanticAction"),
          fixtures.path("goalPlan"),
          fixtures.path("claudeDecision"),
          expected);
      case "envelope-full-action" -> assertEnvelopeFullWith(
          id,
          fixtures.path("executionEnvelope"),
          fixtures.path("catalogSnapshot"),
          fixtures.path("semanticObservation"),
          fixtures.path("planningInput"),
          fixtures.path("eligibilityResult"),
          mutated,
          fixtures.path("goalPlan"),
          fixtures.path("claudeDecision"),
          expected);
      case "envelope-full-decision" -> assertEnvelopeFullWith(
          id,
          fixtures.path("executionEnvelope"),
          fixtures.path("catalogSnapshot"),
          fixtures.path("semanticObservation"),
          fixtures.path("planningInput"),
          fixtures.path("eligibilityResult"),
          fixtures.path("semanticAction"),
          fixtures.path("goalPlan"),
          mutated,
          expected);
      case "authority-rules" -> assertAuthorityRules(id, op, mutated, expected);
      case "authority-scope" -> assertAuthorityScope(id, sourcePointer, mutated, expected);
      case "receipt-rules" -> assertSchemaViolation(id, "lifecycleReceipt", mutated, expected);
      case "tool-mode" -> assertSchemaViolation(id, "toolOperation", mutated, expected);
      case "idempotency" -> assertIdempotency(id, op, source, testCase.path("expected").asText());
      default -> throw new AssertionError("Unhandled case kind: " + id);
    }
  }

  private JsonNode sourceOf(String pointer) {
    return fixtures.at("/" + pointer.replace('.', '/'));
  }

  private JsonNode applyMutation(JsonNode source, String op, String path, JsonNode value) {
    JsonNode target = source.deepCopy();
    String[] segments = path.split("/");
    JsonNode parent = target;
    for (int i = 1; i < segments.length - 1; i++) {
      parent = navigate(parent, segments[i]);
    }
    String leaf = segments[segments.length - 1];
    JsonNode leafParent = parent;
    switch (op) {
      case "replace", "add" -> {
        if (leafParent.isArray()) {
          ((com.fasterxml.jackson.databind.node.ArrayNode) leafParent)
              .set(Integer.parseInt(leaf), value.deepCopy());
        } else {
          ((com.fasterxml.jackson.databind.node.ObjectNode) leafParent).set(leaf, value.deepCopy());
        }
      }
      case "remove" -> {
        if (leafParent.isArray()) {
          ((com.fasterxml.jackson.databind.node.ArrayNode) leafParent)
              .remove(Integer.parseInt(leaf));
        } else {
          ((com.fasterxml.jackson.databind.node.ObjectNode) leafParent).remove(leaf);
        }
      }
      case "consume-twice",
          "dispatch-identical-twice",
          "reuse-idempotency-with-different-request" -> {
        // Handled by dedicated rule assertions; return the source unchanged.
        return target;
      }
      default -> throw new AssertionError("Unhandled mutation op: " + op);
    }
    return target;
  }

  private JsonNode navigate(JsonNode node, String segment) {
    if (node.isArray()) {
      return node.path(Integer.parseInt(segment));
    }
    return node.path(segment);
  }

  private void assertSchemaViolation(
      String id, String definition, JsonNode value, String expected) {
    try {
      validator.validateWithRules(definition, value);
      throw new AssertionError(id + ": expected rejection with " + expected);
    } catch (GameplayV2ContractValidator.GameplayV2ContractViolation violation) {
      assertThat(violation.failureCode()).as(id).isEqualTo(expected);
    }
  }

  private void assertEligibility(
      String id, String sourcePointer, JsonNode mutated, String expected) {
    JsonNode observation;
    JsonNode planningInput;
    if (sourcePointer.startsWith("planningInput")) {
      observation = fixtures.path("semanticObservation");
      planningInput = mutated;
    } else {
      observation = mutated;
      planningInput = planningInputFor(observation);
    }
    JsonNode result =
        new GameplayV2EligibilityFilter(objectMapper)
            .filter(fixtures.path("catalogSnapshot"), observation, planningInput, NOW);
    assertThat(GameplayV2EligibilityFilter.entries(result))
        .as(id)
        .allSatisfy(entry -> assertThat(entry.status().name()).isEqualTo(expected));
  }

  /** The real flow builds planning input from the observation, so the harness rebinding follows. */
  private JsonNode planningInputFor(JsonNode observation) {
    JsonNode planningInput = fixtures.path("planningInput").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) planningInput)
        .put(
            "observationDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, observation));
    return planningInput;
  }

  private void assertEnvelopeRules(String id, JsonNode envelope, String expected) {
    String code =
        GameplayV2EnvelopeRules.checkBindings(
            envelope,
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("goalPlan"),
            NOW);
    assertThat(code).as(id).isEqualTo(expected);
  }

  private void assertGoalPlan(String id, JsonNode plan, String expected) {
    try {
      // Catalog binding runs before graph/digest checks so a node that
      // violates the catalog surfaces as NODE_CATALOG_MISMATCH rather than
      // a downstream digest mismatch.
      validator.validateGoalPlanWithCatalog(plan, fixtures.path("catalogSnapshot"));
      validator.validateWithRules("goalPlan", plan);
      throw new AssertionError(id + ": expected rejection with " + expected);
    } catch (GameplayV2ContractValidator.GameplayV2ContractViolation violation) {
      assertThat(violation.failureCode()).as(id).isEqualTo(expected);
    }
  }

  private void assertProviderCatalog(String id, JsonNode decision, String expected) {
    try {
      // Catalog binding runs before graph/digest checks so a node that
      // violates the catalog surfaces as NODE_CATALOG_MISMATCH rather than
      // a downstream digest mismatch.
      validator.validateProviderDecisionWithCatalog(decision, fixtures.path("catalogSnapshot"));
      throw new AssertionError(id + ": expected rejection with " + expected);
    } catch (GameplayV2ContractValidator.GameplayV2ContractViolation violation) {
      assertThat(violation.failureCode()).as(id).isEqualTo(expected);
    }
  }

  private void assertEnvelopeFull(String id, JsonNode envelope, String expected) {
    String code =
        GameplayV2EnvelopeRules.checkFullBindings(
            envelope,
            fixtures.path("catalogSnapshot"),
            fixtures.path("semanticObservation"),
            fixtures.path("planningInput"),
            fixtures.path("eligibilityResult"),
            fixtures.path("semanticAction"),
            fixtures.path("goalPlan"),
            fixtures.path("claudeDecision"),
            GameplayV2EnvelopeRules.canonicalSchemaDigest(),
            NOW);
    assertThat(code).as(id).isEqualTo(expected);
  }

  private void assertEnvelopeFullWith(
      String id,
      JsonNode envelope,
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      JsonNode semanticAction,
      JsonNode goalPlan,
      JsonNode providerDecision,
      String expected) {
    String code =
        GameplayV2EnvelopeRules.checkFullBindings(
            envelope,
            catalog,
            observation,
            planningInput,
            eligibilityResult,
            semanticAction,
            goalPlan,
            providerDecision,
            GameplayV2EnvelopeRules.canonicalSchemaDigest(),
            NOW);
    assertThat(code).as(id).isEqualTo(expected);
  }

  private void assertAuthorityRules(String id, String op, JsonNode authority, String expected) {
    JsonNode envelope = fixtures.path("executionEnvelope");
    Set<String> consumed =
        "consume-twice".equals(op) ? Set.of(authority.path("authorityId").asText()) : Set.of();
    String code =
        GameplayV2EnvelopeRules.checkAuthority(
            authority,
            fixtures.path("approvedScope"),
            envelope,
            fixtures.path("expectedDigests").path("executionEnvelope").asText(),
            envelope.path("authorityId").asText(),
            fixtures.path("testSigningSecret").asText(),
            NOW,
            consumed);
    assertThat(code).as(id).isEqualTo(expected);
  }

  private void assertAuthorityScope(
      String id, String sourcePointer, JsonNode mutated, String expected) {
    JsonNode authority =
        sourcePointer.startsWith("authority") ? mutated : fixtures.path("authority");
    JsonNode approvedScope =
        sourcePointer.startsWith("approvedScope") ? mutated : fixtures.path("approvedScope");
    JsonNode envelope = fixtures.path("executionEnvelope");
    String code =
        GameplayV2EnvelopeRules.checkAuthority(
            authority,
            approvedScope,
            envelope,
            fixtures.path("expectedDigests").path("executionEnvelope").asText(),
            envelope.path("authorityId").asText(),
            fixtures.path("testSigningSecret").asText(),
            NOW,
            Set.of());
    assertThat(code).as(id).isEqualTo(expected);
  }

  private void assertIdempotency(String id, String op, JsonNode envelope, String expectedOutcome) {
    String key = envelope.path("idempotencyKey").asText();
    String requestId = envelope.path("requestId").asText();
    if ("dispatch-identical-twice".equals(op)) {
      java.util.concurrent.ConcurrentMap<String, String> ledger =
          new java.util.concurrent.ConcurrentHashMap<>();
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, ledger))
          .as(id)
          .isNull();
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, ledger))
          .as(id)
          .isEqualTo("RECONCILED");
      assertThat(expectedOutcome).as(id).isEqualTo("RECONCILED");
      // Same key and requestId but a mutated envelope must conflict, not
      // reconcile. The envelope overload recomputes the digest internally.
      JsonNode mutated = envelope.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) mutated).put("fence", 9999L);
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, mutated, ledger))
          .as(id + ":mutated-envelope")
          .isEqualTo("IDEMPOTENCY_CONFLICT");
      String staleDigest = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope);
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, staleDigest, ledger))
          .as(id + ":stale-digest-hint")
          .isEqualTo("IDEMPOTENCY_CONFLICT");
      // Legacy requestId-only records return a non-fresh reconciliation signal, never the
      // fresh-request null, and are never mutated in place: external reconciliation must prove
      // the old request's identity/outcome first. A repeat call still returns
      // NEEDS_RECONCILIATION, never RECONCILED.
      java.util.concurrent.ConcurrentMap<String, String> legacy =
          new java.util.concurrent.ConcurrentHashMap<>(Map.of(key, requestId));
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
          .as(id + ":legacy-upgrade")
          .isEqualTo("NEEDS_RECONCILIATION");
      assertThat(legacy).as(id + ":legacy-unmutated").isEqualTo(Map.of(key, requestId));
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
          .as(id + ":legacy-retry")
          .isEqualTo("NEEDS_RECONCILIATION");
      // Once external reconciliation proves the old request with a validated receipt,
      // the ledger advances atomically via compare-and-set; the next identical dispatch
      // then reconciles.
      java.time.Instant confirmedAt = java.time.Instant.parse("2026-09-03T12:04:00Z");
      GameplayV2ReconciliationReceipt receipt =
          GameplayV2ReconciliationReceipt.issueTrusted(
              key,
              requestId,
              GameplayV2EligibilityFilter.canonicalDigest(objectMapper, envelope),
              GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME,
              "sc-1449-trusted-reconciler",
              confirmedAt,
              "sc-1449-test-key",
              confirmedAt.plusSeconds(600),
              confirmedAt,
              "test-signing-secret-0123456789abcdef-0123456789");
      assertThat(
              GameplayV2EnvelopeRules.confirmLegacyReconciliation(
                  key,
                  requestId,
                  envelope,
                  legacy,
                  receipt,
                  "test-signing-secret-0123456789abcdef-0123456789",
                  "sc-1449-test-key",
                  confirmedAt.plusSeconds(1)))
          .as(id + ":legacy-confirmed")
          .isTrue();
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, legacy))
          .as(id + ":legacy-reconciled")
          .isEqualTo("RECONCILED");
    } else {
      java.util.concurrent.ConcurrentMap<String, String> ledger =
          new java.util.concurrent.ConcurrentHashMap<>(Map.of(key, "other-request"));
      assertThat(GameplayV2EnvelopeRules.checkIdempotency(key, requestId, envelope, ledger))
          .as(id)
          .isEqualTo("IDEMPOTENCY_CONFLICT");
      assertThat(expectedOutcome).as(id).isEqualTo("DENIED");
    }
  }

  @Test
  void authorityHintMustMatchEnvelopeAuthorityId() {
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
        .put("authorityId", "auth-envelope-other");
    // The envelope/authority pair is internally signed for the original ID,
    // but the hint carries the authority's ID while the envelope binds a
    // different one: the triple equality must fail closed.
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                fixtures.path("authority"),
                fixtures.path("approvedScope"),
                envelope,
                fixtures.path("expectedDigests").path("executionEnvelope").asText(),
                fixtures.path("authority").path("authorityId").asText(),
                fixtures.path("testSigningSecret").asText(),
                NOW,
                Set.of()))
        .isEqualTo("AUTHORITY_CHANGED");
  }

  @Test
  void fabricatedEligibilityIsRejectedByFullBindings() {
    // Fabricate an ELIGIBLE for a stale observation, then rebind every
    // downstream digest consistently: the replay must still reject it.
    JsonNode staleObservation = fixtures.path("semanticObservation").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) staleObservation)
        .put("observationComplete", false);
    JsonNode planningInput = planningInputFor(staleObservation);
    JsonNode fabricated =
        new GameplayV2EligibilityFilter(objectMapper)
            .filter(
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                planningInput,
                NOW);
    ((com.fasterxml.jackson.databind.node.ObjectNode) fabricated)
        .put(
            "observationDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, staleObservation));
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
        .put(
            "eligibilityResultDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, fabricated));
    assertThat(
            GameplayV2EnvelopeRules.checkFullBindings(
                envelope,
                fixtures.path("catalogSnapshot"),
                staleObservation,
                planningInput,
                fabricated,
                fixtures.path("semanticAction"),
                fixtures.path("goalPlan"),
                fixtures.path("claudeDecision"),
                GameplayV2EnvelopeRules.canonicalSchemaDigest(),
                NOW))
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void futureEligibilityEvaluationIsRejected() {
    JsonNode future = fixtures.path("eligibilityResult").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) future)
        .put("evaluatedAt", NOW.plusSeconds(600).toString());
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope)
        .put(
            "eligibilityResultDigest",
            GameplayV2EligibilityFilter.canonicalDigest(objectMapper, future));
    assertThat(
            GameplayV2EnvelopeRules.checkFullBindings(
                envelope,
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                fixtures.path("planningInput"),
                future,
                fixtures.path("semanticAction"),
                fixtures.path("goalPlan"),
                fixtures.path("claudeDecision"),
                GameplayV2EnvelopeRules.canonicalSchemaDigest(),
                NOW))
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void tamperedEnvelopeDigestFailsAuthorityBinding() {
    JsonNode tampered = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("fence", 9999L);
    String staleDigest = fixtures.path("expectedDigests").path("executionEnvelope").asText();
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                fixtures.path("authority"),
                fixtures.path("approvedScope"),
                tampered,
                staleDigest,
                tampered.path("authorityId").asText(),
                fixtures.path("testSigningSecret").asText(),
                NOW,
                Set.of()))
        .isEqualTo("AUTHORITY_CHANGED");
  }

  @Test
  void legacyAuthorityOverloadFailsClosed() {
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                fixtures.path("authority"),
                fixtures.path("expectedDigests").path("executionEnvelope").asText(),
                fixtures.path("executionEnvelope").path("authorityId").asText(),
                fixtures.path("testSigningSecret").asText(),
                NOW,
                Set.of()))
        .isEqualTo("AUTHORITY_SCOPE_MISMATCH");
  }

  @Test
  void uncataloguedAdapterFailsClosed() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new com.chromascape.web.gameplay.provider.v2.ClaudeGameplayV2ProviderAdapter(
                        validator)
                    .adapt(
                        fixtures.path("eligibilityResult"),
                        "claude-fixture-v2",
                        fixtures.path("claudeDecision")))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("NODE_CATALOG_MISMATCH");
  }

  @Test
  void mismatchedDecisionSourceIsRejected() {
    JsonNode mismatched = fixtures.path("claudeDecision").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            ((com.fasterxml.jackson.databind.node.ObjectNode) mismatched.path("goalPlan")))
        .put("sourceDecisionId", "other-decision");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> validator.validateWithRules("providerDecision", mismatched))
        .isInstanceOf(GameplayV2ContractValidator.GameplayV2ContractViolation.class)
        .extracting("failureCode")
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void selectedNodeRevisionMismatchIsRejected() {
    JsonNode envelope = fixtures.path("executionEnvelope").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope).put("expectedTaskRevision", 6L);
    assertThat(
            GameplayV2EnvelopeRules.checkBindings(
                envelope,
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                fixtures.path("goalPlan"),
                NOW))
        .isEqualTo("IDENTITY_MISMATCH");
  }

  @Test
  void unmutedEnvelopePassesAllBindingChecks() {
    assertThat(
            GameplayV2EnvelopeRules.checkBindings(
                fixtures.path("executionEnvelope"),
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                fixtures.path("goalPlan"),
                NOW))
        .isNull();
    assertThat(
            GameplayV2EnvelopeRules.checkFullBindings(
                fixtures.path("executionEnvelope"),
                fixtures.path("catalogSnapshot"),
                fixtures.path("semanticObservation"),
                fixtures.path("planningInput"),
                fixtures.path("eligibilityResult"),
                fixtures.path("semanticAction"),
                fixtures.path("goalPlan"),
                fixtures.path("claudeDecision"),
                GameplayV2EnvelopeRules.canonicalSchemaDigest(),
                NOW))
        .isNull();
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                fixtures.path("authority"),
                fixtures.path("approvedScope"),
                fixtures.path("executionEnvelope"),
                fixtures.path("expectedDigests").path("executionEnvelope").asText(),
                fixtures.path("executionEnvelope").path("authorityId").asText(),
                fixtures.path("testSigningSecret").asText(),
                NOW,
                Set.of()))
        .isNull();
  }

  @Test
  void missingAuthorityInputsFailClosedWithoutConsumption() {
    Set<String> consumed = new HashSet<>();
    JsonNode authority = fixtures.path("authority");
    JsonNode scope = fixtures.path("approvedScope");
    JsonNode envelope = fixtures.path("executionEnvelope");
    String digest = fixtures.path("expectedDigests").path("executionEnvelope").asText();
    String authorityId = envelope.path("authorityId").asText();
    String secret = fixtures.path("testSigningSecret").asText();

    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                null, scope, envelope, digest, authorityId, secret, NOW, consumed))
        .isEqualTo("AUTHORITY_SCOPE_MISMATCH");
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                authority, null, envelope, digest, authorityId, secret, NOW, consumed))
        .isEqualTo("AUTHORITY_SCOPE_MISMATCH");
    assertThat(
            GameplayV2EnvelopeRules.checkAuthority(
                authority, scope, null, digest, authorityId, secret, NOW, consumed))
        .isEqualTo("AUTHORITY_SCOPE_MISMATCH");
    assertThat(consumed).isEmpty();
  }

  @Test
  void authoritySignatureIsDeterministicallyVerifiable() throws Exception {
    JsonNode authority = fixtures.path("authority");
    com.fasterxml.jackson.databind.node.ObjectNode unsigned = authority.deepCopy();
    unsigned.remove("signature");
    byte[] canonical = GameplayV2EligibilityFilter.canonicalDigestBytes(objectMapper, unsigned);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(fixtures.path("testSigningSecret").asText().getBytes(), "HmacSHA256"));
    String expected = "hmac-sha256:" + java.util.HexFormat.of().formatHex(mac.doFinal(canonical));
    assertThat(authority.path("signature").asText()).isEqualTo(expected);
  }
}
