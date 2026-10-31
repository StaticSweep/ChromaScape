package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoPublicErrors;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes durable ANALYSIS and PREVIEW jobs. Work is claimed with the same generation/lease fence
 * as import jobs; only the current unexpired execution may publish, and cancellation terminates the
 * owned child before a terminal state is reported.
 */
@Service
public class MouseVideoScheduledJobRunner {

  private static final Logger LOG = LoggerFactory.getLogger(MouseVideoScheduledJobRunner.class);
  private static final String WORKER_ID =
      "mouse-video-scheduled-" + UUID.randomUUID() + "-" + ProcessHandle.current().pid();

  private final MouseVideoProperties properties;
  private final MouseVideoJobRepository jobs;
  private final MouseVideoJobService jobService;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisWorker analysisWorker;
  private final MouseVideoPreviewWorker previewWorker;
  private final MouseVideoFinalizationService analysisFinalization;
  private final MouseVideoPreviewFinalizationService previewFinalization;
  private final MouseVideoWorkerRegistry workerRegistry;
  private final MouseVideoLeaseHeartbeat leaseHeartbeat;
  private final MouseVideoByteStore byteStore;
  private final TransactionTemplate transactions;
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor executor;

  public MouseVideoScheduledJobRunner(
      MouseVideoProperties properties,
      MouseVideoJobRepository jobs,
      MouseVideoJobService jobService,
      MouseVideoAnalysisRepository analyses,
      MouseVideoFramePreviewRepository previews,
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisWorker analysisWorker,
      MouseVideoPreviewWorker previewWorker,
      MouseVideoFinalizationService analysisFinalization,
      MouseVideoPreviewFinalizationService previewFinalization,
      MouseVideoWorkerRegistry workerRegistry,
      MouseVideoLeaseHeartbeat leaseHeartbeat,
      MouseVideoByteStore byteStore,
      PlatformTransactionManager transactionManager) {
    this.properties = properties;
    this.jobs = jobs;
    this.jobService = jobService;
    this.analyses = analyses;
    this.previews = previews;
    this.sources = sources;
    this.analysisWorker = analysisWorker;
    this.previewWorker = previewWorker;
    this.analysisFinalization = analysisFinalization;
    this.previewFinalization = previewFinalization;
    this.workerRegistry = workerRegistry;
    this.leaseHeartbeat = leaseHeartbeat;
    this.byteStore = byteStore;
    this.transactions = new TransactionTemplate(transactionManager);
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, properties.getWaitingSlots())),
            runnable -> {
              Thread thread = new Thread(runnable, "mouse-video-scheduled");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Scheduled(fixedDelay = 3_000L)
  public void poll() {
    if (!properties.isEnabled()) {
      return;
    }
    for (String kind : List.of(MouseVideoJob.KIND_ANALYSIS, MouseVideoJob.KIND_PREVIEW)) {
      for (MouseVideoJob job :
          jobs.findByKindAndStatusOrderByCreatedAtAsc(kind, MouseVideoJob.STATUS_QUEUED)) {
        schedule(job.getId());
      }
    }
  }

  void schedule(UUID jobId) {
    if (!inFlight.add(jobId)) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              execute(jobId);
            } finally {
              inFlight.remove(jobId);
            }
          });
    } catch (RejectedExecutionException busy) {
      inFlight.remove(jobId);
      // The durable poller retries queued jobs; dropping here is safe and bounded.
    }
  }

  /** Executes one scheduled job; package-private so tests can drive it directly. */
  long execute(UUID jobId) {
    MouseVideoJobService.ClaimResult claim =
        jobService.claim(jobId, WORKER_ID, properties.getLeaseExpirySeconds());
    if (!claim.acquired()) {
      return -1L;
    }
    MouseVideoJob job = claim.job();
    long generation = job.getGeneration();
    long cancellationRevision = job.getCancellationRevision();
    MouseVideoCancellation cancellation = workerRegistry.register(jobId, generation);
    cancellation.bindThread(Thread.currentThread());
    MouseVideoLeaseHeartbeat.Handle heartbeat =
        leaseHeartbeat.start(jobId, generation, WORKER_ID, cancellation);
    try (MouseVideoWorkspace volume =
            MouseVideoWorkspace.open(properties, jobId + "-" + generation);
        var publication = byteStore.publicationBatch()) {
      if (MouseVideoJob.KIND_ANALYSIS.equals(job.getKind())) {
        executeAnalysis(job, generation, cancellation, heartbeat, volume);
      } else if (MouseVideoJob.KIND_PREVIEW.equals(job.getKind())) {
        executePreview(job, generation, cancellationRevision, cancellation, heartbeat, volume);
      } else {
        throw new MouseVideoException(
            MouseVideoException.CODE_INVALID_REQUEST, "Unsupported scheduled job kind");
      }
    } catch (MouseVideoException failure) {
      if (heartbeat.leaseLost()) {
        reconcile(jobId, generation, cancellationRevision, "Worker lost its job lease");
      } else if (MouseVideoException.CODE_CLEANUP_UNCONFIRMED.equals(failure.getCode())) {
        reconcile(jobId, generation, cancellationRevision, failure.getMessage());
      } else if (cancellation.isShutdownRequested()) {
        requeueAfterShutdown(jobId, generation, cancellationRevision);
      } else if (cancellation.isCancelled()
          || MouseVideoException.CODE_INTERRUPTED.equals(failure.getCode())) {
        confirmCancelled(jobId, generation, cancellationRevision);
      } else {
        settleFailure(job, generation, failure.getCode());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      // The process runner raises InterruptedException only after it confirmed empty containment.
      if (cancellation.isShutdownRequested() && !heartbeat.leaseLost()) {
        requeueAfterShutdown(jobId, generation, cancellationRevision);
      } else {
        confirmCancelled(jobId, generation, cancellationRevision);
      }
    } catch (IOException failure) {
      settleFailure(job, generation, MouseVideoException.CODE_DECODER_FAILED);
    } catch (RuntimeException failure) {
      LOG.warn("Scheduled video job {} failed privately", jobId, failure);
      settleFailure(job, generation, MouseVideoException.CODE_DECODER_FAILED);
    } finally {
      heartbeat.close();
      workerRegistry.complete(jobId, generation);
    }
    return generation;
  }

  private void executeAnalysis(
      MouseVideoJob job,
      long generation,
      MouseVideoCancellation cancellation,
      MouseVideoLeaseHeartbeat.Handle heartbeat,
      MouseVideoWorkspace volume)
      throws IOException, InterruptedException {
    if (job.getAnalysisId() == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Analysis job has no bound analysis");
    }
    jobService.progress(job.getId(), generation, WORKER_ID, MouseVideoJob.STAGE_ANALYSIS, 20);
    MouseVideoAnalysisWorker.Outcome outcome =
        analysisWorker.run(cancellation, job.getSourceId(), job.getAnalysisId(), volume.path());
    volume.close();
    ensurePublishable(cancellation, heartbeat);
    jobService.progress(job.getId(), generation, WORKER_ID, MouseVideoJob.STAGE_PUBLISH, 90);
    analysisFinalization.finalizeSuccess(
        job.getId(), generation, WORKER_ID, outcome.analysis(), null, outcome.artifacts());
  }

  private void executePreview(
      MouseVideoJob job,
      long generation,
      long cancellationRevision,
      MouseVideoCancellation cancellation,
      MouseVideoLeaseHeartbeat.Handle heartbeat,
      MouseVideoWorkspace volume)
      throws IOException, InterruptedException {
    if (job.getPreviewId() == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Preview job has no bound preview");
    }
    MouseVideoFramePreview preview =
        previews
            .findById(job.getPreviewId())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Preview row is missing"));
    MouseVideoSource source =
        sources
            .findById(job.getSourceId())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Source is missing"));
    Path media =
        byteStore
            .locate(source.getOriginalChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Original media bytes are missing"));
    jobService.progress(job.getId(), generation, WORKER_ID, MouseVideoJob.STAGE_PREVIEW, 30);
    MouseVideoPreviewFinalizationService.PreviewBytes bytes =
        previewWorker.extract(volume.path(), media, preview, cancellation);
    volume.close();
    ensurePublishable(cancellation, heartbeat);
    jobService.progress(job.getId(), generation, WORKER_ID, MouseVideoJob.STAGE_PUBLISH, 90);
    previewFinalization.finalizeReady(
        job.getId(), generation, cancellationRevision, WORKER_ID, preview.getId(), bytes);
  }

  private void settleFailure(MouseVideoJob job, long generation, String code) {
    try {
      jobService.fail(
          job.getId(),
          generation,
          code,
          MouseVideoPublicErrors.jobFailure(code, job.getId()),
          MouseVideoException.CODE_WORKER_TIMEOUT.equals(code));
      if (job.getAnalysisId() != null) {
        transactions.executeWithoutResult(
            status ->
                analyses
                    .findById(job.getAnalysisId())
                    .filter(
                        analysis -> MouseVideoAnalysis.STATUS_PENDING.equals(analysis.getStatus()))
                    .ifPresent(
                        analysis -> {
                          analysis.fail();
                          analyses.save(analysis);
                        }));
      }
      if (job.getPreviewId() != null) {
        previewFinalization.markFailed(job.getPreviewId(), code);
      }
    } catch (RuntimeException fenced) {
      // Already terminal or fenced by another actor.
    }
  }

  private void confirmCancelled(UUID jobId, long generation, long cancellationRevision) {
    try {
      jobService.confirmExecutionCancelled(jobId, generation, cancellationRevision, WORKER_ID);
    } catch (RuntimeException fenced) {
      // Already terminal or fenced by another actor.
    }
  }

  private void requeueAfterShutdown(UUID jobId, long generation, long cancellationRevision) {
    try {
      jobService.requeueAfterShutdown(jobId, generation, cancellationRevision, WORKER_ID);
    } catch (RuntimeException fenced) {
      // Already settled or fenced; restart reconciliation handles anything left running.
    }
  }

  private void reconcile(UUID jobId, long generation, long cancellationRevision, String reason) {
    try {
      jobService.reconcileExecution(
          jobId,
          generation,
          cancellationRevision,
          WORKER_ID,
          MouseVideoPublicErrors.reconciliation(jobId));
    } catch (RuntimeException fenced) {
      // Already terminal or fenced by another actor.
    }
    LOG.warn("Scheduled video job {} reconciliation privateReason={}", jobId, reason);
  }

  private static void ensurePublishable(
      MouseVideoCancellation cancellation, MouseVideoLeaseHeartbeat.Handle heartbeat)
      throws InterruptedException {
    if (heartbeat.leaseLost()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INTERRUPTED, "Job lease was lost; publication is refused");
    }
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Job cancelled before publication");
    }
  }
}
