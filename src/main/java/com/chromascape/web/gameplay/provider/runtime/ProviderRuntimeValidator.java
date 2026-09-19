package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validates immutable runtime input and recomputes every client-supplied digest locally. */
@Component
public final class ProviderRuntimeValidator {
  private static final String ID = "[A-Za-z0-9][A-Za-z0-9._:-]{0,159}";

  private final GameplayContractValidator contractValidator;
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;

  @Autowired
  public ProviderRuntimeValidator(
      GameplayContractValidator contractValidator, CanonicalGameplayJson canonicalJson) {
    this(contractValidator, canonicalJson, Clock.systemUTC());
  }

  ProviderRuntimeValidator(
      GameplayContractValidator contractValidator,
      CanonicalGameplayJson canonicalJson,
      Clock clock) {
    this.contractValidator = contractValidator;
    this.canonicalJson = canonicalJson;
    this.clock = clock;
  }

  public void validateSession(SessionRequest request) {
    Objects.requireNonNull(request, "session request");
    requireId(request.sessionId(), "sessionId");
    Objects.requireNonNull(request.provider(), "provider");
    requireId(request.modelId(), "modelId");
    requireId(request.taskId(), "taskId");
    requireId(request.runId(), "runId");
    requireId(request.recordingId(), "recordingId");
    requireDigest(request.promptDigest(), "promptDigest");
    requireDigest(request.toolsetDigest(), "toolsetDigest");
    requireDigest(request.knowledgeDigest(), "knowledgeDigest");
    requireDigest(request.goalPlanDigest(), "goalPlanDigest");
    if (request.taskSkillDigests() == null || request.taskSkillDigests().isEmpty()) {
      throw invalid("TASK_SKILLS_REQUIRED", "At least one TaskSkill digest is required");
    }
    request.taskSkillDigests().forEach(value -> requireDigest(value, "taskSkillDigest"));
    Objects.requireNonNull(request.budgets(), "budgets");
    if (request.deadline() == null || !request.deadline().isAfter(clock.instant())) {
      throw invalid("DEADLINE_EXPIRED", "Session deadline must be in the future");
    }
    if (!request.decideOnly()) {
      throw invalid("DECIDE_ONLY_REQUIRED", "Provider runtime accepts decideOnly=true only");
    }
  }

  public void validateTurn(SessionRequest session, TurnRequest request) {
    Objects.requireNonNull(request, "turn request");
    requireId(request.turnId(), "turnId");
    requireId(request.requestId(), "requestId");
    requireId(request.idempotencyKey(), "idempotencyKey");
    requireId(request.sourceId(), "sourceId");
    if (!request.decideOnly()) {
      throw invalid("DECIDE_ONLY_REQUIRED", "Provider turn accepts decideOnly=true only");
    }
    if (request.deadline() == null
        || !request.deadline().isAfter(clock.instant())
        || request.deadline().isAfter(session.deadline())) {
      throw invalid("DEADLINE_EXPIRED", "Turn deadline must be current and within the session");
    }
    if (request.estimatedInputTokens() <= 0 || request.estimatedOutputTokens() <= 0) {
      throw invalid("TOKEN_ESTIMATE_REQUIRED", "Conservative token estimates are required");
    }
    contractValidator.validate("observation", request.observation());
    if (request.eligibleActions() == null
        || !request.eligibleActions().isArray()
        || request.eligibleActions().isEmpty()) {
      throw invalid("ELIGIBLE_ACTIONS_REQUIRED", "Eligible actions must be a non-empty array");
    }
    for (JsonNode action : request.eligibleActions()) {
      contractValidator.validate("semanticAction", action);
    }
    requireDigestMatch(request.observation(), request.observationDigest(), "observationDigest");
    requireDigestMatch(request.eligibleActions(), request.eligibilityDigest(), "eligibilityDigest");
    String expectedFingerprint = fingerprint(session, request);
    if (!constantEquals(expectedFingerprint, request.requestFingerprint())) {
      throw invalid("REQUEST_FINGERPRINT_MISMATCH", "Request fingerprint is not canonical");
    }
  }

  public String fingerprint(SessionRequest session, TurnRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", ProviderRuntimeModels.VERSION);
    values.put("sessionId", session.sessionId());
    values.put("provider", session.provider());
    values.put("modelId", session.modelId());
    values.put("turnId", request.turnId());
    values.put("requestId", request.requestId());
    values.put("idempotencyKey", request.idempotencyKey());
    values.put("observationDigest", request.observationDigest());
    values.put("eligibilityDigest", request.eligibilityDigest());
    values.put("identities", request.identities());
    values.put("sourceId", request.sourceId());
    values.put("promptDigest", session.promptDigest());
    values.put("toolsetDigest", session.toolsetDigest());
    values.put("knowledgeDigest", session.knowledgeDigest());
    values.put("goalPlanDigest", session.goalPlanDigest());
    values.put("taskSkillDigests", session.taskSkillDigests());
    values.put("estimatedInputTokens", request.estimatedInputTokens());
    values.put("estimatedOutputTokens", request.estimatedOutputTokens());
    values.put("deadline", request.deadline());
    values.put("decideOnly", request.decideOnly());
    return canonicalJson.digest(values);
  }

  private void requireDigestMatch(Object value, String supplied, String field) {
    requireDigest(supplied, field);
    if (!constantEquals(canonicalJson.digest(value), supplied)) {
      throw invalid("DIGEST_MISMATCH", field + " does not match canonical content");
    }
  }

  private void requireId(String value, String field) {
    if (value == null || !value.matches(ID)) {
      throw invalid("IDENTITY_INVALID", field + " must be a stable bounded identifier");
    }
  }

  private void requireDigest(String value, String field) {
    if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
      throw invalid("DIGEST_INVALID", field + " must be a SHA-256 digest");
    }
  }

  private boolean constantEquals(String left, String right) {
    return left != null
        && right != null
        && java.security.MessageDigest.isEqual(
            left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private ProviderRuntimeException invalid(String code, String message) {
    return new ProviderRuntimeException(code, message);
  }
}
