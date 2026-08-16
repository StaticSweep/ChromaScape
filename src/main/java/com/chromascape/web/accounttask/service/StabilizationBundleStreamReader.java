package com.chromascape.web.accounttask.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    if (Files.isSymbolicLink(tempDir)) {
      throw new IOException("bundle staging directory must not be a symbolic link");
    }
    Files.createDirectories(tempDir);
    if (Files.isSymbolicLink(tempDir)
        || !Files.isDirectory(tempDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("bundle staging directory must be a real directory");
    }
    StabilizationBundleStreamReader reader =
        new StabilizationBundleStreamReader(tempDir.toAbsolutePath().normalize());
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
          // The direct closing-boundary path must enforce the SAME termination rules as the
          // body-copy path: the closing CRLF (or EOF) has already been consumed by readLine, so any
          // remaining byte is trailing data and is rejected (SC-572).
          requireClosingTermination(input);
          break;
        }
        // Read the closing delimiter before enforcing the part count so exactly MAX_PARTS
        // ordinary parts remain valid; the next non-closing part is the one that exceeds the cap.
        if (partCount >= MAX_PARTS) {
          throw new IOException("report bundle exceeds the maximum part count");
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
    boolean dispositionSeen = false;
    boolean contentTypeSeen = false;
    while (current != null && !current.isEmpty()) {
      headerCount++;
      if (headerCount > MAX_HEADERS_PER_PART) {
        throw new IOException("report bundle part exceeds the maximum header count");
      }
      String lower = current.toLowerCase(Locale.ENGLISH);
      if (lower.startsWith("content-disposition:")) {
        if (dispositionSeen) {
          throw new IOException("report bundle part contains duplicate Content-Disposition headers");
        }
        dispositionSeen = true;
        Map<String, String> parameters = parseContentDisposition(current);
        name = parameters.get("name");
        filename = parameters.get("filename");
      } else if (lower.startsWith("content-type:")) {
        if (contentTypeSeen) {
          throw new IOException("report bundle part contains duplicate Content-Type headers");
        }
        contentTypeSeen = true;
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
    boolean created = false;
    try (java.io.OutputStream out = Files.newOutputStream(target,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      created = true;
      while ((value = input.read()) != -1) {
        if (counting != null && counting.count() > MAX_TOTAL_BYTES) {
          throw new IOException("report bundle total size exceeds the limit");
        }
        int pos = windowLen % len;
        if (windowLen >= len) {
          // Evict the oldest byte (it is part of the body) before it is overwritten.
          if (count >= limit) {
            throw new IOException("report bundle part exceeds its size limit");
          }
          out.write(window[pos]);
          count++;
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
            // A closing boundary is valid only as CRLF "--" boundary "--" followed by CRLF or
            // EOF. Any other byte (e.g. --boundary--garbage) is a malformed closing form, and any
            // trailing data after the closing CRLF is rejected so the reader's total-byte
            // accounting covers the entire request body (SC-572).
            int after = input.read();
            if (after == -1) {
              break; // closing delimiter at EOF is valid
            }
            if (after == '\r') {
              int lf = input.read();
              if (lf != '\n') {
                throw new IOException(
                    "malformed multipart: closing boundary must be followed by CRLF or EOF");
              }
              if (input.read() != -1) {
                throw new IOException(
                    "malformed multipart: unexpected trailing data after closing boundary");
              }
              break;
            }
            throw new IOException(
                "malformed multipart: closing boundary must be followed by CRLF or EOF");
          }
          if (peek1 == '\r' && peek2 == '\n') {
            // Interior delimiter: the part ends before the CRLF that precedes the next boundary.
            break;
          }
          // Not a delimiter: the candidate prefix belongs to the body. The window bytes were
          // already counted as read but never written; write them now in their ORIGINAL byte order
          // (the circular window is rotated, so the backing array must not be written from index
          // zero) and continue scanning from the two peeked bytes (also part of the body).
          int rotation = (pos + 1) % len;
          for (int i = 0; i < len; i++) {
            if (count >= limit) {
              throw new IOException("report bundle part exceeds its size limit");
            }
            out.write(window[(rotation + i) % len]);
            count++;
          }
          // Restore the two peeked bytes in their ORIGINAL order: a single unread(byte[]) with
          // [peek1, peek2] pushes peek1 back first so the next read returns peek1 then peek2. Pushing
          // them back with two separate calls (peek1 then peek2) would reverse the order and corrupt
          // the body (e.g. -not- becoming n-ot-), so a single call is required (SC-572).
          if (peek1 != -1) {
            if (peek2 != -1) {
              unread(input, peek1, peek2);
            } else {
              unread(input, peek1);
            }
          }
          windowLen = 0;
        }
      }
    } catch (IOException | RuntimeException e) {
      // CREATE_NEW prevents overwriting an attacker-controlled pre-existing target. Only remove
      // the path after the stream was successfully opened, so an open failure cannot delete
      // someone else's file or symlink.
      if (created) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanup) {
          e.addSuppressed(cleanup);
        }
      }
      throw e;
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

  /**
   * Requires the stream to end immediately after a closing boundary. The closing CRLF (or EOF) has
   * already been consumed by {@link #readLine}; any remaining byte is trailing data after a valid
   * closing boundary and is rejected, matching the body-copy path's EOF/CRLF/trailing-data
   * validation (SC-572).
   */
  private static void requireClosingTermination(InputStream input) throws IOException {
    if (input.read() != -1) {
      throw new IOException("malformed multipart: unexpected trailing data after closing boundary");
    }
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
    if (contentType == null) {
      return null;
    }
    int semicolon = contentType.indexOf(';');
    if (semicolon < 0) {
      return null;
    }
    String parameters = contentType.substring(semicolon + 1);
    int pos = 0;
    String boundary = null;
    while (pos < parameters.length()) {
      while (pos < parameters.length() && isHorizontalWhitespace(parameters.charAt(pos))) {
        pos++;
      }
      if (pos >= parameters.length()) {
        break;
      }
      int nameStart = pos;
      while (pos < parameters.length() && isTokenCharacter(parameters.charAt(pos))) {
        pos++;
      }
      if (nameStart == pos) {
        return null;
      }
      String name = parameters.substring(nameStart, pos).toLowerCase(Locale.ENGLISH);
      while (pos < parameters.length() && isHorizontalWhitespace(parameters.charAt(pos))) {
        pos++;
      }
      if (pos >= parameters.length() || parameters.charAt(pos) != '=') {
        return null;
      }
      pos++;
      while (pos < parameters.length() && isHorizontalWhitespace(parameters.charAt(pos))) {
        pos++;
      }
      String value;
      if (pos < parameters.length() && parameters.charAt(pos) == '"') {
        pos++;
        StringBuilder decoded = new StringBuilder();
        boolean closed = false;
        while (pos < parameters.length()) {
          char current = parameters.charAt(pos++);
          if (current == '\\') {
            if (pos >= parameters.length()) {
              return null;
            }
            char escaped = parameters.charAt(pos++);
            if (escaped == '\r' || escaped == '\n' || escaped < 0x20) {
              return null;
            }
            decoded.append(escaped);
          } else if (current == '"') {
            closed = true;
            break;
          } else {
            if (current == '\r' || current == '\n' || current < 0x20) {
              return null;
            }
            decoded.append(current);
          }
        }
        if (!closed) {
          return null;
        }
        value = decoded.toString();
      } else {
        int valueStart = pos;
        while (pos < parameters.length() && parameters.charAt(pos) != ';'
            && !isHorizontalWhitespace(parameters.charAt(pos))) {
          if (!isTokenCharacter(parameters.charAt(pos))) {
            return null;
          }
          pos++;
        }
        value = parameters.substring(valueStart, pos);
      }
      if (value.isBlank() || "boundary".equals(name) && boundary != null) {
        return null;
      }
      if ("boundary".equals(name)) {
        boundary = value;
      }
      // Optional horizontal whitespace is valid between an unquoted token value and the next
      // semicolon. Rejecting it would make otherwise-valid Content-Type headers fail.
      while (pos < parameters.length() && isHorizontalWhitespace(parameters.charAt(pos))) {
        pos++;
      }
      if (pos < parameters.length()) {
        if (parameters.charAt(pos) != ';') {
          return null;
        }
        pos++;
      }
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
        if (sawCr) {
          throw new IOException("malformed multipart line ending");
        }
        sawCr = true;
        continue;
      }
      if (value == '\n') {
        if (sawCr) {
          return line.toString();
        }
        throw new IOException("multipart headers and boundaries require CRLF line endings");
      }
      if (sawCr) {
        throw new IOException("multipart headers and boundaries require CRLF line endings");
      }
      line.append((char) value);
      if (line.length() > HEADER_LINE_LIMIT) {
        throw new IOException("multipart header line exceeds the limit");
      }
    }
    if (sawCr) {
      throw new IOException("multipart line ends with a bare CR");
    }
    return line.length() == 0 ? null : line.toString();
  }

  /**
   * Parses the complete Content-Disposition field, rejecting malformed quoted strings and
   * malformed/duplicate parameters. Parsing the complete token sequence is important: looking up
   * only {@code name=} would allow an unterminated or smuggled parameter after it to be ignored,
   * and substring matching could confuse {@code name=} with {@code filename=}.
   */
  private static Map<String, String> parseContentDisposition(String header) throws IOException {
    int colon = header.indexOf(':');
    if (colon < 0) {
      throw new IOException("malformed Content-Disposition header");
    }
    String value = header.substring(colon + 1).trim();
    int pos = 0;
    int tokenStart = pos;
    while (pos < value.length() && isTokenCharacter(value.charAt(pos))) {
      pos++;
    }
    if (tokenStart == pos || !"form-data".equalsIgnoreCase(value.substring(tokenStart, pos))) {
      throw new IOException("Content-Disposition must be form-data");
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    while (true) {
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      if (pos == value.length()) {
        return parameters;
      }
      if (value.charAt(pos) != ';') {
        throw new IOException("malformed Content-Disposition parameter list");
      }
      pos++;
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      if (pos == value.length()) {
        throw new IOException("malformed Content-Disposition parameter list");
      }
      int nameStart = pos;
      while (pos < value.length() && isTokenCharacter(value.charAt(pos))) {
        pos++;
      }
      if (nameStart == pos) {
        throw new IOException("malformed Content-Disposition parameter name");
      }
      String name = value.substring(nameStart, pos).toLowerCase(Locale.ENGLISH);
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      if (pos == value.length() || value.charAt(pos) != '=') {
        throw new IOException("malformed Content-Disposition parameter '" + name + "'");
      }
      pos++;
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      String parameterValue;
      if (pos < value.length() && value.charAt(pos) == '"') {
        pos++;
        StringBuilder decoded = new StringBuilder();
        boolean closed = false;
        while (pos < value.length()) {
          char c = value.charAt(pos++);
          if (c == '\\') {
            if (pos >= value.length()) {
              throw new IOException("malformed quoted Content-Disposition parameter");
            }
            char escaped = value.charAt(pos++);
            if (escaped == '\r' || escaped == '\n' || escaped < 0x20) {
              throw new IOException("malformed quoted Content-Disposition parameter");
            }
            decoded.append(escaped);
          } else if (c == '"') {
            closed = true;
            break;
          } else {
            if (c == '\r' || c == '\n' || c < 0x20) {
              throw new IOException("malformed quoted Content-Disposition parameter");
            }
            decoded.append(c);
          }
        }
        if (!closed) {
          throw new IOException("malformed quoted Content-Disposition parameter");
        }
        parameterValue = decoded.toString();
      } else {
        int valueStart = pos;
        while (pos < value.length() && value.charAt(pos) != ';'
            && !isHorizontalWhitespace(value.charAt(pos))) {
          if (!isTokenCharacter(value.charAt(pos))) {
            throw new IOException("malformed unquoted Content-Disposition parameter");
          }
          pos++;
        }
        if (valueStart == pos) {
          throw new IOException("empty Content-Disposition parameter value");
        }
        int valueEnd = pos;
        while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
          pos++;
        }
        if (pos < value.length() && value.charAt(pos) != ';') {
          throw new IOException("malformed unquoted Content-Disposition parameter");
        }
        parameterValue = value.substring(valueStart, valueEnd);
      }
      if (parameters.putIfAbsent(name, parameterValue) != null) {
        throw new IOException("duplicate Content-Disposition parameter '" + name + "'");
      }
    }
  }

  private static boolean isHorizontalWhitespace(char value) {
    return value == ' ' || value == '\t';
  }

  private static boolean isTokenCharacter(char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9') || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
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

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
