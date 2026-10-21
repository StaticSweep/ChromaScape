package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ChromaScape producer against the Micro6-generated {@code candidate-execution-v2-vectors.json}
 * (SC-2106 R9.1, R9.3): the request builder must reproduce the consumer's canonical request and
 * digest, and the result validator must accept the consumer's result and refuse every way a result
 * could claim success it does not prove.
 */
class CandidateExecutionV2ContractTest {
  private static final String VECTORS = "sc2106/candidate-execution-v2-vectors.json";
  private static final String VECTORS_SHA256 =
      "7f8dd7a20be4f072601213c1eea4f2bfdd3a440707f03e905f7be31f7538c01b";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private JsonNode vectors;
  private CandidateExecutionRequestV2 request;

  @BeforeEach
  void load() throws Exception {
    byte[] raw;
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream(VECTORS)) {
      raw = stream.readAllBytes();
    }
    assertEquals(
        VECTORS_SHA256,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)),
        "v2 vectors changed; copy them from Micro6 and update both pins together");
    vectors = MAPPER.readTree(raw);
    request = fromVector(vectors.path("request"));
  }

  @Test
  void theBuilderReproducesTheConsumersCanonicalRequestAndDigest() {
    assertEquals(
        vectors.path("requestCanonical").asText(),
        Sc2106CanonicalJson.write(request.canonicalBody()));
    assertEquals(vectors.path("requestDigest").asText(), request.requestDigest());
    assertEquals(vectors.path("requestDigest").asText(), request.toMap().get("requestDigest"));
  }

  @Test
  void theConsumersResultCanonicalizesIdentically() throws Exception {
    Map<String, Object> result = result();
    Map<String, Object> body = new LinkedHashMap<>(result);
    body.remove("resultDigest");
    assertEquals(vectors.path("resultCanonical").asText(), Sc2106CanonicalJson.write(body));
    assertEquals(vectors.path("resultDigest").asText(), Sc2106CanonicalJson.sha256(body));
  }

  @Test
  void theConsumersOkResultPassesAndIsCertifiable() throws Exception {
    CandidateExecutionResultValidator.Verdict verdict =
        CandidateExecutionResultValidator.validate(result(), request);
    assertEquals(
        CandidateExecutionResultValidator.Outcome.PASS,
        verdict.outcome(),
        verdict.reasons().toString());
    assertTrue(verdict.certifiable());
    assertEquals(List.of(), verdict.reasons());
  }

  @Test
  void workflowStatesAreNeverResultStatuses() throws Exception {
    for (String state : List.of("BUILD_VALIDATED", "APPLIED", "REGISTERED", "BUILT")) {
      CandidateExecutionResultValidator.Verdict verdict = verdict(r -> r.put("status", state));
      assertEquals(CandidateExecutionResultValidator.Outcome.INVALID, verdict.outcome(), state);
      assertTrue(
          verdict.reasons().contains("STATUS_UNKNOWN:" + state), verdict.reasons().toString());
      assertFalse(verdict.certifiable());
    }
  }

  @Test
  void uncertainNeverBecomesPass() throws Exception {
    assertOutcome(
        CandidateExecutionResultValidator.Outcome.UNCERTAIN,
        r -> {
          r.put("status", "UNCERTAIN");
          r.put("failureCode", "UNCERTAIN_OUTCOME");
          r.put("reconciliationState", "REQUIRED");
        });
    assertOutcome(
        CandidateExecutionResultValidator.Outcome.UNCERTAIN,
        r -> r.put("cleanupState", "CLEANUP_UNCERTAIN"));
    assertOutcome(
        CandidateExecutionResultValidator.Outcome.UNCERTAIN,
        r -> r.put("reconciliationState", "REQUIRED"));
  }

  @Test
  void aResultForAnotherRequestIsInvalid() throws Exception {
    assertReason("REQUEST_DIGEST_MISMATCH", r -> r.put("requestDigest", sha("other")));
    assertReason("REQUEST_ID_MISMATCH", r -> r.put("requestId", "request-other"));
    assertReason("ATTEMPT_ID_MISMATCH", r -> r.put("attemptId", "attempt-other"));
    assertReason("RUN_ID_MISMATCH", r -> r.put("runId", "attempt-v2-1/0000000000000000"));
  }

  @Test
  void loadedBytesOtherThanTheRequestedOnesAreInvalid() throws Exception {
    assertReason("LOADED_CORE_MISMATCH", r -> loaded(r).put("coreDigest", sha("other-core")));
    assertReason("LOADED_PACKAGE_MISMATCH", r -> loaded(r).remove("packageDigest"));
    assertReason("LOADED_EVIDENCE_MISSING", r -> loaded(r).put("evidence", "STAGED"));
  }

  @Test
  void budgetsBeyondTheRequestAreInvalid() throws Exception {
    assertReason("BUDGET_EXCEEDED:Steps", r -> r.put("consumedSteps", 201L));
    assertReason("BUDGET_EXCEEDED:Intents", r -> r.put("consumedIntents", 51L));
    assertReason("BUDGET_EXCEEDED:WallTimeMillis", r -> r.put("consumedWallTimeMillis", 120001L));
    assertReason("BUDGET_EXCEEDED:OutputBytes", r -> r.put("consumedOutputBytes", 1048577L));
  }

  @Test
  void anOkResultMustCarryCompleteEvidence() throws Exception {
    assertReason("JOURNAL_REF_MISSING", r -> r.put("journalRef", null));
    assertReason("DECISIONS_REF_MISSING", r -> r.put("decisionsRef", null));
    assertReason("INTENT_REFS_INCOMPLETE", r -> r.put("intentRefs", List.of(sha("intent-0"))));
    assertReason("RECEIPTS_MISSING", r -> r.put("receiptRefs", List.of()));
    assertReason("GOAL_NOT_REACHED", r -> postconditions(r).put("goalReached", false));
    assertReason("UNKNOWN_CAPABILITIES", r -> r.put("unknownCapabilities", List.of("TELEPORT")));
    assertReason("FAILURE_CODE_WITH_OK", r -> r.put("failureCode", "TASK_BLOCKED"));
  }

  @Test
  void aTamperedResultFailsItsOwnDigest() throws Exception {
    Map<String, Object> result = result();
    result.put("consumedSteps", 11L);
    CandidateExecutionResultValidator.Verdict verdict =
        CandidateExecutionResultValidator.validate(result, request);
    assertEquals(CandidateExecutionResultValidator.Outcome.INVALID, verdict.outcome());
    assertTrue(verdict.reasons().contains("RESULT_DIGEST_MISMATCH"));
  }

  @Test
  void aBlockedResultIsBlockedNotPassed() throws Exception {
    CandidateExecutionResultValidator.Verdict verdict =
        verdict(
            r -> {
              r.put("status", "BLOCKED");
              r.put("failureCode", "TASK_BLOCKED");
              postconditions(r).put("goalReached", false);
            });
    assertEquals(CandidateExecutionResultValidator.Outcome.BLOCKED, verdict.outcome());
    assertFalse(verdict.certifiable());
  }

  @Test
  void aDevelopmentWorkerPassesButIsNotCertifiable() throws Exception {
    CandidateExecutionResultValidator.Verdict verdict =
        verdict(r -> loaded(r).put("workerCertifiable", false));
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, verdict.outcome());
    assertFalse(verdict.certifiable());
    assertEquals(List.of("WORKER_NOT_CERTIFIABLE"), verdict.reasons());
  }

  @Test
  void theBuilderRefusesAnIncompleteOrUnsafeRequest() {
    assertThrows(
        IllegalArgumentException.class,
        () -> builder(vectors.path("request")).executionClosureRef(null).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> builder(vectors.path("request")).baseCommit("main").build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            builder(vectors.path("request"))
                .dependencyClosure(List.of(sha("a"), sha("a")))
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            builder(vectors.path("request"))
                .maxSteps(-1)
                .maxTicks(-1)
                .maxWallTimeMillis(-1)
                .build());
  }

  private void assertOutcome(
      CandidateExecutionResultValidator.Outcome expected, Consumer<Map<String, Object>> change)
      throws Exception {
    CandidateExecutionResultValidator.Verdict verdict = verdict(change);
    assertEquals(expected, verdict.outcome(), verdict.reasons().toString());
    assertFalse(verdict.certifiable());
  }

  private void assertReason(String reason, Consumer<Map<String, Object>> change) throws Exception {
    CandidateExecutionResultValidator.Verdict verdict = verdict(change);
    assertEquals(CandidateExecutionResultValidator.Outcome.INVALID, verdict.outcome(), reason);
    assertTrue(verdict.reasons().contains(reason), reason + " not in " + verdict.reasons());
    assertFalse(verdict.certifiable());
  }

  /** Applies {@code change} and re-seals the digest, so only the semantic rule is exercised. */
  private CandidateExecutionResultValidator.Verdict verdict(Consumer<Map<String, Object>> change)
      throws Exception {
    Map<String, Object> result = result();
    change.accept(result);
    result.remove("resultDigest");
    result.put("resultDigest", Sc2106CanonicalJson.sha256(result));
    return CandidateExecutionResultValidator.validate(result, request);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> result() throws Exception {
    return MAPPER.convertValue(vectors.path("result"), LinkedHashMap.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loaded(Map<String, Object> result) {
    return (Map<String, Object>) result.get("actualLoaded");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> postconditions(Map<String, Object> result) {
    return (Map<String, Object>) result.get("postconditions");
  }

  private static String sha(String value) {
    return Sc2106CanonicalJson.sha256(value);
  }

  static CandidateExecutionRequestV2 fromVector(JsonNode v) {
    return builder(v).build();
  }

  private static CandidateExecutionRequestV2.Builder builder(JsonNode v) {
    List<String> dependencies = new ArrayList<>();
    v.path("dependencyClosure").forEach(d -> dependencies.add(d.asText()));
    return CandidateExecutionRequestV2.builder()
        .requestId(v.path("requestId").asText())
        .idempotencyKey(v.path("idempotencyKey").asText())
        .certificationRunId(v.path("certificationRunId").asText())
        .attemptId(v.path("attemptId").asText())
        .jobId(v.path("jobId").asText())
        .proposalDigest(v.path("proposalDigest").asText())
        .approvalDigest(v.path("approvalDigest").asText())
        .baseCommit(v.path("baseCommit").asText())
        .candidateDigest(v.path("candidateDigest").asText())
        .packageDigest(v.path("packageDigest").asText())
        .coreDigest(v.path("coreDigest").asText())
        .signedManifestRef(v.path("signedManifestRef").asText())
        .executionClosureRef(v.path("executionClosureRef").asText())
        .verifierPolicyVersion(v.path("verifierPolicyVersion").asText())
        .taskCoreEntry(v.path("taskCoreEntry").asText())
        .runnerVersion(v.path("runnerVersion").asText())
        .adapterVersion(v.path("adapterVersion").asText())
        .protocolVersion(v.path("protocolVersion").asText())
        .dependencyClosure(dependencies)
        .baselineId(v.path("baselineId").asText())
        .scenarioId(v.path("scenarioId").asText())
        .checkpointId(v.path("checkpointId").isNull() ? null : v.path("checkpointId").asText())
        .contentVersion(v.path("contentVersion").asText())
        .cacheVersion(v.path("cacheVersion").asText())
        .profileId(v.path("profileId").asText())
        .recordingId(v.path("recordingId").asText())
        .initialProvenance(v.path("initialProvenance").asText())
        .taskHash(v.path("taskHash").asText())
        .settingsHash(v.path("settingsHash").asText())
        .executionMode(v.path("executionMode").asText())
        .worldSeed(v.path("worldSeed").asText())
        .taskSeed(v.path("taskSeed").asText())
        .mouseSeed(v.path("mouseSeed").asText())
        .maxSteps(v.path("maxSteps").asLong())
        .maxTicks(v.path("maxTicks").asLong())
        .maxIntents(v.path("maxIntents").asLong())
        .maxWallTimeMillis(v.path("maxWallTimeMillis").asLong())
        .maxMemoryBytes(v.path("maxMemoryBytes").asLong())
        .maxOutputBytes(v.path("maxOutputBytes").asLong())
        .cancellationPolicy(v.path("cancellationPolicy").asText());
  }
}
