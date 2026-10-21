package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoMultipartReaderTest {

  private static final String BOUNDARY = "----chromascapeTestBoundary";
  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path root;

  @Test
  void slowStreamingCannotOutliveItsReservation() throws IOException {
    var clock = new java.util.concurrent.atomic.AtomicLong();
    var input =
        new MouseVideoMultipartReader.CountingInputStream(
            new ByteArrayInputStream(new byte[] {1, 2}), 10L, clock::get);
    assertThat(input.read()).isEqualTo(1);
    clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(10));
    assertThatThrownBy(input::read)
        .isInstanceOf(MouseVideoMultipartReader.MultipartLimitExceededException.class);
  }

  @Test
  void streamsOneFileAndOneMetadataPart() throws IOException {
    String requestId = UUID.randomUUID().toString();
    byte[] body = multipart(mediaPart("clip.mp4", "video-bytes"), metadataPart(requestId));
    MouseVideoMultipartReader.StagedUpload staged =
        MouseVideoMultipartReader.read(
            new ByteArrayInputStream(body),
            "multipart/form-data; boundary=" + BOUNDARY,
            root,
            mapper,
            MouseVideoMultipartReader.MAX_FILE_BYTES,
            MouseVideoMultipartReader.MAX_REQUEST_BYTES);

    assertThat(staged.sizeBytes()).isEqualTo("video-bytes".length());
    assertThat(staged.declaredMediaType()).isEqualTo("video/mp4");
    assertThat(staged.metadata().requestId()).isEqualTo(requestId);
    assertThat(Files.readString(staged.mediaPath())).isEqualTo("video-bytes");
  }

  @Test
  void rejectsDuplicateFilePart() throws IOException {
    byte[] body =
        multipart(
            mediaPart("a.mp4", "one"),
            mediaPart("b.mp4", "two"),
            metadataPart(UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                MouseVideoMultipartReader.read(
                    new ByteArrayInputStream(body),
                    "multipart/form-data; boundary=" + BOUNDARY,
                    root,
                    mapper,
                    MouseVideoMultipartReader.MAX_FILE_BYTES,
                    MouseVideoMultipartReader.MAX_REQUEST_BYTES))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void rejectsUnknownPart() {
    byte[] body =
        multipart("Content-Disposition: form-data; name=\"extra\"" + CRLF + CRLF + "x" + CRLF);
    assertThatThrownBy(
            () ->
                MouseVideoMultipartReader.read(
                    new ByteArrayInputStream(body),
                    "multipart/form-data; boundary=" + BOUNDARY,
                    root,
                    mapper,
                    MouseVideoMultipartReader.MAX_FILE_BYTES,
                    MouseVideoMultipartReader.MAX_REQUEST_BYTES))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsupported upload part");
  }

  @Test
  void rejectsOversizedFileWhileStreaming() {
    String content = "x".repeat(1024);
    byte[] body =
        multipart(mediaPart("clip.mp4", content), metadataPart(UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                MouseVideoMultipartReader.read(
                    new ByteArrayInputStream(body),
                    "multipart/form-data; boundary=" + BOUNDARY,
                    root,
                    mapper,
                    16L,
                    MouseVideoMultipartReader.MAX_REQUEST_BYTES))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("size limit");
  }

  @Test
  void missingMetadataStagingFileRemainsAStorageFailure() {
    Path missing = root.resolve("missing-upload-metadata.json");
    assertThatThrownBy(() -> MouseVideoMultipartReader.parseMetadata(missing, mapper))
        .isInstanceOf(NoSuchFileException.class)
        .isNotInstanceOf(MouseVideoMultipartReader.MultipartException.class);
  }

  @Test
  void invalidMetadataJsonIsSeparateFromStorageFailures() throws IOException {
    Path invalid = Files.writeString(root.resolve("invalid-metadata.json"), "{not-json");
    assertThatThrownBy(() -> MouseVideoMultipartReader.parseMetadata(invalid, mapper))
        .isInstanceOf(MouseVideoMultipartReader.MalformedMultipartException.class)
        .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
  }

  @Test
  void malformedMetadataIsATypedClientInputFailure() {
    byte[] body =
        multipart(
            mediaPart("clip.mp4", "video-bytes"),
            "Content-Disposition: form-data; name=\"metadata\""
                + CRLF
                + "Content-Type: application/json"
                + CRLF
                + CRLF
                + "{not-json"
                + CRLF);
    assertThatThrownBy(
            () ->
                MouseVideoMultipartReader.read(
                    new ByteArrayInputStream(body),
                    "multipart/form-data; boundary=" + BOUNDARY,
                    root,
                    mapper,
                    MouseVideoMultipartReader.MAX_FILE_BYTES,
                    MouseVideoMultipartReader.MAX_REQUEST_BYTES))
        .isInstanceOf(MouseVideoMultipartReader.MalformedMultipartException.class);
  }

  @Test
  void oversizedFileIsATypedLimitFailure() {
    String content = "x".repeat(1024);
    byte[] body =
        multipart(mediaPart("clip.mp4", content), metadataPart(UUID.randomUUID().toString()));
    assertThatThrownBy(
            () ->
                MouseVideoMultipartReader.read(
                    new ByteArrayInputStream(body),
                    "multipart/form-data; boundary=" + BOUNDARY,
                    root,
                    mapper,
                    16L,
                    MouseVideoMultipartReader.MAX_REQUEST_BYTES))
        .isInstanceOf(MouseVideoMultipartReader.MultipartLimitExceededException.class);
  }

  private static final String CRLF = "\r\n";

  private String mediaPart(String filename, String content) {
    return "Content-Disposition: form-data; name=\"file\"; filename=\""
        + filename
        + "\""
        + CRLF
        + "Content-Type: video/mp4"
        + CRLF
        + CRLF
        + content
        + CRLF;
  }

  private String metadataPart(String requestId) {
    String json =
        "{\"requestId\":\""
            + requestId
            + "\",\"declaredOrigin\":\"HUMAN\",\"taskKey\":\"fishing\"}";
    return "Content-Disposition: form-data; name=\"metadata\""
        + CRLF
        + "Content-Type: application/json"
        + CRLF
        + CRLF
        + json
        + CRLF;
  }

  private byte[] multipart(String... parts) {
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      builder.append("--").append(BOUNDARY).append(CRLF).append(part);
    }
    builder.append("--").append(BOUNDARY).append("--").append(CRLF);
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }
}
