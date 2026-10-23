package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.VideoProbeResult;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Bounded visual producer for one scheduled analysis. The fixed server-owned ffmpeg/ffprobe
 * binaries decode a bounded sample of source frames; the analyzer derives motion/cut/activity/
 * indicator observations and an honest quality document. The worker publishes immutable bytes and
 * returns an outcome for the caller to settle inside the job fence.
 */
@Component
public class MouseVideoAnalysisWorker {

  private final MouseVideoProperties properties;
  private final MouseVideoProcessRunner runner;
  private final MouseVideoProbeWorker probeWorker;
  private final MouseVideoDerivativeWorker derivativeWorker;
  private final MouseVideoVisualAnalyzer analyzer;
  private final MouseVideoByteStore byteStore;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final ObjectMapper mapper;
  private final MouseVideoAnalysisIdentity identity;

  public MouseVideoAnalysisWorker(
      MouseVideoProperties properties,
      MouseVideoProcessRunner runner,
      MouseVideoProbeWorker probeWorker,
      MouseVideoDerivativeWorker derivativeWorker,
      MouseVideoVisualAnalyzer analyzer,
      MouseVideoByteStore byteStore,
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoArtifactRefRepository artifacts,
      ObjectMapper mapper) {
    this.properties = properties;
    this.runner = runner;
    this.probeWorker = probeWorker;
    this.derivativeWorker = derivativeWorker;
    this.analyzer = analyzer;
    this.byteStore = byteStore;
    this.sources = sources;
    this.analyses = analyses;
    this.artifacts = artifacts;
    this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.identity = new MouseVideoAnalysisIdentity(properties, mapper);
  }

  /** The completed analysis plus every artifact reference to register in the fenced transaction. */
  public record Outcome(
      MouseVideoAnalysis analysis,
      List<MouseVideoFinalizationService.ArtifactSpec> artifacts,
      String coverage) {}

  public Outcome run(
      MouseVideoCancellation cancellation, UUID sourceId, UUID analysisId, Path workspace)
      throws IOException, InterruptedException {
    Files.createDirectories(workspace);
    MouseVideoSource source =
        sources
            .findById(sourceId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Source is missing"));
    MouseVideoAnalysis analysis =
        analyses
            .findById(analysisId)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND, "Analysis is missing"));
    if (source.getOriginalChecksum() == null
        || !source.getOriginalChecksum().equals(analysis.getOriginalDigest())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Source original no longer matches the analysis identity");
    }
    // Execute only the configuration the identity was scheduled under, never mutable live state.
    identity.requireUnchanged(analysis, source.getVerifiedMediaType());
    Path media =
        byteStore
            .locate(source.getOriginalChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Original media bytes are missing"));
    checkCancelled(cancellation);
    VideoProbeResult probe = probeWorker.probe(workspace, media, cancellation);
    if (!probe.ok() || !probe.hasVideo()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Analysis probe did not produce usable facts");
    }
    int sourceWidth = MouseVideoPreviewWorker.displayWidth(probe);
    int sourceHeight = MouseVideoPreviewWorker.displayHeight(probe);

    List<MouseVideoFinalizationService.ArtifactSpec> specs = new ArrayList<>();
    MouseVideoArtifactRef index =
        artifacts
            .findFirstBySourceIdAndRoleAndStateOrderByCreatedAtDesc(
                sourceId,
                MouseVideoArtifactRef.ROLE_FRAME_INDEX,
                MouseVideoArtifactRef.STATE_FINALIZED)
            .orElse(null);
    if (index == null) {
      MouseVideoDerivativeWorker.Derivatives derived =
          derivativeWorker.derive(workspace, media, cancellation);
      for (MouseVideoDerivativeWorker.DerivedArtifact artifact : derived.artifacts()) {
        specs.add(
            new MouseVideoFinalizationService.ArtifactSpec(
                true,
                artifact.role(),
                artifact.checksum(),
                artifact.sizeBytes(),
                artifact.mediaType(),
                false));
      }
      index =
          artifacts
              .findFirstBySourceIdAndRoleAndStateOrderByCreatedAtDesc(
                  sourceId,
                  MouseVideoArtifactRef.ROLE_FRAME_INDEX,
                  MouseVideoArtifactRef.STATE_FINALIZED)
              .orElse(null);
      if (index == null) {
        throw new MouseVideoException(
            MouseVideoException.CODE_DECODER_FAILED, "No frozen frame index could be produced");
      }
    } else {
      specs.add(
          new MouseVideoFinalizationService.ArtifactSpec(
              true,
              MouseVideoArtifactRef.ROLE_FRAME_INDEX,
              index.getChecksum(),
              index.getSizeBytes(),
              index.getMediaType(),
              false));
      reuse(specs, sourceId, MouseVideoArtifactRef.ROLE_PROXY);
      reuse(specs, sourceId, MouseVideoArtifactRef.ROLE_THUMBNAIL);
    }

    MouseVideoFrameIndexService.FrozenFrameIndex frozen =
        readIndex(byteStore.locate(index.getChecksum()).orElseThrow());
    int step =
        MouseVideoSampleStride.compute(
            frozen, properties.getVisualSampleFps(), properties.getMaxVisualFrames());
    Path frames = workspace.resolve("visual");
    Files.createDirectories(frames);
    if (step <= 0 || frozen.frames().isEmpty()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "No source frames are available for analysis");
    }
    extractSamples(cancellation, media, frames, step);
    Path template = frozenTemplate(analysis, workspace);
    MouseVideoVisualAnalyzer.Result result =
        analyzer.analyze(
            frozen.frames(),
            frames,
            step,
            sourceWidth,
            sourceHeight,
            probe.durationSeconds(),
            template);

    String observationsJson =
        publishJson(
            workspace,
            "observations.json",
            observations(result, sourceId, analysisId, frozen.checksum()));
    String qualityJson =
        publishJson(
            workspace, "quality.json", quality(result, sourceId, analysisId, frozen.checksum()));
    specs.add(
        new MouseVideoFinalizationService.ArtifactSpec(
            true,
            MouseVideoArtifactRef.ROLE_OBSERVATIONS,
            observationsJson,
            sizeOf(observationsJson),
            "application/json",
            false));
    specs.add(
        new MouseVideoFinalizationService.ArtifactSpec(
            true,
            MouseVideoArtifactRef.ROLE_QUALITY,
            qualityJson,
            sizeOf(qualityJson),
            "application/json",
            false));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema", "analysis-manifest/v1");
    manifest.put("algorithmId", MouseVideoVisualAnalyzer.ALGORITHM_ID);
    manifest.put("toolchainPin", properties.getVisualToolchainPin());
    manifest.put("indexChecksum", frozen.checksum());
    manifest.put("observationsChecksum", observationsJson);
    manifest.put("qualityChecksum", qualityJson);
    List<Map<String, Object>> declared = new ArrayList<>();
    for (MouseVideoFinalizationService.ArtifactSpec spec : specs) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("role", spec.role());
      entry.put("checksum", spec.checksum());
      entry.put("sizeBytes", spec.sizeBytes());
      entry.put("mediaType", spec.mediaType());
      declared.add(entry);
    }
    manifest.put("artifacts", declared);
    String manifestChecksum = publishJson(workspace, "analysis-manifest.json", manifest);
    specs.add(
        new MouseVideoFinalizationService.ArtifactSpec(
            true,
            MouseVideoArtifactRef.ROLE_MANIFEST,
            manifestChecksum,
            sizeOf(manifestChecksum),
            "application/json",
            false));
    String coverage =
        String.format(
            java.util.Locale.ROOT,
            "%s: %dx%d, %d/%d source frames sampled (step %d), %d observations, cursor=%s",
            MouseVideoVisualAnalyzer.ALGORITHM_ID,
            sourceWidth,
            sourceHeight,
            result.sampledFrames(),
            frozen.frames().size(),
            result.step(),
            result.observations().size(),
            result.cursorTemplateUsed() ? "template" : "unknown");
    analysis.complete(coverage, manifestChecksum);
    return new Outcome(analysis, List.copyOf(specs), coverage);
  }

  /**
   * Resolves the cursor template frozen at scheduling time. When the analysis pins a template
   * content address, the bytes are materialized from the content-addressed store and re-verified,
   * so a later change at the configured path cannot alter queued work. An analysis without a pinned
   * address was scheduled without a usable template and runs without one.
   */
  private Path frozenTemplate(MouseVideoAnalysis analysis, Path workspace) throws IOException {
    String checksum = analysis.getTemplateChecksum();
    if (checksum == null || checksum.isBlank()) {
      return null;
    }
    Path located =
        byteStore
            .locate(checksum)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Frozen cursor template bytes are missing"));
    Path frozen = workspace.resolve("cursor-template-frozen.png");
    Files.copy(located, frozen, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    if (!checksum.equals(byteStore.digestFile(frozen, 32L * 1024 * 1024))) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CHECKSUM_MISMATCH, "Frozen cursor template hash mismatch");
    }
    return frozen;
  }

  private void extractSamples(
      MouseVideoCancellation cancellation, Path media, Path frames, int step)
      throws IOException, InterruptedException {
    MouseVideoProcessRunner.Result result =
        runner.run(
            samplingCommand(
                properties.getFfmpeg(),
                media,
                frames,
                step,
                properties.getMaxVisualFrames(),
                properties.getVisualSampleMaxWidth()),
            TimeUnit.SECONDS.toMillis(properties.getAnalysisBudgetSeconds()),
            cancellation,
            null,
            0);
    if (result.exitCode() != 0 || !result.outputComplete()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "Visual sampling failed (exit " + result.exitCode() + ")");
    }
  }

  /**
   * The exact bounded ffmpeg command that writes the grayscale sample sequence analyzed by {@link
   * MouseVideoVisualAnalyzer}. Shared with the corpus qualification harness so evaluation decodes
   * frames the same way production does.
   */
  static List<String> samplingCommand(
      String ffmpeg, Path media, Path frames, int step, int maxVisualFrames, int maxWidth) {
    String filter =
        "select='not(mod(n\\,"
            + step
            + "))',scale='min(iw,"
            + Math.max(16, maxWidth)
            + ")':-2:flags=area,format=gray";
    return List.of(
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-threads",
        "2",
        "-filter_threads",
        "1",
        "-i",
        media.toAbsolutePath().toString(),
        "-map",
        "0:v:0",
        "-vf",
        filter,
        "-vsync",
        "0",
        "-frames:v",
        Integer.toString(Math.max(1, maxVisualFrames)),
        frames.resolve("%06d.png").toAbsolutePath().toString());
  }

  private Map<String, Object> observations(
      MouseVideoVisualAnalyzer.Result result,
      UUID sourceId,
      UUID analysisId,
      String indexChecksum) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("schema", MouseVideoVisualAnalyzer.OBSERVATIONS_SCHEMA);
    document.put("algorithmId", MouseVideoVisualAnalyzer.ALGORITHM_ID);
    document.put("toolchainPin", properties.getVisualToolchainPin());
    document.put("samplingMethod", MouseVideoSampleStride.METHOD);
    document.put("sourceId", sourceId.toString());
    document.put("analysisId", analysisId.toString());
    document.put("indexChecksum", indexChecksum);
    document.put("observations", result.observations());
    return document;
  }

  private Map<String, Object> quality(
      MouseVideoVisualAnalyzer.Result result,
      UUID sourceId,
      UUID analysisId,
      String indexChecksum) {
    Map<String, Object> document = new LinkedHashMap<>(result.quality());
    document.put("sourceId", sourceId.toString());
    document.put("analysisId", analysisId.toString());
    document.put("indexChecksum", indexChecksum);
    return document;
  }

  private void reuse(
      List<MouseVideoFinalizationService.ArtifactSpec> specs, UUID sourceId, String role) {
    artifacts
        .findFirstBySourceIdAndRoleAndStateOrderByCreatedAtDesc(
            sourceId, role, MouseVideoArtifactRef.STATE_FINALIZED)
        .ifPresent(
            artifact ->
                specs.add(
                    new MouseVideoFinalizationService.ArtifactSpec(
                        true,
                        role,
                        artifact.getChecksum(),
                        artifact.getSizeBytes(),
                        artifact.getMediaType(),
                        false)));
  }

  private String publishJson(Path workspace, String name, Object document) throws IOException {
    byte[] content = mapper.writeValueAsBytes(document);
    MouseVideoByteStore.StagedArtifact staged =
        byteStore.stage(new java.io.ByteArrayInputStream(content), content.length + 1L);
    byteStore.publish(staged);
    Files.write(workspace.resolve(name), content);
    return staged.checksum();
  }

  private long sizeOf(String checksum) {
    return byteStore
        .locate(checksum)
        .map(
            path -> {
              try {
                return Files.size(path);
              } catch (IOException unreadable) {
                return 0L;
              }
            })
        .orElse(0L);
  }

  private MouseVideoFrameIndexService.FrozenFrameIndex readIndex(Path path) throws IOException {
    if (Files.size(path) > 128L * 1024 * 1024) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "Frozen index is implausibly large");
    }
    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(Files.readAllBytes(path));
    com.fasterxml.jackson.databind.JsonNode framesNode = root.path("frames");
    List<MouseVideoFrameIndexService.Frame> frames = new ArrayList<>();
    for (com.fasterxml.jackson.databind.JsonNode frame : framesNode) {
      frames.add(
          new MouseVideoFrameIndexService.Frame(
              text(frame.get("pts")),
              text(frame.get("ptsTime")),
              text(frame.get("duration")),
              frame.path("key").asBoolean(false)));
    }
    String[] parts = split(root.path("timeBase").asText(""));
    return new MouseVideoFrameIndexService.FrozenFrameIndex(
        byteStore.digestFile(path, 128L * 1024 * 1024),
        root.path("schema").asText(""),
        root.path("complete").asBoolean(false),
        root.path("timeBase").asText(null),
        parts == null ? null : parts[0],
        parts == null ? null : parts[1],
        List.copyOf(frames));
  }

  private static String[] split(String timeBase) {
    String[] parts = timeBase == null ? new String[0] : timeBase.split("/", -1);
    if (parts.length != 2 || !parts[0].matches("[0-9]+") || !parts[1].matches("[0-9]+")) {
      return null;
    }
    return parts;
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  private static void checkCancelled(MouseVideoCancellation cancellation)
      throws InterruptedException {
    if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Analysis cancelled");
    }
  }
}
