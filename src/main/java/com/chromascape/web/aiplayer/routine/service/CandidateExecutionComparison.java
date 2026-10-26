package com.chromascape.web.aiplayer.routine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares a baseline run with a candidate run only when both answer equivalent conditions (SC-2106
 * R9.4).
 *
 * <p>Equivalence covers the world and task the run was asked to execute: scenario, checkpoint,
 * content, cache, profile, recording, provenance, task and settings hashes, execution mode, seeds,
 * runtime identities, dependency closure, policy version, budgets and cancellation policy. The
 * candidate identity itself is deliberately excluded, because a baseline and a candidate are
 * different artifacts. When any condition differs the comparison is neither equivalent nor
 * comparable and every difference is reported.
 *
 * <p>When the conditions are equivalent, only results that actually completed can be compared: both
 * must be {@code OK}, clean and without a pending reconciliation. Divergences then cover the
 * terminal state, postconditions (including inventory and level), decision reference, journal
 * reference, intent and receipt references and the consumed-step/tick/intent counts. Wall-clock and
 * byte counts are reported as metrics, never as divergences, because they are not reproducible
 * across runs.
 */
public final class CandidateExecutionComparison {
  private static final List<String> CONDITION_FIELDS =
      List.of(
          "scenarioId",
          "checkpointId",
          "contentVersion",
          "cacheVersion",
          "profileId",
          "recordingId",
          "initialProvenance",
          "taskHash",
          "settingsHash",
          "executionMode",
          "worldSeed",
          "taskSeed",
          "mouseSeed",
          "runnerVersion",
          "adapterVersion",
          "protocolVersion",
          "taskCoreEntry",
          "coreDigest",
          "dependencyClosure",
          "verifierPolicyVersion",
          "maxSteps",
          "maxTicks",
          "maxIntents",
          "maxWallTimeMillis",
          "maxMemoryBytes",
          "maxOutputBytes",
          "cancellationPolicy");

  private static final List<String> DIVERGENCE_FIELDS =
      List.of(
          "terminalState",
          "postconditions",
          "decisionCount",
          "decisionsRef",
          "journalRef",
          "intentRefs",
          "receiptRefs",
          "supportedCapabilities",
          "unknownCapabilities",
          "consumedSteps",
          "consumedTicks",
          "consumedIntents");

  private CandidateExecutionComparison() {}

  /** Whether the runs were comparable, what differed in the conditions, and what diverged. */
  public record Comparison(
      boolean equivalent,
      boolean comparable,
      List<String> differences,
      List<String> divergences,
      Map<String, Object> baseline,
      Map<String, Object> candidate) {
    public Comparison {
      differences = List.copyOf(differences);
      divergences = List.copyOf(divergences);
      baseline = Map.copyOf(baseline);
      candidate = Map.copyOf(candidate);
    }

    /** True only when both runs were comparable and their observed outcomes agree. */
    public boolean matched() {
      return comparable && divergences.isEmpty();
    }
  }

  /** Compares two finished jobs by their request and result documents. */
  public static Comparison compare(
      CandidateExecutionJobService.ExecutionJob baseline,
      CandidateExecutionJobService.ExecutionJob candidate) {
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(candidate, "candidate");
    return compare(baseline.request(), baseline.result(), candidate.request(), candidate.result());
  }

  /** Compares the wire maps; a missing result is reported as non-comparable, never as equal. */
  public static Comparison compare(
      Map<String, Object> baselineRequest,
      Map<String, Object> baselineResult,
      Map<String, Object> candidateRequest,
      Map<String, Object> candidateResult) {
    Objects.requireNonNull(baselineRequest, "baselineRequest");
    Objects.requireNonNull(candidateRequest, "candidateRequest");
    List<String> differences = new ArrayList<>();
    for (String field : CONDITION_FIELDS) {
      if (!Objects.equals(baselineRequest.get(field), candidateRequest.get(field))) {
        differences.add(condition(field));
      }
    }
    boolean equivalent = differences.isEmpty();

    if (baselineResult == null) {
      differences.add("BASELINE_RESULT_MISSING");
    } else {
      notComparable(differences, baselineResult, "BASELINE");
    }
    if (candidateResult == null) {
      differences.add("CANDIDATE_RESULT_MISSING");
    } else {
      notComparable(differences, candidateResult, "CANDIDATE");
    }
    boolean comparable = equivalent && differences.isEmpty();

    List<String> divergences = new ArrayList<>();
    if (comparable) {
      for (String field : DIVERGENCE_FIELDS) {
        if (!Objects.equals(baselineResult.get(field), candidateResult.get(field))) {
          divergences.add(divergence(field));
        }
      }
    }
    return new Comparison(
        equivalent,
        comparable,
        differences,
        divergences,
        metrics(baselineResult),
        metrics(candidateResult));
  }

  private static void notComparable(
      List<String> differences, Map<String, Object> result, String side) {
    if (!"OK".equals(result.get("status"))) {
      differences.add(side + "_STATUS_NOT_OK");
    }
    if (!"CLEAN".equals(result.get("cleanupState"))) {
      differences.add(side + "_CLEANUP_NOT_CLEAN");
    }
    if (!"NOT_REQUIRED".equals(result.get("reconciliationState"))) {
      differences.add(side + "_RECONCILIATION_PENDING");
    }
  }

  private static Map<String, Object> metrics(Map<String, Object> result) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    if (result == null) {
      return metrics;
    }
    for (String field :
        List.of(
            "status",
            "decisionCount",
            "consumedSteps",
            "consumedTicks",
            "consumedIntents",
            "consumedWallTimeMillis",
            "consumedOutputBytes")) {
      metrics.put(field, result.get(field));
    }
    return metrics;
  }

  private static String condition(String field) {
    return "CONDITION_" + snake(field);
  }

  private static String divergence(String field) {
    return "DIVERGENCE_" + snake(field);
  }

  private static String snake(String field) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        out.append('_');
      }
      out.append(Character.toUpperCase(c));
    }
    return out.toString();
  }
}
