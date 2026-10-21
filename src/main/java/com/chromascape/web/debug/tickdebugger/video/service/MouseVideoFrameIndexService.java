package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoCorrectionContract;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the frozen frame index of one completed analysis.
 *
 * <p>The index is an immutable, analysis-scoped artifact, so every read is pinned to its content
 * address. A page cursor embeds that address: a cursor minted for a different analysis (or for an
 * index that has since been replaced) is rejected rather than silently resolved against different
 * frames. GET requests only read published bytes; they never spawn a decoder or create work.
 *
 * <p>Integer presentation timestamps and the source timebase are transported as decimal strings.
 * The browser may convert them for display, but rounding there must never be mistaken for the
 * source identity recorded here.
 */
@Service
public class MouseVideoFrameIndexService {

  public static final String PAGE_CONTRACT = "frame-index-page/v1";
  private static final String CURSOR_PREFIX = "v1";
  private static final long MAX_INDEX_BYTES = 128L * 1024 * 1024;
  private static final int MAX_ENCODED_PAGE_BYTES = 256 * 1024;
  private static final int CACHE_ENTRIES = 4;

  private final MouseVideoProperties properties;
  private final MouseVideoSourceRepository sources;
  private final MouseVideoAnalysisRepository analyses;
  private final MouseVideoArtifactRefRepository artifacts;
  private final MouseVideoByteStore byteStore;
  private final ObjectMapper mapper;

  private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
  private final Map<String, FrozenFrameIndex> cache = new LinkedHashMap<>(8, 0.75f, true);
  private long cacheBytes;

  public MouseVideoFrameIndexService(
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

  /** One indexed source frame: integer PTS and rational timebase survive as exact strings. */
  public record Frame(String pts, String ptsTime, String duration, boolean key) {}

  /** Immutable parsed index. {@code frames} is never mutated after construction. */
  public record FrozenFrameIndex(
      String checksum,
      String schema,
      boolean complete,
      String timeBase,
      String timeBaseNumerator,
      String timeBaseDenominator,
      List<Frame> frames) {

    public int frameCount() {
      return frames.size();
    }
  }

  /** Decoded page cursor: the immutable artifact address plus a frame position. */
  public record PageCursor(String checksum, int offset) {}

  /**
   * Resolves the frozen frame index for an owned, completed analysis.
   *
   * @throws MouseVideoException {@code NOT_FOUND} for an unknown or unbound analysis, {@code
   *     SOURCE_UNAVAILABLE} when no finalized index exists
   */
  @Transactional(readOnly = true)
  public FrozenFrameIndex requireIndex(String owner, UUID sourceId, UUID analysisId) {
    MouseVideoAnalysis analysis = requireCompletedAnalysis(owner, sourceId, analysisId);
    MouseVideoArtifactRef ref =
        artifacts
            .findByAnalysisIdAndRoleAndState(
                analysis.getId(),
                MouseVideoArtifactRef.ROLE_FRAME_INDEX,
                MouseVideoArtifactRef.STATE_FINALIZED)
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "No frozen frame index is published for this analysis"));
    if (!owner.equals(ref.getOwner())
        || !analysis.getId().equals(ref.getAnalysisId())
        || !sourceId.equals(ref.getSourceId())) {
      throw notFound();
    }
    return load(ref);
  }

  /**
   * Returns one bounded page of the frozen index.
   *
   * @param cursor opaque cursor previously returned by this endpoint, or {@code null} for the head
   * @param limit requested page size; clamped to {@code maxObservationPageSize}
   */
  @Transactional(readOnly = true)
  public Map<String, Object> page(
      String owner, UUID sourceId, UUID analysisId, String cursor, int limit) {
    FrozenFrameIndex index = requireIndex(owner, sourceId, analysisId);
    int boundedLimit =
        Math.max(1, Math.min(properties.getMaxObservationPageSize(), limit <= 0 ? 100 : limit));
    PageCursor position = decodeCursor(cursor, index.checksum());
    if (position.offset() > index.frames().size()) {
      throw invalidCursor();
    }
    List<Map<String, Object>> items = new ArrayList<>();
    int encodedBytes = 0;
    int positionIndex = position.offset();
    boolean byteBoundReached = false;
    while (positionIndex < index.frames().size() && items.size() < boundedLimit) {
      Map<String, Object> view = frameView(positionIndex, index.frames().get(positionIndex));
      int size = view.toString().getBytes(StandardCharsets.UTF_8).length + 1;
      if (!items.isEmpty() && encodedBytes + size > MAX_ENCODED_PAGE_BYTES) {
        byteBoundReached = true;
        break;
      }
      items.add(view);
      encodedBytes += size;
      positionIndex++;
    }
    boolean more = positionIndex < index.frames().size();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("contract", PAGE_CONTRACT);
    body.put("schema", index.schema());
    body.put("artifactChecksum", index.checksum());
    body.put("complete", index.complete());
    body.put("timeBase", timeBaseView(index));
    body.put("frameCount", index.frameCount());
    body.put("offset", position.offset());
    body.put("limit", boundedLimit);
    body.put("items", items);
    body.put("nextCursor", more ? encodeCursor(index.checksum(), positionIndex) : null);
    body.put("boundedByEncodedBytes", byteBoundReached);
    return body;
  }

  /** Frame count and declared source bounds used to validate review corrections. */
  @Transactional(readOnly = true)
  public MouseVideoCorrectionContract.Bounds bounds(String owner, UUID sourceId, UUID analysisId) {
    FrozenFrameIndex index = requireIndex(owner, sourceId, analysisId);
    if (!index.complete()
        || index.frameCount() == 0
        || index.timeBaseNumerator() == null
        || index.timeBaseDenominator() == null) {
      throw new MouseVideoException(
          MouseVideoException.CODE_SOURCE_UNAVAILABLE,
          "Corrections require a complete frozen frame index with a valid timebase");
    }
    return new MouseVideoCorrectionContract.Bounds(
        index.frameCount(), properties.getMaxWidth(), properties.getMaxHeight());
  }

  private MouseVideoAnalysis requireCompletedAnalysis(
      String owner, UUID sourceId, UUID analysisId) {
    MouseVideoSource source =
        sources
            .findByIdAndOwner(sourceId, owner)
            .orElseThrow(MouseVideoFrameIndexService::notFound);
    MouseVideoAnalysis analysis =
        analyses
            .findByIdAndOwner(analysisId, owner)
            .orElseThrow(MouseVideoFrameIndexService::notFound);
    if (!sourceId.equals(analysis.getSourceId())
        || !MouseVideoAnalysis.STATUS_COMPLETE.equals(analysis.getStatus())) {
      throw notFound();
    }
    if (source.getOriginalChecksum() == null
        || !source.getOriginalChecksum().equals(analysis.getOriginalDigest())) {
      throw notFound();
    }
    return analysis;
  }

  private synchronized FrozenFrameIndex load(MouseVideoArtifactRef ref) {
    FrozenFrameIndex cached = cache.get(ref.getChecksum());
    if (cached != null) {
      return cached;
    }
    Path path =
        byteStore
            .locate(ref.getChecksum())
            .orElseThrow(
                () ->
                    new MouseVideoException(
                        MouseVideoException.CODE_SOURCE_UNAVAILABLE,
                        "The frozen frame index bytes are unavailable"));
    try {
      if (Files.size(path) > MAX_INDEX_BYTES) {
        throw new MouseVideoException(
            MouseVideoException.CODE_DECODER_FAILED, "The frozen frame index is implausibly large");
      }
      FrozenFrameIndex parsed = parse(ref.getChecksum(), path);
      long weight = estimateBytes(parsed);
      if (weight <= MAX_CACHE_BYTES) {
        cache.put(ref.getChecksum(), parsed);
        cacheBytes += weight;
        while (cache.size() > CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) {
          var oldest = cache.entrySet().iterator();
          cacheBytes -= estimateBytes(oldest.next().getValue());
          oldest.remove();
        }
      }
      return parsed;
    } catch (IOException failure) {
      throw new MouseVideoException(
          MouseVideoException.CODE_DECODER_FAILED, "The frozen frame index is unreadable", failure);
    }
  }

  private FrozenFrameIndex parse(String checksum, Path path) throws IOException {
    String schema = "";
    boolean complete = false;
    String timeBase = null;
    boolean seenFrames = false;
    List<Frame> frames = new ArrayList<>();
    try (var parser = MouseVideoJsonStream.open(path)) {
      MouseVideoJsonStream.root(parser);
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        if (!parser.hasToken(JsonToken.FIELD_NAME)) throw new IOException("Malformed frame index");
        String field = parser.currentName();
        JsonToken token = parser.nextToken();
        if (token == null) throw new IOException("Truncated frame index");
        if ("schema".equals(field) && token == JsonToken.VALUE_STRING) {
          schema = parser.getText();
        } else if ("complete".equals(field) && token.isBoolean()) {
          complete = parser.getBooleanValue();
        } else if ("timeBase".equals(field) && token == JsonToken.VALUE_STRING) {
          timeBase = parser.getText();
        } else if ("frames".equals(field) && token == JsonToken.START_ARRAY) {
          if (seenFrames) throw new IOException("Duplicate frame index");
          seenFrames = true;
          while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (!parser.hasToken(JsonToken.START_OBJECT)
                || frames.size() >= Math.min(150_000L, properties.getMaxIndexedFrames())) {
              throw new IOException("Invalid frame or frame-count limit exceeded");
            }
            JsonNode frame =
                MouseVideoJsonStream.scalars(
                    parser, mapper, List.of("pts", "ptsTime", "duration", "key"));
            frames.add(
                new Frame(
                    exact(frame.get("pts")),
                    exact(frame.get("ptsTime")),
                    exact(frame.get("duration")),
                    frame.path("key").asBoolean(false)));
          }
        } else {
          parser.skipChildren();
        }
      }
      MouseVideoJsonStream.eof(parser);
    }
    String[] parts = splitTimeBase(timeBase);
    return new FrozenFrameIndex(
        checksum,
        schema,
        complete,
        timeBase,
        parts == null || frames.isEmpty() ? null : parts[0],
        parts == null || frames.isEmpty() ? null : parts[1],
        List.copyOf(frames));
  }

  private static long estimateBytes(FrozenFrameIndex index) {
    long bytes = 512;
    for (Frame frame : index.frames()) {
      bytes += 256L;
      for (String field : new String[] {frame.pts(), frame.ptsTime(), frame.duration()}) {
        if (field != null) bytes += 2L * field.length();
      }
    }
    return bytes;
  }

  /**
   * Preserves the exact textual magnitude of a JSON number. {@code asText} is used instead of
   * {@code longValue} so a PTS beyond 2^53 is never rounded before it reaches the client.
   */
  private static String exact(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return node.asText();
    }
    if (node.isTextual()) {
      String value = node.textValue().trim();
      if (value.isEmpty()) {
        return null;
      }
      try {
        BigDecimal number = new BigDecimal(value);
        if (number.precision() > 128 || Math.abs((long) number.scale()) > 128) return null;
        return number.toPlainString();
      } catch (NumberFormatException invalid) {
        return null;
      }
    }
    return null;
  }

  private static String[] splitTimeBase(String value) {
    if (value == null) {
      return null;
    }
    String[] parts = value.trim().split("/", -1);
    if (parts.length != 2 || !parts[0].matches("[0-9]+") || !parts[1].matches("[0-9]+")) {
      return null;
    }
    try {
      if (Long.parseLong(parts[0]) <= 0 || Long.parseLong(parts[1]) <= 0) {
        return null;
      }
    } catch (NumberFormatException invalid) {
      return null;
    }
    return new String[] {parts[0], parts[1]};
  }

  private static Map<String, Object> timeBaseView(FrozenFrameIndex index) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("numerator", index.timeBaseNumerator());
    view.put("denominator", index.timeBaseDenominator());
    view.put("raw", index.timeBase());
    return view;
  }

  private static Map<String, Object> frameView(int ordinal, Frame frame) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("ordinal", ordinal);
    view.put("pts", frame.pts());
    view.put("ptsTime", frame.ptsTime());
    view.put("duration", frame.duration());
    view.put("key", frame.key());
    return view;
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
        MouseVideoException.CODE_INVALID_REQUEST, "Invalid frame-index cursor");
  }

  private static MouseVideoException notFound() {
    return new MouseVideoException(
        MouseVideoException.CODE_NOT_FOUND, "Analysis is not available to this owner");
  }

  /** Package-private helper so tests can assert cursor parsing without reflection. */
  Optional<PageCursor> parseCursor(String cursor, String checksum) {
    return Optional.of(decodeCursor(cursor, checksum));
  }
}
