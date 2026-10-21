package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRef;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the published observations of one completed analysis.
 *
 * <p>An analysis without a published OBSERVATIONS artifact is answered honestly: {@code available}
 * is false and the response names what is missing. The service never fabricates an observation, an
 * unknown reason or a coordinate, and it never triggers analysis work from a read.
 *
 * <p>Paging is bounded by the configured page cap and an encoded-byte cap, and every cursor is
 * pinned to the artifact content address so a page can only continue over the same immutable bytes.
 */
@Service
public class MouseVideoObservationsService {

  public static final String CONTRACT = "observations-read/v1";
  private static final String CURSOR_PREFIX = "obs1";
  private static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024;
  private static final int MAX_ENCODED_PAGE_BYTES = 512 * 1024;
  private static final java.util.concurrent.Semaphore READS = new java.util.concurrent.Semaphore(4);

  private static final List<String> PROJECTED_FIELDS =
      List.of(
          "kind",
          "frameOrdinal",
          "pts",
          "visibility",
          "confidence",
          "evidence",
          "unknownReason",
          "x",
          "y",
          "width",
          "height",
          "label",
          "activity",
          "indicator",
          "method",
          "methodVersion");

  private final MouseVideoProperties properties;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoByteStore byteStore;
  private final ObjectMapper mapper;

  public MouseVideoObservationsService(
      MouseVideoProperties properties,
      MouseVideoSourceRepository sources,
      MouseVideoAnalysisRepository analyses,
      MouseVideoArtifactRefRepository artifacts,
      MouseVideoByteStore byteStore,
      ObjectMapper mapper) {
    this.properties = properties;
    this.sources = sources;
    this.analyses = analyses;
    this.artifacts = artifacts;
    this.byteStore = byteStore;
    this.mapper = mapper;
  }

  /** Decoded page cursor: immutable artifact address plus an observation position. */
  public record PageCursor(String checksum, int offset) {}

  /**
   * Returns one bounded, optionally filtered page of published observations.
   *
   * @param kind optional observation kind filter, case-insensitive
   * @param sincePts optional inclusive lower bound on the integer source PTS
   * @param untilPts optional inclusive upper bound on the integer source PTS
   */
  @Transactional(readOnly = true)
  public Map<String, Object> list(
      String owner,
      UUID sourceId,
      UUID analysisId,
      String cursor,
      int limit,
      String kind,
      String sincePts,
      String untilPts) {
    MouseVideoAnalysis analysis = requireCompletedAnalysis(owner, sourceId, analysisId);
    int boundedLimit =
        Math.max(1, Math.min(properties.getMaxObservationPageSize(), limit <= 0 ? 100 : limit));
    String normalizedKind = normalizeKind(kind);
    BigInteger lower = parseBound(sincePts, "sincePts");
    BigInteger upper = parseBound(untilPts, "untilPts");
    if (lower != null && upper != null && lower.compareTo(upper) > 0) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "sincePts must not exceed untilPts");
    }
    Artifact artifact =
        resolveArtifact(
            owner, sourceId, analysis, cursor, boundedLimit, normalizedKind, lower, upper);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contract", CONTRACT);
    if (artifact == null) {
      body.put("available", false);
      body.put("analysisId", analysis.getId().toString());
      body.put("artifactChecksum", null);
      body.put("schema", null);
      body.put("offset", 0);
      body.put("limit", boundedLimit);
      body.put("items", List.of());
      body.put("nextCursor", null);
      body.put("unknowns", unknownsForMissingArtifact(analysis));
      return body;
    }
    int start = decodeCursor(cursor, artifact.checksum()).offset();
    List<JsonNode> matched = artifact.observations();
    List<Map<String, Object>> items = new ArrayList<>();
    int encodedBytes = 0;
    int consumed = 0;
    boolean byteBoundReached = false;
    for (JsonNode observation : matched) {
      if (items.size() >= boundedLimit) {
        break;
      }
      Map<String, Object> view = project(observation);
      int size = view.toString().getBytes(StandardCharsets.UTF_8).length + 1;
      if (!items.isEmpty() && encodedBytes + size > MAX_ENCODED_PAGE_BYTES) {
        byteBoundReached = true;
        break;
      }
      items.add(view);
      encodedBytes += size;
      consumed++;
    }
    boolean more = consumed < matched.size();
    body.put("available", true);
    body.put("analysisId", analysis.getId().toString());
    body.put("artifactChecksum", artifact.checksum());
    body.put("schema", artifact.schema());
    body.put("unknowns", List.of());
    body.put("offset", start);
    body.put("limit", boundedLimit);
    body.put("items", items);
    body.put("nextCursor", more ? encodeCursor(artifact.checksum(), start + consumed) : null);
    body.put("boundedByEncodedBytes", byteBoundReached);
    return body;
  }

  private boolean matches(JsonNode observation, String kind, BigInteger lower, BigInteger upper) {
    if (kind != null
        && !kind.equals(observation.path("kind").asText("").toUpperCase(Locale.ROOT))) {
      return false;
    }
    BigInteger pts = integer(observation.get("pts"));
    return (lower == null || (pts != null && pts.compareTo(lower) >= 0))
        && (upper == null || (pts != null && pts.compareTo(upper) <= 0));
  }

  private Map<String, Object> project(JsonNode observation) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (String field : PROJECTED_FIELDS) {
      JsonNode value = observation.get(field);
      if (value == null || value.isNull()) {
        continue;
      }
      if (value.isNumber()) {
        // Preserve exact magnitude; large PTS values must not pass through a double.
        view.put(field, value.asText());
      } else if (value.isBoolean()) {
        view.put(field, value.booleanValue());
      } else if (value.isTextual()) {
        view.put(field, value.textValue());
      }
    }
    return view;
  }

  private Artifact resolveArtifact(
      String owner,
      UUID sourceId,
      MouseVideoAnalysis analysis,
      String cursor,
      int limit,
      String kind,
      BigInteger lower,
      BigInteger upper) {
    MouseVideoArtifactRef ref =
        artifacts
            .findByAnalysisIdAndRoleAndState(
                analysis.getId(),
                MouseVideoArtifactRef.ROLE_OBSERVATIONS,
                MouseVideoArtifactRef.STATE_FINALIZED)
            .orElse(null);
    if (ref == null) {
      return null;
    }
    if (!owner.equals(ref.getOwner())
        || !sourceId.equals(ref.getSourceId())
        || !analysis.getId().equals(ref.getAnalysisId())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND, "Analysis is not available to this owner");
    }
    Path path =
        byteStore
            .locate(ref.getChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "Published observation bytes are unavailable"));
    boolean acquired = false;
    try {
      if (Files.size(path) > MAX_ARTIFACT_BYTES) {
        throw new MouseVideoException(
            MouseVideoException.CODE_DECODER_FAILED,
            "The published observation artifact is implausibly large");
      }
      if (!READS.tryAcquire()) {
        throw new MouseVideoException(
            MouseVideoException.CODE_QUOTA_EXCEEDED,
            "Observation read capacity is busy; retry shortly");
      }
      acquired = true;
      int skip = decodeCursor(cursor, ref.getChecksum()).offset();
      List<JsonNode> page = new ArrayList<>();
      String schema = "";
      int position = 0;
      boolean seenObservations = false;
      try (var parser = MouseVideoJsonStream.open(path)) {
        MouseVideoJsonStream.root(parser);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          if (!parser.hasToken(JsonToken.FIELD_NAME)) {
            throw new IOException("Malformed observation artifact");
          }
          String field = parser.currentName();
          JsonToken token = parser.nextToken();
          if ("schema".equals(field) && token == JsonToken.VALUE_STRING) {
            schema = parser.getText();
          } else if ("observations".equals(field) && token == JsonToken.START_ARRAY) {
            if (seenObservations) throw new IOException("Duplicate observation array");
            seenObservations = true;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
              if (parser.currentToken() == null) throw new IOException("Truncated observations");
              if (!parser.hasToken(JsonToken.START_OBJECT)) {
                parser.skipChildren();
                continue;
              }
              if (page.size() > limit) {
                parser.skipChildren();
                continue;
              }
              JsonNode observation = MouseVideoJsonStream.scalars(parser, mapper, PROJECTED_FIELDS);
              if (matches(observation, kind, lower, upper) && position++ >= skip) {
                page.add(observation);
              }
            }
          } else {
            if (token == null) throw new IOException("Truncated observation metadata");
            parser.skipChildren();
          }
        }
        MouseVideoJsonStream.eof(parser);
      }
      return new Artifact(ref.getChecksum(), schema, List.copyOf(page));
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED,
          "The published observation artifact is unreadable",
          failure);
    } finally {
      if (acquired) READS.release();
    }
  }

  private static List<String> unknownsForMissingArtifact(MouseVideoAnalysis analysis) {
    List<String> unknowns = new ArrayList<>();
    unknowns.add(
        "observations: no published observation artifact for analysis " + analysis.getId());
    unknowns.add(
        "cursor-tracking: unavailable until a reviewed cursor pack and bounded visual worker are"
            + " configured");
    unknowns.add("native-ticks: unavailable (SC-2151)");
    unknowns.add("movement-quality: unavailable (SC-309)");
    return unknowns;
  }

  private MouseVideoAnalysis requireCompletedAnalysis(
      String owner, UUID sourceId, UUID analysisId) {
    MouseVideoSource source =
        sources
            .findByIdAndOwner(sourceId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Source is not available to this owner"));
    MouseVideoAnalysis analysis =
        analyses
            .findByIdAndOwner(analysisId, owner)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_NOT_FOUND,
                        "Analysis is not available to this owner"));
    if (!sourceId.equals(analysis.getSourceId())
        || !MouseVideoAnalysis.STATUS_COMPLETE.equals(analysis.getStatus())
        || source.getOriginalChecksum() == null
        || !source.getOriginalChecksum().equals(analysis.getOriginalDigest())) {
      throw new MouseVideoException(
          MouseVideoException.CODE_NOT_FOUND, "Analysis is not available to this owner");
    }
    return analysis;
  }

  private static String normalizeKind(String kind) {
    if (kind == null || kind.isBlank()) {
      return null;
    }
    String normalized = kind.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z_]{1,40}")) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, "Invalid observation kind filter");
    }
    return normalized;
  }

  private static BigInteger parseBound(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      BigInteger parsed = new BigInteger(value.trim());
      if (parsed.signum() < 0) {
        throw new NumberFormatException("negative");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST, name + " must be a non-negative integer PTS");
    }
  }

  private static BigInteger integer(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      if (node.isNumber()) {
        return new BigInteger(node.asText());
      }
      if (node.isTextual()) {
        return new BigInteger(node.textValue().trim());
      }
    } catch (NumberFormatException invalid) {
      return null;
    }
    return null;
  }

  private static String encodeCursor(String checksum, int offset) {
    String raw = CURSOR_PREFIX + '|' + checksum + '|' + offset;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private PageCursor decodeCursor(String cursor, String checksum) {
    if (cursor == null || cursor.isBlank()) {
      return new PageCursor(checksum, 0);
    }
    String raw;
    try {
      raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalid) {
      throw invalidCursor();
    }
    String[] parts = raw.split("\\|", -1);
    if (parts.length != 3 || !CURSOR_PREFIX.equals(parts[0])) {
      throw invalidCursor();
    }
    if (!parts[1].equals(checksum)) {
      throw new MouseVideoException(
          MouseVideoException.CODE_INVALID_REQUEST,
          "The cursor belongs to a different analysis artifact");
    }
    try {
      int offset = Integer.parseInt(parts[2]);
      if (offset < 0) {
        throw invalidCursor();
      }
      return new PageCursor(checksum, offset);
    } catch (NumberFormatException invalid) {
      throw invalidCursor();
    }
  }

  private static MouseVideoException invalidCursor() {
    return new MouseVideoException(
        MouseVideoException.CODE_INVALID_REQUEST, "Invalid observation cursor");
  }

  /** Parsed artifact identity plus its published document. */
  private record Artifact(String checksum, String schema, List<JsonNode> observations) {}
}
