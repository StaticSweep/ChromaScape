package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/**
 * SC-2106 R9.1/R9.3 in ChromaScape: the request is built only from a registered bundle (here the
 * producer-generated closure vector, signed with the contract TEST-ONLY key), the bundle is staged
 * content-addressed, and only a validated result can pass. The host is a stub; the real host runs
 * in {@code CandidateExecutionHostIntegrationTest}.
 */
class CandidateExecutionJobServiceTest {
  /** Durable ownership double; one per test instance, shared by every service it builds. */
  private final InMemoryCandidateAttemptStore store = new InMemoryCandidateAttemptStore();

  /** The reviewed SC-1914 Fishing profile reference (xrsps-osrs237-fishing r1). */
  static final String PROFILE =
      "xrsps-osrs237-fishing@1#sha256:43d1d3008222c66623f03cedc6f2e08f091ac50c692c80d1456498ea0a58d0ba";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROPOSAL = "proposal-1";

  @TempDir Path temp;

  private Path bundle;
  private JsonNode manifest;
  private JsonNode closure;
  private byte[] manifestBytes;
  private byte[] closureBytes;
  private PatchCandidateWorkflowService workflow;
  private Sc2106ExecutionProperties properties;

  @BeforeEach
  void stageRegisteredBundle() throws Exception {
    JsonNode vector =
        MAPPER.readTree(
            Files.readAllBytes(
                vectorsPath().getParent().resolve("execution-closure-vectors.json")));
    bundle = Files.createDirectories(temp.resolve("bundle/cand_vector"));
    manifestBytes = decode(vector, "manifest");
    closureBytes = decode(vector, "closure");
    manifest = MAPPER.readTree(manifestBytes);
    closure = MAPPER.readTree(closureBytes);
    String jarName = manifest.path("jar_name").asText("candidate.jar");
    String sbomName = manifest.path("sbom_name").asText("candidate.sbom.json");
    Files.write(bundle.resolve("manifest.json"), manifestBytes);
    Files.write(bundle.resolve("execution-closure.json"), closureBytes);
    Files.write(bundle.resolve("certification.json"), decode(vector, "certification"));
    Files.write(bundle.resolve(jarName), decode(vector, "jar"));
    Files.write(bundle.resolve(sbomName), decode(vector, "sbom"));
    Files.write(
        bundle.resolve(closure.path("package").path("name").asText()), decode(vector, "runner"));
    Files.write(bundle.resolve(closure.path("core").path("name").asText()), decode(vector, "core"));
    Files.write(
        bundle.resolve(closure.path("dependencies").get(0).path("name").asText()),
        decode(vector, "dependency"));

    workflow = mock(PatchCandidateWorkflowService.class);
    when(workflow.registeredCandidate(PROPOSAL))
        .thenReturn(
            new PatchCandidateWorkflowService.RegisteredCandidate(
                PROPOSAL, "cand_vector", bundle, jarName, sbomName));
    properties = new Sc2106ExecutionProperties();
    properties.setEnabled(true);
    properties.setStoreRoot(temp.resolve("store").toString());
    properties.setWorkspaceRoot(temp.resolve("workspaces").toString());
    properties.setRunRoot(temp.resolve("runs").toString());
    properties.setVerifierPolicyVersion("sc814-trust-policy/1");
    properties.setWorkerCommand(List.of("node", "dist/worker/main.js", "--stdio"));
    properties.setWorkerDirectory("/opt/xrsps");
    properties.setDevelopmentWorker(true);
  }

  @Test
  void theRequestComesFromTheSignedManifestAndClosure() {
    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    CandidateExecutionJobService service =
        service(
            (job, timeout, cancel) -> {
              sent.set(job);
              return lost();
            });

    CandidateExecutionJobService.ExecutionJob job = service.execute(PROPOSAL, spec("job-1"));

    Map<String, Object> request = job.request();
    assertEquals(manifest.path("proposal_hash").asText(), request.get("proposalDigest"));
    assertEquals(manifest.path("approval_hash").asText(), request.get("approvalDigest"));
    assertEquals(manifest.path("base_commit").asText(), request.get("baseCommit"));
    assertEquals(
        manifest.path("candidate").path("candidate_hash").asText(), request.get("candidateDigest"));
    assertEquals(sha(manifestBytes), request.get("signedManifestRef"));
    assertEquals(sha(closureBytes), request.get("executionClosureRef"));
    assertEquals(closure.path("package").path("sha256").asText(), request.get("packageDigest"));
    assertEquals(closure.path("core").path("sha256").asText(), request.get("coreDigest"));
    assertEquals(
        List.of(closure.path("dependencies").get(0).path("sha256").asText()),
        request.get("dependencyClosure"));
    assertEquals(closure.path("runner_version").asText(), request.get("runnerVersion"));
    assertEquals(closure.path("adapter_version").asText(), request.get("adapterVersion"));
    assertEquals(closure.path("protocol_version").asText(), request.get("protocolVersion"));
    assertEquals(closure.path("task_core_entry").asText(), request.get("taskCoreEntry"));
    assertEquals(2, request.get("schemaVersion"));
    assertEquals(Sc2106CanonicalJson.sha256(settings()), request.get("settingsHash"));

    Map<String, Object> wire = map(sent.get().get("request"));
    Map<String, Object> body = new LinkedHashMap<>(wire);
    body.remove("requestDigest");
    assertEquals(Sc2106CanonicalJson.sha256(body), wire.get("requestDigest"));
    assertEquals(job.requestDigest(), wire.get("requestDigest"));
    assertEquals("sc2106-host-job/v1", sent.get().get("schema"));
    assertEquals(Boolean.TRUE, map(sent.get().get("worker")).get("developmentIdentity"));
    assertTrue(
        Files.isDirectory(Path.of(String.valueOf(sent.get().get("workingDirectory")))),
        "each attempt gets its own run directory");
  }

  @Test
  void everyBundleFileIsStagedUnderItsDigest() throws Exception {
    service((job, timeout, cancel) -> lost()).execute(PROPOSAL, spec("job-1"));

    try (var stored = Files.list(temp.resolve("store/sha256"))) {
      List<Path> entries = stored.toList();
      assertEquals(8, entries.size(), entries.toString());
      for (Path entry : entries) {
        assertEquals("sha256:" + entry.getFileName(), sha(Files.readAllBytes(entry)));
      }
    }
  }

  @Test
  void aCorruptStoreEntryStopsTheJobBeforeTheHost() throws Exception {
    Path store = Files.createDirectories(temp.resolve("store/sha256"));
    Files.writeString(store.resolve(sha(closureBytes).substring(7)), "not the closure");
    AtomicBoolean called = new AtomicBoolean();

    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () ->
                service(
                        (job, timeout, cancel) -> {
                          called.set(true);
                          return lost();
                        })
                    .execute(PROPOSAL, spec("job-1")));

    assertEquals("EXECUTION_STAGING_FAILED", e.code());
    assertFalse(called.get());
  }

  @Test
  void aValidatedOkResultPassesButADevelopmentWorkerIsNotCertifiable() {
    CandidateExecutionJobService.ExecutionJob job =
        service((j, timeout, cancel) -> answer(j, true, true, List.of("WORKER_NOT_CERTIFIABLE")))
            .execute(PROPOSAL, spec("job-1"));

    assertEquals(
        CandidateExecutionResultValidator.Outcome.PASS, job.outcome(), job.reasons().toString());
    assertFalse(job.certifiable());
    assertTrue(job.reasons().contains("WORKER_NOT_CERTIFIABLE"));
    assertTrue(job.reasons().contains("HOST_CERTIFICATION:WORKER_NOT_CERTIFIABLE"));
  }

  @Test
  void onlyBothVerdictsTogetherMakeARunCertifiable() {
    CandidateExecutionJobService.ExecutionJob certified =
        service((j, timeout, cancel) -> answer(j, false, true, List.of()))
            .execute(PROPOSAL, spec("job-1"));
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, certified.outcome());
    assertTrue(certified.certifiable());

    CandidateExecutionJobService.ExecutionJob hostRefused =
        service(
                (j, timeout, cancel) ->
                    answer(j, false, false, List.of("CLEANUP_CLEANUP_UNCERTAIN")))
            .execute(PROPOSAL, spec("job-2"));
    assertFalse(hostRefused.certifiable());
  }

  @Test
  void aResultForAnotherRequestIsInvalid() {
    CandidateExecutionJobService.ExecutionJob job =
        service(
                (j, timeout, cancel) -> {
                  CandidateHostLauncher.HostOutcome outcome = answer(j, false, true, List.of());
                  Map<String, Object> result = map(outcome.document().get("result"));
                  result.put("requestDigest", Sc2106CanonicalJson.sha256("another request"));
                  reseal(result);
                  return outcome;
                })
            .execute(PROPOSAL, spec("job-1"));

    assertEquals(CandidateExecutionResultValidator.Outcome.INVALID, job.outcome());
    assertFalse(job.certifiable());
  }

  @Test
  void aLostHostIsUncertainAndARefusedStartRanNothing() {
    CandidateExecutionJobService.ExecutionJob lost =
        service((j, timeout, cancel) -> lost()).execute(PROPOSAL, spec("job-1"));
    assertEquals(CandidateExecutionResultValidator.Outcome.UNCERTAIN, lost.outcome());
    assertNull(lost.result());

    CandidateExecutionJobService.ExecutionJob notStarted =
        service(
                (j, timeout, cancel) ->
                    new CandidateHostLauncher.HostOutcome(
                        null, false, "HOST_START_FAILED: no java", null, ""))
            .execute(PROPOSAL, spec("job-2"));
    assertEquals(CandidateExecutionResultValidator.Outcome.FAILED, notStarted.outcome());

    Map<String, Object> rejected = new LinkedHashMap<>();
    rejected.put("schema", "sc2106-host-outcome/v1");
    rejected.put("rejected", Map.of("failureCode", "REQUEST_INVALID", "detail", "v1"));
    CandidateExecutionJobService.ExecutionJob refused =
        service(
                (j, timeout, cancel) ->
                    new CandidateHostLauncher.HostOutcome(rejected, false, null, 3, ""))
            .execute(PROPOSAL, spec("job-3"));
    assertEquals(CandidateExecutionResultValidator.Outcome.FAILED, refused.outcome());
    assertEquals(List.of("HOST_REJECTED:REQUEST_INVALID"), refused.reasons());
  }

  @Test
  void aRunningJobIdIsRefusedAndCanBeCancelled() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              started.countDown();
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
              while (!cancel.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
              }
              sawCancel.set(cancel.get());
              return lost();
            });
    Thread first = new Thread(() -> service.execute(PROPOSAL, spec("job-1")));
    first.start();
    assertTrue(started.await(10, TimeUnit.SECONDS));

    PatchCandidateWorkflowService.PatchWorkflowException duplicate =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service.execute(PROPOSAL, spec("job-1")));
    assertEquals("EXECUTION_JOB_ACTIVE", duplicate.code());
    assertEquals(HttpStatus.CONFLICT, duplicate.status());

    assertTrue(service.cancel("job-1"));
    first.join(10_000L);
    assertTrue(sawCancel.get());
    assertFalse(service.cancel("job-1"), "a finished job is no longer cancellable");
  }

  @Test
  void aCausalJobWithoutAPinnedProfileStagesNothing() {
    CandidateExecutionJobService.JobSpec base = spec("job-1");
    for (String profile : new String[] {"fishing-profile-1", null, PROFILE.toUpperCase()}) {
      CandidateExecutionJobService.JobSpec unpinned =
          new CandidateExecutionJobService.JobSpec(
              base.jobId(),
              base.idempotencyKey(),
              base.baselineId(),
              base.scenarioId(),
              base.checkpointId(),
              base.contentVersion(),
              base.cacheVersion(),
              profile,
              base.recordingId(),
              base.initialProvenance(),
              base.task(),
              base.taskHash(),
              base.start(),
              base.executionMode(),
              base.worldSeed(),
              base.taskSeed(),
              base.mouseSeed(),
              base.budgets());
      PatchCandidateWorkflowService.PatchWorkflowException e =
          assertThrows(
              PatchCandidateWorkflowService.PatchWorkflowException.class,
              () -> service((j, timeout, cancel) -> lost()).execute(PROPOSAL, unpinned));
      assertEquals("EXECUTION_PROFILE_UNPINNED", e.code(), String.valueOf(profile));
    }
    assertFalse(Files.exists(temp.resolve("store")));
  }

  @Test
  void aDuplicateRequestReattachesToItsRecordedResultWithoutASecondLaunch() {
    AtomicInteger launches = new AtomicInteger();
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              launches.incrementAndGet();
              return answer(j, false, true, List.of());
            });
    CandidateExecutionJobService.ExecutionJob first = service.execute(PROPOSAL, spec("job-1"));
    // A new service instance stands in for a restarted owner reading the same durable store.
    CandidateExecutionJobService.ExecutionJob again =
        service((j, timeout, cancel) -> fail("a duplicate must never launch"))
            .execute(PROPOSAL, spec("job-1"));

    assertEquals(1, launches.get());
    assertEquals(first.attemptId(), again.attemptId());
    assertEquals(first.requestDigest(), again.requestDigest());
    assertEquals(first.outcome(), again.outcome());
    assertEquals(
        CandidateAttemptStore.State.COMPLETED, store.find(first.attemptId()).orElseThrow().state());
  }

  @Test
  void aReusedKeyWithChangedInputsConflicts() {
    service((j, timeout, cancel) -> answer(j, false, true, List.of()))
        .execute(PROPOSAL, spec("job-1"));
    CandidateExecutionJobService.JobSpec base = spec("job-1");
    CandidateExecutionJobService.JobSpec changed =
        new CandidateExecutionJobService.JobSpec(
            base.jobId(),
            base.idempotencyKey(),
            base.baselineId(),
            base.scenarioId(),
            base.checkpointId(),
            base.contentVersion(),
            base.cacheVersion(),
            base.profileId(),
            base.recordingId(),
            base.initialProvenance(),
            base.task(),
            base.taskHash(),
            base.start(),
            base.executionMode(),
            "another-world-seed",
            base.taskSeed(),
            base.mouseSeed(),
            base.budgets());
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service((j, t, c) -> fail("never launched")).execute(PROPOSAL, changed));
    assertEquals("EXECUTION_IDEMPOTENCY_CONFLICT", e.code());
  }

  @Test
  void anAbandonedAttemptIsUncertainAndNeverRelaunched() {
    CandidateAttemptStore.Attempt abandoned =
        store
            .reserve(
                "proposal:" + PROPOSAL,
                "key-job-1",
                "job-1",
                CandidateExecutionJobService.fingerprint(PROPOSAL, spec("job-1")),
                java.time.Duration.ofMinutes(1))
            .attempt();
    store.expireAndReconcile(abandoned.attemptId());

    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service((j, t, c) -> fail("never relaunched")).execute(PROPOSAL, spec("job-1")));
    assertEquals("EXECUTION_RECONCILIATION_REQUIRED", e.code());
    assertEquals(2L, store.find(abandoned.attemptId()).orElseThrow().epoch());
  }

  @Test
  void aFencedOwnerCannotRecordItsResult() {
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              // While this owner runs, its lease is lost and reconciliation takes the attempt.
              String attemptId = String.valueOf(map(j.get("request")).get("attemptId"));
              store.expireAndReconcile(attemptId);
              return answer(j, false, true, List.of());
            });
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service.execute(PROPOSAL, spec("job-1")));
    assertEquals("EXECUTION_OWNER_FENCED", e.code());
    CandidateAttemptStore.Attempt attempt = store.byId.values().iterator().next();
    assertEquals(CandidateAttemptStore.State.UNCERTAIN, attempt.state());
    assertNull(attempt.job(), "the fenced owner's view was not recorded as the result");
  }

  @Test
  void aStagingFailureEndsTheAttemptWithoutLaunching() throws Exception {
    Path entries = Files.createDirectories(temp.resolve("store/sha256"));
    Files.writeString(entries.resolve(sha(closureBytes).substring(7)), "not the closure");
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service((j, t, c) -> fail("never launched")).execute(PROPOSAL, spec("job-1")));
    assertEquals("EXECUTION_STAGING_FAILED", e.code());
    CandidateAttemptStore.Attempt attempt = store.byId.values().iterator().next();
    assertEquals(CandidateAttemptStore.State.FAILED, attempt.state());
    assertEquals(0, store.launchesMarked);
  }

  @Test
  void disabledExecutionStagesNothing() {
    properties.setEnabled(false);
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service((j, timeout, cancel) -> lost()).execute(PROPOSAL, spec("job-1")));
    assertEquals("CANDIDATE_EXECUTION_DISABLED", e.code());
    assertFalse(Files.exists(temp.resolve("store")));
  }

  @Test
  void aCandidateThatIsNotRegisteredNeverReachesTheHost() {
    when(workflow.registeredCandidate("built"))
        .thenThrow(
            new PatchCandidateWorkflowService.PatchWorkflowException(
                HttpStatus.CONFLICT, "CANDIDATE_NOT_REGISTERED", "state is BUILT"));
    AtomicBoolean called = new AtomicBoolean();
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () ->
                service(
                        (j, timeout, cancel) -> {
                          called.set(true);
                          return lost();
                        })
                    .execute("built", spec("job-1")));
    assertEquals("CANDIDATE_NOT_REGISTERED", e.code());
    assertFalse(called.get());
  }

  private CandidateExecutionJobService service(CandidateHostLauncher host) {
    return new CandidateExecutionJobService(workflow, properties, host, store);
  }

  private static CandidateHostLauncher.HostOutcome lost() {
    return new CandidateHostLauncher.HostOutcome(null, true, "HOST_TIMEOUT", null, "");
  }

  /** A host outcome whose result truthfully answers the job's request. */
  private static CandidateHostLauncher.HostOutcome answer(
      Map<String, Object> job,
      boolean developmentWorker,
      boolean hostCertifiable,
      List<String> hostReasons) {
    Map<String, Object> request = map(job.get("request"));
    String digest = String.valueOf(request.get("requestDigest"));
    Map<String, Object> loaded = new LinkedHashMap<>();
    loaded.put("evidence", "LOADED");
    loaded.put("packageDigest", request.get("packageDigest"));
    loaded.put("coreDigest", request.get("coreDigest"));
    loaded.put("workerCertifiable", !developmentWorker);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("requestId", request.get("requestId"));
    result.put("attemptId", request.get("attemptId"));
    result.put("runId", request.get("attemptId") + "/" + digest.substring(7, 23));
    result.put("requestDigest", digest);
    result.put("status", "OK");
    result.put("failureCode", null);
    result.put("failureDetail", null);
    result.put("actualLoaded", loaded);
    result.put("decisionCount", 3L);
    result.put("decisionsRef", Sc2106CanonicalJson.sha256("decisions"));
    result.put("intentRefs", List.of(Sc2106CanonicalJson.sha256("intent")));
    result.put("receiptRefs", List.of(Sc2106CanonicalJson.sha256("receipt")));
    result.put("journalRef", Sc2106CanonicalJson.sha256("journal"));
    result.put("checkpointRef", null);
    result.put("terminalState", Map.of("revision", 4L));
    result.put("postconditions", Map.of("goalReached", true));
    result.put("supportedCapabilities", List.of("INTERACT_NPC"));
    result.put("unknownCapabilities", List.of());
    result.put("consumedSteps", 3L);
    result.put("consumedTicks", 10L);
    result.put("consumedIntents", 1L);
    result.put("consumedWallTimeMillis", 900L);
    result.put("consumedOutputBytes", 2048L);
    result.put("startedAtMillis", 1L);
    result.put("completedAtMillis", 901L);
    result.put("cleanupState", "CLEAN");
    result.put("reconciliationState", "NOT_REQUIRED");
    reseal(result);
    Map<String, Object> certification = new LinkedHashMap<>();
    certification.put("certifiable", hostCertifiable);
    certification.put("reasons", new ArrayList<>(hostReasons));
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("schema", "sc2106-host-outcome/v1");
    document.put("result", result);
    document.put("dispatched", true);
    document.put("certification", certification);
    return new CandidateHostLauncher.HostOutcome(document, false, null, 0, "");
  }

  private static void reseal(Map<String, Object> result) {
    result.remove("resultDigest");
    result.put("resultDigest", Sc2106CanonicalJson.sha256(result));
  }

  private static CandidateExecutionJobService.JobSpec spec(String jobId) {
    Map<String, Object> task = new LinkedHashMap<>();
    task.put("taskKey", "FISHING_TRAINING");
    task.put("settings", settings());
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("manifest", Map.of("recording", Map.of("recordingId", "rec-java-rpc")));
    return new CandidateExecutionJobService.JobSpec(
        jobId,
        "key-" + jobId,
        "baseline-1",
        "scenario-1",
        null,
        "content-1",
        "cache-1",
        PROFILE,
        "rec-java-rpc",
        "AUTHORED",
        task,
        Sc2106CanonicalJson.sha256("task"),
        start,
        null,
        "java-rpc-seed",
        "task-seed-1",
        "mouse-seed-1",
        new CandidateExecutionJobService.Budgets(200, -1, 50, 120_000, 268_435_456, 1 << 20));
  }

  private static Map<String, Object> settings() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("targetFish", "SHRIMP");
    settings.put("stopConditionValue", 3);
    return settings;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static String sha(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(Sc967CanonicalJson.digest(bytes));
  }

  private static byte[] decode(JsonNode vector, String field) {
    return Base64.getDecoder().decode(vector.path(field).asText());
  }

  private static Path vectorsPath() {
    String resolved = System.getProperty("sc967.vectors.path");
    if (resolved == null || resolved.isBlank()) {
      resolved = System.getenv("SC967_VECTORS_PATH");
    }
    if (resolved == null || resolved.isBlank()) {
      throw new IllegalStateException("configure -Psc967VectorsPath or SC967_VECTORS_PATH");
    }
    return Paths.get(resolved).toAbsolutePath().normalize();
  }
}
