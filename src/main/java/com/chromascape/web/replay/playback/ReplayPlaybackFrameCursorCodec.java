package com.chromascape.web.replay.playback;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Encodes and validates the compound cursor used to page captured playback frames. */
@Component
public class ReplayPlaybackFrameCursorCodec {

  public static final int CURRENT_VERSION = 1;

  private final ObjectMapper objectMapper;

  public ReplayPlaybackFrameCursorCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Encodes a {@code (tick,id)} ordering tuple as unpadded URL-safe Base64 JSON. */
  public String encode(long tick, long id) {
    if (tick < 0 || id <= 0) {
      throw new IllegalArgumentException("A frame cursor requires a nonnegative tick and positive row id");
    }
    try {
      byte[] json =
          objectMapper
              .createObjectNode()
              .put("version", CURRENT_VERSION)
              .put("tick", tick)
              .put("id", id)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Unable to encode replay frame cursor", ex);
    }
  }

  /**
   * Decodes a supplied frame cursor. A missing or blank cursor means the initial page; every
   * nonblank malformed cursor is a stable validation error.
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
          || !payload.hasNonNull("tick")
          || !payload.hasNonNull("id")
          || !payload.get("version").isIntegralNumber()
          || !payload.get("version").canConvertToInt()
          || !payload.get("tick").isIntegralNumber()
          || !payload.get("tick").canConvertToLong()
          || !payload.get("id").isIntegralNumber()
          || !payload.get("id").canConvertToLong()) {
        throw invalidCursor();
      }

      int version = payload.get("version").intValue();
      long tick = payload.get("tick").longValue();
      long id = payload.get("id").longValue();
      if (version != CURRENT_VERSION || tick < 0 || id <= 0) {
        throw invalidCursor();
      }
      return new Cursor(tick, id);
    } catch (ReplayException ex) {
      throw ex;
    } catch (IllegalArgumentException | IOException ex) {
      throw invalidCursor();
    }
  }

  private static ReplayException invalidCursor() {
    return ReplayException.badRequest(
        ReplayErrorCode.VALIDATION_ERROR, "The replay frame cursor is invalid");
  }

  public record Cursor(long tick, long id) {}
}
