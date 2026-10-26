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
 * SC-2106 R4 against a real PostgreSQL (disposable schema; the V71 migration as shipped). Run
 * through {@code sc2106ManagedAttemptTest}, which fails instead of skipping without a database.
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
    try (var input =
        CandidateAttemptStoreIntegrationTest.class.getResourceAsStream(
            "/db/migration/V71__sc2106_candidate_execution_attempts.sql")) {
      jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
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
    jdbc.execute("TRUNCATE candidate_execution_attempts");
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
