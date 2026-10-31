package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoImportMetadata;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoPublicErrors;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates durable external-video sources and import jobs and executes them through the fixed,
 * fenced probe pipeline. Bytes are streamed and hashed before the bounded byte store publishes
 * them; registration and quota charging commit atomically; work is queued only after that commit so
 * a crash in between is recovered by the durable QUEUED poller.
 */
@Service
public class MouseVideoImportService {

  private static final Logger LOG = LoggerFactory.getLogger(MouseVideoImportService.class);

  private final MouseVideoProperties properties;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoProbeWorker probeWorker;
  private final MouseVideoDerivativeWorker derivativeWorker;
  private final MouseVideoYouTubeAcquirer youtubeAcquirer;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoJobRepository jobs;
  private final MouseVideoJobService jobService;
  private final MouseVideoRegistrationService registration;
  private final MouseVideoFinalizationService finalization;
  private final MouseVideoQuotaService quota;
  private final MouseVideoWorkerRegistry workerRegistry;
  private final MouseVideoLeaseHeartbeat leaseHeartbeat;
  private final MouseVideoTelemetry telemetry;
  private final ObjectMapper mapper;
  private final Path root;
  private final ThreadPoolExecutor executor;

  /** Collision-resistant process identity; a bare PID is reused across restarts. */
  private static final String WORKER_ID =
      "mouse-video-" + UUID.randomUUID() + "-" + ProcessHandle.current().pid();

  /** Test seam: when false, {@link #schedule} is a no-op so execution can be driven directly. */
  boolean autoSchedule = true;

  public MouseVideoImportService(
      MouseVideoProperties properties,
      MouseVideoByteStore byteStore,
      MouseVideoProbeWorker probeWorker,
      MouseVideoDerivativeWorker derivativeWorker,
      MouseVideoYouTubeAcquirer youtubeAcquirer,
      MouseVideoSourceRepository sources,
      MouseVideoJobRepository jobs,
      MouseVideoJobService jobService,
      MouseVideoRegistrationService registration,
      MouseVideoFinalizationService finalization,
      MouseVideoQuotaService quota,
      MouseVideoWorkerRegistry workerRegistry,
      MouseVideoLeaseHeartbeat leaseHeartbeat,
      MouseVideoTelemetry telemetry,
      ObjectMapper mapper) {
    this.properties = properties;
    this.byteStore = byteStore;
    this.probeWorker = probeWorker;
    this.derivativeWorker = derivativeWorker;
    this.youtubeAcquirer = youtubeAcquirer;
    this.sources = sources;
    this.jobs = jobs;
    this.jobService = jobService;
    this.registration = registration;
    this.finalization = finalization;
    this.quota = quota;
    this.workerRegistry = workerRegistry;
    this.leaseHeartbeat = leaseHeartbeat;
    this.telemetry = telemetry;
    this.mapper = mapper;
    this.root = properties.rootPath();
    int slots = Math.max(1, properties.getWaitingSlots());
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(slots),
            runnable -> {
              Thread thread = new Thread(runnable, "mouse-video-job");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PostConstruct
  void initialize() {
    telemetry.reconciliationsRequired(jobService.reconcileOnStartup());
  }

  /** Re-queues durable QUEUED jobs and fences claims whose lease expired without cleanup. */
  @Scheduled(fixedDelay = 3000L)
  public void pollQueued() {
    telemetry.reconciliationsRequired(jobService.reconcileExpiredClaims());
    if (!properties.isEnabled()) {
      return;
    }
    for (MouseVideoJob job :
        jobs.findByKindAndStatusOrderByCreatedAtAsc(
            MouseVideoJob.KIND_IMPORT, MouseVideoJob.STATUS_QUEUED)) {
      schedule(job.getId());
    }
    quota.expireReservations();
  }

  /**
   * Registers and publishes a streamed upload. The caller has already reserved intake capacity and
   * hashed the bytes while streaming them into {@code stagedMediaPath}.
   */
  public Map<String, Object> submitUpload(
      MouseVideoAccess.Actor actor,
      MouseVideoImportMetadata metadata,
      UUID reservationId,
      Path stagedMediaPath,
      long sizeBytes,
      String checksum) {
    requireEnabled();
    try (var publication = byteStore.publicationBatch()) {
      try {
        MouseVideoByteStore.StagedArtifact staged =
            byteStore.adopt(stagedMediaPath, checksum, sizeBytes);
        byteStore.publish(staged);
      } catch (IOException failure) {
        quota.release(reservationId);
        throw new MouseVideoException(
            MouseVideoException.CODE_DECODER_FAILED, "Uploaded media could not be stored", failure);
      }
      MouseVideoRegistrationService.Registration registered =
          registration.registerUpload(actor, metadata, reservationId, checksum, sizeBytes);
      if (registered.created()) {
        telemetry.intakeAccepted();
        scheduleAfterCommit(registered.job().getId());
      } else {
        telemetry.intakeReused();
      }
      return receipt(registered.source(), registered.job());
    }
  }

  public Map<String, Object> submitYouTube(
      MouseVideoAccess.Actor actor,
      MouseVideoImportMetadata metadata,
      String canonicalUrl,
      String providerId) {
    requireEnabled();
    if (!properties.isYoutubeEnabled()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DISABLED, "YouTube import is not enabled on this server");
    }
    MouseVideoRegistrationService.Registration registered =
        registration.registerYouTube(actor, metadata, providerId);
    if (registered.created()) {
      scheduleAfterCommit(registered.job().getId());
    }
    return receipt(registered.source(), registered.job());
  }

  /** Reserves disk separately for each concurrent attempt, including identical request retries. */
  public MouseVideoIntakeReservationHandle reserve(
      MouseVideoAccess.Actor actor, String requestId, long declaredBytes) {
    requireEnabled();
    registration.checkUploadAdmission(actor.owner(), requestId);
    var reservation = quota.reserve(actor.owner(), UUID.randomUUID().toString(), declaredBytes);
    return new MouseVideoIntakeReservationHandle(reservation.getId());
  }

  /** The intake reader must enforce the same file cap used by its disk reservation. */
  public long intakeFileLimit() {
    return Math.max(
        1L,
        Math.min(
            properties.getMaxUploadBytes(),
            com.chromascape.web.debug.tickdebugger.video.MouseVideoMultipartReader.MAX_FILE_BYTES));
  }

  /** Owned root for intake staging; callers create and remove per-request subdirectories. */
  public Path stagingRoot() throws IOException {
    Path staging = root.resolve("staging");
    Files.createDirectories(staging);
    return staging;
  }

  public void release(MouseVideoIntakeReservationHandle handle) {
    if (handle != null) {
      quota.release(handle.reservationId());
    }
  }

  /** Opaque handle for a held intake reservation. */
  public record MouseVideoIntakeReservationHandle(UUID reservationId) {}

  private void scheduleAfterCommit(UUID jobId) {
    if (!autoSchedule) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              schedule(jobId);
            }
          });
    } else {
      schedule(jobId);
    }
  }

  private void schedule(UUID jobId) {
    if (!autoSchedule) {
      return;
    }
    try {
      executor.execute(() -> runGuarded(jobId));
    } catch (RejectedExecutionException busy) {
      // The durable poller retries queued jobs; dropping here is safe and bounded.
    }
  }

  private void runGuarded(UUID jobId) {
    execute(jobId);
  }

  /** Executes one import job: claim, acquire, probe, derive, fenced finalization. */
  long execute(UUID jobId) {
    MouseVideoJobService.ClaimResult claim =
        jobService.claim(jobId, workerId(), properties.getLeaseExpirySeconds());
    if (!claim.acquired()) {
      return -1L;
    }
    telemetry.jobClaimed();
    MouseVideoJob job = claim.job();
    long generation = job.getGeneration();
    long cancellationRevision = job.getCancellationRevision();
    MouseVideoCancellation cancellation = workerRegistry.register(jobId, generation);
    cancellation.bindThread(Thread.currentThread());
    MouseVideoLeaseHeartbeat.Handle heartbeat =
        leaseHeartbeat.start(jobId, generation, workerId(), cancellation);
    try (MouseVideoWorkspace volume =
            MouseVideoWorkspace.open(properties, jobId + "-" + generation);
        var publication = byteStore.publicationBatch()) {
      MouseVideoSource source =
          sources
              .findById(job.getSourceId())
              .orElseThrow(
                  () ->
                      new MouseVideoException(
                          MouseVideoException.CODE_NOT_FOUND, "Source was removed during import"));
      Path workspace = volume.path();
      if (MouseVideoSource.KIND_YOUTUBE.equals(source.getSourceKind())) {
        jobService.progress(jobId, generation, workerId(), MouseVideoJob.STAGE_ACQUIRE, 10);
        MouseVideoYouTubeAcquirer.Acquired acquired =
            youtubeAcquirer.acquire(
                workspace, source.getProviderUrl(), source.getProviderId(), cancellation);
        registration.attachAcquiredOriginal(
            source.getId(),
            source.getOwner(),
            acquired.checksum(),
            acquired.sizeBytes(),
            "application/octet-stream");
        publication.checkpoint();
      }
      Path media = requireOriginalPath(job.getSourceId());
      if (cancellation.isCancelled()) {
        throw new MouseVideoException(
            MouseVideoException.CODE_INTERRUPTED, "Import was cancelled before probing");
      }
      String toolchain = probeWorker.freeze(workspace);
      jobService.progress(jobId, generation, workerId(), MouseVideoJob.STAGE_PROBE, 25);
      VideoProbeResult probe = probeWorker.probe(workspace, media, cancellation);
      if (!probe.ok()) {
        String code = MouseVideoPublicErrors.probeCode(probe.code());
        LOG.warn(
            "Video job {} probe failure code={} privateMessage={}", jobId, code, probe.message());
        throw new MouseVideoException(code, MouseVideoPublicErrors.jobFailure(code, jobId));
      }
      validateProbe(probe);
      String verifiedMediaType = verifiedMediaType(probe);
      if (verifiedMediaType == null) {
        throw new MouseVideoException(
            MouseVideoException.CODE_UNSUPPORTED_MEDIA,
            "The uploaded container/codec is not an allowed inline media type");
      }
      if (cancellation.isCancelled()) {
        throw new MouseVideoException(
            MouseVideoException.CODE_INTERRUPTED, "Import was cancelled before publication");
      }
      jobService.progress(jobId, generation, workerId(), MouseVideoJob.STAGE_PUBLISH, 60);
      MouseVideoDerivativeWorker.Derivatives derivatives =
          derivativeWorker.derive(workspace, media, cancellation);
      List<MouseVideoFinalizationService.ArtifactSpec> specs = new ArrayList<>();
      Optional<MouseVideoByteStore.StagedArtifact> facts =
          publishArtifact(workspace, "probe-facts.json", mapper.writeValueAsBytes(probe.raw()));
      facts.ifPresent(
          artifact ->
              specs.add(
                  new MouseVideoFinalizationService.ArtifactSpec(
                      true,
                      MouseVideoArtifactRef.ROLE_MANIFEST,
                      artifact.checksum(),
                      artifact.sizeBytes(),
                      "application/json",
                      false)));
      for (MouseVideoDerivativeWorker.DerivedArtifact derived : derivatives.artifacts()) {
        specs.add(
            new MouseVideoFinalizationService.ArtifactSpec(
                true,
                derived.role(),
                derived.checksum(),
                derived.sizeBytes(),
                derived.mediaType(),
                false));
      }
      MouseVideoAnalysis analysis =
          new MouseVideoAnalysis(
              source.getId(),
              source.getOwner(),
              source.getOriginalChecksum(),
              "probe/v1",
              null,
              configDigest(),
              toolchain);
      analysis.complete(
          coverage(probe, derivatives),
          facts.map(MouseVideoByteStore.StagedArtifact::checksum).orElse(null));

      volume.close();
      ensurePublishable(cancellation, heartbeat);
      finalization.finalizeSuccess(
          jobId, generation, workerId(), analysis, verifiedMediaType, specs);
      telemetry.jobSucceeded();
    } catch (MouseVideoException failure) {
      if (heartbeat.leaseLost()) {
        confirmReconciliation(
            jobId,
            generation,
            cancellationRevision,
            "Worker lost its job lease; owned cleanup was attempted");
      } else if (MouseVideoException.CODE_CLEANUP_UNCONFIRMED.equals(failure.getCode())) {
        confirmReconciliation(jobId, generation, cancellationRevision, failure.getMessage());
      } else if (cancellation.isCancelled()
          || MouseVideoException.CODE_INTERRUPTED.equals(failure.getCode())) {
        confirmCancelled(jobId, generation, cancellationRevision);
      } else {
        settle(jobId, generation, failure.getCode(), failure.getMessage(), false);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      if (heartbeat.leaseLost()) {
        confirmReconciliation(
            jobId,
            generation,
            cancellationRevision,
            "Worker lost its job lease; owned cleanup was attempted");
      } else {
        confirmCancelled(jobId, generation, cancellationRevision);
      }
    } catch (IOException failure) {
      settle(
          jobId,
          generation,
          MouseVideoException.CODE_DECODER_FAILED,
          "Media could not be read",
          false);
    } catch (RuntimeException failure) {
      settle(jobId, generation, MouseVideoException.CODE_DECODER_FAILED, "Import failed", false);
    } finally {
      heartbeat.close();
      workerRegistry.complete(jobId, generation);
    }
    return generation;
  }

  private void settle(UUID jobId, long generation, String code, String message, boolean timeout) {
    boolean timedOut = timeout || MouseVideoException.CODE_WORKER_TIMEOUT.equals(code);
    LOG.warn("Video job {} failed code={} privateMessage={}", jobId, code, message);
    try {
      jobService.fail(
          jobId, generation, code, MouseVideoPublicErrors.jobFailure(code, jobId), timedOut);
      telemetry.jobFailed();
    } catch (RuntimeException fenced) {
      // A fenced/terminal job is already settled; nothing to record.
    }
  }

  /** Confirms a cancellation whose owned cleanup already completed inside this worker. */
  private void confirmCancelled(UUID jobId, long generation, long cancellationRevision) {
    try {
      jobService.confirmExecutionCancelled(jobId, generation, cancellationRevision, workerId());
      telemetry.cancellationConfirmed();
    } catch (RuntimeException fenced) {
      // Already terminal or fenced by another actor.
    }
  }

  /**
   * Refuses publication when the lease was lost or cancellation arrived after the last process
   * finished. Both are checked immediately before the fenced finalization transaction.
   */
  private static void ensurePublishable(
      MouseVideoCancellation cancellation, MouseVideoLeaseHeartbeat.Handle heartbeat)
      throws InterruptedException {
    if (heartbeat.leaseLost()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INTERRUPTED, "Job lease was lost; publication is refused");
    }
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Import was cancelled before finalization");
    }
  }

  /**
   * Records an ambiguous cleanup outcome; the slot stays occupied until explicit reconciliation.
   */
  private void confirmReconciliation(
      UUID jobId, long generation, long cancellationRevision, String reason) {
    LOG.warn("Video job {} reconciliation privateReason={}", jobId, reason);
    try {
      jobService.reconcileExecution(
          jobId,
          generation,
          cancellationRevision,
          workerId(),
          MouseVideoPublicErrors.reconciliation(jobId));
      telemetry.reconciliationRequired();
    } catch (RuntimeException fenced) {
      // Already terminal or fenced by another actor.
    }
  }

  private void validateProbe(VideoProbeResult probe) {
    if (!probe.hasVideo()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_UNSUPPORTED_MEDIA, "The uploaded file contains no video stream");
    }
    if (probe.durationSeconds() > properties.getMaxDurationSeconds()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Media exceeds the configured duration limit");
    }
    if (probe.width() > properties.getMaxWidth() || probe.height() > properties.getMaxHeight()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Media exceeds the configured resolution limit");
    }
    if (probe.fps() > properties.getMaxFps()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Media exceeds the configured frame-rate limit");
    }
    if (probe.effectiveFrameCount() > properties.getMaxDecodeFrames()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Media exceeds the configured frame-count limit");
    }
  }

  /**
   * Chooses a server-side allowlisted inline media type from the actual container and codec. Caller
   * MIME is never reflected here.
   */
  static String verifiedMediaType(VideoProbeResult probe) {
    String container =
        probe.container() == null ? "" : probe.container().toLowerCase(java.util.Locale.ROOT);
    String codec =
        probe.videoCodec() == null ? "" : probe.videoCodec().toLowerCase(java.util.Locale.ROOT);
    boolean mp4 = container.contains("mp4") || container.contains("mov");
    boolean webm =
        container.contains("webm") || container.contains("matroska") || container.contains("mkv");
    boolean ogg = container.contains("ogg");
    if (mp4
        && (codec.equals("h264")
            || codec.equals("hevc")
            || codec.equals("av1")
            || codec.equals("vp9"))) {
      return "video/mp4";
    }
    if (webm && (codec.equals("vp8") || codec.equals("vp9") || codec.equals("av1"))) {
      return "video/webm";
    }
    if (ogg && (codec.equals("theora") || codec.equals("vp8") || codec.equals("vp9"))) {
      return "video/ogg";
    }
    return null;
  }

  private Optional<MouseVideoByteStore.StagedArtifact> publishArtifact(
      Path workspace, String filename, byte[] content) throws IOException {
    Path file = workspace.resolve(filename);
    Files.write(file, content);
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(new java.io.ByteArrayInputStream(content), content.length + 1L);
    byteStore.publish(staged);
    return Optional.of(staged);
  }

  private String coverage(
      VideoProbeResult probe, MouseVideoDerivativeWorker.Derivatives derivatives) {
    return String.format(
        java.util.Locale.ROOT,
        "probe-only: %dx%d @ %.2f fps, %.2fs, %d frames, indexed=%d proxy=%s",
        probe.width(),
        probe.height(),
        probe.fps(),
        probe.durationSeconds(),
        probe.effectiveFrameCount(),
        derivatives.indexedFrames(),
        derivatives.hasProxy());
  }

  private Path requireOriginalPath(UUID sourceId) {
    return byteStore
        .locate(originalChecksum(sourceId))
        .orElseThrow(
            () ->
                new MouseVideoException(
                    MouseVideoException.CODE_NOT_FOUND, "Original media bytes are missing"));
  }

  private String originalChecksum(UUID sourceId) {
    MouseVideoSource source =
        sources
            .findById(sourceId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Source is missing"));
    if (source.getOriginalChecksum() == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND, "Source has no published original media");
    }
    return source.getOriginalChecksum();
  }

  private void requireEnabled() {
    if (!properties.isEnabled()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DISABLED, "External video import is not enabled on this server");
    }
  }

  private Map<String, Object> receipt(MouseVideoSource source, MouseVideoJob job) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceId", source.getId().toString());
    body.put("jobId", job.getId().toString());
    body.put("kind", source.getSourceKind());
    body.put("status", job.getStatus());
    body.put("statusUrl", "/api/debug/mouse-videos/jobs/" + job.getId());
    return body;
  }

  private String configDigest() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("maxDurationSeconds", properties.getMaxDurationSeconds());
    config.put("maxWidth", properties.getMaxWidth());
    config.put("maxHeight", properties.getMaxHeight());
    config.put("maxFps", properties.getMaxFps());
    config.put("maxDecodeFrames", properties.getMaxDecodeFrames());
    config.put("proxyMaxHeight", properties.getProxyMaxHeight());
    try {
      return byteStore.digest(mapper.writeValueAsBytes(config));
    } catch (IOException impossible) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Unable to compute the config digest");
    }
  }

  private static String workerId() {
    return WORKER_ID;
  }
}
