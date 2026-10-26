package com.chromascape.web.aiplayer.routine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an {@code sc815-candidate-execution-result/v1} against the request it claims to answer
 * (SC-2106 R9.3).
 *
 * <p>A result is accepted only when its recorded digest matches its own canonical body, it names
 * this request (ids, request digest, run id), the loaded package and core are the requested ones,
 * every consumed budget is within the request, and, for {@code OK}, the evidence is complete:
 * loaded (not advertised) identity, decisions and journal references, one reference per dispatched
 * intent, receipts, a reached goal, a clean teardown and no unknown capabilities.
 *
 * <p>Only the result status {@code OK} can become {@link Outcome#PASS}; build or workflow states
 * such as {@code BUILD_VALIDATED} or {@code APPLIED} are not result statuses and are invalid here.
 * {@code UNCERTAIN}, a required reconciliation or an unconfirmed teardown is always {@link
 * Outcome#UNCERTAIN}, whatever else the result says.
 */
public final class CandidateExecutionResultValidator {
  public static final int RESULT_SCHEMA_VERSION = 1;

  private static final Set<String> STATUSES =
      Set.of("OK", "BLOCKED", "FAILED", "CANCELLED", "UNCERTAIN");
  private static final List<String> BUDGETS =
      List.of("Steps", "Ticks", "Intents", "WallTimeMillis", "OutputBytes");

  private CandidateExecutionResultValidator() {}

  /** What a validated result means for the job. */
  public enum Outcome {
    PASS,
    BLOCKED,
    FAILED,
    CANCELLED,
    UNCERTAIN,
    INVALID
  }

  /**
   * The decision and every reason behind it. {@code certifiable} additionally requires a worker
   * that reported itself certifiable; a development worker never is.
   */
  public record Verdict(Outcome outcome, List<String> reasons, boolean certifiable) {
    public Verdict {
      reasons = List.copyOf(reasons);
    }
  }

  public static Verdict validate(Map<String, Object> result, CandidateExecutionRequestV2 request) {
    if (result == null) {
      return new Verdict(Outcome.UNCERTAIN, List.of("RESULT_MISSING"), false);
    }
    List<String> reasons = new ArrayList<>();
    if (asLong(result.get("schemaVersion")) != RESULT_SCHEMA_VERSION) {
      reasons.add("RESULT_SCHEMA_VERSION");
    }
    Object recorded = result.get("resultDigest");
    if (!(recorded instanceof String)) {
      reasons.add("RESULT_DIGEST_MISSING");
    } else {
      Map<String, Object> body = new LinkedHashMap<>(result);
      body.remove("resultDigest");
      if (!recorded.equals(Sc2106CanonicalJson.sha256(body))) {
        reasons.add("RESULT_DIGEST_MISMATCH");
      }
    }
    String requestDigest = request.requestDigest();
    same(reasons, result.get("requestId"), request.text("requestId"), "REQUEST_ID_MISMATCH");
    same(reasons, result.get("attemptId"), request.text("attemptId"), "ATTEMPT_ID_MISMATCH");
    same(reasons, result.get("requestDigest"), requestDigest, "REQUEST_DIGEST_MISMATCH");
    same(
        reasons,
        result.get("runId"),
        request.text("attemptId") + "/" + requestDigest.substring(7, 23),
        "RUN_ID_MISMATCH");

    String status = String.valueOf(result.get("status"));
    boolean ok = "OK".equals(status);
    if (!STATUSES.contains(status)) {
      reasons.add("STATUS_UNKNOWN:" + status);
    }
    Object failureCode = result.get("failureCode");
    if (ok && failureCode != null) {
      reasons.add("FAILURE_CODE_WITH_OK");
    }
    if (!ok && STATUSES.contains(status) && failureCode == null) {
      reasons.add("FAILURE_CODE_MISSING");
    }

    Map<String, Object> loaded = map(result.get("actualLoaded"));
    loadedDigest(reasons, loaded, "packageDigest", request, ok, "LOADED_PACKAGE_MISMATCH");
    loadedDigest(reasons, loaded, "coreDigest", request, ok, "LOADED_CORE_MISMATCH");

    for (String budget : BUDGETS) {
      long limit = request.budgetOf("max" + budget);
      long consumed = asLong(result.get("consumed" + budget));
      if (consumed < 0) {
        reasons.add("CONSUMED_INVALID:" + budget);
      } else if (limit >= 0 && consumed > limit) {
        reasons.add("BUDGET_EXCEEDED:" + budget);
      }
    }

    if (ok) {
      requireCompleteEvidence(reasons, result, loaded);
    }

    boolean uncertain =
        "UNCERTAIN".equals(status)
            || !"NOT_REQUIRED".equals(result.get("reconciliationState"))
            || !"CLEAN".equals(result.get("cleanupState"));
    Outcome outcome;
    if (uncertain) {
      if (!"CLEAN".equals(result.get("cleanupState"))) {
        reasons.add("CLEANUP_" + result.get("cleanupState"));
      }
      if (!"NOT_REQUIRED".equals(result.get("reconciliationState"))) {
        reasons.add("RECONCILIATION_" + result.get("reconciliationState"));
      }
      outcome = Outcome.UNCERTAIN;
    } else if (!reasons.isEmpty()) {
      outcome = Outcome.INVALID;
    } else {
      outcome = Outcome.valueOf(ok ? "PASS" : status);
    }
    boolean certifiable =
        outcome == Outcome.PASS && Boolean.TRUE.equals(loaded.get("workerCertifiable"));
    if (outcome == Outcome.PASS && !certifiable) {
      reasons.add(
          loaded.get("workerCertifiable") == null
              ? "WORKER_CERTIFIABILITY_UNKNOWN"
              : "WORKER_NOT_CERTIFIABLE");
    }
    return new Verdict(outcome, reasons, certifiable);
  }

  private static void requireCompleteEvidence(
      List<String> reasons, Map<String, Object> result, Map<String, Object> loaded) {
    if (!"LOADED".equals(loaded.get("evidence"))) {
      reasons.add("LOADED_EVIDENCE_MISSING");
    }
    if (!(result.get("decisionsRef") instanceof String)) {
      reasons.add("DECISIONS_REF_MISSING");
    }
    if (!(result.get("journalRef") instanceof String)) {
      reasons.add("JOURNAL_REF_MISSING");
    }
    long intents = asLong(result.get("consumedIntents"));
    if (list(result.get("intentRefs")).size() != intents) {
      reasons.add("INTENT_REFS_INCOMPLETE");
    }
    if (intents > 0 && list(result.get("receiptRefs")).isEmpty()) {
      reasons.add("RECEIPTS_MISSING");
    }
    if (map(result.get("terminalState")).isEmpty()) {
      reasons.add("TERMINAL_STATE_MISSING");
    }
    Map<String, Object> post = map(result.get("postconditions"));
    if (!Boolean.TRUE.equals(post.get("goalReached"))
        && !Boolean.TRUE.equals(post.get("goalAlreadyReached"))) {
      reasons.add("GOAL_NOT_REACHED");
    }
    if (!list(result.get("unknownCapabilities")).isEmpty()) {
      reasons.add("UNKNOWN_CAPABILITIES");
    }
  }

  private static void loadedDigest(
      List<String> reasons,
      Map<String, Object> loaded,
      String field,
      CandidateExecutionRequestV2 request,
      boolean required,
      String reason) {
    Object actual = loaded.get(field);
    if ((actual == null && required) || (actual != null && !actual.equals(request.text(field)))) {
      reasons.add(reason);
    }
  }

  private static void same(List<String> reasons, Object actual, String expected, String reason) {
    if (!expected.equals(actual)) {
      reasons.add(reason);
    }
  }

  private static long asLong(Object value) {
    if (value instanceof Integer || value instanceof Long || value instanceof Short) {
      return ((Number) value).longValue();
    }
    if (value instanceof java.math.BigInteger big && big.bitLength() <= 63) {
      return big.longValue();
    }
    return -1L;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
