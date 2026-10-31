package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Provider-process regressions: stalled downloads must hit the deadline and provider failures map.
 */
class MouseVideoYouTubeAcquirerTest {

  @TempDir Path root;

  private MouseVideoProperties properties;
  private MouseVideoByteStore byteStore;

  @BeforeEach
  void setUp() throws Exception {
    properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("store").toString());
    properties.setAcquireBudgetSeconds(1L);
    properties.setChildShutdownBudgetSeconds(2L);
    byteStore = new MouseVideoByteStore(properties);
  }

  @Test
  void stalledDownloaderHitsIndependentDeadline() throws Exception {
    Path downloader = script("stalled.sh", "#!/bin/sh\nexec sleep 300\n");
    configure(downloader);

    assertThatThrownBy(
            () ->
                acquirer()
                    .acquire(
                        root.resolve("workspace"),
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        "dQw4w9WgXcQ",
                        new MouseVideoCancellation()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_WORKER_TIMEOUT);
  }

  @Test
  void authenticationFailureMapsToTypedProviderOutcome() throws Exception {
    Path downloader =
        script("private.sh", "#!/bin/sh\necho 'ERROR: This video is private'\nexit 1\n");
    configure(downloader);

    assertThatThrownBy(
            () ->
                acquirer()
                    .acquire(
                        root.resolve("workspace-private"),
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        "dQw4w9WgXcQ",
                        new MouseVideoCancellation()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_SOURCE_AUTH_REQUIRED);
  }

  private MouseVideoYouTubeAcquirer acquirer() {
    return new MouseVideoYouTubeAcquirer(
        properties, byteStore, new MouseVideoProcessRunner(properties));
  }

  private void configure(Path downloader) throws Exception {
    properties.setYoutubeDownloader(downloader.toString());
    properties.setYoutubeDownloaderSha256(byteStore.digestFile(downloader, 1024 * 1024));
  }

  private Path script(String name, String body) throws Exception {
    Path script = root.resolve(name);
    Files.writeString(script, body);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }
}
