package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import com.chromascape.web.aiplayer.routine.source.SourceManifestFailureCode;
import com.chromascape.web.aiplayer.routine.source.SourceManifestTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Micro6SourceManifestClientTest {
  @Test
  void acceptsABoundedProductionSizedManifestResponse() throws Exception {
    // The authoritative Fishing closure is currently about 15.3 MB. Exercise the transport bound
    // with comparable bytes so a smaller stale consumer cap cannot strand managed run creation.
    byte[] manifest = new byte[15_500_000];
    java.util.Arrays.fill(manifest, (byte) 'x');
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK", exchange -> respond(exchange, 200, manifest));
    server.start();
    try {
      assertEquals(
          manifest.length,
          client(server).manifest("EXAMPLE_TASK").getBytes(StandardCharsets.UTF_8).length);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void resolvesAnIndexedSymbolFromTheExactSnapshot() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    KeyPair signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode manifest = SourceManifestTestFixtures.signedManifest(signingKey);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK",
        exchange -> respond(exchange, 200, mapper.writeValueAsBytes(manifest)));
    server.createContext(
        "/api/v1/source-snapshots/"
            + manifest.path("source_snapshot_id").asText()
            + "/files/"
            + SourceManifestTestFixtures.FILE_ID,
        exchange ->
            respond(
                exchange,
                200,
                SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      Micro6SourceManifestClient client = client(server, signingKey);
      var expected =
          new com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier
              .ExpectedIdentity(
              "EXAMPLE_TASK",
              "micro6",
              manifest.path("repository").path("commit_id").asText(),
              manifest.path("repository").path("tree_id").asText(),
              "micro6-build-1",
              manifest.path("build").path("artifact_digest").asText(),
              manifest.path("source_snapshot_id").asText(),
              manifest.path("source_manifest_hash").asText());
      Micro6SourceManifestClient.SourceBinding binding =
          client.resolve(
              new Micro6SourceManifestClient.SourceRequest(
                  "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", expected));

      assertEquals(SourceManifestTestFixtures.FILE_ID, binding.fileId());
      assertEquals("src/main/java/com/example/Entry.java", binding.relativePath());
      assertEquals(
          "example-module/src/main/java/com/example/Entry.java", binding.cloneRelativePath());
      assertEquals("micro6-build-1", binding.clientBuildId());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mapsPrunedUpstreamResponsesToTheStableFailureCode() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-snapshots/srcsnap_0123456789abcdef01234567/files/file_0123456789abcdef",
        exchange ->
            respond(
                exchange, 410, "{\"code\":\"SOURCE_PRUNED\"}".getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      SourceManifestException exception =
          assertThrows(
              SourceManifestException.class,
              () ->
                  client(server)
                      .fileBytes("srcsnap_0123456789abcdef01234567", "file_0123456789abcdef"));
      assertEquals(SourceManifestFailureCode.SOURCE_PRUNED, exception.code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsInvalidManifestJsonAsAContractFailure() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK",
        exchange -> respond(exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      SourceManifestException exception =
          assertThrows(
              SourceManifestException.class,
              () ->
                  client(server)
                      .resolve(
                          new Micro6SourceManifestClient.SourceRequest(
                              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, exception.code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void strictLoaderMutationsFailManifestFetchClosed() throws Exception {
    // Every upstream manifest payload enters through SourceManifestCanonicalJson.parse: malformed,
    // duplicate-key, and trailing-token documents must each surface as a manifest contract
    // failure rather than a leniently parsed tree. (Malformed UTF-8 fails earlier at the
    // transport UTF-8 boundary as SOURCE_CONTENT_HASH_MISMATCH and is covered separately.)
    String[] mutations = {
      "{not json",
      "{\"schema\":\"task-source-manifest/v1\",\"task_key\":\"EXAMPLE_TASK\","
          + "\"task_key\":\"EXAMPLE_TASK\"}",
      "{\"schema\":\"task-source-manifest/v1\"} trailing",
    };
    for (String mutation : mutations) {
      byte[] payload = mutation.getBytes(StandardCharsets.UTF_8);
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/v1/source-manifests/EXAMPLE_TASK", exchange -> respond(exchange, 200, payload));
      server.start();
      try {
        SourceManifestException exception =
            assertThrows(
                SourceManifestException.class,
                () ->
                    client(server)
                        .resolve(
                            new Micro6SourceManifestClient.SourceRequest(
                                "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
        assertEquals(
            SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
            exception.code(),
            "strict manifest mutation must fail as a contract failure");
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void malformedUtf8ManifestFailsAtTheTransportBoundary() throws Exception {
    // Malformed UTF-8 never reaches the strict JSON parser: the transport decodes response bytes
    // with REPORT, so the fetch fails closed at the UTF-8 boundary instead.
    byte[] payload = new byte[] {(byte) 0xFF, (byte) 0xFE, 0x7B, 0x7D};
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK", exchange -> respond(exchange, 200, payload));
    server.start();
    try {
      SourceManifestException exception =
          assertThrows(
              SourceManifestException.class,
              () ->
                  client(server)
                      .resolve(
                          new Micro6SourceManifestClient.SourceRequest(
                              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          exception.code(),
          "malformed UTF-8 must fail at the transport UTF-8 boundary");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsUnsignedV1ManifestEvenWhenTheTransportAndTrustKeyAreConfigured() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    KeyPair signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK",
        exchange ->
            respond(
                exchange, 200, mapper.writeValueAsBytes(SourceManifestTestFixtures.manifest())));
    server.start();
    try {
      SourceManifestException exception =
          assertThrows(
              SourceManifestException.class,
              () ->
                  client(server, signingKey)
                      .resolve(
                          new Micro6SourceManifestClient.SourceRequest(
                              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID, exception.code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsAManifestFromAnotherConfiguredEnvironment() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    KeyPair signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/source-manifests/EXAMPLE_TASK",
        exchange ->
            respond(
                exchange,
                200,
                mapper.writeValueAsBytes(SourceManifestTestFixtures.signedManifest(signingKey))));
    server.start();
    try {
      // The fixture manifest declares environment "production"; configure a different
      // environment so the identity check is the assertion under test.
      Micro6SourceManifestClient client = client(server, signingKey, "staging");
      SourceManifestException exception =
          assertThrows(
              SourceManifestException.class,
              () ->
                  client.resolve(
                      new Micro6SourceManifestClient.SourceRequest(
                          "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
    } finally {
      server.stop(0);
    }
  }

  private static Micro6SourceManifestClient client(HttpServer server) throws Exception {
    KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Micro6SourceManifestClient client =
        new Micro6SourceManifestClient(new ObjectMapper(), trust(key, "production"));
    Field field = Micro6SourceManifestClient.class.getDeclaredField("baseUrl");
    field.setAccessible(true);
    field.set(client, "http://127.0.0.1:" + server.getAddress().getPort());
    Field token = Micro6SourceManifestClient.class.getDeclaredField("authToken");
    token.setAccessible(true);
    token.set(client, "test-token");
    Field actor = Micro6SourceManifestClient.class.getDeclaredField("actor");
    actor.setAccessible(true);
    actor.set(client, "chromascape-test");
    return client;
  }

  private static Micro6SourceManifestClient client(HttpServer server, KeyPair signingKey)
      throws Exception {
    return client(server, signingKey, "production");
  }

  private static Micro6SourceManifestClient client(
      HttpServer server, KeyPair signingKey, String environment) throws Exception {
    Micro6SourceManifestClient client =
        new Micro6SourceManifestClient(new ObjectMapper(), trust(signingKey, environment));
    Field field = Micro6SourceManifestClient.class.getDeclaredField("baseUrl");
    field.setAccessible(true);
    field.set(client, "http://127.0.0.1:" + server.getAddress().getPort());
    Field token = Micro6SourceManifestClient.class.getDeclaredField("authToken");
    token.setAccessible(true);
    token.set(client, "test-token");
    Field actor = Micro6SourceManifestClient.class.getDeclaredField("actor");
    actor.setAccessible(true);
    actor.set(client, "chromascape-test");
    return client;
  }

  private static com.chromascape.web.config.SourceTrustProperties trust(
      KeyPair signingKey, String environment) {
    com.chromascape.web.config.SourceTrustProperties trust =
        new com.chromascape.web.config.SourceTrustProperties();
    trust.setPublicKeyBase64(
        Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()));
    trust.setPublicKeyId("test-key");
    trust.setEnvironment(environment);
    return trust;
  }

  private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
