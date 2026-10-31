package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoCorrectionContract;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoExport;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoExportRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevision;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevisionRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and retrieves immutable, versioned {@code mouse-video-evidence/v1} export snapshots.
 * Identity pins the source original digest, the analysis identity and manifest hash, the review
 * revision, acquisition evidence and contract version. Identical creation retries return the
 * existing snapshot; newer analysis/review evidence yields a distinct version while old downloads
 * remain byte-identical.
 */
@Service
public class MouseVideoExportService {

  public static final String CONTRACT = "mouse-video-evidence/v1";
  public static final String CONTRACT_VERSION = "export/v1";
  private static final long MAX_MANIFEST_BYTES = 8L * 1024L * 1024L;

  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoReviewRevisionRepository reviews;
  private final MouseVideoExportRepository exports;
  private final MouseVideoFramePreviewRepository previews;
  private final MouseVideoByteStore byteStore;
  private final ObjectMapper mapper;

  public MouseVideoExportService(
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoReviewRevisionRepository reviews,
      MouseVideoExportRepository exports,
      MouseVideoFramePreviewRepository previews,
      MouseVideoByteStore byteStore,
      ObjectMapper mapper) {
    this.sources = sources;
    this.analyses = analyses;
    this.artifacts = artifacts;
    this.reviews = reviews;
    this.exports = exports;
    this.previews = previews;
    this.byteStore = byteStore;
    this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  /** A created or reused export plus its manifest bytes. */
  public record ExportBundle(boolean created, MouseVideoExport snapshot, String json) {}

  /** Retrieved immutable export bytes. */
  public record ExportHandle(MouseVideoExport snapshot, Path path, long sizeBytes) {}

  @Transactional(readOnly = true)
  public Map<String, Object> describe(UUID sourceId, String owner) {
    MouseVideoSource source = requireSource(sourceId, owner);
    MouseVideoAnalysis analysis = latestAnalysis(source);
    return manifestMap(
        source, analysis, combinedArtifacts(sourceId, analysis), readyPreviews(analysis), null);
  }

  @Transactional
  public ExportBundle create(
      String owner, UUID sourceId, String requestId, UUID analysisId, Long reviewRevision) {
    try (var publication = byteStore.publicationLease()) {
      artifacts.lockPublication();
      return createGuarded(owner, sourceId, requestId, analysisId, reviewRevision);
    }
  }

  private ExportBundle createGuarded(
      String owner, UUID sourceId, String requestId, UUID analysisId, Long reviewRevision) {
    MouseVideoSource source = requireSource(sourceId, owner);
    MouseVideoAnalysis analysis = resolveAnalysis(owner, sourceId, analysisId);
    long revision = source.getReviewRevision();
    if (reviewRevision != null && reviewRevision != revision) {
      // Only the current append-only revision can be pinned; older revisions are superseded and do
      // not have a stable ordinal mapping, so inventing one would fabricate lineage.
      throw new MouseVideoException(
          MouseVideoException.CODE_CONFLICT,
          "The requested review revision is not the current append-only revision");
    }
    List<MouseVideoArtifactRef> refs = combinedArtifacts(sourceId, analysis);
    List<MouseVideoFramePreview> pinnedPreviews = readyPreviews(analysis);
    String digest = exportDigest(owner, source, analysis, refs, pinnedPreviews, revision);
    Optional<MouseVideoExport> byRequest = exports.findByOwnerAndRequestId(owner, requestId);
    if (byRequest.isPresent()) {
      MouseVideoExport existing = byRequest.get();
      if (!existing.getRequestDigest().equals(digest)) {
        throw new MouseVideoException(
            MouseVideoException.CODE_CONFLICT,
            "This export request identifier was already used with different pinned evidence");
      }
      return new ExportBundle(false, existing, readManifest(existing).orElse(null));
    }
    Optional<MouseVideoExport> existing = exports.findBySourceIdAndRequestDigest(sourceId, digest);
    if (existing.isPresent()) {
      return new ExportBundle(false, existing.get(), readManifest(existing.get()).orElse(null));
    }
    Map<String, Object> manifest = manifestMap(source, analysis, refs, pinnedPreviews, revision);
    byte[] content;
    try {
      content = mapper.writeValueAsBytes(manifest);
    } catch (IOException impossible) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Unable to serialize evidence manifest");
    }
    if (content.length > MAX_MANIFEST_BYTES) {
      throw new MouseVideoException(
          MouseVideoException.CODE_MEDIA_LIMIT, "Evidence manifest exceeds its size limit");
    }
    MouseVideoByteStore.StagedArtifact staged;
    try {
      staged = byteStore.stage(new java.io.ByteArrayInputStream(content), MAX_MANIFEST_BYTES);
      byteStore.publish(staged);
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Unable to publish evidence manifest", failure);
    }
    MouseVideoExport snapshot =
        new MouseVideoExport(
            owner,
            sourceId,
            requestId,
            digest,
            CONTRACT,
            analysis == null ? null : analysis.getId(),
            revision,
            staged.checksum(),
            staged.sizeBytes());
    try {
      exports.saveAndFlush(snapshot);
    } catch (DataIntegrityViolationException raced) {
      Optional<MouseVideoExport> byKey = exports.findByOwnerAndRequestId(owner, requestId);
      if (byKey.isPresent()) {
        MouseVideoExport found = byKey.get();
        if (!found.getRequestDigest().equals(digest)) {
          throw new MouseVideoException(
              MouseVideoException.CODE_CONFLICT,
              "This export request identifier was already used with different pinned evidence");
        }
        return new ExportBundle(false, found, new String(content, StandardCharsets.UTF_8));
      }
      return exports
          .findBySourceIdAndRequestDigest(sourceId, digest)
          .map(found -> new ExportBundle(false, found, new String(content, StandardCharsets.UTF_8)))
          .orElseThrow(() -> raced);
    }
    MouseVideoArtifactRef ref =
        new MouseVideoArtifactRef(
            owner,
            sourceId,
            null,
            MouseVideoArtifactRef.ROLE_EXPORT,
            staged.checksum(),
            staged.sizeBytes(),
            "application/json");
    ref.setExportId(snapshot.getId());
    ref.finalizeRow();
    artifacts.save(ref);
    return new ExportBundle(true, snapshot, new String(content, StandardCharsets.UTF_8));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(String owner, UUID sourceId) {
    requireSource(sourceId, owner);
    List<Map<String, Object>> items = new ArrayList<>();
    for (MouseVideoExport snapshot :
        exports.findBySourceIdAndOwnerOrderByCreatedAtAsc(sourceId, owner)) {
      items.add(view(snapshot));
    }
    return items;
  }

  @Transactional(readOnly = true)
  public ExportHandle retrieve(String owner, UUID exportId) {
    MouseVideoExport snapshot =
        exports
            .findByIdAndOwner(exportId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Export is not available to this owner"));
    Path path =
        byteStore
            .locate(snapshot.getManifestChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Export bytes are missing"));
    return new ExportHandle(snapshot, path, snapshot.getSizeBytes());
  }

  @Transactional(readOnly = true)
  public String manifestJson(String owner, UUID exportId) {
    ExportHandle handle = retrieve(owner, exportId);
    try {
      return Files.readString(handle.path(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Export manifest could not be read", failure);
    }
  }

  private Optional<String> readManifest(MouseVideoExport snapshot) {
    return byteStore
        .locate(snapshot.getManifestChecksum())
        .flatMap(
            path -> {
              try {
                return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
              } catch (IOException failure) {
                return Optional.empty();
              }
            });
  }

  private MouseVideoAnalysis resolveAnalysis(String owner, UUID sourceId, UUID analysisId) {
    if (analysisId != null) {
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
    List<MouseVideoAnalysis> found =
        analyses.findBySourceIdAndOwnerOrderByCreatedAtDesc(sourceId, owner);
    return found.isEmpty() ? null : found.get(0);
  }

  private Map<String, Object> manifestMap(
      MouseVideoSource source,
      MouseVideoAnalysis analysis,
      List<MouseVideoArtifactRef> refs,
      List<MouseVideoFramePreview> pinnedPreviews,
      Long pinnedReviewRevision) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("contract", CONTRACT);
    manifest.put("contractVersion", CONTRACT_VERSION);
    manifest.put(
        "exportVersion",
        exportDigest(
            source.getOwner(),
            source,
            analysis,
            refs,
            pinnedPreviews,
            pinnedReviewRevision == null ? source.getReviewRevision() : pinnedReviewRevision));
    manifest.put("source", sourceBlock(source));
    manifest.put("acquisition", acquisitionBlock(source));
    manifest.put("media", mediaBlock(analysis));
    manifest.put("analysis", analysisBlock(analysis));
    manifest.put("artifacts", artifactBlocks(refs));
    manifest.put("previews", previewBlocks(pinnedPreviews));
    manifest.put("review", reviewBlock(source, pinnedReviewRevision));
    manifest.put(
        "capabilities",
        Map.of(
            "nativeTelemetry", false,
            "exactTicksAvailable", false,
            "controlAllowed", false,
            "trainingEligible", false));
    manifest.put(
        "notes",
        List.of(
            "Video-derived observations are estimates; no native ticks or physical clicks are"
                + " represented."));
    return manifest;
  }

  private String exportDigest(
      String owner,
      MouseVideoSource source,
      MouseVideoAnalysis analysis,
      List<MouseVideoArtifactRef> refs,
      List<MouseVideoFramePreview> pinnedPreviews,
      long reviewRevision) {
    TreeMap<String, Object> canonical = new TreeMap<>();
    canonical.put("contract", CONTRACT);
    canonical.put("contractVersion", CONTRACT_VERSION);
    canonical.put("owner", owner);
    canonical.put("sourceId", source.getId().toString());
    canonical.put("originalDigest", orEmpty(source.getOriginalChecksum()));
    canonical.put("analysisId", analysis == null ? "" : analysis.getId().toString());
    canonical.put(
        "analysisManifest", analysis == null ? "" : orEmpty(analysis.getManifestChecksum()));
    canonical.put("reviewRevision", reviewRevision);
    List<String> artifactIdentities = new ArrayList<>();
    for (MouseVideoArtifactRef ref : refs) {
      artifactIdentities.add(ref.getRole() + ":" + ref.getChecksum() + ":" + ref.getSizeBytes());
    }
    java.util.Collections.sort(artifactIdentities);
    canonical.put("artifacts", artifactIdentities);
    List<String> previewIdentities = new ArrayList<>();
    for (MouseVideoFramePreview preview : pinnedPreviews) {
      previewIdentities.add(
          preview.getFrameOrdinal()
              + ":"
              + orEmpty(preview.getFramePts())
              + ":"
              + orEmpty(preview.getChecksum())
              + ":"
              + preview.getOutputWidth()
              + "x"
              + preview.getOutputHeight());
    }
    java.util.Collections.sort(previewIdentities);
    canonical.put("previews", previewIdentities);
    try {
      byte[] json = mapper.writeValueAsBytes(canonical);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return "sha256:" + HexFormat.of().formatHex(digest.digest(json));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Export identity could not be computed");
    }
  }

  private Map<String, Object> view(MouseVideoExport snapshot) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", snapshot.getId().toString());
    body.put("sourceId", snapshot.getSourceId().toString());
    body.put("contract", snapshot.getContract());
    body.put(
        "analysisId",
        snapshot.getAnalysisId() == null ? null : snapshot.getAnalysisId().toString());
    body.put("reviewRevision", snapshot.getReviewRevision());
    body.put("manifestChecksum", snapshot.getManifestChecksum());
    body.put("sizeBytes", snapshot.getSizeBytes());
    body.put(
        "createdAt", snapshot.getCreatedAt() == null ? null : snapshot.getCreatedAt().toString());
    return body;
  }

  private Map<String, Object> sourceBlock(MouseVideoSource source) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("id", source.getId().toString());
    block.put("kind", source.getSourceKind());
    block.put("owner", source.getOwner());
    block.put("providerId", orEmpty(source.getProviderId()));
    block.put("declaredOrigin", source.getDeclaredOrigin());
    block.put("originReview", source.getOriginReview());
    block.put("lifecycle", source.getLifecycle());
    block.put("createdAt", source.getCreatedAt() == null ? null : source.getCreatedAt().toString());
    return block;
  }

  private Map<String, Object> acquisitionBlock(MouseVideoSource source) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("kind", source.getSourceKind());
    block.put("originalChecksum", orEmpty(source.getOriginalChecksum()));
    block.put("originalSizeBytes", source.getOriginalSizeBytes());
    block.put("originalMediaType", orEmpty(source.getOriginalMediaType()));
    block.put("originalQualified", source.isOriginalQualified());
    return block;
  }

  private Map<String, Object> mediaBlock(MouseVideoAnalysis analysis) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("originalDigest", analysis == null ? "" : analysis.getOriginalDigest());
    block.put("coverageSummary", analysis == null ? "" : orEmpty(analysis.getCoverageSummary()));
    block.put("status", analysis == null ? "UNAVAILABLE" : analysis.getStatus());
    return block;
  }

  private Map<String, Object> analysisBlock(MouseVideoAnalysis analysis) {
    Map<String, Object> block = new LinkedHashMap<>();
    if (analysis == null) {
      block.put("available", false);
      return block;
    }
    block.put("available", true);
    block.put("id", analysis.getId().toString());
    block.put("algorithmId", analysis.getAlgorithmId());
    block.put("templatePackId", orEmpty(analysis.getTemplatePackId()));
    block.put("configDigest", analysis.getConfigDigest());
    block.put("toolchainDigest", analysis.getToolchainDigest());
    block.put("manifestChecksum", orEmpty(analysis.getManifestChecksum()));
    return block;
  }

  private List<Map<String, Object>> artifactBlocks(List<MouseVideoArtifactRef> refs) {
    List<Map<String, Object>> blocks = new ArrayList<>();
    for (MouseVideoArtifactRef ref : refs) {
      Map<String, Object> block = new LinkedHashMap<>();
      block.put("role", ref.getRole());
      block.put("checksum", ref.getChecksum());
      block.put("sizeBytes", ref.getSizeBytes());
      block.put("mediaType", orEmpty(ref.getMediaType()));
      block.put("state", ref.getState());
      blocks.add(block);
    }
    return blocks;
  }

  private Map<String, Object> reviewBlock(MouseVideoSource source, Long pinnedReviewRevision) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put(
        "revision",
        pinnedReviewRevision == null ? source.getReviewRevision() : pinnedReviewRevision);
    List<MouseVideoReviewRevision> revisions =
        reviews.findBySourceIdOrderByCreatedAtAsc(source.getId());
    block.put("count", revisions.size());
    if (!revisions.isEmpty()) {
      MouseVideoReviewRevision latest = revisions.get(revisions.size() - 1);
      block.put("latestId", latest.getId().toString());
      block.put("sourceUse", latest.getSourceUseReview());
      block.put("state", latest.getState());
      if (MouseVideoCorrectionContract.PROVENANCE_VERSION.equals(latest.getContractVersion())) {
        block.put("contract", latest.getContractVersion());
        block.put(
            "correctionsDigest",
            MouseVideoCorrectionContract.digestOf(latest.getCorrectionsJson()));
        block.put("independenceVerified", false);
        java.util.Set<String> provenance = new java.util.TreeSet<>();
        for (JsonNode correction : latest.getCorrectionsJson()) {
          provenance.add(correction.path("provenance").asText("UNKNOWN"));
        }
        block.put("provenance", provenance);
      }
    } else {
      block.put("latestId", "");
      block.put("sourceUse", MouseVideoReviewRevision.USE_INSPECTION_ONLY);
      block.put("state", "NONE");
    }
    return block;
  }

  private MouseVideoSource requireSource(UUID sourceId, String owner) {
    return sources
        .findByIdAndOwner(sourceId, owner)
        .orElseThrow(
            () ->
                new MouseVideoException(
                    MouseVideoException.CODE_NOT_FOUND, "Source is not available to this owner"));
  }

  private MouseVideoAnalysis latestAnalysis(MouseVideoSource source) {
    List<MouseVideoAnalysis> found =
        analyses.findBySourceIdAndOwnerOrderByCreatedAtDesc(source.getId(), source.getOwner());
    return found.isEmpty() ? null : found.get(0);
  }

  private List<MouseVideoArtifactRef> combinedArtifacts(
      UUID sourceId, MouseVideoAnalysis analysis) {
    List<MouseVideoArtifactRef> combined = new ArrayList<>(sourceArtifacts(sourceId));
    if (analysis != null) {
      combined.addAll(
          artifacts.findByAnalysisId(analysis.getId()).stream()
              .filter(ref -> MouseVideoArtifactRef.STATE_FINALIZED.equals(ref.getState()))
              .toList());
    }
    return combined;
  }

  private List<MouseVideoFramePreview> readyPreviews(MouseVideoAnalysis analysis) {
    if (analysis == null) {
      return List.of();
    }
    return previews.findByAnalysisIdOrderByFrameOrdinalAsc(analysis.getId()).stream()
        .filter(preview -> MouseVideoFramePreview.STATE_READY.equals(preview.getState()))
        .filter(preview -> preview.getChecksum() != null)
        .toList();
  }

  private List<Map<String, Object>> previewBlocks(List<MouseVideoFramePreview> pinnedPreviews) {
    List<Map<String, Object>> blocks = new ArrayList<>();
    for (MouseVideoFramePreview preview : pinnedPreviews) {
      Map<String, Object> block = new LinkedHashMap<>();
      block.put("frameOrdinal", preview.getFrameOrdinal());
      block.put("pts", preview.getFramePts());
      block.put("indexChecksum", preview.getIndexChecksum());
      block.put("method", preview.getPreviewMethod());
      block.put("checksum", preview.getChecksum());
      block.put("sizeBytes", preview.getSizeBytes());
      block.put("mediaType", preview.getMediaType());
      block.put("sourceWidth", preview.getSourceWidth());
      block.put("sourceHeight", preview.getSourceHeight());
      block.put("outputWidth", preview.getOutputWidth());
      block.put("outputHeight", preview.getOutputHeight());
      block.put("rotationDegrees", preview.getRotationDegrees());
      blocks.add(block);
    }
    return blocks;
  }

  private List<MouseVideoArtifactRef> sourceArtifacts(UUID sourceId) {
    return artifacts.findBySourceIdAndAnalysisIdIsNull(sourceId).stream()
        .filter(ref -> !MouseVideoArtifactRef.ROLE_EXPORT.equals(ref.getRole()))
        .filter(ref -> !MouseVideoArtifactRef.ROLE_REVIEW_EXPORT.equals(ref.getRole()))
        .toList();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
