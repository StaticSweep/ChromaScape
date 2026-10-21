package com.chromascape.web.debug.tickdebugger.video.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoByteStoreTest {

  @TempDir Path root;

  private MouseVideoByteStore store;

  @BeforeEach
  void setUp() throws IOException {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    store = new MouseVideoByteStore(properties);
  }

  @Test
  void stagesAndPublishesContentAddressedBytes() throws IOException {
    byte[] content = "chromascape-external-video".getBytes(StandardCharsets.UTF_8);
    MouseVideoByteStore.StagedArtifact staged =
        store.stage(new ByteArrayInputStream(content), 1024L);
    assertThat(staged.sizeBytes()).isEqualTo(content.length);
    assertThat(staged.checksum()).isEqualTo(sha256(content));

    Path published = store.publish(staged);
    assertThat(Files.exists(published)).isTrue();
    assertThat(published).isEqualTo(store.finalPath(staged.checksum()));
    assertThat(store.verify(staged.checksum())).isTrue();
  }

  @Test
  void rejectsBytesOverTheConfiguredLimitAndCleansStaging() throws IOException {
    byte[] content = new byte[4096];
    Path stagingBefore = root.resolve("staging");
    assertThatThrownBy(() -> store.stage(new ByteArrayInputStream(content), 100L))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_MEDIA_LIMIT);
    try (var entries = Files.list(stagingBefore)) {
      assertThat(entries.toList()).isEmpty();
    }
  }

  @Test
  void deduplicatesIdenticalContentAndDetectsMismatch() throws IOException {
    byte[] content = "same-bytes".getBytes(StandardCharsets.UTF_8);
    Path first = store.publish(store.stage(new ByteArrayInputStream(content), 1024L));
    Path second = store.publish(store.stage(new ByteArrayInputStream(content), 1024L));
    assertThat(first).isEqualTo(second);

    Files.write(first, "tampered".getBytes(StandardCharsets.UTF_8));
    assertThat(store.verify(sha256(content))).isFalse();
  }

  @Test
  void adoptsAnAlreadyHashedStagedFile() throws IOException {
    byte[] content = "streamed-intake".getBytes(StandardCharsets.UTF_8);
    Path directory = store.newStagingDir();
    Path file = directory.resolve("upload-media");
    Files.write(file, content);
    String checksum = sha256(content);

    MouseVideoByteStore.StagedArtifact adopted = store.adopt(file, checksum, content.length);
    Path published = store.publish(adopted);
    assertThat(Files.exists(published)).isTrue();
    assertThat(store.verify(checksum)).isTrue();
  }

  @Test
  void rejectsMalformedContentAddress() {
    assertThatThrownBy(() -> store.finalPath("sha256:not-hex"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
