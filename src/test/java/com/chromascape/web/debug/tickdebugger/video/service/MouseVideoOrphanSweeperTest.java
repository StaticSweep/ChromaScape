package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoOrphanSweeperTest {
  @TempDir Path root;

  @Test
  void concurrentReuseCannotRegisterBytesDeletedFromAnOlderReferenceSnapshot() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    MouseVideoByteStore store = new MouseVideoByteStore(properties);
    byte[] content = "reused-old-orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var original = store.stage(new ByteArrayInputStream(content), 100);
    Path path = store.publish(original);
    Files.setLastModifiedTime(path, FileTime.from(Instant.EPOCH));
    var replacement = store.stage(new ByteArrayInputStream(content), 100);
    var references = new AtomicReference<List<String>>(List.of());
    var failure = new AtomicReference<Throwable>();
    var attempted = new CountDownLatch(1);
    Thread publisher =
        new Thread(
            () -> {
              attempted.countDown();
              try {
                store.publish(replacement);
                references.set(List.of(original.checksum()));
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    var artifacts = mock(MouseVideoArtifactRefRepository.class);
    var previews = mock(MouseVideoFramePreviewRepository.class);
    when(previews.findDistinctChecksums()).thenReturn(List.of());
    when(artifacts.findDistinctChecksums())
        .thenAnswer(
            invocation -> {
              List<String> snapshot = references.get();
              publisher.start();
              assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
              Thread.sleep(200);
              return snapshot;
            });
    new MouseVideoOrphanSweeper(properties, artifacts, previews, store)
        .sweepOnce(Instant.now().minusSeconds(60), false);
    publisher.join(5_000);
    assertThat(publisher.isAlive()).isFalse();
    assertThat(failure.get()).isNull();
    assertThat(references.get()).containsExactly(original.checksum());
    assertThat(store.verify(original.checksum())).isTrue();
  }

  @Test
  void workerPublicationBatchProtectsTheGapBeforeReferenceCommit() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    MouseVideoByteStore writer = new MouseVideoByteStore(properties);
    MouseVideoByteStore reader = new MouseVideoByteStore(properties);
    byte[] content = new byte[] {7};
    var staged = writer.stage(new ByteArrayInputStream(content), 10);
    var refs = new AtomicReference<List<String>>(List.of());
    var published = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var artifacts = mock(MouseVideoArtifactRefRepository.class);
    var previews = mock(MouseVideoFramePreviewRepository.class);
    when(artifacts.findDistinctChecksums()).thenAnswer(invocation -> refs.get());
    when(previews.findDistinctChecksums()).thenReturn(List.of());
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var publisher =
          pool.submit(
              () -> {
                try (var batch = writer.publicationBatch()) {
                  Files.setLastModifiedTime(writer.publish(staged), FileTime.from(Instant.EPOCH));
                  published.countDown();
                  if (!commit.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("reference barrier timed out");
                  refs.set(List.of(staged.checksum()));
                }
                return null;
              });
      assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
      var collector =
          pool.submit(
              () ->
                  new MouseVideoOrphanSweeper(properties, artifacts, previews, reader)
                      .sweepOnce(Instant.now().minusSeconds(60), false));
      Thread.sleep(200);
      assertThat(collector.isDone()).isFalse();
      org.mockito.Mockito.verify(artifacts, org.mockito.Mockito.never()).findDistinctChecksums();
      commit.countDown();
      publisher.get(5, TimeUnit.SECONDS);
      assertThat(collector.get(5, TimeUnit.SECONDS)).isZero();
      assertThat(reader.verify(staged.checksum())).isTrue();
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void frozenTemplateRemainsReferencedBeforeAnalysisProducesArtifacts() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    MouseVideoByteStore store = new MouseVideoByteStore(properties);
    var template = store.stage(new ByteArrayInputStream(new byte[] {9}), 10);
    Files.setLastModifiedTime(store.publish(template), FileTime.from(Instant.EPOCH));
    var artifacts = mock(MouseVideoArtifactRefRepository.class);
    var previews = mock(MouseVideoFramePreviewRepository.class);
    when(artifacts.findDistinctChecksums()).thenReturn(List.of());
    when(previews.findDistinctChecksums()).thenReturn(List.of());
    when(artifacts.findDistinctTemplateChecksums()).thenReturn(List.of(template.checksum()));
    assertThat(
            new MouseVideoOrphanSweeper(properties, artifacts, previews, store)
                .sweepOnce(Instant.now().minusSeconds(60), false))
        .isZero();
    assertThat(store.verify(template.checksum())).isTrue();
  }

  @Test
  void livePreviewAndArtifactReferencesSurviveWhileAnOldOrphanIsCollected() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    MouseVideoByteStore store = new MouseVideoByteStore(properties);
    var artifacts = mock(MouseVideoArtifactRefRepository.class);
    var previews = mock(MouseVideoFramePreviewRepository.class);
    var source = store.stage(new ByteArrayInputStream(new byte[] {1}), 10);
    var preview = store.stage(new ByteArrayInputStream(new byte[] {2}), 10);
    var orphan = store.stage(new ByteArrayInputStream(new byte[] {3}), 10);
    for (var staged : List.of(source, preview, orphan)) {
      Files.setLastModifiedTime(store.publish(staged), FileTime.from(Instant.EPOCH));
    }
    when(artifacts.findDistinctChecksums()).thenReturn(List.of(source.checksum()));
    when(previews.findDistinctChecksums()).thenReturn(List.of(preview.checksum()));
    assertThat(
            new MouseVideoOrphanSweeper(properties, artifacts, previews, store)
                .sweepOnce(Instant.now().minusSeconds(60), false))
        .isEqualTo(1);
    assertThat(store.verify(source.checksum())).isTrue();
    assertThat(store.verify(preview.checksum())).isTrue();
    assertThat(store.locate(orphan.checksum())).isEmpty();
  }
}
