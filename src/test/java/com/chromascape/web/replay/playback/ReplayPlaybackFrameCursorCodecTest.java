package com.chromascape.web.replay.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ReplayPlaybackFrameCursorCodecTest {

  private final ReplayPlaybackFrameCursorCodec codec =
      new ReplayPlaybackFrameCursorCodec(new ObjectMapper());

  @Test
  void roundTripsUnpaddedUrlSafeCursorWithTickAndId() {
    String encoded = codec.encode(42L, 99L);

    ReplayPlaybackFrameCursorCodec.Cursor decoded = codec.decode(encoded);

    assertEquals(42L, decoded.tick());
    assertEquals(99L, decoded.id());
    assertEquals(-1, encoded.indexOf('='));
    assertEquals(-1, encoded.indexOf('+'));
    assertEquals(-1, encoded.indexOf('/'));
  }

  @Test
  void blankCursorMeansInitialPage() {
    assertNull(codec.decode(null));
    assertNull(codec.decode("  "));
  }

  @Test
  void malformedIncompleteUnsupportedAndOutOfRangeCursorsAreValidationErrors() {
    assertValidationError("not-base64");
    assertValidationError(encoded("{\"version\":1,\"tick\":4}"));
    assertValidationError(encoded("{\"version\":2,\"tick\":4,\"id\":9}"));
    assertValidationError(encoded("{\"version\":1,\"tick\":-1,\"id\":9}"));
    assertValidationError(encoded("{\"version\":1,\"tick\":4,\"id\":0}"));
    assertValidationError(encoded("{\"version\":1,\"tick\":1.5,\"id\":9}"));
    assertValidationError(encoded("{\"version\":1,\"tick\":4,\"id\":9}{}"));
    assertValidationError(encoded("{\"version\":1.0,\"tick\":4,\"id\":9}"));
  }

  private void assertValidationError(String encoded) {
    ReplayException error = assertThrows(ReplayException.class, () -> codec.decode(encoded));
    assertEquals(400, error.getHttpStatus().value());
    assertEquals("VALIDATION_ERROR", error.getErrorCode().name());
  }

  private static String encoded(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
