package com.chromascape.web.debug.tickdebugger.video;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Single-range HTTP byte handling for controlled media roles. Only a single {@code bytes=a-b} range
 * is honoured; multi-range and syntactically invalid headers are rejected by the controller as 416.
 */
public final class MouseVideoMediaRange {

  private MouseVideoMediaRange() {}

  public record ByteRange(long start, long end) {
    public long length() {
      return end - start + 1L;
    }
  }

  /** Parses a single range against a known length, or empty when there is no range header. */
  public static Optional<ByteRange> parse(String header, long length) {
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    String value = header.trim();
    if (!value.startsWith("bytes=") || value.contains(",")) {
      throw new InvalidRangeException();
    }
    String spec = value.substring("bytes=".length()).trim();
    int dash = spec.indexOf('-');
    if (dash < 0) {
      throw new InvalidRangeException();
    }
    String startPart = spec.substring(0, dash).trim();
    String endPart = spec.substring(dash + 1).trim();
    long start;
    long end;
    if (startPart.isEmpty()) {
      if (endPart.isEmpty()) {
        throw new InvalidRangeException();
      }
      long suffix = parseLong(endPart);
      if (suffix <= 0) {
        throw new InvalidRangeException();
      }
      start = Math.max(0L, length - suffix);
      end = length - 1L;
    } else {
      start = parseLong(startPart);
      end = endPart.isEmpty() ? length - 1L : parseLong(endPart);
    }
    if (start < 0 || end < start || start >= length) {
      throw new InvalidRangeException();
    }
    return Optional.of(new ByteRange(start, Math.min(end, length - 1L)));
  }

  /** Streams exactly the requested slice without loading the whole file into memory. */
  public static InputStream slice(Path path, ByteRange range) throws IOException {
    InputStream stream = Files.newInputStream(path);
    try {
      stream.skipNBytes(range.start());
    } catch (IOException | RuntimeException failure) {
      stream.close();
      throw failure;
    }
    return new BoundedSliceInputStream(stream, range.length());
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new InvalidRangeException();
    }
  }

  /** Signals an unsatisfiable or malformed range, mapped to HTTP 416 by the controller. */
  public static class InvalidRangeException extends RuntimeException {
    public InvalidRangeException() {
      super("Requested byte range is not satisfiable");
    }
  }

  private static final class BoundedSliceInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;

    private BoundedSliceInputStream(InputStream delegate, long remaining) {
      this.delegate = delegate;
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int value = delegate.read();
      if (value >= 0) {
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int allowed = (int) Math.min(length, remaining);
      int read = delegate.read(buffer, offset, allowed);
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
