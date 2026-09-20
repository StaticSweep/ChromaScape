package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayCorrelation;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.GatewayReceipt;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic, cryptographically valid data used by committed PostgreSQL proofs. */
final class AccountBuilderPostgresTestFixtures {
  static final Instant NOW = Instant.parse("2026-08-31T12:05:00Z");
  static final String SECRET = "sc1431-test-authority-secret-32-bytes";
  static final java.time.Clock CLOCK = java.time.Clock.fixed(NOW, ZoneOffset.UTC);
  static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();
  static final CanonicalGameplayJson CANONICAL_JSON = new CanonicalGameplayJson(OBJECT_MAPPER);

  private AccountBuilderPostgresTestFixtures() {}

  static OrchestrationRequest request(String suffix) {
    return request(
        suffix,
        Mode.LIVE_TASK,
        "orchestration-" + suffix,
        "request-" + suffix,
        "idempotency-" + suffix,
        "authority-" + suffix);
  }

  static OrchestrationRequest request(String suffix, Mode mode) {
    return request(
        suffix,
        mode,
        "orchestration-" + suffix,
        "request-" + suffix,
        "idempotency-" + suffix,
        "authority-" + suffix);
  }

  /** Returns a structurally valid request with a wall-clock deadline for real HTTP tests. */
  static OrchestrationRequest freshRequest(String suffix, Mode mode) {
    OrchestrationRequest base = request(suffix, mode);
    Instant deadline = Instant.now().plusSeconds(600);
    ExecutionEnvelope envelope = base.envelope();
    var action = base.decision().semanticAction().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) action)
        .set("settings", envelope.settings().deepCopy());
    ProviderDecision decision =
        new ProviderDecision(
            base.decision().contractVersion(),
            base.decision().provider(),
            base.decision().modelVersion(),
            base.decision().decisionId(),
            base.decision().policyConfidence(),
            action,
            base.decision().evidenceRefs());
    ExecutionEnvelope freshEnvelope =
        new ExecutionEnvelope(
            envelope.contractVersion(),
            envelope.provider(),
            envelope.modelVersion(),
            envelope.decisionId(),
            envelope.requestId(),
            envelope.idempotencyKey(),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.taskKey(),
            envelope.settings(),
            envelope.identities(),
            envelope.catalogDigest(),
            envelope.schemaDigest(),
            envelope.policyDigest(),
            envelope.knowledgeDigest(),
            CANONICAL_JSON.digest(action),
            envelope.expectedTransition(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision(),
            envelope.authorityId(),
            envelope.leaseGeneration(),
            envelope.fence(),
            envelope.budgets(),
            deadline,
            envelope.killSwitchScope());
    SnapshotBindings bindings =
        new SnapshotBindings(
            CANONICAL_JSON.digest(decision),
            CANONICAL_JSON.digest(action),
            base.bindings().catalogDigest(),
            base.bindings().capabilityDigest(),
            base.bindings().goalPlanDigest(),
            base.bindings().taskSkillDigests(),
            base.bindings().toolSchemaDigest(),
            base.bindings().knowledgeDigest(),
            base.bindings().policyDigest(),
            base.bindings().verifierDigest(),
            base.bindings().buildId(),
            base.bindings().expectedObservationRevision(),
            base.bindings().expectedTaskRevision());
    return withFingerprint(
        new OrchestrationRequest(
            base.orchestrationId(),
            base.requestId(),
            base.idempotencyKey(),
            null,
            base.mode(),
            base.sourceType(),
            decision,
            freshEnvelope,
            bindings,
            base.authority(),
            base.observation(),
            deadline));
  }

  static OrchestrationRequest request(
      String suffix,
      Mode mode,
      String orchestrationId,
      String requestId,
      String idempotencyKey,
      String authorityId) {
    var action =
        JsonNodeFactory.instance
            .objectNode()
            .put("taskKey", "FISHING_TRAINING")
            .put("expectedTransition", "FISHING_XP_INCREASED")
            .put("verifierId", "verifier-v1")
            .put("kind", "START_FISHING_TASK");
    var settings = JsonNodeFactory.instance.objectNode().put("secret-setting", "must-not-persist");
    var identities = JsonNodeFactory.instance.objectNode().put("runId", "run-" + suffix);
    var budgets = JsonNodeFactory.instance.objectNode().put("actions", 1);
    ExecutionEnvelope envelope =
        new ExecutionEnvelope(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            "model-v1",
            "decision-" + suffix,
            requestId,
            idempotencyKey,
            digest("goal-plan"),
            List.of(digest("task-skill")),
            "FISHING_TRAINING",
            settings,
            identities,
            digest("catalog"),
            digest("tool-schema"),
            digest("policy"),
            digest("knowledge"),
            CANONICAL_JSON.digest(action),
            "FISHING_XP_INCREASED",
            "verifier-v1",
            "build-v1",
            1,
            1,
            authorityId,
            1,
            1,
            budgets,
            NOW.plusSeconds(300),
            "RUN");
    ProviderDecision decision =
        new ProviderDecision(
            GameplayContracts.VERSION,
            Provider.CLAUDE,
            envelope.modelVersion(),
            envelope.decisionId(),
            0.9,
            action,
            List.of("observation:" + suffix));
    SnapshotBindings bindings =
        new SnapshotBindings(
            CANONICAL_JSON.digest(decision),
            CANONICAL_JSON.digest(action),
            envelope.catalogDigest(),
            digest("capability"),
            envelope.goalPlanDigest(),
            envelope.taskSkillDigests(),
            envelope.schemaDigest(),
            envelope.knowledgeDigest(),
            envelope.policyDigest(),
            envelope.verifierId(),
            envelope.buildId(),
            envelope.expectedObservationRevision(),
            envelope.expectedTaskRevision());
    HumanAuthority authority = signedAuthority(envelope);
    var observation =
        JsonNodeFactory.instance
            .objectNode()
            .put("observationId", "observation-" + suffix)
            .put("revision", 1)
            .put("catalogDigest", envelope.catalogDigest())
            .put("buildId", envelope.buildId());
    observation.set("identities", identities.deepCopy());
    observation.set("taskState", JsonNodeFactory.instance.objectNode().put("revision", 1));
    OrchestrationRequest withoutFingerprint =
        new OrchestrationRequest(
            orchestrationId,
            requestId,
            idempotencyKey,
            null,
            mode,
            "TEST",
            decision,
            envelope,
            bindings,
            authority,
            observation,
            envelope.deadline());
    return withFingerprint(withoutFingerprint);
  }

  static OrchestrationRequest withAuthority(
      OrchestrationRequest request, HumanAuthority authority) {
    return withFingerprint(
        new OrchestrationRequest(
            request.orchestrationId(),
            request.requestId(),
            request.idempotencyKey(),
            null,
            request.mode(),
            request.sourceType(),
            request.decision(),
            request.envelope(),
            request.bindings(),
            authority,
            request.observation(),
            request.deadline()));
  }

  static HumanAuthority authorityFor(
      ExecutionEnvelope envelope,
      String authorityId,
      String approvedBy,
      String scopeDigest,
      Instant approvedAt,
      Instant expiresAt,
      String signature) {
    return new HumanAuthority(
        GameplayContracts.VERSION,
        authorityId,
        "HUMAN",
        approvedBy,
        approvedAt,
        expiresAt,
        CANONICAL_JSON.digest(envelope),
        scopeDigest,
        true,
        signature);
  }

  static HumanAuthority signedAuthority(ExecutionEnvelope envelope) {
    Instant approvedAt = NOW.minusSeconds(30);
    Instant expiresAt = NOW.plusSeconds(240);
    String scopeDigest = CANONICAL_JSON.digest(exactScope(envelope));
    HumanAuthority unsigned =
        authorityFor(
            envelope,
            envelope.authorityId(),
            "test-operator",
            scopeDigest,
            approvedAt,
            expiresAt,
            "hmac-sha256:" + "0".repeat(64));
    return authorityFor(
        envelope,
        unsigned.authorityId(),
        unsigned.approvedBy(),
        unsigned.scopeDigest(),
        unsigned.approvedAt(),
        unsigned.expiresAt(),
        "hmac-sha256:" + hmac(unsigned));
  }

  static GatewayReceipt receipt(
      OrchestrationRequest request,
      String phase,
      State state,
      FailureCode failureCode,
      Lease lease,
      boolean acknowledged,
      boolean reconciled) {
    return new GatewayReceipt(
        request.requestId(),
        CANONICAL_JSON.digest(request.envelope()),
        phase,
        state,
        failureCode,
        acknowledged,
        reconciled,
        lease,
        List.of("fixture:" + phase.toLowerCase()),
        NOW);
  }

  static GatewayReceipt correlationReceipt(
      GatewayCorrelation correlation,
      String phase,
      State state,
      FailureCode failureCode,
      Lease lease,
      boolean acknowledged,
      boolean reconciled) {
    return new GatewayReceipt(
        correlation.requestId(),
        correlation.envelopeDigest(),
        phase,
        state,
        failureCode,
        acknowledged,
        reconciled,
        lease,
        List.of("fixture:" + phase.toLowerCase()),
        NOW);
  }

  static Lease lease(OrchestrationRequest request, String suffix) {
    return new Lease(
        "reservation-" + suffix,
        request.envelope().leaseGeneration(),
        request.envelope().fence(),
        digest("fence-token-" + suffix),
        NOW.plusSeconds(120));
  }

  static String envelopeDigest(OrchestrationRequest request) {
    return CANONICAL_JSON.digest(request.envelope());
  }

  static String digest(String value) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static OrchestrationRequest withFingerprint(OrchestrationRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", AccountBuilderOrchestrationModels.VERSION);
    values.put("orchestrationId", request.orchestrationId());
    values.put("requestId", request.requestId());
    values.put("idempotencyKey", request.idempotencyKey());
    values.put("mode", request.mode());
    values.put("sourceType", request.sourceType());
    values.put("decision", request.decision());
    values.put("envelope", request.envelope());
    values.put("bindings", request.bindings());
    values.put("authority", request.authority());
    values.put("observation", request.observation());
    values.put("deadline", request.deadline());
    String fingerprint = CANONICAL_JSON.digest(values);
    return new OrchestrationRequest(
        request.orchestrationId(),
        request.requestId(),
        request.idempotencyKey(),
        fingerprint,
        request.mode(),
        request.sourceType(),
        request.decision(),
        request.envelope(),
        request.bindings(),
        request.authority(),
        request.observation(),
        request.deadline());
  }

  private static Map<String, Object> exactScope(ExecutionEnvelope envelope) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("contractVersion", envelope.contractVersion());
    scope.put("provider", envelope.provider());
    scope.put("modelVersion", envelope.modelVersion());
    scope.put("identities", envelope.identities().deepCopy());
    scope.put("taskKey", envelope.taskKey());
    scope.put("settings", envelope.settings().deepCopy());
    scope.put("budgets", envelope.budgets().deepCopy());
    scope.put("semanticActionDigest", envelope.semanticActionDigest());
    scope.put("expectedTransition", envelope.expectedTransition());
    scope.put("verifierId", envelope.verifierId());
    scope.put("deadline", envelope.deadline());
    scope.put("killSwitchScope", envelope.killSwitchScope());
    return Map.copyOf(scope);
  }

  private static String hmac(HumanAuthority authority) {
    try {
      Map<String, Object> payload =
          Map.of(
              "contractVersion",
              authority.contractVersion(),
              "authorityId",
              authority.authorityId(),
              "actorType",
              authority.actorType(),
              "approvedBy",
              authority.approvedBy(),
              "approvedAt",
              authority.approvedAt(),
              "expiresAt",
              authority.expiresAt(),
              "envelopeDigest",
              authority.envelopeDigest(),
              "scopeDigest",
              authority.scopeDigest(),
              "oneUse",
              authority.oneUse());
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(CANONICAL_JSON.canonicalBytes(payload)));
    } catch (GeneralSecurityException exception) {
      throw new AssertionError(exception);
    }
  }
}
