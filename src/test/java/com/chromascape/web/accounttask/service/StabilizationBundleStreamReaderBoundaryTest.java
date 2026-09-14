package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
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
    out.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    out.append("Content-Type: text/plain\r\n\r\n");
    out.append("COMPLETE\r\n");
    out.append("--").append(boundary).append("--\r\n");
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a malformed boundary is rejected")
  void malformedBoundaryRejected() {
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(minimalBody("ok")),
                    "multipart/form-data; boundary=\"  ; broken",
                    tempDir.resolve("a")))
        .hasMessageContaining("boundary");
  }

  @Test
  @DisplayName("a parameter whose name only contains boundary is not accepted")
  void boundaryParameterNameCollisionRejected() {
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(minimalBody("ok")),
                    "multipart/form-data; notboundary=ok",
                    tempDir.resolve("boundary-name-collision")))
        .hasMessageContaining("boundary");
  }

  @Test
  @DisplayName("optional whitespace around a boundary parameter is accepted")
  void boundaryParameterWhitespaceAccepted() throws Exception {
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(minimalBody("ok")),
            "multipart/form-data; boundary = ok ",
            tempDir.resolve("boundary-ows"));
    assertThat(bundle.names()).contains("completeMarker");
  }

  @Test
  @DisplayName("bare LF header lines are rejected")
  void bareLfHeaderRejected() {
    String boundary = "b-" + UUID.randomUUID();
    String body =
        "--"
            + boundary
            + "\n"
            + "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\n"
            + "Content-Type: text/plain\n\nCOMPLETE\n"
            + "--"
            + boundary
            + "--";
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("bare-lf")))
        .hasMessageContaining("CRLF");
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
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: application/json\r\n\r\n");
    body.append("COMPLETE").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
        .append("a".repeat(64))
        .append(".exe\"\r\n");
    body.append("Content-Type: application/x-msdownload\r\n\r\n");
    body.append("data").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
      body.append(
          "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
      body.append("Content-Type: text/plain\r\n\r\n");
      body.append("COMPLETE\r\n");
    }
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
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
        .append("a".repeat(64))
        .append("\"\r\n");
    body.append("Content-Type: application/x-ndjson\r\n\r\n");
    body.append("data").append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("h")))
        .hasMessageContaining("checksum-addressed");
  }

  @Test
  @DisplayName("a pre-existing staging target is never overwritten")
  void preExistingTargetIsRejectedWithoutOverwrite() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    Path staging = tempDir.resolve("pre-existing-target");
    java.nio.file.Files.createDirectories(staging);
    Path target = staging.resolve("completeMarker-complete.marker");
    java.nio.file.Files.writeString(target, "sentinel");
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");

    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    staging))
        .isInstanceOf(java.io.IOException.class);
    assertThat(java.nio.file.Files.readString(target)).isEqualTo("sentinel");
  }

  @Test
  @DisplayName("a regular file cannot be used as the staging root")
  void stagingRootMustBeDirectory() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    Path file = tempDir.resolve("staging-file");
    java.nio.file.Files.writeString(file, "not a directory");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(minimalBody(boundary)),
                    "multipart/form-data; boundary=" + boundary,
                    file))
        .isInstanceOf(java.io.IOException.class);
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
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("i")))
        .hasMessageContaining("maximum header count");
  }

  @Test
  @DisplayName("a boundary-prefix that is not a real delimiter stays body data byte-for-byte")
  void boundaryPrefixStaysBodyData() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    // The literal \r\n--boundary-extra must remain part of the part body: it is not followed by
    // \r\n or --, so it is not a delimiter. The body bytes must be preserved EXACTLY (in their
    // original order), never reordered by a false boundary candidate.
    body.append("COMPLETE\r\n--").append(boundary).append("-not-a-delimiter\r\n");
    body.append("--").append(boundary).append("--\r\n");
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("j"));
    org.assertj.core.api.Assertions.assertThat(bundle.names()).contains("completeMarker");
    try {
      String content =
          new String(
              java.nio.file.Files.readAllBytes(bundle.part("completeMarker")),
              StandardCharsets.UTF_8);
      // The false-boundary prefix and every surrounding body byte must survive in the EXACT
      // original order, not merely as a substring somewhere in the output. The leading CRLF of the
      // closing delimiter belongs to the delimiter, not the body.
      org.assertj.core.api.Assertions.assertThat(content)
          .isEqualTo("COMPLETE\r\n--" + boundary + "-not-a-delimiter");
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  @DisplayName("a closing boundary -- suffix succeeds")
  void closingBoundarySuffix() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("k"));
    org.assertj.core.api.Assertions.assertThat(bundle.names()).contains("completeMarker");
  }

  @Test
  @DisplayName("a filename= value cannot satisfy name= (token-exact disposition parsing)")
  void filenameCannotSatisfyName() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    // name= is absent; only filename= is present. Substring matching would let name= match inside
    // filename=, so the part must be rejected as missing its name.
    body.append("Content-Disposition: form-data; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("l")))
        .hasMessageContaining("missing a name");
  }

  @Test
  @DisplayName("quoted disposition parameters parse with escaped characters")
  void quotedDispositionParameters() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("m"));
    org.assertj.core.api.Assertions.assertThat(bundle.names()).contains("completeMarker");
  }

  @Test
  @DisplayName("an unterminated quoted disposition parameter is rejected")
  void malformedQuotedDispositionRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("malformed-quoted")))
        .hasMessageContaining("quoted Content-Disposition");
  }

  @Test
  @DisplayName("a closing boundary at EOF (no trailing CRLF) is valid")
  void closingBoundaryAtEofValid() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--");
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("n"));
    org.assertj.core.api.Assertions.assertThat(bundle.names()).contains("completeMarker");
  }

  @Test
  @DisplayName("a closing boundary followed by garbage is rejected")
  void closingBoundaryGarbageRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--garbage\r\n");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("o")))
        .hasMessageContaining("closing boundary");
  }

  @Test
  @DisplayName("trailing data after a closing CRLF is rejected")
  void trailingDataAfterClosingRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append(
        "Content-Disposition: form-data; name=\"completeMarker\"; filename=\"complete.marker\"\r\n");
    body.append("Content-Type: text/plain\r\n\r\n");
    body.append("COMPLETE\r\n");
    body.append("--").append(boundary).append("--\r\n");
    body.append("trailing-data");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("p")))
        .hasMessageContaining("trailing data");
  }

  @Test
  @DisplayName("an initial closing boundary at EOF (empty bundle) is valid")
  void initialClosingBoundaryAtEofValid() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("--").append(boundary).append("--");
    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("q"));
    org.assertj.core.api.Assertions.assertThat(bundle.names()).isEmpty();
  }

  @Test
  @DisplayName("trailing data after an initial closing boundary is rejected")
  void initialClosingBoundaryTrailingDataRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder();
    body.append("--").append(boundary).append("\r\n");
    body.append("--").append(boundary).append("--\r\n");
    body.append("trailing");
    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("r")))
        .hasMessageContaining("trailing data");
  }

  @Test
  @DisplayName("exactly MAX_PARTS ordinary parts are accepted before the closing boundary")
  void exactMaximumPartCountAccepted() throws Exception {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder("--").append(boundary).append("\r\n");
    for (int i = 0; i < StabilizationBundleStreamReader.MAX_PARTS; i++) {
      body.append("Content-Disposition: form-data; name=\"artifacts\"; filename=\"sha256_")
          .append(String.format("%064x", i))
          .append(".log\"\r\n")
          .append("Content-Type: text/plain\r\n\r\n")
          .append("x\r\n--")
          .append(boundary)
          .append("\r\n");
    }
    // Replace the delimiter after the final part with the closing form.
    body.setLength(body.length() - (boundary.length() + 4));
    body.append("--").append(boundary).append("--\r\n");

    StabilizationBundleStreamReader.StagedBundle bundle =
        StabilizationBundleStreamReader.read(
            new java.io.ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8)),
            "multipart/form-data; boundary=" + boundary,
            tempDir.resolve("maximum-parts"));
    org.assertj.core.api.Assertions.assertThat(bundle.artifacts())
        .hasSize(StabilizationBundleStreamReader.MAX_PARTS);
  }

  @Test
  @DisplayName("the part after MAX_PARTS is rejected")
  void partCountAboveMaximumRejected() {
    String boundary = "b-" + UUID.randomUUID();
    StringBuilder body = new StringBuilder("--").append(boundary).append("\r\n");
    for (int i = 0; i < StabilizationBundleStreamReader.MAX_PARTS + 1; i++) {
      body.append("Content-Disposition: form-data; name=\"artifacts\"; filename=\"sha256_")
          .append(String.format("%064x", i))
          .append(".log\"\r\n")
          .append("Content-Type: text/plain\r\n\r\n")
          .append("x\r\n--")
          .append(boundary)
          .append("\r\n");
    }
    body.setLength(body.length() - (boundary.length() + 4));
    body.append("--").append(boundary).append("--\r\n");

    assertThatThrownBy(
            () ->
                StabilizationBundleStreamReader.read(
                    new java.io.ByteArrayInputStream(
                        body.toString().getBytes(StandardCharsets.UTF_8)),
                    "multipart/form-data; boundary=" + boundary,
                    tempDir.resolve("above-maximum-parts")))
        .hasMessageContaining("maximum part count");
  }
}
