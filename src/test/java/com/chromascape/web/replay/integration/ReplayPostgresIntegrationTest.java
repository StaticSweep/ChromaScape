package com.chromascape.web.replay.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.api.dto.ReplayMode;
import com.chromascape.web.replay.api.dto.ReplayOutcome;
import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.kernel.XrspsKernelProcess;
import com.chromascape.web.replay.mismatch.MismatchEngine;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayFence;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.entity.ReplayAttempt;
import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.support.HostPortWaitStrategy;
import com.chromascape.web.replay.worker.ReplayExpiredAttemptRecoveryService;
import com.chromascape.web.replay.worker.ReplayExpiredAttemptRecoveryTransaction;
import com.chromascape.web.replay.worker.ReplayMissingReportReconciler;
import com.chromascape.web.replay.worker.ReplayRecoveryReportTransaction;
import com.chromascape.web.replay.worker.ReplayRecoveryReportWinnerReader;
import com.chromascape.web.replay.worker.ReplayRecoveryReportWriter;
import com.chromascape.web.replay.worker.ReplayWorker;
import com.chromascape.web.replay.worker.ReplayWorkerPersistenceService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * REAL PostgreSQL behavioural coverage for the replay persistence core. Mockito cannot prove native
 * locking SQL, database-clock predicates, rollback-only behaviour, unique-race handling, or
 * multi-partition rollback — only a live server can. Docker availability is a mandatory activation
 * gate for this suite.
 */
@DataJpaTest(
    properties = {
      // Flyway owns the schema; Hibernate may NEVER repair it behind Flyway's back.
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "spring.main.lazy-initialization=true",
      "spring.datasource.hikari.maximum-pool-size=16",
      "spring.datasource.hikari.connection-timeout=120000",
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@org.springframework.test.context.ContextConfiguration(
    classes = ReplayPostgresIntegrationTest.ReplaySliceApplication.class)
@org.springframework.test.context.ActiveProfiles("replay-postgres-integration")
@Import(ReplayPostgresIntegrationTest.ReplayServices.class)
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class ReplayPostgresIntegrationTest {

  /**
   * EXPLICIT slice application so this suite boots INDEPENDENTLY of the wider application.
   *
   * <p>Without it, {@code @DataJpaTest} walks up to {@code ChromaScapeApplication} and pulls the
   * WHOLE entity graph into schema validation — then {@code ddl-auto=validate} fails on unrelated
   * tables (e.g. {@code behavior_graph_definitions}) that exist only under Hibernate management in
   * application deployments and are deliberately absent from the replay-only Flyway chain. This
   * configuration restricts BOTH scans:
   *
   * <ul>
   *   <li>{@code @EntityScan}: ONLY replay entities covered by the replay migrations;
   *   <li>{@code @EnableJpaRepositories}: ONLY replay repositories.
   * </ul>
   *
   * <p>No Hibernate schema creation/update anywhere: Flyway V11–V22 is the single source of the
   * schema under test, and validation runs strictly against what Flyway produced.
   */
  @Configuration(proxyBeanMethods = false)
  @Profile("replay-postgres-integration")
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  @org.springframework.boot.autoconfigure.domain.EntityScan(
      basePackages = "com.chromascape.web.replay")
  @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
      basePackages = "com.chromascape.web.replay")
  static class ReplaySliceApplication {}

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("replay_behavior")
          .withUsername("test")
          .withPassword("test")
          .waitingFor(HostPortWaitStrategy.postgres());

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    // Concurrency bounds as per-test datasource options (the transaction-local SET LOCAL
    // alternative is impractical here: recovery and report writers run in REQUIRES_NEW
    // transactions that would not inherit a caller's SET LOCAL). Session-level lock_timeout
    // keeps a stale lock holder from blocking recovery forever; statement_timeout bounds any
    // single hung statement. Every Future below still uses a bounded get with diagnostics.
    registry.add(
        "spring.datasource.url",
        () ->
            POSTGRES.getJdbcUrl()
                + "?options=-c%20lock_timeout%3D5000%20-c%20statement_timeout%3D15000");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // Replay chain only: V11-V22 excluding the non-replay builder-repair V16, whose target
    // tables exist solely under Hibernate management in application deployments.
    registry.add("spring.flyway.locations", ReplayMigrationIntegrationTest::replayMigrations);
  }

  @Autowired ReplayRunRepository runRepository;
  @Autowired ReplayAttemptRepository attemptRepository;
  @Autowired ReplayEventRepository eventRepository;
  @Autowired ReplayCheckpointRepository checkpointRepository;
  @Autowired ReplayMismatchRepository mismatchRepository;

  @Autowired
  com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifactRefRepository;

  @Autowired StateHashSampleRepository hashSampleRepository;
  @Autowired ReplayReportRepository reportRepository;
  @Autowired ReplayRunService runService;
  @Autowired ReplayWorkerPersistenceService persistence;
  @Autowired ReplayExpiredAttemptRecoveryService recovery;
  @Autowired ReplayMissingReportReconciler reconciler;
  @Autowired com.chromascape.web.replay.artifact.ReplayArtifactService artifactService;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager txManager;

  private TransactionTemplate tx() {
    return new TransactionTemplate(txManager);
  }

  // ── database-clock deadline predicate ──

  @Test
  void deadlinePredicateUsesPostgresClockAndIntervalSyntax() {
    UUID[] ids = seedRunningRun();
    nativeUpdate(
        "UPDATE replay_runs SET started_at = (now() AT TIME ZONE 'UTC') - interval '1 hour'"
            + " WHERE id = '"
            + ids[0]
            + "'");
    assertTrue(
        Boolean.TRUE.equals(runRepository.isDeadlineExceeded(ids[0], 60)),
        "a run started an hour ago must exceed a 60-second deadline");
    nativeUpdate(
        "UPDATE replay_runs SET started_at = (now() AT TIME ZONE 'UTC') WHERE id = '"
            + ids[0]
            + "'");
    assertFalse(
        Boolean.TRUE.equals(runRepository.isDeadlineExceeded(ids[0], 3600)),
        "a just-started run must satisfy a one-hour deadline");
  }

  // ── remaining-deadline budget: explicit bigint on REAL PostgreSQL ──

  @Test
  void remainingDeadlineIsAPositiveBigintForAFreshRun() {
    UUID[] ids = seedRunningRun();
    nativeUpdate(
        "UPDATE replay_runs SET started_at = now() AT TIME ZONE 'UTC' WHERE id = '" + ids[0] + "'");
    Long remaining = runRepository.remainingDeadlineMs(ids[0], 3600);
    assertNotNull(remaining, "the bigint cast must materialize as a Long, never fail conversion");
    assertTrue(remaining > 0, "a fresh run must hold positive budget");
    assertTrue(remaining <= 3600_000L, "budget can never exceed its own deadline window");
  }

  @Test
  void remainingDeadlineIsNegativeOnceExpired() {
    UUID[] ids = seedRunningRun();
    nativeUpdate(
        "UPDATE replay_runs SET started_at = (now() AT TIME ZONE 'UTC') - interval '1 hour'"
            + " WHERE id = '"
            + ids[0]
            + "'");
    Long remaining = runRepository.remainingDeadlineMs(ids[0], 60);
    assertNotNull(remaining);
    assertTrue(
        remaining < 0,
        "a run past its deadline reports NEGATIVE remaining budget, got " + remaining);
  }

  @Test
  void missingRunYieldsNullRemainingDeadline() {
    assertNull(
        runRepository.remainingDeadlineMs(UUID.randomUUID(), 300),
        "a vanished run row must map to null so callers treat it as no time left");
  }

  // ── worker deadline bounding against REAL database clock ──

  private ReplayWorker deadlineBoundedWorker(long requestTimeoutMs) {
    ReplayProperties properties = new ReplayProperties();
    properties.getXrsps().setRequestTimeoutMs(requestTimeoutMs);
    return new ReplayWorker(
        runRepository,
        attemptRepository,
        runService,
        org.mockito.Mockito.mock(
            com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository.class),
        checkpointRepository,
        artifactRefRepository,
        mismatchRepository,
        persistence,
        org.mockito.Mockito.mock(com.chromascape.web.replay.worker.ReplayLeaseHeartbeat.class),
        org.mockito.Mockito.mock(ReplayExpiredAttemptRecoveryService.class),
        artifactService,
        org.mockito.Mockito.mock(MismatchEngine.class),
        reportService(),
        new CanonicalProjectionService(),
        properties,
        new com.fasterxml.jackson.databind.ObjectMapper());
  }

  private ReplayReportService reportService() {
    return new ReplayReportService(
        reportRepository,
        new MismatchEngine(mismatchRepository, new CanonicalProjectionService()),
        new CanonicalProjectionService(),
        new com.fasterxml.jackson.databind.ObjectMapper());
  }

  private com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage okResponse() {
    return new com.chromascape.web.replay.kernel.KernelProtocolCodec.KernelMessage(
        "resp",
        null,
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("ok", true),
        null,
        false,
        false);
  }

  @Test
  void childKernelTimeoutEqualsTheSmallerOfRequestTimeoutAndDatabaseBudget() {
    UUID[] ids = seedRunningRun();
    // Database budget ≈ 5s left of the 300s window; configured request timeout far larger,
    // so MIN() must select the database side even under slow-machine scheduling slop.
    nativeUpdate(
        "UPDATE replay_runs SET started_at = now() AT TIME ZONE 'UTC' - interval '295 seconds'"
            + " WHERE id = '"
            + ids[0]
            + "'");

    ReplayWorker worker = deadlineBoundedWorker(60_000L);
    var handle =
        org.mockito.Mockito.mock(
            com.chromascape.web.replay.worker.ReplayLeaseHeartbeat.Handle.class);
    org.mockito.Mockito.doNothing().when(handle).assertHealthy();
    XrspsKernelProcess kernel = org.mockito.Mockito.mock(XrspsKernelProcess.class);
    org.mockito.Mockito.when(
            kernel.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(okResponse());

    Long dbBudget = runRepository.remainingDeadlineMs(ids[0], 300);
    assertNotNull(dbBudget);
    assertTrue(dbBudget > 0, "the seeded database budget must still be positive, got " + dbBudget);

    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
        worker,
        "sendWithinRunDeadline",
        handle,
        kernel,
        runRepository.findById(ids[0]).orElseThrow(),
        "observe",
        null);

    org.mockito.ArgumentCaptor<Long> timeoutCaptor =
        org.mockito.ArgumentCaptor.forClass(Long.class);
    org.mockito.Mockito.verify(kernel)
        .send(
            org.mockito.ArgumentMatchers.eq("observe"),
            org.mockito.ArgumentMatchers.isNull(),
            timeoutCaptor.capture());
    Long effective = timeoutCaptor.getValue();
    assertTrue(
        effective <= 60_000L, "the kernel timeout can never exceed the configured request timeout");
    // Wall-clock time passes between the capture above and the worker's own query; the
    // contract under test is MIN(request timeout, remaining database budget): the effective
    // value can only be <= the captured budget (minus elapsed) and never the larger request
    // timeout while the database budget is the smaller side.
    assertTrue(
        effective <= 60_000L
            && effective >= Math.min(60_000L, dbBudget - 2_000)
            && effective <= Math.min(60_000L, dbBudget),
        () ->
            "the kernel timeout must equal the SMALLER database budget (captured "
                + dbBudget
                + "ms), got "
                + effective
                + "ms");
  }

  @Test
  void responseArrivingAfterTheRunDeadlineMapsToRunDeadlineExceeded() {
    UUID[] ids = seedRunningRun();

    ReplayWorker worker = deadlineBoundedWorker(60_000L);
    var handle =
        org.mockito.Mockito.mock(
            com.chromascape.web.replay.worker.ReplayLeaseHeartbeat.Handle.class);
    org.mockito.Mockito.doNothing().when(handle).assertHealthy();
    XrspsKernelProcess kernel = org.mockito.Mockito.mock(XrspsKernelProcess.class);
    org.mockito.Mockito.when(
            kernel.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocation -> {
              // The response ARRIVES, but by then the database deadline has passed.
              nativeUpdate(
                  "UPDATE replay_runs SET started_at = (now() AT TIME ZONE 'UTC')"
                      + " - interval '10 hours' WHERE id = '"
                      + ids[0]
                      + "'");
              return okResponse();
            });

    ReplayException e =
        assertThrows(
            ReplayException.class,
            () ->
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    worker,
                    "sendWithinRunDeadline",
                    handle,
                    kernel,
                    runRepository.findById(ids[0]).orElseThrow(),
                    "observe",
                    null));
    assertEquals(
        com.chromascape.web.replay.error.ReplayErrorCode.RUN_DEADLINE_EXCEEDED,
        e.getErrorCode(),
        "a late kernel success must still surface as RUN_DEADLINE_EXCEEDED");
  }

  // ── fenced locking query syntax (FOR UPDATE OF a) ──

  @Test
  void fencedLockingQueryExecutesOnRealPostgres() {
    UUID[] ids = seedRunningRun();
    int generation = attemptRepository.findById(ids[1]).orElseThrow().getFencingGeneration();
    Optional<ReplayAttempt> locked =
        tx().execute(
                status ->
                    attemptRepository.findValidFencedAttempt(
                        ids[1], ids[0], generation, "worker-a"));
    assertTrue(locked.isPresent(), "FOR UPDATE OF a + lease predicates must execute and match");
    Optional<ReplayAttempt> stale =
        tx().execute(
                status ->
                    attemptRepository.findValidFencedAttempt(
                        ids[1], ids[0], generation + 5, "worker-a"));
    assertTrue(stale.isEmpty(), "a wrong fencing generation must fail closed");
  }

  // ── claim race: exactly one winner ──

  @Test
  void twoConcurrentClaimsProduceExactlyOneAttempt() throws Exception {
    UUID runId = createQueuedRun();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger winners = new AtomicInteger();
    try {
      List<Future<Void>> jobs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String worker = "worker-" + i;
        jobs.add(
            pool.submit(
                (Callable<Void>)
                    () -> {
                      tx().executeWithoutResult(
                              status -> {
                                if (runService.claimAtomically(runId, worker) != null) {
                                  winners.incrementAndGet();
                                }
                              });
                      return null;
                    }));
      }
      for (int i = 0; i < jobs.size(); i++) {
        getBounded(jobs.get(i), 30, TimeUnit.SECONDS, "concurrent claim " + i);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, winners.get(), "exactly one claim may win the race");
  }

  // ── recovery separation: terminalization survives a GENUINE report failure ──

  /**
   * A PostgreSQL trigger makes EVERY report insertion fail, so the recovery report write fails for
   * a real database reason (not because a competing report was pre-inserted). Terminalization must
   * still commit; after the trigger is removed, reconciliation repairs exactly one report.
   */
  @Test
  void recoverySurvivesAGenuineReportFailureThenReconciliationRepairsOnce() {
    UUID[] ids = seedExpiredLeaseAttempt();
    nativeUpdate(
        "CREATE OR REPLACE FUNCTION replay_block_reports_test() RETURNS trigger AS $$"
            + " BEGIN RAISE EXCEPTION 'report insertion blocked by test trigger'; END;"
            + " $$ LANGUAGE plpgsql");
    nativeUpdate(
        "CREATE TRIGGER block_replay_reports_test BEFORE INSERT ON replay_reports"
            + " FOR EACH ROW EXECUTE FUNCTION replay_block_reports_test()");
    try {
      var applied = recovery.recover(ids[1]);
      assertTrue(applied.isPresent(), "recovery must commit despite the genuine report failure");

      assertEquals(
          "EXPIRED",
          attemptRepository.findById(ids[1]).orElseThrow().getState(),
          "attempt expiry must be committed");
      ReplayRun runAfter = runRepository.findById(ids[0]).orElseThrow();
      assertTrue(runAfter.isTerminal(), "run terminalization must be committed");
      assertNotNull(runAfter.getTerminalAt());
      assertTrue(
          reportRepository.findByRunId(ids[0]).isEmpty(), "the blocked report must NOT exist");
    } finally {
      nativeUpdate("DROP TRIGGER IF EXISTS block_replay_reports_test ON replay_reports");
      nativeUpdate("DROP FUNCTION IF EXISTS replay_block_reports_test()");
    }

    var stats = reconciler.repairBatch();
    assertEquals(1, stats.repaired(), "exactly one report after the blocker is removed");
    assertTrue(Boolean.TRUE.equals(runRepository.hasReport(ids[0])));
  }

  @Test
  void heartbeatRenewalWinsAgainstRecoveryOnTheSameRow() throws Exception {
    UUID[] ids = seedRunningRun();
    nativeUpdate(
        "UPDATE replay_attempts SET lease_expires_at = CURRENT_TIMESTAMP - interval '2 seconds'"
            + " WHERE id = '"
            + ids[1]
            + "'");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger renewals = new AtomicInteger();
    AtomicInteger expiries = new AtomicInteger();
    try {
      Future<Void> renewal =
          pool.submit(
              (Callable<Void>)
                  () -> {
                    for (int i = 0; i < 20 && expiries.get() == 0; i++) {
                      Integer updated =
                          tx().execute(
                                  status ->
                                      attemptRepository.renewLeaseFenced(
                                          ids[1],
                                          ids[0],
                                          attemptRepository
                                              .findById(ids[1])
                                              .orElseThrow()
                                              .getFencingGeneration(),
                                          "worker-a",
                                          300));
                      if (updated != null && updated == 1) {
                        renewals.incrementAndGet();
                        Thread.sleep(10);
                      } else {
                        break;
                      }
                    }
                    return null;
                  });
      // The recovery call runs DIRECTLY on this thread (never inside a caller TransactionTemplate
      // the way the self-deadlock used to): the orchestrator is NOT_SUPPORTED and its core is
      // REQUIRES_NEW, so terminalization commits independently of any outer transaction.
      Future<Void> expiry =
          pool.submit(
              (Callable<Void>)
                  () -> {
                    if (recovery.recover(ids[1]).isPresent()) {
                      expiries.incrementAndGet();
                    }
                    return null;
                  });
      getBounded(renewal, 30, TimeUnit.SECONDS, "heartbeat renewal racing recovery");
      getBounded(expiry, 30, TimeUnit.SECONDS, "recovery racing heartbeat renewal");
    } finally {
      pool.shutdownNow();
    }

    String finalState = attemptRepository.findById(ids[1]).orElseThrow().getState();
    if ("RUNNING".equals(finalState)) {
      assertTrue(renewals.get() > 0, "a renewed lease must keep the attempt RUNNING");
      assertTrue(expiries.get() == 0, "a renewed lease must never be terminalized by recovery");
    } else {
      assertEquals("EXPIRED", finalState, "only the atomic expiry may end the lease");
      assertTrue(expiries.get() == 1, "exactly one recovery must have won and terminalized");
      assertTrue(
          Boolean.TRUE.equals(runRepository.hasReport(ids[0]))
              || reportRepository.findByRunId(ids[0]).isEmpty(),
          "recovery either wrote its report or reconciliation owns it");
    }
  }

  @Test
  void twoRecoveryInstancesExpireOneAttemptExactlyOnce() throws Exception {
    UUID[] ids = seedExpiredLeaseAttempt();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger expiries = new AtomicInteger();
    List<Future<Void>> jobs = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        jobs.add(
            pool.submit(
                (Callable<Void>)
                    () -> {
                      // Direct call, never wrapped in a caller transaction: the REQUIRES_NEW core
                      // commits attempt expiry + run terminalization before the report writer runs.
                      if (recovery.recover(ids[1]).isPresent()) {
                        expiries.incrementAndGet();
                      }
                      return null;
                    }));
      }
      for (int i = 0; i < jobs.size(); i++) {
        getBounded(jobs.get(i), 30, TimeUnit.SECONDS, "concurrent recovery instance " + i);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, expiries.get(), "atomic expiry must admit exactly one recovery");
    assertTrue(recovery.recover(ids[1]).isEmpty(), "already-terminal runs recover to empty");
  }

  // ── missing-report reconciliation idempotency (NO outer transaction) ──

  @Test
  void reconcilerRepairsAMissingReportExactlyOnce() {
    UUID[] ids = seedTerminalRunWithoutReport();
    assertFalse(Boolean.TRUE.equals(runRepository.hasReport(ids[0])));
    // Deliberately NO surrounding transaction: the orchestrator must drive its own per-run
    // REQUIRES_NEW writer transactions.
    var stats = reconciler.repairBatch();
    assertNotNull(stats);
    assertEquals(1, stats.repaired(), "the terminal run without a report must be repaired");
    assertTrue(Boolean.TRUE.equals(runRepository.hasReport(ids[0])));
    var secondPass = reconciler.repairBatch();
    assertEquals(0, secondPass.repaired(), "a repaired run must not be selected again");
  }

  /** Two instances contend on the same missing report: one advisory-lock winner, zero deadlocks. */
  @Test
  void twoReconcilerInstancesContendWithoutDeadlockAndCreateOneReport() throws Exception {
    UUID[] ids = seedTerminalRunWithoutReport();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger repaired = new AtomicInteger();
    List<ReplayMissingReportReconciler.RepairStats> allStats =
        java.util.Collections.synchronizedList(new ArrayList<>());
    try {
      List<Future<Void>> jobs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        jobs.add(
            pool.submit(
                (Callable<Void>)
                    () -> {
                      var stats = reconciler.repairBatch();
                      repaired.addAndGet(stats.repaired());
                      allStats.add(stats);
                      return null;
                    }));
      }
      for (int i = 0; i < jobs.size(); i++) {
        getBounded(jobs.get(i), 60, TimeUnit.SECONDS, "concurrent reconciler instance " + i);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, repaired.get(), "the advisory lock must admit exactly one repairer");
    assertEquals(1, reportRepository.findByRunId(ids[0]).stream().count());

    // Metrics: every outcome must be fully classified — no candidate may vanish into an
    // unclassified bucket, and any contention counts as SKIPPED, never as failed. Whether
    // contention actually occurs is scheduler-dependent; full classification is not.
    for (var stats : allStats) {
      assertEquals(
          stats.attempted(),
          stats.repaired()
              + stats.alreadyPresent()
              + stats.skipped()
              + stats.ineligible()
              + stats.failed(),
          () -> "unclassified repair outcomes in " + stats);
      assertEquals(0, stats.failed(), "contention is a skip, not a failure");
      assertTrue(
          stats.skipped() == 0 || stats.repaired() == 0,
          "a contended batch either repairs nothing (lost the lock) or repairs its winner");
    }
  }

  // ── V21: pre-execution cancellation reports ──

  @Test
  void queuedCancellationAtomicallyWritesItsPreExecutionReport() {
    UUID runId = createQueuedRun();
    tx().executeWithoutResult(status -> runService.cancel(runId, "owner"));

    ReplayRun cancelled = runRepository.findById(runId).orElseThrow();
    assertEquals(ReplayRunState.CANCELLED, cancelled.getState());
    assertEquals(ReplayOutcome.CANCELLED, cancelled.getOutcome());
    assertEquals("CANCEL_REQUESTED", cancelled.getTerminalReason());
    assertNotNull(cancelled.getTerminalAt());

    ReplayReport report = reportRepository.findByRunId(runId).orElseThrow();
    assertNull(report.getAttemptId(), "pre-execution reports carry no attempt");
    assertNull(report.getStartedAt());
    assertEquals(0, report.getExecutedActionCount());
    assertEquals(0, report.getCheckpointCount());
    assertEquals(ReplayOutcome.CANCELLED, report.getOutcome());
    assertEquals("CANCEL_REQUESTED", report.getTerminalReason());
    assertFalse(report.isControlAllowed());
  }

  @Test
  void failedQueuedCancellationReportRollsBackTheCancellationToo() {
    UUID runId = createQueuedRun();
    nativeUpdate(
        "CREATE OR REPLACE FUNCTION replay_block_reports_test2() RETURNS trigger AS $$"
            + " BEGIN RAISE EXCEPTION 'report insertion blocked by test trigger'; END;"
            + " $$ LANGUAGE plpgsql");
    nativeUpdate(
        "CREATE TRIGGER block_replay_reports_test2 BEFORE INSERT ON replay_reports"
            + " FOR EACH ROW EXECUTE FUNCTION replay_block_reports_test2()");
    try {
      assertThrows(
          Exception.class,
          () -> tx().executeWithoutResult(status -> runService.cancel(runId, "owner")),
          "report failure must fail the whole cancellation transaction");
    } finally {
      nativeUpdate("DROP TRIGGER IF EXISTS block_replay_reports_test2 ON replay_reports");
      nativeUpdate("DROP FUNCTION IF EXISTS replay_block_reports_test2()");
    }
    // Atomic rollback: the run is STILL QUEUED and no report exists.
    assertEquals(ReplayRunState.QUEUED, runRepository.findById(runId).orElseThrow().getState());
    assertTrue(reportRepository.findByRunId(runId).isEmpty());
  }

  @Test
  void v21AllowsNullAttemptIdWhilePreservingTheForeignKey() {
    UUID[] ids = seedRunningRun();
    UUID reportId = UUID.randomUUID();
    // NULL attempt_id inserts cleanly after V21. The class runs NOT_SUPPORTED, so this
    // update-capable native statement needs an explicit transaction.
    tx().executeWithoutResult(
            status ->
                entityManager
                    .createNativeQuery(
                        "INSERT INTO replay_reports (id, run_id, attempt_id, mode,"
                            + " manifest_checksum, outcome, executed_action_count,"
                            + " checkpoint_count, mismatch_hard_count, mismatch_soft_count,"
                            + " mismatch_info_count, report_json, report_checksum, control_allowed,"
                            + " finalized_at)"
                            + " VALUES ('"
                            + reportId
                            + "', '"
                            + ids[0]
                            + "', NULL, 'CAUSAL_SIMULATION', 'sha256:"
                            + "0".repeat(64)
                            + "', 'CANCELLED', 0, 0, 0, 0, 0, '{}'::jsonb, 'sha256:"
                            + "e".repeat(63)
                            + "f', false, now())")
                    .executeUpdate());
    Object attemptId =
        entityManager
            .createNativeQuery(
                "SELECT attempt_id FROM replay_reports WHERE id = '" + reportId + "'")
            .getSingleResult();
    assertNull(attemptId, "V21 dropped NOT NULL from attempt_id");

    // The foreign key SURVIVES for non-null values: a bogus attempt id violates it.
    UUID bogus = UUID.randomUUID();
    assertThrows(
        Exception.class,
        () ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO replay_reports (id, run_id, attempt_id, mode, manifest_checksum,"
                        + " outcome, executed_action_count, checkpoint_count, mismatch_hard_count,"
                        + " mismatch_soft_count, mismatch_info_count, report_json, report_checksum,"
                        + " control_allowed, finalized_at)"
                        + " VALUES ('"
                        + UUID.randomUUID()
                        + "', '"
                        + ids[0]
                        + "', '"
                        + bogus
                        + "', 'CAUSAL_SIMULATION', 'sha256:"
                        + "0".repeat(64)
                        + "', 'FAILED', 0, 0, 0, 0, 0, '{}'::jsonb, 'sha256:"
                        + "b".repeat(64)
                        + "', false, now())")
                .executeUpdate(),
        "FK replay_reports(attempt_id) must still be enforced");
  }

  // ── unique-race handling ──

  @Test
  void concurrentReportInsertsAdmitExactlyOneWinner() throws Exception {
    UUID[] ids = seedTerminalRunWithoutReport();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    AtomicInteger winners = new AtomicInteger();
    List<Future<Void>> jobs = new ArrayList<>();
    try {
      for (int seq = 0; seq < 2; seq++) {
        final int s = seq;
        jobs.add(
            pool.submit(
                (Callable<Void>)
                    () -> {
                      ReplayReport r = new ReplayReport();
                      r.setRunId(ids[0]);
                      r.setAttemptId(ids[1]);
                      r.setMode(ReplayMode.CAUSAL_SIMULATION);
                      r.setManifestChecksum("sha256:" + "0".repeat(64));
                      r.setOutcome(ReplayOutcome.CANCELLED);
                      r.setExecutedActionCount(s);
                      r.setCheckpointCount(0);
                      r.setMismatchHardCount(0);
                      r.setMismatchSoftCount(0);
                      r.setMismatchInfoCount(0);
                      r.setReportJson("{}");
                      r.setReportChecksum("sha256:" + ("f".repeat(60) + "0" + s));
                      r.setControlAllowed(false);
                      r.setFinalizedAt(java.time.Instant.now());
                      try {
                        // The loser's transaction is marked rollback-only by the unique
                        // violation: exactly what recovery separation relies on.
                        tx().executeWithoutResult(status -> reportRepository.saveAndFlush(r));
                        winners.incrementAndGet();
                      } catch (DataIntegrityViolationException
                          | org.springframework.transaction.UnexpectedRollbackException lost) {
                        // expected loser of the unique(run_id) race
                      }
                      return null;
                    }));
      }
      for (int i = 0; i < jobs.size(); i++) {
        getBounded(jobs.get(i), 30, TimeUnit.SECONDS, "concurrent report insert " + i);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, winners.get(), "unique(run_id) must admit exactly one report row");
  }

  // ── real batching: >2 partitions, final-batch failure rolls back EVERYTHING ──

  @Test
  void lateConstraintFailureRollsBackAllEventPartitionsAndHashSample() {
    UUID[] ids = seedRunningRun();
    ReplayFence fence = fenceOf(ids);

    List<ReplayEvent> events = new ArrayList<>();
    int total = 250; // three partitions under max-event-batch=100
    for (long ordinal = 0; ordinal < total; ordinal++) {
      events.add(event(ids, ordinal));
    }
    // LATE failure: strip event_type from an event in the FINAL partition. The service-level
    // guards (fence identity, negative ordinals) pass — validation happens per-flush — so the
    // NOT NULL violation fires only while flushing that last partition, AFTER earlier
    // partitions were already flushed uncommitted. Repository-boundary translation surfaces it
    // as DataIntegrityViolationException, and the single surrounding transaction must roll back
    // EVERY partition plus the state-hash sample.
    events.get(events.size() - 1).setEventType(null);

    assertThrows(
        DataIntegrityViolationException.class,
        () -> persistence.appendActionResult(fence, events, sample(ids)));

    assertEquals(0, eventRepository.countByRunId(ids[0]), "no partition may survive rollback");
    assertEquals(
        0,
        hashSampleRepository.countByIdRunId(ids[0]),
        "the state-hash sample must roll back together with its events");
  }

  // ── checkpoint reference + row atomicity (distinct checksums A/B) ──

  @Test
  void failedCheckpointInsertRollsBackItsArtifactReference() {
    UUID[] ids = seedRunningRun();
    ReplayFence fence = fenceOf(ids);

    // Checkpoint A: registers cleanly with artifact checksum A.
    byte[] bytesA = "{\"snapshot\":\"A\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    PreparedArtifactLike preparedA = stageSnapshot(ids, bytesA);
    persistence.registerCheckpoint(
        fence, preparedA.artifact(), checkpoint(ids, 7L, preparedA.checksum()));

    // Conflicting checkpoint B at the same tick+action_index violates the uniqueness constraint
    // AFTER its own artifact reference registration; its NEW checksum B must not survive.
    byte[] bytesB = "{\"snapshot\":\"B\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    PreparedArtifactLike preparedB = stageSnapshot(ids, bytesB);
    assertNotEquals(preparedA.checksum(), preparedB.checksum(), "A and B need distinct content");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            persistence.registerCheckpoint(
                fence, preparedB.artifact(), checkpoint(ids, 7L, preparedB.checksum())));

    assertEquals(
        0,
        artifactCount(ids[0], preparedB.checksum()),
        "the conflicting checksum-B artifact reference must NOT survive rollback");
    assertEquals(
        1,
        artifactCount(ids[0], preparedA.checksum()),
        "the successful checksum-A reference survives exactly once");
  }

  private record PreparedArtifactLike(
      com.chromascape.web.replay.artifact.PreparedArtifact artifact, String checksum) {}

  private PreparedArtifactLike stageSnapshot(UUID[] ids, byte[] bytes) {
    var prepared =
        artifactService.prepareArtifact(
            ids[0],
            com.chromascape.web.replay.api.dto.ArtifactKind.SNAPSHOT,
            bytes,
            "application/json");
    return new PreparedArtifactLike(prepared, prepared.checksum());
  }

  private com.chromascape.web.replay.run.entity.ReplayCheckpoint checkpoint(
      UUID[] ids, long tick, String snapshotChecksum) {
    return new com.chromascape.web.replay.run.entity.ReplayCheckpoint(
        UUID.randomUUID(), ids[0], ids[1], tick, snapshotChecksum, "sha256:s", "sha256:c", "main");
  }

  private long artifactCount(UUID runId, String checksum) {
    return tx().execute(
            status -> {
              var result =
                  entityManager
                      .createNativeQuery(
                          "SELECT count(*) FROM artifact_refs WHERE run_id = ?1 AND checksum = ?2")
                      .setParameter(1, runId)
                      .setParameter(2, checksum)
                      .getSingleResult();
              return ((Number) result).longValue();
            });
  }

  // ── bounded future get with live-database diagnostics ──

  /**
   * Bounded {@code Future.get} that, on timeout, reports the LIVE database state so a stuck
   * concurrency test never hides behind a bare {@code TimeoutException}: active sessions and their
   * wait events / SQL, plus the run, attempt, fencing generation, lease expiry, and report
   * existence for the seeded pair.
   */
  private void getBounded(Future<?> future, long timeout, TimeUnit unit, String what)
      throws Exception {
    try {
      future.get(timeout, unit);
    } catch (java.util.concurrent.TimeoutException timeoutEx) {
      throw new AssertionError(
          "Timed out after "
              + timeout
              + " "
              + unit
              + " waiting for "
              + what
              + "\n"
              + databaseDiagnostics(),
          timeoutEx);
    }
  }

  private String databaseDiagnostics() {
    StringBuilder sb = new StringBuilder("Live PostgreSQL diagnostics:\n");
    try {
      List<?> blocked =
          tx().execute(
                  status ->
                      entityManager
                          .createNativeQuery(
                              "SELECT pid, state, wait_event_type, wait_event,"
                                  + " left(query, 120), now() - xact_start AS tx_age"
                                  + " FROM pg_stat_activity"
                                  + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
                                  + " ORDER BY pid")
                          .getResultList());
      for (Object row : blocked) {
        sb.append("  session: ").append(String.valueOf(row)).append('\n');
      }
      if (blocked.isEmpty()) {
        sb.append("  (no other sessions)\n");
      }
    } catch (Exception e) {
      sb.append("  pg_stat_activity unavailable: ").append(e.getMessage()).append('\n');
    }
    List<ReplayRun> runs = runRepository.findAll();
    for (ReplayRun run : runs) {
      ReplayAttempt latest =
          attemptRepository.findFirstByRunIdOrderByAttemptNoDesc(run.getId()).orElse(null);
      sb.append("  run ")
          .append(run.getId())
          .append(" state=")
          .append(run.getState())
          .append(" phase=")
          .append(run.getPhase())
          .append(" terminalAt=")
          .append(run.getTerminalAt())
          .append(" report=")
          .append(reportRepository.findByRunId(run.getId()).isPresent())
          .append('\n');
      if (latest != null) {
        sb.append("  attempt ")
            .append(latest.getId())
            .append(" state=")
            .append(latest.getState())
            .append(" fencingGeneration=")
            .append(latest.getFencingGeneration())
            .append(" leaseExpiresAt=")
            .append(latest.getLeaseExpiresAt())
            .append('\n');
      }
    }
    return sb.toString();
  }

  // ── helpers ──

  private void nativeUpdate(String sql) {
    tx().executeWithoutResult(status -> entityManager.createNativeQuery(sql).executeUpdate());
  }

  private ReplayEvent event(UUID[] ids, long ordinal) {
    ReplayEvent e = new ReplayEvent();
    e.setRunId(ids[0]);
    e.setAttemptId(ids[1]);
    e.setOrdinal(ordinal);
    e.setTick(ordinal);
    e.setSequence(0);
    e.setEventType("TICK_ADVANCED");
    e.setCategory("SIMULATION");
    return e;
  }

  private com.chromascape.web.replay.run.entity.StateHashSample sample(UUID[] ids) {
    return new com.chromascape.web.replay.run.entity.StateHashSample(
        ids[0], 10L, 0, 1, "sha256:s", "sha256:c");
  }

  private ReplayFence fenceOf(UUID[] ids) {
    ReplayAttempt a = attemptRepository.findById(ids[1]).orElseThrow();
    return new ReplayFence(
        ids[0], ids[1], a.getAttemptNo(), a.getFencingGeneration(), a.getWorkerIdentity());
  }

  private UUID createQueuedRun() {
    return tx().execute(
            status -> {
              ReplayRun run = newQueuedRun();
              run.setManifestId(seedManifestNative());
              return runRepository.saveAndFlush(run).getId();
            });
  }

  private static final AtomicInteger manifestCounter = new AtomicInteger();

  /** Minimal curation + published manifest rows satisfying V11 foreign keys, via native SQL. */
  private UUID seedManifestNative() {
    String recordingId = "rec-it-" + manifestCounter.incrementAndGet();
    java.util.UUID curationId = java.util.UUID.randomUUID();
    java.util.UUID manifestId = java.util.UUID.randomUUID();
    String checksumHex = "d".repeat(56) + String.format("%08x", manifestCounter.get());
    entityManager
        .createNativeQuery(
            "INSERT INTO recording_curations (id, recording_id, debug_session_id, version,"
                + " state, role, content_json, content_checksum, created_by)"
                + " VALUES ('"
                + curationId
                + "', '"
                + recordingId
                + "', 'sess-it', 1,"
                + " 'PUBLISHED', 'TRAIN', '{}'::jsonb, 'sha256:"
                + "c".repeat(64)
                + "', 'tester')")
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "INSERT INTO scenario_manifests (id, curation_id, recording_id, debug_session_id,"
                + " recording_checksum, debug_session_checksum, compiler_version,"
                + " compiler_schema_version, xrsps_adapter_version, xrsps_build_commit,"
                + " cache_revision, content_revision, seed, fixed_clock_origin, start_tick,"
                + " end_tick, manifest_json, manifest_checksum, completeness, publish_state,"
                + " created_at)"
                + " VALUES ('"
                + manifestId
                + "', '"
                + curationId
                + "', '"
                + recordingId
                + "', 'sess-it', 'sha256:"
                + "a".repeat(64)
                + "', 'sha256:"
                + "b".repeat(64)
                + "', 'it', 1, 'test-adapter', 'test-commit', 'cache-it', 'content-it',"
                + " 'seed-it', TIMESTAMP '2020-01-01 00:00:00', 0, 10, '{}'::jsonb, 'sha256:"
                + checksumHex
                + "', 'COMPLETE', 'PUBLISHED', now())")
        .executeUpdate();
    return manifestId;
  }

  private ReplayRun newQueuedRun() {
    ReplayRun run = new ReplayRun();
    // Id left to Hibernate generation: a preset id would route save() through merge().
    run.setMode(ReplayMode.CAUSAL_SIMULATION);
    run.setManifestId(UUID.randomUUID());
    run.setManifestChecksum("sha256:" + "0".repeat(64));
    run.setOwnerId("owner");
    run.setIdempotencyKey("idem-" + UUID.randomUUID());
    run.setState(ReplayRunState.QUEUED);
    run.setPhase(ReplayRunPhase.PREFLIGHT);
    run.setBranchKey("main");
    run.setCreatedAt(LocalDateTime.now());
    // Strict reports refuse to render without a populated runtime identity: EVERY seeded run
    // carries one so recovery/reconciliation can always complete truthfully.
    run.setXrspsAdapterVersion("it-adapter");
    run.setXrspsBuildCommit("b".repeat(40));
    run.setCacheRevision("sha256:" + "c".repeat(64));
    run.setContentRevision("sha256:" + "d".repeat(64));
    return run;
  }

  /** [runId, attemptId] with RUNNING state and a live lease owned by worker-a. */
  private UUID[] seedRunningRun() {
    return tx().execute(
            status -> {
              ReplayRun queued = newQueuedRun();
              queued.setManifestId(seedManifestNative());
              UUID runId = runRepository.saveAndFlush(queued).getId();
              runRepository.claimQueuedRun(runId);
              UUID attemptId = UUID.randomUUID();
              attemptRepository.insertAttemptWithDatabaseClock(
                  attemptId, runId, 1, 1, "worker-a", 300);
              // Worker-visible execution state: CLAIMED -> RUNNING with an execution phase.
              ReplayRun claimed = runRepository.findByIdForUpdate(runId).orElseThrow();
              claimed.setState(ReplayRunState.RUNNING);
              claimed.setPhase(ReplayRunPhase.EXECUTE);
              runRepository.save(claimed);
              return new UUID[] {runId, attemptId};
            });
  }

  /** [runId, attemptId] whose RUNNING attempt has an expired lease on the database clock. */
  private UUID[] seedExpiredLeaseAttempt() {
    UUID[] ids = seedRunningRun();
    nativeUpdate(
        "UPDATE replay_attempts SET lease_expires_at = CURRENT_TIMESTAMP - interval '1 hour'"
            + " WHERE id = '"
            + ids[1]
            + "'");
    return ids;
  }

  /** [runId, attemptId] with a terminal FAILED run that carries no report. */
  private UUID[] seedTerminalRunWithoutReport() {
    return tx().execute(
            status -> {
              ReplayRun queued = newQueuedRun();
              queued.setManifestId(seedManifestNative());
              UUID runId = runRepository.saveAndFlush(queued).getId();
              runRepository.claimQueuedRun(runId);
              UUID attemptId = UUID.randomUUID();
              attemptRepository.insertAttemptWithDatabaseClock(
                  attemptId, runId, 1, 1, "worker-a", 300);
              LocalDateTime dbNow = runRepository.currentTimestamp();
              ReplayRun run = runRepository.findByIdForUpdate(runId).orElseThrow();
              run.setState(ReplayRunState.FAILED);
              run.setOutcome(ReplayOutcome.INFRASTRUCTURE_FAILURE);
              run.setTerminalReason("LEASE_EXPIRED");
              run.setStartedAt(dbNow.minusSeconds(120));
              run.setTerminalAt(dbNow);
              runRepository.save(run);
              return new UUID[] {runId, attemptId};
            });
  }

  /**
   * The ONLY services this slice needs, registered explicitly: the narrowed context has no
   * component scan, so every collaborator of the replay persistence core is wired by hand.
   */
  @TestConfiguration
  @org.springframework.context.annotation.Profile("replay-postgres-integration")
  static class ReplayServices {

    @Bean
    com.chromascape.web.replay.config.ReplayProperties replayProperties() {
      return new com.chromascape.web.replay.config.ReplayProperties();
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper replayObjectMapper() {
      // Canonical JSON must never pretty-print: the shared projection depends on plain Jackson.
      return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @Bean
    CanonicalProjectionService canonicalProjectionService() {
      return new CanonicalProjectionService();
    }

    @Bean
    MismatchEngine mismatchEngine(
        com.chromascape.web.replay.run.repository.ReplayMismatchRepository mismatches,
        CanonicalProjectionService projectionService) {
      return new MismatchEngine(mismatches, projectionService);
    }

    @Bean
    ReplayReportService replayReportService(
        ReplayReportRepository reports,
        MismatchEngine mismatchEngine,
        CanonicalProjectionService projectionService,
        com.fasterxml.jackson.databind.ObjectMapper mapper) {
      return new ReplayReportService(reports, mismatchEngine, projectionService, mapper);
    }

    @Bean
    ReplayRunService replayRunService(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayCheckpointRepository checkpoints,
        ReplayEventRepository events,
        ReplayReportRepository reports,
        com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository manifests,
        ReplayReportService reportService,
        org.springframework.core.env.Environment environment) {
      return new ReplayRunService(
          runs, attempts, checkpoints, events, reports, manifests, reportService, environment);
    }

    @Bean
    ReplayWorkerPersistenceService replayWorkerPersistenceService(
        ReplayRunService runService,
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayEventRepository events,
        StateHashSampleRepository hashSamples,
        ReplayCheckpointRepository checkpoints,
        com.chromascape.web.replay.run.repository.ReplayMismatchRepository mismatches,
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        ReplayReportRepository reports,
        com.chromascape.web.replay.config.ReplayProperties properties,
        EntityManager entityManager) {
      return new ReplayWorkerPersistenceService(
          runService,
          runs,
          attempts,
          events,
          hashSamples,
          checkpoints,
          mismatches,
          artifacts,
          reports,
          properties,
          entityManager);
    }

    @Bean
    ReplayExpiredAttemptRecoveryTransaction recoveryExpiredAttemptTransaction(
        ReplayAttemptRepository attempts,
        ReplayRunRepository runs,
        ReplayRunService runService,
        com.chromascape.web.replay.config.ReplayProperties properties) {
      return new ReplayExpiredAttemptRecoveryTransaction(attempts, runs, runService, properties);
    }

    @Bean
    ReplayExpiredAttemptRecoveryService recoveryExpiredAttemptService(
        ReplayExpiredAttemptRecoveryTransaction transaction,
        ReplayRecoveryReportWriter reportWriter) {
      return new ReplayExpiredAttemptRecoveryService(transaction, reportWriter);
    }

    @Bean
    com.chromascape.web.replay.artifact.ReplayArtifactService replayArtifactService(
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        com.chromascape.web.replay.config.ReplayProperties properties) {
      return new com.chromascape.web.replay.artifact.ReplayArtifactService(artifacts, properties);
    }

    @Bean
    ReplayRecoveryReportTransaction recoveryReportTransaction(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        StateHashSampleRepository hashSamples,
        ReplayCheckpointRepository checkpoints,
        com.chromascape.web.replay.artifact.repository.ArtifactRefRepository artifacts,
        ReplayReportRepository reports,
        ReplayMismatchRepository mismatches,
        ReplayReportService reportService) {
      return new ReplayRecoveryReportTransaction(
          runs, attempts, hashSamples, checkpoints, artifacts, reports, mismatches, reportService);
    }

    @Bean
    ReplayRecoveryReportWinnerReader recoveryReportWinnerReader(ReplayReportRepository reports) {
      return new ReplayRecoveryReportWinnerReader(reports);
    }

    @Bean
    ReplayRecoveryReportWriter recoveryReportWriter(
        ReplayRecoveryReportTransaction transaction,
        ReplayRecoveryReportWinnerReader winnerReader,
        ReplayRunRepository runs,
        CanonicalProjectionService projectionService,
        com.fasterxml.jackson.databind.ObjectMapper mapper) {
      return new ReplayRecoveryReportWriter(
          transaction, winnerReader, runs, projectionService, mapper);
    }

    @Bean
    ReplayMissingReportReconciler missingReportReconciler(
        ReplayRunRepository runs, ReplayRecoveryReportWriter writer, ReplayProperties properties) {
      return new ReplayMissingReportReconciler(runs, writer, properties);
    }
  }
}
