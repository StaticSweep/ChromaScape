package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @TempDir Path temp;

  @Test
  void registerWithClosureSendsClosurePartsAndRequiresTheAdvertisedDocumentHash() throws Exception {
    Staged staged = stage();
    AtomicReference<byte[]> received = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.set(exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().set("X-Candidate-Manifest-SHA256", staged.manifestHash());
          exchange.getResponseHeaders().set("X-Execution-Closure-SHA256", staged.closureHash());
          exchange.sendResponseHeaders(201, staged.manifest().length);
          exchange.getResponseBody().write(staged.manifest());
          exchange.close();
        });
    server.start();

    CandidateRegistryClient.RegistryManifestResponse response =
        clientForServer()
            .registerEvidence(
                staged.manifestPath(),
                staged.jar(),
                staged.sbom(),
                staged.certification(),
                new CandidateRegistryClient.ClosureUpload(
                    staged.closurePath(), List.of(staged.runner(), staged.core())));

    String body = new String(received.get(), StandardCharsets.ISO_8859_1);
    assertTrue(body.contains("name=\"execution_closure\"; filename=\"execution-closure.json\""));
    assertTrue(body.contains("name=\"closure_jar\"; filename=\"runner.jar\""));
    assertTrue(body.contains("name=\"closure_jar\"; filename=\"core.jar\""));
    assertEquals(staged.closureHash(), response.closureHash());
  }

  @Test
  void registerWithClosureRejectsARegistryThatDoesNotConfirmTheStoredClosure() throws Exception {
    Staged staged = stage();
    for (String advertised : new String[] {null, HASH}) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("X-Candidate-Manifest-SHA256", staged.manifestHash());
            if (advertised != null) {
              exchange.getResponseHeaders().set("X-Execution-Closure-SHA256", advertised);
            }
            exchange.sendResponseHeaders(201, staged.manifest().length);
            exchange.getResponseBody().write(staged.manifest());
            exchange.close();
          });
      server.start();

      CandidateRegistryClient.RegistryException error =
          assertThrows(
              CandidateRegistryClient.RegistryException.class,
              () ->
                  clientForServer()
                      .registerEvidence(
                          staged.manifestPath(),
                          staged.jar(),
                          staged.sbom(),
                          staged.certification(),
                          new CandidateRegistryClient.ClosureUpload(
                              staged.closurePath(), List.of(staged.runner()))));
      assertEquals("REGISTRY_CLOSURE_HASH_MISMATCH", error.getMessage());
      assertTrue(!error.isUncertain());
      server.stop(0);
    }
  }

  @Test
  void registerWithClosureRejectsBadJarSetsBeforeAnyRequest() throws Exception {
    Staged staged = stage();
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient client = clientForServer();

    for (List<Path> jars : List.of(List.<Path>of(), List.of(staged.runner(), staged.runner()))) {
      CandidateRegistryClient.RegistryException error =
          assertThrows(
              CandidateRegistryClient.RegistryException.class,
              () ->
                  client.registerEvidence(
                      staged.manifestPath(),
                      staged.jar(),
                      staged.sbom(),
                      staged.certification(),
                      new CandidateRegistryClient.ClosureUpload(staged.closurePath(), jars)));
      assertTrue(error.getMessage().startsWith("EXECUTION_CLOSURE_JAR"), error.getMessage());
    }
    assertEquals(0, requests.get());
  }

  @Test
  void downloadClosureChecksTheAdvertisedDigestOfTheExactBytes() throws Exception {
    byte[] document =
        "{\"schema\":\"candidate-execution-closure/v1\"}".getBytes(StandardCharsets.UTF_8);
    for (String advertised : new String[] {CandidateRegistryClient.hash(document), HASH}) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.getResponseHeaders().set("X-Execution-Closure-SHA256", advertised);
            exchange.sendResponseHeaders(200, document.length);
            exchange.getResponseBody().write(document);
            exchange.close();
          });
      server.start();

      if (advertised.equals(HASH)) {
        CandidateRegistryClient.RegistryException error =
            assertThrows(
                CandidateRegistryClient.RegistryException.class,
                () -> clientForServer().downloadClosure(CANDIDATE_ID));
        assertEquals("REGISTRY_CLOSURE_HASH_MISMATCH", error.getMessage());
      } else {
        assertArrayEquals(document, clientForServer().downloadClosure(CANDIDATE_ID).bytes());
      }
      server.stop(0);
    }
  }

  @Test
  void downloadClosureJarFetchesByDeclaredNameAndRefusesUnsafeNames() throws Exception {
    byte[] jar = {1, 2, 3, 4};
    AtomicReference<String> path = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(200, jar.length);
          exchange.getResponseBody().write(jar);
          exchange.close();
        });
    server.start();
    CandidateRegistryClient client = clientForServer();

    assertArrayEquals(jar, client.downloadClosureJar(CANDIDATE_ID, "core.jar"));
    assertEquals("/api/v1/candidates/" + CANDIDATE_ID + "/closure/core.jar", path.get());
    for (String unsafe : new String[] {"../core.jar", "a/b.jar", "core.txt", "", null}) {
      CandidateRegistryClient.RegistryException error =
          assertThrows(
              CandidateRegistryClient.RegistryException.class,
              () -> client.downloadClosureJar(CANDIDATE_ID, unsafe));
      assertEquals("EXECUTION_CLOSURE_JAR_NAME_INVALID", error.getMessage());
    }
  }

  private record Staged(
      Path manifestPath,
      Path jar,
      Path sbom,
      Path certification,
      Path closurePath,
      Path runner,
      Path core,
      byte[] manifest,
      String closureHash) {
    String manifestHash() {
      return CandidateRegistryClient.hash(manifest);
    }
  }

  /** Real files with arbitrary bytes: the validator is a stub, so this exercises transport only. */
  private Staged stage() throws Exception {
    byte[] manifest = "{\"schema\":\"certified-candidate/v2\"}".getBytes(StandardCharsets.UTF_8);
    byte[] closure =
        "{\"schema\":\"candidate-execution-closure/v1\"}".getBytes(StandardCharsets.UTF_8);
    Path manifestPath = write("manifest.json", manifest);
    return new Staged(
        manifestPath,
        write("candidate.jar", new byte[] {9}),
        write("sbom.cdx.json", "{}".getBytes(StandardCharsets.UTF_8)),
        write("certification.json", "{}".getBytes(StandardCharsets.UTF_8)),
        write("execution-closure.json", closure),
        write("runner.jar", new byte[] {1}),
        write("core.jar", new byte[] {2}),
        manifest,
        CandidateRegistryClient.hash(closure));
  }

  private Path write(String name, byte[] bytes) throws Exception {
    return Files.write(temp.resolve(name), bytes);
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
    CandidateRegistryClient client =
        new CandidateRegistryClient(mock(CertifiedCandidateV2Validator.class));
    Field baseUrl = CandidateRegistryClient.class.getDeclaredField("baseUrl");
    baseUrl.setAccessible(true);
    baseUrl.set(client, "http://127.0.0.1:" + server.getAddress().getPort());
    return client;
  }
}
