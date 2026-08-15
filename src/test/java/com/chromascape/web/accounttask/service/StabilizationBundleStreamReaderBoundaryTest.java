package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounded streaming reader boundary cases (SC-572): malformed boundaries, traversal, duplicate and
 * unsupported parts, content-type enforcement, part-count and total-byte limits are all rejected
 * safely while the body streams, never buffering it in memory.
 */
class StabilizationBundleStreamReaderBoundaryTest {

  @TempDir Path tempDir;

  private byte[] minimalBody(String boundary) {
    StringBuilder out = new StringBuilder();
    out.append("--").append(boundary).append("\r\n");
    out.append("Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    out.append("Content-Type: text/plain\r\n\r\n");
    out.append("COMPLETE\r\n");
    out.append("--").append(boundary).append("--\r\n");
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a malformed boundary is rejected")
  void malformedBoundaryRejected() {
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(minimalBody("ok")),
        "multipart/form-data; boundary=\"  ; broken",
        tempDir.resolve("a")))
        .hasMessageContaining("boundary");
  }

  @Test
  @DisplayName("an unsupported part name is rejected")
  void unsupportedPartRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"evil\"; filename=\"x\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("data\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("b")))
        .hasMessageContaining("unsupported report bundle part");
  }

  @Test
  @DisplayName("a JSON part with a non-JSON content type is rejected")
  void jsonPartRequiresJsonContentType() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"plan\"; filename=\"plan.json\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("{}").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("c")))
        .hasMessageContaining("must be application/json");
  }

  @Test
  @DisplayName("the completeMarker part must be text/plain")
  void markerRequiresPlainTextContentType() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: application/json\r\n\r\n");
    body.append("COMPLETE").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("d")))
        .hasMessageContaining("completeMarker part must be text/plain");
  }

  @Test
  @DisplayName("an artifact part with an unsupported media type is rejected")
  void artifactMediaTypeAllowlisted() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"artifacts\"; filename=\"sha256_")
        .append("a".repeat(64)).append(".exe\"\r\n");
    body.append("Content-Type: application/x-msdownload\r\n\r\n");
    body.append("data").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("e")))
        .hasMessageContaining("unsupported artifact media type");
  }

  @Test
  @DisplayName("duplicate required parts are rejected")
  void duplicateRequiredPartRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 2; i++) {
      body.append("--").append(boundary).append("\r\n");
      body.append("Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
      body.append("Content-Type: text/plain\r\n\r\n");
      body.append("COMPLETE\r\n");
    }
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("f")))
        .hasMessageContaining("duplicate report bundle part");
  }

  @Test
  @DisplayName("a traversal artifact filename is rejected")
  void traversalRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"artifacts\"; filename=\"../../evil\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("data").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    // Traversal is rejected either because the name is not checksum-addressed or because sanitize
    // refuses separators; either stable client error is a safe rejection.
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("g")))
        .hasMessageMatching(".*(unsafe report bundle part filename|checksum-addressed).*");
  }

  @Test
  @DisplayName("an artifact filename without a valid extension is rejected")
  void artifactFilenameWithoutExtensionRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("Content-Disposition: form-data; name=\"artifacts\"; filename=\"sha256_")
        .append("a".repeat(64)).append("\"\r\n");
    body.append("Content-Type: application/x-ndjson\r\n\r\n");
    body.append("data").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("h")))
        .hasMessageContaining("checksum-addressed");
  }

  @Test
  @DisplayName("a part with too many header lines is rejected")
  void tooManyHeadersRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    for (int i = 0; i < StabilizationBundleStreamReader.MAX_HEADERS_PER_PART + 1; i++) {
      body.append("X-Custom-").append(i).append(": value\r\n");
    }
    body.append("Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(() -> StabilizationBundleStreamReader.read(
        new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
        "multipart/form-data; boundary=" + boundary,
        tempDir.resolve("i")))
        .hasMessageContaining("maximum header count");
  }
}
