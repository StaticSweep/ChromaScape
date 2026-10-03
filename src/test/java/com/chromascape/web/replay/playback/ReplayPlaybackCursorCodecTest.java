package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ReplayPlaybackCursorCodecTest {

  private final ReplayPlaybackCursorCodec codec = new ReplayPlaybackCursorCodec(new ObjectMapper());

  @Test
  void roundTripsUrlSafeUnpaddedCursor() {
    LocalDateTime startTime = LocalDateTime.of(2026, 8, 28, 3, 12, 13, 456000000);

    String encoded = codec.encode(startTime, 42L);
    ReplayPlaybackCursorCodec.Cursor decoded = codec.decode(encoded);

    assertEquals(startTime, decoded.startTime());
    assertEquals(42L, decoded.id());
    assertEquals(-1, encoded.indexOf('='));
    assertEquals(-1, encoded.indexOf('+'));
    assertEquals(-1, encoded.indexOf('/'));
  }

  @Test
  void missingCursorStartsAtTheBeginning() {
    assertNull(codec.decode(null));
    assertNull(codec.decode("  "));
  }

  @Test
  void malformedAndUnsupportedCursorsAreStableValidationErrors() {
    assertValidationError("not-base64");

    String unsupported =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"version\":2,\"startTime\":\"2026-08-28T03:12:13\",\"id\":42}"
                    .getBytes(StandardCharsets.UTF_8));
    assertValidationError(unsupported);

    String incomplete =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"version\":1,\"id\":42}".getBytes(StandardCharsets.UTF_8));
    assertValidationError(incomplete);
  }

  @Test
  void nonPositiveIdsAndInvalidStartTimesAreRejected() {
    assertValidationError(
        encoded("{\"version\":1,\"startTime\":\"2026-08-28T03:12:13\",\"id\":0}"));
    assertValidationError(encoded("{\"version\":1,\"startTime\":\"not-a-time\",\"id\":42}"));
    assertValidationError(
        encoded("{\"version\":1,\"startTime\":\"2026-08-28T03:12:13\",\"id\":42}{}"));
    assertValidationError(
        encoded("{\"version\":1.0,\"startTime\":\"2026-08-28T03:12:13\",\"id\":42}"));
  }

  private static String encoded(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private void assertValidationError(String cursor) {
    ReplayException error = assertThrows(ReplayException.class, () -> codec.decode(cursor));
    assertEquals("VALIDATION_ERROR", error.getErrorCode().name());
  }
}
