package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CandidateRegistryClientTest {
  private static final String CANDIDATE_ID = "cand_000000000000000000000000";
  private static final String HASH = "sha256:" + "0".repeat(64);
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void headerless404LookupIsRejectedByStatusBeforeContentLength() throws Exception {
    CandidateRegistryClient client = clientForStatus(404);

    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () -> client.lookupEvidence(CANDIDATE_ID, HASH));

    assertEquals("REGISTRY_REJECTED_404", error.getMessage());
  }

  @Test
  void headerless404DownloadIsRejectedByStatusBeforeContentLength() throws Exception {
    CandidateRegistryClient client = clientForStatus(404);

    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () -> client.downloadEvidence(CANDIDATE_ID));

    assertEquals("REGISTRY_REJECTED_404", error.getMessage());
  }

  @Test
  void headerless2xxStillRequiresContentLength() throws Exception {
    CandidateRegistryClient client = clientForStatus(200);

    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () -> client.lookupEvidence(CANDIDATE_ID, HASH));

    assertEquals("REGISTRY_RESPONSE_LENGTH_REQUIRED", error.getMessage());
  }

  @Test
  void lookupReturnsLiteralRawManifestBytesAndChecksBodyHash() throws Exception {
    byte[] raw =
        "{\n  \"escaped\": \"\\u00e9\",\n  \"html\": \"<>&\"\n}\r\n"
            .getBytes(StandardCharsets.UTF_8);
    String hash = CandidateRegistryClient.hash(raw);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("X-Candidate-Manifest-SHA256", hash);
          exchange.sendResponseHeaders(200, raw.length);
          exchange.getResponseBody().write(raw);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient client = clientForServer();
    CandidateRegistryClient.RegistryManifestResponse response =
        client.lookupEvidence(CANDIDATE_ID, hash);
    assertArrayEquals(raw, response.rawManifest());
    assertEquals(hash, response.manifestHash());
  }

  @Test
  void bodyHashMismatchIsRejected() throws Exception {
    byte[] raw = "{\"literal\":true}".getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("X-Candidate-Manifest-SHA256", HASH);
          exchange.sendResponseHeaders(200, raw.length);
          exchange.getResponseBody().write(raw);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient client = clientForServer();
    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () -> client.lookupEvidence(CANDIDATE_ID, CandidateRegistryClient.hash(raw)));
    assertEquals("REGISTRY_MANIFEST_HASH_HEADER_MISSING", error.getMessage());
  }

  @Test
  void lookupHeaderMustMatchExpectedStagedHash() throws Exception {
    byte[] raw = "{\"literal\":true}".getBytes(StandardCharsets.UTF_8);
    String bodyHash = CandidateRegistryClient.hash(raw);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().set("X-Candidate-Manifest-SHA256", bodyHash);
          exchange.sendResponseHeaders(200, raw.length);
          exchange.getResponseBody().write(raw);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () -> clientForServer().lookupEvidence(CANDIDATE_ID, HASH));
    assertEquals("REGISTRY_MANIFEST_HASH_HEADER_MISMATCH", error.getMessage());
  }

  @Test
  void truncatedBodyIsRejectedEvenWhenHashMatchesTheAvailableBytes() throws Exception {
    byte[] raw = "{\"literal\":true}".getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange
              .getResponseHeaders()
              .set("X-Candidate-Manifest-SHA256", CandidateRegistryClient.hash(raw));
          exchange.sendResponseHeaders(200, raw.length + 1L);
          exchange.getResponseBody().write(raw);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient.RegistryException error =
        assertThrows(
            CandidateRegistryClient.RegistryException.class,
            () ->
                clientForServer().lookupEvidence(CANDIDATE_ID, CandidateRegistryClient.hash(raw)));
    assertEquals("REGISTRY_RESPONSE_LENGTH_MISMATCH", error.getMessage());
  }

  private CandidateRegistryClient clientForStatus(int status) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return clientForServer();
  }

  private CandidateRegistryClient clientForServer() throws Exception {
    CandidateRegistryClient client = new CandidateRegistryClient();
    Field baseUrl = CandidateRegistryClient.class.getDeclaredField("baseUrl");
    baseUrl.setAccessible(true);
    baseUrl.set(client, "http://127.0.0.1:" + server.getAddress().getPort());
    return client;
  }
}
