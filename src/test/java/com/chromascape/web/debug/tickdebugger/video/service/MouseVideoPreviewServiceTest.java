package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Public preview state is truthful about settled and stuck work so clients stop polling. */
class MouseVideoPreviewServiceTest {

  private final MouseVideoFramePreviewRepository previews =
      mock(MouseVideoFramePreviewRepository.class);
  private final MouseVideoJobRepository jobs = mock(MouseVideoJobRepository.class);
  private final MouseVideoByteStore byteStore = mock(MouseVideoByteStore.class);
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  private MouseVideoPreviewService service;
  private MouseVideoFramePreview preview;
  private MouseVideoJob job;

  @BeforeEach
  void setUp() {
    service = new MouseVideoPreviewService(previews, byteStore, jobs);
    UUID sourceId = UUID.randomUUID();
    preview =
        new MouseVideoFramePreview(
            "owner",
            sourceId,
            UUID.randomUUID(),
            "request-1",
            "digest",
            "index",
            0L,
            "0",
            "0",
            "1",
            "30",
            MouseVideoFramePreview.METHOD_FRAME_PNG,
            100,
            100);
    ReflectionTestUtils.setField(preview, "id", UUID.randomUUID());
    job =
        new MouseVideoJob(
            sourceId,
            "owner",
            MouseVideoJob.KIND_PREVIEW,
            "request-1",
            "digest",
            preview.getAnalysisId(),
            preview.getId(),
            "preview:" + preview.getId());
    ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
    preview.setJobId(job.getId());
    when(previews.findByIdAndOwner(preview.getId(), "owner")).thenReturn(Optional.of(preview));
    when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
  }

  @Test
  void pendingPreviewOfQueuedJobStaysPending() {
    assertThat(service.get("owner", preview.getId()).get("state"))
        .isEqualTo(MouseVideoFramePreview.STATE_PENDING);
  }

  @Test
  void cancelledPreviewIsReportedCancelled() {
    preview.cancel();
    assertThat(service.get("owner", preview.getId()).get("state"))
        .isEqualTo(MouseVideoFramePreview.STATE_CANCELLED);
  }

  @Test
  void pendingPreviewWhoseJobNeedsReconciliationIsNotReportedPending() {
    job.setStatus(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(service.get("owner", preview.getId()).get("state"))
        .isEqualTo(MouseVideoPreviewService.STATE_RECONCILIATION_REQUIRED);
  }

  @Test
  void geometryRequiresOwnedMatchingReadyBytes() throws Exception {
    org.springframework.test.util.ReflectionTestUtils.setField(
        preview, "state", MouseVideoFramePreview.STATE_READY);
    org.springframework.test.util.ReflectionTestUtils.setField(preview, "sourceWidth", 160);
    org.springframework.test.util.ReflectionTestUtils.setField(preview, "sourceHeight", 96);
    org.springframework.test.util.ReflectionTestUtils.setField(
        preview, "checksum", "sha256:" + "a".repeat(64));
    org.springframework.test.util.ReflectionTestUtils.setField(preview, "sizeBytes", 1L);
    org.springframework.test.util.ReflectionTestUtils.setField(preview, "mediaType", "image/png");
    when(previews.findFirstByAnalysisIdAndFrameOrdinalAndStateOrderByCreatedAtDesc(
            preview.getAnalysisId(), 0, MouseVideoFramePreview.STATE_READY))
        .thenReturn(Optional.of(preview));
    var file = java.nio.file.Files.write(root.resolve("frame.png"), new byte[] {1});
    when(byteStore.locate(preview.getChecksum())).thenReturn(Optional.of(file));
    assertThat(
            service.requireFrameGeometry(
                "owner", preview.getSourceId(), preview.getAnalysisId(), 0, "index"))
        .isEqualTo(new MouseVideoPreviewService.FrameGeometry(160, 96));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.requireFrameGeometry(
                    "other", preview.getSourceId(), preview.getAnalysisId(), 0, "index"))
        .isInstanceOf(com.chromascape.web.debug.tickdebugger.video.MouseVideoException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.requireFrameGeometry(
                    "owner", UUID.randomUUID(), preview.getAnalysisId(), 0, "index"))
        .isInstanceOf(com.chromascape.web.debug.tickdebugger.video.MouseVideoException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.requireFrameGeometry(
                    "owner", preview.getSourceId(), preview.getAnalysisId(), 0, "different-index"))
        .isInstanceOf(com.chromascape.web.debug.tickdebugger.video.MouseVideoException.class);
    java.nio.file.Files.delete(file);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.requireFrameGeometry(
                    "owner", preview.getSourceId(), preview.getAnalysisId(), 0, "index"))
        .isInstanceOf(com.chromascape.web.debug.tickdebugger.video.MouseVideoException.class);
  }
}
