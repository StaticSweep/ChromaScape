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
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSchedulingRequestRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
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
class MouseVideoSchedulingServiceTest {

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoAnalysisRepository analyses;
  @Mock MouseVideoJobRepository jobs;
  @Mock MouseVideoJobEventRepository events;
  @Mock MouseVideoFramePreviewRepository previews;
  @Mock MouseVideoFrameIndexService frameIndex;
  @Mock MouseVideoByteStore byteStore;
  @Mock MouseVideoSchedulingRequestRepository requests;

  private MouseVideoSchedulingService service;
  private MouseVideoProperties properties;
  private MouseVideoSource source;
  private final UUID sourceId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    properties = new MouseVideoProperties();
    properties.setEnabled(true);
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
            requests,
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
  void disabledProcessingRejectsBothSchedulingRoutesBeforeAnyRepositoryAccess() {
    properties.setEnabled(false);
    assertThatThrownBy(
            () ->
                service.scheduleAnalysis(
                    "owner", UUID.randomUUID(), UUID.randomUUID().toString(), "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(e -> ((MouseVideoException) e).getCode())
        .isEqualTo(MouseVideoException.CODE_DISABLED);
    assertThatThrownBy(
            () ->
                service.schedulePreview(
                    "owner",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID().toString(),
                    0,
                    100,
                    100))
        .isInstanceOf(MouseVideoException.class)
        .extracting(e -> ((MouseVideoException) e).getCode())
        .isEqualTo(MouseVideoException.CODE_DISABLED);
    org.mockito.Mockito.verifyNoInteractions(jobs);
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
  void replacingTemplateBytesAtTheSamePathChangesTheAnalysisIdentity() throws Exception {
    Path template = root.resolve("cursor.png");
    Files.write(template, new byte[] {1, 2, 3});
    properties.setVisualCursorTemplate(template.toString());
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

    MouseVideoSchedulingService.Receipt first =
        service.scheduleAnalysis(
            "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", null);
    Files.write(template, new byte[] {9, 8, 7, 6});
    MouseVideoSchedulingService.Receipt second =
        service.scheduleAnalysis(
            "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", null);

    assertThat(second.requestDigest()).isNotEqualTo(first.requestDigest());
  }

  @Test
  void racedRecoveryRejectsAWinnerScheduledForDifferentContent() {
    MouseVideoJob winner =
        new MouseVideoJob(
            UUID.randomUUID(),
            "owner",
            MouseVideoJob.KIND_ANALYSIS,
            "request-raced",
            "sha256:" + "x".repeat(64));
    when(jobs.findByOwnerAndRequestId("owner", "request-raced"))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(analyses.findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
            any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(analyses.saveAndFlush(any(MouseVideoAnalysis.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoAnalysis analysis = invocation.getArgument(0);
              ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
              return analysis;
            });
    when(jobs.findFirstByAnalysisIdAndKindAndStatusIn(any(), anyString(), any()))
        .thenReturn(Optional.empty());
    when(jobs.saveAndFlush(any(MouseVideoJob.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("raced"));

    assertThatThrownBy(
            () -> service.scheduleAnalysis("owner", sourceId, "request-raced", "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
  }

  @Test
  void admissionIsSerializedPerOwnerAndCountsReconciliationOccupancy() {
    when(jobs.countByOwnerAndStatusIn(anyString(), any())).thenReturn(9L);
    when(analyses.findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
            any(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(analyses.saveAndFlush(any(MouseVideoAnalysis.class)))
        .thenAnswer(
            invocation -> {
              MouseVideoAnalysis analysis = invocation.getArgument(0);
              ReflectionTestUtils.setField(analysis, "id", UUID.randomUUID());
              return analysis;
            });
    when(jobs.findFirstByAnalysisIdAndKindAndStatusIn(any(), anyString(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.scheduleAnalysis(
                    "owner", sourceId, UUID.randomUUID().toString(), "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
    org.mockito.Mockito.verify(jobs).lockAdmission("mouse-video-admission:owner");
    org.mockito.ArgumentCaptor<List> statuses = org.mockito.ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(jobs)
        .countByOwnerAndStatusIn(org.mockito.ArgumentMatchers.eq("owner"), statuses.capture());
    assertThat(statuses.getValue()).contains(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
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
