package com.chromascape.web.debug.tickdebugger.video;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Route-scoped bounded multipart reader for the SC-2150 video upload.
 *
 * <p>Global eager multipart parsing is intentionally disabled so authentication and limits run
 * before the servlet input stream is consumed. This reader accepts exactly one {@code file} part
 * and one {@code metadata} JSON part, enforces request/file/metadata/header/part limits while bytes
 * are read (including chunked bodies), hashes the media as it streams, and writes to
 * server-generated staging paths only. An uploaded filename never becomes a filesystem path.
 */
public final class MouseVideoMultipartReader {

  public static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
  public static final long MAX_REQUEST_BYTES = 520L * 1024L * 1024L;
  public static final long MAX_METADATA_BYTES = 16L * 1024L;
  public static final int MAX_PARTS = 2;
  public static final int MAX_HEADERS_PER_PART = 16;

  private static final int HEADER_LINE_LIMIT = 8 * 1024;
  private static final Set<String> ALLOWED_PARTS = Set.of("file", "metadata");

  private MouseVideoMultipartReader() {}

  /** A request that is not valid multipart input and must map to a typed 400 response. */
  public static final class MalformedMultipartException extends MultipartException {
    public MalformedMultipartException(String message) {
      super(message);
    }

    public MalformedMultipartException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A request that exceeded a configured multipart limit and must map to 413. */
  public static final class MultipartLimitExceededException extends MultipartException {
    public MultipartLimitExceededException(String message) {
      super(message);
    }
  }

  /**
   * Base type for caller-correctable multipart failures. Genuine storage and filesystem failures
   * remain ordinary {@link IOException}s so they are never misreported as client errors.
   */
  public abstract static class MultipartException extends IOException {
    private MultipartException(String message) {
      super(message);
    }

    private MultipartException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Streamed upload plus its computed content address and parsed metadata. */
  public record StagedUpload(
      long sizeBytes,
      String checksum,
      String declaredMediaType,
      MouseVideoImportMetadata metadata,
      Path mediaPath) {}

  /**
   * Reads one bounded upload into {@code stagingDir}. The caller owns the directory and must remove
   * it after adopting or rejecting the media.
   */
  public static StagedUpload read(
      InputStream rawInput,
      String contentType,
      Path stagingDir,
      ObjectMapper mapper,
      long maxFileBytes,
      long maxRequestBytes)
      throws IOException {
    if (rawInput == null) {
      throw new MalformedMultipartException("missing multipart request body");
    }
    String boundary = extractBoundary(contentType);
    if (boundary == null
        || boundary.isBlank()
        || boundary.length() > 200
        || !boundary.matches("[A-Za-z0-9'()+_,./:=?\\- ]+")) {
      throw new MalformedMultipartException("malformed multipart boundary");
    }
    if (stagingDir == null) {
      throw new IOException("upload staging directory is required");
    }
    if (Files.isSymbolicLink(stagingDir)) {
      throw new IOException("upload staging directory must not be a symbolic link");
    }
    Files.createDirectories(stagingDir);
    if (Files.isSymbolicLink(stagingDir)
        || !Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("upload staging directory must be a real directory");
    }
    Reader reader =
        new Reader(
            stagingDir.toAbsolutePath().normalize(),
            mapper,
            Math.max(1L, Math.min(MAX_FILE_BYTES, maxFileBytes)),
            Math.max(1L, maxRequestBytes));
    reader.readAll(rawInput, boundary);
    return reader.result();
  }

  private static final class Reader {
    private final Path stagingDir;
    private final ObjectMapper mapper;
    private final long maxFileBytes;
    private final long maxRequestBytes;
    private final Set<String> seen = new LinkedHashSet<>();
    private Path mediaPath;
    private long mediaSize;
    private String mediaChecksum;
    private String declaredMediaType;
    private MouseVideoImportMetadata metadata;
    private int partCount;

    private Reader(Path stagingDir, ObjectMapper mapper, long maxFileBytes, long maxRequestBytes) {
      this.stagingDir = stagingDir;
      this.mapper = mapper;
      this.maxFileBytes = maxFileBytes;
      this.maxRequestBytes = maxRequestBytes;
    }

    private StagedUpload result() throws IOException {
      if (mediaPath == null) {
        throw new MalformedMultipartException("multipart upload is missing the file part");
      }
      if (metadata == null) {
        throw new MalformedMultipartException("multipart upload is missing the metadata part");
      }
      return new StagedUpload(mediaSize, mediaChecksum, declaredMediaType, metadata, mediaPath);
    }

    private void readAll(InputStream rawInput, String boundary) throws IOException {
      try (CountingInputStream counting = new CountingInputStream(rawInput, maxRequestBytes)) {
        PushbackInputStream input = new PushbackInputStream(counting, 256);
        String opening = readLine(input);
        if (opening == null || !opening.equals("--" + boundary)) {
          throw new MalformedMultipartException("malformed multipart: missing opening boundary");
        }
        while (true) {
          String firstHeader = readLine(input);
          if (firstHeader != null && firstHeader.isEmpty()) {
            firstHeader = readLine(input);
          }
          if (firstHeader == null) {
            throw new MalformedMultipartException(
                "malformed multipart: truncated before closing boundary");
          }
          if (firstHeader.equals("--" + boundary + "--")) {
            requireClosingTermination(input);
            break;
          }
          if (partCount >= MAX_PARTS) {
            throw new MultipartLimitExceededException("upload exceeds the maximum part count");
          }
          PartHeaders headers = parsePartHeaders(firstHeader, input);
          boolean closed = readPartBody(headers, input, boundary, counting);
          partCount++;
          if (closed) {
            break;
          }
        }
      }
    }

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
          throw new MultipartLimitExceededException("upload part exceeds the maximum header count");
        }
        String lower = current.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("content-disposition:")) {
          if (dispositionSeen) {
            throw new MalformedMultipartException(
                "upload part contains duplicate Content-Disposition headers");
          }
          dispositionSeen = true;
          Map<String, String> parameters = parseContentDisposition(current);
          name = parameters.get("name");
          filename = parameters.get("filename");
        } else if (lower.startsWith("content-type:")) {
          if (contentTypeSeen) {
            throw new MalformedMultipartException(
                "upload part contains duplicate Content-Type headers");
          }
          contentTypeSeen = true;
          contentType = current.substring("content-type:".length()).trim();
        }
        current = readLine(input);
      }
      if (name == null || name.isBlank()) {
        throw new MalformedMultipartException("upload part is missing a name");
      }
      return new PartHeaders(name, filename, contentType);
    }

    private boolean readPartBody(
        PartHeaders headers,
        PushbackInputStream input,
        String boundary,
        CountingInputStream counting)
        throws IOException {
      String name = headers.name();
      if (!ALLOWED_PARTS.contains(name)) {
        throw new MalformedMultipartException("unsupported upload part '" + name + "'");
      }
      if (!seen.add(name)) {
        throw new MalformedMultipartException("duplicate upload part '" + name + "'");
      }
      if ("file".equals(name)) {
        if (headers.filename() == null || headers.filename().isBlank()) {
          throw new MalformedMultipartException("upload file part must carry a filename");
        }
        if (headers.filename().length() > 255) {
          throw new MalformedMultipartException("upload filename is too long");
        }
        declaredMediaType = normalizedBaseType(headers.contentType());
        Path target = stagingDir.resolve("upload-media");
        CopyOutcome outcome = copyBody(input, target, boundary, maxFileBytes, counting, true);
        mediaSize = outcome.bytes();
        mediaChecksum = outcome.checksum();
        mediaPath = target;
        return outcome.closed();
      }
      if (headers.contentType() == null
          || !"application/json".equals(normalizedBaseType(headers.contentType()))) {
        throw new MalformedMultipartException("upload metadata part must be application/json");
      }
      Path target = stagingDir.resolve("upload-metadata.json");
      CopyOutcome outcome = copyBody(input, target, boundary, MAX_METADATA_BYTES, counting, false);
      metadata = parseMetadata(target);
      return outcome.closed();
    }

    private MouseVideoImportMetadata parseMetadata(Path path) throws IOException {
      return MouseVideoMultipartReader.parseMetadata(path, mapper);
    }
  }

  /**
   * Reads and validates the metadata staging file. The filesystem read stays outside the JSON
   * validation catch so storage failures remain ordinary I/O failures rather than client errors.
   */
  static MouseVideoImportMetadata parseMetadata(Path path, ObjectMapper mapper) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    JsonNode document;
    try {
      document = mapper.readTree(bytes);
    } catch (JsonProcessingException invalid) {
      throw new MalformedMultipartException("upload metadata is not valid JSON", invalid);
    }
    if (document == null || !document.isObject()) {
      throw new MalformedMultipartException("upload metadata must be a JSON object");
    }
    Set<String> allowed =
        Set.of("requestId", "declaredOrigin", "taskKey", "skill", "context", "place");
    var fields = document.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowed.contains(field)) {
        throw new MalformedMultipartException(
            "upload metadata contains an unsupported field: " + field);
      }
    }
    return new MouseVideoImportMetadata(
        text(document, "requestId"),
        text(document, "declaredOrigin"),
        text(document, "taskKey"),
        text(document, "skill"),
        text(document, "context"),
        text(document, "place"));
  }

  private static String text(JsonNode document, String field) throws IOException {
    JsonNode value = document.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new MalformedMultipartException(
          "upload metadata field '" + field + "' must be a string");
    }
    return value.asText();
  }

  private record PartHeaders(String name, String filename, String contentType) {}

  private record CopyOutcome(long bytes, String checksum, boolean closed) {}

  private static CopyOutcome copyBody(
      PushbackInputStream input,
      Path target,
      String boundary,
      long limit,
      CountingInputStream counting,
      boolean digest)
      throws IOException {
    byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.UTF_8);
    int len = delimiter.length;
    int[] window = new int[len];
    int windowLen = 0;
    long count = 0L;
    boolean closed = false;
    int value = -1;
    boolean created = false;
    MessageDigest messageDigest = digest ? newDigest() : null;
    try (OutputStream raw =
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        OutputStream out = digest ? new DigestOutputStream(raw, messageDigest) : raw) {
      created = true;
      while ((value = input.read()) != -1) {
        int pos = windowLen % len;
        if (windowLen >= len) {
          if (count >= limit) {
            throw new MultipartLimitExceededException("upload part exceeds its size limit");
          }
          out.write(window[pos]);
          count++;
        }
        window[pos] = value;
        windowLen++;
        if (windowLen >= len && matchesWindow(window, delimiter, (pos + 1) % len)) {
          int peek1 = input.read();
          int peek2 = input.read();
          if (peek1 == '-' && peek2 == '-') {
            closed = true;
            int after = input.read();
            if (after == -1) {
              break;
            }
            if (after == '\r') {
              if (input.read() != '\n') {
                throw new MalformedMultipartException(
                    "malformed multipart: closing boundary must be followed by CRLF or EOF");
              }
              if (input.read() != -1) {
                throw new MalformedMultipartException(
                    "malformed multipart: unexpected trailing data after closing boundary");
              }
              break;
            }
            throw new MalformedMultipartException(
                "malformed multipart: closing boundary must be followed by CRLF or EOF");
          }
          if (peek1 == '\r' && peek2 == '\n') {
            break;
          }
          int rotation = (pos + 1) % len;
          for (int i = 0; i < len; i++) {
            if (count >= limit) {
              throw new MultipartLimitExceededException("upload part exceeds its size limit");
            }
            out.write(window[(rotation + i) % len]);
            count++;
          }
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
    } catch (IOException | RuntimeException failure) {
      if (created) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
    if (value == -1 && !closed) {
      throw new MalformedMultipartException("malformed multipart: part ended without a boundary");
    }
    String checksum =
        messageDigest == null ? null : "sha256:" + HexFormat.of().formatHex(messageDigest.digest());
    return new CopyOutcome(count, checksum, closed);
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

  private static void unread(PushbackInputStream input, int... bytes) throws IOException {
    byte[] buffer = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      buffer[i] = (byte) bytes[i];
    }
    input.unread(buffer);
  }

  private static void requireClosingTermination(InputStream input) throws IOException {
    if (input.read() != -1) {
      throw new MalformedMultipartException(
          "malformed multipart: unexpected trailing data after closing boundary");
    }
  }

  private static String extractBoundary(String contentType) {
    if (contentType == null || !"multipart/form-data".equals(normalizedBaseType(contentType))) {
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
        while (pos < parameters.length()
            && parameters.charAt(pos) != ';'
            && !isHorizontalWhitespace(parameters.charAt(pos))) {
          if (!isTokenCharacter(parameters.charAt(pos))) {
            return null;
          }
          pos++;
        }
        value = parameters.substring(valueStart, pos);
      }
      if (value.isBlank() || ("boundary".equals(name) && boundary != null)) {
        return null;
      }
      if ("boundary".equals(name)) {
        boundary = value;
      }
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

  private static Map<String, String> parseContentDisposition(String header) throws IOException {
    int colon = header.indexOf(':');
    if (colon < 0) {
      throw new MalformedMultipartException("malformed Content-Disposition header");
    }
    String value = header.substring(colon + 1).trim();
    int pos = 0;
    int tokenStart = pos;
    while (pos < value.length() && isTokenCharacter(value.charAt(pos))) {
      pos++;
    }
    if (tokenStart == pos || !"form-data".equalsIgnoreCase(value.substring(tokenStart, pos))) {
      throw new MalformedMultipartException("Content-Disposition must be form-data");
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
        throw new MalformedMultipartException("malformed Content-Disposition parameter list");
      }
      pos++;
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      if (pos == value.length()) {
        throw new MalformedMultipartException("malformed Content-Disposition parameter list");
      }
      int nameStart = pos;
      while (pos < value.length() && isTokenCharacter(value.charAt(pos))) {
        pos++;
      }
      if (nameStart == pos) {
        throw new MalformedMultipartException("malformed Content-Disposition parameter name");
      }
      String name = value.substring(nameStart, pos).toLowerCase(Locale.ENGLISH);
      while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
        pos++;
      }
      if (pos == value.length() || value.charAt(pos) != '=') {
        throw new MalformedMultipartException(
            "malformed Content-Disposition parameter '" + name + "'");
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
              throw new MalformedMultipartException(
                  "malformed quoted Content-Disposition parameter");
            }
            char escaped = value.charAt(pos++);
            if (escaped == '\r' || escaped == '\n' || escaped < 0x20) {
              throw new MalformedMultipartException(
                  "malformed quoted Content-Disposition parameter");
            }
            decoded.append(escaped);
          } else if (c == '"') {
            closed = true;
            break;
          } else {
            if (c == '\r' || c == '\n' || c < 0x20) {
              throw new MalformedMultipartException(
                  "malformed quoted Content-Disposition parameter");
            }
            decoded.append(c);
          }
        }
        if (!closed) {
          throw new MalformedMultipartException("malformed quoted Content-Disposition parameter");
        }
        parameterValue = decoded.toString();
      } else {
        int valueStart = pos;
        while (pos < value.length()
            && value.charAt(pos) != ';'
            && !isHorizontalWhitespace(value.charAt(pos))) {
          if (!isTokenCharacter(value.charAt(pos))) {
            throw new MalformedMultipartException(
                "malformed unquoted Content-Disposition parameter");
          }
          pos++;
        }
        if (valueStart == pos) {
          throw new MalformedMultipartException("empty Content-Disposition parameter value");
        }
        int valueEnd = pos;
        while (pos < value.length() && isHorizontalWhitespace(value.charAt(pos))) {
          pos++;
        }
        if (pos < value.length() && value.charAt(pos) != ';') {
          throw new MalformedMultipartException("malformed unquoted Content-Disposition parameter");
        }
        parameterValue = value.substring(valueStart, valueEnd);
      }
      if (parameters.putIfAbsent(name, parameterValue) != null) {
        throw new MalformedMultipartException(
            "duplicate Content-Disposition parameter '" + name + "'");
      }
    }
  }

  private static String readLine(InputStream input) throws IOException {
    StringBuilder line = new StringBuilder();
    boolean sawCr = false;
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\r') {
        if (sawCr) {
          throw new MalformedMultipartException("malformed multipart line ending");
        }
        sawCr = true;
        continue;
      }
      if (value == '\n') {
        if (sawCr) {
          return line.toString();
        }
        throw new MalformedMultipartException(
            "multipart headers and boundaries require CRLF line endings");
      }
      if (sawCr) {
        throw new MalformedMultipartException(
            "multipart headers and boundaries require CRLF line endings");
      }
      line.append((char) value);
      if (line.length() > HEADER_LINE_LIMIT) {
        throw new MultipartLimitExceededException("multipart header line exceeds the limit");
      }
    }
    if (sawCr) {
      throw new MalformedMultipartException("multipart line ends with a bare CR");
    }
    return line.length() == 0 ? null : line.toString();
  }

  private static String normalizedBaseType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return base.trim().toLowerCase(Locale.ENGLISH);
  }

  private static boolean isHorizontalWhitespace(char value) {
    return value == ' ' || value == '\t';
  }

  private static boolean isTokenCharacter(char value) {
    return (value >= 'A' && value <= 'Z')
        || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is always available", impossible);
    }
  }

  static final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private final long maxBytes;
    private long count;
    private final java.util.function.LongSupplier clock;
    private final long started;

    private CountingInputStream(InputStream delegate, long maxBytes) {
      this(delegate, maxBytes, System::nanoTime);
    }

    CountingInputStream(
        InputStream delegate, long maxBytes, java.util.function.LongSupplier clock) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
      this.clock = clock;
      this.started = clock.getAsLong();
    }

    private void checkDeadline() throws MultipartLimitExceededException {
      // Ten minutes of streaming, comfortably below the one-hour reservation TTL. A blocked
      // socket still requires the server's finite connection/read timeout.
      if (clock.getAsLong() - started >= java.util.concurrent.TimeUnit.MINUTES.toNanos(10)) {
        throw new MultipartLimitExceededException("upload exceeded the intake time limit");
      }
    }

    @Override
    public int read() throws IOException {
      checkDeadline();
      int value = delegate.read();
      checkDeadline();
      if (value != -1) {
        count++;
        if (count > maxBytes) {
          throw new MultipartLimitExceededException("upload request exceeds the total size limit");
        }
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      checkDeadline();
      int read = delegate.read(bytes, offset, length);
      checkDeadline();
      if (read > 0) {
        count += read;
        if (count > maxBytes) {
          throw new MultipartLimitExceededException("upload request exceeds the total size limit");
        }
      }
      return read;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
