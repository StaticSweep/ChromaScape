package com.chromascape.web.aiplayer.routine.service;

import static com.chromascape.web.aiplayer.routine.service.Sc2106RealHost.PROPOSAL;
import static com.chromascape.web.aiplayer.routine.service.Sc2106RealHost.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.aiplayer.routine.config.Sc2106ExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SC-2106 R6.2 owner crash on the real path, run only by {@code :sc2106OwnerCrashTest}.
 *
 * <p>A ChromaScape owner in its own JVM ({@link Sc2106OwnerProcess}) reserves an attempt in
 * PostgreSQL and runs it through the real host, runner and XRSPS worker. The runner is parked right
 * after its first durable cut, and the owner JVM is SIGKILLed. Then:
 *
 * <ul>
 *   <li>the host, runner and worker do not outlive their owner (stdin closes, the run cancels);
 *   <li>the attempt is not reported as anything: it stays open until its lease runs out, and a
 *       cold-started owner reconciles it to UNCERTAIN with a new epoch, never relaunching it;
 *   <li>an explicit recovery resumes from the last resumable cut in a fresh worker (the cut taken
 *       after the cancel is skipped) and ends exactly like an uninterrupted reference run.
 * </ul>
 */
class CandidateOwnerCrashIntegrationTest {
  /** 83 XP: a 48-step run whose first cut (every 25 steps) is mid-run. */
  private static final int START_XP = 83;

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @TempDir static Path temp;

  private static String url;
  private static String user;
  private static String password;
  private static String schema;
  private static JdbcTemplate admin;
  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void prepare() throws Exception {
    Sc2106RealHost.prepare(temp);
    url = System.getenv("SC2106_TEST_JDBC_URL");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("SC2106_TEST_JDBC_URL is required for the owner-crash test");
    }
    user = System.getenv().getOrDefault("SC2106_TEST_JDBC_USER", "test");
    password = System.getenv().getOrDefault("SC2106_TEST_JDBC_PASSWORD", "test");
    schema = "sc2106_owner_crash_" + UUID.randomUUID().toString().replace("-", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("CREATE SCHEMA " + schema);
    url = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    dataSource = new DriverManagerDataSource(url, user, password);
    jdbc = new JdbcTemplate(dataSource);
    for (String migration :
        List.of(
            "V71__sc2106_candidate_execution_attempts.sql",
            "V73__sc2106_one_recovery_per_attempt.sql",
            "V74__sc2106_attempt_controls.sql")) {
      try (var input =
          CandidateOwnerCrashIntegrationTest.class.getResourceAsStream(
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

  private static JdbcCandidateAttemptStore store() {
    return new JdbcCandidateAttemptStore(
        jdbc, MAPPER, new DataSourceTransactionManager(dataSource));
  }

  @Test
  void anOwnerKilledMidRunIsReconciledUncertainAndRecoversLikeTheReference() throws Exception {
    Path policy = Sc2106RealHost.policy(temp, "trusted", Sc2106RealHost.keys.getPublic());

    // 1. The uninterrupted reference.
    CandidateExecutionJobService.ExecutionJob reference =
        new CandidateExecutionJobService(
                Sc2106RealHost.workflow(),
                Sc2106RealHost.properties(temp.resolve("reference"), policy),
                new InMemoryCandidateAttemptStore())
            .execute(PROPOSAL, Sc2106RealHost.spec("job-reference", START_XP));
    assertEquals(
        CandidateExecutionResultValidator.Outcome.PASS,
        reference.outcome(),
        reference.reasons() + "");

    // 2. The owner in its own JVM, with a short lease so reconciliation need not wait minutes.
    Path root = temp.resolve("owned");
    Sc2106ExecutionProperties owned = Sc2106RealHost.properties(root, policy);
    owned.setHostTimeout(Duration.ofSeconds(20));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("jdbcUrl", url);
    config.put("user", user);
    config.put("password", password);
    config.put("bundle", Sc2106RealHost.bundle.toString());
    config.put("jarName", Sc2106RealHost.jarName);
    config.put("sbomName", Sc2106RealHost.sbomName);
    config.put("properties", owned);
    config.put("spec", Sc2106RealHost.spec("job-owned", START_XP));
    config.put("fault", Map.of("point", "PARK_AFTER_CHECKPOINT", "at", 1));
    Path configFile = temp.resolve("owner.json");
    MAPPER.writeValue(configFile.toFile(), config);
    Path log = temp.resolve("owner.log");
    Process owner =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                Sc2106RealHost.required("sc2106.test.classpath"),
                Sc2106OwnerProcess.class.getName(),
                configFile.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();

    // 3. Wait until the runner is parked after its first durable cut, then SIGKILL the owner.
    String attemptId = null;
    long deadline = System.currentTimeMillis() + 120_000L;
    while (true) {
      assertTrue(owner.isAlive(), "the owner died on its own: " + Files.readString(log));
      assertTrue(System.currentTimeMillis() < deadline, "no parked run: " + Files.readString(log));
      if (attemptId == null) {
        List<String> ids =
            jdbc.queryForList(
                "SELECT attempt_id FROM candidate_execution_attempts WHERE idempotency_key = ?"
                    + " AND state = 'RUNNING'",
                String.class,
                "key-job-owned");
        attemptId = ids.isEmpty() ? null : ids.get(0);
      }
      if (attemptId != null && cuts(root, attemptId).size() >= 1) {
        break;
      }
      Thread.sleep(100L);
    }
    List<ProcessHandle> descendants = owner.descendants().collect(Collectors.toList());
    assertTrue(
        descendants.size() >= 3,
        "host, runner and worker run under the owner: " + describe(descendants));
    owner.toHandle().destroyForcibly();
    assertTrue(owner.waitFor(10, TimeUnit.SECONDS));

    // 4. Nothing the owner started survives it: the host sees stdin close and cancels the run.
    long reaped = System.currentTimeMillis() + 60_000L;
    for (ProcessHandle child : descendants) {
      while (child.isAlive()) {
        assertTrue(
            System.currentTimeMillis() < reaped, "outlived its owner: " + describe(descendants));
        Thread.sleep(100L);
      }
    }
    assertFalse(Files.readString(log).contains("SC2106_OWNER_FINISHED"));

    // 5. The attempt is not reported as anything: it is still open under the dead owner's lease.
    JdbcCandidateAttemptStore store = store();
    CandidateAttemptStore.Attempt open = store.find(attemptId).orElseThrow();
    assertEquals(CandidateAttemptStore.State.RUNNING, open.state());
    assertNull(open.job());
    while (Instant.now().isBefore(open.leaseExpiresAt().plusSeconds(1))) {
      Thread.sleep(250L);
    }

    // 6. A cold-started owner reconciles it to UNCERTAIN under a new epoch and relaunches nothing.
    CandidateExecutionJobService restarted =
        new CandidateExecutionJobService(
            Sc2106RealHost.workflow(), Sc2106RealHost.properties(root, policy), store);
    restarted.reconcileAbandonedAttempts();
    CandidateAttemptStore.Attempt reconciled = store.find(attemptId).orElseThrow();
    assertEquals(CandidateAttemptStore.State.UNCERTAIN, reconciled.state());
    assertEquals(open.epoch() + 1, reconciled.epoch());
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM candidate_execution_attempts", Integer.class),
        "nothing was relaunched");

    // The cancel the dying owner caused left a stopped cut after the parked one.
    List<Map<String, Object>> cuts = cuts(root, attemptId);
    assertTrue(
        cuts.stream().anyMatch(cut -> Boolean.TRUE.equals(map(cut.get("runner")).get("stopped"))),
        "the cancel is recorded as a stopped cut");

    // 7. An explicit recovery resumes from the parked cut and ends like the reference.
    CandidateExecutionJobService.ExecutionJob recovered =
        restarted.recover(PROPOSAL, attemptId, Sc2106RealHost.spec("job-owned-recovery", START_XP));
    assertEquals(
        CandidateExecutionResultValidator.Outcome.PASS,
        recovered.outcome(),
        recovered.reasons() + "\n" + recovered.hostOutcome());
    Map<String, Object> from =
        map(map(recovered.result().get("terminalState")).get("recoveredFrom"));
    assertEquals(attemptId, from.get("attemptId"));
    assertEquals(0, ((Number) from.get("checkpointIndex")).intValue());
    for (String field : List.of("goalReached", "finalFishingLevel", "finalInventory")) {
      assertEquals(
          map(reference.result().get("postconditions")).get(field),
          map(recovered.result().get("postconditions")).get(field),
          field);
    }
    Map<String, Object> referenceState = map(reference.result().get("terminalState"));
    Map<String, Object> recoveredState = map(recovered.result().get("terminalState"));
    assertEquals(referenceState.get("task"), recoveredState.get("task"), "task state");
    assertEquals(referenceState.get("logicalNowMillis"), recoveredState.get("logicalNowMillis"));
    for (String budget : List.of("consumedSteps", "consumedTicks", "consumedIntents")) {
      assertEquals(reference.result().get(budget), recovered.result().get(budget), budget);
    }
    assertEquals(
        CandidateAttemptStore.State.COMPLETED,
        store.find(recovered.attemptId()).orElseThrow().state());
  }

  private static List<Map<String, Object>> cuts(Path root, String attemptId) {
    List<Map<String, Object>> cuts = new ArrayList<>();
    for (Map<String, Object> event :
        Sc2106RealHost.journalEvents(
            root.resolve("runs").resolve(attemptId).resolve("journal"), attemptId)) {
      if ("CHECKPOINT".equals(event.get("type"))) {
        cuts.add(event);
      }
    }
    return cuts;
  }

  private static String describe(List<ProcessHandle> processes) {
    return processes.stream()
        .map(p -> p.pid() + ":" + p.info().command().orElse("?") + (p.isAlive() ? "" : "(dead)"))
        .collect(Collectors.joining(", "));
  }
}
