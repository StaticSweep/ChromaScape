package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoCorrectionContract;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevision;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevisionRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only external-video review. A correction creates a new revision that records its
 * predecessor, reviewer, reason and the base revision it was applied against; earlier revisions and
 * the original observations are never mutated.
 *
 * <p>Three properties are enforced here rather than in the browser:
 *
 * <ul>
 *   <li>Corrections are validated against the versioned {@link MouseVideoCorrectionContract} and
 *       the frozen frame index of the referenced analysis.
 *   <li>A request identifier is idempotent for an identical body and conflicting for a changed
 *       body. Historical rows written before the digest contract are compared through their stored
 *       semantic projection; no digest is invented for them.
 *   <li>A predecessor is never rewritten. Its superseded presentation is derived from successor
 *       lineage, so old revision content and every export pinned to it stay byte-identical.
 * </ul>
 */
@Service
public class MouseVideoReviewService {

  private static final Set<String> ALLOWED_USE =
      Set.of(
          MouseVideoReviewRevision.USE_INSPECTION_ONLY,
          MouseVideoReviewRevision.USE_TRAINING_REQUESTED,
          MouseVideoReviewRevision.USE_TRAINING_DENIED);

  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoReviewRevisionRepository revisions;
  private final MouseVideoFrameIndexService frameIndex;
  private final MouseVideoPreviewService previews;

  public MouseVideoReviewService(
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoReviewRevisionRepository revisions,
      MouseVideoFrameIndexService frameIndex,
      MouseVideoPreviewService previews) {
    this.sources = sources;
    this.analyses = analyses;
    this.revisions = revisions;
    this.frameIndex = frameIndex;
    this.previews = previews;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(String owner, UUID sourceId) {
    requireSource(owner, sourceId);
    List<MouseVideoReviewRevision> history = revisions.findBySourceIdOrderByCreatedAtAsc(sourceId);
    Map<UUID, UUID> successors = successors(history);
    List<Map<String, Object>> items = new ArrayList<>();
    for (MouseVideoReviewRevision revision : history) {
      items.add(view(revision, successors));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("contract", MouseVideoCorrectionContract.VERSION);
    body.put(
        "supportedContracts",
        List.of(
            MouseVideoCorrectionContract.VERSION, MouseVideoCorrectionContract.PROVENANCE_VERSION));
    return body;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(String owner, UUID sourceId, UUID revisionId) {
    requireSource(owner, sourceId);
    MouseVideoReviewRevision revision =
        revisions
            .findByIdAndSourceId(revisionId, sourceId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Review revision is not available"));
    return view(revision, successors(revisions.findBySourceIdOrderByCreatedAtAsc(sourceId)));
  }

  /** Reads immutable revision content without materializing all source history. */
  @Transactional(readOnly = true)
  public Map<String, Object> getSnapshot(String owner, UUID sourceId, UUID revisionId) {
    requireSource(owner, sourceId);
    var revision =
        revisions
            .findByIdAndSourceId(revisionId, sourceId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Review revision is not available"));
    return view(revision, Map.of());
  }

  @Transactional
  public Map<String, Object> create(
      String owner,
      String actor,
      UUID sourceId,
      String requestId,
      UUID analysisId,
      long expectedBaseRevision,
      String reason,
      String sourceUseReview,
      JsonNode corrections) {
    return createInternal(
        owner,
        actor,
        sourceId,
        requestId,
        analysisId,
        expectedBaseRevision,
        reason,
        sourceUseReview,
        corrections,
        true);
  }

  /** Appends a context-bound revision with bounded history work and a flushed immutable result. */
  @Transactional
  public Map<String, Object> createSnapshot(
      String owner,
      String actor,
      UUID sourceId,
      String requestId,
      UUID analysisId,
      long expectedBaseRevision,
      String reason,
      String sourceUseReview,
      JsonNode corrections) {
    return createInternal(
        owner,
        actor,
        sourceId,
        requestId,
        analysisId,
        expectedBaseRevision,
        reason,
        sourceUseReview,
        corrections,
        false);
  }

  private Map<String, Object> createInternal(
      String owner,
      String actor,
      UUID sourceId,
      String requestId,
      UUID analysisId,
      long expectedBaseRevision,
      String reason,
      String sourceUseReview,
      JsonNode corrections,
      boolean includeHistory) {
    MouseVideoSource source =
        sources
            .findForUpdateByIdAndOwner(sourceId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Source is not available to this owner"));
    String use = normalizeUse(sourceUseReview);
    String normalizedReason = requireReason(reason);
    JsonNode submittedCorrections =
        corrections == null ? JsonNodeFactory.instance.arrayNode() : corrections;
    MouseVideoAnalysis analysis = bindAnalysis(owner, sourceId, analysisId);
    MouseVideoCorrectionContract.Bounds bounds = null;
    if (analysis != null && !isEmptyArray(submittedCorrections)) {
      bounds = frameIndex.bounds(owner, sourceId, analysis.getId());
    }
    MouseVideoCorrectionContract.Validated validated =
        MouseVideoCorrectionContract.validate(
            submittedCorrections,
            bounds == null
                ? null
                : new MouseVideoCorrectionContract.Bounds(
                    bounds.frameCount(),
                    Math.max(bounds.maxWidth(), bounds.maxHeight()),
                    Math.max(bounds.maxWidth(), bounds.maxHeight())));
    String requestDigest =
        requestDigest(analysisId, expectedBaseRevision, normalizedReason, use, validated);

    Optional<MouseVideoReviewRevision> existing =
        revisions.findBySourceIdAndRequestId(sourceId, requestId);
    if (existing.isPresent()) {
      MouseVideoReviewRevision revision = existing.get();
      if (!sameRequest(
          revision,
          requestDigest,
          analysisId,
          expectedBaseRevision,
          normalizedReason,
          use,
          submittedCorrections)) {
        throw new MouseVideoException(
            MouseVideoException.CODE_CONFLICT,
            "This request identifier was already used with different review content; submit a new"
                + " request identifier");
      }
      return view(
          revision,
          includeHistory
              ? successors(revisions.findBySourceIdOrderByCreatedAtAsc(sourceId))
              : Map.of());
    }
    if (source.getReviewRevision() != expectedBaseRevision) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT,
          "The review has advanced; reload before applying a correction");
    }
    validateGeometry(owner, sourceId, analysisId, bounds, validated.corrections());
    MouseVideoReviewRevision predecessor =
        revisions.findFirstBySourceIdOrderByCreatedAtDesc(sourceId).orElse(null);
    MouseVideoReviewRevision revision =
        new MouseVideoReviewRevision(
            sourceId,
            analysisId,
            predecessor == null ? null : predecessor.getId(),
            actor,
            normalizedReason,
            expectedBaseRevision,
            use,
            validated.corrections());
    revision.setRequestId(requestId);
    revision.setRequestDigest(requestDigest);
    revision.setContractVersion(validated.version());
    revisions.save(revision);
    source.advanceReviewRevision();
    source.setOriginReview(MouseVideoSource.REVIEW_REVIEWED);
    sources.save(source);
    if (!includeHistory) sources.flush();
    return view(
        revision,
        includeHistory
            ? successors(revisions.findBySourceIdOrderByCreatedAtAsc(sourceId))
            : Map.of());
  }

  /**
   * Authorizes new spatial edits against their exact frame evidence, including display rotation.
   */
  private void validateGeometry(
      String owner,
      UUID sourceId,
      UUID analysisId,
      MouseVideoCorrectionContract.Bounds bounds,
      JsonNode corrections) {
    MouseVideoFrameIndexService.FrozenFrameIndex index = null;
    Map<Long, MouseVideoPreviewService.FrameGeometry> frames = new HashMap<>();
    for (int i = 0; i < corrections.size(); i++) {
      JsonNode correction = corrections.get(i);
      if (!correction.has("x") && !correction.has("y")) continue;
      if (bounds == null || analysisId == null) {
        throw new MouseVideoException(
            MouseVideoException.CODE_INVALID_REQUEST,
            "Geometric corrections require a frozen analysis");
      }
      if (index == null) index = frameIndex.requireIndex(owner, sourceId, analysisId);
      long ordinal = correction.path("frameOrdinal").longValue();
      var geometry = frames.get(ordinal);
      if (geometry == null) {
        geometry =
            previews.requireFrameGeometry(owner, sourceId, analysisId, ordinal, index.checksum());
        frames.put(ordinal, geometry);
      }
      if (geometry == null)
        throw new MouseVideoException(
            MouseVideoException.CODE_SOURCE_UNAVAILABLE, "Exact frame geometry is unavailable");
      try {
        MouseVideoCorrectionContract.validate(
            JsonNodeFactory.instance.arrayNode().add(correction),
            new MouseVideoCorrectionContract.Bounds(
                bounds.frameCount(), geometry.sourceWidth(), geometry.sourceHeight()));
      } catch (MouseVideoException invalid) {
        throw new MouseVideoException(
            MouseVideoException.CODE_INVALID_REQUEST,
            "Correction " + i + " exceeds the exact source frame dimensions",
            invalid);
      }
    }
  }

  /** Retries retain their immutable stored result without requiring a new preview. */
  private boolean sameRequest(
      MouseVideoReviewRevision revision,
      String requestDigest,
      UUID analysisId,
      long expectedBaseRevision,
      String reason,
      String use,
      JsonNode corrections) {
    if (revision.getRequestDigest() != null) {
      return revision.getRequestDigest().equals(requestDigest);
    }
    return equalIds(revision.getAnalysisId(), analysisId)
        && revision.getExpectedBaseRevision() == expectedBaseRevision
        && revision.getReason().equals(reason)
        && revision.getSourceUseReview().equals(use)
        && canonical(revision.getCorrectionsJson()).equals(canonical(corrections));
  }

  private MouseVideoAnalysis bindAnalysis(String owner, UUID sourceId, UUID analysisId) {
    if (analysisId == null) {
      return null;
    }
    MouseVideoAnalysis analysis =
        analyses
            .findByIdAndOwner(analysisId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Analysis is not available"));
    if (!analysis.getSourceId().equals(sourceId)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Analysis does not belong to this source");
    }
    return analysis;
  }

  private static String normalizeUse(String sourceUseReview) {
    String use =
        sourceUseReview == null || sourceUseReview.isBlank()
            ? MouseVideoReviewRevision.USE_INSPECTION_ONLY
            : sourceUseReview.trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_USE.contains(use)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Unsupported source-use decision");
    }
    return use;
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "A review reason is required");
    }
    String trimmed = reason.trim();
    return trimmed.length() <= 1024 ? trimmed : trimmed.substring(0, 1024);
  }

  /**
   * Binds the validated correction payload to its analysis, base revision, reason and source-use
   * decision, so reusing a request identifier with any changed field is a conflict.
   */
  private static String requestDigest(
      UUID analysisId,
      long expectedBaseRevision,
      String reason,
      String use,
      MouseVideoCorrectionContract.Validated validated) {
    String material =
        validated.version()
            + '\n'
            + (analysisId == null ? "" : analysisId.toString())
            + '\n'
            + expectedBaseRevision
            + '\n'
            + use
            + '\n'
            + reason
            + '\n'
            + validated.digest();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:"
          + HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  private static String canonical(JsonNode node) {
    return node == null ? "null" : node.toString();
  }

  private static boolean isEmptyArray(JsonNode node) {
    return node.isArray() && node.isEmpty();
  }

  private static boolean equalIds(UUID left, UUID right) {
    return left == null ? right == null : left.equals(right);
  }

  /** Maps each revision to the revision that directly succeeds it, without mutating history. */
  private static Map<UUID, UUID> successors(List<MouseVideoReviewRevision> history) {
    Map<UUID, UUID> successors = new HashMap<>();
    for (MouseVideoReviewRevision revision : history) {
      if (revision.getPredecessorId() != null) {
        successors.put(revision.getPredecessorId(), revision.getId());
      }
    }
    return successors;
  }

  private MouseVideoSource requireSource(String owner, UUID sourceId) {
    return sources
        .findByIdAndOwner(sourceId, owner)
        .orElseThrow(
            () ->
                new MouseVideoException(
                    MouseVideoException.CODE_NOT_FOUND, "Source is not available to this owner"));
  }

  private Map<String, Object> view(MouseVideoReviewRevision revision, Map<UUID, UUID> successors) {
    UUID successorId = successors.get(revision.getId());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", revision.getId().toString());
    body.put("sourceId", revision.getSourceId().toString());
    body.put(
        "analysisId",
        revision.getAnalysisId() == null ? null : revision.getAnalysisId().toString());
    body.put(
        "predecessorId",
        revision.getPredecessorId() == null ? null : revision.getPredecessorId().toString());
    body.put("supersededBy", successorId == null ? null : successorId.toString());
    body.put("actor", revision.getActor());
    body.put("reason", revision.getReason());
    body.put("expectedBaseRevision", revision.getExpectedBaseRevision());
    body.put("sourceUse", revision.getSourceUseReview());
    body.put(
        "state",
        successorId == null ? revision.getState() : MouseVideoReviewRevision.STATE_SUPERSEDED);
    body.put("contract", revision.getContractVersion());
    body.put("corrections", revision.getCorrectionsJson());
    body.put(
        "createdAt", revision.getCreatedAt() == null ? null : revision.getCreatedAt().toString());
    return body;
  }
}
