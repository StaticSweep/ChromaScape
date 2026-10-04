package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a dedicated disposable PostgreSQL database, never the application database. */
class BuilderGoalStartRequestPostgresTest {
  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static BuilderGoalStartRequestService service;

  @BeforeAll
  static void schema() throws Exception {
    String url = System.getProperty("sc666.test.jdbc.url");
    assertNotNull(url, "Run sc666CompletionTest with a disposable sc666TestJdbcUrl");
    assertTrue(url.endsWith("/sc666"), "Only a disposable sc666 test database is supported");
    dataSource = new DriverManagerDataSource(url, "postgres", "sc666-test-only");
    jdbc = new JdbcTemplate(dataSource);
    tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    service = new BuilderGoalStartRequestService(jdbc);
    jdbc.execute("DROP TABLE IF EXISTS builder_goal_start_request");
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS patch_loop_iteration (iteration_id VARCHAR(100) UNIQUE NOT NULL)");
    try (var stream =
        BuilderGoalStartRequestPostgresTest.class.getResourceAsStream(
            "/db/migration/V65__sc666_durable_start_requests.sql")) {
      assertNotNull(stream);
      jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM builder_goal_start_request");
    jdbc.update("DELETE FROM patch_loop_iteration");
  }

  private void bind(
      BuilderGoalStartRequestService instance, String key, String fingerprint, String run) {
    assertNull(instance.reserve(key, fingerprint));
    jdbc.update("INSERT INTO patch_loop_iteration (iteration_id) VALUES (?)", run);
    instance.bind(key, run);
  }

  @Test
  void competingInstancesWaitForCommitAndReuseExactlyOneRun() throws Exception {
    var second = new BuilderGoalStartRequestService(jdbc);
    var reserved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var contenderStarted = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> winner =
          pool.submit(
              () ->
                  tx.execute(
                      status -> {
                        bind(service, "request", "digest", "run-one");
                        reserved.countDown();
                        try {
                          assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return null;
                      }));
      assertTrue(reserved.await(10, TimeUnit.SECONDS));
      Future<String> contender =
          pool.submit(
              () -> {
                contenderStarted.countDown();
                return tx.execute(status -> second.reserve("request", "digest"));
              });
      assertTrue(contenderStarted.await(10, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> contender.get(200, TimeUnit.MILLISECONDS));
      release.countDown();
      winner.get(10, TimeUnit.SECONDS);
      assertEquals("run-one", contender.get(10, TimeUnit.SECONDS));
      assertEquals(
          1, jdbc.queryForObject("SELECT count(*) FROM patch_loop_iteration", Integer.class));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void rollbackLeavesNoRequestOrRunAndRetryCanCreate() {
    tx.execute(
        status -> {
          bind(service, "request", "digest", "rolled-back");
          status.setRollbackOnly();
          return null;
        });
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM builder_goal_start_request", Integer.class));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM patch_loop_iteration", Integer.class));
    tx.execute(
        status -> {
          bind(service, "request", "digest", "retry");
          return null;
        });
    assertEquals("retry", tx.execute(status -> service.reserve("request", "digest")));
  }

  @Test
  void lostResponseAndNewServiceInstanceResolveOriginalCommittedAttempt() {
    tx.execute(
        status -> {
          bind(service, "request", "digest", "original");
          return null;
        });
    // The application/process identity is absent from the lookup; a new instance needs only DB
    // state.
    var reopenedDataSource =
        new DriverManagerDataSource(dataSource.getUrl(), "postgres", "sc666-test-only");
    var reopened = new BuilderGoalStartRequestService(new JdbcTemplate(reopenedDataSource));
    var reopenedTx = new TransactionTemplate(new JdbcTransactionManager(reopenedDataSource));
    assertEquals("original", reopenedTx.execute(status -> reopened.reserve("request", "digest")));
    var conflict =
        assertThrows(
            BuilderGoalStartException.class,
            () -> reopenedTx.execute(status -> reopened.reserve("request", "changed")));
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, conflict.getStatus());
    assertEquals("REQUEST_IDENTITY_CONFLICT", conflict.getCode());
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM patch_loop_iteration", Integer.class));
  }

  @Test
  void invalidIdsAndUnboundForeignKeysFailWithoutCommittedClaims() {
    assertThrows(IllegalStateException.class, () -> service.reserve("request", "digest"));
    assertThrows(
        BuilderGoalStartException.class,
        () -> tx.execute(status -> service.reserve("x".repeat(257), "digest")));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            tx.execute(
                status -> {
                  service.reserve("request", "digest");
                  service.bind("request", "missing-run");
                  return null;
                }));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM builder_goal_start_request", Integer.class));
  }

  @Test
  void canonicalFingerprintsIgnoreObjectOrderAndNumericEncodingButBindScope() {
    var mapper = new ObjectMapper();
    var first =
        BuilderGoalStartRequestService.fingerprint(
            mapper, Map.of("settings", Map.of("a", 1, "b", 2), "account", "a"));
    var retry =
        BuilderGoalStartRequestService.fingerprint(
            mapper, Map.of("account", "a", "settings", Map.of("b", 2.0, "a", 1.0)));
    assertEquals(first, retry);
    assertNotEquals(
        first,
        BuilderGoalStartRequestService.fingerprint(
            mapper, Map.of("account", "b", "settings", Map.of("a", 1, "b", 2))));
  }
}
