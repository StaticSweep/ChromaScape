package com.chromascape.web.replay.run;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayMismatchPageResponse;
import com.chromascape.web.replay.api.dto.ReplayMismatchResponse;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.api.dto.ViewerSessionResponse;
import com.chromascape.web.replay.api.dto.XrspsPreflightResponse;
import com.chromascape.web.replay.api.dto.XrspsRunResponse;
import com.chromascape.web.replay.api.dto.XrspsTraceFramePageResponse;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.catalog.ReplayV2PreflightService;
import com.chromascape.web.replay.catalog.dto.ReplayBlockingReason;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ReplayApproximation;
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.dto.CancelReplayRunResponse;
import com.chromascape.web.replay.run.dto.CreateXrspsRunRequest;
import com.chromascape.web.replay.run.dto.ReplayEventPageResponse;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.entity.StateHashSample;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.viewer.ReplayViewerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates the one-click, durable recording-to-xRSPS v2 flow. */
@Service
@Transactional
public class ReplayV2RunService {

  private final ReplayV2PreflightService preflightService;
  private final ScenarioManifestV2Compiler compiler;
  private final ReplayRunService runService;
  private final ScenarioManifestRepository manifestRepository;
  private final ReplayViewerService viewerService;
  private final ArtifactRefRepository artifactRepository;
  private final ReplayArtifactService artifactService;
  private final ReplayReportRepository reportRepository;
  private final ReplayMismatchRepository mismatchRepository;
  private final StateHashSampleRepository hashSampleRepository;
  private final CanonicalProjectionService projectionService;
  private final ObjectMapper mapper;
  private final com.chromascape.web.replay.config.ReplayProperties properties;

  public ReplayV2RunService(
      ReplayV2PreflightService preflightService,
      ScenarioManifestV2Compiler compiler,
      ReplayRunService runService,
      ScenarioManifestRepository manifestRepository,
      ReplayViewerService viewerService,
      ArtifactRefRepository artifactRepository,
      ReplayArtifactService artifactService,
      ReplayReportRepository reportRepository,
      ReplayMismatchRepository mismatchRepository,
      StateHashSampleRepository hashSampleRepository,
      CanonicalProjectionService projectionService,
      ObjectMapper mapper,
      com.chromascape.web.replay.config.ReplayProperties properties) {
    this.preflightService = preflightService;
    this.compiler = compiler;
    this.runService = runService;
    this.manifestRepository = manifestRepository;
    this.viewerService = viewerService;
    this.artifactRepository = artifactRepository;
    this.artifactService = artifactService;
    this.reportRepository = reportRepository;
    this.mismatchRepository = mismatchRepository;
    this.hashSampleRepository = hashSampleRepository;
    this.projectionService = projectionService;
    this.mapper = mapper;
    this.properties = properties;
  }

  public XrspsPreflightResponse preflight(String recordingId, ConversionMode conversionMode) {
    return preflightService.preflight(recordingId, conversionMode);
  }

  public Creation create(String recordingId, CreateXrspsRunRequest request, String ownerId) {
    if (ownerId == null || ownerId.isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.UNAUTHORIZED,
          false,
          "Authentication is required",
          "Sign in before starting an xRSPS replay");
    }
    if (request == null) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Replay request is required");
    }
    if (request.stopPolicy() != ReplayStopPolicy.STOP_ON_FIRST_HARD
        && request.stopPolicy() != ReplayStopPolicy.RUN_TO_END) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR,
          "v2 stopPolicy must be STOP_ON_FIRST_HARD or RUN_TO_END");
    }
    String fingerprint = requestFingerprint(recordingId, request);
    Optional<ReplayRun> existing =
        runService.findByIdempotencyKey(request.idempotencyKey(), ownerId);
    if (existing.isPresent()) {
      ReplayRun run = existing.get();
      if (run.getRequestFingerprint() != null && !fingerprint.equals(run.getRequestFingerprint())) {
        throw ReplayException.conflict(
            ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
            "The idempotency key was already used for a different replay request");
      }
      ScenarioManifest oldManifest = manifestRepository.findById(run.getManifestId()).orElse(null);
      if (oldManifest == null || !recordingId.equals(oldManifest.getRecordingId())) {
        throw ReplayException.conflict(
            ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
            "The idempotency key was already used for a different replay request");
      }
      return new Creation(toResponse(run), false);
    }

    if (!properties.isEnabled()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "xRSPS replay is disabled",
          "Enable REPLAY_ENABLED only for a reviewed replay deployment");
    }

    XrspsPreflightResponse preflight =
        preflightService.preflight(recordingId, request.conversionMode());
    if (!preflight.simulationEligible()) {
      throw preflightFailure(preflight);
    }
    ScenarioManifestV2Compiler.Compilation compilation =
        compiler.compile(recordingId, request.conversionMode());
    ReplayRun run = runService.createV2(compilation.manifest(), request, ownerId, fingerprint);
    return new Creation(toResponse(run), true);
  }

  /**
   * SC-2106 R3.4: the recording's start alone, as a deterministic ScenarioManifest/v2 document for
   * causal candidate execution. No recorded action is carried.
   */
  @Transactional(readOnly = true)
  public com.fasterxml.jackson.databind.node.ObjectNode startManifest(String recordingId) {
    if (!properties.isEnabled()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "xRSPS replay is disabled",
          "Enable REPLAY_ENABLED only for a reviewed replay deployment");
    }
    return compiler.compileStart(recordingId);
  }

  @Transactional(readOnly = true)
  public XrspsRunResponse get(UUID runId, String ownerId) {
    return toResponse(runService.getOwnedEntity(runId, ownerId));
  }

  @Transactional(readOnly = true)
  public ReplayEventPageResponse events(UUID runId, Long after, int limit, String ownerId) {
    return runService.events(runId, after, limit, ownerId);
  }

  @Transactional(readOnly = true)
  public ReplayReport report(UUID runId, String ownerId) {
    ReplayRun run = runService.getOwnedEntity(runId, ownerId);
    if (!run.isTerminal()) {
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID,
          "A replay report is available only after the run is terminal");
    }
    return runService.getReportForOwner(runId, ownerId);
  }

  public CancelReplayRunResponse cancel(UUID runId, String ownerId) {
    return runService.cancel(runId, ownerId);
  }

  @Transactional(readOnly = true)
  public ViewerSessionResponse viewerSession(UUID runId, String ownerId) {
    ReplayRun run = runService.getOwnedEntity(runId, ownerId);
    return viewerService.createSession(run);
  }

  @Transactional(readOnly = true)
  public XrspsTraceFramePageResponse frames(
      UUID runId, String ownerId, String viewerToken, String cursor, int limit) {
    return viewerService.frames(runId, ownerId, viewerToken, cursor, limit);
  }

  /** Returns bounded mismatch evidence for the authenticated owner. */
  @Transactional(readOnly = true)
  public ReplayMismatchPageResponse mismatches(UUID runId, String ownerId, int limit) {
    ReplayRun run = runService.getOwnedEntity(runId, ownerId);
    List<ReplayMismatch> all =
        mismatchRepository.findByRunIdOrderByFirstTickAscTickSequenceAsc(run.getId());
    int boundedLimit = Math.max(1, Math.min(200, limit));
    List<ReplayMismatchResponse> page =
        all.stream().limit(boundedLimit).map(mismatch -> projectMismatch(run, mismatch)).toList();
    return new ReplayMismatchPageResponse(
        ReplayMismatchPageResponse.SCHEMA, runId, page, all.size() > page.size());
  }

  /** Returns the first ordered mismatch, or null when the run has no mismatch evidence. */
  @Transactional(readOnly = true)
  public ReplayMismatchResponse firstMismatch(UUID runId, String ownerId) {
    ReplayRun run = runService.getOwnedEntity(runId, ownerId);
    return mismatchRepository.findByRunIdOrderByFirstTickAscTickSequenceAsc(run.getId()).stream()
        .findFirst()
        .map(mismatch -> projectMismatch(run, mismatch))
        .orElse(null);
  }

  /**
   * Reads a terminal artifact through the owner boundary. The hashes bundle is constructed from the
   * durable ordered hash samples and receives its own content checksum; it is not derived from
   * browser state.
   */
  @Transactional(readOnly = true)
  public ArtifactDownload artifact(UUID runId, String artifactName, String ownerId) {
    ReplayRun run = runService.getOwnedEntity(runId, ownerId);
    if (!run.isTerminal()) {
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID,
          "Replay artifacts are available only after the run is terminal");
    }
    String normalized = artifactName == null ? "" : artifactName.trim().toLowerCase();
    if ("hashes".equals(normalized)) {
      byte[] content = hashBundle(run);
      return new ArtifactDownload(
          content,
          "application/json",
          "replay-" + runId + "-hashes.json",
          artifactService.checksum(content));
    }
    ArtifactKind kind =
        switch (normalized) {
          case "manifest" -> ArtifactKind.MANIFEST;
          case "trace" -> ArtifactKind.TRACE;
          case "report" -> ArtifactKind.REPORT;
          default -> throw ReplayException.badRequest(
              ReplayErrorCode.VALIDATION_ERROR,
              "Unknown replay artifact; expected manifest, trace, hashes, or report");
        };
    ArtifactRef ref =
        artifactRepository
            .findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
                runId, kind, ArtifactRef.STATE_FINALIZED)
            .orElse(null);
    if (ref != null) {
      byte[] content = artifactService.readVerified(ref);
      return new ArtifactDownload(
          content,
          ref.getMediaType() == null ? "application/json" : ref.getMediaType(),
          "replay-" + runId + "-" + normalized + ".json",
          ref.getChecksum());
    }
    if (kind == ArtifactKind.REPORT) {
      ReplayReport report =
          reportRepository
              .findByRunId(runId)
              .orElseThrow(
                  () ->
                      ReplayException.conflict(
                          ReplayErrorCode.TRACE_NOT_AVAILABLE,
                          "No finalized terminal report is available for this run"));
      byte[] content = report.getReportJson().getBytes(StandardCharsets.UTF_8);
      if (!verifyReportDocumentChecksum(content, report.getReportChecksum())) {
        throw ReplayException.conflict(
            ReplayErrorCode.TRACE_CORRUPT, "The terminal report failed checksum verification");
      }
      return new ArtifactDownload(
          content,
          "application/json",
          "replay-" + runId + "-report.json",
          artifactService.checksum(content));
    }
    throw ReplayException.conflict(
        ReplayErrorCode.TRACE_NOT_AVAILABLE,
        "No finalized " + normalized + " artifact is available for this run");
  }

  /**
   * The report checksum authenticates the canonical report document before its checksum field is
   * added. The artifact checksum, by contrast, authenticates the complete downloaded bytes. Keep
   * those two integrity domains distinct so a report fallback is both verifiable and correctly
   * advertised by the download endpoint.
   */
  private boolean verifyReportDocumentChecksum(byte[] content, String expectedChecksum) {
    if (expectedChecksum == null || expectedChecksum.isBlank()) return false;
    try {
      JsonNode parsed = mapper.readTree(content);
      if (parsed == null || !parsed.isObject()) return false;
      if (!expectedChecksum.equals(parsed.path("reportChecksum").asText(null))) return false;
      ObjectNode document = (ObjectNode) parsed.deepCopy();
      document.remove("reportChecksum");
      return expectedChecksum.equals(
          projectionService.sha256Hex(projectionService.canonicalJsonBytes(document)));
    } catch (Exception ignored) {
      return false;
    }
  }

  private byte[] hashBundle(ReplayRun run) {
    List<StateHashSample> samples =
        hashSampleRepository.findByIdRunIdOrderByIdTickAscIdSequenceAsc(run.getId());
    ObjectNode document = mapper.createObjectNode();
    document.put(
        "schema",
        samples.stream().anyMatch(s -> s.getProjectionVersion() == 2)
            ? "xrsps-replay-hashes/v2"
            : "xrsps-replay-hashes/v1");
    document.put("runId", run.getId().toString());
    ArrayNode values = document.putArray("samples");
    for (StateHashSample sample : samples) {
      ObjectNode value = values.addObject();
      value.put("tick", sample.getId().getTick());
      value.put("sequence", sample.getId().getSequence());
      value.put("projectionVersion", sample.getProjectionVersion());
      if (sample.getTransitionOrdinal() != null) {
        value.put("ordinal", sample.getTransitionOrdinal());
        value.put("hashVersion", "sha256-semantic-state-v2");
      }
      value.put("stateHash", sample.getStateHash());
      value.put("chainHash", sample.getChainHash());
    }
    document.put("sampleCount", samples.size());
    if (run.getViewerTraceChecksum() != null) {
      document.put("traceChecksum", run.getViewerTraceChecksum());
    }
    String bundleChecksum =
        projectionService.sha256Hex(projectionService.canonicalJsonBytes(document));
    document.put("bundleChecksum", bundleChecksum);
    return projectionService.canonicalJsonBytes(document);
  }

  private ReplayMismatchResponse projectMismatch(ReplayRun run, ReplayMismatch mismatch) {
    JsonNode manifest =
        manifestRepository
            .findById(run.getManifestId())
            .map(ScenarioManifest::getManifestJson)
            .orElse(null);
    JsonNode expected = parseStoredValue(mismatch.getExpectedValue());
    JsonNode actual = parseStoredValue(mismatch.getActualValue());
    ObjectNode evidence = mapper.createObjectNode();
    evidence.put("kind", "recorded_expectation_vs_simulation");
    evidence.put("manifestId", run.getManifestId().toString());
    evidence.put(
        "manifestPath", "/expectations/" + escapeJsonPointerSegment(mismatch.getJsonPointer()));
    if (mismatch.getFirstTick() != null) evidence.put("tick", mismatch.getFirstTick());
    if (mismatch.getJsonPointer() != null) evidence.put("jsonPointer", mismatch.getJsonPointer());
    evidence.set("recordedValue", expected.deepCopy());
    evidence.set("simulatedValue", actual.deepCopy());

    ActionMatch actionMatch = locateAction(manifest, mismatch);
    if (actionMatch != null) {
      evidence.put("actionIndex", actionMatch.index());
      evidence.set("action", actionMatch.action().deepCopy());
      JsonNode source = actionMatch.action().path("sourceEvidence");
      if (source.isObject()) evidence.set("actionSource", source.deepCopy());
    } else {
      evidence.put("actionEvidence", "No semantic action was recorded for this expectation");
    }

    return new ReplayMismatchResponse(
        ReplayMismatchResponse.SCHEMA,
        1,
        mismatch.getId(),
        mismatch.getLevel(),
        mismatch.getRuleId(),
        mismatch.getFirstTick(),
        mismatch.getTickSequence(),
        mismatch.getDomain(),
        mismatch.getJsonPointer(),
        expected,
        actual,
        mismatch.getRelatedActionOrdinal(),
        mismatch.getCheckpointId(),
        mismatch.getCausalParentId(),
        mismatch.getCascadeCount(),
        evidence);
  }

  private ActionMatch locateAction(JsonNode manifest, ReplayMismatch mismatch) {
    JsonNode actions = manifest == null ? null : manifest.path("actions");
    if (actions == null || !actions.isArray() || mismatch.getFirstTick() == null) return null;
    ActionMatch nearest = null;
    for (int i = 0; i < actions.size(); i++) {
      JsonNode action = actions.get(i);
      if (!action.isObject() || !action.has("sourceActionTick")) continue;
      long tick = action.path("sourceActionTick").asLong(Long.MIN_VALUE);
      if (tick > mismatch.getFirstTick()) continue;
      int sequence = action.path("sourceActionSequence").asInt(Integer.MIN_VALUE);
      if (tick == mismatch.getFirstTick()
          && mismatch.getTickSequence() != null
          && sequence == mismatch.getTickSequence()) {
        return new ActionMatch(i, action);
      }
      if (nearest == null
          || tick > nearest.tick()
          || (tick == nearest.tick() && sequence > nearest.sequence())) {
        nearest = new ActionMatch(i, action);
      }
    }
    return nearest;
  }

  private JsonNode parseStoredValue(String value) {
    if (value == null) return mapper.nullNode();
    try {
      JsonNode parsed = mapper.readTree(value);
      return parsed == null ? mapper.nullNode() : parsed;
    } catch (Exception ignored) {
      return mapper.getNodeFactory().textNode(value);
    }
  }

  private static String escapeJsonPointerSegment(String value) {
    if (value == null || value.isBlank()) return "";
    return value.replace("~", "~0").replace("/", "~1");
  }

  private ReplayException preflightFailure(XrspsPreflightResponse preflight) {
    ReplayBlockingReason selected = selectBlocker(preflight.blockingReasons());
    if (selected == null) {
      return ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED, "The recording is not eligible for xRSPS simulation");
    }
    ReplayErrorCode code = selected.code();
    HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
    boolean retryable = false;
    if (code == ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE
        || code == ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE) {
      status = HttpStatus.SERVICE_UNAVAILABLE;
      retryable = true;
    }
    return new ReplayException(code, status, retryable, selected.message(), selected.remediation());
  }

  private static ReplayBlockingReason selectBlocker(List<ReplayBlockingReason> blockers) {
    if (blockers == null || blockers.isEmpty()) return null;
    List<ReplayErrorCode> priority =
        List.of(
            ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
            ReplayErrorCode.XRSPS_VERSION_INCOMPATIBLE,
            ReplayErrorCode.DEBUG_SESSION_NOT_FINALIZED,
            ReplayErrorCode.TICK_GAP_DETECTED,
            ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE,
            ReplayErrorCode.ACTION_UNSUPPORTED);
    for (ReplayErrorCode code : priority) {
      Optional<ReplayBlockingReason> matching =
          blockers.stream().filter(reason -> reason.code() == code).findFirst();
      if (matching.isPresent()) return matching.get();
    }
    return blockers.get(0);
  }

  private String requestFingerprint(String recordingId, CreateXrspsRunRequest request) {
    var node = mapper.createObjectNode();
    node.put("recordingId", recordingId);
    node.put("conversionMode", request.conversionMode().name());
    node.put("openViewer", request.viewerRequested());
    node.put("stopPolicy", request.stopPolicy().name());
    return projectionService.sha256Hex(projectionService.canonicalJsonBytes(node));
  }

  private XrspsRunResponse toResponse(ReplayRun run) {
    ScenarioManifest manifest =
        manifestRepository
            .findById(run.getManifestId())
            .orElseThrow(
                () ->
                    ReplayException.notFound(
                        ReplayErrorCode.RECORDING_NOT_FOUND, "Replay manifest not found"));
    List<ReplayGap> gaps = readGaps(manifest.getManifestJson());
    List<ReplayApproximation> approximations = readApproximations(manifest.getManifestJson());
    boolean viewerAvailable = viewerService.hasVerifiedTrace(run);
    return new XrspsRunResponse(
        XrspsRunResponse.SCHEMA,
        run.getId(),
        manifest.getRecordingId(),
        run.getManifestId(),
        run.getState(),
        run.getPhase(),
        run.getOutcome(),
        run.getTerminalReason(),
        run.getFidelity(),
        run.getXrspsAdapterVersion(),
        run.getXrspsBuildCommit(),
        run.getCacheRevision(),
        run.getContentRevision(),
        quality(run.getConversionQuality()),
        run.getActionCoveragePct(),
        run.getGapCount(),
        run.getApproximationCount(),
        gaps,
        approximations,
        false,
        run.isViewerRequested(),
        viewerAvailable,
        run.getViewerTraceChecksum(),
        run.getViewerTraceFrameCount(),
        run.getStopPolicy(),
        run.getCreatedAt(),
        run.getStartedAt(),
        run.getTerminalAt());
  }

  private List<ReplayGap> readGaps(JsonNode manifest) {
    JsonNode array = manifest == null ? null : manifest.path("conversion").path("gaps");
    if (array == null || !array.isArray()) return List.of();
    List<ReplayGap> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(
          new ReplayGap(
              node.path("tick").asLong(0),
              node.path("code").asText("UNKNOWN"),
              node.path("message").asText(""),
              provenance(node.path("provenance").asText(null)),
              node.has("sourceLogId") ? node.path("sourceLogId").asLong() : null));
    }
    return List.copyOf(result);
  }

  private List<ReplayApproximation> readApproximations(JsonNode manifest) {
    JsonNode array = manifest == null ? null : manifest.path("conversion").path("approximations");
    if (array == null || !array.isArray()) return List.of();
    List<ReplayApproximation> result = new ArrayList<>();
    for (JsonNode node : array) {
      result.add(
          new ReplayApproximation(
              node.path("tick").asLong(0),
              node.path("code").asText("UNKNOWN"),
              node.path("message").asText(""),
              provenance(node.path("provenance").asText(null)),
              node.has("sourceSnapshotId") ? node.path("sourceSnapshotId").asLong() : null));
    }
    return List.copyOf(result);
  }

  private static ActionProvenance provenance(String value) {
    try {
      return value == null ? ActionProvenance.DEFAULTED : ActionProvenance.valueOf(value);
    } catch (IllegalArgumentException ex) {
      return ActionProvenance.DEFAULTED;
    }
  }

  private static ConversionQuality quality(String value) {
    try {
      return value == null ? ConversionQuality.BEST_EFFORT : ConversionQuality.valueOf(value);
    } catch (IllegalArgumentException ex) {
      return ConversionQuality.BEST_EFFORT;
    }
  }

  private record ActionMatch(int index, JsonNode action) {
    long tick() {
      return action.path("sourceActionTick").asLong(Long.MIN_VALUE);
    }

    int sequence() {
      return action.path("sourceActionSequence").asInt(Integer.MIN_VALUE);
    }
  }

  public record ArtifactDownload(
      byte[] content, String mediaType, String filename, String checksum) {}

  public record Creation(XrspsRunResponse response, boolean created) {}
}
