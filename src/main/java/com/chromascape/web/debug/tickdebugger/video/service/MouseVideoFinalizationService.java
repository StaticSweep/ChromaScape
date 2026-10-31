package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEvent;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fenced, single-transaction finalization. The job row is locked and re-validated (RUNNING status,
 * generation, lease owner and unexpired database lease) before the analysis, every artifact
 * reference and the SUCCEEDED status/event become visible together. A cancellation that wins first
 * bumps the generation, so this transaction refuses and no analysis or reference is written.
 */
@Service
public class MouseVideoFinalizationService {

  /**
   * One pre-published artifact to register during finalization. {@code analysisScoped} attaches the
   * reference to the analysis created in this same transaction; otherwise it is source-level.
   */
  public record ArtifactSpec(
      boolean analysisScoped,
      String role,
      String checksum,
      long sizeBytes,
      String mediaType,
      boolean quarantined) {}

  private final MouseVideoJobRepository jobs;
  private final MouseVideoJobEventRepository events;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoSourceRepository sources;
  private final ObjectMapper mapper;

  public MouseVideoFinalizationService(
      MouseVideoJobRepository jobs,
      MouseVideoJobEventRepository events,
      MouseVideoAnalysisRepository analyses,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoSourceRepository sources,
      ObjectMapper mapper) {
    this.jobs = jobs;
    this.events = events;
    this.analyses = analyses;
    this.artifacts = artifacts;
    this.sources = sources;
    this.mapper = mapper;
  }

  /**
   * Registers the completed analysis, artifact references and success in one fenced transaction.
   * Throws {@code CONFLICT} for a stale/expired/terminal job without writing anything.
   */
  @Transactional
  public MouseVideoJob finalizeSuccess(
      UUID jobId,
      long generation,
      String leaseOwner,
      MouseVideoAnalysis analysis,
      String verifiedMediaType,
      List<ArtifactSpec> specs) {
    artifacts.lockPublication();
    MouseVideoJob job =
        jobs.findForUpdate(jobId)
            .orElseThrow(
                () ->
                    new MouseVideoException(MouseVideoException.CODE_NOT_FOUND, "Job is missing"));
    validateFence(job, generation, leaseOwner);
    if (verifiedMediaType != null) {
      artifacts
          .findBySourceIdAndRoleAndAnalysisIdIsNull(
              analysis.getSourceId(), MouseVideoArtifactRef.ROLE_ORIGINAL)
          .ifPresent(
              original -> {
                original.finalizeVerified(verifiedMediaType);
                artifacts.save(original);
              });
      sources
          .findById(analysis.getSourceId())
          .ifPresent(
              source -> {
                source.qualifyOriginal(verifiedMediaType);
                sources.save(source);
              });
    }
    MouseVideoAnalysis saved = analyses.save(analysis);
    for (ArtifactSpec spec : specs) {
      MouseVideoArtifactRef ref =
          new MouseVideoArtifactRef(
              saved.getOwner(),
              saved.getSourceId(),
              spec.analysisScoped() ? saved.getId() : null,
              spec.role(),
              spec.checksum(),
              spec.sizeBytes(),
              spec.mediaType());
      if (spec.quarantined()) {
        ref.quarantine();
      } else {
        ref.finalizeRow();
      }
      artifacts.save(ref);
    }
    job.setStage(MouseVideoJob.STAGE_DONE);
    job.setProgress(100);
    job.setStatus(MouseVideoJob.STATUS_SUCCEEDED);
    job.setLeaseOwner(null);
    job.setLeaseExpiresAt(null);
    MouseVideoJob savedJob = jobs.save(job);
    events.save(
        new MouseVideoJobEvent(
            savedJob.getId(),
            savedJob.getGeneration(),
            "SUCCEEDED",
            mapper.valueToTree(
                new LinkedHashMap<>(Map.of("analysisId", saved.getId().toString())))));
    return savedJob;
  }

  /** Validates the publication fence under the job row lock. */
  public void validateFence(MouseVideoJob job, long generation, String leaseOwner) {
    if (!job.isRunning()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job is not running; finalization is refused");
    }
    if (job.getGeneration() != generation) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job generation changed; finalization is fenced");
    }
    if (leaseOwner == null || !leaseOwner.equals(job.getLeaseOwner())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job lease is owned by another worker");
    }
    if (!jobs.leaseValid(job.getId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT, "Job lease expired before finalization");
    }
  }
}
