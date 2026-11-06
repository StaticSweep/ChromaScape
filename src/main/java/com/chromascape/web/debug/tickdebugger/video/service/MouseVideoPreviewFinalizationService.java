package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEvent;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fenced publication of exact-preview bytes. The preview row and job row are locked together, the
 * generation/lease/cancellation fence is re-validated, and only then are READY metadata and the
 * terminal SUCCEEDED state made visible atomically.
 */
@Service
public class MouseVideoPreviewFinalizationService {

  private final MouseVideoJobRepository jobs;
  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoJobEventRepository events;
  private final ObjectMapper mapper;

  @PersistenceContext private EntityManager entityManager;

  public MouseVideoPreviewFinalizationService(
      MouseVideoJobRepository jobs,
      MouseVideoFramePreviewRepository previews,
      MouseVideoJobEventRepository events,
      ObjectMapper mapper) {
    this.jobs = jobs;
    this.previews = previews;
    this.events = events;
    this.mapper = mapper;
  }

  /** Published preview metadata produced by the extractor. */
  public record PreviewBytes(
      String checksum,
      long sizeBytes,
      String mediaType,
      int sourceWidth,
      int sourceHeight,
      int outputWidth,
      int outputHeight,
      int rotationDegrees,
      String cropJson,
      String transformJson) {}

  @Transactional
  public MouseVideoJob finalizeReady(
      UUID jobId,
      long generation,
      long cancellationRevision,
      String leaseOwner,
      UUID previewId,
      PreviewBytes bytes) {
    previews.lockPublication();
    MouseVideoJob job = lockFresh(jobId);
    if (!job.isRunning()
        || job.getGeneration() != generation
        || leaseOwner == null
        || !leaseOwner.equals(job.getLeaseOwner())
        || !jobs.leaseValid(job.getId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job fence changed before preview publication");
    }
    if (job.getCancellationRevision() != cancellationRevision) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Cancellation revision changed before publication");
    }
    MouseVideoFramePreview preview =
        previews
            .findById(previewId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Preview row is missing"));
    if (!job.getPreviewId().equals(previewId)
        || !job.getOwner().equals(preview.getOwner())
        || !job.getAnalysisId().equals(preview.getAnalysisId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Preview identity does not match the job fence");
    }
    if (!MouseVideoFramePreview.STATE_PENDING.equals(preview.getState())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Preview is not pending publication");
    }
    preview.ready(
        bytes.checksum(),
        bytes.sizeBytes(),
        bytes.mediaType(),
        bytes.sourceWidth(),
        bytes.sourceHeight(),
        bytes.outputWidth(),
        bytes.outputHeight(),
        bytes.rotationDegrees(),
        bytes.cropJson(),
        bytes.transformJson());
    previews.save(preview);
    job.setStage(MouseVideoJob.STAGE_DONE);
    job.setProgress(100);
    job.setStatus(MouseVideoJob.STATUS_SUCCEEDED);
    job.setLeaseOwner(null);
    job.setLeaseExpiresAt(null);
    MouseVideoJob saved = jobs.save(job);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("previewId", previewId.toString());
    metadata.put("checksum", bytes.checksum());
    events.save(
        new MouseVideoJobEvent(
            saved.getId(), saved.getGeneration(), "SUCCEEDED", mapper.valueToTree(metadata)));
    return saved;
  }

  @Transactional
  public MouseVideoFramePreview markFailed(UUID previewId, String code) {
    MouseVideoFramePreview preview =
        previews
            .findById(previewId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Preview row is missing"));
    if (MouseVideoFramePreview.STATE_PENDING.equals(preview.getState())) {
      preview.fail(code);
      previews.save(preview);
    }
    return preview;
  }

  private MouseVideoJob lockFresh(UUID jobId) {
    MouseVideoJob job =
        jobs.findForUpdate(jobId)
            .orElseThrow(
                () ->
                    new MouseVideoException(MouseVideoException.CODE_NOT_FOUND, "Job is missing"));
    if (entityManager != null && entityManager.contains(job)) {
      entityManager.refresh(job);
    }
    return job;
  }
}
