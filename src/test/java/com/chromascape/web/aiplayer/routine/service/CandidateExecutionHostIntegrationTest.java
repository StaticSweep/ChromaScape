package com.chromascape.web.aiplayer.routine.service;

import static com.chromascape.web.aiplayer.routine.service.Sc2106RealHost.MAPPER;
import static com.chromascape.web.aiplayer.routine.service.Sc2106RealHost.PROPOSAL;
import static com.chromascape.web.aiplayer.routine.service.Sc2106RealHost.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-2106 R9.1 end to end, run only by {@code :sc2106HostIntegrationTest}. {@link
 * CandidateExecutionJobService} runs a ChromaScape-signed candidate through the real {@code
 * CandidateHostMain} process (see {@link Sc2106RealHost}): the host resolves and verifies the
 * candidate, the runner drives the shared Fishing core on the real XRSPS stdio worker, and the
 * result comes back through {@link CandidateExecutionResultValidator}.
 */
class CandidateExecutionHostIntegrationTest {
  @TempDir static Path temp;

  @BeforeAll
  static void signCandidate() throws Exception {
    Sc2106RealHost.prepare(temp);
  }

  @Test
  void aChromaScapeSignedCandidateReachesItsGoalThroughTheRealHost() throws Exception {
    CandidateExecutionJobService.ExecutionJob job =
        service(policy("trusted", Sc2106RealHost.keys.getPublic()))
            .execute(PROPOSAL, Sc2106RealHost.spec("job-e2e-1"));

    assertEquals(
        CandidateExecutionResultValidator.Outcome.PASS,
        job.outcome(),
        job.reasons() + "\n" + job.hostOutcome());
    Map<String, Object> result = job.result();
    assertEquals(job.requestDigest(), result.get("requestDigest"));
    assertEquals(Boolean.TRUE, map(result.get("postconditions")).get("goalReached"));
    JsonNode closure =
        MAPPER.readTree(
            Files.readAllBytes(Sc2106RealHost.bundle.resolve("execution-closure.json")));
    Map<String, Object> loaded = map(result.get("actualLoaded"));
    assertEquals(closure.path("core").path("sha256").asText(), loaded.get("coreDigest"));
    assertEquals(closure.path("package").path("sha256").asText(), loaded.get("packageDigest"));
    assertEquals(Boolean.TRUE, job.hostOutcome().get("dispatched"));
    assertFalse(job.certifiable(), "a development worker is never certifiable");
    assertTrue(job.reasons().contains("WORKER_NOT_CERTIFIABLE"), job.reasons().toString());
    assertEquals(
        List.of("WORKER_NOT_CERTIFIABLE"),
        map(job.hostOutcome().get("certification")).get("reasons"),
        "the host refuses certification for the development worker and nothing else");
    // R4.2: the owner accepted the real worker's binding before the runner acted on it.
    List<CandidateAttemptStore.WorkerBinding> bindings = store.bindings.get(job.attemptId());
    assertEquals(1, bindings.size(), String.valueOf(bindings));
    CandidateAttemptStore.WorkerBinding binding = bindings.get(0);
    assertEquals(1L, binding.generation());
    assertEquals("CREATE", binding.reason());
    assertEquals(job.requestDigest(), binding.requestDigest());
    assertEquals("candidate-stdio/v1", binding.workerAuthority());
    assertEquals("sc2106-" + job.attemptId(), binding.workerSessionId());
    assertEquals(0L, binding.runnerIntents(), "bound before the first intent");
    assertTrue(binding.workerLeaseDigest().matches("sha256:[0-9a-f]{64}"));
  }

  @Test
  void aHostThatTrustsAnotherKeyRefusesBeforeLaunch() throws Exception {
    PublicKey other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
    CandidateExecutionJobService.ExecutionJob job =
        service(policy("other", other)).execute(PROPOSAL, Sc2106RealHost.spec("job-e2e-2"));

    assertEquals(
        CandidateExecutionResultValidator.Outcome.FAILED,
        job.outcome(),
        job.reasons() + "\n" + job.hostOutcome());
    assertEquals(Boolean.FALSE, job.hostOutcome().get("dispatched"));
    assertEquals(
        "SIGNATURE_INVALID", job.result().get("failureCode"), String.valueOf(job.result()));
    assertTrue(
        job.reasons().contains("HOST_CERTIFICATION:NOT_DISPATCHED"), job.reasons().toString());
    assertFalse(job.certifiable());
  }

  /** The attempt store of the last service built; the owner's durable record of the run. */
  private InMemoryCandidateAttemptStore store;

  private CandidateExecutionJobService service(Path policy) {
    store = new InMemoryCandidateAttemptStore();
    return new CandidateExecutionJobService(
        Sc2106RealHost.workflow(), Sc2106RealHost.properties(temp, policy), store);
  }

  private static Path policy(String name, PublicKey key) throws Exception {
    return Sc2106RealHost.policy(temp, name, key);
  }
}
