package com.chromascape.web.gameplay.provider.v2;

import com.chromascape.web.gameplay.contract.v2.GameplayV2ContractValidator;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.AbstentionReason;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.DecisionKind;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.Provider;
import com.chromascape.web.gameplay.contract.v2.GameplayV2Contracts.ProviderDecision;
import com.chromascape.web.gameplay.contract.v2.GameplayV2EligibilityFilter;
import com.chromascape.web.gameplay.contract.v2.GameplayV2ValidatedEligibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Claude/Codex normalization for SC-1429 v2. Adapters fail closed: they refuse to run before
 * deterministic eligibility, refuse a filtered task, and normalize equivalent provider outputs to
 * the same provider-neutral {@link ProviderDecision}.
 */
public abstract class GameplayV2ProviderAdapter {
  protected final GameplayV2ContractValidator validator;

  /** Creates an adapter bound to the shared v2 contract validator. */
  protected GameplayV2ProviderAdapter(GameplayV2ContractValidator validator) {
    this.validator = validator;
  }

  /** The provider this adapter normalizes output for. */
  public abstract Provider provider();

  /**
   * Uncatalogued adaptation fails closed: catalog allowlists (verifier, transition, capability,
   * budget) cannot be proven without the source catalog, so this overload always rejects. Use
   * {@link #adapt(JsonNode, JsonNode, JsonNode, JsonNode, Instant, String, JsonNode)} with the
   * source catalog, observation, and planning input.
   */
  public final ProviderDecision adapt(
      JsonNode eligibilityResult, String modelVersion, JsonNode providerOutput) {
    throw fail(
        "NODE_CATALOG_MISMATCH",
        "Provider adaptation requires the source catalog; uncatalogued adapt() is closed");
  }

  /**
   * Unreplayed adaptation fails closed: without the observation and planning input the adapter
   * cannot reproduce eligibility, so a fabricated but structurally plausible eligibility result
   * would be accepted. Use {@link #adapt(JsonNode, JsonNode, JsonNode, JsonNode, Instant, String,
   * JsonNode)} or {@link #adapt(GameplayV2ValidatedEligibility, JsonNode, String, JsonNode)}
   * instead.
   *
   * @deprecated Eligibility replay is enforced at the adapter boundary; this overload always fails
   *     closed by design.
   */
  @Deprecated
  public final ProviderDecision adapt(
      JsonNode eligibilityResult, JsonNode catalog, String modelVersion, JsonNode providerOutput) {
    throw fail(
        "PROVIDER_BEFORE_ELIGIBILITY",
        "Provider adaptation requires replayed eligibility; use the"
            + " catalog/observation/planning-input overload");
  }

  /**
   * Replay-bound adaptation. Schema-validates the catalog, observation, planning input and
   * eligibility result, replays deterministic eligibility for the same inputs at the eligibility
   * result's claimed {@code evaluatedAt} (which must be plausible relative to {@code now}) before
   * doing anything else, and binds the seal to the source catalog's canonical digest. A fabricated
   * {@code ELIGIBLE} that the deterministic filter would not have produced is rejected with {@code
   * IDENTITY_MISMATCH}, even when every downstream digest was consistently rebound to match the
   * fabrication. This enforces the production sequence: schema validation, deterministic filter
   * replay, provider adapter, full bindings.
   */
  public final ProviderDecision adapt(
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      Instant now,
      String modelVersion,
      JsonNode providerOutput) {
    validateModelVersion(modelVersion);
    // Snapshot-at-boundary: copy every mutable input once at entry so the seal
    // and the sealed adaptation below observe identical bytes even if the
    // caller mutates the originals concurrently. Nulls are passed through for
    // the seal to reject with its stable code; they are never dereferenced here.
    // Provider output is fail-closed here: null, missing, null-node and
    // non-object outputs can never reach the seal or the sealed overload.
    if (providerOutput == null
        || providerOutput.isMissingNode()
        || providerOutput.isNull()
        || !providerOutput.isObject()) {
      throw fail("INVALID_PROVIDER_DECISION", "Provider output must be a JSON object");
    }
    JsonNode catalogSnapshot = catalog == null ? null : catalog.deepCopy();
    JsonNode observationSnapshot = observation == null ? null : observation.deepCopy();
    JsonNode planningSnapshot = planningInput == null ? null : planningInput.deepCopy();
    JsonNode resultSnapshot = eligibilityResult == null ? null : eligibilityResult.deepCopy();
    final JsonNode providerSnapshot = providerOutput.deepCopy();
    GameplayV2ValidatedEligibility validated =
        GameplayV2ValidatedEligibility.seal(
            validator, catalogSnapshot, observationSnapshot, planningSnapshot, resultSnapshot, now);
    return adapt(validated, catalogSnapshot, modelVersion, providerSnapshot);
  }

  /**
   * Sealed adaptation. Accepts only an eligibility result sealed by deterministic replay (see
   * {@link GameplayV2ValidatedEligibility}), so the adapter boundary cannot be reached with an
   * unreplayed result. The overload stays public because replay and sealed adaptation are split
   * across the contract/adapter package boundary (the replay-bound overload above is the only
   * in-repo producer); the seal's catalog binding is what prevents cross-package bypass, since any
   * substituted catalog is rejected with {@code NODE_CATALOG_MISMATCH} before any other check. The
   * embedded GoalPlan must additionally satisfy per-node task/verifier/transition/settings/budget
   * binding against that same catalog, and graph, ordering, budget and digest rules apply via the
   * contract validator. The catalog is snapshotted (deep-copied) on entry so a concurrent caller
   * mutation cannot slip between the binding check and the later GoalPlan validation.
   */
  public final ProviderDecision adapt(
      GameplayV2ValidatedEligibility validatedEligibility,
      JsonNode catalog,
      String modelVersion,
      JsonNode providerOutput) {
    validateModelVersion(modelVersion);
    if (validatedEligibility == null) {
      throw fail("PROVIDER_BEFORE_ELIGIBILITY", "Provider adaptation requires sealed eligibility");
    }
    if (catalog == null || catalog.isMissingNode() || catalog.isNull() || !catalog.isObject()) {
      throw fail("NODE_CATALOG_MISMATCH", "Provider catalog does not match the sealed catalog");
    }
    // Snapshot-at-boundary: deep-copy the caller-owned catalog and provider
    // output on entry and use those snapshots exclusively for schema
    // validation, provider/model verification, eligible-input digest
    // verification, decision kind/reason, evidence extraction, GoalPlan
    // validation, catalog validation and the returned ProviderDecision,
    // closing the check/use window between them. The snapshots below are the
    // only provider-output and catalog bytes this overload observes; the
    // caller-owned nodes are never retained.
    if (providerOutput == null
        || providerOutput.isMissingNode()
        || providerOutput.isNull()
        || !providerOutput.isObject()) {
      throw fail("INVALID_PROVIDER_DECISION", "Provider output must be a JSON object");
    }
    JsonNode catalogSnapshot = catalog.deepCopy();
    final JsonNode providerSnapshot = providerOutput.deepCopy();
    String catalogBinding = validatedEligibility.checkCatalogBinding(catalogSnapshot);
    if (catalogBinding != null) {
      throw fail(catalogBinding, "Provider catalog does not match the sealed eligibility catalog");
    }
    JsonNode eligibilityResult = validatedEligibility.result();
    validator.validateWithRules("immutableTaskCatalogSnapshot", catalogSnapshot);
    validator.validateWithRules("eligibilityResult", eligibilityResult);
    List<String> eligible = GameplayV2EligibilityFilter.eligibleTaskKeys(eligibilityResult);
    if (eligible.isEmpty()) {
      throw fail("PROVIDER_BEFORE_ELIGIBILITY", "Provider adapter invoked with no eligible tasks");
    }
    validator.validateWithRules("providerDecision", providerSnapshot);
    // The adapter must never silently replace provider identity: the output's
    // declared provider and model must match the invoked adapter.
    String declaredProvider = providerSnapshot.path("provider").asText();
    String declaredModel = providerSnapshot.path("modelVersion").asText();
    if (!provider().name().equals(declaredProvider)) {
      throw fail("PROVIDER_MISMATCH", "Provider output declares " + declaredProvider);
    }
    if (!modelVersion.equals(declaredModel)) {
      throw fail("PROVIDER_MISMATCH", "Provider output declares model " + declaredModel);
    }
    // Provenance: the decision must bind exactly the eligibility result it was given.
    String recomputedEligibility =
        GameplayV2EligibilityFilter.canonicalDigest(new ObjectMapper(), eligibilityResult);
    if (!providerSnapshot.path("eligibleInputDigest").asText().equals(recomputedEligibility)) {
      throw fail(
          "IDENTITY_MISMATCH", "Provider eligibleInputDigest does not match eligibility result");
    }
    String decisionId = requiredText(providerSnapshot, "decisionId");
    String kind = providerSnapshot.path("kind").asText();
    DecisionKind decisionKind;
    try {
      decisionKind = DecisionKind.valueOf(kind);
    } catch (Exception exception) {
      throw fail("INVALID_PROVIDER_DECISION", "Unknown provider decision kind: " + kind);
    }
    List<String> evidence = new ArrayList<>();
    providerSnapshot.path("evidenceRefs").forEach(ref -> evidence.add(ref.asText()));
    if (decisionKind == DecisionKind.ABSTAINED) {
      String reason = providerSnapshot.path("reason").asText();
      if ("ELIGIBLE".equals(reason)) {
        throw fail("INVALID_PROVIDER_DECISION", "ABSTAINED must not claim ELIGIBLE");
      }
      AbstentionReason abstentionReason;
      try {
        abstentionReason = AbstentionReason.valueOf(reason);
      } catch (Exception exception) {
        throw fail("INVALID_PROVIDER_DECISION", "Unknown abstention reason: " + reason);
      }
      if (!providerSnapshot.path("goalPlan").isMissingNode()
          && !providerSnapshot.path("goalPlan").isNull()) {
        throw fail("INVALID_PROVIDER_DECISION", "ABSTAINED decisions must not carry a goalPlan");
      }
      return new ProviderDecision(
          GameplayV2Contracts.VERSION,
          decisionKind,
          provider(),
          declaredModel,
          decisionId,
          providerSnapshot.path("promptId").asText(),
          providerSnapshot.path("policyConfidence").asDouble(),
          providerSnapshot.path("adapterObservationConfidence").asDouble(),
          providerSnapshot.path("knowledgeFreshnessConfidence").asDouble(),
          providerSnapshot.path("eligibleInputDigest").asText(),
          null,
          abstentionReason,
          List.copyOf(evidence));
    }
    // Full provider-decision validation (schema + timestamps + recursive
    // GoalPlan graph/digest rules + decisionId/sourceDecisionId binding) runs
    // before the catalog allowlist. A structurally mutated plan therefore
    // surfaces its structural code (e.g. GOAL_PLAN_DIGEST_MISMATCH); catalog
    // violations on a structurally intact plan surface as NODE_CATALOG_MISMATCH.
    validator.validateWithRules("providerDecision", providerSnapshot);
    JsonNode goalPlan = providerSnapshot.path("goalPlan").deepCopy();
    validator.validateWithRules("goalPlan", goalPlan);
    validator.validateGoalPlanWithCatalog(goalPlan, catalogSnapshot);
    if (!decisionId.equals(goalPlan.path("sourceDecisionId").asText())) {
      throw fail("IDENTITY_MISMATCH", "GoalPlan sourceDecisionId does not match decisionId");
    }
    for (JsonNode node : goalPlan.path("nodes")) {
      if (!eligible.contains(node.path("taskKey").asText())) {
        throw fail(
            "PROVIDER_SELECTED_FILTERED_TASK",
            "Provider attempted to select a deterministically filtered task");
      }
    }
    return new ProviderDecision(
        GameplayV2Contracts.VERSION,
        decisionKind,
        provider(),
        declaredModel,
        decisionId,
        providerSnapshot.path("promptId").asText(),
        providerSnapshot.path("policyConfidence").asDouble(),
        providerSnapshot.path("adapterObservationConfidence").asDouble(),
        providerSnapshot.path("knowledgeFreshnessConfidence").asDouble(),
        providerSnapshot.path("eligibleInputDigest").asText(),
        goalPlan.deepCopy(),
        null,
        List.copyOf(evidence));
  }

  private String requiredText(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw fail("INVALID_PROVIDER_DECISION", field + " is required");
    }
    return value;
  }

  private static void validateModelVersion(String modelVersion) {
    if (modelVersion == null || !modelVersion.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
      throw fail("INVALID_PROVIDER_DECISION", "modelVersion must be a schema token");
    }
  }

  private static IllegalArgumentException fail(String code, String message) {
    return new GameplayV2ContractValidator.GameplayV2ContractViolation(code, message);
  }
}
