package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.contract.v2.GameplayV2BootstrapPolicy;
import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EnvelopeRules;
import com.chromascape.web.gameplay.contract.v2.GameplayV2ValidatedEligibility;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FrozenV2Bundle;
import com.chromascape.web.gameplay.provider.v2.ClaudeGameplayV2ProviderAdapter;
import com.chromascape.web.gameplay.provider.v2.CodexGameplayV2ProviderAdapter;
import com.chromascape.web.gameplay.provider.v2.GameplayV2ProviderAdapter;
import com.chromascape.web.gameplay.provider.v2.MistralGameplayV2ProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Assembles the complete SC-1429/v2 bootstrap bundle and freezes its exact bytes.
 *
 * <p>The inputs are already contract-shaped and come from durable or attested sources: the catalog
 * snapshot and semantic observation from Micro6, the planning input and eligibility result from the
 * deterministic filter, and the provider decision from a persisted v2 turn. Nothing here invents a
 * value: every digest is recomputed from the bytes it names, eligibility is sealed by replay before
 * the provider decision is accepted, and the resulting bundle is validated with the same rules
 * Micro6 applies before it is frozen.
 *
 * <p>The frozen bytes are what the operator previews, what the human approves and what the gateway
 * transmits. They are never rebuilt from mutable domain objects afterwards.
 */
@Component
public final class BootstrapV2BundleAssembler {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final GameplayV2ContractValidator validator;
  private final String signingSecret;

  public BootstrapV2BundleAssembler(
      GameplayV2ContractValidator validator,
      @Value("${gameplay.bootstrap.authority-hmac-secret:}") String signingSecret) {
    this.validator = validator;
    this.signingSecret = signingSecret == null ? "" : signingSecret;
  }

  /**
   * Assembles and freezes one bundle.
   *
   * @throws GameplayV2ContractValidator.GameplayV2ContractViolation when any input fails schema
   *     validation, eligibility replay, provider adaptation, full binding or authority checking.
   */
  public Assembled assemble(Draft draft) {
    if (draft == null) {
      throw violation("INVALID_BUNDLE", "A complete draft is required");
    }
    if (signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw violation(
          "AUTHORITY_SIGNING_UNAVAILABLE",
          "The bootstrap authority signing secret is not configured");
    }
    JsonNode catalog = copy(draft.catalogSnapshot());
    JsonNode observation = copy(draft.semanticObservation());
    JsonNode planning = copy(draft.planningInput());
    JsonNode eligibility = copy(draft.eligibilityResult());
    JsonNode providerDecision = copy(draft.providerDecision());
    JsonNode identities = copy(draft.identities());
    JsonNode budgets = copy(draft.budgets());

    validator.validateWithRules("immutableTaskCatalogSnapshot", catalog);
    validator.validateWithRules("semanticObservation", observation);
    validator.validateWithRules("planningInput", planning);
    validator.validateWithRules("eligibilityResult", eligibility);
    validator.validateWithRules("providerDecision", providerDecision);

    // Eligibility is sealed by replay before the provider decision is accepted, so a fabricated
    // ELIGIBLE that the deterministic filter would not have produced is refused here.
    GameplayV2ValidatedEligibility sealed =
        GameplayV2ValidatedEligibility.seal(
            validator, catalog, observation, planning, eligibility, draft.now());

    String provider = providerDecision.path("provider").asText();
    GameplayV2ProviderAdapter adapter =
        switch (provider) {
          case "CLAUDE" -> new ClaudeGameplayV2ProviderAdapter(validator);
          case "CODEX" -> new CodexGameplayV2ProviderAdapter(validator);
          case "MISTRAL" -> new MistralGameplayV2ProviderAdapter(validator);
          default -> throw violation("INVALID_BUNDLE", "Provider is not allowlisted");
        };
    var decision =
        adapter.adapt(
            sealed,
            catalog,
            providerDecision.path("modelVersion").asText(),
            providerDecision);
    if (decision.goalPlan() == null) {
      throw violation("INVALID_PROVIDER_DECISION", "Only SELECTED decisions carry a GoalPlan");
    }
    JsonNode goalPlan = decision.goalPlan().deepCopy();
    JsonNode head = goalPlan.path("nodes").path(0);
    JsonNode semanticAction = semanticAction(head, decision.decisionId());

    String catalogDigest = catalogDigest(catalog);
    String observationDigest = canonical(observation);
    String planningDigest = canonical(planning);
    String eligibilityDigest = canonical(eligibility);
    String actionDigest = canonical(semanticAction);
    String goalPlanDigest = goalPlan.path("goalPlanDigest").asText();

    ObjectNode envelope = MAPPER.createObjectNode();
    envelope.put("contractVersion", "sc-1429/v2");
    envelope.put("mode", "LIVE_TASK");
    envelope.put("provider", provider);
    envelope.put("modelVersion", decision.modelVersion());
    envelope.put("decisionId", decision.decisionId());
    envelope.put("promptId", decision.promptId());
    envelope.put("requestId", draft.requestId());
    envelope.put("idempotencyKey", draft.idempotencyKey());
    envelope.put("goalPlanDigest", goalPlanDigest);
    var taskSkillDigests = envelope.putArray("taskSkillDigests");
    head.path("taskSkillRefs")
        .forEach(ref -> taskSkillDigests.add(ref.path("artifactDigest").asText()));
    envelope.put("taskKey", head.path("taskKey").asText());
    envelope.set("settings", head.path("settings").deepCopy());
    envelope.set("identities", identities.deepCopy());
    envelope.put("catalogDigest", catalogDigest);
    envelope.put("catalogRevision", catalog.path("catalogRevision").asLong());
    envelope.put(
        "capabilitySnapshotDigest", catalog.path("capabilitySnapshotDigest").asText());
    envelope.put("schemaDigest", GameplayV2EnvelopeRules.canonicalSchemaDigest());
    envelope.put("policyDigest", observation.path("policyDigest").asText());
    envelope.put("knowledgeDigest", observation.path("knowledgeDigest").asText());
    envelope.put("planningInputDigest", planningDigest);
    envelope.put("eligibilityResultDigest", eligibilityDigest);
    envelope.put("semanticActionDigest", actionDigest);
    envelope.put("expectedTransition", head.path("expectedTransition").asText());
    envelope.put("verifierId", head.path("verifierId").asText());
    envelope.put("executorArtifactId", catalog.path("executorArtifactId").asText());
    envelope.put("pluginId", catalog.path("pluginId").asText());
    envelope.put("buildId", catalog.path("executorBuildId").asText());
    envelope.put("expectedObservationRevision", head.path("expectedObservationRevision").asLong());
    envelope.put("expectedTaskRevision", head.path("expectedTaskRevision").asLong());
    envelope.put("leaseGeneration", draft.leaseGeneration());
    envelope.put("fence", draft.fence());
    envelope.set("budgets", budgets.deepCopy());
    envelope.put("deadline", draft.deadline().toString());
    envelope.put("killSwitchScope", "RUN");
    envelope.put("authorityId", draft.authorityId());

    ObjectNode approvedScope = MAPPER.createObjectNode();
    approvedScope.put("contractVersion", "sc-1429/v2");
    approvedScope.put("mode", "LIVE_TASK");
    approvedScope.put("provider", provider);
    approvedScope.put("modelVersion", decision.modelVersion());
    approvedScope.put("decisionId", decision.decisionId());
    approvedScope.set("identities", identities.deepCopy());
    approvedScope.put("taskKey", head.path("taskKey").asText());
    approvedScope.set("settings", head.path("settings").deepCopy());
    approvedScope.set("budgets", budgets.deepCopy());
    approvedScope.put("semanticActionDigest", actionDigest);
    approvedScope.put("expectedTransition", head.path("expectedTransition").asText());
    approvedScope.put("verifierId", head.path("verifierId").asText());
    approvedScope.put("deadline", draft.deadline().toString());
    approvedScope.put("killSwitchScope", "RUN");

    String envelopeDigest = canonical(envelope);
    String scopeDigest = canonical(approvedScope);
    ObjectNode authority = authority(draft, envelopeDigest, scopeDigest);

    validator.validateWithRules("goalPlan", goalPlan);
    validator.validateWithRules("semanticAction", semanticAction);
    validator.validateWithRules("executionEnvelope", envelope);
    validator.validateWithRules("approvedScope", approvedScope);
    validator.validateWithRules("authority", authority);

    String binding =
        GameplayV2EnvelopeRules.checkFullBindings(
            envelope,
            catalog,
            observation,
            planning,
            eligibility,
            semanticAction,
            goalPlan,
            providerDecision,
            GameplayV2EnvelopeRules.canonicalSchemaDigest(),
            draft.now());
    if (binding != null) {
      throw violation(binding, "Full envelope binding failed: " + binding);
    }
    String authorityCode =
        GameplayV2EnvelopeRules.checkAuthority(
            authority,
            approvedScope,
            envelope,
            envelopeDigest,
            draft.authorityId(),
            signingSecret,
            draft.now(),
            Set.of());
    if (authorityCode != null) {
      throw violation(authorityCode, "Authority binding failed: " + authorityCode);
    }

    Map<String, JsonNode> members = new LinkedHashMap<>();
    members.put("executionEnvelope", envelope);
    members.put("catalogSnapshot", catalog);
    members.put("semanticObservation", observation);
    members.put("planningInput", planning);
    members.put("eligibilityResult", eligibility);
    members.put("providerDecision", providerDecision);
    members.put("goalPlan", goalPlan);
    members.put("semanticAction", semanticAction);
    members.put("approvedScope", approvedScope);
    members.put("authority", authority);
    byte[] bytes = canonicalBytes(members);

    FrozenV2Bundle frozen =
        new FrozenV2Bundle(
            bytes,
            "sha256:" + sha256Hex(bytes),
            envelopeDigest,
            scopeDigest,
            catalogDigest,
            observationDigest,
            planningDigest,
            eligibilityDigest,
            goalPlanDigest,
            actionDigest,
            draft.authorityId(),
            draft.requestId(),
            draft.idempotencyKey(),
            draft.deadline());
    return new Assembled(frozen, members, approvedScope, envelope, draft.now());
  }

  /** The semantic action the selected head node authorises. */
  private JsonNode semanticAction(JsonNode head, String decisionId) {
    ObjectNode action = MAPPER.createObjectNode();
    action.put("kind", "START_FISHING_TASK");
    action.put("taskKey", head.path("taskKey").asText());
    action.set("settings", head.path("settings").deepCopy());
    action.put("expectedTransition", head.path("expectedTransition").asText());
    action.put("verifierId", head.path("verifierId").asText());
    action.put("decisionId", decisionId);
    return action;
  }

  /** Signed human authority over the exact envelope and approved scope. */
  private ObjectNode authority(Draft draft, String envelopeDigest, String scopeDigest) {
    ObjectNode authority = MAPPER.createObjectNode();
    authority.put("contractVersion", "sc-1429/v2");
    authority.put("authorityId", draft.authorityId());
    authority.put("actorType", "HUMAN");
    authority.put("approvedBy", draft.approvedBy());
    authority.put("approvedAt", draft.approvedAt().toString());
    authority.put("expiresAt", draft.approvalExpiresAt().toString());
    authority.put("envelopeDigest", envelopeDigest);
    authority.put("scopeDigest", scopeDigest);
    authority.put("oneUse", true);
    authority.put("signature", signature(authority));
    return authority;
  }

  private String signature(JsonNode authority) {
    try {
      ObjectNode unsigned = authority.deepCopy();
      unsigned.remove("signature");
      byte[] canonical = GameplayV2EligibilityFilter.canonicalDigestBytes(MAPPER, unsigned);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(canonical));
    } catch (Exception exception) {
      throw violation("AUTHORITY_SIGNING_UNAVAILABLE", "Authority could not be signed");
    }
  }

  private String canonical(JsonNode node) {
    return GameplayV2EligibilityFilter.canonicalDigest(MAPPER, node);
  }

  /** The catalog's own digest: the canonical digest of the object without its digest member. */
  private String catalogDigest(JsonNode catalog) {
    ObjectNode copy = catalog.deepCopy();
    copy.remove("catalogDigest");
    return canonical(copy);
  }

  private byte[] canonicalBytes(Map<String, JsonNode> members) {
    ObjectNode bundle = MAPPER.createObjectNode();
    members.forEach(bundle::set);
    return GameplayV2EligibilityFilter.canonicalDigestBytes(MAPPER, bundle);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static JsonNode copy(JsonNode node) {
    if (node == null) {
      throw violation("INVALID_BUNDLE", "Every draft member is required");
    }
    return node.deepCopy();
  }

  private static GameplayV2ContractValidator.GameplayV2ContractViolation violation(
      String code, String message) {
    return new GameplayV2ContractValidator.GameplayV2ContractViolation(code, message);
  }

  /**
   * The contract-shaped inputs of one draft. Every member is already validated against its source
   * and carries the digests it claims; the assembler recomputes them all.
   */
  public record Draft(
      JsonNode catalogSnapshot,
      JsonNode semanticObservation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      JsonNode providerDecision,
      JsonNode identities,
      JsonNode budgets,
      Instant deadline,
      String requestId,
      String idempotencyKey,
      String authorityId,
      String approvedBy,
      Instant approvedAt,
      Instant approvalExpiresAt,
      long leaseGeneration,
      long fence,
      Instant now) {}

  /** The frozen bundle plus the exact objects that were validated, for operator preview. */
  public record Assembled(
      FrozenV2Bundle frozen,
      Map<String, JsonNode> members,
      JsonNode approvedScope,
      JsonNode executionEnvelope,
      Instant assembledAt) {
    public Assembled {
      members = Map.copyOf(members);
    }

    /** The policy digest both the observation and the planning input must carry. */
    public static String policyDigest() {
      return GameplayV2BootstrapPolicy.digest(MAPPER);
    }
  }
}
