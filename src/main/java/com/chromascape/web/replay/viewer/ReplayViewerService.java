package com.chromascape.web.replay.viewer;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ViewerSessionResponse;
import com.chromascape.web.replay.api.dto.XrspsTraceFramePageResponse;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.report.entity.ReplayReport;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves only immutable, content-address-verified trace data to the read-only observer. The
 * browser never receives a mutation capability and the token is not persisted.
 */
@Service
@Transactional(readOnly = true)
public class ReplayViewerService {

  private static final String TRACE_SCHEMA = "xrsps-replay-trace/v1";
  private static final int MAX_FRAME_LIMIT = 500;
  private static final String TOKEN_SEPARATOR = ".";

  private final ArtifactRefRepository artifactRepository;
  private final ReplayArtifactService artifactService;
  private final ReplayReportRepository reportRepository;
  private final ReplayRunRepository runRepository;
  private final ReplayProperties properties;
  private final ObjectMapper mapper;

  public ReplayViewerService(
      ArtifactRefRepository artifactRepository,
      ReplayArtifactService artifactService,
      ReplayReportRepository reportRepository,
      ReplayRunRepository runRepository,
      ReplayProperties properties,
      ObjectMapper mapper) {
    this.artifactRepository = artifactRepository;
    this.artifactService = artifactService;
    this.reportRepository = reportRepository;
    this.runRepository = runRepository;
    this.properties = properties;
    this.mapper = mapper;
  }

  public ViewerSessionResponse createSession(ReplayRun run) {
    requireViewerConfigured();
    if (run == null || !run.isTerminal()) {
      throw ReplayException.conflict(
          ReplayErrorCode.TRACE_NOT_AVAILABLE,
          "A viewer session is available only after a terminal run has a verified trace");
    }
    TraceDocument trace = loadVerifiedTrace(run);
    Instant expiresAt = Instant.now().plusSeconds(properties.getViewer().getTokenTtlSeconds());
    String token = signToken(run.getId(), expiresAt);
    String origin = trimTrailingSlash(properties.getViewer().getOrigin());
    String viewerUrl =
        origin
            + "/replay-observer?runId="
            + URLEncoder.encode(run.getId().toString(), StandardCharsets.UTF_8)
            + "&token="
            + URLEncoder.encode(token, StandardCharsets.UTF_8);
    // Loading the document before minting is intentional: no URL is issued for an unverified
    // or corrupt trace. The local variable also makes the safety property explicit to reviewers.
    if (trace.frames().isEmpty() && run.getViewerTraceFrameCount() != null
        && run.getViewerTraceFrameCount() > 0) {
      throw traceCorrupt("Trace metadata declares frames that are not readable");
    }
    return new ViewerSessionResponse(
        ViewerSessionResponse.SCHEMA, run.getId(), viewerUrl, expiresAt, false);
  }

  public XrspsTraceFramePageResponse frames(
      UUID runId, String ownerId, String viewerToken, String cursor, int limit) {
    ReplayRun run =
        runRepository
            .findById(runId)
            .orElseThrow(
                () -> ReplayException.notFound(ReplayErrorCode.RECORDING_NOT_FOUND, "Replay run not found"));
    if (ownerId == null || !ownerId.equals(run.getOwnerId())) {
      requireValidViewerToken(runId, viewerToken);
    }
    TraceDocument trace = loadVerifiedTrace(run);
    int boundedLimit = Math.max(1, Math.min(MAX_FRAME_LIMIT, limit));
    int start = decodeCursor(cursor, trace.frames().size());
    int end = (int) Math.min((long) trace.frames().size(), (long) start + boundedLimit);
    List<JsonNode> page = new ArrayList<>(trace.frames().subList(start, end));
    boolean hasMore = end < trace.frames().size();
    return new XrspsTraceFramePageResponse(
        XrspsTraceFramePageResponse.SCHEMA,
        runId,
        trace.checksum(),
        page,
        hasMore ? encodeCursor(end) : null,
        hasMore);
  }

  /** True only when the run's linked finalized trace can be read and verified now. */
  public boolean hasVerifiedTrace(ReplayRun run) {
    if (run == null || run.getViewerTraceChecksum() == null) return false;
    try {
      loadVerifiedTrace(run);
      return true;
    } catch (ReplayException ex) {
      if (ex.getErrorCode() == ReplayErrorCode.TRACE_NOT_AVAILABLE
          || ex.getErrorCode() == ReplayErrorCode.TRACE_CORRUPT) return false;
      throw ex;
    }
  }

  private TraceDocument loadVerifiedTrace(ReplayRun run) {
    if (run == null || run.getId() == null || run.getViewerTraceChecksum() == null) {
      throw ReplayException.conflict(
          ReplayErrorCode.TRACE_NOT_AVAILABLE, "No verified xRSPS trace is available for this run");
    }
    ArtifactRef ref =
        artifactRepository
            .findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
                run.getId(), ArtifactKind.TRACE, ArtifactRef.STATE_FINALIZED)
            .orElseThrow(
                () ->
                    ReplayException.conflict(
                        ReplayErrorCode.TRACE_NOT_AVAILABLE,
                        "No finalized xRSPS trace is available for this run"));
    if (!run.getViewerTraceChecksum().equals(ref.getChecksum())) {
      throw traceCorrupt("Run trace metadata does not match the finalized trace artifact");
    }
    ReplayReport report = reportRepository.findByRunId(run.getId()).orElse(null);
    if (report != null
        && report.getViewerTraceChecksum() != null
        && !ref.getChecksum().equals(report.getViewerTraceChecksum())) {
      throw traceCorrupt("Terminal report is not bound to the finalized trace artifact");
    }
    byte[] content = artifactService.readVerified(ref);
    JsonNode root;
    try {
      root = mapper.readTree(content);
    } catch (Exception ex) {
      throw traceCorrupt("Trace artifact is not valid JSON");
    }
    if (root == null || !root.isObject() || !TRACE_SCHEMA.equals(root.path("schema").asText())) {
      throw traceCorrupt("Trace artifact has an unsupported schema");
    }
    JsonNode frames = root.path("frames");
    if (!frames.isArray()) {
      throw traceCorrupt("Trace artifact has no frame array");
    }
    List<JsonNode> immutableFrames = new ArrayList<>(frames.size());
    long previousOrdinal = -1;
    boolean strict = run.getManifestVersion() >= 2;
    for (JsonNode frame : frames) {
      validateFrame(frame, strict);
      long ordinal = frame.path("ordinal").asLong(-1);
      if (ordinal <= previousOrdinal) {
        throw traceCorrupt("Trace artifact frame ordinals are not strictly increasing");
      }
      previousOrdinal = ordinal;
      immutableFrames.add(frame.deepCopy());
    }
    if (run.getViewerTraceFrameCount() != null
        && run.getViewerTraceFrameCount() != immutableFrames.size()) {
      throw traceCorrupt("Run trace frame count does not match the trace artifact");
    }
    return new TraceDocument(ref.getChecksum(), List.copyOf(immutableFrames));
  }

  private void validateFrame(JsonNode frame, boolean strict) {
    if (frame == null || !frame.isObject()) {
      throw traceCorrupt("Trace artifact contains a non-object frame");
    }
    if (!frame.path("ordinal").isIntegralNumber()
        || frame.path("ordinal").asLong(-1) < 0
        || !frame.path("tick").isIntegralNumber()
        || frame.path("tick").asLong(-1) < 0
        || !frame.path("stateHash").isTextual()
        || frame.path("stateHash").asText().isBlank()
        || !frame.path("chainHash").isTextual()
        || frame.path("chainHash").asText().isBlank()) {
      throw traceCorrupt("Trace artifact contains an invalid frame identity or hash");
    }
    if (!strict) return;
    if (frame.path("frameVersion").asInt(-1) != 1
        || !frame.path("observation").isObject()
        || !frame.path("player").isObject()
        || !frame.path("npcs").isArray()
        || !frame.path("objects").isArray()
        || !frame.path("taskState").isObject()
        || !frame.path("events").isArray()
        || !(frame.path("actionDigest").isNull() || frame.path("actionDigest").isTextual())
        || !(frame.path("sourceActionIndex").isNull()
            || (frame.path("sourceActionIndex").isIntegralNumber()
                && frame.path("sourceActionIndex").asLong(-1) >= 0))) {
      throw traceCorrupt("Trace artifact contains an invalid xRSPS replay frame");
    }
    for (JsonNode event : frame.path("events")) {
      if (!event.isObject()
          || event.path("eventVersion").asInt(-1) != 1
          || !event.path("ordinal").isIntegralNumber()
          || !event.path("tick").isIntegralNumber()
          || !event.path("sequence").isIntegralNumber()
          || !event.path("eventType").isTextual()
          || !event.path("category").isTextual()) {
        throw traceCorrupt("Trace artifact contains an invalid semantic effect");
      }
    }
  }

  private void requireViewerConfigured() {
    if (!properties.isEnabled() || !properties.getViewer().isEnabled()) {
      throw new ReplayException(
          ReplayErrorCode.VIEWER_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "The xRSPS viewer is disabled",
          "Enable replay.viewer only for a reviewed local observer deployment");
    }
    if (properties.getViewer().getSigningSecret() == null
        || properties.getViewer().getSigningSecret().isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VIEWER_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "The xRSPS viewer signing key is not configured",
          "Configure REPLAY_VIEWER_SIGNING_SECRET outside the repository");
    }
  }

  private void requireValidViewerToken(UUID runId, String token) {
    if (token == null || token.isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VIEWER_TOKEN_INVALID,
          HttpStatus.UNAUTHORIZED,
          false,
          "Viewer authentication is required",
          "Use the short-lived token from the viewer launch URL");
    }
    TokenVerification verification = verifyToken(runId, token);
    if (verification == TokenVerification.EXPIRED) {
      throw new ReplayException(
          ReplayErrorCode.VIEWER_TOKEN_EXPIRED,
          HttpStatus.GONE,
          false,
          "The viewer token has expired",
          "Request a new viewer session");
    }
    if (verification != TokenVerification.VALID) {
      throw new ReplayException(
          ReplayErrorCode.VIEWER_TOKEN_INVALID,
          HttpStatus.UNAUTHORIZED,
          false,
          "The viewer token is invalid",
          "Request a new viewer session");
    }
  }

  private String signToken(UUID runId, Instant expiresAt) {
    String payload =
        runId + TOKEN_SEPARATOR + expiresAt.getEpochSecond() + TOKEN_SEPARATOR + UUID.randomUUID();
    String encodedPayload =
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    String signature =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(encodedPayload));
    return encodedPayload + TOKEN_SEPARATOR + signature;
  }

  private TokenVerification verifyToken(UUID runId, String token) {
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2) return TokenVerification.INVALID;
    byte[] payloadBytes;
    byte[] signature;
    try {
      payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
      signature = Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException ex) {
      return TokenVerification.INVALID;
    }
    byte[] expected = hmac(parts[0]);
    if (!MessageDigest.isEqual(expected, signature)) return TokenVerification.INVALID;
    String payload = new String(payloadBytes, StandardCharsets.UTF_8);
    String[] values = payload.split("\\.", -1);
    if (values.length != 3 || !runId.toString().equals(values[0])) return TokenVerification.INVALID;
    long expires;
    try {
      expires = Long.parseLong(values[1]);
    } catch (NumberFormatException ex) {
      return TokenVerification.INVALID;
    }
    return Instant.now().getEpochSecond() >= expires
        ? TokenVerification.EXPIRED
        : TokenVerification.VALID;
  }

  private byte[] hmac(String encodedPayload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              properties.getViewer().getSigningSecret().getBytes(StandardCharsets.UTF_8),
              "HmacSHA256"));
      return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
      throw new ReplayException(
          ReplayErrorCode.INTERNAL_ERROR,
          HttpStatus.INTERNAL_SERVER_ERROR,
          false,
          "Viewer token signing is unavailable",
          "Check the server cryptography provider");
    }
  }

  private static String encodeCursor(int index) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("frame:" + index).getBytes(StandardCharsets.UTF_8));
  }

  private static int decodeCursor(String cursor, int frameCount) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      if (!value.startsWith("frame:")) throw new IllegalArgumentException();
      int index = Integer.parseInt(value.substring("frame:".length()));
      if (index < 0 || index > frameCount) throw new IllegalArgumentException();
      return index;
    } catch (IllegalArgumentException ex) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "The trace cursor is invalid");
    }
  }

  private static String trimTrailingSlash(String value) {
    if (value == null || value.isBlank()) return "";
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private ReplayException traceCorrupt(String message) {
    return new ReplayException(
        ReplayErrorCode.TRACE_CORRUPT,
        HttpStatus.CONFLICT,
        false,
        message,
        "Regenerate the replay trace; the stored evidence is corrupt");
  }

  private enum TokenVerification {
    VALID,
    EXPIRED,
    INVALID
  }

  private record TraceDocument(String checksum, List<JsonNode> frames) {}
}
