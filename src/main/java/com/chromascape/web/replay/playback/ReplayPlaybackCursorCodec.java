package com.chromascape.web.replay.playback;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Encodes and validates the opaque keyset cursor used by the playback catalog. */
@Component
public class ReplayPlaybackCursorCodec {

  public static final int CURRENT_VERSION = 1;

  private final ObjectMapper objectMapper;

  public ReplayPlaybackCursorCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Encodes the final catalog ordering tuple as URL-safe, unpadded Base64 JSON. */
  public String encode(LocalDateTime startTime, long id) {
    if (startTime == null || id <= 0) {
      throw new IllegalArgumentException("A cursor requires a start time and positive row id");
    }
    try {
      byte[] json =
          objectMapper
              .createObjectNode()
              .put("version", CURRENT_VERSION)
              .put("startTime", startTime.toString())
              .put("id", id)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Unable to encode replay cursor", ex);
    }
  }

  /**
   * Decodes a cursor. A missing cursor means the first page; every supplied malformed cursor is a
   * stable validation error.
   */
  public Cursor decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    try {
      byte[] json = Base64.getUrlDecoder().decode(encoded);
      JsonNode payload;
      try (JsonParser parser =
          objectMapper.getFactory().createParser(new String(json, StandardCharsets.UTF_8))) {
        payload = objectMapper.readTree(parser);
        if (parser.nextToken() != null) {
          throw invalidCursor();
        }
      }
      if (payload == null
          || !payload.isObject()
          || !payload.hasNonNull("version")
          || !payload.hasNonNull("startTime")
          || !payload.hasNonNull("id")
          || !payload.get("version").isIntegralNumber()
          || !payload.get("version").canConvertToInt()
          || !payload.get("id").isIntegralNumber()
          || !payload.get("id").canConvertToLong()
          || !payload.get("startTime").isTextual()) {
        throw invalidCursor();
      }

      int version = payload.get("version").intValue();
      if (version != CURRENT_VERSION) {
        throw invalidCursor();
      }
      long id = payload.get("id").longValue();
      if (id <= 0) {
        throw invalidCursor();
      }
      LocalDateTime startTime = LocalDateTime.parse(payload.get("startTime").textValue());
      return new Cursor(startTime, id);
    } catch (ReplayException ex) {
      throw ex;
    } catch (IllegalArgumentException | IOException | DateTimeParseException ex) {
      throw invalidCursor();
    }
  }

  private static ReplayException invalidCursor() {
    return ReplayException.badRequest(
        ReplayErrorCode.VALIDATION_ERROR, "The replay catalog cursor is invalid");
  }

  public record Cursor(LocalDateTime startTime, long id) {}
}
