package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.source.SourceManifestCanonicalJson;
import com.chromascape.web.aiplayer.routine.source.SourceManifestContract;
import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import com.chromascape.web.aiplayer.routine.source.SourceManifestFailureCode;
import com.chromascape.web.aiplayer.routine.source.SourceManifestSignatureVerifier;
import com.chromascape.web.aiplayer.routine.source.TaskSourceManifest;
import com.chromascape.web.aiplayer.routine.source.TaskSourceManifestVerifier;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trusted, generic Micro6 source consumer. The only source selector accepted from callers is a task
 * key plus an entry/symbol contract; no filesystem path, upstream URL, branch, or mutable checkout
 * location crosses this boundary.
 */
@Component
public class Micro6SourceManifestClient {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ObjectMapper mapper;

  @Value("${micro6.source.transport.base-url:}")
  private String baseUrl;

  @Value("${micro6.source.transport.auth-token:}")
  private String authToken;

  /**
   * Micro6 authorizes each read by purpose: manifest reads require its metadata token and file
   * reads its content token, and each is rejected on the other's routes. When a purpose-specific
   * token is configured it is sent for that purpose; otherwise the shared auth token is used.
   */
  @Value("${micro6.source.transport.metadata-token:}")
  private String metadataToken;

  @Value("${micro6.source.transport.content-token:}")
  private String contentToken;

  @Value("${micro6.source.transport.actor:chromascape-web}")
  private String actor;

  /** The single canonical trust configuration; transport settings above are never trust. */
  private SourceTrustProperties trust = new SourceTrustProperties();

  @Autowired
  public Micro6SourceManifestClient(ObjectMapper mapper, SourceTrustProperties trust) {
    this.mapper = mapper;
    this.trust = trust;
  }

  /**
   * Fetches a manifest document without trusting it. Call {@link #resolve(SourceRequest)} to verify
   * it.
   */
  public String manifest(String taskKey) {
    requireTaskKey(taskKey);
    return decodeUtf8(
        getBytes(
            "/api/v1/source-manifests/" + encode(taskKey),
            SourceManifestContract.MAX_MANIFEST_BYTES,
            PURPOSE_MANIFEST));
  }

  /** Fetches a UTF-8 snapshot file; the caller must still verify it against a manifest entry. */
  public String file(String snapshotId, String fileId) {
    return decodeUtf8(fileBytes(snapshotId, fileId));
  }

  /** Fetches raw UTF-8 bytes so callers never hash a replacement-decoded response. */
  public byte[] fileBytes(String snapshotId, String fileId) {
    requireSnapshotId(snapshotId);
    requireFileId(fileId);
    return getBytes(
        "/api/v1/source-snapshots/" + encode(snapshotId) + "/files/" + encode(fileId),
        SourceManifestContract.MAX_FILE_BYTES,
        PURPOSE_FILE);
  }

  /**
   * Resolves a task's declared entry symbol using producer-indexed metadata. This is the reusable
   * path used by all tasks; Fishing remains only a compatibility convenience at the bottom of the
   * class and contains no source-scanning logic.
   */
  public SourceBinding resolve(SourceRequest request) {
    if (request == null || !SourceManifestContract.isTaskKey(request.taskKey())) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "task selector is required");
    }
    try {
      JsonNode rawManifest = parseManifest(manifest(request.taskKey()));
      TaskSourceManifestVerifier verifier =
          new TaskSourceManifestVerifier(
              SourceManifestSignatureVerifier.fromX509Base64(
                  trust.getPublicKeyBase64(), trust.getPublicKeyId()));
      TaskSourceManifestVerifier.ExpectedIdentity expected = request.expectedIdentity();
      TaskSourceManifestVerifier.Verified verified = verifier.verify(rawManifest, expected);
      enforceEnvironment(rawManifest);
      TaskSourceManifest sourceManifest = verified.manifest();
      rejectTerminalStatus(sourceManifest.status());

      TaskSourceManifest.File entryFile = findEntryFile(sourceManifest, rawManifest, request);
      String entryClassName = text(rawManifest.get("entry_point"), "class_name");
      TaskSourceManifest.Symbol symbol = findSymbol(sourceManifest, request, entryClassName);
      if (symbol == null) {
        throw sourceError(
            SourceManifestFailureCode.SOURCE_INDEX_PARTIAL,
            "manifest does not contain the requested symbol index");
      }
      if (!entryFile.fileId().equals(symbol.fileId())) {
        throw sourceError(
            SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
            "entry symbol is not contained by the declared entry file");
      }
      byte[] sourceBytes =
          getBytes(
              "/api/v1/source-snapshots/"
                  + encode(sourceManifest.sourceSnapshotId())
                  + "/files/"
                  + encode(entryFile.fileId()),
              SourceManifestContract.MAX_FILE_BYTES,
              PURPOSE_FILE);
      verifyFile(entryFile, sourceBytes);
      String source = decodeUtf8(sourceBytes);
      int start = boundedIndex(sourceBytes, symbol.startByte());
      int end = boundedIndex(sourceBytes, symbol.endByte());
      validateUtf8Boundaries(sourceBytes, start, end);
      validateLineColumns(sourceBytes, symbol);
      String preimage = hash(slice(sourceBytes, start, end));
      Map<String, PinnedFile> pinnedFiles = new LinkedHashMap<>();
      for (TaskSourceManifest.File file : sourceManifest.files()) {
        pinnedFiles.put(
            file.relativePath(),
            new PinnedFile(
                file.fileId(), file.relativePath(), file.contentHash(), file.sizeBytes()));
      }
      String module =
          entryFile.module() == null || entryFile.module().isBlank()
              ? "runelite-client"
              : entryFile.module();
      return new SourceBinding(
          nestedText(rawManifest, "build", "build_id"),
          sourceManifest.sourceSnapshotId(),
          sourceManifest.manifestHash(),
          entryFile.fileId(),
          entryFile.relativePath(),
          cloneRelativePath(module, entryFile.relativePath()),
          entryFile.contentHash(),
          symbol.startLine(),
          symbol.endLine(),
          preimage,
          source,
          mapper.writeValueAsString(rawManifest),
          pinnedFiles,
          nestedText(rawManifest, "repository", "repository_id", "id"),
          nestedText(rawManifest, "repository", "commit_id"),
          nestedText(rawManifest, "repository", "tree_id"),
          nestedText(rawManifest, "build", "artifact_digest"),
          verified.signatureVerified());
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          "authoritative source could not be fetched or verified",
          exception);
    }
  }

  /** Builds the expected source identity from an already-attested SC-609 client identity. */
  public static TaskSourceManifestVerifier.ExpectedIdentity expectedIdentity(
      CurrentSliceIdentity identity) {
    if (identity == null || identity.task() == null) return null;
    CurrentSliceIdentity.Source source = identity.source();
    CurrentSliceIdentity.Client client = identity.client();
    return new TaskSourceManifestVerifier.ExpectedIdentity(
        identity.task().taskKey(),
        source == null ? null : source.repositoryId(),
        source == null ? null : source.commitId(),
        source == null ? null : source.treeId(),
        source != null && source.buildId() != null
            ? source.buildId()
            : client == null ? null : client.clientBuildId(),
        source != null && source.artifactDigest() != null
            ? source.artifactDigest()
            : client == null ? null : client.artifactDigest(),
        source == null ? null : source.sourceSnapshotId(),
        source == null ? null : source.sourceManifestHash());
  }

  private TaskSourceManifest.File findEntryFile(
      TaskSourceManifest manifest, JsonNode rawManifest, SourceRequest request) {
    JsonNode entry = rawManifest.get("entry_point");
    String entryFileId = text(entry, "file_id");
    String entryClass = text(entry, "class_name");
    if (request.entryClassName() != null
        && !request.entryClassName().isBlank()
        && !request.entryClassName().equals(entryClass)) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "requested entry class differs from the signed manifest");
    }
    TaskSourceManifest.File file = entryFileId == null ? null : manifest.file(entryFileId);
    if (file == null) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "manifest entry point does not reference an allowlisted file");
    }
    return file;
  }

  private TaskSourceManifest.Symbol findSymbol(
      TaskSourceManifest manifest, SourceRequest request, String entryClassName) {
    if (request.symbolId() != null && !request.symbolId().isBlank()) {
      return manifest.symbols().stream()
          .filter(symbol -> request.symbolId().equals(symbol.sourceSymbolId()))
          .filter(symbol -> belongsToEntryClass(symbol, entryClassName))
          .findFirst()
          .orElse(null);
    }
    if (request.symbolName() == null || request.symbolName().isBlank()) {
      String entrySymbolId = text(manifest.document().get("entry_point"), "source_symbol_id");
      return manifest.symbols().stream()
          .filter(symbol -> entrySymbolId != null && entrySymbolId.equals(symbol.sourceSymbolId()))
          .filter(symbol -> belongsToEntryClass(symbol, entryClassName))
          .findFirst()
          .orElse(null);
    }
    String requested = request.symbolName().trim();
    String requestedBeforeSignature = requested;
    String requestedParameters = null;
    int opening = requested.indexOf('(');
    if (opening > 0 && requested.endsWith(")")) {
      requestedBeforeSignature = requested.substring(0, opening);
      requestedParameters = requested.substring(opening);
    }
    final String normalizedName = requested;
    final String normalizedBeforeSignature = requestedBeforeSignature;
    final String normalizedParameters = requestedParameters;
    final String requestedMethod =
        normalizedBeforeSignature.substring(normalizedBeforeSignature.lastIndexOf('#') + 1);
    List<TaskSourceManifest.Symbol> matches =
        manifest.symbols().stream()
            .filter(
                symbol ->
                    symbolMatches(
                        symbol,
                        normalizedName,
                        normalizedBeforeSignature,
                        normalizedParameters,
                        requestedMethod))
            .filter(symbol -> belongsToEntryClass(symbol, entryClassName))
            .toList();
    if (matches.size() > 1) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_INDEX_PARTIAL,
          "manifest contains an ambiguous source symbol index");
    }
    return matches.isEmpty() ? null : matches.get(0);
  }

  private static boolean belongsToEntryClass(
      TaskSourceManifest.Symbol symbol, String entryClassName) {
    if (entryClassName == null || entryClassName.isBlank()) return false;
    return entryClassName.equals(symbol.qualifiedName())
        || (symbol.qualifiedName() != null
            && symbol.qualifiedName().startsWith(entryClassName + "#"));
  }

  private static boolean symbolMatches(
      TaskSourceManifest.Symbol symbol,
      String requested,
      String requestedBeforeSignature,
      String requestedParameters,
      String requestedMethod) {
    if (requested.equals(symbol.qualifiedName())
        || requested.equals(symbol.signature())
        || requestedBeforeSignature.equals(symbol.qualifiedName())) return true;
    if (symbol.qualifiedName() == null || !symbol.qualifiedName().endsWith("#" + requestedMethod))
      return false;
    if (requestedParameters == null) return true;
    return parameterTypes(symbol.signature()).equals(parameterTypes(requestedParameters));
  }

  /** Compares Java parameter type lists while tolerating provider-added parameter names. */
  private static String parameterTypes(String signature) {
    if (signature == null) return "";
    int open = signature.indexOf('(');
    int close = signature.lastIndexOf(')');
    if (open < 0 || close < open) return signature.replaceAll("\\s+", "");
    String parameters = signature.substring(open + 1, close).trim();
    if (parameters.isEmpty()) return "";
    StringBuilder result = new StringBuilder();
    for (String parameter : parameters.split(",")) {
      String value = parameter.trim().replaceAll("\\s+", " ");
      // The source indexer records parameter names; callers generally provide a signature with
      // types only. Strip only the final identifier when there is a separate type token.
      int split = value.lastIndexOf(' ');
      if (split > 0 && value.substring(split + 1).matches("[A-Za-z_$][\\w$]*")) {
        value = value.substring(0, split);
      }
      if (result.length() > 0) result.append(',');
      result.append(value.replace(" ", ""));
    }
    return result.toString();
  }

  /** Stable audit purposes sent as {@code X-Source-Purpose} on every authoritative read. */
  public static final String PURPOSE_MANIFEST = "manifest-read";

  public static final String PURPOSE_FILE = "file-read";

  private byte[] getBytes(String path, long maximumBytes, String purpose) {
    try {
      String token = tokenFor(purpose);
      if (token == null || token.isBlank()) {
        throw sourceError(
            SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
            "source transport authentication is not configured");
      }
      String environment = trust == null ? "" : trust.getEnvironment();
      if (environment.isBlank()) {
        throw sourceError(
            SourceManifestFailureCode.SOURCE_ACCESS_DENIED, "source environment is not configured");
      }
      URI endpoint = trustedEndpoint(path);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).GET();
      builder.header("Authorization", "Bearer " + token);
      builder.header("X-Source-Actor", boundedActor());
      builder.header("X-Source-Purpose", purpose);
      builder.header("X-Correlation-ID", correlationId());
      builder.header("X-Source-Environment", environment);
      HttpResponse<byte[]> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200)
        throw upstreamFailure(response.statusCode(), response.body());
      if (response.body().length > maximumBytes) {
        throw sourceError(
            SourceManifestFailureCode.SOURCE_UNAVAILABLE,
            "source response exceeds the configured bound");
      }
      return response.body();
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_NETWORK_UNAVAILABLE,
          "source endpoint is unavailable",
          exception);
    }
  }

  private String tokenFor(String purpose) {
    String specific = PURPOSE_MANIFEST.equals(purpose) ? metadataToken : contentToken;
    return specific == null || specific.isBlank() ? authToken : specific.trim();
  }

  /** Bounded actor identity for Micro6 audit. */
  private String boundedActor() {
    String candidate = actor == null ? "" : actor.trim();
    if (!candidate.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_AUDIT_CONTEXT_INVALID,
          "source actor identity is invalid");
    }
    return candidate;
  }

  /**
   * Correlation identity for Micro6 audit. An inbound debugger correlation is propagated so the two
   * audit trails can be joined; every other request gets a fresh bounded identifier.
   */
  private static String correlationId() {
    String existing = org.slf4j.MDC.get("source_correlation_id");
    if (existing != null) {
      String normalized = existing.trim();
      if (!normalized.isBlank()
          && normalized.length() <= 64
          && normalized.matches("[A-Za-z0-9._-]+")) {
        return normalized;
      }
    }
    return java.util.UUID.randomUUID().toString();
  }

  private JsonNode parseManifest(String json) {
    try {
      return SourceManifestCanonicalJson.parse(json.getBytes(StandardCharsets.UTF_8));
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest JSON is invalid",
          exception);
    }
  }

  private URI trustedEndpoint(String path) {
    if (baseUrl == null || baseUrl.isBlank() || path == null || !path.startsWith("/api/v1/")) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED, "source endpoint is not trusted");
    }
    try {
      URI root = URI.create(baseUrl);
      if (!"http".equalsIgnoreCase(root.getScheme())
          && !"https".equalsIgnoreCase(root.getScheme())) {
        throw new IllegalArgumentException("unsupported source endpoint scheme");
      }
      if (root.getUserInfo() != null
          || root.getRawQuery() != null
          || root.getRawFragment() != null) {
        throw new IllegalArgumentException("source endpoint contains untrusted URI components");
      }
      String rootText = baseUrl.trim();
      while (rootText.endsWith("/")) rootText = rootText.substring(0, rootText.length() - 1);
      return URI.create(rootText + path);
    } catch (Exception exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
          "source endpoint is not trusted",
          exception);
    }
  }

  private SourceManifestException upstreamFailure(int status, byte[] response) {
    SourceManifestFailureCode code =
        switch (status) {
          case 401, 403 -> SourceManifestFailureCode.SOURCE_ACCESS_DENIED;
          case 404 -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
          case 410 -> SourceManifestFailureCode.SOURCE_PRUNED;
          case 429 -> SourceManifestFailureCode.SOURCE_RATE_LIMITED;
          default -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
        };
    try {
      JsonNode body = mapper.readTree(response);
      String declared = text(body, "code");
      if (declared != null) {
        try {
          code = SourceManifestFailureCode.valueOf(declared);
        } catch (IllegalArgumentException ignored) {
        }
      }
    } catch (Exception ignored) {
      // Do not expose upstream response text or URLs.
    }
    return new SourceManifestException(code, statusFor(code), "source request was not available");
  }

  private static void verifyFile(TaskSourceManifest.File entry, byte[] bytes) {
    if (bytes.length != entry.sizeBytes() || !hash(bytes).equals(entry.contentHash())) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content does not match the signed file identity");
    }
  }

  private static int boundedIndex(byte[] bytes, int value) {
    if (value < 0 || value > bytes.length) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source symbol range is outside its file");
    }
    return value;
  }

  private static byte[] slice(byte[] bytes, int start, int end) {
    if (end <= start)
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "source symbol range is empty");
    return java.util.Arrays.copyOfRange(bytes, start, end);
  }

  private static void validateUtf8Boundaries(byte[] bytes, int start, int end) {
    if ((start > 0 && start < bytes.length && isUtf8Continuation(bytes[start]))
        || (end > 0 && end < bytes.length && isUtf8Continuation(bytes[end]))) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source symbol range splits a UTF-8 code point");
    }
  }

  private static void validateLineColumns(byte[] bytes, TaskSourceManifest.Symbol symbol) {
    Position start = position(bytes, symbol.startByte());
    Position end = position(bytes, symbol.endByte());
    if (start.line != symbol.startLine()
        || start.column != symbol.startColumn()
        || end.line != symbol.endLine()
        || end.column != symbol.endColumn()) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source symbol line and column span does not match its bytes");
    }
  }

  private static Position position(byte[] bytes, int offset) {
    try {
      String prefix =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, 0, offset))
              .toString();
      int line = 1;
      int column = 1;
      for (int index = 0; index < prefix.length(); index++) {
        if (prefix.charAt(index) == '\n') {
          line++;
          column = 1;
        } else {
          column++;
        }
      }
      return new Position(line, column);
    } catch (CharacterCodingException exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source symbol span is not valid UTF-8");
    }
  }

  private record Position(int line, int column) {}

  private static boolean isUtf8Continuation(byte value) {
    return (value & 0xc0) == 0x80;
  }

  private static void requireTaskKey(String value) {
    if (!SourceManifestContract.isTaskKey(value)) {
      throw sourceError(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "task key is invalid");
    }
  }

  private static void requireSnapshotId(String value) {
    if (value == null || !SourceManifestContract.SNAPSHOT_ID.matcher(value).matches()) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "snapshot identity is invalid");
    }
  }

  private static void requireFileId(String value) {
    if (value == null || !SourceManifestContract.FILE_ID.matcher(value).matches()) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "file identity is invalid");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content is not valid UTF-8");
    }
  }

  private static String cloneRelativePath(String module, String relativePath) {
    if (module == null || module.isBlank()) module = "runelite-client";
    if (!SourceManifestContract.isSafeModule(module)) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, "source module identity is invalid");
    }
    if (relativePath.equals(module) || relativePath.startsWith(module + "/")) {
      return relativePath;
    }
    return module + "/" + relativePath;
  }

  private void enforceEnvironment(JsonNode manifest) {
    String environment = trust.getEnvironment();
    if (environment.isBlank()) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED, "source environment is not configured");
    }
    JsonNode declared = manifest == null ? null : manifest.get("environment");
    String expected = environment.trim();
    if (declared == null
        || !declared.isTextual()
        || !expected.equals(declared.textValue().trim())) {
      throw sourceError(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "source environment identity differs from the configured environment");
    }
  }

  private static void rejectTerminalStatus(String status) {
    if (status == null || "AVAILABLE".equals(status)) return;
    SourceManifestFailureCode code =
        switch (status) {
          case "INDEX_PARTIAL" -> SourceManifestFailureCode.SOURCE_INDEX_PARTIAL;
          case "UNAVAILABLE" -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
          case "REDACTED" -> SourceManifestFailureCode.SOURCE_REDACTED;
          case "PRUNED" -> SourceManifestFailureCode.SOURCE_PRUNED;
          case "GENERATED_ONLY" -> SourceManifestFailureCode.SOURCE_GENERATED_ONLY;
          default -> SourceManifestFailureCode.SOURCE_MANIFEST_INVALID;
        };
    throw sourceError(code, "source manifest is not available");
  }

  private static org.springframework.http.HttpStatus statusFor(SourceManifestFailureCode code) {
    return switch (code) {
      case SOURCE_ACCESS_DENIED -> org.springframework.http.HttpStatus.UNAUTHORIZED;
      case SOURCE_PRUNED -> org.springframework.http.HttpStatus.GONE;
      case SOURCE_REDACTED,
          SOURCE_FILE_NOT_FOUND,
          SOURCE_SYMBOL_NOT_FOUND,
          SOURCE_UNAVAILABLE -> org.springframework.http.HttpStatus.NOT_FOUND;
      case SOURCE_NETWORK_UNAVAILABLE -> org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
      case SOURCE_RATE_LIMITED -> org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
      default -> org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }

  private static String text(JsonNode node, String field, String... alternatives) {
    JsonNode value = node == null ? null : node.get(field);
    if (value != null && value.isTextual()) return value.textValue();
    if (alternatives != null) {
      for (String alternative : alternatives) {
        value = node == null ? null : node.get(alternative);
        if (value != null && value.isTextual()) return value.textValue();
      }
    }
    return null;
  }

  private static String nestedText(
      JsonNode node, String object, String field, String... alternatives) {
    JsonNode nested = node == null ? null : node.get(object);
    return text(nested, field, alternatives);
  }

  private static String hash(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder("sha256:");
      for (byte value : digest) {
        result.append(String.format(java.util.Locale.ROOT, "%02x", value));
      }
      return result.toString();
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static SourceManifestException sourceError(
      SourceManifestFailureCode code, String message) {
    return new SourceManifestException(code, message);
  }

  private static SourceManifestException sourceError(
      SourceManifestFailureCode code, String message, Throwable cause) {
    return new SourceManifestException(
        code, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, message, cause);
  }

  public record SourceRequest(
      String taskKey,
      String entryClassName,
      String symbolName,
      String symbolId,
      TaskSourceManifestVerifier.ExpectedIdentity expectedIdentity) {
    public SourceRequest(
        String taskKey,
        String entryClassName,
        String symbolName,
        TaskSourceManifestVerifier.ExpectedIdentity expectedIdentity) {
      this(taskKey, entryClassName, symbolName, null, expectedIdentity);
    }
  }

  public record PinnedFile(
      String fileId, String relativePath, String contentHash, long sizeBytes) {}

  public record SourceBinding(
      String clientBuildId,
      String sourceSnapshotId,
      String sourceManifestHash,
      String fileId,
      String relativePath,
      String cloneRelativePath,
      String contentHash,
      int startLine,
      int endLine,
      String preimageHash,
      String source,
      String manifestJson,
      Map<String, PinnedFile> pinnedFiles,
      String repositoryId,
      String commitId,
      String treeId,
      String artifactDigest,
      boolean signatureVerified) {
    /** Source-compatible constructor for callers that only have the original prototype fields. */
    public SourceBinding(
        String clientBuildId,
        String sourceSnapshotId,
        String sourceManifestHash,
        String fileId,
        String relativePath,
        String cloneRelativePath,
        String contentHash,
        int startLine,
        int endLine,
        String preimageHash,
        String source,
        String manifestJson,
        Map<String, PinnedFile> pinnedFiles) {
      this(
          clientBuildId,
          sourceSnapshotId,
          sourceManifestHash,
          fileId,
          relativePath,
          cloneRelativePath,
          contentHash,
          startLine,
          endLine,
          preimageHash,
          source,
          manifestJson,
          pinnedFiles,
          null,
          clientBuildId,
          null,
          null,
          false);
    }
  }
}
