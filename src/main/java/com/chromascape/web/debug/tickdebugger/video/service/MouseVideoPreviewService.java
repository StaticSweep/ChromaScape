package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only exact-preview metadata and byte access. A read never starts FFmpeg: only a READY row
 * with verified content-addressed bytes can be served. Metadata is cached in a small bounded map
 * keyed by the immutable preview identity; byte access always re-checks the store.
 */
@Service
public class MouseVideoPreviewService {

  private static final int CACHE_ENTRIES = 64;

  /** Public-only state: the preview row stays PENDING until reconciliation resolves its job. */
  public static final String STATE_RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";

  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoJobRepository jobs;

  private final Map<String, MouseVideoFramePreview> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, MouseVideoFramePreview> eldest) {
              return size() > CACHE_ENTRIES;
            }
          });

  public MouseVideoPreviewService(
      MouseVideoFramePreviewRepository previews,
      MouseVideoByteStore byteStore,
      MouseVideoJobRepository jobs) {
    this.previews = previews;
    this.byteStore = byteStore;
    this.jobs = jobs;
  }

  /** Resolved servable preview bytes. */
  public record PreviewMedia(Path path, long sizeBytes, String mediaType) {}

  /** Source display coordinates proven by an immutable exact-frame preview. */
  public record FrameGeometry(int sourceWidth, int sourceHeight) {}

  @Transactional(readOnly = true)
  public FrameGeometry requireFrameGeometry(
      String owner, UUID sourceId, UUID analysisId, long ordinal, String indexChecksum) {
    MouseVideoFramePreview preview =
        previews
            .findFirstByAnalysisIdAndFrameOrdinalAndStateOrderByCreatedAtDesc(
                analysisId, ordinal, MouseVideoFramePreview.STATE_READY)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "An exact frame preview is required for geometric corrections"));
    if (!owner.equals(preview.getOwner())
        || !sourceId.equals(preview.getSourceId())
        || !analysisId.equals(preview.getAnalysisId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND,
          "Exact frame evidence is not available to this owner");
    }
    if (!MouseVideoFramePreview.STATE_READY.equals(preview.getState())
        || !indexChecksum.equals(preview.getIndexChecksum())
        || preview.getFrameOrdinal() != ordinal
        || preview.getSourceWidth() == null
        || preview.getSourceHeight() == null
        || preview.getSourceWidth() <= 0
        || preview.getSourceHeight() <= 0
        || !"image/png".equals(preview.getMediaType())
        || preview.getChecksum() == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Exact frame geometry does not match the frozen analysis");
    }
    Path path =
        byteStore
            .locate(preview.getChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Exact frame evidence bytes are unavailable"));
    try {
      if (preview.getSizeBytes() == null || Files.size(path) != preview.getSizeBytes()) {
        throw new java.io.IOException("Preview size changed");
      }
    } catch (java.io.IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Exact frame evidence bytes are unavailable",
          failure);
    }
    return new FrameGeometry(preview.getSourceWidth(), preview.getSourceHeight());
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String owner, UUID previewId) {
    return view(require(owner, previewId));
  }

  @Transactional(readOnly = true)
  public PreviewMedia media(String owner, UUID previewId) {
    MouseVideoFramePreview preview = require(owner, previewId);
    if (!MouseVideoFramePreview.STATE_READY.equals(preview.getState())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE, "The exact-frame preview is not ready yet");
    }
    Path path =
        byteStore
            .locate(preview.getChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Published preview bytes are unavailable"));
    try {
      if (Files.size(path) != preview.getSizeBytes()) {
        throw new MouseVideoException(
            MouseVideoException.CODE_SOURCE_UNAVAILABLE,
            "Published preview bytes do not match their recorded size");
      }
    } catch (java.io.IOException unreadable) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Published preview bytes are unreadable",
          unreadable);
    }
    return new PreviewMedia(path, preview.getSizeBytes(), preview.getMediaType());
  }

  /** Lists the previews already requested for one analysis, newest identity first. */
  @Transactional(readOnly = true)
  public java.util.List<Map<String, Object>> list(
      String owner, java.util.UUID sourceId, java.util.UUID analysisId) {
    java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (MouseVideoFramePreview preview :
        previews.findByAnalysisIdOrderByFrameOrdinalAsc(analysisId)) {
      if (owner.equals(preview.getOwner()) && sourceId.equals(preview.getSourceId())) {
        items.add(view(preview));
      }
    }
    return items;
  }

  /** Resolves one exact preview for a READY frame, or empty when no preview has been published. */
  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> latestReady(
      String owner, UUID analysisId, long frameOrdinal) {
    return previews
        .findFirstByAnalysisIdAndFrameOrdinalAndStateOrderByCreatedAtDesc(
            analysisId, frameOrdinal, MouseVideoFramePreview.STATE_READY)
        .filter(preview -> owner.equals(preview.getOwner()))
        .map(this::view);
  }

  public void evict(UUID previewId) {
    if (previewId != null) {
      cache.remove(previewId.toString());
    }
  }

  private MouseVideoFramePreview require(String owner, UUID previewId) {
    String key = previewId == null ? "" : previewId.toString();
    MouseVideoFramePreview cached = cache.get(key);
    if (cached != null && owner.equals(cached.getOwner())) {
      return cached;
    }
    MouseVideoFramePreview preview =
        previews
            .findByIdAndOwner(previewId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Frame preview is not available to this owner"));
    if (MouseVideoFramePreview.STATE_READY.equals(preview.getState())) {
      cache.put(key, preview);
    }
    return preview;
  }

  private Map<String, Object> view(MouseVideoFramePreview preview) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("previewId", preview.getId().toString());
    view.put("sourceId", preview.getSourceId().toString());
    view.put("analysisId", preview.getAnalysisId().toString());
    view.put("state", publicState(preview));
    view.put("frameOrdinal", preview.getFrameOrdinal());
    view.put("pts", preview.getFramePts());
    view.put("ptsTime", preview.getFramePtsTime());
    view.put("timeBase", timeBase(preview));
    view.put("indexChecksum", preview.getIndexChecksum());
    view.put("method", preview.getPreviewMethod());
    view.put("maxWidth", preview.getMaxWidth());
    view.put("maxHeight", preview.getMaxHeight());
    view.put("sourceWidth", preview.getSourceWidth());
    view.put("sourceHeight", preview.getSourceHeight());
    view.put("outputWidth", preview.getOutputWidth());
    view.put("outputHeight", preview.getOutputHeight());
    view.put("rotationDegrees", preview.getRotationDegrees());
    view.put("crop", preview.getCropJson());
    view.put("transform", preview.getTransformJson());
    view.put("checksum", preview.getChecksum());
    view.put("sizeBytes", preview.getSizeBytes());
    view.put("mediaType", preview.getMediaType());
    view.put("failureCode", preview.getFailureCode());
    view.put("jobId", preview.getJobId() == null ? null : preview.getJobId().toString());
    view.put("createdAt", timestamp(preview.getCreatedAt()));
    view.put("readyAt", timestamp(preview.getReadyAt()));
    view.put("metadataUrl", "/api/debug/mouse-videos/frame-previews/" + preview.getId());
    view.put(
        "mediaUrl",
        MouseVideoFramePreview.STATE_READY.equals(preview.getState())
            ? "/api/debug/mouse-videos/frame-previews/" + preview.getId() + "/media"
            : null);
    return view;
  }

  /**
   * A pending preview whose bound job is awaiting operator reconciliation is reported as such, so
   * clients stop polling work that no worker will finish. Settled previews report their own state.
   */
  private String publicState(MouseVideoFramePreview preview) {
    if (!MouseVideoFramePreview.STATE_PENDING.equals(preview.getState())
        || preview.getJobId() == null) {
      return preview.getState();
    }
    return jobs.findById(preview.getJobId())
        .filter(job -> MouseVideoJob.STATUS_NEEDS_RECONCILIATION.equals(job.getStatus()))
        .map(job -> STATE_RECONCILIATION_REQUIRED)
        .orElse(preview.getState());
  }

  private static Map<String, Object> timeBase(MouseVideoFramePreview preview) {
    Map<String, Object> timeBase = new LinkedHashMap<>();
    timeBase.put("numerator", preview.getTimeBaseNumerator());
    timeBase.put("denominator", preview.getTimeBaseDenominator());
    timeBase.put(
        "raw",
        preview.getTimeBaseNumerator() == null || preview.getTimeBaseDenominator() == null
            ? null
            : preview.getTimeBaseNumerator() + "/" + preview.getTimeBaseDenominator());
    return timeBase;
  }

  private static String timestamp(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
