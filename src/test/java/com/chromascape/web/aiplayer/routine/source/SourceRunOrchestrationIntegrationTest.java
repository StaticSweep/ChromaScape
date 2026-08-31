package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.ChromaScapeApplication;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SC-810 transactional integration proof for the source-binding split: the Micro6 manifest is
 * fetched with no transaction active, and manifest reference, run, source binding and run-stage
 * writes commit (or roll back) in exactly one database transaction against the real JPA stack.
 *
 * <p>Uses the test PostgreSQL datasource (application-test.yml). The Micro6 endpoint is a local
 * HTTP stub serving the deterministic {@link SourceManifestTestFixtures} manifest.
 */
@SpringBootTest(
    classes = ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SourceRunOrchestrationIntegrationTest {

  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";
  /** The fixture manifest's artifact identity; the attested client identity must carry it. */
  private static final String ARTIFACT =
      SourceManifestTestFixtures.manifest().path("build").path("artifact_digest").asText();

  /** Static block so the stub address exists before the Spring context resolves properties. */
  private static final HttpServer MICRO6 = startStub();

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] manifest = SourceManifestTestFixtures.manifest().toString()
          .getBytes(StandardCharsets.UTF_8);
      server.createContext("/api/v1/source-manifests/EXAMPLE_TASK", exchange -> {
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
    registry.add("micro6.executor-catalog.base-url",
        () -> "http://127.0.0.1:" + MICRO6.getAddress().getPort());
  }

  @Autowired SourceRunOrchestrationService orchestration;
  @Autowired SourceManifestReferenceRepository manifests;
  @Autowired SourceRunBindingRepository bindings;
  @Autowired CurrentSliceRunRepository runs;
  @Autowired ScriptStabilizationRunRepository stabilization;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  /** Reads a @Lob-bearing manifest row; LOB columns must be read inside a transaction. */
  private java.util.Optional<SourceManifestReference> findManifest(String hash) {
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .execute(status -> manifests.findByManifestHash(hash));
  }

  @AfterEach
  void cleanCreatedRows() {
    // Remove only rows this suite created; the fixture identity is deterministic so the rows are
    // findable by their exact identities.
    bindings.findAll().forEach(binding -> {
      if (binding.getRunId() != null && binding.getRunId().startsWith("orch-")) {
        bindings.delete(binding);
      }
    });
    runs.findAll().forEach(run -> {
      if (run.getRunId() != null && run.getRunId().startsWith("orch-")) {
        runs.delete(run);
      }
    });
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          manifests.findAll().forEach(reference -> {
            if (SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText()
                    .equals(reference.getManifestHash())
                || "snap_pre_existing_conflict".equals(reference.getSnapshotId())) {
              manifests.delete(reference);
            }
          });
        });
    stabilization.findAll().forEach(run -> {
      if (run.getRunId() != null && run.getRunId().startsWith("orch-")) {
        stabilization.delete(run);
      }
    });
  }

  @Test
  void manifestRunBindingAndStageCommitTogether() {
    String runId = "orch-run-happy";
    seedPassStabilization(runId, "attempt-1");
    String hash = SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();

    orchestration.createSourceBoundRun("attempt-1", identity(runId));

    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          Optional<SourceManifestReference> reference = manifests.findByManifestHash(hash);
          assertTrue(reference.isPresent(), "manifest reference must be persisted");
          Optional<SourceRunBinding> binding = bindings.findByRunId(runId);
          assertTrue(binding.isPresent(), "run binding must be persisted");
          assertEquals(hash, binding.get().getManifestHash());
          assertTrue(runs.findByRunId(runId).isPresent(), "run must be persisted");
        });
  }

  @Test
  void runInsertFailureLeavesNoManifest() {
    String runId = "orch-run-runfail";
    String hash = SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();
    // The orchestration transaction persists the manifest BEFORE runs.create rejects a blank
    // MICRO6_MANAGED attemptId; the rejection must roll the manifest write back too.
    assertThrows(Exception.class,
        () -> orchestration.createSourceBoundRun("  ", identity(runId)));

    assertTrue(findManifest(hash).isEmpty(), "manifest insert must roll back");
    assertTrue(bindings.findByRunId(runId).isEmpty(), "no binding may be created");
    assertTrue(runs.findByRunId(runId).isEmpty(), "no run may be created");
  }

  @Test
  void bindingConflictRollsBackRunAndManifest() {
    String runId = "orch-run-conflict";
    seedPassStabilization(runId, "attempt-1");
    SourceRunBinding conflicting = new SourceRunBinding();
    conflicting.setRunId(runId);
    conflicting.setTaskKey("EXAMPLE_TASK");
    conflicting.setManifestHash("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    conflicting.setSnapshotId("snap_pre_existing_conflict");
    conflicting.setArtifactDigest("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    conflicting.setVerificationState("UNSIGNED");
    conflicting.setSourceProvenance("UNSIGNED");
    // source_run_bindings.manifest_hash references source_manifest_references, so the conflicting
    // identity needs its own durable manifest reference row first.
    SourceManifestReference conflictingManifest = new SourceManifestReference();
    conflictingManifest.setTaskKey("EXAMPLE_TASK");
    conflictingManifest.setSchemaVersion(SourceManifestContract.SCHEMA);
    conflictingManifest.setManifestHash(conflicting.getManifestHash());
    conflictingManifest.setSnapshotId(conflicting.getSnapshotId());
    conflictingManifest.setBuildId("micro6-build-other");
    conflictingManifest.setArtifactDigest(conflicting.getArtifactDigest());
    conflictingManifest.setVerificationState("UNSIGNED");
    conflictingManifest.setSourceProvenance("UNSIGNED");
    conflictingManifest.setRetentionClass("DEBUG");
    conflictingManifest.setConfidentiality("INTERNAL");
    conflictingManifest.setManifestJson("{\"schema\":\"" + SourceManifestContract.SCHEMA + "\"}");
    manifests.saveAndFlush(conflictingManifest);
    bindings.saveAndFlush(conflicting);
    String hash = SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();

    assertThrows(Exception.class,
        () -> orchestration.createSourceBoundRun("attempt-1", identity(runId)));

    assertTrue(findManifest(hash).isEmpty(), "manifest insert must roll back");
    assertTrue(runs.findByRunId(runId).isEmpty(), "run insert must roll back");
    Optional<SourceRunBinding> untouched = bindings.findByRunId(runId);
    assertTrue(untouched.isPresent(), "pre-existing conflicting binding must be untouched");
    assertEquals(conflicting.getManifestHash(), untouched.get().getManifestHash());
  }

  @Test
  void fetchFailurePersistsNothing() {
    String runId = "orch-run-fetchfail";
    // This task is not served by the stub: the HTTP fetch fails before any transaction opens.
    CurrentSliceIdentity unserviced = new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("OTHER_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", HASH,
            "micro6-builder/v2", "MICRO6"),
        null, null, null, null, null, false);

    assertThrows(Exception.class,
        () -> orchestration.createSourceBoundRun("attempt-1", unserviced));

    assertTrue(bindings.findByRunId(runId).isEmpty());
    assertTrue(runs.findByRunId(runId).isEmpty());
    assertFalse(findManifest(
        SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText()).isPresent());
  }

  @Test
  void retriesAreIdempotent() {
    String runId = "orch-run-retry";
    seedPassStabilization(runId, "attempt-1");
    String hash = SourceManifestTestFixtures.manifest().path("source_manifest_hash").asText();

    orchestration.createSourceBoundRun("attempt-1", identity(runId));
    orchestration.createSourceBoundRun("attempt-1", identity(runId));

    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          assertEquals(1, manifests.findByManifestHash(hash).stream().count(),
              "identical manifests converge on one durable identity");
          assertEquals(1, bindings.findByRunId(runId).stream().count(),
              "retries reuse the existing run binding");
        });
  }

  private static CurrentSliceIdentity identity(String runId) {
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", ARTIFACT,
            "micro6-builder/v2", "MICRO6"),
        null, null, null, null, null, false);
  }

  private void seedPassStabilization(String runId, String attemptId) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setReportId("orch-" + runId);
    run.setScriptKey("EXAMPLE_TASK");
    run.setOutcome("PASS");
    run.setRunId(runId);
    run.setAttemptId(attemptId);
    stabilization.saveAndFlush(run);
    assertNotNull(run.getId());
  }
}
