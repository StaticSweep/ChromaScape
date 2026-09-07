package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.testsupport.IsolatedPostgresTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SC-810 transaction-suspension proof: {@link SourceManifestFetchService#fetchForRun} is annotated
 * {@code NOT_SUPPORTED}, so a caller that already holds a database transaction never performs a
 * remote read inside it. The fetch runs through the real Spring proxy inside a live transaction
 * (TransactionTemplate), and the HTTP stub records whether a transaction was active while it
 * served the manifest.
 */
@SpringBootTest(
    classes = Sc810PostgresTestConfiguration.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.flyway.target=21",
    "spring.jpa.hibernate.ddl-auto=update"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SourceFetchTransactionSuspensionTest {

  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  /** Recorded by the stub while it serves the manifest: true means a transaction was active. */
  private static final AtomicBoolean TRANSACTION_ACTIVE_DURING_FETCH = new AtomicBoolean(true);
  private static final KeyPair SIGNING_KEY = signingKey();

  private static final HttpServer MICRO6 = startStub();

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] manifest = SourceManifestTestFixtures.signedManifest(SIGNING_KEY).toString()
          .getBytes(StandardCharsets.UTF_8);
      server.createContext("/api/v1/source-manifests/EXAMPLE_TASK", exchange -> {
        // The stub observes the fetch call site: for NOT_SUPPORTED to be proven, no transaction
        // may be active here even though the test caller holds one.
        TRANSACTION_ACTIVE_DURING_FETCH.set(
            TransactionSynchronizationManager.isActualTransactionActive());
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
    registry.add("micro6.source.transport.base-url",
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

  @Autowired SourceManifestFetchService fetch;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void fetchForRunRunsUpstreamOutsideAnyDatabaseTransaction() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    AtomicReference<Boolean> callerTransactionActive = new AtomicReference<>();
    AtomicReference<SourceManifestFetchService.FetchedSource> fetched = new AtomicReference<>();
    template.executeWithoutResult(status -> {
      callerTransactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
      // The caller holds a real transaction here; the NOT_SUPPORTED interceptor must suspend it
      // for the duration of the upstream read.
      fetched.set(fetch.fetchForRun("tx-run-1", "EXAMPLE_TASK", identity("tx-run-1")));
    });

    assertTrue(callerTransactionActive.get(),
        "the caller must hold a transaction for this proof");
    assertNotNull(fetched.get(), "the fetch must succeed");
    assertNotNull(fetched.get().verified());
    assertFalse(TRANSACTION_ACTIVE_DURING_FETCH.get(),
        "the upstream HTTP read must never run inside the caller's transaction");
  }

  private static CurrentSliceIdentity identity(String runId) {
    String artifact =
        SourceManifestTestFixtures.manifest().path("build").path("artifact_digest").asText();
    var manifest = SourceManifestTestFixtures.manifest();
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", artifact,
            "micro6-builder/v2", "MICRO6"),
        null,
        new CurrentSliceIdentity.Source(
            manifest.path("source_snapshot_id").asText(),
            manifest.path("source_manifest_hash").asText(),
            manifest.path("repository").path("repository_id").asText(),
            manifest.path("repository").path("commit_id").asText(),
            manifest.path("repository").path("tree_id").asText(),
            manifest.path("build").path("build_id").asText(),
            manifest.path("build").path("artifact_digest").asText(), true),
        null, null, null, false);
  }
}
