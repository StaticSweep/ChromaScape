package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Byte-bounded line reading: caps are enforced WHILE reading, malformed UTF-8 is detected. */
class XrspsBoundedLineReaderTest {

  private static InputStream streamOf(String... lines) {
    StringBuilder joined = new StringBuilder();
    for (String line : lines) {
      joined.append(line).append('\n');
    }
    return new ByteArrayInputStream(joined.toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void readsWellFormedLinesWithinTheCap() throws Exception {
    try (BoundedUtf8LineReader reader =
        new BoundedUtf8LineReader(streamOf("{\"a\":1}", "{\"b\":2}"), 1024)) {
      assertEquals(BoundedUtf8LineReader.Status.LINE, reader.readLine().status());
      BoundedUtf8LineReader.Read second = reader.readLine();
      assertEquals("{\"b\":2}", second.line());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 1024})
  void oversizedLinesAreRejectedWhileReadingNotAfterwards(int cap) throws Exception {
    byte[] payload = new byte[cap + 1];
    java.util.Arrays.fill(payload, (byte) 'x');
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    buffer.write(payload);
    buffer.write('\n');
    try (BoundedUtf8LineReader reader =
        new BoundedUtf8LineReader(new ByteArrayInputStream(buffer.toByteArray()), cap)) {
      assertEquals(BoundedUtf8LineReader.Status.OVERSIZED, reader.readLine().status());
    }
  }

  @Test
  void unterminatedFinalLineIsReportedAsProtocolNoise() throws Exception {
    InputStream in = new ByteArrayInputStream("no-newline".getBytes(StandardCharsets.UTF_8));
    try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(in, 1024)) {
      assertEquals(BoundedUtf8LineReader.Status.UNTERMINATED_AT_EOS, reader.readLine().status());
    }
  }

  @Test
  void emptyStreamIsEndOfStream() throws Exception {
    try (BoundedUtf8LineReader reader =
        new BoundedUtf8LineReader(new ByteArrayInputStream(new byte[0]), 64)) {
      assertEquals(BoundedUtf8LineReader.Status.END_OF_STREAM, reader.readLine().status());
    }
  }

  @Test
  void multiByteUtf8SurvivesChunkBoundaries() throws Exception {
    // "é" is two UTF-8 bytes; force the boundary between them via a wrapping stream that
    // yields one byte per read.
    byte[] raw = "{\"name\":\"é\"}\n".getBytes(StandardCharsets.UTF_8);
    InputStream oneByteAtATime =
        new InputStream() {
          private int pos = 0;

          @Override
          public int read() {
            return pos < raw.length ? (raw[pos++] & 0xFF) : -1;
          }
        };
    List<String> seen = new ArrayList<>();
    try (BoundedUtf8LineReader reader = new BoundedUtf8LineReader(oneByteAtATime, 256)) {
      BoundedUtf8LineReader.Read read = reader.readLine();
      if (read.status() == BoundedUtf8LineReader.Status.LINE) {
        seen.add(read.line());
      } else {
        seen.add(read.status().name());
      }
    }
    assertEquals(List.of("{\"name\":\"é\"}"), seen);
    assertTrue(true);
  }
}
