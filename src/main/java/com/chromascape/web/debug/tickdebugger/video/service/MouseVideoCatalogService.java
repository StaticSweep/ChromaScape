package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped read access to the external-video catalog and controlled media roles. Only ORIGINAL,
 * PROXY and THUMBNAIL bytes are servable; observation/quality/export artifacts are metadata-only
 * and never returned as raw media.
 */
@Service
public class MouseVideoCatalogService {

  private static final Set<String> SERVABLE_ROLES =
      Set.of(
          MouseVideoArtifactRef.ROLE_ORIGINAL,
          MouseVideoArtifactRef.ROLE_PROXY,
          MouseVideoArtifactRef.ROLE_THUMBNAIL);

  private static final Set<String> INLINE_VIDEO_TYPES =
      Set.of("video/mp4", "video/webm", "video/ogg");
  private static final Set<String> INLINE_IMAGE_TYPES = Set.of("image/png", "image/jpeg");

  private final MouseVideoProperties properties;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoJobRepository jobs;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoProbeWorker probeWorker;
  private final MouseVideoDerivativeWorker derivativeWorker;

  public MouseVideoCatalogService(
      MouseVideoProperties properties,
      MouseVideoSourceRepository sources,
      MouseVideoJobRepository jobs,
      MouseVideoAnalysisRepository analyses,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoByteStore byteStore,
      MouseVideoProbeWorker probeWorker,
      MouseVideoDerivativeWorker derivativeWorker) {
    this.properties = properties;
    this.sources = sources;
    this.jobs = jobs;
    this.analyses = analyses;
    this.artifacts = artifacts;
    this.byteStore = byteStore;
    this.probeWorker = probeWorker;
    this.derivativeWorker = derivativeWorker;
  }

  /** Resolved servable media bytes for a controlled role. */
  public record MediaHandle(Path path, long sizeBytes, String mediaType, boolean inline) {}

  public Map<String, Object> capabilities() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", properties.isEnabled());
    body.put("youtubeEnabled", properties.isYoutubeEnabled());
    body.put(
        "tools",
        Map.of(
            "pythonConfigured",
            probeWorker.pythonConfigured(),
            "ffprobeAvailable",
            probeWorker.ffprobeAvailable(),
            "ffmpegAvailable",
            derivativeWorker.ffmpegAvailable(),
            "youtubeDownloaderConfigured",
            properties.getYoutubeDownloader() != null
                && !properties.getYoutubeDownloader().isBlank()));
    body.put("formats", List.of("mp4", "webm", "mkv", "mov", "avi"));
    boolean ffmpeg = derivativeWorker.ffmpegAvailable();
    MouseVideoCursorTemplate.Status template = new MouseVideoCursorTemplate(properties).inspect();
    boolean cursorTemplate = template.usable();
    body.put("analysisProfile", analysisProfile(template));
    body.put(
        "methods",
        List.of(
            "probe/v1",
            "frame-index/v2",
            "corrections/v1",
            "observations-read/v1",
            "observations/v1",
            "analysis/v1",
            "frame-preview/v1"));
    Map<String, Object> features = new LinkedHashMap<>();
    features.put("safeInlineMedia", true);
    features.put("versionedExports", true);
    features.put("appendOnlyReview", true);
    features.put("cursorTrackingAvailable", cursorTemplate);
    features.put("boundedFrameIndexReads", true);
    features.put("boundedObservationReads", true);
    features.put("validatedCorrections", true);
    features.put("exactFramePreview", ffmpeg);
    features.put("durableAnalysisScheduling", true);
    features.put("exactPointCorrections", ffmpeg);
    features.put("regionCorrections", ffmpeg);
    features.put("proxyAvailable", ffmpeg);
    features.put("restrictedLauncherRequired", properties.isRestrictedLauncher());
    body.put("features", features);
    List<String> unknowns = new ArrayList<>();
    if (!cursorTemplate) {
      unknowns.add(
          template.state() == MouseVideoCursorTemplate.State.NOT_CONFIGURED
              ? "cursor-tracking: unavailable until a reviewed cursor template is configured"
              : "cursor-tracking: unavailable — " + template.problem());
    }
    if (!ffmpeg) {
      unknowns.add("exact-frame-preview: unavailable because ffmpeg is not available");
    } else {
      unknowns.add(
          "proxy seeking is an approximation; exact previews identify the source frame by ordinal");
    }
    unknowns.add("native-ticks: unavailable (SC-2151)");
    unknowns.add("movement-quality: unavailable (SC-309)");
    unknowns.add("physical-input: never inferred from external video");
    body.put("unknowns", List.copyOf(unknowns));
    body.put("limits", limitsBlock());
    body.put(
        "notes",
        List.of(
            "Video observations are estimates; import does not verify human input.",
            "Native ticks and physical clicks are never derived from external media."));
    return body;
  }

  /**
   * Effective, server-owned analysis profile: the settings, thresholds and template bytes a newly
   * scheduled analysis will actually use. Exposing the resolved pack and its digest lets an
   * operator verify provisioning instead of inferring it from a request label.
   */
  private Map<String, Object> analysisProfile(MouseVideoCursorTemplate.Status template) {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("algorithmId", MouseVideoVisualAnalyzer.ALGORITHM_ID);
    profile.put("cursorMethod", MouseVideoCursorTracker.METHOD);
    profile.put("toolchainPin", properties.getVisualToolchainPin());
    String packId = properties.getVisualCursorTemplatePackId();
    profile.put("templatePackId", packId == null || packId.isBlank() ? null : packId.trim());
    profile.put("templateState", template.state().name());
    profile.put("templateSha256", template.checksum());
    profile.put("templatePinnedSha256", template.expectedSha256());
    profile.put("templateUsable", template.usable());
    if (template.problem() != null) {
      profile.put("templateProblem", template.problem());
    }
    profile.put("sampleFps", Math.max(1.0d, Math.min(60.0d, properties.getVisualSampleFps())));
    profile.put("sampleMaxWidth", properties.getVisualSampleMaxWidth());
    profile.put("maxVisualFrames", properties.getMaxVisualFrames());
    profile.put(
        "hotspot",
        Map.of(
            "x", properties.getVisualCursorHotspotX(),
            "y", properties.getVisualCursorHotspotY()));
    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("cursorMinScore", properties.getVisualCursorMinScore());
    thresholds.put("cursorStartScore", properties.getVisualCursorStartScore());
    thresholds.put("cursorMinGain", properties.getVisualCursorMinGain());
    thresholds.put("cursorMaxGain", properties.getVisualCursorMaxGain());
    thresholds.put("cursorMinContrast", properties.getVisualCursorMinContrast());
    thresholds.put("cursorAmbiguityMargin", properties.getVisualCursorAmbiguityMargin());
    thresholds.put("cursorStaticMaxDelta", properties.getVisualCursorStaticMaxDelta());
    thresholds.put("pauseMinMillis", properties.getPauseMinMillis());
    thresholds.put("pauseMaxDelta", properties.getPauseMaxDelta());
    thresholds.put("cutMinDelta", properties.getCutMinDelta());
    thresholds.put("activityMinDelta", properties.getActivityMinDelta());
    profile.put("thresholds", thresholds);
    Map<String, Object> storage = new LinkedHashMap<>();
    storage.put("maxJobDiskBytes", properties.getMaxJobDiskBytes());
    storage.put("jobVolumeBytes", properties.getJobVolumeBytes());
    profile.put("storage", storage);
    return profile;
  }

  private Map<String, Object> limitsBlock() {
    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("maxUploadBytes", properties.getMaxUploadBytes());
    limits.put("maxDurationSeconds", properties.getMaxDurationSeconds());
    limits.put("maxWidth", properties.getMaxWidth());
    limits.put("maxHeight", properties.getMaxHeight());
    limits.put("maxFps", properties.getMaxFps());
    limits.put("maxDecodeFrames", properties.getMaxDecodeFrames());
    limits.put("maxSourceDiskBytes", properties.getMaxSourceDiskBytes());
    limits.put("jobVolumeBytes", properties.getJobVolumeBytes());
    limits.put("cpuQuotaPercent", properties.getCpuQuotaPercent());
    limits.put("tasksMax", properties.getTasksMax());
    limits.put("memoryMaxBytes", properties.getMaxRssBytes());
    limits.put("maxVisualFrames", properties.getMaxVisualFrames());
    limits.put("maxPreviewWidth", properties.getMaxPreviewWidth());
    limits.put("maxPreviewHeight", properties.getMaxPreviewHeight());
    return limits;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(String owner, String cursor, int limit, String sourceKind) {
    int bounded = Math.max(1, Math.min(properties.getMaxCatalogPageSize(), limit));
    int offset = decodeCursor(cursor);
    Page<MouseVideoSource> page =
        sourceKind == null || sourceKind.isBlank()
            ? sources.findByOwnerOrderByCreatedAtDesc(
                owner, PageRequest.of(offset / bounded, bounded))
            : sources.findByOwnerAndSourceKindOrderByCreatedAtDesc(
                owner,
                sourceKind.trim().toUpperCase(java.util.Locale.ROOT),
                PageRequest.of(offset / bounded, bounded));
    List<Map<String, Object>> items = new ArrayList<>();
    for (MouseVideoSource source : page.getContent()) {
      items.add(summary(owner, source, selectedCompletedAnalysis(owner, source.getId())));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("limit", bounded);
    body.put("total", page.getTotalElements());
    body.put("nextCursor", page.hasNext() ? encodeCursor(offset + bounded) : null);
    return body;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String owner, UUID sourceId) {
    MouseVideoSource source = requireSource(owner, sourceId);
    Optional<MouseVideoAnalysis> selected = selectedCompletedAnalysis(owner, sourceId);
    Map<String, Object> body = summary(owner, source, selected);
    body.put("providerUrl", source.getProviderUrl());
    body.put("jobs", jobViews(owner, sourceId));
    body.put("artifacts", artifactViews(sourceId, selected));
    body.put("analyses", analysisViews(owner, sourceId, selected));
    return body;
  }

  @Transactional(readOnly = true)
  public MediaHandle media(String owner, UUID sourceId, String role) {
    return media(owner, sourceId, role, null);
  }

  /**
   * Resolves servable bytes for a role. ORIGINAL remains source-scoped. A derivative role is always
   * resolved against one explicitly selected COMPLETE analysis, so PROXY, THUMBNAIL and FRAME_INDEX
   * can never mix artifacts from different analyses and never silently fall back to an older one.
   */
  @Transactional(readOnly = true)
  public MediaHandle media(String owner, UUID sourceId, String role, UUID analysisId) {
    MouseVideoSource source = requireSource(owner, sourceId);
    String normalized = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
    if (!SERVABLE_ROLES.contains(normalized)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Unsupported media role");
    }
    MouseVideoArtifactRef ref;
    if (MouseVideoArtifactRef.ROLE_ORIGINAL.equals(normalized)) {
      ref =
          artifacts
              .findBySourceIdAndRoleAndAnalysisIdIsNullAndState(
                  sourceId, normalized, MouseVideoArtifactRef.STATE_FINALIZED)
              .orElseThrow(MouseVideoCatalogService::unavailable);
    } else {
      MouseVideoAnalysis analysis =
          analysisId == null
              ? selectedCompletedAnalysis(owner, sourceId)
                  .orElseThrow(MouseVideoCatalogService::unavailable)
              : requireCompletedAnalysis(owner, sourceId, analysisId);
      verifyOriginalIdentity(source, analysis);
      ref =
          artifacts
              .findByAnalysisIdAndRoleAndState(
                  analysis.getId(), normalized, MouseVideoArtifactRef.STATE_FINALIZED)
              .orElseThrow(MouseVideoCatalogService::unavailable);
      if (!owner.equals(ref.getOwner())
          || !analysis.getId().equals(ref.getAnalysisId())
          || !sourceId.equals(ref.getSourceId())) {
        throw unavailable();
      }
    }
    boolean inline;
    if (MouseVideoArtifactRef.ROLE_THUMBNAIL.equals(normalized)) {
      inline = INLINE_IMAGE_TYPES.contains(normalizeType(ref.getMediaType()));
    } else if (MouseVideoArtifactRef.ROLE_PROXY.equals(normalized)) {
      inline = INLINE_VIDEO_TYPES.contains(normalizeType(ref.getMediaType()));
    } else {
      // An unqualified original is quarantined and never served; a qualified original uses the
      // server-derived verified media type, not the caller-declared one.
      inline =
          source.isOriginalQualified()
              && INLINE_VIDEO_TYPES.contains(normalizeType(source.getVerifiedMediaType()));
    }
    if (!inline) {
      throw unavailable();
    }
    Path path =
        byteStore.locate(ref.getChecksum()).orElseThrow(MouseVideoCatalogService::unavailable);
    String mediaType = normalizeType(ref.getMediaType());
    return new MediaHandle(path, ref.getSizeBytes(), mediaType, true);
  }

  private static MouseVideoException unavailable() {
    return new MouseVideoException(
        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
        "Requested media is not available as verified inline content");
  }

  private static String normalizeType(String mediaType) {
    return mediaType == null ? "" : mediaType.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private Map<String, Object> summary(
      String owner, MouseVideoSource source, Optional<MouseVideoAnalysis> selected) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", source.getId().toString());
    body.put("kind", source.getSourceKind());
    body.put("declaredOrigin", source.getDeclaredOrigin());
    body.put("originReview", source.getOriginReview());
    body.put("lifecycle", source.getLifecycle());
    body.put("taskKey", source.getTaskKey());
    body.put("skill", source.getSkill());
    body.put("context", source.getContextLabel());
    body.put("place", source.getPlaceLabel());
    body.put("originalChecksum", source.getOriginalChecksum());
    body.put("originalSizeBytes", source.getOriginalSizeBytes());
    body.put("originalMediaType", source.getOriginalMediaType());
    body.put("verifiedMediaType", source.getVerifiedMediaType());
    body.put("originalQualified", source.isOriginalQualified());
    body.put("reviewRevision", source.getReviewRevision());
    body.put("createdAt", source.getCreatedAt() == null ? null : source.getCreatedAt().toString());
    Optional<MouseVideoJob> latest = latestJob(owner, source.getId());
    body.put("status", latest.map(MouseVideoJob::getStatus).orElse("NO_JOB"));
    body.put("stage", latest.map(MouseVideoJob::getStage).orElse(null));
    body.put("selectedAnalysisId", selected.map(a -> a.getId().toString()).orElse(null));
    body.put("coverage", selected.map(MouseVideoAnalysis::getCoverageSummary).orElse(null));
    return body;
  }

  private List<Map<String, Object>> jobViews(String owner, UUID sourceId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (MouseVideoJob job : jobs.findBySourceIdAndOwnerOrderByCreatedAtAsc(sourceId, owner)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", job.getId().toString());
      view.put("kind", job.getKind());
      view.put("stage", job.getStage());
      view.put("status", job.getStatus());
      view.put("progress", job.getProgress());
      view.put("attempt", job.getAttempt());
      view.put("errorCode", job.getErrorCode());
      views.add(view);
    }
    return views;
  }

  private List<Map<String, Object>> artifactViews(
      UUID sourceId, Optional<MouseVideoAnalysis> selected) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (MouseVideoArtifactRef ref : artifacts.findBySourceIdAndAnalysisIdIsNull(sourceId)) {
      views.add(artifactView(ref, "SOURCE"));
    }
    selected.ifPresent(
        analysis -> {
          for (MouseVideoArtifactRef ref : artifacts.findByAnalysisId(analysis.getId())) {
            if (MouseVideoArtifactRef.STATE_FINALIZED.equals(ref.getState())) {
              views.add(artifactView(ref, "ANALYSIS"));
            }
          }
        });
    return views;
  }

  private static Map<String, Object> artifactView(MouseVideoArtifactRef ref, String scope) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("role", ref.getRole());
    view.put("analysisId", ref.getAnalysisId() == null ? null : ref.getAnalysisId().toString());
    view.put("scope", scope);
    view.put("checksum", ref.getChecksum());
    view.put("sizeBytes", ref.getSizeBytes());
    view.put("mediaType", ref.getMediaType());
    view.put("state", ref.getState());
    return view;
  }

  private List<Map<String, Object>> analysisViews(
      String owner, UUID sourceId, Optional<MouseVideoAnalysis> selected) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (MouseVideoAnalysis analysis :
        analyses.findBySourceIdAndOwnerOrderByCreatedAtDesc(sourceId, owner)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", analysis.getId().toString());
      view.put(
          "selected",
          selected.map(chosen -> chosen.getId().equals(analysis.getId())).orElse(false));
      view.put("algorithmId", analysis.getAlgorithmId());
      view.put("status", analysis.getStatus());
      view.put("coverage", analysis.getCoverageSummary());
      view.put("manifestChecksum", analysis.getManifestChecksum());
      view.put("nativeTelemetry", analysis.isNativeTelemetry());
      view.put("exactTicksAvailable", analysis.isExactTicksAvailable());
      view.put("controlAllowed", analysis.isControlAllowed());
      view.put("trainingEligible", analysis.isTrainingEligible());
      views.add(view);
    }
    return views;
  }

  private Optional<MouseVideoJob> latestJob(String owner, UUID sourceId) {
    List<MouseVideoJob> found = jobs.findBySourceIdAndOwnerOrderByCreatedAtAsc(sourceId, owner);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(found.size() - 1));
  }

  private MouseVideoSource requireSource(String owner, UUID sourceId) {
    return sources
        .findByIdAndOwner(sourceId, owner)
        .orElseThrow(
            () ->
                new MouseVideoException(
                    MouseVideoException.CODE_NOT_FOUND, "Source is not available to this owner"));
  }

  private Optional<MouseVideoAnalysis> selectedCompletedAnalysis(String owner, UUID sourceId) {
    return analyses.findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
        sourceId, owner, MouseVideoAnalysis.STATUS_COMPLETE);
  }

  private MouseVideoAnalysis requireCompletedAnalysis(
      String owner, UUID sourceId, UUID analysisId) {
    MouseVideoAnalysis analysis =
        analyses
            .findByIdAndOwner(analysisId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Analysis is not available to this owner"));
    if (!sourceId.equals(analysis.getSourceId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND, "Analysis is not available to this owner");
    }
    if (!MouseVideoAnalysis.STATUS_COMPLETE.equals(analysis.getStatus())) {
      throw unavailable();
    }
    return analysis;
  }

  private static void verifyOriginalIdentity(MouseVideoSource source, MouseVideoAnalysis analysis) {
    String original = source.getOriginalChecksum();
    if (original == null || !original.equals(analysis.getOriginalDigest())) {
      throw unavailable();
    }
  }

  private static int decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int value = Integer.parseInt(cursor.trim());
      return Math.max(0, value);
    } catch (NumberFormatException invalid) {
      throw new MouseVideoException(MouseVideoException.CODE_INVALID_REQUEST, "Invalid cursor");
    }
  }

  private static String encodeCursor(int offset) {
    return Integer.toString(offset);
  }
}
