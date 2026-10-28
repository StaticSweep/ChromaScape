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
    assertEquals("sc2106-host-job/v3", sent.get().get("schema"));
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

  /** An attempt that lost its owner, with the journal directory its runner would have published. */
  private CandidateAttemptStore.Attempt uncertainAttemptWithJournal(String key) throws Exception {
    CandidateAttemptStore.Attempt previous =
        store
            .reserve(
                "proposal:" + PROPOSAL,
                key,
                "job-crashed",
                CandidateExecutionJobService.fingerprint(PROPOSAL, spec("job-crashed")),
                java.time.Duration.ofMinutes(1))
            .attempt();
    store.expireAndReconcile(previous.attemptId());
    Files.createDirectories(temp.resolve("runs").resolve(previous.attemptId()).resolve("journal"));
    return store.find(previous.attemptId()).orElseThrow();
  }

  @Test
  void aRecoveryRebuildsTheManagedJournalWithThePreviousRunDirectoryGone() throws Exception {
    com.chromascape.web.artifact.ImmutableArtifactStore artifacts =
        new com.chromascape.web.artifact.ImmutableArtifactStore(
            temp.resolve("artifacts"), 1 << 20, 1 << 24, MAPPER);
    CandidateAttemptJournalArchive journals =
        new CandidateAttemptJournalArchive(store, artifacts, MAPPER);
    AtomicReference<String> crashed = new AtomicReference<>();
    // The runner publishes one cut to its spool, then the host is lost.
    new CandidateExecutionJobService(
            workflow,
            properties,
            (job, timeout, cancel) -> {
              String attemptId = String.valueOf(map(job.get("request")).get("attemptId"));
              crashed.set(attemptId);
              try {
                new CandidateAttemptJournalArchiveTest.Spool(
                        Path.of(String.valueOf(job.get("workingDirectory"))).resolve("journal"),
                        attemptId)
                    .append(CandidateAttemptJournalArchiveTest.event("CHECKPOINT"));
              } catch (Exception e) {
                throw new AssertionError(e);
              }
              return lost();
            },
            store,
            journals)
        .execute(PROPOSAL, spec("job-crashed"));
    assertEquals(1L, store.journalHead(crashed.get()).sequence(), "published before settling");
    CandidateAttemptJournalArchiveTest.deleteTree(temp.resolve("runs").resolve(crashed.get()));

    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    new CandidateExecutionJobService(
            workflow,
            properties,
            (j, timeout, cancel) -> {
              sent.set(j);
              return answer(j, false, true, List.of());
            },
            store,
            journals)
        .recover(PROPOSAL, crashed.get(), spec("job-recovery"));

    Map<String, Object> resume = map(sent.get().get("resume"));
    assertEquals("MANAGED", resume.get("journalSource"));
    Path rebuilt = Path.of(String.valueOf(resume.get("root")));
    assertTrue(rebuilt.startsWith(temp.resolve("runs").resolve("recovery-sources")));
    assertTrue(Files.isRegularFile(rebuilt.resolve(crashed.get()).resolve("HEAD")));
    assertFalse(Files.exists(temp.resolve("runs").resolve(crashed.get())));
    Map<String, Object> journal =
        new CandidateExecutionJobService(workflow, properties, null, store, journals)
            .status(PROPOSAL, "key-job-crashed")
            .journal();
    assertEquals(1L, ((Number) journal.get("sequence")).longValue());
    assertFalse(String.valueOf(journal).contains(temp.toString()), "no host path in status");
  }

  /** An archive whose managed store refuses writes while {@code full} is set (R5.4). */
  private CandidateAttemptJournalArchive fullUntilCleared(AtomicBoolean full) {
    return new CandidateAttemptJournalArchive(store, null, MAPPER) {
      @Override
      public CandidateAttemptStore.JournalHead ingest(
          CandidateAttemptStore.Attempt held, Path spoolRoot) throws java.io.IOException {
        if (full.get()) {
          throw new java.io.IOException("ARTIFACT_QUOTA_EXCEEDED");
        }
        return store.journalHead(held.attemptId());
      }
    };
  }

  private static boolean within(long millis, java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + millis;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        return false;
      }
      Thread.sleep(50L);
    }
    return true;
  }

  @Test
  void aStalledManagedJournalParksTheRunnerUntilPublicationCatchesUp() throws Exception {
    AtomicBoolean full = new AtomicBoolean(true);
    List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    CandidateExecutionJobService.ExecutionJob job =
        new CandidateExecutionJobService(
                workflow,
                properties,
                new CandidateHostLauncher() {
                  @Override
                  public HostOutcome run(
                      Map<String, Object> j, java.time.Duration timeout, AtomicBoolean cancel) {
                    throw new AssertionError("the service passes its controls");
                  }

                  @Override
                  public HostOutcome run(
                      Map<String, Object> j, java.time.Duration timeout, HostControl control) {
                    try {
                      seen.add(within(5_000L, () -> control.pause().get()) ? "parked" : "never");
                      full.set(false);
                      seen.add(within(5_000L, () -> !control.pause().get()) ? "resumed" : "stuck");
                      seen.add(control.cancel().get() ? "cancelled" : "running");
                    } catch (InterruptedException e) {
                      throw new AssertionError(e);
                    }
                    return answer(j, false, true, List.of());
                  }
                },
                store,
                fullUntilCleared(full))
            .execute(PROPOSAL, spec("job-1"));

    assertEquals(List.of("parked", "resumed", "running"), seen);
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, job.outcome());
    assertFalse(job.reasons().contains("STORAGE_PUBLICATION_STALLED"));
  }

  @Test
  void aStallPastItsDeadlineStopsTheRunWithATypedReason() throws Exception {
    properties.setJournalPublicationDeadline(java.time.Duration.ofSeconds(1));
    AtomicBoolean pausedFirst = new AtomicBoolean();
    CandidateExecutionJobService.ExecutionJob job =
        new CandidateExecutionJobService(
                workflow,
                properties,
                new CandidateHostLauncher() {
                  @Override
                  public HostOutcome run(
                      Map<String, Object> j, java.time.Duration timeout, AtomicBoolean cancel) {
                    throw new AssertionError("the service passes its controls");
                  }

                  @Override
                  public HostOutcome run(
                      Map<String, Object> j, java.time.Duration timeout, HostControl control) {
                    try {
                      pausedFirst.set(within(5_000L, () -> control.pause().get()));
                      assertTrue(within(10_000L, () -> control.cancel().get()), "never stopped");
                    } catch (InterruptedException e) {
                      throw new AssertionError(e);
                    }
                    return answer(j, false, true, List.of());
                  }
                },
                store,
                fullUntilCleared(new AtomicBoolean(true)))
            .execute(PROPOSAL, spec("job-1"));

    assertTrue(pausedFirst.get(), "the runner is parked before the run is stopped");
    assertTrue(job.reasons().contains("STORAGE_PUBLICATION_STALLED"), job.reasons().toString());
    assertTrue(
        store.find(job.attemptId()).orElseThrow().detail().contains("STORAGE_PUBLICATION_STALLED"));
  }

  @Test
  void journalStorageSettingsAreBoundedNeverUnbounded() {
    Sc2106ExecutionProperties settings = new Sc2106ExecutionProperties();
    for (java.time.Duration bad :
        java.util.Arrays.asList(
            null,
            java.time.Duration.ZERO,
            java.time.Duration.ofMillis(10),
            java.time.Duration.ofHours(2))) {
      assertThrows(
          IllegalArgumentException.class, () -> settings.setJournalPublicationDeadline(bad));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> settings.setRetentionGrace(java.time.Duration.ofSeconds(5)));
    assertEquals(java.time.Duration.ofMinutes(2), settings.getJournalPublicationDeadline());
    assertEquals(java.time.Duration.ofHours(24), settings.getRetentionGrace());
  }

  @Test
  void aRecoveryIsANewFencedAttemptThatNamesThePreviousJournal() throws Exception {
    CandidateAttemptStore.Attempt previous = uncertainAttemptWithJournal("key-crashed");
    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    CandidateExecutionJobService.ExecutionJob recovered =
        service(
                (j, timeout, cancel) -> {
                  sent.set(j);
                  return answer(j, false, true, List.of());
                })
            .recover(PROPOSAL, previous.attemptId(), spec("job-recovery"));

    Map<String, Object> resume = map(sent.get().get("resume"));
    assertEquals(previous.attemptId(), resume.get("attemptId"));
    assertEquals(List.of(), resume.get("unresolvedCommandIds"));
    assertEquals("LOCAL_ONLY", resume.get("journalSource"), "no managed journal: labelled");
    assertEquals(
        temp.resolve("runs").resolve(previous.attemptId()).resolve("journal").toString(),
        resume.get("root"));
    assertFalse(previous.attemptId().equals(recovered.attemptId()), "a recovery is a new attempt");
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, recovered.outcome());
    assertEquals(
        CandidateAttemptStore.State.UNCERTAIN,
        store.find(previous.attemptId()).orElseThrow().state(),
        "the recovered attempt's own record is never rewritten");

    // Asking again with the same key reattaches instead of recovering twice.
    CandidateExecutionJobService.ExecutionJob again =
        service((j, timeout, cancel) -> fail("recovered only once"))
            .recover(PROPOSAL, previous.attemptId(), spec("job-recovery"));
    assertEquals(recovered.attemptId(), again.attemptId());
  }

  @Test
  void twoRecoveriesCanNeverBothContinueOneAttempt() throws Exception {
    CandidateAttemptStore.Attempt previous = uncertainAttemptWithJournal("key-crashed");
    AtomicReference<String> second = new AtomicReference<>();
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              // While the first recovery runs, a second caller with its own key tries as well.
              second.set(
                  assertThrows(
                          PatchCandidateWorkflowService.PatchWorkflowException.class,
                          () ->
                              service((k, t, c) -> fail("a second recovery never launches"))
                                  .recover(PROPOSAL, previous.attemptId(), spec("job-rival")))
                      .code());
              return answer(j, false, true, List.of());
            });
    CandidateExecutionJobService.ExecutionJob first =
        service.recover(PROPOSAL, previous.attemptId(), spec("job-recovery"));
    assertEquals("EXECUTION_RECOVERY_CLAIMED", second.get());
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, first.outcome());
    // A completed recovery keeps its claim.
    assertEquals(
        "EXECUTION_RECOVERY_CLAIMED",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () ->
                    service((k, t, c) -> fail("never launched"))
                        .recover(PROPOSAL, previous.attemptId(), spec("job-late")))
            .code());
  }

  @Test
  void aRecoveryThatFailedReleasesItsClaim() throws Exception {
    CandidateAttemptStore.Attempt previous = uncertainAttemptWithJournal("key-crashed");
    // The runner refused to resume (no usable cut) before any intent.
    CandidateExecutionJobService.ExecutionJob failed =
        service((j, t, c) -> refusedRecovery(j))
            .recover(PROPOSAL, previous.attemptId(), spec("job-recovery-1"));
    assertEquals(CandidateExecutionResultValidator.Outcome.FAILED, failed.outcome());
    assertEquals(
        CandidateAttemptStore.State.FAILED, store.find(failed.attemptId()).orElseThrow().state());
    CandidateExecutionJobService.ExecutionJob retried =
        service((j, t, c) -> answer(j, false, true, List.of()))
            .recover(PROPOSAL, previous.attemptId(), spec("job-recovery-2"));
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, retried.outcome());
  }

  @Test
  void aFailedRecoverysDescendantPreventsAnAncestorFromLaunchingAgain() throws Exception {
    var original = uncertainAttemptWithJournal("key-lineage-original");
    var failed =
        service((j, t, c) -> refusedRecovery(j))
            .recover(PROPOSAL, original.attemptId(), spec("job-lineage-failed"));
    Files.createDirectories(temp.resolve("runs").resolve(failed.attemptId()).resolve("journal"));
    var continuation =
        service(
                (j, t, c) -> {
                  assertEquals(
                      "EXECUTION_RECOVERY_CLAIMED",
                      assertThrows(
                              PatchCandidateWorkflowService.PatchWorkflowException.class,
                              () ->
                                  service(
                                          (k, timeout, cancel) ->
                                              fail("an ancestor must never launch"))
                                      .recover(
                                          PROPOSAL,
                                          original.attemptId(),
                                          spec("job-lineage-rival")))
                          .code());
                  return answer(j, false, true, List.of());
                })
            .recover(PROPOSAL, failed.attemptId(), spec("job-lineage-continuation"));
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, continuation.outcome());
    assertEquals(
        "EXECUTION_RECOVERY_CLAIMED",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () ->
                    service(
                            (k, timeout, cancel) ->
                                fail("a completed descendant prevents a replay"))
                        .recover(PROPOSAL, original.attemptId(), spec("job-lineage-late")))
            .code());
    var attached =
        service((k, timeout, cancel) -> fail("the same key only reattaches"))
            .recover(PROPOSAL, failed.attemptId(), spec("job-lineage-continuation"));
    assertEquals(continuation.attemptId(), attached.attemptId());
  }

  @Test
  void aResultThatCannotBeRecordedIsNeverReportedAndBecomesRecoverable() throws Exception {
    store.finishFailure = new org.springframework.dao.DataAccessResourceFailureException("db down");
    AtomicReference<String> attemptId = new AtomicReference<>();
    PatchCandidateWorkflowService.PatchWorkflowException e =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () ->
                service(
                        (j, t, c) -> {
                          attemptId.set(String.valueOf(map(j.get("request")).get("attemptId")));
                          try {
                            Files.createDirectories(
                                temp.resolve("runs").resolve(attemptId.get()).resolve("journal"));
                          } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                          }
                          return answer(j, false, true, List.of());
                        })
                    .execute(PROPOSAL, spec("job-1")));
    assertEquals("EXECUTION_RESULT_UNRECORDED", e.code());
    CandidateAttemptStore.Attempt open = store.find(attemptId.get()).orElseThrow();
    assertEquals(
        CandidateAttemptStore.State.RUNNING, open.state(), "the run is neither final nor FAILED");
    assertNull(open.job());

    // Once the store is back and the lease has run out, recovery reconciles it first.
    store.finishFailure = null;
    store.expire(attemptId.get());
    CandidateExecutionJobService.ExecutionJob recovered =
        service((j, t, c) -> answer(j, false, true, List.of()))
            .recover(PROPOSAL, attemptId.get(), spec("job-1-recovery"));
    assertEquals(CandidateExecutionResultValidator.Outcome.PASS, recovered.outcome());
    assertEquals(
        CandidateAttemptStore.State.UNCERTAIN, store.find(attemptId.get()).orElseThrow().state());
  }

  @Test
  void aFaultReachesTheHostOnlyThroughTheTestSeam() throws Exception {
    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    CandidateExecutionJobService service =
        service(
            (j, t, c) -> {
              sent.set(j);
              return answer(j, false, true, List.of());
            });
    service.execute(PROPOSAL, spec("job-plain"));
    assertFalse(sent.get().containsKey("fault"));
    service.injectFault(Map.of("point", "HALT_BEFORE_SEAL", "at", 1));
    service.execute(PROPOSAL, spec("job-faulted"));
    assertEquals("HALT_BEFORE_SEAL", map(sent.get().get("fault")).get("point"));
  }

  @Test
  void onlyAnUncertainOrFailedAttemptOfTheSameProposalWithAJournalCanBeRecovered()
      throws Exception {
    CandidateAttemptStore.Attempt previous = uncertainAttemptWithJournal("key-crashed");
    CandidateExecutionJobService service = service((j, t, c) -> fail("never launched"));

    assertEquals(
        "EXECUTION_ATTEMPT_NOT_FOUND",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () -> service.recover(PROPOSAL, "attempt-missing", spec("job-r1")))
            .code());
    assertEquals(
        "EXECUTION_RECOVERY_FOREIGN",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () -> service.recover("another-proposal", previous.attemptId(), spec("job-r2")))
            .code());
    assertEquals(
        "EXECUTION_JOB_INVALID",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () ->
                    service.recover(
                        PROPOSAL,
                        previous.attemptId(),
                        new CandidateExecutionJobService.JobSpec(
                            "job-r3",
                            "key-crashed",
                            "baseline-1",
                            "scenario-1",
                            null,
                            "content-1",
                            "cache-1",
                            PROFILE,
                            "rec-java-rpc",
                            "AUTHORED",
                            spec("x").task(),
                            spec("x").taskHash(),
                            spec("x").start(),
                            null,
                            "java-rpc-seed",
                            "task-seed-1",
                            "mouse-seed-1",
                            spec("x").budgets())))
            .code());

    CandidateExecutionJobService.ExecutionJob finished =
        service((j, t, c) -> answer(j, false, true, List.of())).execute(PROPOSAL, spec("job-ok"));
    assertEquals(
        "EXECUTION_RECOVERY_NOT_NEEDED",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () -> service.recover(PROPOSAL, finished.attemptId(), spec("job-r4")))
            .code());

    CandidateAttemptStore.Attempt noJournal =
        store
            .reserve(
                "proposal:" + PROPOSAL,
                "key-nojournal",
                "job-nj",
                "sha256:x",
                java.time.Duration.ofMinutes(1))
            .attempt();
    store.expireAndReconcile(noJournal.attemptId());
    assertEquals(
        "EXECUTION_RECOVERY_UNAVAILABLE",
        assertThrows(
                PatchCandidateWorkflowService.PatchWorkflowException.class,
                () -> service.recover(PROPOSAL, noJournal.attemptId(), spec("job-r5")))
            .code());
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

  // ---- R9.2 / R9.5 durable job control ----

  @Test
  void aSubmittedJobIsQueuedThenRunsAndItsStatusReadsBackFromTheStore() throws Exception {
    CountDownLatch launched = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              launched.countDown();
              awaitLatch(release);
              return answer(j, false, true, List.of());
            });

    CandidateExecutionJobService.JobStatus queued = service.submit(PROPOSAL, spec("job-1"));
    assertEquals(CandidateExecutionJobService.Lifecycle.QUEUED, queued.lifecycle());
    assertTrue(launched.await(10, TimeUnit.SECONDS));
    // A refresh, another process or a restarted server reads the same job by its key.
    CandidateExecutionJobService other = service((j, timeout, cancel) -> fail("never launches"));
    assertEquals(
        CandidateExecutionJobService.Lifecycle.RUNNING,
        other.status(PROPOSAL, "key-job-1").lifecycle());
    release.countDown();

    CandidateExecutionJobService.JobStatus done =
        awaitLifecycle(other, "key-job-1", CandidateExecutionJobService.Lifecycle.COMPLETED);
    assertEquals(queued.attemptId(), done.attemptId());
    assertEquals("PASS", done.outcome());
    assertEquals(Sc2106CanonicalJson.sha256("journal"), done.resultRefs().get("journalRef"));
    assertEquals(Sc2106CanonicalJson.sha256("decisions"), done.resultRefs().get("decisionsRef"));
    assertTrue(done.resultRefs().containsKey("resultDigest"));
  }

  @Test
  void aRepeatedSubmissionReturnsTheSameJobAndChangedInputsConflict() throws Exception {
    AtomicInteger launches = new AtomicInteger();
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              launches.incrementAndGet();
              return answer(j, false, true, List.of());
            });
    CandidateExecutionJobService.JobStatus first = service.submit(PROPOSAL, spec("job-1"));
    awaitLifecycle(service, "key-job-1", CandidateExecutionJobService.Lifecycle.COMPLETED);

    CandidateExecutionJobService.JobStatus again = service.submit(PROPOSAL, spec("job-1"));
    assertEquals(first.attemptId(), again.attemptId());
    assertEquals(CandidateExecutionJobService.Lifecycle.COMPLETED, again.lifecycle());
    assertEquals(1, launches.get(), "a duplicate submission never launches");

    PatchCandidateWorkflowService.PatchWorkflowException conflict =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service.submit(PROPOSAL, withSeed(spec("job-1"), "another-seed")));
    assertEquals("EXECUTION_IDEMPOTENCY_CONFLICT", conflict.code());
  }

  @Test
  void aCancelRecordedByAnotherProcessReachesTheRunningJob() throws Exception {
    CountDownLatch launched = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    CandidateExecutionJobService owner =
        service(
            (j, timeout, cancel) -> {
              launched.countDown();
              waitUntil(cancel::get);
              sawCancel.set(true);
              return cancelledAnswer(j);
            });
    owner.submit(PROPOSAL, spec("job-1"));
    assertTrue(launched.await(10, TimeUnit.SECONDS));

    // Another instance shares only the store: the request must travel through it.
    CandidateExecutionJobService other = service((j, timeout, cancel) -> fail("never launches"));
    CandidateExecutionJobService.JobStatus requested = other.requestCancel(PROPOSAL, "key-job-1");
    assertTrue(requested.cancelRequested());

    CandidateExecutionJobService.JobStatus done =
        awaitLifecycle(other, "key-job-1", CandidateExecutionJobService.Lifecycle.CANCELLED);
    assertTrue(sawCancel.get());
    assertEquals("CANCELLED", done.outcome());
    PatchCandidateWorkflowService.PatchWorkflowException finished =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> other.requestCancel(PROPOSAL, "key-job-1"));
    assertEquals("EXECUTION_JOB_NOT_ACTIVE", finished.code());
  }

  @Test
  void aJobCancelledWhileQueuedIsNeverLaunched() throws Exception {
    properties.setMaxConcurrentJobs(1);
    CountDownLatch launched = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger launches = new AtomicInteger();
    CandidateExecutionJobService service =
        service(
            (j, timeout, cancel) -> {
              launches.incrementAndGet();
              launched.countDown();
              awaitLatch(release);
              return answer(j, false, true, List.of());
            });
    service.submit(PROPOSAL, spec("job-1"));
    assertTrue(launched.await(10, TimeUnit.SECONDS));
    CandidateExecutionJobService.JobStatus second = service.submit(PROPOSAL, spec("job-2"));
    assertEquals(CandidateExecutionJobService.Lifecycle.QUEUED, second.lifecycle());

    service.requestCancel(PROPOSAL, "key-job-2");
    release.countDown();

    CandidateExecutionJobService.JobStatus cancelled =
        awaitLifecycle(service, "key-job-2", CandidateExecutionJobService.Lifecycle.CANCELLED);
    assertEquals(List.of("CANCELLED_BEFORE_LAUNCH"), cancelled.reasons());
    awaitLifecycle(service, "key-job-1", CandidateExecutionJobService.Lifecycle.COMPLETED);
    assertEquals(1, launches.get(), "the cancelled job never reached the host");
  }

  @Test
  void aPauseReachesTheHostAndTheObservedPauseIsDurable() throws Exception {
    CountDownLatch launched = new CountDownLatch(1);
    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    CandidateExecutionJobService service =
        service(
            new CandidateHostLauncher() {
              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, AtomicBoolean cancel) {
                throw new AssertionError("the service passes pause control");
              }

              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, HostControl control) {
                sent.set(job);
                launched.countDown();
                waitUntil(() -> control.pause().get());
                control.listener().onPause(true);
                waitUntil(() -> !control.pause().get());
                control.listener().onPause(false);
                return answer(job, false, true, List.of());
              }
            });
    service.submit(PROPOSAL, spec("job-1"));
    assertTrue(launched.await(10, TimeUnit.SECONDS));
    assertNull(sent.get().get("startPaused"));

    service.requestPause(PROPOSAL, "key-job-1", true);
    CandidateExecutionJobService.JobStatus paused =
        awaitLifecycle(service, "key-job-1", CandidateExecutionJobService.Lifecycle.PAUSED);
    assertTrue(paused.pauseRequested());
    assertTrue(paused.paused(), "the runner reported that it parked");

    service.requestPause(PROPOSAL, "key-job-1", false);
    CandidateExecutionJobService.JobStatus done =
        awaitLifecycle(service, "key-job-1", CandidateExecutionJobService.Lifecycle.COMPLETED);
    assertFalse(done.paused());
    assertFalse(done.pauseRequested());
  }

  @Test
  void theOwnerCommitsEachWorkerBindingGenerationBeforeAnsweringIt() throws Exception {
    List<Boolean> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
    AtomicReference<String> attemptId = new AtomicReference<>();
    CountDownLatch bound = new CountDownLatch(1);
    CountDownLatch checked = new CountDownLatch(1);
    CandidateExecutionJobService service =
        service(
            new CandidateHostLauncher() {
              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, AtomicBoolean cancel) {
                throw new AssertionError("the service passes the binding handler");
              }

              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, HostControl control) {
                Map<String, Object> request = map(job.get("request"));
                attemptId.set(String.valueOf(request.get("attemptId")));
                answers.add(control.binding().accept(binding(request, 1, "CREATE", 3)));
                // A repeat, a skipped generation and a foreign request are all refused.
                answers.add(control.binding().accept(binding(request, 1, "CREATE", 3)));
                answers.add(control.binding().accept(binding(request, 3, "RESTORE", 5)));
                Map<String, Object> foreign = new LinkedHashMap<>(request);
                foreign.put("requestDigest", "sha256:" + "0".repeat(64));
                answers.add(control.binding().accept(binding(foreign, 2, "RESTORE", 4)));
                answers.add(control.binding().accept(binding(request, 2, "RESTORE", 4)));
                bound.countDown();
                awaitLatch(checked);
                return answer(job, false, true, List.of());
              }
            });
    service.submit(PROPOSAL, spec("job-1"));
    awaitLatch(bound);

    assertEquals(List.of(true, false, false, false, true), answers);
    Map<String, Object> shown = service.status(PROPOSAL, "key-job-1").binding();
    assertEquals(2L, ((Number) shown.get("generation")).longValue());
    assertEquals("RESTORE", shown.get("reason"));
    assertEquals(4L, ((Number) shown.get("workerEpoch")).longValue());
    assertFalse(shown.containsKey("workerLeaseDigest"), "status never shows lease material");
    assertEquals(2, store.bindings.get(attemptId.get()).size());
    checked.countDown();
    awaitLifecycle(service, "key-job-1", CandidateExecutionJobService.Lifecycle.COMPLETED);
  }

  @Test
  void aFencedOwnerRefusesTheBindingAndStopsItsRun() throws Exception {
    AtomicBoolean answer = new AtomicBoolean(true);
    AtomicBoolean cancelled = new AtomicBoolean();
    CountDownLatch done = new CountDownLatch(1);
    CandidateExecutionJobService service =
        service(
            new CandidateHostLauncher() {
              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, AtomicBoolean cancel) {
                throw new AssertionError("the service passes the binding handler");
              }

              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, HostControl control) {
                Map<String, Object> request = map(job.get("request"));
                store.expireAndReconcile(String.valueOf(request.get("attemptId")));
                answer.set(control.binding().accept(binding(request, 1, "CREATE", 3)));
                cancelled.set(control.cancel().get());
                done.countDown();
                return answer(job, false, true, List.of());
              }
            });
    service.submit(PROPOSAL, spec("job-1"));
    awaitLatch(done);

    assertFalse(answer.get(), "a stale owner cannot accept a binding");
    assertTrue(cancelled.get(), "the fenced owner stops its run");
    assertTrue(store.bindings.values().stream().allMatch(List::isEmpty));
  }

  @Test
  void aRecoveryResolvesThePreviousAttemptsUnknownCommandThroughItsRestoreBinding()
      throws Exception {
    CandidateAttemptStore.Attempt previous = uncertainAttemptWithJournal("key-crashed");
    store.commands.put(
        previous.attemptId(),
        new ArrayList<>(
            List.of(
                new CandidateAttemptStore.CommandRecord(
                    previous.attemptId(),
                    "c-9",
                    "i-9",
                    "session.candidate.advance",
                    "TIME",
                    "sha256:" + "a".repeat(64),
                    1,
                    3,
                    7,
                    CandidateAttemptStore.CommandState.UNKNOWN,
                    null,
                    null,
                    null))));
    AtomicReference<Map<String, Object>> resume = new AtomicReference<>();
    List<Boolean> answers = new ArrayList<>();
    CandidateExecutionJobService.ExecutionJob recovered =
        service(
                new CandidateHostLauncher() {
                  @Override
                  public HostOutcome run(
                      Map<String, Object> job, java.time.Duration timeout, AtomicBoolean cancel) {
                    throw new AssertionError("the service passes the binding handler");
                  }

                  @Override
                  public HostOutcome run(
                      Map<String, Object> job, java.time.Duration timeout, HostControl control) {
                    resume.set(map(job.get("resume")));
                    Map<String, Object> request = map(job.get("request"));
                    answers.add(control.binding().accept(binding(request, 1, "CREATE", 3)));
                    // The restored cut must account for the previous attempt's unknown command.
                    answers.add(control.binding().accept(binding(request, 2, "RESTORE", 4)));
                    Map<String, Object> restore = binding(request, 2, "RESTORE", 4);
                    restore.put(
                        "recoveredFrom",
                        Map.of(
                            "attemptId",
                            previous.attemptId(),
                            "checkpointIndex",
                            2,
                            "checkpointChecksum",
                            "sha256:" + "c".repeat(64),
                            "workerEpoch",
                            3,
                            "workerRevision",
                            7,
                            "commandResolutions",
                            Map.of("c-9", "APPLIED")));
                    answers.add(control.binding().accept(restore));
                    return answer(job, false, true, List.of());
                  }
                })
            .recover(PROPOSAL, previous.attemptId(), spec("job-recovery"));

    assertEquals(List.of("c-9"), resume.get().get("unresolvedCommandIds"));
    assertEquals(List.of(true, false, true), answers);
    CandidateAttemptStore.CommandRecord resolved = store.commands(previous.attemptId()).get(0);
    assertEquals(CandidateAttemptStore.CommandState.APPLIED, resolved.state());
    assertEquals(recovered.attemptId(), resolved.resolvedByAttemptId());
  }

  @Test
  void aMalformedRecoveredCutIsRefusedNotCoerced() {
    Map<String, Object> line =
        binding(Map.of("attemptId", "a", "requestDigest", "d"), 2, "RESTORE", 4);
    line.put(
        "recoveredFrom",
        Map.of(
            "attemptId",
            "p",
            "checkpointIndex",
            2,
            "checkpointChecksum",
            "sha256:x",
            "workerEpoch",
            3,
            "workerRevision",
            7,
            "commandResolutions",
            Map.of("c-9", "MAYBE")));
    assertThrows(
        IllegalArgumentException.class, () -> CandidateExecutionJobService.workerBinding(line));
  }

  @Test
  void aMalformedBindingLineIsRefusedNotCoerced() {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("bindingId", "b");
    line.put("generation", "1");
    assertThrows(
        IllegalArgumentException.class, () -> CandidateExecutionJobService.workerBinding(line));
  }

  private static Map<String, Object> binding(
      Map<String, Object> request, long generation, String reason, long workerEpoch) {
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("schema", "sc2106-host-binding/v1");
    binding.put("bindingId", java.util.UUID.randomUUID().toString());
    binding.put("generation", generation);
    binding.put("attemptId", request.get("attemptId"));
    binding.put("requestDigest", request.get("requestDigest"));
    binding.put("workerAuthority", "candidate-stdio/v1");
    binding.put("workerSessionId", "sc2106-" + request.get("attemptId"));
    binding.put("workerEpoch", workerEpoch);
    binding.put("workerLeaseDigest", "sha256:" + "a".repeat(63) + workerEpoch);
    binding.put("workerRevision", 7L);
    binding.put("runnerSteps", 0L);
    binding.put("runnerIntents", 0L);
    binding.put("reason", reason);
    binding.put("workerIdentity", Map.of("workerCertifiable", false));
    return binding;
  }

  @Test
  void aRecoveryOfAPausedAttemptStartsPausedUntilResumed() throws Exception {
    CandidateAttemptStore.Attempt previous =
        store
            .reserve(
                "proposal:" + PROPOSAL,
                "key-crashed",
                "job-crashed",
                CandidateExecutionJobService.fingerprint(PROPOSAL, spec("job-crashed")),
                java.time.Duration.ofMinutes(1))
            .attempt();
    assertTrue(store.requestPause(previous.attemptId(), true));
    store.expireAndReconcile(previous.attemptId());
    Files.createDirectories(temp.resolve("runs").resolve(previous.attemptId()).resolve("journal"));

    CountDownLatch launched = new CountDownLatch(1);
    AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
    AtomicBoolean startedPaused = new AtomicBoolean();
    CandidateExecutionJobService service =
        service(
            new CandidateHostLauncher() {
              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, AtomicBoolean cancel) {
                throw new AssertionError("the service passes pause control");
              }

              @Override
              public HostOutcome run(
                  Map<String, Object> job, java.time.Duration timeout, HostControl control) {
                sent.set(job);
                startedPaused.set(control.pause().get());
                launched.countDown();
                waitUntil(() -> !control.pause().get());
                return answer(job, false, true, List.of());
              }
            });

    CandidateExecutionJobService.JobStatus recovery =
        service.submitRecovery(PROPOSAL, "key-crashed", spec("job-recovery"));
    assertTrue(recovery.pauseRequested(), "restored work stays paused");
    assertTrue(launched.await(10, TimeUnit.SECONDS));
    assertEquals(Boolean.TRUE, sent.get().get("startPaused"));
    assertTrue(startedPaused.get());
    assertEquals(previous.attemptId(), map(sent.get().get("resume")).get("attemptId"));

    service.requestPause(PROPOSAL, "key-job-recovery", false);
    awaitLifecycle(service, "key-job-recovery", CandidateExecutionJobService.Lifecycle.COMPLETED);
  }

  @Test
  void anOpenAttemptWhoseOwnerLeaseRanOutIsReconcilingAndUnknownJobsAreNotFound() {
    CandidateAttemptStore.Attempt orphan =
        store
            .reserve(
                "proposal:" + PROPOSAL,
                "key-orphan",
                "job-orphan",
                "fingerprint",
                java.time.Duration.ofMinutes(1))
            .attempt();
    store.expire(orphan.attemptId());
    CandidateExecutionJobService service = service((j, timeout, cancel) -> fail("never"));

    assertEquals(
        CandidateExecutionJobService.Lifecycle.RECONCILING,
        service.status(PROPOSAL, "key-orphan").lifecycle());
    PatchCandidateWorkflowService.PatchWorkflowException missing =
        assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service.status(PROPOSAL, "key-unknown"));
    assertEquals("EXECUTION_JOB_NOT_FOUND", missing.code());
    assertEquals(HttpStatus.NOT_FOUND, missing.status());
    assertThrows(
        PatchCandidateWorkflowService.PatchWorkflowException.class,
        () -> service.status("another-proposal", "key-orphan"),
        "a job is only visible under its own proposal");
  }

  private static CandidateHostLauncher.HostOutcome cancelledAnswer(Map<String, Object> job) {
    CandidateHostLauncher.HostOutcome ok = answer(job, false, true, List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) ok.document().get("result");
    result.put("status", "CANCELLED");
    result.put("failureCode", "CANCELLED");
    result.put("failureDetail", "cancelled by host");
    result.put("postconditions", Map.of("goalReached", false));
    reseal(result);
    return ok;
  }

  private static CandidateExecutionJobService.JobSpec withSeed(
      CandidateExecutionJobService.JobSpec s, String worldSeed) {
    return new CandidateExecutionJobService.JobSpec(
        s.jobId(),
        s.idempotencyKey(),
        s.baselineId(),
        s.scenarioId(),
        s.checkpointId(),
        s.contentVersion(),
        s.cacheVersion(),
        s.profileId(),
        s.recordingId(),
        s.initialProvenance(),
        s.task(),
        s.taskHash(),
        s.start(),
        s.executionMode(),
        worldSeed,
        s.taskSeed(),
        s.mouseSeed(),
        s.budgets());
  }

  private static CandidateExecutionJobService.JobStatus awaitLifecycle(
      CandidateExecutionJobService service,
      String key,
      CandidateExecutionJobService.Lifecycle lifecycle) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    CandidateExecutionJobService.JobStatus status = service.status(PROPOSAL, key);
    while (status.lifecycle() != lifecycle) {
      assertTrue(System.nanoTime() < deadline, key + " never became " + lifecycle + ": " + status);
      sleep(20);
      status = service.status(PROPOSAL, key);
    }
    return status;
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never held");
      }
      sleep(10);
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("latch never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
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

  /** The runner's answer when a recovery cannot resume: FAILED before any intent. */
  @SuppressWarnings("unchecked")
  private static CandidateHostLauncher.HostOutcome refusedRecovery(Map<String, Object> job) {
    CandidateHostLauncher.HostOutcome ok = answer(job, false, true, List.of());
    Map<String, Object> result = (Map<String, Object>) ok.document().get("result");
    result.put("status", "FAILED");
    result.put("failureCode", "CHECKPOINT_INCOMPLETE");
    result.put("failureDetail", "recovery refused before any intent");
    result.put("intentRefs", List.of());
    result.put("receiptRefs", List.of());
    result.put("consumedIntents", 0L);
    reseal(result);
    return ok;
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
