package com.chromascape.web.replay.session;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable PostgreSQL only; does not launch a worker or touch the gameplay database. */
@EnabledIfEnvironmentVariable(named = "SC1991_TEST_JDBC_URL", matches = ".+")
class XrspsSessionRepositoryIntegrationTest {
  private static JdbcTemplate jdbc;
  private static XrspsSessionRepository repository;
  private static String schema;

  @BeforeAll
  static void connect() throws Exception {
    String url = System.getenv("SC1991_TEST_JDBC_URL");
    schema = "sc1991_owner_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(new DriverManagerDataSource(url, "test", "test"))
        .execute("CREATE SCHEMA " + schema);
    var ds =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "test", "test");
    jdbc = new JdbcTemplate(ds);
    for (String migration :
        new String[] {"V66__sc1991_durable_sessions.sql", "V67__sc1991_session_ownership.sql"}) {
      try (var input =
          XrspsSessionRepositoryIntegrationTest.class.getResourceAsStream(
              "/db/migration/" + migration)) {
        assertNotNull(input);
        jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    repository =
        new XrspsSessionRepository(
            jdbc,
            new ObjectMapper().findAndRegisterModules(),
            new DataSourceTransactionManager(ds));
  }

  @AfterAll
  static void clean() {
    if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE xrsps_sessions");
  }

  private XrspsSessionDocument initialized(XrspsSession session) {
    session.markReady();
    var doc = new XrspsSessionDocument();
    doc.lifecycle = session.snapshot();
    return doc;
  }

  @Test
  void retryKeysArePerOwnerAndOwnershipSurvivesSerialization() {
    var alice =
        repository.create(
            XrspsSessionMode.RECORDED_ACTIONS,
            "owner-shared-key",
            "scenario",
            "alice",
            this::initialized);
    var bob =
        repository.create(
            XrspsSessionMode.RECORDED_ACTIONS,
            "owner-shared-key",
            "scenario",
            "bob",
            this::initialized);
    assertNotEquals(alice.session().getId(), bob.session().getId());
    var retry =
        repository.create(
            XrspsSessionMode.RECORDED_ACTIONS,
            "owner-shared-key",
            "scenario",
            "alice",
            s -> {
              throw new AssertionError("Retry must not initialize another world");
            });
    assertFalse(retry.created());
    assertEquals(alice.session().getId(), retry.session().getId());
    assertEquals(
        "alice", repository.getOwned(alice.session().getId(), "alice").lifecycle.ownerId());
    assertThrows(
        ReplayException.class,
        () ->
            repository.create(
                XrspsSessionMode.RECORDED_ACTIONS,
                "owner-shared-key",
                "changed",
                "alice",
                this::initialized));
  }

  @Test
  void foreignMissingAndUnownedLegacyRowsAreUniformlyHidden() {
    var alice =
        repository.create(
            XrspsSessionMode.RECORDED_ACTIONS,
            "owner-private-key",
            "scenario",
            "alice",
            this::initialized);
    var legacy = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO xrsps_sessions(id,idempotency_key,document) VALUES (?,?,'{}'::jsonb)",
        legacy,
        "legacy-key");
    var foreign =
        assertThrows(
            ReplayException.class, () -> repository.getOwned(alice.session().getId(), "bob"));
    var missing =
        assertThrows(ReplayException.class, () -> repository.getOwned(UUID.randomUUID(), "bob"));
    var unowned = assertThrows(ReplayException.class, () -> repository.getOwned(legacy, "bob"));
    assertEquals(missing.getErrorCode(), foreign.getErrorCode());
    assertEquals(missing.getMessage(), foreign.getMessage());
    assertEquals(missing.getMessage(), unowned.getMessage());
    assertThrows(
        ReplayException.class,
        () ->
            repository.create(
                XrspsSessionMode.RECORDED_ACTIONS,
                "owner-missing-key",
                "scenario",
                null,
                this::initialized));
  }

  @Test
  void concurrentRetriesInitializeOnce() throws Exception {
    var pool = Executors.newFixedThreadPool(8);
    var initialized = new AtomicInteger();
    try {
      var jobs = new java.util.ArrayList<Future<XrspsSessionRepository.Creation>>();
      for (int i = 0; i < 8; i++)
        jobs.add(
            pool.submit(
                () ->
                    repository.create(
                        XrspsSessionMode.RECORDED_ACTIONS,
                        "owner-concurrent-key",
                        "scenario",
                        "alice",
                        s -> {
                          initialized.incrementAndGet();
                          return initialized(s);
                        })));
      UUID first = jobs.get(0).get(15, TimeUnit.SECONDS).session().getId();
      int created = 0;
      for (var job : jobs) {
        var result = job.get(15, TimeUnit.SECONDS);
        assertEquals(first, result.session().getId());
        if (result.created()) created++;
      }
      assertEquals(1, created);
      assertEquals(1, initialized.get());
    } finally {
      pool.shutdownNow();
    }
  }
}
