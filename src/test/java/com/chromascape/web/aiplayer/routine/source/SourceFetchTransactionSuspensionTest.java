package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.ChromaScapeApplication;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    classes = ChromaScapeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SourceFetchTransactionSuspensionTest {

  private static final String HASH =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  /** Recorded by the stub while it serves the manifest: true means a transaction was active. */
  private static final AtomicBoolean TRANSACTION_ACTIVE_DURING_FETCH = new AtomicBoolean(true);

  private static final HttpServer MICRO6 = startStub();

  private static HttpServer startStub() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] manifest = SourceManifestTestFixtures.manifest().toString()
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
    registry.add("micro6.executor-catalog.base-url",
        () -> "http://127.0.0.1:" + MICRO6.getAddress().getPort());
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
    return new CurrentSliceIdentity(
        "current-slice-identity/v1",
        new CurrentSliceIdentity.Task("EXAMPLE_TASK", HASH, "profile", HASH, HASH),
        new CurrentSliceIdentity.Run(runId, "attempt-1", 1, "executor-1"),
        new CurrentSliceIdentity.Client("client", "MICRO6_MANAGED", "micro6-build-1", artifact,
            "micro6-builder/v2", "MICRO6"),
        null, null, null, null, null, false);
  }
}
