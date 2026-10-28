package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SC-2106 R4, the R6 recovery claim and the R9 controls against a real PostgreSQL (disposable
 * schema; the V71, V73, V74 and V75 migrations as shipped). Run through {@code
 * sc2106ManagedAttemptTest}, which fails instead of skipping without a database.
 */
class CandidateAttemptStoreIntegrationTest {
  private static JdbcTemplate admin;
  private static DriverManagerDataSource dataSource;
  private static String schema;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void connect() throws Exception {
    String url = System.getenv("SC2106_TEST_JDBC_URL");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("SC2106_TEST_JDBC_URL is required for the R4 store tests");
    }
    String user = System.getenv().getOrDefault("SC2106_TEST_JDBC_USER", "test");
    String password = System.getenv().getOrDefault("SC2106_TEST_JDBC_PASSWORD", "test");
    schema = "sc2106_attempts_" + UUID.randomUUID().toString().replace("-", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("CREATE SCHEMA " + schema);
    dataSource =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
    jdbc = new JdbcTemplate(dataSource);
    for (String migration :
        List.of(
            "V71__sc2106_candidate_execution_attempts.sql",
            "V73__sc2106_one_recovery_per_attempt.sql",
            "V74__sc2106_attempt_controls.sql",
            "V75__sc2106_attempt_worker_binding.sql")) {
      try (var input =
          CandidateAttemptStoreIntegrationTest.class.getResourceAsStream(
              "/db/migration/" + migration)) {
        jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @AfterAll
  static void drop() {
    if (admin != null) {
      admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @BeforeEach
  void truncate() {
    jdbc.execute("TRUNCATE candidate_execution_attempts, candidate_attempt_worker_bindings");
  }

  /** A fresh store instance per call stands in for a separate owner process. */
  private static JdbcCandidateAttemptStore owner() {
    return new JdbcCandidateAttemptStore(
        jdbc,
        new ObjectMapper().findAndRegisterModules(),
        new DataSourceTransactionManager(dataSource));
  }

  @Test
  void simultaneousReservationsYieldExactlyOneAttempt() throws Exception {
    int threads = 12;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<CandidateAttemptStore.Reservation>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<CandidateAttemptStore.Reservation> reserve =
          () -> {
            go.await();
            return owner()
                .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1));
          };
      results.add(pool.submit(reserve));
    }
    go.countDown();
    int created = 0;
    String attemptId = null;
    for (Future<CandidateAttemptStore.Reservation> f : results) {
      CandidateAttemptStore.Reservation r = f.get();
      created += r.created() ? 1 : 0;
      if (attemptId == null) {
        attemptId = r.attempt().attemptId();
      }
      assertEquals(attemptId, r.attempt().attemptId(), "every caller sees the same attempt");
    }
    pool.shutdownNow();
    assertEquals(1, created);
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM candidate_execution_attempts", Integer.class));
  }

  /** An attempt whose owner died: UNCERTAIN, as a recovery finds it. */
  private static String uncertainSource() {
    CandidateAttemptStore store = owner();
    var held =
        store
            .reserve("proposal:p1", "crashed", "job-0", "sha256:f", Duration.ofMinutes(1))
            .attempt();
    store.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:request");
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        held.attemptId());
    store.reconcileExpired(Instant.now());
    return held.attemptId();
  }

  @Test
  void simultaneousRecoveriesOfOneAttemptYieldExactlyOne() throws Exception {
    String source = uncertainSource();
    int threads = 12;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String key = "recover-" + i;
      Callable<Object> recover =
          () -> {
            go.await();
            try {
              return owner()
                  .reserveRecovery(
                      "proposal:p1", key, "job-r", "sha256:r", Duration.ofMinutes(1), source);
            } catch (CandidateAttemptStore.RecoveryClaimedException e) {
              return e;
            }
          };
      results.add(pool.submit(recover));
    }
    go.countDown();
    String winner = null;
    int claimed = 0;
    for (Future<Object> f : results) {
      Object r = f.get();
      if (r instanceof CandidateAttemptStore.Reservation reservation) {
        assertTrue(reservation.created());
        assertNull(winner, "only one recovery is reserved");
        winner = reservation.attempt().attemptId();
      } else {
        claimed++;
      }
    }
    pool.shutdownNow();
    assertEquals(threads - 1, claimed);
    for (Future<Object> f : results) {
      if (f.get() instanceof CandidateAttemptStore.RecoveryClaimedException e) {
        assertEquals(winner, e.holder(), "the refusal names the recovery that holds the claim");
      }
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM candidate_execution_attempts WHERE recovers = ?",
            Integer.class,
            source));
  }

  @Test
  void aRecoveryRetryReattachesAndOnlyAFailedRecoveryReleasesTheClaim() {
    String source = uncertainSource();
    CandidateAttemptStore store = owner();
    var first =
        store.reserveRecovery(
            "proposal:p1", "recover-a", "job-r", "sha256:r", Duration.ofMinutes(1), source);
    var retry =
        owner()
            .reserveRecovery(
                "proposal:p1", "recover-a", "job-r", "sha256:r", Duration.ofMinutes(1), source);
    assertFalse(retry.created(), "the same key reattaches; the claim index never fires");
    assertEquals(first.attempt().attemptId(), retry.attempt().attemptId());

    var held = first.attempt();
    store.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:r");
    store.finish(
        held.attemptId(),
        held.leaseId(),
        held.epoch(),
        CandidateAttemptStore.State.FAILED,
        Map.of("outcome", "FAILED"),
        "CHECKPOINT_INCOMPLETE");
    var second =
        owner()
            .reserveRecovery(
                "proposal:p1", "recover-b", "job-r", "sha256:r", Duration.ofMinutes(1), source);
    assertTrue(second.created(), "a FAILED recovery released its claim");

    var next = second.attempt();
    store.finish(
        next.attemptId(),
        next.leaseId(),
        next.epoch(),
        CandidateAttemptStore.State.COMPLETED,
        Map.of("outcome", "PASS"),
        "done");
    var refused =
        assertThrows(
            CandidateAttemptStore.RecoveryClaimedException.class,
            () ->
                owner()
                    .reserveRecovery(
                        "proposal:p1",
                        "recover-c",
                        "job-r",
                        "sha256:r",
                        Duration.ofMinutes(1),
                        source));
    assertEquals(next.attemptId(), refused.holder(), "a COMPLETED recovery keeps its claim");
  }

  private static CandidateAttemptStore.Attempt failedRecovery(String source) {
    CandidateAttemptStore store = owner();
    var attempt =
        store
            .reserveRecovery(
                "proposal:p1",
                "failed-recovery",
                "job-failed",
                "sha256:f",
                Duration.ofMinutes(1),
                source)
            .attempt();
    return store.finish(
        attempt.attemptId(),
        attempt.leaseId(),
        attempt.epoch(),
        CandidateAttemptStore.State.FAILED,
        Map.of("outcome", "FAILED"),
        "journal publication failed");
  }

  private static CandidateAttemptStore.Reservation recover(String source, String key) {
    return owner()
        .reserveRecovery(
            "proposal:p1", key, "job-" + key, "sha256:r", Duration.ofMinutes(1), source);
  }

  @Test
  void aDescendantOfAFailedRecoveryBlocksAncestorRetriesWhileRunningAndAfterCompletion() {
    String source = uncertainSource();
    var failed = failedRecovery(source);
    var child = recover(failed.attemptId(), "child").attempt();
    owner().markRunning(child.attemptId(), child.leaseId(), child.epoch(), "sha256:request");
    assertEquals(
        child.attemptId(),
        assertThrows(
                CandidateAttemptStore.RecoveryClaimedException.class,
                () -> recover(source, "ancestor-retry"))
            .holder());
    owner()
        .finish(
            child.attemptId(),
            child.leaseId(),
            child.epoch(),
            CandidateAttemptStore.State.COMPLETED,
            Map.of("outcome", "PASS"),
            "done");
    assertEquals(
        child.attemptId(),
        assertThrows(
                CandidateAttemptStore.RecoveryClaimedException.class,
                () -> recover(source, "ancestor-after-completion"))
            .holder());
    assertFalse(
        recover(failed.attemptId(), "child").created(),
        "the completed child's key still reattaches");
  }

  @Test
  void anAncestorRetryPreventsContinuingAnOlderFailedBranch() {
    String source = uncertainSource();
    var failed = failedRecovery(source);
    var retry = recover(source, "ancestor-retry").attempt();
    assertEquals(
        retry.attemptId(),
        assertThrows(
                CandidateAttemptStore.RecoveryClaimedException.class,
                () -> recover(failed.attemptId(), "child"))
            .holder());
  }

  @Test
  void simultaneousAncestorAndDescendantRecoveriesYieldExactlyOne() throws Exception {
    String source = uncertainSource();
    String failed = failedRecovery(source).attemptId();
    ExecutorService pool = Executors.newFixedThreadPool(12);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 12; i++) {
        String parent = i % 2 == 0 ? source : failed;
        String key = "lineage-race-" + i;
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    return recover(parent, key);
                  } catch (CandidateAttemptStore.RecoveryClaimedException e) {
                    return e;
                  }
                }));
      }
      go.countDown();
      String winner = null;
      int refused = 0;
      for (Future<Object> result : results) {
        Object value = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (value instanceof CandidateAttemptStore.Reservation reservation) {
          assertTrue(reservation.created());
          assertNull(winner, "only one continuation across both source attempts");
          winner = reservation.attempt().attemptId();
        } else {
          refused++;
        }
      }
      assertEquals(11, refused);
      for (Future<Object> result : results) {
        if (result.get() instanceof CandidateAttemptStore.RecoveryClaimedException e) {
          assertEquals(winner, e.holder());
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anUncertainRecoveryCanContinueButItsAncestorsCannotFork() {
    String source = uncertainSource();
    var first = recover(source, "uncertain-recovery").attempt();
    owner()
        .finish(
            first.attemptId(),
            first.leaseId(),
            first.epoch(),
            CandidateAttemptStore.State.UNCERTAIN,
            Map.of("outcome", "UNCERTAIN"),
            "runner lost");
    var continuation = recover(first.attemptId(), "continuation");
    assertTrue(continuation.created(), "an uncertain ancestor must not block its continuation");
    assertThrows(
        CandidateAttemptStore.RecoveryClaimedException.class,
        () -> recover(source, "ancestor-retry"));
    assertFalse(recover(first.attemptId(), "continuation").created());
  }

  @Test
  void keysArePerOwner() {
    var a = owner().reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1));
    var b = owner().reserve("proposal:p2", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1));
    assertTrue(a.created());
    assertTrue(b.created());
    assertFalse(a.attempt().attemptId().equals(b.attempt().attemptId()));
  }

  @Test
  void aColdRestartedOwnerReconcilesToUncertainAndTheOldOwnerIsFencedAtEqualLease() {
    CandidateAttemptStore first = owner();
    var held =
        first
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMillis(200))
            .attempt();
    first.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:request");

    // The owner process dies; after its lease expires a fresh owner starts and reconciles.
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        held.attemptId());
    CandidateAttemptStore restarted = owner();
    assertEquals(1, restarted.reconcileExpired(Instant.now()));
    var reconciled = restarted.find(held.attemptId()).orElseThrow();
    assertEquals(CandidateAttemptStore.State.UNCERTAIN, reconciled.state());
    assertEquals(2L, reconciled.epoch());
    assertNull(reconciled.leaseId());

    // The old owner comes back holding the same lease id: every write is refused.
    assertFalse(first.renew(held.attemptId(), held.leaseId(), held.epoch(), Duration.ofMinutes(1)));
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            first.finish(
                held.attemptId(),
                held.leaseId(),
                held.epoch(),
                CandidateAttemptStore.State.COMPLETED,
                Map.of("outcome", "PASS"),
                "late"));
    var after = restarted.find(held.attemptId()).orElseThrow();
    assertEquals(CandidateAttemptStore.State.UNCERTAIN, after.state());
    assertNull(after.job());
  }

  @Test
  void controlsAreDurableAcrossOwnersAndOnlyTheHolderRecordsAPause() {
    CandidateAttemptStore holder = owner();
    var held =
        holder
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1))
            .attempt();
    assertEquals(
        held.attemptId(), owner().findByKey("proposal:p1", "key-1").orElseThrow().attemptId());
    assertTrue(owner().findByKey("proposal:p2", "key-1").isEmpty(), "keys are per owner");

    // Another process records the requests; the holder reads them from the row.
    CandidateAttemptStore elsewhere = owner();
    assertTrue(elsewhere.requestPause(held.attemptId(), true));
    assertEquals(
        new CandidateAttemptStore.Control(false, true, false), holder.control(held.attemptId()));
    holder.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:request");
    holder.recordPaused(held.attemptId(), held.leaseId(), held.epoch(), true);
    assertTrue(elsewhere.control(held.attemptId()).paused());
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () -> elsewhere.recordPaused(held.attemptId(), "lease-other", held.epoch(), false),
        "only the lease holder reports what the runner did");
    assertTrue(elsewhere.requestCancel(held.attemptId()));
    assertTrue(holder.control(held.attemptId()).cancelRequested());

    // Finishing releases the observed pause; a finished attempt takes no more requests.
    holder.finish(
        held.attemptId(),
        held.leaseId(),
        held.epoch(),
        CandidateAttemptStore.State.CANCELLED,
        Map.of("outcome", "CANCELLED"),
        "cancelled");
    assertEquals(
        new CandidateAttemptStore.Control(true, true, false), elsewhere.control(held.attemptId()));
    assertFalse(elsewhere.requestCancel(held.attemptId()));
    assertFalse(elsewhere.requestPause(held.attemptId(), false));
    assertEquals(CandidateAttemptStore.Control.NONE, elsewhere.control("attempt-missing"));
  }

  @Test
  void onlyTheHolderAcceptsEachNextBindingGenerationOfItsOwnRequest() {
    CandidateAttemptStore holder = owner();
    var held =
        holder
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1))
            .attempt();
    String id = held.attemptId();
    assertThrows(
        CandidateAttemptStore.BindingRejectedException.class,
        () ->
            holder.recordBinding(
                id, held.leaseId(), held.epoch(), binding(id, 1, "sha256:request")),
        "a reserved attempt has not launched and cannot be bound");
    holder.markRunning(id, held.leaseId(), held.epoch(), "sha256:request");
    assertTrue(owner().currentBinding(id).isEmpty(), "unbound until the owner accepts one");

    holder.recordBinding(id, held.leaseId(), held.epoch(), binding(id, 1, "sha256:request"));
    for (CandidateAttemptStore.WorkerBinding refused :
        List.of(
            binding(id, 1, "sha256:request"),
            binding(id, 3, "sha256:request"),
            binding(id, 2, "sha256:other"),
            binding("attempt-foreign", 2, "sha256:request"))) {
      assertThrows(
          CandidateAttemptStore.BindingRejectedException.class,
          () -> holder.recordBinding(id, held.leaseId(), held.epoch(), refused));
    }
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            owner()
                .recordBinding(id, "lease-other", held.epoch(), binding(id, 2, "sha256:request")));
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            owner()
                .recordBinding(
                    id, held.leaseId(), held.epoch() + 1, binding(id, 2, "sha256:request")),
        "an equal lease under another epoch is still stale");
    assertEquals(1L, owner().currentBinding(id).orElseThrow().generation());

    holder.recordBinding(id, held.leaseId(), held.epoch(), binding(id, 2, "sha256:request"));
    CandidateAttemptStore.WorkerBinding current = owner().currentBinding(id).orElseThrow();
    assertEquals(2L, current.generation());
    assertEquals("RESTORE", current.reason());
    assertEquals(Map.of("workerCertifiable", false), current.workerIdentity());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM candidate_attempt_worker_bindings WHERE attempt_id = ?",
            Integer.class,
            id));
  }

  @Test
  void aReconciledOwnerCanNoLongerBindItsAttempt() {
    CandidateAttemptStore holder = owner();
    var held =
        holder
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMillis(200))
            .attempt();
    String id = held.attemptId();
    holder.markRunning(id, held.leaseId(), held.epoch(), "sha256:request");
    holder.recordBinding(id, held.leaseId(), held.epoch(), binding(id, 1, "sha256:request"));
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        id);
    assertEquals(1, owner().reconcileExpired(Instant.now()));

    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            holder.recordBinding(
                id, held.leaseId(), held.epoch(), binding(id, 2, "sha256:request")),
        "a cold-restarted owner cannot bind one more worker generation");
    assertEquals(1L, owner().currentBinding(id).orElseThrow().generation());
  }

  @Test
  void competingRecoveriesOfABoundAttemptYieldOneRecoveryWithItsOwnBindings() throws Exception {
    CandidateAttemptStore crashed = owner();
    var held =
        crashed
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMillis(200))
            .attempt();
    String source = held.attemptId();
    crashed.markRunning(source, held.leaseId(), held.epoch(), "sha256:request");
    for (long generation = 1; generation <= 2; generation++) {
      crashed.recordBinding(
          source, held.leaseId(), held.epoch(), binding(source, generation, "sha256:request"));
    }
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        source);
    assertEquals(1, owner().reconcileExpired(Instant.now()));

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String key = "recover-" + i;
      results.add(
          pool.submit(
              () -> {
                go.await();
                try {
                  return owner()
                      .reserveRecovery(
                          "proposal:p1", key, "job-r", "sha256:r", Duration.ofMinutes(1), source);
                } catch (CandidateAttemptStore.RecoveryClaimedException e) {
                  return e;
                }
              }));
    }
    go.countDown();
    List<CandidateAttemptStore.Reservation> won = new ArrayList<>();
    for (Future<Object> f : results) {
      if (f.get() instanceof CandidateAttemptStore.Reservation reservation) {
        won.add(reservation);
      }
    }
    pool.shutdownNow();
    assertEquals(1, won.size(), "only one recovery of a bound attempt is reserved");

    // The source keeps its last accepted binding; neither the crashed owner nor anyone else can
    // advance it once it is uncertain.
    assertEquals(2L, owner().currentBinding(source).orElseThrow().generation());
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            crashed.recordBinding(
                source, held.leaseId(), held.epoch(), binding(source, 3, "sha256:request")));

    // The recovery is a new attempt: its bindings start again at generation 1 under its own
    // request, and a binding naming the source attempt is refused.
    var recovery = won.get(0).attempt();
    CandidateAttemptStore winner = owner();
    winner.markRunning(
        recovery.attemptId(), recovery.leaseId(), recovery.epoch(), "sha256:recovery");
    assertTrue(owner().currentBinding(recovery.attemptId()).isEmpty());
    assertThrows(
        CandidateAttemptStore.BindingRejectedException.class,
        () ->
            winner.recordBinding(
                recovery.attemptId(),
                recovery.leaseId(),
                recovery.epoch(),
                binding(source, 1, "sha256:recovery")));
    assertThrows(
        CandidateAttemptStore.BindingRejectedException.class,
        () ->
            winner.recordBinding(
                recovery.attemptId(),
                recovery.leaseId(),
                recovery.epoch(),
                binding(recovery.attemptId(), 3, "sha256:recovery")),
        "generations do not continue from the source attempt");
    winner.recordBinding(
        recovery.attemptId(),
        recovery.leaseId(),
        recovery.epoch(),
        binding(recovery.attemptId(), 1, "sha256:recovery"));
    assertEquals(1L, owner().currentBinding(recovery.attemptId()).orElseThrow().generation());
    assertEquals(2L, owner().currentBinding(source).orElseThrow().generation());
  }

  @Test
  void losingTheLeaseWhilePausedFencesTheOldOwnerAndTheRecoveryStartsPaused() {
    CandidateAttemptStore holder = owner();
    var held =
        holder
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMillis(200))
            .attempt();
    String id = held.attemptId();
    holder.markRunning(id, held.leaseId(), held.epoch(), "sha256:request");
    holder.recordBinding(id, held.leaseId(), held.epoch(), binding(id, 1, "sha256:request"));
    assertTrue(owner().requestPause(id, true));
    holder.recordPaused(id, held.leaseId(), held.epoch(), true);
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        id);
    assertEquals(1, owner().reconcileExpired(Instant.now()));
    assertEquals(
        CandidateAttemptStore.State.UNCERTAIN, owner().find(id).orElseThrow().state());

    // The parked owner wakes after losing its lease: it can neither report a resume, renew, nor
    // bind a restored worker.
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () -> holder.recordPaused(id, held.leaseId(), held.epoch(), false));
    assertFalse(holder.renew(id, held.leaseId(), held.epoch(), Duration.ofMinutes(1)));
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            holder.recordBinding(
                id, held.leaseId(), held.epoch(), binding(id, 2, "sha256:request")));
    assertEquals(1L, owner().currentBinding(id).orElseThrow().generation());
    assertFalse(owner().control(id).paused(), "nothing is parked once the owner is gone");

    // The requested pause survives on the source, where CandidateExecutionJobService reads it to
    // park a new recovery; the store does not copy it onto the recovery row itself.
    var recovery =
        owner()
            .reserveRecovery(
                "proposal:p1", "recover-1", "job-r", "sha256:r", Duration.ofMinutes(1), id)
            .attempt();
    assertTrue(owner().control(id).pauseRequested());
    assertFalse(owner().control(recovery.attemptId()).pauseRequested());
  }

  private static CandidateAttemptStore.WorkerBinding binding(
      String attemptId, long generation, String requestDigest) {
    return new CandidateAttemptStore.WorkerBinding(
        UUID.randomUUID().toString(),
        generation,
        attemptId,
        requestDigest,
        "candidate-stdio/v1",
        "sc2106-" + attemptId,
        generation + 2,
        "sha256:" + "b".repeat(63) + generation,
        7L,
        0L,
        0L,
        generation == 1 ? "CREATE" : "RESTORE",
        Map.of("workerCertifiable", false));
  }

  @Test
  void reconciliationKeepsTheRequestedPauseForTheRecovery() {
    CandidateAttemptStore holder = owner();
    var held =
        holder
            .reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMillis(200))
            .attempt();
    holder.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:request");
    holder.requestPause(held.attemptId(), true);
    holder.recordPaused(held.attemptId(), held.leaseId(), held.epoch(), true);
    jdbc.update(
        "UPDATE candidate_execution_attempts SET lease_expires_at = now() - interval '1 second'"
            + " WHERE attempt_id = ?",
        held.attemptId());

    assertEquals(1, owner().reconcileExpired(Instant.now()));
    var control = owner().control(held.attemptId());
    assertTrue(control.pauseRequested(), "a recovery of this attempt must start paused");
    assertFalse(control.paused(), "nothing is parked once the owner is gone");
  }

  @Test
  void anOldOwnerRetryReattachesInsteadOfReservingAgain() {
    CandidateAttemptStore first = owner();
    var held =
        first.reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1)).attempt();
    first.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:request");
    first.finish(
        held.attemptId(),
        held.leaseId(),
        held.epoch(),
        CandidateAttemptStore.State.COMPLETED,
        Map.of("outcome", "PASS", "attemptId", held.attemptId()),
        "done");

    var retry = owner().reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1));
    assertFalse(retry.created());
    assertEquals(held.attemptId(), retry.attempt().attemptId());
    assertEquals(CandidateAttemptStore.State.COMPLETED, retry.attempt().state());
    assertEquals("PASS", retry.attempt().job().get("outcome"));
    assertEquals("sha256:request", retry.attempt().requestDigest());
    // A finished attempt can never be finished again, even by its own former lease.
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () ->
            first.finish(
                held.attemptId(),
                held.leaseId(),
                held.epoch(),
                CandidateAttemptStore.State.FAILED,
                null,
                "again"));
  }

  @Test
  void runningRequiresTheReservedHolderAndRenewalExtendsOnlyOpenAttempts() {
    CandidateAttemptStore store = owner();
    var held =
        store.reserve("proposal:p1", "key-1", "job-1", "sha256:f", Duration.ofMinutes(1)).attempt();
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () -> store.markRunning(held.attemptId(), "lease-forged", held.epoch(), "sha256:r"));
    assertThrows(
        CandidateAttemptStore.StaleOwnerException.class,
        () -> store.markRunning(held.attemptId(), held.leaseId(), held.epoch() + 1, "sha256:r"));
    Instant before = store.find(held.attemptId()).orElseThrow().leaseExpiresAt();
    assertTrue(store.renew(held.attemptId(), held.leaseId(), held.epoch(), Duration.ofMinutes(10)));
    assertTrue(store.find(held.attemptId()).orElseThrow().leaseExpiresAt().isAfter(before));
    var running = store.markRunning(held.attemptId(), held.leaseId(), held.epoch(), "sha256:r");
    assertEquals(CandidateAttemptStore.State.RUNNING, running.state());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.finish(
                held.attemptId(),
                held.leaseId(),
                held.epoch(),
                CandidateAttemptStore.State.RUNNING,
                null,
                null));
  }

  @Test
  void reconciliationLeavesLiveAndFinishedAttemptsAlone() {
    CandidateAttemptStore store = owner();
    var live =
        store.reserve("proposal:p1", "live", "job-1", "sha256:f", Duration.ofMinutes(5)).attempt();
    var done =
        store.reserve("proposal:p1", "done", "job-2", "sha256:f", Duration.ofMinutes(5)).attempt();
    store.finish(
        done.attemptId(),
        done.leaseId(),
        done.epoch(),
        CandidateAttemptStore.State.FAILED,
        null,
        "x");
    assertEquals(0, store.reconcileExpired(Instant.now()));
    assertEquals(
        CandidateAttemptStore.State.RESERVED, store.find(live.attemptId()).orElseThrow().state());
    assertEquals(
        CandidateAttemptStore.State.FAILED, store.find(done.attemptId()).orElseThrow().state());
  }
}
