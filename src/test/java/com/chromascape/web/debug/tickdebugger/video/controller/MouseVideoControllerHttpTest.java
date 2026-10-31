package com.chromascape.web.debug.tickdebugger.video.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoMultipartReader;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoCatalogService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoExportService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoFrameIndexService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoImportService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoJobService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoObservationsService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoPreviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReconciliationService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRequestGuard;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRestrictedLauncher;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewSessionService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoSchedulingService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoTelemetry;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWorkerRegistry;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWriteNonceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-level regressions for the SC-2150 review defects: analysis-pinned derivative media, truthful
 * cancellation status codes, and typed multipart failures. These drive the real controller and
 * multipart reader through MockMvc; database/race behavior is covered separately.
 */
class MouseVideoControllerHttpTest {

  @TempDir Path root;

  private MockMvc mvc;
  private MouseVideoController controller;
  private MouseVideoImportService imports;
  private MouseVideoCatalogService catalog;
  private MouseVideoJobService jobService;
  private MouseVideoWorkerRegistry workerRegistry;
  private MouseVideoFrameIndexService frameIndexService;
  private MouseVideoObservationsService observationsService;
  private MouseVideoReviewService reviewService;
  private MouseVideoSchedulingService schedulingService;
  private MouseVideoPreviewService previewService;
  private MouseVideoReconciliationService reconciliation;

  @BeforeEach
  void setUp() throws Exception {
    imports = mock(MouseVideoImportService.class);
    when(imports.intakeFileLimit()).thenReturn(512L * 1024 * 1024);
    catalog = mock(MouseVideoCatalogService.class);
    jobService = mock(MouseVideoJobService.class);
    workerRegistry = mock(MouseVideoWorkerRegistry.class);
    frameIndexService = mock(MouseVideoFrameIndexService.class);
    observationsService = mock(MouseVideoObservationsService.class);
    reviewService = mock(MouseVideoReviewService.class);
    schedulingService = mock(MouseVideoSchedulingService.class);
    previewService = mock(MouseVideoPreviewService.class);
    reconciliation = mock(MouseVideoReconciliationService.class);
    when(imports.stagingRoot()).thenReturn(root);
    when(imports.reserve(any(), anyString(), anyLong()))
        .thenReturn(
            new MouseVideoImportService.MouseVideoIntakeReservationHandle(UUID.randomUUID()));
    controller =
        new MouseVideoController(
            new MouseVideoAccess(),
            imports,
            catalog,
            jobService,
            mock(MouseVideoExportService.class),
            reviewService,
            frameIndexService,
            observationsService,
            schedulingService,
            previewService,
            mock(MouseVideoRestrictedLauncher.class),
            mock(MouseVideoRequestGuard.class),
            mock(MouseVideoWriteNonceService.class),
            new MouseVideoTelemetry(),
            workerRegistry,
            mock(MouseVideoReviewSessionService.class),
            reconciliation,
            new ObjectMapper());
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void derivativeMediaIsServedFromTheRequestedAnalysis() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    Path media = Files.writeString(root.resolve("proxy.mp4"), "proxy-bytes");
    when(catalog.media("owner", sourceId, "PROXY", analysisId))
        .thenReturn(
            new MouseVideoCatalogService.MediaHandle(media, Files.size(media), "video/mp4", true));

    mvc.perform(
            get("/api/debug/mouse-videos/sources/{sourceId}/media/PROXY", sourceId)
                .param("analysisId", analysisId.toString())
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "video/mp4"))
        .andExpect(content().bytes("proxy-bytes".getBytes(StandardCharsets.UTF_8)));

    verify(catalog).media("owner", sourceId, "PROXY", analysisId);
  }

  @Test
  void previewSchedulingReturnsTheDurablePendingReceipt() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    UUID previewId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(schedulingService.schedulePreview(
            eq("owner"), eq(sourceId), eq(analysisId), anyString(), eq(7L), eq(640), eq(360)))
        .thenReturn(
            new MouseVideoSchedulingService.Receipt(
                "PENDING",
                sourceId,
                analysisId,
                previewId,
                jobId,
                7L,
                "70",
                "sha256:" + "a".repeat(64)));

    mvc.perform(
            post(
                    "/api/debug/mouse-videos/sources/{sourceId}/analyses/{analysisId}/frame-previews",
                    sourceId,
                    analysisId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"frameOrdinal\":7,\"maxWidth\":640,\"maxHeight\":360}")
                .principal(() -> "owner"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.previewId").value(previewId.toString()))
        .andExpect(jsonPath("$.state").value("PENDING"))
        .andExpect(jsonPath("$.frameOrdinal").value(7))
        .andExpect(jsonPath("$.pts").value("70"));

    verify(schedulingService)
        .schedulePreview(
            eq("owner"), eq(sourceId), eq(analysisId), anyString(), eq(7L), eq(640), eq(360));
  }

  @Test
  void exactPreviewMediaIsServedWithoutCreatingWork() throws Exception {
    UUID previewId = UUID.randomUUID();
    Path media = Files.write(root.resolve("preview.png"), new byte[] {1, 2, 3, 4});
    when(previewService.media("owner", previewId))
        .thenReturn(new MouseVideoPreviewService.PreviewMedia(media, 4L, "image/png"));

    mvc.perform(
            get("/api/debug/mouse-videos/frame-previews/{previewId}/media", previewId)
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(content().bytes(new byte[] {1, 2, 3, 4}));

    verify(previewService).media("owner", previewId);
  }

  @Test
  void reconcileSettlesAProvenJob() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(reconciliation.reconcile(jobId, "owner")).thenReturn(mock(MouseVideoJob.class));
    when(jobService.view(any())).thenReturn(jobView(jobId, "FAILED"));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/reconcile", jobId).principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void reconcileWithoutProvenCleanupIsAConflict() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(reconciliation.reconcile(jobId, "owner"))
        .thenThrow(
            new MouseVideoException(
                MouseVideoException.CODE_CONFLICT,
                "Cleanup is not proven: A workspace of this job is still mounted"));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/reconcile", jobId).principal(() -> "owner"))
        .andExpect(status().isConflict());
    verify(jobService, never()).view(any());
  }

  @Test
  void alreadyCancelledJobReturnsSuccess() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.requestCancellation(jobId, "owner", 0L)).thenReturn(mock(MouseVideoJob.class));
    when(jobService.view(any())).thenReturn(jobView(jobId, "CANCELLED"));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/cancel", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void workerSettlingFirstStillReturnsSuccessWithoutAServerError() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.requestCancellation(jobId, "owner", 0L)).thenReturn(mock(MouseVideoJob.class));
    when(jobService.view(any()))
        .thenReturn(jobView(jobId, "CANCELLING"), jobView(jobId, "CANCELLED"));
    when(workerRegistry.requestCancel(jobId)).thenReturn(true);
    when(workerRegistry.awaitCleanup(eq(jobId), anyLong())).thenReturn(true);
    when(jobService.confirmCancelled(jobId, "owner")).thenReturn(mock(MouseVideoJob.class));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/cancel", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void unconfirmedCleanupRemainsAcceptedForReconciliation() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.requestCancellation(jobId, "owner", 0L)).thenReturn(mock(MouseVideoJob.class));
    when(jobService.view(any()))
        .thenReturn(jobView(jobId, "CANCELLING"), jobView(jobId, "NEEDS_RECONCILIATION"));
    when(workerRegistry.requestCancel(jobId)).thenReturn(false);
    when(jobService.confirmReconciliation(eq(jobId), eq("owner"), anyString()))
        .thenReturn(mock(MouseVideoJob.class));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/cancel", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .principal(() -> "owner"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("NEEDS_RECONCILIATION"));
  }

  @Test
  void staleCancellationRevisionIsAConflict() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.requestCancellation(jobId, "owner", 0L))
        .thenThrow(new MouseVideoException(MouseVideoException.CODE_CONFLICT, "stale"));

    mvc.perform(
            post("/api/debug/mouse-videos/jobs/{jobId}/cancel", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .principal(() -> "owner"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(MouseVideoException.CODE_CONFLICT));
  }

  @Test
  void configuredFileCapIsEnforcedWhileStreamingAndReleasesCapacity() throws Exception {
    when(imports.intakeFileLimit()).thenReturn(3L);
    String body =
        "--limit\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.mp4\"\r\nContent-Type: video/mp4\r\n\r\n1234\r\n--limit--\r\n";
    mvc.perform(
            post("/api/debug/mouse-videos/imports/upload")
                .header("X-Mouse-Video-Request-Id", UUID.randomUUID().toString())
                .contentType("multipart/form-data; boundary=limit")
                .content(body)
                .principal(() -> "owner"))
        .andExpect(status().isPayloadTooLarge());
    verify(imports).release(any());
    verify(imports, org.mockito.Mockito.never())
        .submitUpload(any(), any(), any(), any(), anyLong(), anyString());
    try (var staged = Files.list(root)) {
      assertThat(staged.toList()).isEmpty();
    }
  }

  @Test
  void malformedMultipartIsABadRequestAndReleasesTheReservation() throws Exception {
    mvc.perform(
            post("/api/debug/mouse-videos/imports/upload")
                .header("X-Mouse-Video-Request-Id", UUID.randomUUID().toString())
                .contentType("multipart/form-data; boundary=broken")
                .content("not-a-boundary")
                .principal(() -> "owner"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MouseVideoException.CODE_INVALID_REQUEST));

    verify(imports).release(any());
  }

  @Test
  void storageReadFailureRemainsAServerErrorAndReleasesTheReservation() throws Exception {
    MouseVideoImportService.MouseVideoIntakeReservationHandle handle =
        new MouseVideoImportService.MouseVideoIntakeReservationHandle(UUID.randomUUID());
    when(imports.reserve(any(), anyString(), anyLong())).thenReturn(handle);

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Mouse-Video-Request-Id")).thenReturn(UUID.randomUUID().toString());
    when(request.getContentLengthLong()).thenReturn(0L);
    when(request.getContentType()).thenReturn("multipart/form-data; boundary=boundary");
    when(request.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("staging storage read failed");
              }

              @Override
              public boolean isFinished() {
                return false;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {}
            });

    assertThatThrownBy(() -> controller.upload(request, () -> "owner"))
        .isInstanceOf(IOException.class)
        .isNotInstanceOf(MouseVideoException.class);

    verify(imports).release(handle);
    try (var remaining = Files.list(root)) {
      assertThat(remaining).isEmpty();
    }
  }

  @Test
  void declaredOversizeRequestIsRejectedBeforeReadingTheBody() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Mouse-Video-Request-Id")).thenReturn(UUID.randomUUID().toString());
    when(request.getContentLengthLong())
        .thenReturn(MouseVideoMultipartReader.MAX_REQUEST_BYTES + 1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.upload(request, () -> "owner"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_MEDIA_LIMIT);
  }

  @Test
  void jobCapacityRejectionDoesNotReadTheUploadBody() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Mouse-Video-Request-Id")).thenReturn(UUID.randomUUID().toString());
    when(imports.reserve(any(), anyString(), anyLong()))
        .thenThrow(new MouseVideoException(MouseVideoException.CODE_QUOTA_EXCEEDED, "full"));
    assertThatThrownBy(() -> controller.upload(request, () -> "owner"))
        .isInstanceOf(MouseVideoException.class);
    verify(request, org.mockito.Mockito.never()).getInputStream();
  }

  @Test
  void configuredLimitRejectionIsPayloadTooLarge() throws Exception {
    when(imports.reserve(any(), anyString(), anyLong()))
        .thenThrow(new MouseVideoException(MouseVideoException.CODE_MEDIA_LIMIT, "limit"));

    mvc.perform(
            post("/api/debug/mouse-videos/imports/upload")
                .header("X-Mouse-Video-Request-Id", UUID.randomUUID().toString())
                .contentType("multipart/form-data; boundary=boundary")
                .content("--boundary--\r\n")
                .principal(() -> "owner"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(MouseVideoException.CODE_MEDIA_LIMIT));
  }

  @Test
  void requestErrorsKeepBoundedPublicMessages() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.requireOwned(jobId, "owner")).thenReturn(mock(MouseVideoJob.class));
    when(jobService.view(any()))
        .thenReturn(
            jobView(
                jobId,
                "FAILED",
                "The media could not be decoded or probed (reference " + jobId + ")"));

    mvc.perform(get("/api/debug/mouse-videos/jobs/{jobId}", jobId).principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("reference")))
        .andExpect(
            jsonPath("$.errorMessage")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/tmp"))));
  }

  @Test
  void frameIndexRouteRequiresAuthentication() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();

    mvc.perform(
            get(
                "/api/debug/mouse-videos/sources/{sourceId}/analyses/{analysisId}/frame-index",
                sourceId,
                analysisId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(MouseVideoException.CODE_UNAUTHENTICATED));
  }

  @Test
  void frameIndexRouteForwardsTheOwnerCursorAndLimit() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    when(frameIndexService.page("owner", sourceId, analysisId, "cursor-1", 25))
        .thenReturn(
            java.util.Map.of(
                "contract",
                MouseVideoFrameIndexService.PAGE_CONTRACT,
                "artifactChecksum",
                "sha256:abc",
                "frameCount",
                3,
                "items",
                java.util.List.of()));

    mvc.perform(
            get(
                    "/api/debug/mouse-videos/sources/{sourceId}/analyses/{analysisId}/frame-index",
                    sourceId,
                    analysisId)
                .param("cursor", "cursor-1")
                .param("limit", "25")
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contract").value("frame-index-page/v1"))
        .andExpect(jsonPath("$.artifactChecksum").value("sha256:abc"));

    verify(frameIndexService).page("owner", sourceId, analysisId, "cursor-1", 25);
  }

  @Test
  void observationsRouteForwardsFiltersAndReportsAbsence() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID analysisId = UUID.randomUUID();
    when(observationsService.list(
            "owner", sourceId, analysisId, null, 100, "CURSOR_VISIBILITY", "10", "20"))
        .thenReturn(
            java.util.Map.of(
                "available", false,
                "items", java.util.List.of(),
                "unknowns", java.util.List.of("no published observation artifact")));

    mvc.perform(
            get(
                    "/api/debug/mouse-videos/sources/{sourceId}/analyses/{analysisId}/observations",
                    sourceId,
                    analysisId)
                .param("kind", "CURSOR_VISIBILITY")
                .param("sincePts", "10")
                .param("untilPts", "20")
                .principal(() -> "owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
  }

  @Test
  void invalidCorrectionsSurfaceAsATypedBadRequest() throws Exception {
    UUID sourceId = UUID.randomUUID();
    when(reviewService.create(any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
        .thenThrow(
            new MouseVideoException(
                MouseVideoException.CODE_INVALID_REQUEST,
                "Correction 0 has an unsupported kind 'MOVE_CURSOR_TO'"));

    mvc.perform(
            post("/api/debug/mouse-videos/sources/{sourceId}/reviews", sourceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"requestId\":\""
                        + UUID.randomUUID()
                        + "\",\"reason\":\"r\",\"corrections\":[{\"kind\":\"MOVE_CURSOR_TO\"}]}")
                .principal(() -> "owner"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(MouseVideoException.CODE_INVALID_REQUEST));
  }

  private static MouseVideoJobService.JobView jobView(UUID jobId, String status) {
    return jobView(jobId, status, null);
  }

  private static MouseVideoJobService.JobView jobView(
      UUID jobId, String status, String errorMessage) {
    return new MouseVideoJobService.JobView(
        jobId.toString(),
        UUID.randomUUID().toString(),
        MouseVideoJob.KIND_IMPORT,
        MouseVideoJob.STAGE_PROBE,
        status,
        0,
        1,
        1L,
        0L,
        errorMessage == null ? null : MouseVideoException.CODE_DECODER_FAILED,
        errorMessage,
        null,
        null,
        null);
  }
}
