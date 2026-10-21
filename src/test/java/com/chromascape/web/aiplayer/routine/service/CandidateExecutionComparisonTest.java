package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R9.4: a comparison is only made under equivalent conditions, and non-equivalence is reported. */
class CandidateExecutionComparisonTest {
  @Test
  void equivalentCleanRunsWithEqualOutcomesMatch() {
    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), request(), result("OK"));

    assertTrue(comparison.equivalent());
    assertTrue(comparison.comparable());
    assertEquals(List.of(), comparison.differences());
    assertEquals(List.of(), comparison.divergences());
    assertTrue(comparison.matched());
  }

  @Test
  void aBaseResultWithoutACandidateIsNotAComparison() {
    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), request(), null);

    assertTrue(comparison.equivalent());
    assertFalse(comparison.comparable());
    assertTrue(comparison.differences().contains("CANDIDATE_RESULT_MISSING"));
    assertFalse(comparison.matched());
  }

  @Test
  void aDifferentScenarioIsReportedAsNonEquivalent() {
    Map<String, Object> other = request();
    other.put("scenarioId", "scenario-other");

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), other, result("OK"));

    assertFalse(comparison.equivalent());
    assertFalse(comparison.comparable());
    assertEquals(List.of("CONDITION_SCENARIO_ID"), comparison.differences());
    assertFalse(comparison.matched());
  }

  @Test
  void differentBudgetsOrSeedsAreReported() {
    Map<String, Object> other = request();
    other.put("maxSteps", 99L);
    other.put("worldSeed", "another-seed");

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), other, result("OK"));

    assertEquals(List.of("CONDITION_WORLD_SEED", "CONDITION_MAX_STEPS"), comparison.differences());
  }

  @Test
  void theCandidateIdentityItselfDoesNotBreakEquivalence() {
    Map<String, Object> other = request();
    other.put("requestId", "request-other");
    other.put("attemptId", "attempt-other");
    other.put("jobId", "job-other");
    other.put("candidateDigest", digest("d"));
    other.put("packageDigest", digest("e"));
    other.put("signedManifestRef", digest("f"));
    other.put("executionClosureRef", digest("1"));

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), other, result("OK"));

    assertTrue(comparison.equivalent(), comparison.differences().toString());
    assertTrue(comparison.comparable());
    assertTrue(comparison.matched());
  }

  @Test
  void aDifferentCoreIsNonEquivalentButADifferentCandidatePackageIsNot() {
    Map<String, Object> other = request();
    other.put("coreDigest", digest("2"));

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), other, result("OK"));

    assertEquals(List.of("CONDITION_CORE_DIGEST"), comparison.differences());
  }

  @Test
  void equalConditionsWithDifferentPostconditionsDiverge() {
    Map<String, Object> other = result("OK");
    other.put("postconditions", Map.of("goalReached", true, "fishingLevel", 4));

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), request(), other);

    assertTrue(comparison.comparable());
    assertEquals(List.of("DIVERGENCE_POSTCONDITIONS"), comparison.divergences());
    assertFalse(comparison.matched());
  }

  @Test
  void aFailedBaselineIsNotComparable() {
    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("FAILED"), request(), result("OK"));

    assertTrue(comparison.equivalent());
    assertFalse(comparison.comparable());
    assertTrue(comparison.differences().contains("BASELINE_STATUS_NOT_OK"));
  }

  @Test
  void aPendingReconciliationIsNotComparable() {
    Map<String, Object> other = result("OK");
    other.put("reconciliationState", "RECONCILING");

    CandidateExecutionComparison.Comparison comparison =
        CandidateExecutionComparison.compare(request(), result("OK"), request(), other);

    assertFalse(comparison.comparable());
    assertTrue(comparison.differences().contains("CANDIDATE_RECONCILIATION_PENDING"));
    assertEquals(List.of(), comparison.divergences(), "divergences need a comparable pair");
  }

  private static Map<String, Object> request() {
    return CandidateExecutionRequestV2.builder()
        .requestId("request-1")
        .idempotencyKey("key-1")
        .certificationRunId("run-1")
        .attemptId("attempt-1")
        .jobId("job-1")
        .proposalDigest(digest("a"))
        .approvalDigest(digest("b"))
        .baseCommit("5a7c30f8")
        .candidateDigest(digest("c"))
        .packageDigest(digest("d"))
        .coreDigest(digest("e"))
        .signedManifestRef(digest("f"))
        .executionClosureRef(digest("1"))
        .verifierPolicyVersion("sc814-trust-policy/1")
        .taskCoreEntry("net.runelite.Task")
        .runnerVersion("runner-1")
        .adapterVersion("adapter-1")
        .protocolVersion("xrsps-candidate-session/v1")
        .dependencyClosure(List.of(digest("2"), digest("3")))
        .baselineId("baseline-1")
        .scenarioId("scenario-1")
        .checkpointId("checkpoint-1")
        .contentVersion("content-1")
        .cacheVersion("cache-1")
        .profileId("profile-1")
        .recordingId("recording-1")
        .initialProvenance("AUTHORED")
        .taskHash(digest("4"))
        .settingsHash(digest("5"))
        .executionMode(CandidateExecutionRequestV2.CAUSAL_CANDIDATE)
        .worldSeed("world-seed")
        .taskSeed("task-seed")
        .mouseSeed("mouse-seed")
        .maxSteps(200)
        .maxTicks(400)
        .maxIntents(20)
        .maxWallTimeMillis(60_000)
        .maxMemoryBytes(1 << 20)
        .maxOutputBytes(1 << 16)
        .build()
        .toMap();
  }

  private static Map<String, Object> result(String status) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", status);
    result.put("cleanupState", "CLEAN");
    result.put("reconciliationState", "NOT_REQUIRED");
    result.put("terminalState", Map.of("task", "COMPLETE"));
    result.put("postconditions", Map.of("goalReached", true, "fishingLevel", 3));
    result.put("decisionCount", 12);
    result.put("decisionsRef", digest("6"));
    result.put("journalRef", digest("7"));
    result.put("intentRefs", List.of(digest("8")));
    result.put("receiptRefs", List.of(digest("9")));
    result.put("supportedCapabilities", List.of("INTERACT_NPC"));
    result.put("unknownCapabilities", List.of());
    result.put("consumedSteps", 12);
    result.put("consumedTicks", 40);
    result.put("consumedIntents", 1);
    result.put("consumedWallTimeMillis", 5_000);
    result.put("consumedOutputBytes", 1_024);
    return result;
  }

  private static String digest(String nibble) {
    return "sha256:" + nibble.repeat(64);
  }
}
