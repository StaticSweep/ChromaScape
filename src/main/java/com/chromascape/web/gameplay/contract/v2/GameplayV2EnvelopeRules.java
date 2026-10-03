package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure binding, authority, and idempotency rules for SC-1429 v2 envelopes. These checks run after
 * schema validation; every rejection returns the same stable failure code used by the v2 golden
 * fixture corpus and the Micro6 conformance validator.
 */
public final class GameplayV2EnvelopeRules {
  private GameplayV2EnvelopeRules() {}

  private static final String SCHEMA_RESOURCE =
      "contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json";

  /**
   * Independently derived schema digest: raw SHA-256 of the canonical schema resource bytes.
   * Envelope validation always compares against this value; any caller-supplied digest is treated
   * as an untrusted hint and must also equal it. Self-reported envelope digests alone never satisfy
   * the check.
   */
  public static String canonicalSchemaDigest() {
    try (InputStream input =
        GameplayV2EnvelopeRules.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Missing " + SCHEMA_RESOURCE);
      }
      byte[] bytes = input.readAllBytes();
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to hash SC-1429 v2 schema", exception);
    }
  }

  /**
   * Cross-object binding checks between an envelope and the catalog, observation, and GoalPlan it
   * claims. Returns {@code null} when every binding holds, otherwise a stable failure code. Digests
   * are recomputed from source bytes; self-reported digests alone never satisfy a check.
   */
  public static String checkBindings(
      JsonNode envelope, JsonNode catalog, JsonNode observation, JsonNode goalPlan, Instant now) {
    if (!Instant.parse(envelope.path("deadline").asText()).isAfter(now)) {
      return "DEADLINE_EXPIRED";
    }
    String mode = envelope.path("mode").asText();
    JsonNode authorityId = envelope.path("authorityId");
    if ("LIVE_TASK".equals(mode) && authorityId.isNull()) {
      return "AUTHORITY_MISSING";
    }
    if (!"LIVE_TASK".equals(mode) && !authorityId.isNull()) {
      return "AUTHORITY_FORBIDDEN";
    }
    String environment = envelope.path("identities").path("environmentId").asText("").toUpperCase();
    if ("LIVE_TASK".equals(mode)
        && (environment.contains("SIM") || environment.contains("XRSPS"))) {
      return "AUTHORITY_SOURCE_FORBIDDEN";
    }
    // Recomputed catalog digest must match both the catalog declaration and the envelope copy.
    // A mutated catalog that retains its old self-reported digest cannot pass.
    String recomputedCatalog = catalogDigestOf(catalog);
    if (!recomputedCatalog.equals(catalog.path("catalogDigest").asText())
        || !envelope.path("catalogDigest").asText().equals(recomputedCatalog)) {
      return "IDENTITY_MISMATCH";
    }
    // Recomputed GoalPlan digest must match both the plan declaration and the envelope copy.
    String recomputedPlan = goalPlanDigestOf(goalPlan);
    if (!recomputedPlan.equals(goalPlan.path("goalPlanDigest").asText())
        || !envelope.path("goalPlanDigest").asText().equals(recomputedPlan)) {
      return "IDENTITY_MISMATCH";
    }
    if (!envelope.path("catalogDigest").asText().equals(catalog.path("catalogDigest").asText())
        || !envelope
            .path("capabilitySnapshotDigest")
            .asText()
            .equals(catalog.path("capabilitySnapshotDigest").asText())
        || !envelope.path("policyDigest").asText().equals(observation.path("policyDigest").asText())
        || !envelope
            .path("knowledgeDigest")
            .asText()
            .equals(observation.path("knowledgeDigest").asText())
        || envelope.path("expectedObservationRevision").asLong()
            != observation.path("revision").asLong()
        || envelope.path("expectedTaskRevision").asLong()
            != observation.path("taskState").path("revision").asLong()) {
      return "IDENTITY_MISMATCH";
    }
    // Capability snapshot must provably represent the concrete immutable snapshot,
    // not merely agree across carriers.
    String pinnedCapabilities =
        GameplayV2EligibilityFilter.capabilitySnapshotDigest(new ObjectMapper());
    if (!envelope.path("capabilitySnapshotDigest").asText().equals(pinnedCapabilities)
        || !catalog.path("capabilitySnapshotDigest").asText().equals(pinnedCapabilities)
        || !observation.path("capabilitySnapshotDigest").asText().equals(pinnedCapabilities)) {
      return "IDENTITY_MISMATCH";
    }
    // Catalog revision must agree between envelope, catalog, and observation.
    if (envelope.path("catalogRevision").asLong(-1) != catalog.path("catalogRevision").asLong(-2)
        || observation.path("catalogRevision").asLong(-3)
            != catalog.path("catalogRevision").asLong(-2)) {
      return "IDENTITY_MISMATCH";
    }
    // Envelope mode and identities must match the observation they claim.
    if (!envelope.path("mode").asText().equals(observation.path("mode").asText())
        || !canonicalOf(envelope.path("identities"))
            .equals(canonicalOf(observation.path("identities")))) {
      return "IDENTITY_MISMATCH";
    }
    JsonNode selectedNode = selectedNodeOf(goalPlan);
    if (selectedNode.isMissingNode() || selectedNode.isNull()) {
      return "IDENTITY_MISMATCH";
    }
    JsonNode taskSkillDigests = envelope.path("taskSkillDigests");
    JsonNode taskSkillRefs = selectedNode.path("taskSkillRefs");
    if (taskSkillDigests.size() != taskSkillRefs.size()) {
      return "IDENTITY_MISMATCH";
    }
    for (int i = 0; i < taskSkillDigests.size(); i++) {
      if (!taskSkillDigests
          .path(i)
          .asText()
          .equals(taskSkillRefs.path(i).path("artifactDigest").asText())) {
        return "IDENTITY_MISMATCH";
      }
    }
    if (!envelope.path("verifierId").asText().equals(selectedNode.path("verifierId").asText())) {
      return "VERIFIER_MISMATCH";
    }
    // The selected node's expected revisions are the dispatch identity: the envelope
    // must execute exactly the node the plan selected (nodes[0] with ordinal 0), not a
    // stale revision. The node ID itself is a stable token per the contract; no literal
    // ID value is required here.
    if (envelope.path("expectedObservationRevision").asLong()
            != selectedNode.path("expectedObservationRevision").asLong()
        || envelope.path("expectedTaskRevision").asLong()
            != selectedNode.path("expectedTaskRevision").asLong()
        || selectedNode.path("ordinal").asInt() != 0) {
      return "IDENTITY_MISMATCH";
    }
    return null;
  }

  /**
   * Selected-node identity for v2 dispatch. Envelopes can only dispatch the head node ({@code
   * nodes[0]} with {@code ordinal=0}); multi-node plans describe fallback/prerequisite structure,
   * not parallel dispatch targets. The head node's ID is a stable token constrained by the schema,
   * not a literal contract value.
   */
  static JsonNode selectedNodeOf(JsonNode goalPlan) {
    return goalPlan.path("nodes").path(0);
  }

  /**
   * Full envelope binding across every v2 source object. In addition to {@link #checkBindings},
   * this verifies planning-input, eligibility-result, semantic-action, schema, provider-decision,
   * mode/identity/settings/task/deadline/budget/artifact bindings and catalog revision agreement.
   * The supplied eligibility result must also be exactly what the deterministic filter recomputes
   * for the same inputs at the claimed evaluatedAt (which must itself be plausible), so a
   * fabricated ELIGIBLE with consistently rebound digests is still rejected. The envelope must
   * carry the recomputed {@code planningInputDigest} and {@code eligibilityResultDigest}; a
   * mutually altered collection of planning, eligibility, decision and action objects that no
   * longer represents the originally approved chain is rejected. Returns {@code null} when every
   * binding holds, otherwise a stable failure code.
   */
  public static String checkFullBindings(
      JsonNode envelope,
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      JsonNode semanticAction,
      JsonNode goalPlan,
      JsonNode providerDecision,
      String expectedSchemaDigest,
      Instant now) {
    String base = checkBindings(envelope, catalog, observation, goalPlan, now);
    if (base != null) {
      return base;
    }
    // Deterministic eligibility recomputation: the supplied result must be
    // exactly what the filter produces for this catalog, observation and
    // planning input at the claimed evaluatedAt. A fabricated ELIGIBLE for a
    // stale or incomplete observation is rejected even when every downstream
    // digest was recomputed to match the fabrication.
    String eligibilityReplay =
        checkEligibilityReplay(catalog, observation, planningInput, eligibilityResult, now);
    if (eligibilityReplay != null) {
      return eligibilityReplay;
    }
    // Observation digest must be recomputed; planning and eligibility copies must match it.
    String recomputedObservation = canonicalOf(observation);
    if (!planningInput.path("observationDigest").asText().equals(recomputedObservation)
        || !eligibilityResult.path("observationDigest").asText().equals(recomputedObservation)) {
      return "IDENTITY_MISMATCH";
    }
    // Planning-input and eligibility-result digests must be recomputed; the envelope
    // must bind both. Mutating any planning or eligibility field without updating the
    // envelope is rejected even when the mutated objects remain mutually consistent.
    String recomputedPlanning = canonicalOf(planningInput);
    if (!envelope.path("planningInputDigest").asText().equals(recomputedPlanning)) {
      return "IDENTITY_MISMATCH";
    }
    // Eligibility-result digest must be recomputed; the envelope and the provider
    // decision must both bind to it.
    String recomputedEligibility = canonicalOf(eligibilityResult);
    if (!envelope.path("eligibilityResultDigest").asText().equals(recomputedEligibility)
        || !providerDecision.path("eligibleInputDigest").asText().equals(recomputedEligibility)) {
      return "IDENTITY_MISMATCH";
    }
    // Semantic-action bytes must be recomputed; the envelope copy must match them.
    String recomputedAction = canonicalOf(semanticAction);
    if (!envelope.path("semanticActionDigest").asText().equals(recomputedAction)) {
      return "IDENTITY_MISMATCH";
    }
    // Schema bytes binding. The pinned resource hash is authoritative: a caller-supplied
    // digest that disagrees with it is rejected, and the envelope must always match it.
    // Passing the envelope's own digest back as the expected value cannot satisfy this
    // check unless it already equals the independently derived resource hash.
    String pinnedSchema = canonicalSchemaDigest();
    if (expectedSchemaDigest != null && !expectedSchemaDigest.equals(pinnedSchema)) {
      return "IDENTITY_MISMATCH";
    }
    if (!envelope.path("schemaDigest").asText().equals(pinnedSchema)) {
      return "IDENTITY_MISMATCH";
    }
    // Provider decision binding: envelope must carry the decision it claims, including
    // the provider prompt provenance.
    if (!envelope.path("decisionId").asText().equals(providerDecision.path("decisionId").asText())
        || !envelope.path("provider").asText().equals(providerDecision.path("provider").asText())
        || !envelope
            .path("modelVersion")
            .asText()
            .equals(providerDecision.path("modelVersion").asText())
        || !envelope.path("promptId").asText().equals(providerDecision.path("promptId").asText())
        || !goalPlan
            .path("sourceDecisionId")
            .asText()
            .equals(providerDecision.path("decisionId").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Semantic action must bind the selected decision.
    if (!semanticAction
            .path("decisionId")
            .asText()
            .equals(providerDecision.path("decisionId").asText())
        || !semanticAction
            .path("decisionId")
            .asText()
            .equals(envelope.path("decisionId").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Provider decision's embedded goalPlan must be byte-identical to the separately
    // supplied GoalPlan. An envelope cannot mix a valid plan with a different decision.
    if ("SELECTED".equals(providerDecision.path("kind").asText())) {
      JsonNode embedded = providerDecision.path("goalPlan");
      if (embedded.isMissingNode()
          || embedded.isNull()
          || !canonicalOf(embedded).equals(canonicalOf(goalPlan))) {
        return "IDENTITY_MISMATCH";
      }
    } else if (!providerDecision.path("goalPlan").isMissingNode()
        && !providerDecision.path("goalPlan").isNull()) {
      return "IDENTITY_MISMATCH";
    } else {
      // Envelopes only bind SELECTED decisions; an abstention carries no executable plan.
      return "IDENTITY_MISMATCH";
    }
    // Eligibility mode and catalog binding against their sources.
    if (!eligibilityResult.path("mode").asText().equals(envelope.path("mode").asText())
        || !eligibilityResult
            .path("catalogDigest")
            .asText()
            .equals(envelope.path("catalogDigest").asText())
        || !eligibilityResult
            .path("catalogDigest")
            .asText()
            .equals(catalog.path("catalogDigest").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Complete planning-input binding, including eligible/rejected tasks, provenance,
    // prompt schema and policy inputs. The digest check above covers byte equality;
    // the field checks below ensure the planning input actually describes this envelope.
    if (!planningInput.path("mode").asText().equals(envelope.path("mode").asText())
        || !planningInput
            .path("catalogDigest")
            .asText()
            .equals(envelope.path("catalogDigest").asText())
        || !planningInput
            .path("capabilitySnapshotDigest")
            .asText()
            .equals(envelope.path("capabilitySnapshotDigest").asText())
        || !planningInput
            .path("policyDigest")
            .asText()
            .equals(envelope.path("policyDigest").asText())
        || !planningInput
            .path("policyDigest")
            .asText()
            .equals(observation.path("policyDigest").asText())
        || !planningInput
            .path("knowledgeDigest")
            .asText()
            .equals(envelope.path("knowledgeDigest").asText())
        || !planningInput
            .path("knowledgeDigest")
            .asText()
            .equals(observation.path("knowledgeDigest").asText())) {
      return "IDENTITY_MISMATCH";
    }
    if (planningInput.path("promptSchemaId").asText("").isBlank()
        || planningInput.path("promptSchemaVersion").asText("").isBlank()) {
      return "IDENTITY_MISMATCH";
    }
    if (!planningInput
        .path("capabilitySnapshotDigest")
        .asText()
        .equals(GameplayV2EligibilityFilter.capabilitySnapshotDigest(new ObjectMapper()))) {
      return "IDENTITY_MISMATCH";
    }
    String envelopeTask = envelope.path("taskKey").asText();
    if (!containsText(planningInput.path("eligibleTaskKeys"), envelopeTask)
        || containsRejected(planningInput, envelopeTask)
        || !eligibilityHasStatus(eligibilityResult, envelopeTask, "ELIGIBLE")) {
      return "IDENTITY_MISMATCH";
    }
    // Planning eligible/rejected sets must be consistent with the eligibility result:
    // every ELIGIBLE result entry must have been an eligible planning key, and every
    // planning rejection must remain non-ELIGIBLE in the result.
    for (JsonNode key : planningInput.path("eligibleTaskKeys")) {
      if (key.isTextual() && eligibilityHasStatus(eligibilityResult, key.asText(), "ELIGIBLE")) {
        continue;
      }
      // Eligible planning keys that are not ELIGIBLE in the result are only acceptable
      // when the result marks them with a concrete non-eligible status; a missing entry
      // means the result no longer represents this planning input.
      if (key.isTextual() && !eligibilityHasEntry(eligibilityResult, key.asText())) {
        return "IDENTITY_MISMATCH";
      }
    }
    for (JsonNode rejected : planningInput.path("rejectedEntries")) {
      String rejectedKey = rejected.path("taskKey").asText();
      if (eligibilityHasStatus(eligibilityResult, rejectedKey, "ELIGIBLE")) {
        return "IDENTITY_MISMATCH";
      }
    }
    // Mode and identity agreement across envelope, planning input, and goal plan.
    if (!envelope.path("mode").asText().equals(planningInput.path("mode").asText())
        || !envelope.path("mode").asText().equals(goalPlan.path("mode").asText())
        || !canonicalOf(envelope.path("identities"))
            .equals(canonicalOf(planningInput.path("identities")))
        || !canonicalOf(envelope.path("identities"))
            .equals(canonicalOf(goalPlan.path("identities")))) {
      return "IDENTITY_MISMATCH";
    }
    // Task key, settings, transition, and verifier must match the semantic action
    // and the selected plan node.
    JsonNode selected = goalPlan.path("nodes").path(0);
    if (!envelope.path("taskKey").asText().equals(semanticAction.path("taskKey").asText())
        || !envelope.path("taskKey").asText().equals(selected.path("taskKey").asText())
        || !canonicalOf(envelope.path("settings"))
            .equals(canonicalOf(semanticAction.path("settings")))
        || !canonicalOf(envelope.path("settings")).equals(canonicalOf(selected.path("settings")))
        || !envelope
            .path("expectedTransition")
            .asText()
            .equals(semanticAction.path("expectedTransition").asText())
        || !envelope
            .path("expectedTransition")
            .asText()
            .equals(selected.path("expectedTransition").asText())
        || !envelope
            .path("verifierId")
            .asText()
            .equals(semanticAction.path("verifierId").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Deadline agreement across envelope, planning input, and goal plan.
    if (!envelope.path("deadline").asText().equals(planningInput.path("deadline").asText())
        || !envelope.path("deadline").asText().equals(goalPlan.path("deadline").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Catalog revision agreement across every object.
    long revision = catalog.path("catalogRevision").asLong(-1);
    if (envelope.path("catalogRevision").asLong(-2) != revision
        || observation.path("catalogRevision").asLong(-3) != revision
        || planningInput.path("catalogRevision").asLong(-4) != revision) {
      return "IDENTITY_MISMATCH";
    }
    // Executor identity binding: the envelope must carry the catalog's executor.
    if (!envelope
            .path("executorArtifactId")
            .asText()
            .equals(catalog.path("executorArtifactId").asText())
        || !envelope.path("pluginId").asText().equals(catalog.path("pluginId").asText())
        || !envelope.path("buildId").asText().equals(catalog.path("executorBuildId").asText())
        || !envelope.path("buildId").asText().equals(observation.path("buildId").asText())) {
      return "IDENTITY_MISMATCH";
    }
    // Single-execution budgets must fit the planning input, the selected node and
    // the catalog bounds.
    JsonNode planningBudgets = planningInput.path("budgets");
    JsonNode envelopeBudgets = envelope.path("budgets");
    if (envelopeBudgets.path("maxActions").asLong() > planningBudgets.path("maxActions").asLong()
        || envelopeBudgets.path("maxAttempts").asLong()
            > planningBudgets.path("maxAttempts").asLong()
        || envelopeBudgets.path("maxElapsedMs").asLong()
            > planningBudgets.path("maxElapsedMs").asLong()) {
      return "BUDGET_EXHAUSTED";
    }
    JsonNode task = findCatalogTask(catalog, envelope.path("taskKey").asText());
    if (task != null) {
      JsonNode bounds = task.path("maxPolicyBounds");
      JsonNode budgets = envelope.path("budgets");
      if (budgets.path("maxActions").asLong() > bounds.path("maxActions").asLong()
          || budgets.path("maxAttempts").asLong() > bounds.path("maxAttempts").asLong()
          || budgets.path("maxElapsedMs").asLong() > bounds.path("maxElapsedMs").asLong()) {
        return "BUDGET_EXHAUSTED";
      }
      JsonNode nodeBudgets = selected.path("budgets");
      if (budgets.path("maxActions").asLong() > nodeBudgets.path("maxActions").asLong()
          || budgets.path("maxAttempts").asLong() > nodeBudgets.path("maxAttempts").asLong()
          || budgets.path("maxElapsedMs").asLong() > nodeBudgets.path("maxElapsedMs").asLong()) {
        return "BUDGET_EXHAUSTED";
      }
    }
    return null;
  }

  private static boolean containsText(JsonNode array, String value) {
    if (array == null || !array.isArray() || value == null) {
      return false;
    }
    for (JsonNode item : array) {
      if (value.equals(item.asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsRejected(JsonNode planningInput, String taskKey) {
    for (JsonNode rejected : planningInput.path("rejectedEntries")) {
      if (taskKey.equals(rejected.path("taskKey").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean eligibilityHasEntry(JsonNode eligibilityResult, String taskKey) {
    for (JsonNode entry : eligibilityResult.path("entries")) {
      if (taskKey.equals(entry.path("taskKey").asText())) {
        return true;
      }
    }
    return false;
  }

  private static boolean eligibilityHasStatus(
      JsonNode eligibilityResult, String taskKey, String status) {
    for (JsonNode entry : eligibilityResult.path("entries")) {
      if (taskKey.equals(entry.path("taskKey").asText())
          && status.equals(entry.path("status").asText())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Deterministic eligibility replay: reruns the filter for the same catalog, observation and
   * planning input at the claimed evaluatedAt and requires the supplied result's value/status
   * entries to match exactly. The claimed instant must be plausible: never in the future relative
   * to now, and never stale relative to the observation (fresh capture, still within maxAge).
   */
  static String checkEligibilityReplay(
      JsonNode catalog,
      JsonNode observation,
      JsonNode planningInput,
      JsonNode eligibilityResult,
      Instant now) {
    Instant evaluatedAt;
    try {
      evaluatedAt = Instant.parse(eligibilityResult.path("evaluatedAt").asText());
    } catch (Exception exception) {
      return "IDENTITY_MISMATCH";
    }
    if (evaluatedAt.isAfter(now)) {
      return "IDENTITY_MISMATCH";
    }
    Instant capturedAt;
    try {
      capturedAt = Instant.parse(observation.path("capturedAt").asText());
    } catch (Exception exception) {
      return "IDENTITY_MISMATCH";
    }
    if (evaluatedAt.isBefore(capturedAt)) {
      return "IDENTITY_MISMATCH";
    }
    if (capturedAt
        .plusMillis(observation.path("freshness").path("maxAgeMs").asLong(Long.MAX_VALUE))
        .isBefore(evaluatedAt)) {
      return "IDENTITY_MISMATCH";
    }
    JsonNode replayed =
        new GameplayV2EligibilityFilter(new ObjectMapper())
            .filter(catalog, observation, planningInput, evaluatedAt);
    if (!canonicalOf(eligibilityResult.path("entries"))
        .equals(canonicalOf(replayed.path("entries")))) {
      return "IDENTITY_MISMATCH";
    }
    if (!eligibilityResult.path("mode").asText().equals(replayed.path("mode").asText())
        || !eligibilityResult
            .path("catalogDigest")
            .asText()
            .equals(replayed.path("catalogDigest").asText())
        || !eligibilityResult
            .path("observationDigest")
            .asText()
            .equals(replayed.path("observationDigest").asText())) {
      return "IDENTITY_MISMATCH";
    }
    return null;
  }

  private static JsonNode findCatalogTask(JsonNode catalog, String taskKey) {
    for (JsonNode task : catalog.path("tasks")) {
      if (taskKey.equals(task.path("taskKey").asText())) {
        return task;
      }
    }
    return null;
  }

  static String catalogDigestOf(JsonNode catalog) {
    ObjectNode copy = catalog.deepCopy();
    copy.remove("catalogDigest");
    return canonicalOf(copy);
  }

  static String envelopeDigestOf(JsonNode envelope) {
    return canonicalOf(envelope);
  }

  static String goalPlanDigestOf(JsonNode goalPlan) {
    ObjectNode copy = goalPlan.deepCopy();
    copy.remove("goalPlanDigest");
    return canonicalOf(copy);
  }

  private static String canonicalOf(JsonNode value) {
    return GameplayV2EligibilityFilter.canonicalDigest(new ObjectMapper(), value);
  }

  /**
   * Legacy authority binding without approved scope. This overload cannot prove the signed {@code
   * scopeDigest} represents the scope being executed, so it always fails closed with {@code
   * AUTHORITY_SCOPE_MISMATCH}. LIVE_TASK callers must use {@link #checkAuthority(JsonNode,
   * JsonNode, JsonNode, String, String, String, Instant, Set)}.
   *
   * @deprecated Use the approved-scope overload; this method fails closed by design.
   */
  @Deprecated
  public static String checkAuthority(
      JsonNode authority,
      String envelopeDigest,
      String envelopeAuthorityId,
      String signingSecret,
      Instant now,
      Set<String> consumedAuthorityIds) {
    return "AUTHORITY_SCOPE_MISMATCH";
  }

  /**
   * Authority binding checks including approved human scope verification. This method is a pure
   * check: it reads {@code consumedAuthorityIds} as an immutable snapshot and never mutates it.
   * Atomic consume-and-dispatch must happen in the live gate (SC-1449), which holds the durable
   * one-use ownership row under lock before dispatch. Concurrent callers sharing one snapshot can
   * all observe "not yet consumed"; only the gate's atomic consumption decides the winner.
   *
   * <p>The envelope digest is recomputed from the supplied envelope bytes: a caller-provided digest
   * is only an untrusted hint and must equal the recomputation, and the authority must bind the
   * recomputation. The authority reference is derived from the envelope itself: the {@code
   * envelopeAuthorityId} hint must equal {@code envelope.authorityId}, which in turn must equal
   * {@code authority.authorityId}, so a mismatched envelope/authority pair cannot pass by supplying
   * the authority's own ID as the hint. The scope is validated, its canonical digest is recomputed
   * and compared to {@code authority.scopeDigest}, and every individual execution field is bound to
   * the envelope being executed. Returns {@code null} when the authority holds, otherwise a stable
   * {@code AUTHORITY_*} failure code.
   */
  public static String checkAuthority(
      JsonNode authority,
      JsonNode approvedScope,
      JsonNode envelope,
      String envelopeDigest,
      String envelopeAuthorityId,
      String signingSecret,
      Instant now,
      Set<String> consumedAuthorityIds) {
    if (envelope == null || envelope.isMissingNode() || envelope.isNull()) {
      return "AUTHORITY_SCOPE_MISMATCH";
    }
    if (approvedScope == null || approvedScope.isMissingNode() || approvedScope.isNull()) {
      return "AUTHORITY_SCOPE_MISMATCH";
    }
    // Authority identity is envelope-derived, not hint-derived: the hint must
    // match the envelope's own authorityId before any signature comparison.
    String envelopeBoundId = envelope.path("authorityId").asText(null);
    if (envelopeBoundId == null
        || envelopeBoundId.isBlank()
        || !envelopeBoundId.equals(envelopeAuthorityId)) {
      return "AUTHORITY_CHANGED";
    }
    String recomputedScope = canonicalOf(approvedScope);
    if (!recomputedScope.equals(authority.path("scopeDigest").asText())) {
      return "AUTHORITY_SCOPE_MISMATCH";
    }
    String recomputedEnvelope = envelopeDigestOf(envelope);
    if (envelopeDigest != null && !envelopeDigest.equals(recomputedEnvelope)) {
      return "AUTHORITY_CHANGED";
    }
    String base =
        checkAuthorityBase(
            authority,
            recomputedEnvelope,
            envelopeAuthorityId,
            signingSecret,
            now,
            consumedAuthorityIds);
    if (base != null) {
      return base;
    }
    if (!"sc-1429/v2".equals(approvedScope.path("contractVersion").asText())
        || !"RUN".equals(approvedScope.path("killSwitchScope").asText())) {
      return "AUTHORITY_SCOPE_MISMATCH";
    }
    if (!approvedScope.path("mode").asText().equals(envelope.path("mode").asText())
        || !approvedScope.path("provider").asText().equals(envelope.path("provider").asText())
        || !approvedScope
            .path("modelVersion")
            .asText()
            .equals(envelope.path("modelVersion").asText())
        || !approvedScope.path("decisionId").asText().equals(envelope.path("decisionId").asText())
        || !canonicalOf(approvedScope.path("identities"))
            .equals(canonicalOf(envelope.path("identities")))
        || !approvedScope.path("taskKey").asText().equals(envelope.path("taskKey").asText())
        || !canonicalOf(approvedScope.path("settings"))
            .equals(canonicalOf(envelope.path("settings")))
        || !canonicalOf(approvedScope.path("budgets")).equals(canonicalOf(envelope.path("budgets")))
        || !approvedScope
            .path("semanticActionDigest")
            .asText()
            .equals(envelope.path("semanticActionDigest").asText())
        || !approvedScope
            .path("expectedTransition")
            .asText()
            .equals(envelope.path("expectedTransition").asText())
        || !approvedScope.path("verifierId").asText().equals(envelope.path("verifierId").asText())
        || !approvedScope.path("deadline").asText().equals(envelope.path("deadline").asText())
        || !approvedScope
            .path("killSwitchScope")
            .asText()
            .equals(envelope.path("killSwitchScope").asText())) {
      return "AUTHORITY_SCOPE_MISMATCH";
    }
    return null;
  }

  private static String checkAuthorityBase(
      JsonNode authority,
      String envelopeDigest,
      String envelopeAuthorityId,
      String signingSecret,
      Instant now,
      Set<String> consumedAuthorityIds) {
    Instant approvedAt = Instant.parse(authority.path("approvedAt").asText());
    Instant expiresAt = Instant.parse(authority.path("expiresAt").asText());
    if (approvedAt.isAfter(now)
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(approvedAt.plusSeconds(900))) {
      return "AUTHORITY_EXPIRED";
    }
    if (!authority.path("authorityId").asText().equals(envelopeAuthorityId)) {
      return "AUTHORITY_CHANGED";
    }
    if (!authority.path("envelopeDigest").asText().equals(envelopeDigest)) {
      return "AUTHORITY_CHANGED";
    }
    if (consumedAuthorityIds.contains(authority.path("authorityId").asText())) {
      return "AUTHORITY_REUSED";
    }
    if (!verifySignature(authority, signingSecret)) {
      return "AUTHORITY_CHANGED";
    }
    return null;
  }

  private static boolean verifySignature(JsonNode authority, String signingSecret) {
    if (signingSecret == null
        || signingSecret.isBlank()
        || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      return false;
    }
    try {
      ObjectNode unsigned = authority.deepCopy();
      unsigned.remove("signature");
      byte[] canonical =
          GameplayV2EligibilityFilter.canonicalDigestBytes(new ObjectMapper(), unsigned);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(canonical));
      return constantTime(expected, authority.path("signature").asText());
    } catch (Exception exception) {
      return false;
    }
  }

  private static boolean constantTime(String left, String right) {
    return java.security.MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Legacy idempotency overload without envelope bytes. This overload cannot recompute the envelope
   * digest and therefore cannot prove the request identity, so it unconditionally fails closed with
   * {@code IDEMPOTENCY_CONFLICT} and never mutates the ledger. Callers must use {@link
   * #checkIdempotency(String, String, JsonNode, java.util.concurrent.ConcurrentMap)}.
   *
   * @deprecated Pass the envelope so its digest is recomputed internally; this overload always
   *     fails closed by design.
   */
  @Deprecated
  public static String checkIdempotency(
      String idempotencyKey, String requestId, Map<String, String> ledger) {
    return "IDEMPOTENCY_CONFLICT";
  }

  /**
   * Legacy idempotency overload with a caller-supplied digest hint. The hint cannot detect a caller
   * who modifies the envelope while reusing its original digest, so this overload unconditionally
   * fails closed with {@code IDEMPOTENCY_CONFLICT} and never mutates the ledger. Callers must use
   * {@link #checkIdempotency(String, String, JsonNode, java.util.concurrent.ConcurrentMap)}, which
   * recomputes the digest from the supplied envelope.
   *
   * @deprecated Pass the envelope itself; this overload always fails closed by design.
   */
  @Deprecated
  public static String checkIdempotency(
      String idempotencyKey, String requestId, String envelopeDigest, Map<String, String> ledger) {
    return "IDEMPOTENCY_CONFLICT";
  }

  /**
   * Idempotent reconciliation against the envelope fingerprint recomputed from the supplied
   * envelope bytes. The digest is never trusted from the caller: modifying any envelope field
   * (settings, digests, authority, lease, fence, ...) changes the fingerprint and therefore
   * conflicts instead of reconciling, even when the caller reuses the original digest string.
   *
   * <p>Null or blank {@code idempotencyKey}/{@code requestId}, a null or missing envelope, or a
   * null ledger fails closed with {@code IDEMPOTENCY_CONFLICT} without mutating the ledger.
   *
   * <p>First use is recorded atomically with {@code putIfAbsent}: concurrent callers racing on a
   * fresh key cannot both observe a fresh ledger. Exactly one caller receives {@code null} (fresh,
   * proceed); the loser observes the winner's fingerprint and reconciles or conflicts accordingly.
   * The ledger must be a {@link java.util.concurrent.ConcurrentMap} (for example {@code
   * ConcurrentHashMap}); a plain {@code HashMap} cannot provide the atomic first-use guarantee and
   * is rejected by overloading: pass a concurrent map.
   *
   * <p>A legacy {@code requestId}-only record is never mutated here. It returns {@code
   * NEEDS_RECONCILIATION} so external reconciliation can prove the old request's identity/outcome
   * first; only then may the ledger advance via {@link #confirmLegacyReconciliation(String, String,
   * JsonNode, ConcurrentMap)}. A repeat call while the legacy record stands still returns {@code
   * NEEDS_RECONCILIATION}, never {@code RECONCILED}.
   */
  public static String checkIdempotency(
      String idempotencyKey,
      String requestId,
      JsonNode envelope,
      ConcurrentMap<String, String> ledger) {
    if (ledger == null
        || idempotencyKey == null
        || idempotencyKey.isBlank()
        || requestId == null
        || requestId.isBlank()
        || envelope == null
        || envelope.isMissingNode()
        || envelope.isNull()) {
      return "IDEMPOTENCY_CONFLICT";
    }
    String fingerprint = requestId + "|" + envelopeDigestOf(envelope);
    String recorded = ledger.putIfAbsent(idempotencyKey, fingerprint);
    if (recorded == null) {
      return null;
    }
    if (recorded.equals(fingerprint)) {
      return "RECONCILED";
    }
    if (recorded.equals(requestId)) {
      return "NEEDS_RECONCILIATION";
    }
    return "IDEMPOTENCY_CONFLICT";
  }

  /**
   * Legacy reconciliation without proof. This overload cannot prove the old request's identity,
   * outcome or verifier, so any caller with ledger access could promote legacy state. It always
   * fails closed with {@code false} and never mutates the ledger. Callers must present a {@link
   * GameplayV2ReconciliationReceipt} via {@link #confirmLegacyReconciliation(String, String,
   * JsonNode, ConcurrentMap, GameplayV2ReconciliationReceipt)}.
   *
   * @deprecated Present a validated reconciliation receipt; this overload always fails closed.
   */
  @Deprecated
  public static boolean confirmLegacyReconciliation(
      String idempotencyKey,
      String requestId,
      JsonNode envelope,
      ConcurrentMap<String, String> ledger) {
    return false;
  }

  /**
   * Unsigned legacy reconciliation. Unsigned receipts are forgeable by any caller (the envelope
   * digest is computable and every other field is caller-supplied), so this overload always fails
   * closed with {@code false} and never mutates the ledger. Callers must present a trusted signed
   * receipt via {@link #confirmLegacyReconciliation(String, String, JsonNode, ConcurrentMap,
   * GameplayV2ReconciliationReceipt, String, String, Instant)}.
   *
   * @deprecated Present a trusted signed receipt; this overload always fails closed.
   */
  @Deprecated
  public static boolean confirmLegacyReconciliation(
      String idempotencyKey,
      String requestId,
      JsonNode envelope,
      ConcurrentMap<String, String> ledger,
      GameplayV2ReconciliationReceipt receipt) {
    return false;
  }

  /**
   * External reconciliation completion for a legacy {@code requestId}-only record, gated by a
   * cryptographically authenticated {@link GameplayV2ReconciliationReceipt} issued at the SC-1449
   * trusted reconciler boundary. The live gate (SC-1449) owns durable one-use consumption; this
   * helper only advances a pure contract-level ledger (a {@link ConcurrentMap} held by the caller)
   * when the receipt proves external reconciliation: the HMAC-SHA256 {@code signature} verifies
   * under {@code signingSecret} ({@code >=32} bytes), {@code keyId} equals {@code expectedKeyId},
   * {@code expiresAt} is after {@code now} and within {@link
   * GameplayV2ReconciliationReceipt#MAX_TTL_SECONDS} of {@code confirmedAt}, the receipt binds the
   * same request identity, the recomputed envelope digest, the {@code CONFIRMED} external outcome,
   * a non-blank verifier identity and a non-future confirmation time. Atomically replaces the
   * legacy value with the recomputed envelope fingerprint via compare-and-set; returns {@code true}
   * when the replacement happened and {@code false} otherwise. Invalid inputs never mutate the
   * ledger.
   */
  public static boolean confirmLegacyReconciliation(
      String idempotencyKey,
      String requestId,
      JsonNode envelope,
      ConcurrentMap<String, String> ledger,
      GameplayV2ReconciliationReceipt receipt,
      String signingSecret,
      String expectedKeyId,
      Instant now) {
    if (ledger == null
        || idempotencyKey == null
        || idempotencyKey.isBlank()
        || requestId == null
        || requestId.isBlank()
        || envelope == null
        || envelope.isMissingNode()
        || envelope.isNull()
        || receipt == null
        || now == null) {
      return false;
    }
    if (!idempotencyKey.equals(receipt.idempotencyKey())
        || !requestId.equals(receipt.requestId())
        || !GameplayV2ReconciliationReceipt.CONFIRMED_OUTCOME.equals(receipt.externalOutcome())
        || receipt.verifierIdentity() == null
        || receipt.verifierIdentity().isBlank()
        || receipt.confirmedAt() == null
        || receipt.confirmedAt().isAfter(now.plusSeconds(60))) {
      return false;
    }
    if (receipt.keyId() == null
        || receipt.keyId().isBlank()
        || expectedKeyId == null
        || !expectedKeyId.equals(receipt.keyId())
        || receipt.expiresAt() == null
        || !receipt.expiresAt().isAfter(now)
        || !receipt.expiresAt().isAfter(receipt.confirmedAt())
        || receipt
            .expiresAt()
            .isAfter(
                receipt.confirmedAt().plusSeconds(GameplayV2ReconciliationReceipt.MAX_TTL_SECONDS))
        || receipt.signature() == null
        || receipt.signature().isBlank()) {
      return false;
    }
    if (signingSecret == null
        || signingSecret.isBlank()
        || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      return false;
    }
    if (!GameplayV2ReconciliationReceipt.verifySignature(receipt, signingSecret)) {
      return false;
    }
    String recomputedDigest = envelopeDigestOf(envelope);
    if (!recomputedDigest.equals(receipt.envelopeDigest())) {
      return false;
    }
    String fingerprint = requestId + "|" + recomputedDigest;
    return ledger.replace(idempotencyKey, requestId, fingerprint);
  }
}
