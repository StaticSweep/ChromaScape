package com.chromascape.web.aiplayer.routine.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strict, bounded client for the SC-967 v2 candidate registry. */
@Component
public class CandidateRegistryClient {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final CertifiedCandidateV2Validator validator;

  @Value("${leeuwbot.candidate-registry.base-url:http://127.0.0.1:8081}")
  private String baseUrl;

  @Value("${leeuwbot.candidate-registry.token:}")
  private String token;

  /** Compatibility constructor for focused tests that instantiate the client directly. */
  public CandidateRegistryClient() {
    this(new CertifiedCandidateV2Validator());
  }

  @Autowired
  public CandidateRegistryClient(CertifiedCandidateV2Validator validator) {
    this.validator = validator;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String token() {
    return token;
  }

  public String lookup(String candidateId) {
    throw new RegistryException("REGISTRY_MANIFEST_HASH_REQUIRED", false);
  }

  /** Returns the literal manifest response bytes and its integrity header. */
  public RegistryManifestResponse lookupEvidence(String candidateId) {
    // A lookup without the hash of the persisted manifest cannot be safely reconciled: accepting
    // an arbitrary registry document would break the candidate identity binding. Keep this method
    // only as a source-compatible fail-closed seam for old callers; production callers must use
    // the hash-aware overload below.
    throw new RegistryException("REGISTRY_MANIFEST_HASH_REQUIRED", false);
  }

  /**
   * Looks up a candidate while binding the request to the exact manifest bytes already staged by
   * ChromaScape. The registry uses this header to reject a lookup that is not about the persisted
   * candidate document; the response header is checked again by the workflow readback verifier.
   */
  public RegistryManifestResponse lookupEvidence(String candidateId, String expectedManifestHash) {
    validateId(candidateId);
    if (expectedManifestHash == null || !expectedManifestHash.matches("sha256:[0-9a-f]{64}")) {
      throw new RegistryException("REGISTRY_MANIFEST_HASH_REQUIRED", false);
    }
    return sendManifest(
        "GET", "/api/v1/candidates/" + candidateId, null, null, expectedManifestHash);
  }

  public byte[] download(String candidateId) throws Exception {
    return downloadEvidence(candidateId).bytes();
  }

  /** Streams a bounded artifact response and aborts on a missing/oversized Content-Length. */
  public ArtifactDownload downloadEvidence(String candidateId) throws Exception {
    validateId(candidateId);
    HttpResponse<InputStream> response;
    try {
      response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(baseUrl + "/api/v1/candidates/" + candidateId + "/download"))
                  .timeout(Duration.ofSeconds(60))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
    } catch (Exception e) {
      throw new RegistryException("REGISTRY_NETWORK_UNCERTAIN", true, e);
    }
    try (InputStream stream = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new RegistryException("REGISTRY_REJECTED_" + response.statusCode(), false);
      }
      long length =
          contentLength(
              response,
              CertifiedCandidateV2Validator.MAX_ARTIFACT_BYTES,
              "REGISTRY_ARTIFACT_LENGTH_REQUIRED");
      byte[] bytes =
          readBounded(
              stream,
              length,
              CertifiedCandidateV2Validator.MAX_ARTIFACT_BYTES,
              "REGISTRY_ARTIFACT_TOO_LARGE");
      String headerId = response.headers().firstValue("X-Candidate-ID").orElse("");
      String headerHash = response.headers().firstValue("X-Artifact-SHA256").orElse("");
      if (!candidateId.equals(headerId) || !headerHash.equals(hash(bytes))) {
        throw new RegistryException("REGISTRY_ARTIFACT_HASH_MISMATCH", false);
      }
      return new ArtifactDownload(bytes, headerHash);
    } catch (RegistryException e) {
      throw e;
    } catch (Exception e) {
      throw new RegistryException("REGISTRY_ARTIFACT_STREAM_FAILED", true, e);
    }
  }

  public String register(Path manifest, Path artifact, Path sbom, Path certification)
      throws Exception {
    return new String(
        registerEvidence(manifest, artifact, sbom, certification).rawManifest(),
        StandardCharsets.UTF_8);
  }

  /**
   * Validates the exact staged bytes before posting and retains the exact response bytes. The
   * response body is deliberately limited to 1 MiB and must advertise the exact persisted manifest
   * hash in {@code X-Candidate-Manifest-SHA256}.
   */
  public RegistryManifestResponse registerEvidence(
      Path manifest, Path artifact, Path sbom, Path certification) throws Exception {
    byte[] manifestBytes =
        boundedFile(
            manifest, CertifiedCandidateV2Validator.MAX_MANIFEST_BYTES, "MANIFEST_TOO_LARGE");
    byte[] artifactBytes =
        boundedFile(
            artifact, CertifiedCandidateV2Validator.MAX_ARTIFACT_BYTES, "ARTIFACT_TOO_LARGE");
    byte[] sbomBytes =
        boundedFile(sbom, CertifiedCandidateV2Validator.MAX_SBOM_BYTES, "SBOM_TOO_LARGE");
    byte[] certificationBytes =
        boundedFile(
            certification, CandidateCertificationV1Validator.MAX_BYTES, "CERTIFICATION_TOO_LARGE");
    validator.validate(manifestBytes, certificationBytes, artifactBytes, sbomBytes);

    String boundary = "----sc967" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writePart(body, boundary, "manifest", manifest, manifestBytes);
    writePart(body, boundary, "artifact", artifact, artifactBytes);
    writePart(body, boundary, "sbom", sbom, sbomBytes);
    writePart(body, boundary, "certification", certification, certificationBytes);
    body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    String manifestHash = hash(manifestBytes);
    return sendManifest(
        "POST",
        "/api/v1/candidates",
        body.toByteArray(),
        "multipart/form-data; boundary=" + boundary,
        manifestHash);
  }

  private RegistryManifestResponse sendManifest(
      String method, String path, byte[] body, String contentType, String manifestHash) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Bearer " + token);
      if (contentType != null) builder.header("Content-Type", contentType);
      if (manifestHash != null) {
        builder.header("X-Candidate-Manifest-SHA256", manifestHash);
        if ("POST".equals(method)) builder.header("Idempotency-Key", manifestHash);
      }
      if ("POST".equals(method)) {
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
      } else {
        builder.GET();
      }
      HttpResponse<InputStream> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream stream = response.body()) {
        if (response.statusCode() / 100 != 2) {
          throw new RegistryException("REGISTRY_REJECTED_" + response.statusCode(), false);
        }
        long length =
            contentLength(
                response,
                CertifiedCandidateV2Validator.MAX_MANIFEST_BYTES,
                "REGISTRY_RESPONSE_LENGTH_REQUIRED");
        byte[] raw;
        try {
          raw =
              readBounded(
                  stream,
                  length,
                  CertifiedCandidateV2Validator.MAX_MANIFEST_BYTES,
                  "REGISTRY_RESPONSE_TOO_LARGE");
        } catch (IOException e) {
          throw new RegistryException("REGISTRY_RESPONSE_LENGTH_MISMATCH", false, e);
        }
        String responseManifestHash =
            response.headers().firstValue("X-Candidate-Manifest-SHA256").orElse("");
        if (!responseManifestHash.matches("sha256:[0-9a-f]{64}")
            || !responseManifestHash.equals(hash(raw))) {
          throw new RegistryException("REGISTRY_MANIFEST_HASH_HEADER_MISSING", false);
        }
        if (manifestHash != null && !manifestHash.equals(responseManifestHash)) {
          throw new RegistryException("REGISTRY_MANIFEST_HASH_HEADER_MISMATCH", false);
        }
        return new RegistryManifestResponse(raw, responseManifestHash, response.statusCode());
      }
    } catch (RegistryException e) {
      throw e;
    } catch (Exception e) {
      throw new RegistryException("REGISTRY_NETWORK_UNCERTAIN", true, e);
    }
  }

  private static long contentLength(HttpResponse<?> response, long limit, String missingCode) {
    String header = response.headers().firstValue("Content-Length").orElse(null);
    if (header == null || header.isBlank()) throw new RegistryException(missingCode, false);
    final long length;
    try {
      length = Long.parseLong(header);
    } catch (NumberFormatException e) {
      throw new RegistryException(missingCode, false);
    }
    if (length == 0) {
      throw new RegistryException(missingCode, false);
    }
    if (length < 0 || length > limit) {
      throw new RegistryException("REGISTRY_RESPONSE_TOO_LARGE", false);
    }
    return length;
  }

  private static byte[] readBounded(InputStream stream, long declared, long limit, String code)
      throws IOException {
    if (declared > limit) throw new RegistryException(code, false);
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(declared, 8192));
    byte[] buffer = new byte[8192];
    long total = 0;
    int count;
    while ((count = stream.read(buffer)) != -1) {
      total += count;
      if (total > limit || total > declared) throw new RegistryException(code, false);
      output.write(buffer, 0, count);
    }
    if (total != declared) throw new RegistryException("REGISTRY_RESPONSE_LENGTH_MISMATCH", false);
    return output.toByteArray();
  }

  private static byte[] boundedFile(Path path, long limit, String code) throws IOException {
    long size = Files.size(path);
    if (size < 0 || size > limit) throw new RegistryException(code, false);
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(size, 8192));
    byte[] buffer = new byte[8192];
    long total = 0;
    try (InputStream input = Files.newInputStream(path)) {
      // Read exactly the size observed before opening the file.  A final probe below catches a
      // file that grew after the initial size check; reading through a bounded stream also keeps a
      // concurrently replaced oversized file from being pulled into memory by readAllBytes().
      while (total < size) {
        int wanted = (int) Math.min(buffer.length, size - total);
        int count = input.read(buffer, 0, wanted);
        if (count < 0) throw new RegistryException(code, false);
        if (count == 0) continue;
        total += count;
        if (total > limit) throw new RegistryException(code, false);
        output.write(buffer, 0, count);
      }
      if (input.read() != -1) throw new RegistryException(code, false);
    }
    if (Files.size(path) != total) throw new RegistryException(code, false);
    return output.toByteArray();
  }

  private static void writePart(
      ByteArrayOutputStream body, String boundary, String name, Path path, byte[] bytes)
      throws IOException {
    String filename = path.getFileName().toString();
    String header =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"; filename=\""
            + filename
            + "\"\r\nContent-Type: "
            + contentType(path)
            + "\r\n\r\n";
    body.write(header.getBytes(StandardCharsets.UTF_8));
    body.write(bytes);
    body.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static String contentType(Path path) {
    try {
      String value = Files.probeContentType(path);
      if (value != null) return value;
    } catch (Exception ignored) {
      // Use the deterministic fallback below.
    }
    return path.getFileName().toString().endsWith(".jar")
        ? "application/java-archive"
        : "application/json";
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("cand_[0-9a-f]{24}")) {
      throw new RegistryException("INVALID_CANDIDATE_ID", false);
    }
  }

  static String hash(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public record RegistryManifestResponse(byte[] rawManifest, String manifestHash, int statusCode) {
    public RegistryManifestResponse {
      rawManifest = rawManifest == null ? new byte[0] : rawManifest.clone();
    }
  }

  public record ArtifactDownload(byte[] bytes, String sha256) {
    public ArtifactDownload {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }
  }

  public static final class RegistryException extends RuntimeException {
    private final boolean uncertain;

    RegistryException(String message, boolean uncertain) {
      super(message);
      this.uncertain = uncertain;
    }

    RegistryException(String message, boolean uncertain, Throwable cause) {
      super(message, cause);
      this.uncertain = uncertain;
    }

    public boolean isUncertain() {
      return uncertain;
    }
  }
}
