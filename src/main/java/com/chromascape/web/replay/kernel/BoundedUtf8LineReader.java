package com.chromascape.web.replay.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Byte-bounded UTF-8 line reader for UNTRUSTED child-process output.
 *
 * <p>Unlike {@code BufferedReader.readLine()}, which allocates the entire line BEFORE any size
 * check, this reader enforces the byte ceiling WHILE reading and never allocates beyond it. It also
 * rejects malformed UTF-8 and treats EOF-before-newline as an explicit protocol condition. CRLF is
 * accepted and normalized to LF.
 */
public final class BoundedUtf8LineReader implements AutoCloseable {

  /** Terminal read outcomes. Any non-LINE outcome leaves the stream unusable. */
  public enum Status {
    /** A complete newline-terminated, valid-UTF-8 line within bounds. */
    LINE,
    /** More than the configured bytes arrived before a newline; reading stopped at the cap. */
    OVERSIZED,
    /** The bytes before the terminator were not valid UTF-8. */
    MALFORMED_UTF8,
    /** Stream ended before a terminating newline while a partial line was pending. */
    UNTERMINATED_AT_EOS,
    /** Clean end of stream with no pending bytes. */
    END_OF_STREAM
  }

  public record Read(Status status, String line) {}

  private final InputStream in;
  private final int maxLineBytes;
  private byte[] buffer;
  private int length;
  private boolean eof;

  public BoundedUtf8LineReader(InputStream in, long maxLineBytes) {
    this.in = in;
    this.maxLineBytes = (int) Math.min(maxLineBytes, Integer.MAX_VALUE - 8);
    this.buffer = new byte[Math.max(256, this.maxLineBytes)];
  }

  /**
   * Reads one line up to the configured ceiling.
   *
   * @return the read outcome; {@code line} is populated only for {@link Status#LINE}
   * @throws IOException on underlying stream failure
   */
  @SuppressWarnings("checkstyle:FinalParameters")
  public Read readLine() throws IOException {
    length = 0;
    while (true) {
      int b = in.read();
      if (b == -1) {
        eof = true;
        if (length == 0) {
          return new Read(Status.END_OF_STREAM, null);
        }
        return new Read(Status.UNTERMINATED_AT_EOS, null);
      }
      if (b == '\n') {
        int end = length;
        if (end > 0 && buffer[end - 1] == '\r') {
          end--;
        }
        try {
          CharsetDecoder decoder =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT);
          String line = decoder.decode(java.nio.ByteBuffer.wrap(buffer, 0, end)).toString();
          return new Read(Status.LINE, line);
        } catch (Exception malformed) {
          return new Read(Status.MALFORMED_UTF8, null);
        }
      }
      if (length >= maxLineBytes) {
        // Stop reading immediately: no further allocation, no further consumption.
        return new Read(Status.OVERSIZED, null);
      }
      if (length == buffer.length) {
        grow();
      }
      buffer[length++] = (byte) b;
    }
  }

  private void grow() {
    int newSize = Math.min(buffer.length * 2, maxLineBytes);
    if (newSize <= buffer.length) {
      newSize = maxLineBytes;
    }
    byte[] grown = new byte[newSize];
    System.arraycopy(buffer, 0, grown, 0, length);
    buffer = grown;
  }

  /** Closes the underlying stream. */
  @Override
  public void close() throws IOException {
    in.close();
  }
}
