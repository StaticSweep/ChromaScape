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
import org.junit.jupiter.api.Test;

class Micro6SourceManifestClientTest {
  @Test
  void resolvesAnIndexedSymbolFromTheExactSnapshot() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode manifest = SourceManifestTestFixtures.manifest();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/source-manifests/EXAMPLE_TASK",
        exchange -> respond(exchange, 200, mapper.writeValueAsBytes(manifest)));
    server.createContext("/api/v1/source-snapshots/"
            + manifest.path("source_snapshot_id").asText() + "/files/"
            + SourceManifestTestFixtures.FILE_ID,
        exchange -> respond(exchange, 200,
            SourceManifestTestFixtures.CONTENT.getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      Micro6SourceManifestClient client = client(server);
      var expected = new com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier
          .ExpectedIdentity(
              "EXAMPLE_TASK",
              "micro6",
              manifest.path("repository").path("commit_id").asText(),
              manifest.path("repository").path("tree_id").asText(),
              "micro6-build-1",
              manifest.path("build").path("artifact_digest").asText(),
              manifest.path("source_snapshot_id").asText(),
              manifest.path("source_manifest_hash").asText());
      Micro6SourceManifestClient.SourceBinding binding = client.resolve(
          new Micro6SourceManifestClient.SourceRequest(
              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", expected));

      assertEquals(SourceManifestTestFixtures.FILE_ID, binding.fileId());
      assertEquals("src/main/java/com/example/Entry.java", binding.relativePath());
      assertEquals("example-module/src/main/java/com/example/Entry.java",
          binding.cloneRelativePath());
      assertEquals("micro6-build-1", binding.clientBuildId());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mapsPrunedUpstreamResponsesToTheStableFailureCode() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/source-snapshots/srcsnap_0123456789abcdef01234567/files/file_0123456789abcdef",
        exchange -> respond(exchange, 410,
            "{\"code\":\"SOURCE_PRUNED\"}".getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      SourceManifestException exception = assertThrows(SourceManifestException.class,
          () -> client(server).fileBytes("srcsnap_0123456789abcdef01234567",
              "file_0123456789abcdef"));
      assertEquals(SourceManifestFailureCode.SOURCE_PRUNED, exception.code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsInvalidManifestJsonAsAContractFailure() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/source-manifests/EXAMPLE_TASK",
        exchange -> respond(exchange, 200, "not-json".getBytes(StandardCharsets.UTF_8)));
    server.start();
    try {
      SourceManifestException exception = assertThrows(SourceManifestException.class,
          () -> client(server).resolve(new Micro6SourceManifestClient.SourceRequest(
              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, exception.code());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsAManifestFromAnotherConfiguredEnvironment() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/source-manifests/EXAMPLE_TASK",
        exchange -> respond(exchange, 200,
            mapper.writeValueAsBytes(SourceManifestTestFixtures.manifest())));
    server.start();
    try {
      Micro6SourceManifestClient client = client(server);
      com.chromascape.web.config.SourceTrustProperties trust =
          new com.chromascape.web.config.SourceTrustProperties();
      // The fixture manifest declares environment "production"; configure a different
      // environment so the identity check is the assertion under test.
      trust.setEnvironment("staging");
      client.setTrust(trust);
      SourceManifestException exception = assertThrows(SourceManifestException.class,
          () -> client.resolve(new Micro6SourceManifestClient.SourceRequest(
              "EXAMPLE_TASK", "com.example.Entry", "Entry#run()", null)));
      assertEquals(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, exception.code());
    } finally {
      server.stop(0);
    }
  }

  private static Micro6SourceManifestClient client(HttpServer server) throws Exception {
    Micro6SourceManifestClient client = new Micro6SourceManifestClient(new ObjectMapper());
    Field field = Micro6SourceManifestClient.class.getDeclaredField("baseUrl");
    field.setAccessible(true);
    field.set(client, "http://127.0.0.1:" + server.getAddress().getPort());
    return client;
  }

  private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
