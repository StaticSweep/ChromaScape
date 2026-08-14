package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CandidateRegistryClient {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper = new ObjectMapper();
  @Value("${leeuwbot.candidate-registry.base-url:http://127.0.0.1:8081}") private String baseUrl;
  @Value("${leeuwbot.candidate-registry.token:}") private String token;

  public String lookup(String candidateId) {
    validateId(candidateId);
    return send("GET", "/api/v1/candidates/" + candidateId, null, "application/json", null);
  }

  public byte[] download(String candidateId) throws Exception {
    validateId(candidateId);
    JsonNode manifest = mapper.readTree(lookup(candidateId)).path("manifest");
    if (!manifest.has("jar_sha256")) manifest = manifest.path("candidate");
    String expected = manifest.path("jar_sha256").asText();
    HttpResponse<byte[]> response = sendBytes("/api/v1/candidates/" + candidateId + "/download");
    if (response.statusCode() / 100 != 2) throw new RegistryException("REGISTRY_REJECTED", false);
    String headerId = response.headers().firstValue("X-Candidate-ID").orElse("");
    String headerHash = response.headers().firstValue("X-Artifact-SHA256").orElse("");
    if (!candidateId.equals(headerId) || !expected.equals(headerHash) || !expected.equals(sha256(response.body()))) {
      throw new RegistryException("REGISTRY_ARTIFACT_HASH_MISMATCH", false);
    }
    return response.body();
  }

  public String register(Path manifest, Path artifact, Path sbom, Path certification) throws Exception {
    String boundary = "----sc967" + UUID.randomUUID();
    var body = new java.io.ByteArrayOutputStream();
    for (PathPart part : new PathPart[]{new PathPart("manifest", manifest), new PathPart("artifact", artifact), new PathPart("sbom", sbom), new PathPart("certification", certification)}) {
      String filename = part.path().getFileName().toString();
      String type = contentType(part.path());
      body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      body.write(Files.readAllBytes(part.path()));
      body.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    body.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String idempotency = sha256(Files.readAllBytes(manifest));
    return send("POST", "/api/v1/candidates", body.toByteArray(), "multipart/form-data; boundary=" + boundary, idempotency);
  }

  private String send(String method, String path, byte[] body, String contentType, String idempotency) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).header("Content-Type", contentType);
      if (idempotency != null) builder.header("Idempotency-Key", idempotency);
      if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)); else builder.GET();
      HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) throw new RegistryException("REGISTRY_REJECTED_" + response.statusCode(), false);
      return response.body();
    } catch (RegistryException e) { throw e; }
    catch (Exception e) { throw new RegistryException("REGISTRY_NETWORK_UNCERTAIN", true, e); }
  }

  private HttpResponse<byte[]> sendBytes(String path) {
    try {
      return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (Exception e) { throw new RegistryException("REGISTRY_NETWORK_UNCERTAIN", true, e); }
  }

  private static String contentType(Path path) {
    try { String value = Files.probeContentType(path); if (value != null) return value; } catch (Exception ignored) { }
    return path.getFileName().toString().endsWith(".jar") ? "application/java-archive" : "application/json";
  }
  private static void validateId(String id) { if (id == null || !id.matches("cand_[0-9a-f]{24}")) throw new RegistryException("INVALID_CANDIDATE_ID", false); }
  private static String sha256(byte[] bytes) {
    try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder("sha256:"); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString(); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  private record PathPart(String name, Path path) { }
  public static final class RegistryException extends RuntimeException {
    private final boolean uncertain;
    RegistryException(String message, boolean uncertain) { super(message); this.uncertain = uncertain; }
    RegistryException(String message, boolean uncertain, Throwable cause) { super(message, cause); this.uncertain = uncertain; }
    public boolean isUncertain() { return uncertain; }
  }
}
