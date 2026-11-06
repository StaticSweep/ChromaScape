package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoMediaRangeTest {

  @TempDir Path root;

  @Test
  void parsesCommonRangeForms() {
    assertThat(MouseVideoMediaRange.parse(null, 100L)).isEmpty();
    assertThat(MouseVideoMediaRange.parse("bytes=0-9", 100L))
        .contains(new MouseVideoMediaRange.ByteRange(0L, 9L));
    assertThat(MouseVideoMediaRange.parse("bytes=90-", 100L))
        .contains(new MouseVideoMediaRange.ByteRange(90L, 99L));
    assertThat(MouseVideoMediaRange.parse("bytes=-10", 100L))
        .contains(new MouseVideoMediaRange.ByteRange(90L, 99L));
    assertThat(MouseVideoMediaRange.parse("bytes=95-1000", 100L))
        .contains(new MouseVideoMediaRange.ByteRange(95L, 99L));
  }

  @Test
  void rejectsMalformedOrUnsatisfiableRanges() {
    assertThatThrownBy(() -> MouseVideoMediaRange.parse("bytes=100-200", 100L))
        .isInstanceOf(MouseVideoMediaRange.InvalidRangeException.class);
    assertThatThrownBy(() -> MouseVideoMediaRange.parse("bytes=0-9,20-29", 100L))
        .isInstanceOf(MouseVideoMediaRange.InvalidRangeException.class);
    assertThatThrownBy(() -> MouseVideoMediaRange.parse("items=0-9", 100L))
        .isInstanceOf(MouseVideoMediaRange.InvalidRangeException.class);
    assertThatThrownBy(() -> MouseVideoMediaRange.parse("bytes=abc-9", 100L))
        .isInstanceOf(MouseVideoMediaRange.InvalidRangeException.class);
  }

  @Test
  void slicesExactlyTheRequestedBytes() throws IOException {
    Path file = root.resolve("blob");
    Files.writeString(file, "0123456789");
    try (InputStream stream =
        MouseVideoMediaRange.slice(file, new MouseVideoMediaRange.ByteRange(2L, 5L))) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("2345");
    }
  }
}
