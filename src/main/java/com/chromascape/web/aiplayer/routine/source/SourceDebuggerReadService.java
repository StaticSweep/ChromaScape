package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.routine.service.Micro6SourceManifestClient;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObject;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceContentObjectRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReference;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceManifestReferenceRepository;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBinding;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceRunBindingRepository;
import com.chromascape.web.config.SourceTrustProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read-only debugger view over exact persisted source identities and verified content. */
@Service
public class SourceDebuggerReadService {
  private final ObjectMapper mapper;
  private final SourceManifestReferenceRepository manifests;
  private final SourceRunBindingRepository bindings;
  private final SourceContentObjectRepository contentObjects;
  private final VerifiedSourceContentCache cache;
  private final Micro6SourceManifestClient upstream;
  private final SourceReadAuditService audit;

  /** The canonical manifest trust configuration. */
  private final SourceTrustProperties trust;

  @Autowired
  public SourceDebuggerReadService(
      ObjectMapper mapper,
      SourceManifestReferenceRepository manifests,
      SourceRunBindingRepository bindings,
      SourceContentObjectRepository contentObjects,
      VerifiedSourceContentCache cache,
      Micro6SourceManifestClient upstream,
      SourceReadAuditService audit,
      SourceTrustProperties trust) {
    this.mapper = mapper;
    this.manifests = manifests;
    this.bindings = bindings;
    this.contentObjects = contentObjects;
    this.cache = cache;
    this.upstream = upstream;
    this.audit = audit;
    this.trust = trust;
  }

  public java.util.Map<String, Object> manifest(String runId, String actor) {
    SourceRunBinding binding = binding(runId, actor, "manifest", null);
    SourceManifestReference reference = reference(binding, actor, "manifest", null);
    TaskSourceManifestVerifier.Verified verified = verify(reference);
    java.util.Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "debugger-source-manifest/v1");
    result.put("run_id", runId);
    result.put("recording_id", binding.getRecordingId());
    result.put("verification_state", binding.getVerificationState());
    result.put("source_provenance", binding.getSourceProvenance());
    result.put("manifest", verified.manifest().document());
    return result;
  }

  public java.util.Map<String, Object> file(String runId, String fileId, String actor) {
    SourceRunBinding binding = binding(runId, actor, "file", fileId);
    if (fileId == null || !SourceManifestContract.FILE_ID.matcher(fileId).matches()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          "source file is not available");
    }
    SourceManifestReference reference = reference(binding, actor, "file", fileId);
    TaskSourceManifestVerifier.Verified verified = verify(reference);
    TaskSourceManifest.File file = verified.manifest().file(fileId);
    if (file == null) {
      audit.record(
          actor,
          "file",
          runId,
          binding.getRecordingId(),
          binding.getTaskKey(),
          binding.getSnapshotId(),
          fileId,
          "SOURCE_FILE_NOT_FOUND");
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          "source file is not in the verified manifest");
    }
    ensureReadable(file, actor, binding);
    byte[] content = durableOrFetch(file, binding, actor);
    content = verifyContent(file, content);
    String text = decodeUtf8(content);
    java.util.Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "debugger-source-file/v1");
    result.put("run_id", runId);
    result.put("recording_id", binding.getRecordingId());
    result.put("file_id", file.fileId());
    result.put("relative_path", file.relativePath());
    result.put("module", file.module());
    result.put("language", file.language());
    result.put("content_hash", file.contentHash());
    result.put("size_bytes", file.sizeBytes());
    result.put("classification", file.classification());
    result.put("content", text);
    return result;
  }

  private byte[] durableOrFetch(
      TaskSourceManifest.File file, SourceRunBinding binding, String actor) {
    if (contentObjects != null) {
      SourceContentObject object =
          contentObjects.findByContentHash(file.contentHash()).orElse(null);
      if (object != null) {
        if (object.getPrunedAt() != null) {
          throw new SourceManifestException(
              SourceManifestFailureCode.SOURCE_PRUNED,
              HttpStatus.GONE,
              "source snapshot has been pruned");
        }
        if (object.getRedactedAt() != null) {
          throw new SourceManifestException(
              SourceManifestFailureCode.SOURCE_REDACTED,
              HttpStatus.NOT_FOUND,
              "source is not available");
        }
        java.util.Optional<byte[]> cached = cache.get(file.contentHash());
        if (cached.isPresent()) {
          byte[] content = verifyContent(file, cached.get());
          audit.record(
              actor,
              "file",
              binding.getRunId(),
              binding.getRecordingId(),
              binding.getTaskKey(),
              binding.getSnapshotId(),
              file.fileId(),
              "CACHE_HIT");
          return content;
        }
        byte[] content = object.getContentBytes();
        if (content == null
            || object.getSizeBytes() != file.sizeBytes()
            || content.length != file.sizeBytes()
            || !hash(content).equals(file.contentHash())) {
          throw new SourceManifestException(
              SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
              "persisted source content does not match the verified identity");
        }
        cache.put(file.contentHash(), content);
        audit.record(
            actor,
            "file",
            binding.getRunId(),
            binding.getRecordingId(),
            binding.getTaskKey(),
            binding.getSnapshotId(),
            file.fileId(),
            "PERSISTED_CACHE_HIT");
        return content;
      }
      // Once durable storage is configured, a cache entry without a corresponding durable object
      // is not authoritative. Re-fetching also prevents a stale pre-persistence cache value from
      // bypassing the immutable object/retention state.
      return fetchAndCache(file, binding, actor);
    }
    java.util.Optional<byte[]> cached = cache.get(file.contentHash());
    if (cached.isPresent()) {
      byte[] content = verifyContent(file, cached.get());
      audit.record(
          actor,
          "file",
          binding.getRunId(),
          binding.getRecordingId(),
          binding.getTaskKey(),
          binding.getSnapshotId(),
          file.fileId(),
          "CACHE_HIT");
      return content;
    }
    return fetchAndCache(file, binding, actor);
  }

  public java.util.Map<String, Object> symbols(
      String runId, String fileId, int page, int limit, String actor) {
    SourceRunBinding binding = binding(runId, actor, "symbols", fileId);
    SourceManifestReference reference = reference(binding, actor, "symbols", fileId);
    TaskSourceManifestVerifier.Verified verified = verify(reference);
    if (fileId != null && !SourceManifestContract.FILE_ID.matcher(fileId).matches()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          "source file is not available");
    }
    if (page < 0 || limit < 1 || limit > 500) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          HttpStatus.BAD_REQUEST,
          "symbol pagination is invalid");
    }
    if (fileId != null) {
      TaskSourceManifest.File file = verified.manifest().file(fileId);
      if (file == null) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_FILE_NOT_FOUND,
            HttpStatus.NOT_FOUND,
            "source file is not available");
      }
      ensureReadable(file, actor, binding);
    }
    List<TaskSourceManifest.Symbol> all =
        fileId == null
            ? verified.manifest().symbols().stream()
                .filter(symbol -> isReadable(verified.manifest().file(symbol.fileId())))
                .toList()
            : verified.manifest().symbolsForFile(fileId);
    long offset = (long) page * limit;
    int from = offset >= all.size() ? all.size() : (int) offset;
    int to = Math.min(from + limit, all.size());
    List<Object> values = new ArrayList<>();
    for (TaskSourceManifest.Symbol symbol : all.subList(from, to)) {
      values.add(symbolMap(symbol));
    }
    java.util.Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "debugger-source-symbols/v1");
    result.put("run_id", runId);
    result.put("file_id", fileId);
    result.put("page", page);
    result.put("limit", limit);
    result.put("total", all.size());
    result.put("symbols", values);
    return result;
  }

  private static java.util.Map<String, Object> symbolMap(TaskSourceManifest.Symbol symbol) {
    java.util.Map<String, Object> value = new LinkedHashMap<>();
    value.put("source_symbol_id", symbol.sourceSymbolId());
    value.put("file_id", symbol.fileId());
    value.put("kind", symbol.kind());
    value.put("qualified_name", symbol.qualifiedName());
    value.put("signature", symbol.signature());
    value.put("enclosing_symbol_id", symbol.enclosingSymbolId());
    value.put("start_byte", symbol.startByte());
    value.put("end_byte", symbol.endByte());
    value.put("start_line", symbol.startLine());
    value.put("end_line", symbol.endLine());
    value.put("start_column", symbol.startColumn());
    value.put("end_column", symbol.endColumn());
    value.put("generated", symbol.generated());
    return value;
  }

  private byte[] fetchAndCache(
      TaskSourceManifest.File file, SourceRunBinding binding, String actor) {
    try {
      byte[] bytes = upstream.fileBytes(binding.getSnapshotId(), file.fileId());
      bytes = verifyContent(file, bytes);
      if (contentObjects != null) {
        SourceContentObject existing =
            contentObjects.findByContentHash(file.contentHash()).orElse(null);
        if (existing == null) {
          contentObjects.save(SourceContentObject.create(file.contentHash(), bytes));
        } else if (existing.getPrunedAt() != null
            || existing.getRedactedAt() != null
            || !java.util.Arrays.equals(existing.getContentBytes(), bytes)) {
          throw new SourceManifestException(
              SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
              "persisted source content identity was reused with different bytes");
        }
      }
      cache.put(file.contentHash(), bytes);
      audit.record(
          actor,
          "file",
          binding.getRunId(),
          binding.getRecordingId(),
          binding.getTaskKey(),
          binding.getSnapshotId(),
          file.fileId(),
          "CACHE_MISS_VERIFIED");
      return bytes;
    } catch (SourceManifestException exception) {
      audit.record(
          actor,
          "file",
          binding.getRunId(),
          binding.getRecordingId(),
          binding.getTaskKey(),
          binding.getSnapshotId(),
          file.fileId(),
          exception.code().name());
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          HttpStatus.NOT_FOUND,
          "source content is unavailable",
          exception);
    }
  }

  private TaskSourceManifestVerifier.Verified verify(SourceManifestReference reference) {
    try {
      JsonNode manifest =
          SourceManifestCanonicalJson.parse(
              reference.getManifestJson().getBytes(StandardCharsets.UTF_8));
      TaskSourceManifestVerifier.Verified verified =
          new TaskSourceManifestVerifier(
                  SourceManifestSignatureVerifier.fromX509Base64(
                      trust.getPublicKeyBase64(), trust.getPublicKeyId()))
              .verify(
                  manifest,
                  new TaskSourceManifestVerifier.ExpectedIdentity(
                      reference.getTaskKey(),
                      reference.getRepositoryId(),
                      reference.getCommitId(),
                      reference.getTreeId(),
                      reference.getBuildId(),
                      reference.getArtifactDigest(),
                      reference.getSnapshotId(),
                      reference.getManifestHash()));
      // Debugger reads serve only persisted signed v1 identities. The verify call above
      // already rejects unsupported schemas; this guard keeps a future non-authoritative
      // result from ever being served even if storage predates the rule.
      if (verified == null
          || !verified.signatureVerified()
          || !SourceManifestContract.SCHEMA.equals(verified.manifest().schema())) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
            "persisted source manifest is not authoritative");
      }
      return verified;
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "persisted source manifest is invalid",
          exception);
    }
  }

  private SourceRunBinding binding(String runId, String actor, String operation, String fileId) {
    if (runId == null || runId.isBlank() || runId.length() > 160 || runId.contains("/")) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_UNAVAILABLE,
          HttpStatus.NOT_FOUND,
          "source binding is unavailable");
    }
    SourceRunBinding binding =
        bindings
            .findByRunId(runId)
            .orElseThrow(
                () ->
                    new SourceManifestException(
                        SourceManifestFailureCode.SOURCE_UNAVAILABLE,
                        HttpStatus.NOT_FOUND,
                        "source binding is unavailable"));
    if ("PRUNED".equals(binding.getVerificationState())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_PRUNED,
          HttpStatus.GONE,
          "source snapshot has been pruned");
    }
    if (!"VERIFIED".equals(binding.getVerificationState())
        || !"VERIFIED".equals(binding.getSourceProvenance())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          HttpStatus.UNPROCESSABLE_ENTITY,
          "source binding is not authoritative");
    }
    audit.record(
        actor,
        operation,
        runId,
        binding.getRecordingId(),
        binding.getTaskKey(),
        binding.getSnapshotId(),
        fileId,
        "AUTHORIZED");
    return binding;
  }

  private SourceManifestReference reference(
      SourceRunBinding binding, String actor, String operation, String fileId) {
    SourceManifestReference reference =
        manifests
            .findByManifestHash(binding.getManifestHash())
            .orElseThrow(
                () ->
                    new SourceManifestException(
                        SourceManifestFailureCode.SOURCE_UNAVAILABLE,
                        HttpStatus.NOT_FOUND,
                        "source manifest is unavailable"));
    if (reference.getPrunedAt() != null) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_PRUNED,
          HttpStatus.GONE,
          "source snapshot has been pruned");
    }
    if (reference.getRedactedAt() != null || "REDACTED".equals(reference.getVerificationState())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_REDACTED,
          HttpStatus.NOT_FOUND,
          "source is not available");
    }
    if (!same(binding.getTaskKey(), reference.getTaskKey())
        || !same(binding.getSnapshotId(), reference.getSnapshotId())
        || !same(binding.getManifestHash(), reference.getManifestHash())
        || !same(binding.getArtifactDigest(), reference.getArtifactDigest())
        || !same(binding.getSourceProvenance(), reference.getSourceProvenance())
        || !same(binding.getEnvironment(), reference.getEnvironment())
        || !same(binding.getRepositoryId(), reference.getRepositoryId())
        || !same(binding.getCommitId(), reference.getCommitId())
        || !same(binding.getTreeId(), reference.getTreeId())
        || !same(binding.getBuildId(), reference.getBuildId())
        || !SourceManifestContract.SCHEMA.equals(reference.getSchemaVersion())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "source binding identity is inconsistent");
    }
    return reference;
  }

  private void ensureReadable(
      TaskSourceManifest.File file, String actor, SourceRunBinding binding) {
    if (!isReadable(file)) {
      audit.record(
          actor,
          "content",
          binding.getRunId(),
          binding.getRecordingId(),
          binding.getTaskKey(),
          binding.getSnapshotId(),
          file == null ? null : file.fileId(),
          "SOURCE_ACCESS_DENIED");
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_ACCESS_DENIED,
          HttpStatus.FORBIDDEN,
          "source content is not available");
    }
  }

  private static boolean isReadable(TaskSourceManifest.File file) {
    if (file == null) return false;
    return "SOURCE".equals(file.classification());
  }

  private static byte[] verifyContent(TaskSourceManifest.File file, byte[] content) {
    if (content == null
        || content.length != file.sizeBytes()
        || !hash(content).equals(file.contentHash())) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content does not match the verified identity");
    }
    return content;
  }

  private static boolean same(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private static String hash(byte[] bytes) {
    byte[] digest = SourceManifestCanonicalJson.digest(bytes);
    StringBuilder value = new StringBuilder("sha256:");
    for (byte part : digest) {
      value.append(String.format(java.util.Locale.ROOT, "%02x", part));
    }
    return value.toString();
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
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_CONTENT_HASH_MISMATCH,
          "source content is not valid UTF-8");
    }
  }
}
