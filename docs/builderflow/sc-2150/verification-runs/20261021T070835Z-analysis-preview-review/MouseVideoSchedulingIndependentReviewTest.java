package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Identity, coalescing and conflict rules for durable analysis/preview scheduling. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoSchedulingIndependentReviewTest {

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoJobRepository jobs;
  @Mock MouseVideoJobEventRepository events;
  @Mock MouseVideoFramePreviewRepository previews;
  @Mock MouseVideoFrameIndexService frameIndex;
  @Mock MouseVideoByteStore byteStore;

  private MouseVideoSchedulingService service;
  private MouseVideoSource source;
  private final UUID sourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setWaitingSlots(8);
    when(byteStore.locate(anyString())).thenReturn(Optional.of(root.resolve("media")));
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service =
        new MouseVideoSchedulingService(
            properties,
            sources,
            analyses,
            jobs,
            events,
            previews,
            frameIndex,
            byteStore,
            new ObjectMapper(),
            transactions);
    source = new MouseVideoSource("owner", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal("sha256:" + "a".repeat(64), 100L, "video/mp4");
    source.qualifyOriginal("video/mp4");
    when(sources.findByIdAndOwner(sourceId, "owner")).thenReturn(Optional.of(source));
    when(jobs.findByOwnerAndRequestId(anyString(), anyString())).thenReturn(Optional.empty());
    when(jobs.countByOwnerAndStatusIn(anyString(), any())).thenReturn(0L);
    when(jobs.saveAndFlush(any(MouseVideoJob.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoJob job = invocation.getArgument(0);
              ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
              return job;
            });
    when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void equivalentAnalysisContentCoalescesOntoOnePendingAnalysisAndJob() {
    UUID analysisId = UUID.randomUUID();
    when(analyses.findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
            any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(analyses.saveAndFlush(any(MouseVideoAnalysis.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoAnalysis analysis = invocation.getArgument(0);
              ReflectionTestUtils.setField(analysis, "id", analysisId);
              return analysis;
            });
    when(jobs.findFirstByAnalysisIdAndKindAndStatusIn(any(), anyString(), any()))
        .thenReturn(Optional.empty());

    MouseVideoSchedulingService.Receipt receipt =
        service.scheduleAnalysis(
            "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", "pack-1");

    assertThat(receipt.state()).isEqualTo("PENDING");
    assertThat(receipt.analysisId()).isEqualTo(analysisId);
    assertThat(receipt.jobId()).isNotNull();
  }

  @Test
  void completedAnalysisIsReturnedReadyWithoutCreatingAnotherJob() {
    UUID analysisId = UUID.randomUUID();
    MouseVideoAnalysis completed =
        new MouseVideoAnalysis(
            sourceId,
            "owner",
            source.getOriginalChecksum(),
            "visual/v1",
            null,
            "sha256:" + "c".repeat(64),
            "sha256:" + "t".repeat(64));
    ReflectionTestUtils.setField(completed, "id", analysisId);
    completed.complete("ready", "sha256:" + "m".repeat(64));
    when(analyses.findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
            any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(completed));

    MouseVideoSchedulingService.Receipt receipt =
        service.scheduleAnalysis(
            "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", null);

    assertThat(receipt.state()).isEqualTo("READY");
    assertThat(receipt.analysisId()).isEqualTo(analysisId);
    assertThat(receipt.jobId()).isNull();
  }

  @Test
  void previewRequestIsPinnedToTheFrozenOrdinalAndPts() {
    UUID analysisId = UUID.randomUUID();
    stubAnalysis(analysisId);
    when(frameIndex.requireIndex("owner", sourceId, analysisId))
        .thenReturn(
            new MouseVideoFrameIndexService.FrozenFrameIndex(
                "sha256:" + "i".repeat(64),
                "frame-index/v2",
                true,
                "1/10",
                "1",
                "10",
                List.of(
                    new MouseVideoFrameIndexService.Frame("0", "0.0", "0.1", true),
                    new MouseVideoFrameIndexService.Frame("7", "0.7", "0.1", false))));
    when(previews.findByOwnerAndRequestId(anyString(), anyString())).thenReturn(Optional.empty());
    when(previews
            .findByAnalysisIdAndIndexChecksumAndFrameOrdinalAndPreviewMethodAndMaxWidthAndMaxHeight(
                any(), anyString(), anyLong(), anyString(), anyInt(), anyInt()))
        .thenReturn(Optional.empty());
    when(previews.saveAndFlush(any(MouseVideoFramePreview.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoFramePreview preview = invocation.getArgument(0);
              ReflectionTestUtils.setField(preview, "id", UUID.randomUUID());
              return preview;
            });
    when(jobs.findFirstByPreviewIdAndStatusIn(any(), any())).thenReturn(Optional.empty());

    MouseVideoSchedulingService.Receipt receipt =
        service.schedulePreview(
            "owner", sourceId, analysisId, UUID.randomUUID().toString(), 1L, 640, 360);

    assertThat(receipt.state()).isEqualTo("PENDING");
    assertThat(receipt.frameOrdinal()).isEqualTo(1L);
    assertThat(receipt.framePts()).isEqualTo("7");
    assertThat(receipt.previewId()).isNotNull();
  }

  @Test
  void reusingARequestIdentifierWithDifferentContentIsAConflict() {
    UUID analysisId = UUID.randomUUID();
    stubAnalysis(analysisId);
    MouseVideoJob existing =
        new MouseVideoJob(
            sourceId,
            "owner",
            MouseVideoJob.KIND_ANALYSIS,
            "request-1",
            "sha256:" + "x".repeat(64));
    when(jobs.findByOwnerAndRequestId("owner", "request-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> service.scheduleAnalysis("owner", sourceId, "request-1", "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("different content");
  }

  @Test
  void unqualifiedSourceCannotBeScheduled() {
    source.setRevision(1L);
    MouseVideoSource unqualified = new MouseVideoSource("owner", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(unqualified, "id", sourceId);
    unqualified.bindOriginal("sha256:" + "b".repeat(64), 10L, "application/octet-stream");
    when(sources.findByIdAndOwner(sourceId, "owner")).thenReturn(Optional.of(unqualified));

    assertThatThrownBy(
            () ->
                service.scheduleAnalysis(
                    "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("not qualified");
  }


  @Test
  void sandboxCannotOverwriteAnotherJobsOriginal() throws Exception {
    var properties = new MouseVideoProperties();
    var mediaRoot = root.resolve("media-root");
    java.nio.file.Files.createDirectories(mediaRoot.resolve("jobs/current"));
    java.nio.file.Files.createDirectories(mediaRoot.resolve("bytes"));
    var victim = java.nio.file.Files.writeString(mediaRoot.resolve("bytes/another-owner-original"), "IMMUTABLE ORIGINAL");
    properties.setDirectory(mediaRoot.toString());
    properties.setRestrictedLauncher(true);
    var launcher = new MouseVideoRestrictedLauncher(properties);
    org.junit.jupiter.api.Assumptions.assumeTrue(launcher.available(), "bwrap required");
    var process = new ProcessBuilder(launcher.wrap(List.of("/usr/bin/truncate", "-s", "0", victim.toString())))
        .redirectErrorStream(true).start();
    assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(java.nio.file.Files.readString(victim)).isEqualTo("IMMUTABLE ORIGINAL");
  }

  @Test
  void racedRequestMustNotReturnAnotherBodyOrSource() {
    var requestId = UUID.randomUUID().toString();
    var winner = new MouseVideoJob(UUID.randomUUID(), "owner", MouseVideoJob.KIND_ANALYSIS,
        requestId, "sha256:different-request-body", UUID.randomUUID(), null, "analysis:winner");
    ReflectionTestUtils.setField(winner, "id", UUID.randomUUID());
    when(jobs.findByOwnerAndRequestId("owner", requestId)).thenReturn(Optional.empty(), Optional.of(winner));
    when(analyses.saveAndFlush(any(MouseVideoAnalysis.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("concurrent request winner"));
    assertThatThrownBy(() -> service.scheduleAnalysis("owner", sourceId, requestId, "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(e -> ((MouseVideoException)e).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void changingTemplateBytesMustChangeAnalysisIdentity() throws Exception {
    var properties = (MouseVideoProperties) ReflectionTestUtils.getField(service, "properties");
    var template = root.resolve("cursor.png");
    var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    javax.imageio.ImageIO.write(image, "png", template.toFile());
    properties.setVisualCursorTemplate(template.toString());
    var completed = new MouseVideoAnalysis(sourceId, "owner", source.getOriginalChecksum(),
        "visual/v1", "pack", "config", "toolchain");
    ReflectionTestUtils.setField(completed, "id", UUID.randomUUID());
    completed.complete("ready", "manifest");
    when(analyses.findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(completed));
    var first = service.scheduleAnalysis("owner", sourceId, UUID.randomUUID().toString(), "visual/v1", "pack");
    image.setRGB(0, 0, 0x00ffffff);
    javax.imageio.ImageIO.write(image, "png", template.toFile());
    var changed = service.scheduleAnalysis("owner", sourceId, UUID.randomUUID().toString(), "visual/v1", "pack");
    assertThat(changed.requestDigest()).isNotEqualTo(first.requestDigest());
  }

  private void stubAnalysis(UUID analysisId) {
    MouseVideoAnalysis analysis =
        new MouseVideoAnalysis(
            sourceId,
            "owner",
            source.getOriginalChecksum(),
            "visual/v1",
            null,
            "sha256:" + "c".repeat(64),
            "sha256:" + "t".repeat(64));
    ReflectionTestUtils.setField(analysis, "id", analysisId);
    when(analyses.findByIdAndOwner(analysisId, "owner")).thenReturn(Optional.of(analysis));
  }
}
