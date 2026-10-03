package com.chromascape.web.gameplay.contract.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates named SC-1429 v2 definitions, rejects unknown security-sensitive fields, and enforces
 * the closed semantic rules that JSON Schema cannot express. Stable failure codes are shared with
 * the Micro6 conformance validator and the v2 golden fixture corpus.
 */
@Component
public final class GameplayV2ContractValidator {
  private static final String SCHEMA =
      "contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json";
  private final ObjectMapper objectMapper;
  private final JsonNode root;
  private final JsonSchemaFactory schemaFactory =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  public GameplayV2ContractValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(SCHEMA)) {
      if (input == null) {
        throw new IllegalStateException("Missing " + SCHEMA);
      }
      this.root = objectMapper.readTree(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load SC-1429 v2 schema", exception);
    }
  }

  /** Schema-only validation. Violations always carry the stable {@code INVALID_<DEF>} code. */
  public void validate(String definition, Object value) {
    JsonNode definitionNode = root.path("$defs").path(definition);
    if (definitionNode.isMissingNode()) {
      throw violation(failureCode(definition), "Unknown definition: " + definition);
    }
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.set("$defs", root.path("$defs"));
    wrapper.put("$ref", "#/$defs/" + definition);
    JsonSchema schema = schemaFactory.getSchema(wrapper);
    Set<ValidationMessage> errors = schema.validate(objectMapper.valueToTree(value));
    if (!errors.isEmpty()) {
      String message =
          errors.stream()
              .map(ValidationMessage::getMessage)
              .sorted(Comparator.naturalOrder())
              .collect(Collectors.joining("; "));
      throw violation(failureCode(definition), message);
    }
  }

  /**
   * Stable snake-case failure code, e.g. {@code providerDecision} to {@code
   * INVALID_PROVIDER_DECISION}.
   */
  static String failureCode(String definition) {
    return "INVALID_" + definition.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
  }

  /**
   * Schema validation plus the closed semantic rules. Only the definitions that carry semantic
   * rules gain extra codes; everything else behaves like {@link #validate(String, Object)}.
   * Timestamp strings are strictly validated as RFC-3339 date-times because JSON Schema {@code
   * format} is annotation-only by default; a malformed, offset-less or impossible timestamp is
   * rejected with the definition's {@code INVALID_*} code.
   */
  public void validateWithRules(String definition, Object value) {
    validate(definition, value);
    JsonNode node = objectMapper.valueToTree(value);
    checkTimestamps(definition, node);
    switch (definition) {
      case "goalPlan" -> checkGoalPlan(node);
      case "providerDecision" -> checkProviderDecision(node);
      case "executionEnvelope" -> checkEnvelopeMode(node);
      case "toolOperation" -> checkToolOperationMode(node);
      case "lifecycleReceipt" -> checkReceiptRules(node);
      default -> {
        // No additional semantic rules for this definition.
      }
    }
  }

  /**
   * Strict RFC-3339 date-time validation for every v2 timestamp field. {@link
   * java.time.Instant#parse} accepts exactly the closed timestamp shape (full date, time, and
   * explicit zone) and rejects malformed dates, missing offsets and impossible calendar values.
   */
  private void checkTimestamps(String definition, JsonNode node) {
    for (String field : timestampFields(definition)) {
      JsonNode value = field.contains(".") ? atPath(node, field) : node.path(field);
      if (value.isMissingNode() || value.isNull()) {
        continue;
      }
      assertDateTime(definition, value.asText());
    }
  }

  private static java.util.List<String> timestampFields(String definition) {
    return switch (definition) {
      case "immutableTaskCatalogSnapshot" -> java.util.List.of("capturedAt");
      case "semanticObservation" -> java.util.List.of("capturedAt");
      case "planningInput" -> java.util.List.of("deadline");
      case "eligibilityResult" -> java.util.List.of("evaluatedAt");
      case "goalPlan" -> java.util.List.of("deadline");
      case "executionEnvelope" -> java.util.List.of("deadline");
      case "authority" -> java.util.List.of("approvedAt", "expiresAt");
      case "approvedScope" -> java.util.List.of("deadline");
      case "toolOperation" -> java.util.List.of("deadline");
      case "lifecycleReceipt" -> java.util.List.of("at");
      default -> java.util.List.of();
    };
  }

  private JsonNode atPath(JsonNode node, String dotted) {
    JsonNode current = node;
    for (String segment : dotted.split("\\.")) {
      current = current.path(segment);
    }
    return current;
  }

  private void assertDateTime(String definition, String value) {
    try {
      java.time.Instant.parse(value);
    } catch (Exception exception) {
      throw violation(failureCode(definition), "Invalid RFC-3339 date-time: " + value);
    }
  }

  /**
   * Recursive provider-decision validation: a SELECTED decision's embedded GoalPlan must satisfy
   * the same graph, ordering, budget and digest rules as a top-level plan. A structurally valid
   * decision carrying a cyclic, digest-invalid or catalog-unbound plan is rejected here so generic
   * contract validation cannot be bypassed. Catalog binding is covered by {@link
   * #validateProviderDecisionWithCatalog(JsonNode, JsonNode)}.
   */
  private void checkProviderDecision(JsonNode decision) {
    if (!"SELECTED".equals(decision.path("kind").asText())) {
      return;
    }
    JsonNode goalPlan = decision.path("goalPlan");
    if (goalPlan.isMissingNode() || goalPlan.isNull()) {
      throw violation("INVALID_PROVIDER_DECISION", "SELECTED decisions must carry a goalPlan");
    }
    if (!decision.path("decisionId").asText().equals(goalPlan.path("sourceDecisionId").asText())) {
      throw violation(
          "IDENTITY_MISMATCH", "Provider decisionId does not match goalPlan sourceDecisionId");
    }
    checkGoalPlan(goalPlan);
  }

  /**
   * Full provider-decision validation against its source catalog: schema, recursive graph/digest
   * rules, plus per-node catalog binding for the embedded GoalPlan.
   */
  public void validateProviderDecisionWithCatalog(JsonNode decision, JsonNode catalog) {
    if ("SELECTED".equals(decision.path("kind").asText())) {
      checkGoalPlanCatalogBinding(decision.path("goalPlan"), catalog);
    }
    validateWithRules("providerDecision", decision);
  }

  private void checkGoalPlan(JsonNode plan) {
    // Duplicate ordinals.
    Set<Integer> ordinals = new HashSet<>();
    for (JsonNode node : plan.path("nodes")) {
      if (!ordinals.add(node.path("ordinal").asInt())) {
        throw violation("DUPLICATE_NODE_ORDINAL", "GoalPlan node ordinal is not unique");
      }
    }
    // Duplicate node IDs.
    Set<String> nodeIds = new HashSet<>();
    for (JsonNode node : plan.path("nodes")) {
      String nodeId = node.path("nodeId").asText();
      if (!nodeIds.add(nodeId)) {
        throw violation("DUPLICATE_NODE_ID", "GoalPlan nodeId is not unique: " + nodeId);
      }
    }
    // Ordinal ordering: array order must match ascending ordinals 0..n-1.
    int index = 0;
    for (JsonNode node : plan.path("nodes")) {
      if (node.path("ordinal").asInt() != index) {
        throw violation(
            "NODE_ORDINAL_OUT_OF_ORDER",
            "GoalPlan ordinals must be sequential 0..n-1 in array order");
      }
      index++;
    }
    // Fallback targets must exist; fallback chains must be acyclic and bounded.
    for (JsonNode node : plan.path("nodes")) {
      String fallback = node.path("fallbackNodeId").asText(null);
      if (fallback != null && findNode(plan, fallback) == null) {
        throw violation(
            "UNKNOWN_NODE_REFERENCE", "GoalPlan fallback target is missing: " + fallback);
      }
      Set<String> visited = new HashSet<>();
      visited.add(node.path("nodeId").asText());
      JsonNode current = findNode(plan, fallback);
      int depth = 0;
      while (current != null) {
        depth++;
        if (depth > 8) {
          throw violation("FALLBACK_CHAIN_TOO_DEEP", "GoalPlan fallback chain exceeds 8 hops");
        }
        String currentId = current.path("nodeId").asText();
        if (!visited.add(currentId)) {
          throw violation("GOAL_PLAN_CYCLIC", "GoalPlan fallback chain is cyclic");
        }
        current = findNode(plan, current.path("fallbackNodeId").asText(null));
      }
    }
    // Prerequisite targets must exist; prerequisite graphs must be acyclic.
    for (JsonNode node : plan.path("nodes")) {
      for (JsonNode prereq : node.path("prerequisiteNodeIds")) {
        if (findNode(plan, prereq.asText()) == null) {
          throw violation(
              "UNKNOWN_NODE_REFERENCE",
              "GoalPlan prerequisite target is missing: " + prereq.asText());
        }
      }
    }
    checkPrerequisiteAcyclic(plan);
    // Aggregate budget overflow: the plan cannot promise more than its aggregate envelope.
    long sumActions = 0;
    long sumAttempts = 0;
    long sumElapsed = 0;
    for (JsonNode node : plan.path("nodes")) {
      sumActions += node.path("budgets").path("maxActions").asLong();
      sumAttempts += node.path("budgets").path("maxAttempts").asLong();
      sumElapsed += node.path("budgets").path("maxElapsedMs").asLong();
    }
    JsonNode aggregate = plan.path("aggregateBudgets");
    if (sumActions > aggregate.path("maxActions").asLong()
        || sumAttempts > aggregate.path("maxAttempts").asLong()
        || sumElapsed > aggregate.path("maxElapsedMs").asLong()) {
      throw violation("AGGREGATE_BUDGET_OVERFLOW", "GoalPlan node budgets exceed aggregateBudgets");
    }
    // Declared digest must match the canonical plan bytes without the self-referential field.
    ObjectNode copy = plan.deepCopy();
    copy.remove("goalPlanDigest");
    String recomputed = GameplayV2EligibilityFilter.canonicalDigest(objectMapper, copy);
    if (!recomputed.equals(plan.path("goalPlanDigest").asText())) {
      throw violation(
          "GOAL_PLAN_DIGEST_MISMATCH", "GoalPlan goalPlanDigest does not match plan bytes");
    }
  }

  private void checkPrerequisiteAcyclic(JsonNode plan) {
    Set<String> done = new HashSet<>();
    for (JsonNode node : plan.path("nodes")) {
      visitPrereq(plan, node.path("nodeId").asText(), new HashSet<>(), done);
    }
  }

  private void visitPrereq(JsonNode plan, String nodeId, Set<String> stack, Set<String> done) {
    if (nodeId == null || done.contains(nodeId)) {
      return;
    }
    if (!stack.add(nodeId)) {
      throw violation("GOAL_PLAN_CYCLIC", "GoalPlan prerequisite graph is cyclic at " + nodeId);
    }
    JsonNode node = findNode(plan, nodeId);
    if (node != null) {
      for (JsonNode prereq : node.path("prerequisiteNodeIds")) {
        visitPrereq(plan, prereq.asText(), stack, done);
      }
    }
    stack.remove(nodeId);
    done.add(nodeId);
  }

  /**
   * Full GoalPlan validation against its source catalog: graph rules plus per-node
   * task/verifier/transition/settings/budget consistency. Call after {@link #validateWithRules}.
   */
  public void validateGoalPlanWithCatalog(JsonNode plan, JsonNode catalog) {
    checkGoalPlanCatalogBinding(plan, catalog);
  }

  private void checkGoalPlanCatalogBinding(JsonNode plan, JsonNode catalog) {
    for (JsonNode node : plan.path("nodes")) {
      String taskKey = node.path("taskKey").asText();
      JsonNode task = findCatalogTask(catalog, taskKey);
      if (task == null) {
        throw violation(
            "NODE_CATALOG_MISMATCH", "GoalPlan node task is not in the catalog: " + taskKey);
      }
      if (!containsText(task.path("verifierIds"), node.path("verifierId").asText())) {
        throw violation(
            "NODE_CATALOG_MISMATCH", "GoalPlan node verifier is not catalog-allowlisted");
      }
      if (!containsText(
          task.path("expectedTransitions"), node.path("expectedTransition").asText())) {
        throw violation(
            "NODE_CATALOG_MISMATCH", "GoalPlan node transition is not catalog-allowlisted");
      }
      for (JsonNode precondition : node.path("preconditions")) {
        if (!containsText(task.path("preconditions"), precondition.asText())) {
          throw violation(
              "NODE_CATALOG_MISMATCH",
              "GoalPlan node precondition is not catalog-allowlisted: " + precondition.asText());
        }
      }
      for (JsonNode ref : node.path("taskSkillRefs")) {
        for (JsonNode capability : ref.path("capabilityRequirements")) {
          if (!containsText(task.path("requiredCapabilities"), capability.asText())) {
            throw violation(
                "NODE_CATALOG_MISMATCH",
                "GoalPlan node capability is not required by the catalog: " + capability.asText());
          }
        }
      }
      JsonNode bounds = task.path("maxPolicyBounds");
      JsonNode budgets = node.path("budgets");
      if (budgets.path("maxActions").asLong() > bounds.path("maxActions").asLong()
          || budgets.path("maxAttempts").asLong() > bounds.path("maxAttempts").asLong()
          || budgets.path("maxElapsedMs").asLong() > bounds.path("maxElapsedMs").asLong()) {
        throw violation("NODE_CATALOG_MISMATCH", "GoalPlan node budgets exceed catalog bounds");
      }
    }
    JsonNode task0 = plan.path("nodes").path(0);
    if (!task0.isMissingNode()) {
      String taskKey = task0.path("taskKey").asText();
      JsonNode task = findCatalogTask(catalog, taskKey);
      if (task != null) {
        JsonNode aggregate = plan.path("aggregateBudgets");
        JsonNode bounds = task.path("maxPolicyBounds");
        if (aggregate.path("maxActions").asLong() > bounds.path("maxActions").asLong()
            || aggregate.path("maxAttempts").asLong() > bounds.path("maxAttempts").asLong()
            || aggregate.path("maxElapsedMs").asLong() > bounds.path("maxElapsedMs").asLong()) {
          throw violation("NODE_CATALOG_MISMATCH", "GoalPlan aggregate exceeds catalog bounds");
        }
      }
    }
  }

  private JsonNode findCatalogTask(JsonNode catalog, String taskKey) {
    for (JsonNode task : catalog.path("tasks")) {
      if (taskKey.equals(task.path("taskKey").asText())) {
        return task;
      }
    }
    return null;
  }

  private boolean containsText(JsonNode array, String value) {
    for (JsonNode item : array) {
      if (value.equals(item.asText())) {
        return true;
      }
    }
    return false;
  }

  private JsonNode findNode(JsonNode plan, String nodeId) {
    for (JsonNode node : plan.path("nodes")) {
      if (node.path("nodeId").asText().equals(nodeId)) {
        return node;
      }
    }
    return null;
  }

  private void checkEnvelopeMode(JsonNode envelope) {
    String mode = envelope.path("mode").asText();
    JsonNode authorityId = envelope.path("authorityId");
    if ("LIVE_TASK".equals(mode)) {
      if (authorityId.isNull() || authorityId.asText().isBlank()) {
        throw violation("AUTHORITY_MISSING", "LIVE_TASK envelopes require an authorityId");
      }
      if (environment(envelope).toUpperCase().contains("SIM")
          || environment(envelope).toUpperCase().contains("XRSPS")) {
        throw violation(
            "AUTHORITY_SOURCE_FORBIDDEN", "Simulator identity cannot authorize live delivery");
      }
    } else if (!authorityId.isNull()) {
      throw violation("AUTHORITY_FORBIDDEN", "Only LIVE_TASK envelopes may carry an authorityId");
    }
  }

  private String environment(JsonNode envelope) {
    return envelope.path("identities").path("environmentId").asText("");
  }

  private void checkToolOperationMode(JsonNode operation) {
    String op = operation.path("op").asText();
    String mode = operation.path("mode").asText();
    if (("RESERVE".equals(op) || "START".equals(op)) && !"LIVE_TASK".equals(mode)) {
      throw violation("MODE_OPERATION_MISMATCH", op + " is not permitted in " + mode + " mode");
    }
  }

  private void checkReceiptRules(JsonNode receipt) {
    String phase = receipt.path("phase").asText();
    String outcome = receipt.path("outcome").asText();
    int evidenceCount = receipt.path("evidenceRefs").size();
    boolean retryAllowed = receipt.path("retryAllowed").asBoolean();
    JsonNode failureCode = receipt.path("failureCode");
    boolean hasFailure = !failureCode.isNull() && !failureCode.asText().isBlank();
    // Phase/outcome matrix: every phase accepts only its closed outcome set.
    // Terminal verification outcomes never appear in early phases and delivery
    // acknowledgements are never CONFIRMED.
    if (!allowedOutcomes(phase).contains(outcome)) {
      if ("DISPATCH".equals(phase) && "CONFIRMED".equals(outcome)) {
        throw violation("ACK_MISLABELED_CONFIRMED", "Delivery acknowledgement is never CONFIRMED");
      }
      throw violation(
          "RECEIPT_PHASE_OUTCOME_MISMATCH",
          "Outcome " + outcome + " is not permitted in phase " + phase);
    }
    // Failure-code matrix: terminal failure outcomes must carry a code; successful
    // outcomes must not.
    if (requiresFailureCode(outcome) && !hasFailure) {
      throw violation(
          "RECEIPT_FAILURE_CODE_MISSING", "Outcome " + outcome + " requires a failureCode");
    }
    if (forbidsFailureCode(outcome) && hasFailure) {
      throw violation(
          "RECEIPT_FAILURE_CODE_FORBIDDEN", "Outcome " + outcome + " must not carry a failureCode");
    }
    // Evidence matrix.
    if ("CONFIRMED".equals(outcome) && evidenceCount == 0) {
      throw violation(
          "VERIFIER_EVIDENCE_MISSING", "CONFIRMED requires verifier evidence references");
    }
    if ("UNVERIFIABLE".equals(outcome) && evidenceCount > 0) {
      throw violation("VERIFIER_EVIDENCE_MISSING", "UNVERIFIABLE must not claim verifier evidence");
    }
    // Retry matrix: only UNVERIFIABLE, DENIED and FAILED are retryable. Ambiguous
    // terminal outcomes are never automatically retried.
    if ("UNCERTAIN_OUTCOME".equals(outcome) && retryAllowed) {
      throw violation(
          "UNCERTAIN_NON_RETRYABLE", "Ambiguous outcomes are never automatically retried");
    }
    if (retryAllowed && !Set.of("UNVERIFIABLE", "DENIED", "FAILED").contains(outcome)) {
      throw violation("RECEIPT_RETRY_FORBIDDEN", "Outcome " + outcome + " must not allow retry");
    }
  }

  private static Set<String> allowedOutcomes(String phase) {
    return switch (phase) {
      case "VALIDATION" -> Set.of("HELD", "DENIED", "FAILED", "ABSTAINED");
      case "AUTHORIZATION" -> Set.of("HELD", "DENIED", "FAILED", "ABSTAINED");
      case "RESERVATION" -> Set.of("HELD", "DENIED", "FAILED", "ABSTAINED");
      case "DISPATCH" -> Set.of("DELIVERED", "DENIED", "FAILED", "STOPPED");
      case "ACTIVATION" -> Set.of("DELIVERED", "DENIED", "FAILED", "STOPPED");
      case "PROGRESS" -> Set.of("DELIVERED", "DENIED", "FAILED", "STOPPED");
      case "TASK_TERMINAL" -> Set.of(
          "STOPPED", "FAILED", "DENIED", "UNCERTAIN_OUTCOME", "ABSTAINED");
      case "VERIFICATION" -> Set.of(
          "CONFIRMED", "REFUTED", "UNVERIFIABLE", "UNCERTAIN_OUTCOME", "DENIED", "FAILED");
      case "FINALIZATION" -> Set.of(
          "CONFIRMED", "REFUTED", "DENIED", "FAILED", "STOPPED", "ABSTAINED");
      default -> Set.of();
    };
  }

  private static boolean requiresFailureCode(String outcome) {
    return Set.of("REFUTED", "UNVERIFIABLE", "UNCERTAIN_OUTCOME", "DENIED", "FAILED")
        .contains(outcome);
  }

  private static boolean forbidsFailureCode(String outcome) {
    return Set.of("HELD", "DELIVERED", "CONFIRMED", "STOPPED", "ABSTAINED").contains(outcome);
  }

  private static GameplayV2ContractViolation violation(String code, String message) {
    return new GameplayV2ContractViolation(code, message);
  }

  /** Stable failure code carrier. Codes are pinned by the v2 golden fixture corpus. */
  public static final class GameplayV2ContractViolation extends IllegalArgumentException {
    private final String failureCode;

    public GameplayV2ContractViolation(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }

  /** Convenience accessor for tests and downstream readers. */
  public List<String> definitionNames() {
    List<String> names = new ArrayList<>();
    root.path("$defs").fieldNames().forEachRemaining(names::add);
    return names;
  }
}
