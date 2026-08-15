package com.chromascape.web.accounttask.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, streaming multipart/form-data reader for SC-572 report bundles (SC-572). The boundary is
 * read from the {@code ServletInputStream} after authorization; each part body is streamed directly
 * into a unique bounded temporary directory and is never buffered in memory. Limits are enforced
 * while bytes are read (including chunked requests without {@code Content-Length}), and traversal,
 * control characters, duplicate required names, sanitization collisions, unsupported content types
 * and malformed boundaries are rejected.
 *
 * <p>Exactly one part each of {@code plan}, {@code manifest}, {@code matrix}, {@code transitions},
 * {@code evidence}, {@code checksums} and {@code completeMarker} is permitted; optional
 * {@code artifacts} parts are permitted only with original filenames matching
 * {@code sha256_<64 hex>.<extension>}. The original artifact filename is preserved separately from
 * its staging path.
 */
public final class StabilizationBundleStreamReader {

  public static final int MAX_PARTS = 200;
  public static final long MAX_PART_BYTES = 64L * 1024L * 1024L;
  public static final long MAX_JSON_PART_BYTES = 5L * 1024L * 1024L;
  public static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
  /** Maximum number of header lines per part, in addition to the per-line length limit. */
  public static final int MAX_HEADERS_PER_PART = 32;

  private static final Set<String> REQUIRED_PARTS =
      Set.of("plan", "manifest", "matrix", "transitions", "evidence", "checksums", "completeMarker");
  private static final Set<String> JSON_PARTS =
      Set.of("plan", "manifest", "matrix", "transitions", "evidence", "checksums");
  /** Supported media types for optional recorder artifact parts. */
  private static final Set<String> ARTIFACT_MEDIA_TYPES = Set.of(
      "application/x-ndjson", "application/json", "text/csv", "text/plain",
      "application/octet-stream");
  private static final int HEADER_LINE_LIMIT = 16 * 1024;

  private final Path tempDir;
  private final Map<String, Path> stagedByPartName = new LinkedHashMap<>();
  private final List<StagedArtifact> artifactParts = new ArrayList<>();
  private final Set<String> seenUniqueNames = new LinkedHashSet<>();
  private int partCount;

  private StabilizationBundleStreamReader(Path tempDir) {
    this.tempDir = tempDir;
  }

  /** One staged artifact part: original checksum-addressed filename, its normalized media type and
   *  its staging path. The media type is the allowlisted base Content-Type carried by the uploaded
   *  part so evidence media-type claims can be compared against the actual upload. */
  public record StagedArtifact(String filename, String mediaType, Path path) {}

  /** Parsed bundle: staged part paths by part name, and the artifact parts. */
  public record StagedBundle(Map<String, Path> parts, List<StagedArtifact> artifacts) {
    public Set<String> names() {
      return parts.keySet();
    }

    public Path part(String name) {
      return parts.get(name);
    }
  }

  /**
   * Streams a multipart request body into a bounded temporary directory. {@code contentType} must
   * be {@code multipart/form-data; boundary=...}; the boundary is taken from it and validated.
   */
  public static StagedBundle read(InputStream input, String contentType, Path tempDir)
      throws IOException {
    if (input == null) {
      throw new IOException("missing multipart request body");
    }
    if (contentType == null
        || !"multipart/form-data".equals(normalizedBaseType(contentType))) {
      throw new IOException("report bundle must be multipart/form-data");
    }
    String boundary = extractBoundary(contentType);
    if (boundary == null || boundary.isBlank() || boundary.length() > 200
        || !boundary.matches("[A-Za-z0-9'()+_,./:=?\\- ]+")) {
      throw new IOException("malformed multipart boundary");
    }
    if (tempDir == null) {
      throw new IOException("bundle staging directory is required");
    }
    Files.createDirectories(tempDir);
    StabilizationBundleStreamReader reader = new StabilizationBundleStreamReader(tempDir);
    reader.readAll(input, boundary);
    return new StagedBundle(reader.stagedByPartName, reader.artifactParts);
  }

  private void readAll(InputStream rawInput, String boundary) throws IOException {
    // The whole request body is counted in raw bytes (headers, boundaries and bodies alike) so
    // MAX_TOTAL_BYTES bounds the request before it can be fully consumed in memory. The bound is
    // enforced while bytes are read, including chunked requests without Content-Length.
    try (CountingInputStream counting = new CountingInputStream(rawInput)) {
      java.io.PushbackInputStream input = new java.io.PushbackInputStream(counting, 256);
      String opening = readLine(input);
      if (opening == null || !opening.equals("--" + boundary)) {
        throw new IOException("malformed multipart: missing opening boundary");
      }
      while (true) {
        if (partCount >= MAX_PARTS) {
          throw new IOException("report bundle exceeds the maximum part count");
        }
        // A new part begins with its first header line (no blank line follows the opening
        // boundary; a blank line may follow an interior boundary).
        String firstHeader = readLine(input);
        if (firstHeader != null && firstHeader.isEmpty()) {
          firstHeader = readLine(input);
        }
        if (firstHeader == null) {
          throw new IOException("malformed multipart: truncated before closing boundary");
        }
        if (firstHeader.equals("--" + boundary + "--")) {
          break;
        }
        PartHeaders headers = parsePartHeaders(firstHeader, input);
        PartBodyResult body = readPartBody(headers, input, boundary, counting);
        if (body.closed()) {
          break;
        }
      }
    }
  }

  /** Parses part headers starting from the first header line already read. */
  private PartHeaders parsePartHeaders(String firstHeader, InputStream input) throws IOException {
    String name = null;
    String filename = null;
    String contentType = null;
    String current = firstHeader;
    int headerCount = 0;
    while (current != null && !current.isEmpty()) {
      headerCount++;
      if (headerCount > MAX_HEADERS_PER_PART) {
        throw new IOException("report bundle part exceeds the maximum header count");
      }
      String lower = current.toLowerCase();
      if (lower.startsWith("content-disposition:")) {
        name = headerParam(current, "name");
        filename = headerParam(current, "filename");
      } else if (lower.startsWith("content-type:")) {
        contentType = current.substring("content-type:".length()).trim();
      }
      current = readLine(input);
    }
    if (name == null || name.isBlank()) {
      throw new IOException("report bundle part is missing a name");
    }
    return new PartHeaders(name, filename, contentType);
  }

  private PartBodyResult readPartBody(PartHeaders headers, java.io.PushbackInputStream input,
      String boundary, CountingInputStream counting) throws IOException {
    String name = headers.name();
    boolean requiredPart = REQUIRED_PARTS.contains(name);
    boolean artifactPart = "artifacts".equals(name);
    if (!requiredPart && !artifactPart) {
      throw new IOException("unsupported report bundle part '" + name + "'");
    }
    if (requiredPart && stagedByPartName.containsKey(name)) {
      throw new IOException("duplicate report bundle part '" + name + "'");
    }
    // Media types are normalized to their base type so substring tricks and unsupported
    // parameters/encodings can never bypass the checks.
    String mediaType = normalizedBaseType(headers.contentType());
    if (requiredPart && JSON_PARTS.contains(name)
        && !"application/json".equals(mediaType)) {
      throw new IOException("report bundle part '" + name + "' must be application/json");
    }
    if ("completeMarker".equals(name) && !"text/plain".equals(mediaType)) {
      throw new IOException("report bundle completeMarker part must be text/plain");
    }
    if (artifactPart) {
      if (!isChecksumAddressedFilename(headers.filename())) {
        throw new IOException(
            "artifact part filename must be checksum-addressed sha256_<64>.<ext>: " + headers.filename());
      }
      if (!seenUniqueNames.add(headers.filename())) {
        throw new IOException("duplicate artifact filename '" + headers.filename() + "'");
      }
      if (!ARTIFACT_MEDIA_TYPES.contains(mediaType)) {
        throw new IOException("unsupported artifact media type '" + headers.contentType()
            + "'; allowed types are " + ARTIFACT_MEDIA_TYPES);
      }
    }
    long limit = artifactPart ? MAX_PART_BYTES
        : ("completeMarker".equals(name) || JSON_PARTS.contains(name) ? MAX_JSON_PART_BYTES
            : MAX_PART_BYTES);
    Path target = uniqueTarget(name, headers.filename());
    CopyOutcome outcome = copyBody(input, target, boundary, limit, counting);
    partCount++;
    stagedByPartName.put(name, target);
    if (artifactPart) {
      artifactParts.add(new StagedArtifact(headers.filename(), mediaType, target));
    }
    return new PartBodyResult(outcome.closed());
  }

  /**
   * Copies a part body to {@code target} until a REAL multipart delimiter. A candidate
   * {@code \r\n--boundary} is recognized as a delimiter only when it is followed by either
   * {@code \r\n} (interior boundary) or {@code --} (closing boundary). A body containing a prefix
   * such as {@code \r\n--boundary-not-a-delimiter} remains body data and does NOT terminate the
   * part. A rolling candidate buffer matches the delimiter across byte boundaries; anything not
   * part of a genuine delimiter is written to the target. The per-part and total-size bounds are
   * enforced while bytes are read, including chunked requests.
   */
  private CopyOutcome copyBody(java.io.PushbackInputStream input, Path target, String boundary,
      long limit, CountingInputStream counting) throws IOException {
    byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.UTF_8);
    int len = delimiter.length;
    int[] window = new int[len];
    int windowLen = 0;
    long count = 0;
    boolean closed = false;
    int value = -1;
    try (java.io.OutputStream out = Files.newOutputStream(target)) {
      while ((value = input.read()) != -1) {
        count++;
        if (count > limit) {
          throw new IOException("report bundle part exceeds its size limit");
        }
        if (counting != null && counting.count() > MAX_TOTAL_BYTES) {
          throw new IOException("report bundle total size exceeds the limit");
        }
        int pos = windowLen % len;
        if (windowLen >= len) {
          // Evict the oldest byte (it is part of the body) before it is overwritten.
          out.write(window[pos]);
        }
        window[pos] = value;
        windowLen++;
        if (windowLen >= len && matchesWindow(window, delimiter, (pos + 1) % len)) {
          // A candidate \r\n--boundary was found. It is a delimiter only when followed by \r\n
          // (interior) or -- (closing). Anything else (e.g. \r\n--boundaryX) is body data.
          int peek1 = input.read();
          int peek2 = input.read();
          if (peek1 == '-' && peek2 == '-') {
            closed = true;
            break;
          }
          if (peek1 == '\r' && peek2 == '\n') {
            // Interior delimiter: the part ends before the CRLF that precedes the next boundary.
            break;
          }
          // Not a delimiter: the candidate prefix belongs to the body. Write the full candidate
          // (it was never written) and continue scanning from the two peeked bytes.
          out.write(window, 0, len);
          count += len;
          if (count > limit) {
            throw new IOException("report bundle part exceeds its size limit");
          }
          if (peek1 != -1) {
            unread(input, peek1);
          }
          if (peek2 != -1) {
            unread(input, peek2);
          }
          windowLen = 0;
        }
      }
    }
    if (value == -1 && !closed) {
      throw new IOException("malformed multipart: part '" + target.getFileName()
          + "' ended without a boundary");
    }
    return new CopyOutcome(count, closed);
  }

  private static boolean matchesWindow(int[] window, byte[] delimiter, int rotation) {
    int len = delimiter.length;
    for (int i = 0; i < len; i++) {
      if (window[(rotation + i) % len] != (delimiter[i] & 0xFF)) {
        return false;
      }
    }
    return true;
  }

  private static void unread(java.io.PushbackInputStream input, int... bytes) throws IOException {
    byte[] buffer = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      buffer[i] = (byte) bytes[i];
    }
    input.unread(buffer);
  }

  private Path uniqueTarget(String partName, String originalFilename) {
    String safe = sanitize(originalFilename == null ? partName : originalFilename);
    String base = partName + "-" + safe;
    if (!seenUniqueNames.add(base)) {
      base = base + "-" + partCount;
    }
    return tempDir.resolve(base);
  }

  /** Preserves only safe characters; rejects traversal and separators/control characters. */
  private static String sanitize(String value) {
    if (value == null) {
      return "part";
    }
    if (value.contains("/") || value.contains("\\") || value.contains("..")
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("unsafe report bundle part filename: " + value);
    }
    String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
    return sanitized.isEmpty() ? "part" : sanitized;
  }

  private static boolean isChecksumAddressedFilename(String filename) {
    if (filename == null) {
      return false;
    }
    int dot = filename.indexOf('.');
    String base = dot >= 0 ? filename.substring(0, dot) : filename;
    if (!base.startsWith("sha256_") || base.length() != "sha256_".length() + 64) {
      return false;
    }
    if (!base.substring("sha256_".length()).matches("[0-9a-f]{64}")) {
      return false;
    }
    // A valid, known extension is required: a checksum-addressed artifact with no extension (or
    // with an empty/overlong extension) is rejected so media-type inference can never be abused.
    if (dot < 0) {
      return false;
    }
    String extension = filename.substring(dot + 1);
    return extension.length() >= 1 && extension.length() <= 16
        && extension.matches("[A-Za-z0-9]+");
  }

  private static String extractBoundary(String contentType) {
    int idx = contentType.toLowerCase().indexOf("boundary=");
    if (idx < 0) {
      return null;
    }
    String boundary = contentType.substring(idx + "boundary=".length()).trim();
    if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) {
      boundary = boundary.substring(1, boundary.length() - 1);
    }
    int semicolon = boundary.indexOf(';');
    if (semicolon >= 0) {
      boundary = boundary.substring(0, semicolon).trim();
    }
    return boundary;
  }

  /**
   * Normalizes a Content-Type value to its lowercased base type (the media type before any {@code ;}
   * parameter). Substring tricks such as {@code application/jsonX} or
   * {@code multipart/form-data-evil} can never match the exact base type.
   */
  private static String normalizedBaseType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return base.trim().toLowerCase(java.util.Locale.ENGLISH);
  }

  /** Reads a CRLF-terminated line, bounded to {@link #HEADER_LINE_LIMIT} bytes. */
  private static String readLine(InputStream input) throws IOException {
    StringBuilder line = new StringBuilder();
    boolean sawCr = false;
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\r') {
        sawCr = true;
        continue;
      }
      if (value == '\n') {
        if (sawCr) {
          return line.toString();
        }
        line.append('\n');
        sawCr = false;
        continue;
      }
      sawCr = false;
      line.append((char) value);
      if (line.length() > HEADER_LINE_LIMIT) {
        throw new IOException("multipart header line exceeds the limit");
      }
    }
    return line.length() == 0 ? null : line.toString();
  }

  /**
   * Extracts a Content-Disposition parameter by parsing the disposition tokens one at a time with
   * exact, case-insensitive parameter-name comparison and proper quoted-string (and escaped
   * quoted-character) handling. It never uses substring lookup, so a {@code name=} can never match
   * inside {@code filename=}, and a parameter name like {@code name} only matches a standalone
   * {@code name=} token.
   */
  private static String headerParam(String header, String param) {
    if (header == null || param == null) {
      return null;
    }
    String needle = param.toLowerCase(java.util.Locale.ENGLISH);
    int pos = 0;
    while (pos < header.length()) {
      pos = skipSpacesAndSemicolons(header, pos);
      int nameStart = pos;
      while (pos < header.length() && header.charAt(pos) != '=' && header.charAt(pos) != ';') {
        pos++;
      }
      String name = header.substring(nameStart, pos).trim();
      if (name.isEmpty() || pos >= header.length() || header.charAt(pos) != '=') {
        // Not a parameter assignment; continue past the token (if any).
        if (pos < header.length()) {
          pos++;
        }
        continue;
      }
      pos++; // consume '='
      pos = skipSpacesAndSemicolons(header, pos);
      String value;
      if (pos < header.length() && header.charAt(pos) == '"') {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < header.length()) {
          char c = header.charAt(pos);
          if (c == '\\') {
            if (pos + 1 < header.length()) {
              sb.append(header.charAt(pos + 1));
              pos += 2;
              continue;
            }
            pos++;
            continue;
          }
          if (c == '"') {
            pos++; // consume closing quote
            break;
          }
          sb.append(c);
          pos++;
        }
        value = sb.toString();
      } else {
        int valueStart = pos;
        while (pos < header.length() && header.charAt(pos) != ';' && header.charAt(pos) != '\r') {
          pos++;
        }
        value = header.substring(valueStart, pos).trim();
      }
      if (name.toLowerCase(java.util.Locale.ENGLISH).equals(needle)) {
        return value;
      }
    }
    return null;
  }

  private static int skipSpacesAndSemicolons(String header, int pos) {
    while (pos < header.length()
        && (header.charAt(pos) == ' ' || header.charAt(pos) == '\t' || header.charAt(pos) == ';')) {
      pos++;
    }
    return pos;
  }

  private record PartHeaders(String name, String filename, String contentType) {}

  private record PartBodyResult(boolean closed) {}

  private record CopyOutcome(long bytes, boolean closed) {}

  /**
   * Counts every raw byte consumed from the request body, including headers and boundaries, and
   * enforces {@link #MAX_TOTAL_BYTES} inside {@code read()} so a request cannot send arbitrarily
   * many bounded header lines before body copying begins to bypass the whole-request limit. The
   * limit applies to every byte the request body supplies.
   */
  private static final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private long count;

    CountingInputStream(InputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      int value = delegate.read();
      if (value != -1) {
        count++;
        if (count > MAX_TOTAL_BYTES) {
          throw new IOException("report bundle total size exceeds the limit");
        }
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = delegate.read(bytes, offset, length);
      if (read > 0) {
        count += read;
        if (count > MAX_TOTAL_BYTES) {
          throw new IOException("report bundle total size exceeds the limit");
        }
      }
      return read;
    }

    long count() {
      return count;
    }
  }
}
