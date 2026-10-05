package com.chromascape.web.debug.buildergoal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class ExecutorCertificateTelemetryTest {
  @TempDir Path directory;

  @Test
  void realCatalogTlsObservesExpiryAndRejectsFreshExpiredHandshake() throws Exception {
    makeAuthority();
    Instant expires = Instant.now().plusSeconds(20);
    issueLeaf(expires);
    AtomicInteger calls = new AtomicInteger();
    HttpsServer server = startPeer(0, calls);
    try {
      String origin = "https://127.0.0.1:" + server.getAddress().getPort();
      ObjectMapper mapper = new ObjectMapper();
      BuilderGoalEndpointAuthority authority =
          new BuilderGoalEndpointAuthority(
              mapper, "http://127.0.0.1:1", "", "DEV", origin, "", true);
      Micro6ExecutorCatalogService catalog =
          new Micro6ExecutorCatalogService(new Micro6Config(), mapper);
      ReflectionTestUtils.setField(catalog, "endpointAuthority", authority);
      Micro6OnlineClientRegistryService registry = new Micro6OnlineClientRegistryService();
      ReflectionTestUtils.setField(registry, "endpointAuthority", authority);
      var identity =
          mapper
              .createObjectNode()
              .put("executor_instance_id", "fixture-executor")
              .put("route_source", "DEV_STATIC_ROUTE");
      registry.recordHeartbeat(identity);
      assertEquals("NOT_OBSERVED", registry.listClients().get(0).get("certificateExpiryClass"));
      assertEquals(
          "ENDPOINT_UNAVAILABLE",
          catalog
              .getCatalogForIdentity(Map.of("route_source", "DEV_STATIC_ROUTE"), true)
              .path("reason_code")
              .asText());
      assertEquals("NOT_OBSERVED", registry.listClients().get(0).get("certificateExpiryClass"));
      assertEquals(0, calls.get(), "untrusted CA must not reach HTTP handler");
      ReflectionTestUtils.setField(catalog, "httpClient", trustedClient());
      assertTrue(
          catalog
              .getCatalogForIdentity(Map.of("route_source", "DEV_STATIC_ROUTE"), true)
              .has("tasks"));
      var observed = registry.listClients().get(0);
      assertEquals("EXPIRING_SOON", observed.get("certificateExpiryClass"));
      assertEquals("VERIFIED_TLS_SESSION", observed.get("certificateObservationSource"));
      Instant peerExpiry = Instant.parse((String) observed.get("certificateNotAfter"));
      // Real wall-clock expiry, not a mocked clock. The wait is bounded by the 20-second leaf.
      while (Instant.now().isBefore(peerExpiry.plusMillis(300))) Thread.sleep(100);
      var expired = registry.listClients().get(0);
      assertEquals("EXPIRED", expired.get("certificateExpiryClass"));
      assertEquals("DEGRADED", expired.get("topologyState"));
      // Fresh context forces certificate validation rather than TLS session resumption.
      ReflectionTestUtils.setField(catalog, "httpClient", trustedClient());
      assertEquals(
          "ENDPOINT_UNAVAILABLE",
          catalog
              .getCatalogForIdentity(Map.of("route_source", "DEV_STATIC_ROUTE"), true)
              .path("reason_code")
              .asText());
      assertEquals(1, calls.get(), "expired TLS handshake must not reach the HTTP handler");
      assertEquals("EXPIRED", registry.listClients().get(0).get("certificateExpiryClass"));
      int port = server.getAddress().getPort();
      server.stop(0);
      issueLeaf(Instant.now().plus(Duration.ofDays(1)));
      server = startPeer(port, calls);
      ReflectionTestUtils.setField(catalog, "httpClient", trustedClient());
      assertTrue(
          catalog
              .getCatalogForIdentity(Map.of("route_source", "DEV_STATIC_ROUTE"), true)
              .has("tasks"));
      var rotated = registry.listClients().get(0);
      assertEquals("EXPIRING_SOON", rotated.get("certificateExpiryClass"));
      assertTrue(Instant.parse((String) rotated.get("certificateNotAfter")).isAfter(peerExpiry));
      assertEquals(2, calls.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void noSessionUnverifiedPeerAndUnapprovedOriginsNeverInventCertificateEvidence()
      throws Exception {
    makeAuthority();
    issueLeaf(Instant.now().plus(Duration.ofDays(30)));
    X509Certificate cert = certificate("leaf.pem");
    var session = org.mockito.Mockito.mock(javax.net.ssl.SSLSession.class);
    org.mockito.Mockito.when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {cert});
    HttpResponse<?> response = org.mockito.Mockito.mock(HttpResponse.class);
    org.mockito.Mockito.when(response.sslSession()).thenReturn(java.util.Optional.of(session));
    Instant now = Instant.now();
    ExecutorCertificateTelemetry telemetry =
        new ExecutorCertificateTelemetry(Clock.fixed(now, ZoneOffset.UTC));
    telemetry.observe("https://127.0.0.1:8098", response);
    assertEquals(
        "VALID", telemetry.summary("https://127.0.0.1:8098").get("certificateExpiryClass"));
    assertEquals(
        "NOT_OBSERVED", telemetry.summary("http://127.0.0.1:8098").get("certificateExpiryClass"));
    org.mockito.Mockito.when(response.sslSession()).thenReturn(java.util.Optional.empty());
    telemetry.observe("https://127.0.0.1:8099", response);
    assertEquals(
        "NOT_OBSERVED", telemetry.summary("https://127.0.0.1:8099").get("certificateExpiryClass"));
    // An unverified peer and an unapproved origin cannot overwrite safe evidence.
    var authority =
        new BuilderGoalEndpointAuthority(
            new ObjectMapper(),
            "http://127.0.0.1:1",
            "",
            "DEV",
            "https://127.0.0.1:8098",
            "",
            true);
    org.mockito.Mockito.when(response.uri())
        .thenReturn(URI.create("https://127.0.0.1:8099/health"));
    org.mockito.Mockito.when(response.sslSession()).thenReturn(java.util.Optional.of(session));
    authority.observeCertificate(response);
    assertEquals(
        "NOT_OBSERVED",
        authority.certificateSummary("https://127.0.0.1:8099").get("certificateExpiryClass"));
    org.mockito.Mockito.when(session.getPeerCertificates())
        .thenThrow(new javax.net.ssl.SSLPeerUnverifiedException("fixture"));
    telemetry.observe("https://127.0.0.1:8100", response);
    assertEquals(
        "NOT_OBSERVED", telemetry.summary("https://127.0.0.1:8100").get("certificateExpiryClass"));
  }

  @Test
  void observationFreshnessRetentionAndCapacityAreBounded() throws Exception {
    makeAuthority();
    issueLeaf(Instant.now().plus(Duration.ofDays(30)));
    var session = org.mockito.Mockito.mock(javax.net.ssl.SSLSession.class);
    org.mockito.Mockito.when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate("leaf.pem")});
    HttpResponse<?> response = org.mockito.Mockito.mock(HttpResponse.class);
    org.mockito.Mockito.when(response.sslSession()).thenReturn(java.util.Optional.of(session));
    Clock clock = org.mockito.Mockito.mock(Clock.class);
    Instant now = Instant.now();
    org.mockito.Mockito.when(clock.instant()).thenReturn(now);
    ExecutorCertificateTelemetry telemetry = new ExecutorCertificateTelemetry(clock);
    telemetry.observe("https://127.0.0.1:8098", response);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(6)));
    assertEquals(
        "OBSERVATION_STALE",
        telemetry.summary("https://127.0.0.1:8098").get("certificateExpiryClass"));
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plus(Duration.ofHours(25)));
    assertEquals(
        "NOT_OBSERVED", telemetry.summary("https://127.0.0.1:8098").get("certificateExpiryClass"));
    for (int port = 10000; port < 10513; port++)
      telemetry.observe("https://127.0.0.1:" + port, response);
    assertEquals(
        "NOT_OBSERVED", telemetry.summary("https://127.0.0.1:10000").get("certificateExpiryClass"));
    assertEquals(
        "VALID", telemetry.summary("https://127.0.0.1:10512").get("certificateExpiryClass"));
  }

  private HttpsServer startPeer(int port, AtomicInteger calls) throws Exception {
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext()));
    server.createContext(
        "/api/v1/catalog",
        exchange -> {
          calls.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, 12);
          exchange
              .getResponseBody()
              .write("{\"tasks\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          exchange.close();
        });
    server.start();
    return server;
  }

  private void makeAuthority() throws Exception {
    openssl(
        "req",
        "-x509",
        "-newkey",
        "ec",
        "-pkeyopt",
        "ec_paramgen_curve:prime256v1",
        "-nodes",
        "-keyout",
        "ca.key",
        "-out",
        "ca.pem",
        "-subj",
        "/CN=SC802 fixture CA",
        "-days",
        "2");
    openssl(
        "req",
        "-new",
        "-newkey",
        "ec",
        "-pkeyopt",
        "ec_paramgen_curve:prime256v1",
        "-nodes",
        "-keyout",
        "leaf.key",
        "-out",
        "leaf.csr",
        "-subj",
        "/CN=127.0.0.1");
    Files.writeString(directory.resolve("index"), "");
    Files.writeString(directory.resolve("serial"), "01\n");
    Files.writeString(
        directory.resolve("ca.cnf"),
        """
        [ca]
        default_ca = fixture
        [fixture]
        database = index
        serial = serial
        new_certs_dir = .
        certificate = ca.pem
        private_key = ca.key
        default_md = sha256
        policy = identity
        x509_extensions = server
        unique_subject = no
        [identity]
        commonName = supplied
        [server]
        basicConstraints = critical,CA:false
        keyUsage = critical,digitalSignature
        extendedKeyUsage = serverAuth
        subjectAltName = IP:127.0.0.1
        """);
  }

  private void issueLeaf(Instant expires) throws Exception {
    var format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    openssl(
        "ca",
        "-batch",
        "-config",
        "ca.cnf",
        "-in",
        "leaf.csr",
        "-out",
        "leaf.pem",
        "-notext",
        "-startdate",
        format.format(Instant.now().minusSeconds(60)),
        "-enddate",
        format.format(expires));
  }

  private void openssl(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("/usr/bin/openssl");
    command.addAll(java.util.List.of(arguments));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("openssl.log").toFile());
    builder.environment().clear();
    Process process = builder.start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("openssl fixture timed out");
    }
    assertEquals(
        0,
        process.exitValue(),
        () -> {
          try {
            return Files.readString(directory.resolve("openssl.log"));
          } catch (Exception e) {
            return "openssl fixture failed";
          }
        });
  }

  private X509Certificate certificate(String file) throws Exception {
    try (var input = Files.newInputStream(directory.resolve(file))) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private SSLContext serverContext() throws Exception {
    String pem =
        Files.readString(directory.resolve("leaf.key"))
            .replaceAll("-----[A-Z ]+-----", "")
            .replaceAll("\\s", "");
    var key =
        KeyFactory.getInstance("EC")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry(
        "fixture",
        key,
        "fixture".toCharArray(),
        new java.security.cert.Certificate[] {certificate("leaf.pem"), certificate("ca.pem")});
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "fixture".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }

  private HttpClient trustedClient() throws Exception {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("fixture-ca", certificate("ca.pem"));
    TrustManagerFactory trusts =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trusts.init(trust);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trusts.getTrustManagers(), null);
    return HttpClient.newBuilder()
        .sslContext(context)
        .connectTimeout(Duration.ofSeconds(3))
        .build();
  }
}
