package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEvent;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSchedulingRequest;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSchedulingRequestRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable, idempotent scheduling for analysis and exact-frame preview work.
 *
 * <p>Request identity is stored before dispatch. An identical (owner, requestId) returns the same
 * job/result; a changed body under the same request identifier is a conflict; and an equivalent
 * content identity coalesces onto one analysis or preview row with at most one active job. The
 * database unique constraints are the cross-instance authority, so a concurrent insert loser
 * re-reads the winner instead of creating a duplicate computation.
 */
@Service
public class MouseVideoSchedulingService {

  public static final String ANALYSIS_METHOD = MouseVideoVisualAnalyzer.ALGORITHM_ID;
  public static final String PREVIEW_METHOD = MouseVideoFramePreview.METHOD_FRAME_PNG;

  private static final List<String> ACTIVE_JOB_STATUSES =
      List.of(
          MouseVideoJob.STATUS_QUEUED,
          MouseVideoJob.STATUS_RUNNING,
          MouseVideoJob.STATUS_CANCELLING);

  private final MouseVideoProperties properties;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoJobRepository jobs;
  private final MouseVideoJobEventRepository events;
  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoFrameIndexService frameIndex;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoSchedulingRequestRepository requests;
  private final ObjectMapper mapper;
  private final MouseVideoAnalysisIdentity identity;
  private final MouseVideoAdmission admission;
  private final TransactionTemplate transactions;

  public MouseVideoSchedulingService(
      MouseVideoProperties properties,
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoJobRepository jobs,
      MouseVideoJobEventRepository events,
      MouseVideoFramePreviewRepository previews,
      MouseVideoFrameIndexService frameIndex,
      MouseVideoByteStore byteStore,
      MouseVideoSchedulingRequestRepository requests,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.properties = properties;
    this.sources = sources;
    this.analyses = analyses;
    this.jobs = jobs;
    this.events = events;
    this.previews = previews;
    this.frameIndex = frameIndex;
    this.byteStore = byteStore;
    this.requests = Objects.requireNonNull(requests, "requests");
    this.mapper = mapper;
    this.identity = new MouseVideoAnalysisIdentity(properties, mapper);
    this.admission = new MouseVideoAdmission(jobs, properties);
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Scheduling receipt: an existing immutable result or one durable pending job. */
  public record Receipt(
      String state,
      UUID sourceId,
      UUID analysisId,
      UUID previewId,
      UUID jobId,
      long frameOrdinal,
      String framePts,
      String requestDigest) {}

  /**
   * Schedules a bounded visual analysis of an already-qualified source. Only the shipped {@link
   * MouseVideoVisualAnalyzer#ALGORITHM_ID} is accepted; caller-supplied executables or thresholds
   * are never accepted.
   */
  public Receipt scheduleAnalysis(
      String owner, UUID sourceId, String requestId, String algorithmId, String templatePackId) {
    requireEnabled();
    String algorithm =
        algorithmId == null || algorithmId.isBlank() ? ANALYSIS_METHOD : algorithmId.trim();
    if (!ANALYSIS_METHOD.equals(algorithm)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST,
          "Only the server-owned " + ANALYSIS_METHOD + " analysis method is available");
    }
    String templatePack = identity.resolveTemplatePackId(templatePackId);
    try (var publication = byteStore.publicationLease()) {
      try {
        return transactions.execute(
            status -> createAnalysis(owner, sourceId, requestId, algorithm, templatePack));
      } catch (DataIntegrityViolationException raced) {
        return transactions.execute(
            status -> existingAnalysisReceipt(owner, sourceId, requestId, algorithm, templatePack));
      }
    }
  }

  /**
   * Schedules an exact preview for one frozen frame ordinal. The ordinal, index checksum, source
   * PTS and extraction bounds are pinned before dispatch; GET is never allowed to create work.
   */
  public Receipt schedulePreview(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String requestId,
      long frameOrdinal,
      int maxWidth,
      int maxHeight) {
    requireEnabled();
    int boundedWidth =
        Math.max(1, Math.min(properties.getMaxPreviewWidth(), maxWidth <= 0 ? 960 : maxWidth));
    int boundedHeight =
        Math.max(1, Math.min(properties.getMaxPreviewHeight(), maxHeight <= 0 ? 540 : maxHeight));
    try {
      return transactions.execute(
          status ->
              createPreview(
                  owner,
                  sourceId,
                  analysisId,
                  requestId,
                  frameOrdinal,
                  boundedWidth,
                  boundedHeight));
    } catch (DataIntegrityViolationException raced) {
      int width = boundedWidth;
      int height = boundedHeight;
      return transactions.execute(
          status ->
              existingPreviewReceipt(
                  owner, sourceId, analysisId, requestId, frameOrdinal, width, height));
    }
  }

  public Optional<Receipt> findPreviewReceipt(String owner, UUID sourceId, UUID previewId) {
    return transactions.execute(
        status ->
            previews
                .findByIdAndOwner(previewId, owner)
                .filter(preview -> preview.getSourceId().equals(sourceId))
                .map(this::receipt));
  }

  private Receipt createAnalysis(
      String owner, UUID sourceId, String requestId, String algorithm, String templatePackId) {
    lockAdmission(owner);
    analyses.lockPublication();
    MouseVideoSource source = requireQualifiedSource(owner, sourceId);
    String templateChecksum = identity.requireCursorTemplateChecksum();
    String configDigest =
        identity.configDigest(
            algorithm, templatePackId, source.getVerifiedMediaType(), templateChecksum);
    String toolchainDigest = identity.toolchainDigest();
    String requestDigest =
        digest(
            "analysis\n"
                + sourceId
                + "\n"
                + source.getOriginalChecksum()
                + "\n"
                + algorithm
                + "\n"
                + orEmpty(templatePackId)
                + "\n"
                + configDigest
                + "\n"
                + toolchainDigest);

    Optional<Receipt> alias =
        aliasReceipt(
            owner, requestId, requestDigest, MouseVideoSchedulingRequest.KIND_ANALYSIS, sourceId);
    if (alias.isPresent()) {
      return alias.get();
    }
    Optional<MouseVideoJob> byRequest = jobs.findByOwnerAndRequestId(owner, requestId);
    if (byRequest.isPresent()) {
      MouseVideoJob winner = byRequest.get();
      if (!requestDigest.equals(winner.getRequestDigest())
          || !sourceId.equals(winner.getSourceId())
          || !MouseVideoJob.KIND_ANALYSIS.equals(winner.getKind())) {
        throw conflict();
      }
      validateBoundAnalysis(winner, source, configDigest, toolchainDigest, algorithm);
      return receipt(winner, analysisIdOf(winner), null, 0L, null);
    }
    MouseVideoAnalysis analysis =
        analyses
            .findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
                sourceId, source.getOriginalChecksum(), configDigest, toolchainDigest)
            .orElse(null);
    if (analysis != null && MouseVideoAnalysis.STATUS_COMPLETE.equals(analysis.getStatus())) {
      recordAlias(
          owner,
          requestId,
          requestDigest,
          MouseVideoSchedulingRequest.KIND_ANALYSIS,
          sourceId,
          analysis.getId(),
          null,
          null);
      return new Receipt("READY", sourceId, analysis.getId(), null, null, 0L, null, requestDigest);
    }
    if (analysis == null) {
      freezeTemplate(templateChecksum);
      analysis =
          new MouseVideoAnalysis(
              sourceId,
              owner,
              source.getOriginalChecksum(),
              algorithm,
              templatePackId,
              configDigest,
              toolchainDigest);
      analysis.setTemplateChecksum(templateChecksum);
      analyses.saveAndFlush(analysis);
    } else if (MouseVideoAnalysis.STATUS_FAILED.equals(analysis.getStatus())) {
      analysis.reopen();
      analyses.saveAndFlush(analysis);
    }
    Optional<MouseVideoJob> active =
        jobs.findFirstByAnalysisIdAndKindAndStatusIn(
            analysis.getId(), MouseVideoJob.KIND_ANALYSIS, ACTIVE_JOB_STATUSES);
    if (active.isPresent()) {
      recordAlias(
          owner,
          requestId,
          requestDigest,
          MouseVideoSchedulingRequest.KIND_ANALYSIS,
          sourceId,
          analysis.getId(),
          null,
          active.get().getId());
      return receipt(active.get(), analysis.getId(), null, 0L, null);
    }
    admit(owner);
    MouseVideoJob job =
        new MouseVideoJob(
            sourceId,
            owner,
            MouseVideoJob.KIND_ANALYSIS,
            requestId,
            requestDigest,
            analysis.getId(),
            null,
            "analysis:" + analysis.getId());
    job.setStage(MouseVideoJob.STAGE_ANALYSIS);
    jobs.saveAndFlush(job);
    recordAlias(
        owner,
        requestId,
        requestDigest,
        MouseVideoSchedulingRequest.KIND_ANALYSIS,
        sourceId,
        analysis.getId(),
        null,
        job.getId());
    record(job, "QUEUED", "analysisId", analysis.getId().toString());
    return receipt(job, analysis.getId(), null, 0L, null);
  }

  private Receipt existingAnalysisReceipt(
      String owner, UUID sourceId, String requestId, String algorithm, String templatePackId) {
    MouseVideoSource source = requireQualifiedSource(owner, sourceId);
    String templateChecksum = identity.requireCursorTemplateChecksum();
    String configDigest =
        identity.configDigest(
            algorithm, templatePackId, source.getVerifiedMediaType(), templateChecksum);
    String toolchainDigest = identity.toolchainDigest();
    String requestDigest =
        digest(
            "analysis\n"
                + sourceId
                + "\n"
                + source.getOriginalChecksum()
                + "\n"
                + algorithm
                + "\n"
                + orEmpty(templatePackId)
                + "\n"
                + configDigest
                + "\n"
                + toolchainDigest);
    Optional<Receipt> alias =
        aliasReceipt(
            owner, requestId, requestDigest, MouseVideoSchedulingRequest.KIND_ANALYSIS, sourceId);
    if (alias.isPresent()) {
      return alias.get();
    }
    Optional<MouseVideoJob> job = jobs.findByOwnerAndRequestId(owner, requestId);
    if (job.isPresent()) {
      MouseVideoJob winner = job.get();
      if (!requestDigest.equals(winner.getRequestDigest())
          || !sourceId.equals(winner.getSourceId())
          || !MouseVideoJob.KIND_ANALYSIS.equals(winner.getKind())) {
        throw conflict();
      }
      validateBoundAnalysis(winner, source, configDigest, toolchainDigest, algorithm);
      return receipt(winner, analysisIdOf(winner), null, 0L, null);
    }
    MouseVideoAnalysis analysis =
        analyses
            .findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
                sourceId, source.getOriginalChecksum(), configDigest, toolchainDigest)
            .filter(candidate -> MouseVideoAnalysis.STATUS_COMPLETE.equals(candidate.getStatus()))
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_CONFLICT,
                        "Concurrent analysis scheduling could not be resolved"));
    return new Receipt("READY", sourceId, analysis.getId(), null, null, 0L, null, requestDigest);
  }

  private void validateBoundAnalysis(
      MouseVideoJob job,
      MouseVideoSource source,
      String configDigest,
      String toolchainDigest,
      String algorithm) {
    if (job.getAnalysisId() == null) {
      return;
    }
    MouseVideoAnalysis bound =
        analyses
            .findByIdAndOwner(job.getAnalysisId(), job.getOwner())
            .orElseThrow(MouseVideoSchedulingService::conflict);
    if (!source.getId().equals(bound.getSourceId())
        || !source.getOriginalChecksum().equals(bound.getOriginalDigest())
        || !configDigest.equals(bound.getConfigDigest())
        || !toolchainDigest.equals(bound.getToolchainDigest())
        || !algorithm.equals(bound.getAlgorithmId())) {
      throw conflict();
    }
  }

  private Receipt createPreview(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String requestId,
      long frameOrdinal,
      int maxWidth,
      int maxHeight) {
    lockAdmission(owner);
    MouseVideoSource source = requireQualifiedSource(owner, sourceId);
    MouseVideoFrameIndexService.FrozenFrameIndex index =
        frameIndex.requireIndex(owner, sourceId, analysisId);
    if (frameOrdinal < 0 || frameOrdinal >= index.frames().size()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST,
          "frameOrdinal is outside the frozen index range");
    }
    MouseVideoFrameIndexService.Frame frame = index.frames().get((int) frameOrdinal);
    String requestDigest =
        digest(
            "preview\n"
                + analysisId
                + "\n"
                + index.checksum()
                + "\n"
                + frameOrdinal
                + "\n"
                + orEmpty(frame.pts())
                + "\n"
                + PREVIEW_METHOD
                + "\n"
                + maxWidth
                + "\n"
                + maxHeight);

    Optional<Receipt> alias =
        aliasReceipt(
            owner, requestId, requestDigest, MouseVideoSchedulingRequest.KIND_PREVIEW, sourceId);
    if (alias.isPresent()) {
      return alias.get();
    }
    Optional<MouseVideoFramePreview> byRequest = previews.findByOwnerAndRequestId(owner, requestId);
    if (byRequest.isPresent()) {
      if (!byRequest.get().getRequestDigest().equals(requestDigest)) {
        throw conflict();
      }
      validatePreview(
          byRequest.get(),
          sourceId,
          analysisId,
          index.checksum(),
          frameOrdinal,
          frame.pts(),
          maxWidth,
          maxHeight);
      return receipt(byRequest.get());
    }
    MouseVideoFramePreview preview =
        previews
            .findByAnalysisIdAndIndexChecksumAndFrameOrdinalAndPreviewMethodAndMaxWidthAndMaxHeight(
                analysisId, index.checksum(), frameOrdinal, PREVIEW_METHOD, maxWidth, maxHeight)
            .orElse(null);
    if (preview != null && MouseVideoFramePreview.STATE_READY.equals(preview.getState())) {
      recordAlias(
          owner,
          requestId,
          requestDigest,
          MouseVideoSchedulingRequest.KIND_PREVIEW,
          sourceId,
          analysisId,
          preview.getId(),
          preview.getJobId());
      return receipt(preview);
    }
    if (preview == null) {
      preview =
          new MouseVideoFramePreview(
              owner,
              sourceId,
              analysisId,
              requestId,
              requestDigest,
              index.checksum(),
              frameOrdinal,
              frame.pts(),
              frame.ptsTime(),
              index.timeBaseNumerator(),
              index.timeBaseDenominator(),
              PREVIEW_METHOD,
              maxWidth,
              maxHeight);
      previews.saveAndFlush(preview);
    } else {
      if (!owner.equals(preview.getOwner())
          || !sourceId.equals(preview.getSourceId())
          || !analysisId.equals(preview.getAnalysisId())
          || !source.getOriginalChecksum().equals(analysisOriginalDigest(owner, analysisId))) {
        throw notFound();
      }
      if (MouseVideoFramePreview.STATE_FAILED.equals(preview.getState())
          || MouseVideoFramePreview.STATE_CANCELLED.equals(preview.getState())) {
        preview.reopen();
        previews.saveAndFlush(preview);
      }
    }
    Optional<MouseVideoJob> active =
        jobs.findFirstByPreviewIdAndStatusIn(preview.getId(), ACTIVE_JOB_STATUSES);
    if (active.isPresent()) {
      recordAlias(
          owner,
          requestId,
          requestDigest,
          MouseVideoSchedulingRequest.KIND_PREVIEW,
          sourceId,
          analysisId,
          preview.getId(),
          active.get().getId());
      return receipt(active.get(), analysisId, preview.getId(), frameOrdinal, frame.pts());
    }
    admit(owner);
    MouseVideoJob job =
        new MouseVideoJob(
            sourceId,
            owner,
            MouseVideoJob.KIND_PREVIEW,
            requestId,
            requestDigest,
            analysisId,
            preview.getId(),
            "preview:" + preview.getId());
    job.setStage(MouseVideoJob.STAGE_PREVIEW);
    jobs.saveAndFlush(job);
    preview.setJobId(job.getId());
    previews.saveAndFlush(preview);
    recordAlias(
        owner,
        requestId,
        requestDigest,
        MouseVideoSchedulingRequest.KIND_PREVIEW,
        sourceId,
        analysisId,
        preview.getId(),
        job.getId());
    record(job, "QUEUED", "previewId", preview.getId().toString());
    return receipt(job, analysisId, preview.getId(), frameOrdinal, frame.pts());
  }

  private Receipt existingPreviewReceipt(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String requestId,
      long frameOrdinal,
      int maxWidth,
      int maxHeight) {
    MouseVideoSource source = requireQualifiedSource(owner, sourceId);
    MouseVideoFrameIndexService.FrozenFrameIndex index =
        frameIndex.requireIndex(owner, sourceId, analysisId);
    if (frameOrdinal < 0 || frameOrdinal >= index.frames().size()) {
      throw conflict();
    }
    MouseVideoFrameIndexService.Frame frame = index.frames().get((int) frameOrdinal);
    String requestDigest =
        digest(
            "preview\n"
                + analysisId
                + "\n"
                + index.checksum()
                + "\n"
                + frameOrdinal
                + "\n"
                + orEmpty(frame.pts())
                + "\n"
                + PREVIEW_METHOD
                + "\n"
                + maxWidth
                + "\n"
                + maxHeight);
    Optional<Receipt> alias =
        aliasReceipt(
            owner, requestId, requestDigest, MouseVideoSchedulingRequest.KIND_PREVIEW, sourceId);
    if (alias.isPresent()) {
      return alias.get();
    }
    MouseVideoFramePreview preview =
        previews
            .findByOwnerAndRequestId(owner, requestId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_CONFLICT,
                        "Concurrent preview scheduling could not be resolved"));
    if (!requestDigest.equals(preview.getRequestDigest())) {
      throw conflict();
    }
    validatePreview(
        preview,
        sourceId,
        analysisId,
        index.checksum(),
        frameOrdinal,
        frame.pts(),
        maxWidth,
        maxHeight);
    if (!source.getOriginalChecksum().equals(analysisOriginalDigest(owner, analysisId))) {
      throw notFound();
    }
    return receipt(preview);
  }

  private void validatePreview(
      MouseVideoFramePreview preview,
      UUID sourceId,
      UUID analysisId,
      String indexChecksum,
      long frameOrdinal,
      String framePts,
      int maxWidth,
      int maxHeight) {
    if (!sourceId.equals(preview.getSourceId())) {
      throw notFound();
    }
    if (!analysisId.equals(preview.getAnalysisId())
        || !indexChecksum.equals(preview.getIndexChecksum())
        || preview.getFrameOrdinal() != frameOrdinal
        || !java.util.Objects.equals(framePts, preview.getFramePts())
        || !PREVIEW_METHOD.equals(preview.getPreviewMethod())
        || preview.getMaxWidth() != maxWidth
        || preview.getMaxHeight() != maxHeight) {
      throw conflict();
    }
  }

  private void lockAdmission(String owner) {
    // First statement in the scheduling transaction; see MouseVideoAdmission for lock order.
    admission.lock(owner);
  }

  private void requireEnabled() {
    if (!properties.isEnabled())
      throw new MouseVideoException(
          MouseVideoException.CODE_DISABLED, "External video processing is disabled");
  }

  private Optional<Receipt> aliasReceipt(
      String owner, String requestId, String requestDigest, String kind, UUID sourceId) {
    Optional<MouseVideoSchedulingRequest> alias =
        requests.findByOwnerAndRequestId(owner, requestId);
    if (alias.isEmpty()) {
      return Optional.empty();
    }
    MouseVideoSchedulingRequest recorded = alias.get();
    if (!requestDigest.equals(recorded.getRequestDigest())
        || !kind.equals(recorded.getKind())
        || !sourceId.equals(recorded.getSourceId())) {
      throw conflict();
    }
    if (MouseVideoSchedulingRequest.KIND_PREVIEW.equals(kind)) {
      if (recorded.getPreviewId() == null) {
        throw conflict();
      }
      return previews.findById(recorded.getPreviewId()).map(this::receipt);
    }
    if (recorded.getJobId() != null) {
      return jobs.findById(recorded.getJobId())
          .map(job -> receipt(job, recorded.getAnalysisId(), null, 0L, null));
    }
    if (recorded.getAnalysisId() != null) {
      return Optional.of(
          new Receipt(
              "READY", sourceId, recorded.getAnalysisId(), null, null, 0L, null, requestDigest));
    }
    throw conflict();
  }

  private void recordAlias(
      String owner,
      String requestId,
      String requestDigest,
      String kind,
      UUID sourceId,
      UUID analysisId,
      UUID previewId,
      UUID jobId) {
    if (requests.findByOwnerAndRequestId(owner, requestId).isPresent()) {
      return;
    }
    requests.saveAndFlush(
        new MouseVideoSchedulingRequest(
            owner, requestId, requestDigest, kind, sourceId, analysisId, previewId, jobId));
  }

  private void admit(String owner) {
    admission.requireCapacity(owner);
  }

  private MouseVideoSource requireQualifiedSource(String owner, UUID sourceId) {
    MouseVideoSource source =
        sources
            .findByIdAndOwner(sourceId, owner)
            .orElseThrow(MouseVideoSchedulingService::notFound);
    if (source.getOriginalChecksum() == null
        || !source.isOriginalQualified()
        || byteStore.locate(source.getOriginalChecksum()).isEmpty()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Source media is not qualified for analysis yet");
    }
    return source;
  }

  private String analysisOriginalDigest(String owner, UUID analysisId) {
    return analyses
        .findByIdAndOwner(analysisId, owner)
        .map(MouseVideoAnalysis::getOriginalDigest)
        .orElseThrow(MouseVideoSchedulingService::notFound);
  }

  /**
   * Publishes exactly the template bytes whose content address was hashed into the analysis
   * identity. When the configured file changed between hashing and staging, the staged copy is
   * discarded and scheduling fails closed as a retryable conflict instead of pinning other bytes.
   */
  private void freezeTemplate(String expectedChecksum) {
    if (expectedChecksum == null) {
      return;
    }
    try {
      if (byteStore.locate(expectedChecksum).isPresent()) {
        return;
      }
      Path path = Path.of(properties.getVisualCursorTemplate());
      MouseVideoByteStore.StagedArtifact staged;
      try (InputStream input = Files.newInputStream(path)) {
        staged = byteStore.stage(input, Math.max(1L, Files.size(path)));
      }
      if (!expectedChecksum.equals(staged.checksum())) {
        byteStore.discard(staged);
        throw new MouseVideoException(
            MouseVideoException.CODE_CONFLICT,
            "Cursor template changed while scheduling; retry the request",
            true);
      }
      byteStore.publish(staged);
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Cursor template could not be frozen for scheduling",
          failure);
    }
  }

  private UUID analysisIdOf(MouseVideoJob job) {
    if (job.getAnalysisId() != null) {
      return job.getAnalysisId();
    }
    return analyses
        .findBySourceIdAndOwnerOrderByCreatedAtDesc(job.getSourceId(), job.getOwner())
        .stream()
        .map(MouseVideoAnalysis::getId)
        .findFirst()
        .orElse(null);
  }

  private Receipt receipt(MouseVideoFramePreview preview) {
    return new Receipt(
        MouseVideoFramePreview.STATE_READY.equals(preview.getState())
            ? "READY"
            : (MouseVideoFramePreview.STATE_FAILED.equals(preview.getState())
                ? "FAILED"
                : (MouseVideoFramePreview.STATE_CANCELLED.equals(preview.getState())
                    ? "CANCELLED"
                    : "PENDING")),
        preview.getSourceId(),
        preview.getAnalysisId(),
        preview.getId(),
        preview.getJobId(),
        preview.getFrameOrdinal(),
        preview.getFramePts(),
        preview.getRequestDigest());
  }

  private Receipt receipt(
      MouseVideoJob job, UUID analysisId, UUID previewId, long ordinal, String pts) {
    String state =
        MouseVideoJob.STATUS_SUCCEEDED.equals(job.getStatus())
            ? "READY"
            : (MouseVideoJob.STATUS_FAILED.equals(job.getStatus())
                    || MouseVideoJob.STATUS_TIMED_OUT.equals(job.getStatus())
                ? "FAILED"
                : (MouseVideoJob.STATUS_CANCELLED.equals(job.getStatus())
                    ? "CANCELLED"
                    : "PENDING"));
    return new Receipt(
        state,
        job.getSourceId(),
        analysisId,
        previewId,
        job.getId(),
        ordinal,
        pts,
        job.getRequestDigest());
  }

  private void record(MouseVideoJob job, String type, String key, String value) {
    events.save(
        new MouseVideoJobEvent(
            job.getId(), job.getGeneration(), type, mapper.valueToTree(Map.of(key, value))));
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String digest(String value) {
    return MouseVideoAnalysisIdentity.digest(value);
  }

  private static MouseVideoException conflict() {
    return new MouseVideoException(
        MouseVideoException.CODE_CONFLICT,
        "This request identifier was already used with different content");
  }

  private static MouseVideoException notFound() {
    return new MouseVideoException(
        MouseVideoException.CODE_NOT_FOUND, "Source or analysis is not available to this owner");
  }
}
