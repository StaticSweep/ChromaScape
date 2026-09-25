package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SC-810 concurrent-persistence proof against the real test PostgreSQL datasource and JPA stack:
 * two threads racing the same immutable manifest identity through the orchestration retry must
 * converge on one durable row with identical canonical bytes; identity reuse with different bytes
 * must fail closed at the database; and the immutable trigger must reject any mutation of a
 * persisted manifest reference without corrupting the original bytes.
 */
@SpringBootTest(
    classes = Sc810PostgresTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {"spring.flyway.target=21", "spring.jpa.hibernate.ddl-auto=update"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SourceManifestConcurrentPersistenceIntegrationTest {

  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";
  private static final String FIXTURE_HASH =
      SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();
  private static final String ARTIFACT =
      SourceManifestTestFixtures.manifest().path("build").path("artifact_digest").asText();
  private static final KeyPair SIGNING_KEY = signingKey();

  private static final HttpServer MICRO6 = startStub();

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] manifest =
          SourceManifestTestFixtures.signedManifest(SIGNING_KEY)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/api/v1/source-manifests/EXAMPLE_TASK",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, manifest.length);
            exchange.getResponseBody().write(manifest);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException exception) {
      throw new IllegalStateException("micro6 stub could not start", exception);
    }
  }

  @DynamicPropertySource
  static void micro6BaseUrl(DynamicPropertyRegistry registry) {
    IsolatedPostgresTestDatabase.register(registry);
    registry.add(
        "micro6.source.transport.base-url",
        () -> "http://127.0.0.1:" + MICRO6.getAddress().getPort());
    registry.add("micro6.source.transport.auth-token", () -> "test-source-upstream-token");
    registry.add("micro6.source.transport.actor", () -> "chromascape-test");
    String key = Base64.getEncoder().encodeToString(SIGNING_KEY.getPublic().getEncoded());
    registry.add("chromascape.source.public-key-base64", () -> key);
    registry.add("chromascape.source.public-key-id", () -> "test-key");
    registry.add("chromascape.source.environment", () -> "production");
  }

  private static KeyPair signingKey() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("Ed25519 unavailable", exception);
    }
  }

  @Autowired SourceRunOrchestrationService orchestration;
  @Autowired SourceManifestReferenceRepository manifests;
  @Autowired SourceRunBindingRepository bindings;
  @Autowired SourceManifestBindingService bindingService;
  @Autowired CurrentSliceRunRepository runs;
  @Autowired ScriptStabilizationRunRepository stabilization;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanCreatedRows() {
    bindings
        .findAll()
        .forEach(
            binding -> {
              if (binding.getRunId() != null && binding.getRunId().startsWith("race-")) {
                bindings.delete(binding);
              }
            });
    runs.findAll()
        .forEach(
            run -> {
              if (run.getRunId() != null && run.getRunId().startsWith("race-")) {
                runs.delete(run);
              }
            });
    stabilization
        .findAll()
        .forEach(
            run -> {
              if (run.getRunId() != null && run.getRunId().startsWith("race-")) {
                stabilization.delete(run);
              }
            });
    // LOB-bearing manifest rows must be read inside a transaction.
    tx().executeWithoutResult(
            status -> {
              manifests
                  .findAll()
                  .forEach(
                      reference -> {
                        if (FIXTURE_HASH.equals(reference.getManifestHash())
                            || (reference.getSnapshotId() != null
                                && reference.getSnapshotId().startsWith("snap_conflict_"))) {
                          manifests.delete(reference);
                        }
                      });
            });
  }

  @Test
  void twoThreadsRacingTheSameIdentityConvergeOnOneImmutableRow() throws Exception {
    seedPassStabilization("race-run-0", "attempt-1");
    seedPassStabilization("race-run-1", "attempt-1");
    int threads = 2;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> results = new ArrayList<>();
    for (int index = 0; index < threads; index++) {
      int runIndex = index;
      results.add(
          pool.submit(
              () -> {
                start.await();
                orchestration.createSourceBoundRun("attempt-1", identity("race-run-" + runIndex));
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : results) {
      // Both threads must succeed: the loser rolls back and retries the whole short transaction,
      // resolving the winner instead of surfacing a rollback-only state.
      future.get(30, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    long rows =
        tx().execute(
                status ->
                    manifests.findAll().stream()
                        .filter(reference -> FIXTURE_HASH.equals(reference.getManifestHash()))
                        .count());
    assertEquals(1, rows, "identical immutable bytes must converge on exactly one row");
    for (int index = 0; index < threads; index++) {
      Optional<SourceRunBinding> binding = bindings.findByRunId("race-run-" + index);
      assertTrue(binding.isPresent(), "every racing run must bind to the shared identity");
      assertEquals(FIXTURE_HASH, binding.get().getManifestHash());
    }
  }

  @Test
  void sameManifestHashWithDifferentBytesIsRejectedAtTheDatabase() {
    String firstJson = SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString();
    bindingService.verifyAndPersist("EXAMPLE_TASK", firstJson, null);
    String storedJson =
        tx().execute(
                status ->
                    manifests.findByManifestHash(FIXTURE_HASH).orElseThrow().getManifestJson());

    // Identity reuse: same manifest hash, different bytes, different snapshot id. The unique
    // manifest-hash index must reject the insert before any conflicting row exists.
    SourceManifestReference conflicting = new SourceManifestReference();
    conflicting.setTaskKey("EXAMPLE_TASK");
    conflicting.setSchemaVersion(SourceManifestContract.SCHEMA);
    conflicting.setManifestHash(FIXTURE_HASH);
    conflicting.setSnapshotId("snap_conflict_same_hash");
    conflicting.setBuildId("micro6-build-other");
    conflicting.setArtifactDigest(ARTIFACT);
    conflicting.setRepositoryId("micro6");
    conflicting.setCommitId("0123456789abcdef0123456789abcdef01234567");
    conflicting.setTreeId("fedcba9876543210fedcba9876543210fedcba98");
    conflicting.setVerificationState("VERIFIED");
    conflicting.setSourceProvenance("VERIFIED");
    conflicting.setRetentionClass("DEBUG");
    conflicting.setConfidentiality("INTERNAL");
    conflicting.setEnvironment("production");
    conflicting.setManifestJson("{\"tampered\":true}");
    assertThrows(
        Exception.class,
        () -> manifests.saveAndFlush(conflicting),
        "the unique manifest-hash index must reject identity reuse with different bytes");

    tx().executeWithoutResult(
            status -> {
              SourceManifestReference original =
                  manifests.findByManifestHash(FIXTURE_HASH).orElseThrow();
              assertEquals(
                  storedJson,
                  original.getManifestJson(),
                  "the original immutable bytes must survive the failed conflict");
            });
  }

  @Test
  void immutableTriggerRejectsAnyMutationOfAPersistedReference() {
    String firstJson = SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString();
    bindingService.verifyAndPersist("EXAMPLE_TASK", firstJson, null);
    String storedJson =
        tx().execute(
                status ->
                    manifests.findByManifestHash(FIXTURE_HASH).orElseThrow().getManifestJson());

    // Any attempted UPDATE of an immutable identity field must be rejected by the PostgreSQL
    // trigger, and the durable row must remain byte-identical afterwards.
    assertThrows(
        Exception.class,
        () ->
            tx().executeWithoutResult(
                    status -> {
                      SourceManifestReference reference =
                          manifests.findByManifestHash(FIXTURE_HASH).orElseThrow();
                      reference.setManifestJson("{\"tampered\":true}");
                      manifests.saveAndFlush(reference);
                    }),
        "the SC-810 immutability trigger must reject identity mutation");

    tx().executeWithoutResult(
            status -> {
              SourceManifestReference after =
                  manifests.findByManifestHash(FIXTURE_HASH).orElseThrow();
              assertEquals(
                  storedJson,
                  after.getManifestJson(),
                  "the immutable row must survive the rejected update");
            });
  }

  private static CurrentSliceIdentity identity(String runId) {
    var manifest = SourceManifestTestFixtures.manifest();
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client(
            "client", "MICRO6_MANAGED", "micro6-build-1", ARTIFACT, "micro6-builder/v2", "MICRO6"),
        null,
        new CurrentSliceIdentity.Source(
            manifest.path("source_snapshot_id").asText(),
            manifest.path("source_manifest_hash").asText(),
            manifest.path("repository").path("repository_id").asText(),
            manifest.path("repository").path("commit_id").asText(),
            manifest.path("repository").path("tree_id").asText(),
            manifest.path("build").path("build_id").asText(),
            manifest.path("build").path("artifact_digest").asText(),
            true),
        null,
        null,
        null,
        false);
  }

  private void seedPassStabilization(String runId, String attemptId) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setReportId("race-" + runId);
    run.setScriptKey("EXAMPLE_TASK");
    run.setOutcome("PASS");
    run.setRunId(runId);
    run.setAttemptId(attemptId);
    stabilization.saveAndFlush(run);
  }
}
