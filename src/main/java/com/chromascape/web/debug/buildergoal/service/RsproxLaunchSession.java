package com.chromascape.web.debug.buildergoal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/** Per-launch control lease. No account data or packet bodies cross this interface. */
final class RsproxLaunchSession implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final URI endpoint;
  private final String token;
  private String leaseId;
  private Map<String, Object> payload;
  private boolean retained;

  static RsproxLaunchSession connect() throws IOException {
    return new RsproxLaunchSession(Path.of(System.getProperty("user.home"), ".rsprox", "dashboard-control.json"));
  }

  RsproxLaunchSession(Path descriptor) throws IOException {
    try {
      if (Files.size(descriptor) > 4096) throw new IOException("Invalid RSProx descriptor");
      JsonNode node = JSON.readTree(Files.readString(descriptor));
      int port = node.path("port").asInt();
      token = node.path("token").asText();
      if (node.path("version").asInt() != 1 || port < 1 || port > 65535
          || !token.matches("[a-f0-9]{64}")) throw new IOException("Incompatible RSProx control interface");
      endpoint = URI.create("http://127.0.0.1:" + port + "/v1/launch");
      if (request("GET", Map.of()).path("version").asInt() != 1) {
        throw new IOException("Incompatible RSProx control protocol");
      }
    } catch (IOException e) {
      throw new IOException("Packet Capture requires a running compatible local RSProx. Open or update RSProx and retry.", e);
    }
  }

  void prepare(Path projectDir) throws IOException {
    Properties runtime = new Properties();
    Path manifest = projectDir.resolve("runelite-client/build/rsprox-buildergoal/runtime.properties");
    try (var input = Files.newInputStream(manifest)) { runtime.load(input); }
    String sourceHash = runtime.getProperty("sha256", "");
    if (!sourceHash.matches("[a-f0-9]{64}")) throw new IOException("Micro6 packet launch preparation is missing; update the launcher");
    JsonNode response = request("POST", Map.of("action", "prepare", "sourceSha256", sourceHash));
    leaseId = response.path("leaseId").asText();
    if (!leaseId.matches("[a-f0-9-]{36}")) throw new IOException("Invalid RSProx launch lease");
    Path patched = Path.of(response.path("injectedJar").asText()).toAbsolutePath().normalize();
    Path allowed = Path.of(System.getProperty("user.home"), ".rsprox", "clients", "temp").toRealPath();
    if (!patched.toRealPath().startsWith(allowed) || !Files.isRegularFile(patched)
        || !sourceHash.equals(response.path("sourceSha256").asText())) {
      throw new IOException("RSProx prepared an incompatible Micro6 artifact");
    }
    String hash = sha256(patched);
    if (!hash.equals(response.path("sha256").asText())) throw new IOException("RSProx artifact checksum mismatch");
    validateLocalUrl(response.path("javConfigUrl").asText());
    validateLocalUrl(response.path("worldsUrl").asText());
    payload = JSON.convertValue(response, new TypeReference<Map<String, Object>>() {});
  }

  static void validateLocalUrl(String value) throws IOException {
    URI uri;
    try { uri = URI.create(value); } catch (IllegalArgumentException e) { throw new IOException("Invalid RSProx URL", e); }
    if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getUserInfo() != null
        || uri.getFragment() != null || uri.getQuery() != null) throw new IOException("RSProx URL must use loopback");
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] bytes = new byte[65536]; int count;
        while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  Map<String, Object> payload() { return payload; }

  void attach(Process process) throws IOException {
    request("POST", Map.of("action", "attach", "leaseId", leaseId, "pid", process.pid()));
  }

  void retain() { retained = true; }

  private JsonNode request(String method, Map<String, Object> body) throws IOException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
    builder.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    try {
      HttpResponse<java.io.InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        byte[] bytes = input.readNBytes(16385);
        if (bytes.length > 16384) throw new IOException("Oversized RSProx control response");
        if (response.statusCode() == 409 && "unsupported_runtime".equals(JSON.readTree(bytes).path("error").asText())) {
          throw new IOException("Packet Capture is unavailable: RSProx supports the verified revision 240 client, but the current Micro6 injected client is incompatible. Provision the verified revision 240 Micro6 injected client and rerun launcher preparation.");
        }
        if (response.statusCode() != 200) throw new IOException("RSProx could not prepare Packet Capture (HTTP " + response.statusCode() + "). Check its runtime compatibility.");
        return JSON.readTree(bytes);
      }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("RSProx preparation interrupted", e); }
  }

  @Override public void close() {
    if (leaseId != null && !retained) {
      try { request("POST", Map.of("action", "release", "leaseId", leaseId)); }
      catch (IOException ignored) { /* RSProx expires unattached leases and observes owner exit. */ }
    }
  }
}
