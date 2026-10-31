package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoImportMetadata;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MouseVideoImportServiceTest {

  @TempDir Path root;

  @Mock MouseVideoSourceRepository sources;
  @Mock MouseVideoJobRepository jobs;
  @Mock MouseVideoProbeWorker probeWorker;
  @Mock MouseVideoDerivativeWorker derivativeWorker;
  @Mock MouseVideoYouTubeAcquirer youtubeAcquirer;
  @Mock MouseVideoJobService jobService;
  @Mock MouseVideoRegistrationService registration;
  @Mock MouseVideoFinalizationService finalization;
  @Mock MouseVideoQuotaService quota;
  @Mock MouseVideoLeaseHeartbeat leaseHeartbeat;
  @Mock MouseVideoLeaseHeartbeat.Handle heartbeatHandle;

  private MouseVideoProperties properties;
  private MouseVideoByteStore byteStore;
  private MouseVideoImportService service;

  private static final MouseVideoAccess.Actor ACTOR =
      new MouseVideoAccess.Actor("owner-1", "owner-1");

  @BeforeEach
  void setUp() throws IOException {
    properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    properties.setEnabled(true);
    properties.setMaxDurationSeconds(60);
    properties.setMaxWidth(1920);
    properties.setMaxHeight(1080);
    properties.setMaxFps(60);
    byteStore = new MouseVideoByteStore(properties);
    when(leaseHeartbeat.start(any(), anyLong(), anyString(), any())).thenReturn(heartbeatHandle);
    when(heartbeatHandle.leaseLost()).thenReturn(false);
    service =
        new MouseVideoImportService(
            properties,
            byteStore,
            probeWorker,
            derivativeWorker,
            youtubeAcquirer,
            sources,
            jobs,
            jobService,
            registration,
            finalization,
            quota,
            new MouseVideoWorkerRegistry(),
            leaseHeartbeat,
            new MouseVideoTelemetry(),
            new ObjectMapper());
    service.autoSchedule = false;
  }

  @Test
  void fullOwnerIsRejectedBeforeStorageReservation() {
    String request = UUID.randomUUID().toString();
    doThrow(new MouseVideoException(MouseVideoException.CODE_QUOTA_EXCEEDED, "full"))
        .when(registration)
        .checkUploadAdmission(ACTOR.owner(), request);
    assertThatThrownBy(() -> service.reserve(ACTOR, request, 123L))
        .isInstanceOf(MouseVideoException.class);
    verify(quota, never()).reserve(anyString(), anyString(), anyLong());
  }

  @Test
  void admissibleUploadChecksJobsBeforeReservingDisk() {
    String request = UUID.randomUUID().toString();
    var reservation =
        new com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoIntakeReservation(
            ACTOR.owner(), request, null, 123L, java.time.Instant.now().plusSeconds(60));
    when(quota.reserve(
            org.mockito.ArgumentMatchers.eq(ACTOR.owner()),
            anyString(),
            org.mockito.ArgumentMatchers.eq(123L)))
        .thenReturn(reservation);
    service.reserve(ACTOR, request, 123L);
    var ordered = inOrder(registration, quota);
    ordered.verify(registration).checkUploadAdmission(ACTOR.owner(), request);
    ordered
        .verify(quota)
        .reserve(
            org.mockito.ArgumentMatchers.eq(ACTOR.owner()),
            org.mockito.ArgumentMatchers.argThat(value -> !value.equals(request)),
            org.mockito.ArgumentMatchers.eq(123L));
  }

  @Test
  void disabledFeatureIsRejected() throws IOException {
    properties.setEnabled(false);
    Path staged = Files.writeString(root.resolve("staged.bin"), "x");
    assertThatThrownBy(
            () ->
                service.submitUpload(
                    ACTOR,
                    metadata("r1"),
                    UUID.randomUUID(),
                    staged,
                    1L,
                    "sha256:" + "0".repeat(64)))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_DISABLED);
  }

  @Test
  void verifiedMediaTypeNeverReflectsCallerMime() {
    assertThat(MouseVideoImportService.verifiedMediaType(okProbe("mov,mp4", "h264")))
        .isEqualTo("video/mp4");
    assertThat(MouseVideoImportService.verifiedMediaType(okProbe("matroska,webm", "vp9")))
        .isEqualTo("video/webm");
    assertThat(MouseVideoImportService.verifiedMediaType(okProbe("mpegts", "mpeg2video"))).isNull();
    assertThat(MouseVideoImportService.verifiedMediaType(okProbe("mov,mp4", "mpeg4"))).isNull();
  }

  @Test
  void youtubeDisabledIsRejected() {
    properties.setYoutubeEnabled(false);
    assertThatThrownBy(
            () ->
                service.submitYouTube(
                    ACTOR,
                    metadata("r2"),
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    "dQw4w9WgXcQ"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_DISABLED);
  }

  @Test
  void executeRefusesWhenClaimNotAcquired() throws Exception {
    UUID jobId = UUID.randomUUID();
    when(jobService.claim(eq(jobId), anyString(), anyLong()))
        .thenReturn(new MouseVideoJobService.ClaimResult(false, job(jobId, UUID.randomUUID())));
    service.execute(jobId);
    verify(probeWorker, never()).probe(any(), any(), any());
    verify(finalization, never())
        .finalizeSuccess(any(), anyLong(), anyString(), any(), any(), any());
  }

  @Test
  void executeProbesDerivesAndFinalizesInOneFencedTransaction() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    MouseVideoJob running = job(jobId, sourceId);
    running.setStatus(MouseVideoJob.STATUS_RUNNING);
    when(jobService.claim(eq(jobId), anyString(), anyLong()))
        .thenReturn(new MouseVideoJobService.ClaimResult(true, running));

    MouseVideoSource source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal(
        publish("video-bytes"),
        "video-bytes".getBytes(StandardCharsets.UTF_8).length,
        "application/octet-stream");
    when(sources.findById(sourceId)).thenReturn(Optional.of(source));

    when(probeWorker.freeze(any())).thenReturn("sha256:toolchain");
    when(probeWorker.probe(any(), any(), any())).thenReturn(okProbe("mov,mp4", "h264"));
    when(derivativeWorker.derive(any(), any(), any()))
        .thenReturn(new MouseVideoDerivativeWorker.Derivatives(false, false, 0L, List.of()));
    when(jobService.find(jobId)).thenReturn(Optional.of(running));

    service.execute(jobId);

    verify(leaseHeartbeat).start(eq(jobId), anyLong(), anyString(), any());
    verify(finalization)
        .finalizeSuccess(eq(jobId), anyLong(), anyString(), any(), eq("video/mp4"), any());
  }

  @Test
  void executeSettlesUnsupportedCodecAsFailure() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    MouseVideoJob running = job(jobId, sourceId);
    running.setStatus(MouseVideoJob.STATUS_RUNNING);
    when(jobService.claim(eq(jobId), anyString(), anyLong()))
        .thenReturn(new MouseVideoJobService.ClaimResult(true, running));
    MouseVideoSource source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal(publish("bytes"), 5L, "application/octet-stream");
    when(sources.findById(sourceId)).thenReturn(Optional.of(source));
    when(probeWorker.freeze(any())).thenReturn("sha256:toolchain");
    when(probeWorker.probe(any(), any(), any())).thenReturn(okProbe("mpegts", "mpeg2video"));
    when(jobService.find(jobId)).thenReturn(Optional.of(running));

    service.execute(jobId);

    verify(jobService)
        .fail(
            eq(jobId),
            anyLong(),
            eq(MouseVideoException.CODE_UNSUPPORTED_MEDIA),
            anyString(),
            eq(false));
    verify(finalization, never())
        .finalizeSuccess(any(), anyLong(), anyString(), any(), any(), any());
  }

  @Test
  void probeFailurePublishesBoundedTextWithoutPrivateDiagnostics() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    MouseVideoJob running = job(jobId, sourceId);
    running.setStatus(MouseVideoJob.STATUS_RUNNING);
    when(jobService.claim(eq(jobId), anyString(), anyLong()))
        .thenReturn(new MouseVideoJobService.ClaimResult(true, running));
    MouseVideoSource source = new MouseVideoSource("owner-1", MouseVideoSource.KIND_UPLOAD);
    ReflectionTestUtils.setField(source, "id", sourceId);
    source.bindOriginal(publish("bytes"), 5L, "application/octet-stream");
    when(sources.findById(sourceId)).thenReturn(Optional.of(source));
    when(probeWorker.freeze(any())).thenReturn("sha256:toolchain");
    when(probeWorker.probe(any(), any(), any()))
        .thenReturn(
            VideoProbeResult.unavailable(
                MouseVideoException.CODE_DECODER_FAILED,
                "Traceback: /tmp/sc2150/private/probe.py failed with secret=abc",
                new ObjectMapper().createObjectNode()));

    service.execute(jobId);

    ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
    verify(jobService)
        .fail(
            eq(jobId),
            anyLong(),
            eq(MouseVideoException.CODE_DECODER_FAILED),
            message.capture(),
            eq(false));
    assertThat(message.getValue())
        .doesNotContain("/tmp")
        .doesNotContain("Traceback")
        .doesNotContain("secret")
        .contains(jobId.toString())
        .hasSizeLessThanOrEqualTo(320);
  }

  @Test
  void leaseLossReconciliationUsesTheOriginalExecutionIdentity() throws Exception {
    UUID jobId = UUID.randomUUID();
    MouseVideoJob running = job(jobId, UUID.randomUUID());
    running.setStatus(MouseVideoJob.STATUS_RUNNING);
    when(jobService.claim(eq(jobId), anyString(), anyLong()))
        .thenReturn(new MouseVideoJobService.ClaimResult(true, running));
    when(heartbeatHandle.leaseLost()).thenReturn(true);
    // Missing source triggers the worker's lease-loss handler without executing a tool.
    when(sources.findById(running.getSourceId())).thenReturn(Optional.empty());

    service.execute(jobId);

    verify(jobService).reconcileExecution(eq(jobId), eq(1L), eq(0L), anyString(), anyString());
    verify(jobService, never()).confirmReconciliation(any(), anyString(), anyString());
    verify(jobService, never()).find(jobId);
  }

  private String publish(String content) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(new java.io.ByteArrayInputStream(bytes), bytes.length + 1L);
    byteStore.publish(staged);
    return staged.checksum();
  }

  private MouseVideoJob job(UUID jobId, UUID sourceId) {
    MouseVideoJob job =
        new MouseVideoJob(sourceId, "owner-1", MouseVideoJob.KIND_IMPORT, "req", "digest");
    ReflectionTestUtils.setField(job, "id", jobId);
    return job;
  }

  private MouseVideoImportMetadata metadata(String requestId) {
    return new MouseVideoImportMetadata(
        requestId, "UNKNOWN", "fishing", "fishing", "skilling", "catherby");
  }

  private static VideoProbeResult okProbe(String container, String codec) {
    return new VideoProbeResult(
        true, true, container, 12.0d, 1280, 720, 30.0d, 360L, codec, 0, 0, null, null, null);
  }
}
