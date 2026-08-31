package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed verifier for producer manifests. It validates identity and structure before any
 * content is persisted or displayed; it never resolves a checkout path or follows a caller target.
 */
public final class TaskSourceManifestVerifier {
  private static final Pattern CLASSIFICATION =
      Pattern.compile("SOURCE|GENERATED|TRANSFORMED|VENDOR|TEST|UNRELATED|SENSITIVE|EXCLUDED");
  private static final Set<String> ROOT_FIELDS = Set.of(
      "schema", "task_key", "task_family", "catalog_version", "settings_schema_version",
      "repository", "build", "source_snapshot_id", "source_manifest_hash", "parser_version",
      "indexer_version", "status", "retention_class", "confidentiality", "environment",
      "entry_point", "files", "closure", "symbols", "signature", "warnings", "failure");
  private static final Set<String> REPOSITORY_FIELDS =
      Set.of("repository_id", "commit_id", "tree_id", "clean");
  private static final Set<String> BUILD_FIELDS =
      Set.of("build_id", "build_timestamp", "artifact_digest");
  private static final Set<String> ENTRY_FIELDS =
      Set.of("class_name", "file_id", "source_symbol_id");
  private static final Set<String> FILE_FIELDS = Set.of(
      "file_id", "relative_path", "module", "language", "content_hash", "size_bytes",
      "classification", "inclusion_reason");
  private static final Set<String> SYMBOL_FIELDS = Set.of(
      "source_symbol_id", "file_id", "kind", "qualified_name", "signature",
      "enclosing_symbol_id", "start_byte", "end_byte", "start_line", "end_line",
      "start_column", "end_column", "generated");
  private static final Set<String> SIGNATURE_FIELDS =
      Set.of("algorithm", "key_id", "value", "signed_bytes_hash");
  private final boolean requireSignature;
  private final boolean allowLegacy;
  private final SourceManifestSignatureVerifier signatureVerifier;

  public TaskSourceManifestVerifier() {
    this(false, true, null);
  }

  public TaskSourceManifestVerifier(
      boolean requireSignature,
      boolean allowLegacy,
      SourceManifestSignatureVerifier signatureVerifier) {
    this.requireSignature = requireSignature;
    this.allowLegacy = allowLegacy;
    this.signatureVerifier = signatureVerifier;
  }

  public Verified verify(JsonNode manifest) {
    try {
      SourceManifestContract.requireObject(manifest);
      String schema = text(manifest, "schema");
      if (!SourceManifestContract.supportedSchema(schema, allowLegacy)) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_SCHEMA_UNSUPPORTED,
            "unsupported source manifest schema");
      }
      String taskKey = text(manifest, "task_key");
      if (!SourceManifestContract.isTaskKey(taskKey)) invalid("task_key is invalid");
      String snapshot = SourceManifestContract.declaredSnapshotId(manifest);
      String declaredHash = SourceManifestContract.declaredManifestHash(manifest);
      if (!SourceManifestContract.SNAPSHOT_ID.matcher(snapshot == null ? "" : snapshot).matches()
          || !SourceManifestContract.isHash(declaredHash)) invalid("manifest identity is malformed");
      String computedHash = hash(SourceManifestContract.canonicalHashBytes(manifest));
      if (!computedHash.equals(declaredHash)
          || !snapshot.equals(SourceManifestContract.snapshotForHash(declaredHash))) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
            "manifest checksum and snapshot identity do not agree");
      }

      boolean legacy = SourceManifestContract.LEGACY_SCHEMA.equals(schema)
          || SourceManifestContract.LEGACY_SCHEMA_V2.equals(schema);
      if (!legacy) validateKnownFields(manifest, ROOT_FIELDS, "manifest");
      validateEnvironment(manifest, legacy);
      // An explicitly unavailable publication is still integrity-checked above, but it cannot
      // be required to carry the commit/build proof that an AVAILABLE publication promises. This
      // preserves the producer's stable UNAVAILABLE/REDACTED/PRUNED/GENERATED_ONLY states instead
      // of collapsing them into a misleading build mismatch.
      String status = text(manifest, "status");
      SourceManifestFailureCode statusFailure = statusFailure(status);
      if (statusFailure != null) unavailable(statusFailure);
      if (status != null && !"AVAILABLE".equals(status) && !legacy) {
        invalid("source manifest status is invalid");
      }
      if (!legacy) validateAuthoritativeIdentity(manifest);
      JsonNode declaredSignature = manifest.get("signature");
      boolean signatureVerified = false;
      // The schema requires the producer to sign every AVAILABLE (promoted) manifest; the
      // production posture enforces that here through require-signature=true. Development defaults
      // (require-signature=false) tolerate locally generated unsigned manifests, and any declared
      // signature block is always shape- and key-verified above regardless of that knob.
      if (!legacy && declaredSignature != null) {
        validateSignatureShape(declaredSignature);
        if (signatureVerifier == null || !signatureVerifier.verify(manifest)) {
          throw new SourceManifestException(
              SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
              "source manifest signature is missing or invalid");
        }
        signatureVerified = true;
      }
      if (requireSignature && !legacy && !signatureVerified) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
            "source manifest signature is missing or invalid");
      }

      JsonNode filesNode = manifest.get("files");
      if (filesNode == null || !filesNode.isArray() || filesNode.size() == 0
          || filesNode.size() > SourceManifestContract.MAX_FILES) invalid("files is invalid");
      List<TaskSourceManifest.File> files = new ArrayList<>();
      Set<String> fileIds = new HashSet<>();
      Set<String> paths = new HashSet<>();
      long totalBytes = 0L;
      for (JsonNode file : filesNode) {
        if (!legacy) validateKnownFields(file, FILE_FIELDS, "file");
        String id = text(file, "file_id");
        String path = text(file, "relative_path");
        String contentHash = text(file, "content_hash");
        long size = number(file, "size_bytes", -1);
        if (id == null || !SourceManifestContract.FILE_ID.matcher(id).matches()
            || !fileIds.add(id)) invalid("file identity is invalid");
        if (!SourceManifestContract.isSafeRelativePath(path) || !paths.add(path)) invalid("file path is invalid");
        if (!SourceManifestContract.isHash(contentHash) || size < 0
            || size > SourceManifestContract.MAX_FILE_BYTES) invalid("file content metadata is invalid");
        if (text(file, "module") != null
                && (!SourceManifestContract.isSafeModule(text(file, "module"))
                    || text(file, "module").length() > 160)
            || text(file, "language") != null && text(file, "language").length() > 40) {
          invalid("file module or language is invalid");
        }
        String classification = text(file, "classification");
        String inclusionReason = text(file, "inclusion_reason");
        if ((!legacy && classification == null)
            || (classification != null && !CLASSIFICATION.matcher(classification).matches())
            || (!legacy && (inclusionReason == null || inclusionReason.isBlank()
                || inclusionReason.length() > 1000))) invalid("file classification is invalid");
        totalBytes = Math.addExact(totalBytes, size);
        if (totalBytes > SourceManifestContract.MAX_CLOSURE_BYTES) invalid("source closure is too large");
        files.add(new TaskSourceManifest.File(id, path, text(file, "module"), text(file, "language"), contentHash, size, classification, inclusionReason));
      }

      validateClosure(manifest, fileIds);
      List<TaskSourceManifest.Symbol> symbols = parseSymbols(manifest, fileIds, files);
      if (!legacy) validateEntryPoint(manifest, fileIds);
      if (!legacy) validateEntrySymbol(manifest, symbols);
      if (!legacy && symbols.isEmpty()) {
        invalid("source manifest symbol index is empty");
      }
      return new Verified(manifest.deepCopy(), new TaskSourceManifest(manifest, files, symbols), legacy,
          computedHash, signatureVerified);
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest cannot be verified",
          exception);
    }
  }

  public Verified verify(JsonNode manifest, ExpectedIdentity expected) {
    Verified verified = verify(manifest);
    if (expected != null) {
      compare(expected.taskKey(), verified.manifest().taskKey(), "task");
      // Legacy manifests have no authoritative repository/build group. Their snapshot and
      // manifest identities may still be matched to a historical recording, but absent legacy
      // fields must never be compared to the newer client attestation fields.
      if (!verified.legacy()) {
        compare(expected.repositoryId(), textObject(manifest, "repository", "repository_id"), "repository");
        compare(expected.commitId(), textObject(manifest, "repository", "commit_id"), "commit");
        compare(expected.treeId(), textObject(manifest, "repository", "tree_id"), "tree");
        compare(expected.buildId(), textObject(manifest, "build", "build_id"), "build");
        compare(expected.artifactDigest(), textObject(manifest, "build", "artifact_digest"), "artifact");
      }
      compare(expected.snapshotId(), verified.manifest().sourceSnapshotId(), "snapshot");
      compare(expected.manifestHash(), verified.manifest().manifestHash(), "manifest");
    }
    return verified;
  }

  private static List<TaskSourceManifest.Symbol> parseSymbols(
      JsonNode manifest, Set<String> fileIds, List<TaskSourceManifest.File> files) {
    JsonNode node = manifest.get("symbols");
    if (node == null || node.isNull()) return List.of();
    if (!node.isArray() || node.size() > SourceManifestContract.MAX_SYMBOLS) invalid("symbols is invalid");
    List<TaskSourceManifest.Symbol> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode symbol : node) {
      validateKnownFields(symbol, SYMBOL_FIELDS, "symbol");
      String id = text(symbol, "source_symbol_id");
      String fileId = text(symbol, "file_id");
      int startByte = number(symbol, "start_byte", -1);
      int endByte = number(symbol, "end_byte", -1);
      int startLine = number(symbol, "start_line", -1);
      int endLine = number(symbol, "end_line", -1);
      int startColumn = number(symbol, "start_column", -1);
      int endColumn = number(symbol, "end_column", -1);
      long fileSize = files.stream().filter(file -> file.fileId().equals(fileId)).mapToLong(TaskSourceManifest.File::sizeBytes).findFirst().orElse(-1L);
      if (id == null || !SourceManifestContract.SYMBOL_ID.matcher(id).matches() || !ids.add(id)
          || fileId == null || !fileIds.contains(fileId) || startByte < 0 || endByte <= startByte
          || endByte > fileSize
          || startLine < 1 || endLine < startLine || startColumn < 1 || endColumn < 1
          || (text(symbol, "kind") == null || text(symbol, "kind").isBlank())
          || (text(symbol, "qualified_name") == null || text(symbol, "qualified_name").isBlank())
          || (text(symbol, "signature") != null && text(symbol, "signature").length() > 1000)) invalid("symbol range is invalid");
      String enclosing = text(symbol, "enclosing_symbol_id");
      if (enclosing != null && !SourceManifestContract.SYMBOL_ID.matcher(enclosing).matches()) {
        invalid("symbol enclosing identity is invalid");
      }
      if (text(symbol, "kind") != null && text(symbol, "kind").length() > 40
          || text(symbol, "qualified_name") != null
              && text(symbol, "qualified_name").length() > 500) {
        invalid("symbol name is too long");
      }
      JsonNode generated = symbol == null ? null : symbol.get("generated");
      if (generated != null && !generated.isBoolean()) invalid("symbol generated flag is invalid");
      result.add(new TaskSourceManifest.Symbol(id, fileId, text(symbol, "kind"), text(symbol, "qualified_name"), text(symbol, "signature"), enclosing, startByte, endByte, startLine, endLine, startColumn, endColumn, booleanValue(symbol, "generated")));
    }
    Set<String> symbolIds = result.stream().map(TaskSourceManifest.Symbol::sourceSymbolId).collect(java.util.stream.Collectors.toSet());
    for (TaskSourceManifest.Symbol symbol : result) {
      if (symbol.enclosingSymbolId() != null && !symbolIds.contains(symbol.enclosingSymbolId())) {
        invalid("symbol enclosing identity is not indexed");
      }
      if (symbol.enclosingSymbolId() != null && symbol.enclosingSymbolId().equals(symbol.sourceSymbolId())) {
        invalid("symbol cannot enclose itself");
      }
    }
    return result;
  }

  private static void validateAuthoritativeIdentity(JsonNode manifest) {
    JsonNode repository = manifest.get("repository");
    JsonNode build = manifest.get("build");
    if (repository == null || !repository.isObject() || build == null || !build.isObject()) {
      invalid("repository and build identities are required");
    }
    validateKnownFields(repository, REPOSITORY_FIELDS, "repository");
    validateKnownFields(build, BUILD_FIELDS, "build");
    if (!isPresentText(repository, "repository_id")
        || !SourceManifestContract.isGitObjectId(text(repository, "commit_id"))
        || !SourceManifestContract.isGitObjectId(text(repository, "tree_id"))) {
      invalid("repository commit/tree identity is required");
    }
    JsonNode clean = repository.get("clean");
    if (clean == null || !clean.isBoolean()) invalid("repository clean state is invalid");
    if (!clean.booleanValue()) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "dirty source trees are not patch-eligible");
    }
    if (text(build, "build_id") == null || text(build, "build_id").isBlank()
        || !SourceManifestContract.isHash(text(build, "artifact_digest"))
        || "UNVERIFIED".equals(text(build, "artifact_digest"))) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH,
          "build and launched artifact identities are required");
    }
    if (text(manifest, "task_family") == null || text(manifest, "task_family").isBlank()
        || text(manifest, "catalog_version") == null || text(manifest, "catalog_version").isBlank()
        || text(manifest, "settings_schema_version") == null
        || text(manifest, "settings_schema_version").isBlank()
        || text(manifest, "parser_version") == null || text(manifest, "parser_version").isBlank()
        || text(manifest, "indexer_version") == null || text(manifest, "indexer_version").isBlank()
        || text(manifest, "status") == null || text(manifest, "retention_class") == null
        || text(manifest, "confidentiality") == null) {
      invalid("manifest compatibility and retention metadata are required");
    }
    if (!Set.of("DEBUG", "AUDIT", "REGULATED").contains(text(manifest, "retention_class"))) {
      invalid("retention class is invalid");
    }
    if (!Set.of("INTERNAL", "RESTRICTED", "SECRET").contains(text(manifest, "confidentiality"))) {
      invalid("confidentiality is invalid");
    }
    String timestamp = text(build, "build_timestamp");
    if (timestamp != null) {
      try {
        java.time.Instant.parse(timestamp);
      } catch (RuntimeException exception) {
        invalid("build timestamp is invalid");
      }
    }
  }

  private static void validateEnvironment(JsonNode manifest, boolean legacy) {
    JsonNode value = manifest.get("environment");
    if (value == null) {
      if (!legacy) invalid("environment is required");
      return;
    }
    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 120) {
      invalid("environment is invalid");
    }
    for (int index = 0; index < value.textValue().length(); index++) {
      if (Character.isISOControl(value.textValue().charAt(index))) {
        invalid("environment is invalid");
      }
    }
  }

  private static void validateEntryPoint(JsonNode manifest, Set<String> fileIds) {
    JsonNode entry = manifest.get("entry_point");
    validateKnownFields(entry, ENTRY_FIELDS, "entry point");
    if (entry == null || !entry.isObject() || text(entry, "class_name") == null
        || !fileIds.contains(text(entry, "file_id"))) {
      invalid("entry point must reference an allowlisted source file");
    }
    String symbolId = text(entry, "source_symbol_id");
    if (symbolId == null || !SourceManifestContract.SYMBOL_ID.matcher(symbolId).matches()) {
      invalid("entry point symbol identity is invalid");
    }
  }

  private static void validateEntrySymbol(
      JsonNode manifest, List<TaskSourceManifest.Symbol> symbols) {
    String entrySymbolId = text(manifest.get("entry_point"), "source_symbol_id");
    if (entrySymbolId == null) return;
    TaskSourceManifest.Symbol entry = symbols.stream()
        .filter(symbol -> entrySymbolId.equals(symbol.sourceSymbolId()))
        .findFirst().orElse(null);
    if (entry == null) invalid("entry point symbol is not indexed");
    String entryFileId = text(manifest.get("entry_point"), "file_id");
    if (!entryFileId.equals(entry.fileId())) invalid("entry point symbol file differs");
    String entryClass = text(manifest.get("entry_point"), "class_name");
    if (!entryClass.equals(entry.qualifiedName())
        && (entry.qualifiedName() == null || !entry.qualifiedName().startsWith(entryClass + "#"))) {
      invalid("entry point symbol differs from the entry class");
    }
  }

  private static SourceManifestFailureCode statusFailure(String status) {
    if (status == null || "AVAILABLE".equals(status)) return null;
    return switch (status) {
      case "REDACTED" -> SourceManifestFailureCode.SOURCE_REDACTED;
      case "PRUNED" -> SourceManifestFailureCode.SOURCE_PRUNED;
      case "GENERATED_ONLY" -> SourceManifestFailureCode.SOURCE_GENERATED_ONLY;
      case "UNAVAILABLE" -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
      case "INDEX_PARTIAL" -> SourceManifestFailureCode.SOURCE_INDEX_PARTIAL;
      default -> null;
    };
  }

  private static void compare(String expected, String actual, String label) {
    if (expected != null && !expected.isBlank() && !expected.equals(actual)) {
      throw new SourceManifestException(SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, "source " + label + " identity differs");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static boolean isPresentText(JsonNode node, String field) {
    String value = text(node, field);
    return value != null && !value.isBlank();
  }

  private static void validateSignatureShape(JsonNode signature) {
    validateKnownFields(signature, SIGNATURE_FIELDS, "signature");
    if (signature == null || !signature.isObject()
        || !"Ed25519".equals(text(signature, "algorithm"))
        || !isPresentText(signature, "key_id")
        || !isPresentText(signature, "value")
        || !SourceManifestContract.isHash(text(signature, "signed_bytes_hash"))) {
      invalid("source manifest signature is malformed");
    }
    try {
      java.util.Base64.getDecoder().decode(text(signature, "value"));
    } catch (IllegalArgumentException exception) {
      invalid("source manifest signature is malformed");
    }
  }

  private static void validateClosure(JsonNode manifest, Set<String> fileIds) {
    JsonNode closure = manifest.get("closure");
    if (closure == null || closure.isNull()) return;
    if (!closure.isArray() || closure.size() > SourceManifestContract.MAX_CLOSURE_EDGES) {
      invalid("source closure index is invalid");
    }
    for (JsonNode edge : closure) {
      if (edge == null || !edge.isObject()) invalid("source closure edge is invalid");
      validateClosureNode(edge, fileIds, 0);
    }
  }

  /** Closure edges are extensible, but values must stay bounded and path-safe. */
  private static void validateClosureNode(JsonNode node, Set<String> fileIds, int depth) {
    if (depth > 3) invalid("source closure edge is too deeply nested");
    node.fields().forEachRemaining(field -> {
      String name = field.getKey();
      JsonNode value = field.getValue();
      if (name.length() > 80 || value == null) invalid("source closure edge is invalid");
      if (value.isTextual()) {
        String text = value.textValue();
        if (text.length() > 1000 || text.indexOf('\u0000') >= 0) {
          invalid("source closure value is too long");
        }
        if (name.contains("path") && !SourceManifestContract.isSafeRelativePath(text)) {
          invalid("source closure path is invalid");
        }
        if (name.endsWith("file_id") && !fileIds.contains(text)) {
          invalid("source closure file identity is not allowlisted");
        }
        if (name.endsWith("symbol_id")
            && !SourceManifestContract.SYMBOL_ID.matcher(text).matches()) {
          invalid("source closure symbol identity is invalid");
        }
      } else if (value.isObject()) {
        validateClosureNode(value, fileIds, depth + 1);
      } else if (value.isArray()) {
        if (value.size() > 1000) invalid("source closure value is too large");
        for (JsonNode child : value) {
          if (child.isObject()) validateClosureNode(child, fileIds, depth + 1);
          else if (child.isTextual() && child.textValue().length() > 1000) {
            invalid("source closure value is too long");
          }
        }
      } else if (value.isNumber() && !value.canConvertToLong()) {
        invalid("source closure number is invalid");
      }
    });
  }

  private static void validateKnownFields(JsonNode node, Set<String> allowed, String label) {
    if (node == null || !node.isObject()) {
      invalid(label + " is invalid");
    }
    java.util.Iterator<String> fields = node.fieldNames();
    while (fields.hasNext()) {
      if (!allowed.contains(fields.next())) invalid(label + " contains an unsupported field");
    }
  }

  private static String textObject(JsonNode node, String object, String field) {
    JsonNode value = node == null ? null : node.get(object);
    return text(value, field) == null && "repository".equals(object) && "repository_id".equals(field)
        ? text(value, "id") : text(value, field);
  }

  private static int number(JsonNode node, String field, int fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.intValue() : fallback;
  }

  private static long number(JsonNode node, String field, long fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToLong()
        ? value.longValue() : fallback;
  }

  private static boolean booleanValue(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isBoolean() && value.booleanValue();
  }

  private static String hash(byte[] bytes) {
    byte[] digest = CanonicalJsonHasher.digest(bytes);
    StringBuilder result = new StringBuilder("sha256:");
    for (byte value : digest) result.append(String.format("%02x", value));
    return result.toString();
  }

  private static void invalid(String message) {
    throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, message);
  }

  private static void unavailable(SourceManifestFailureCode code) {
    throw new SourceManifestException(
        code, org.springframework.http.HttpStatus.NOT_FOUND, "source is not available");
  }

  public record ExpectedIdentity(
      String taskKey,
      String repositoryId,
      String commitId,
      String treeId,
      String buildId,
      String artifactDigest,
      String snapshotId,
      String manifestHash) {}

  public record Verified(
      JsonNode rawManifest,
      TaskSourceManifest manifest,
      boolean legacy,
      String computedManifestHash,
      boolean signatureVerified) {
    /** Compatibility constructor for callers that predate the signature state field. */
    public Verified(JsonNode rawManifest, TaskSourceManifest manifest, boolean legacy,
        String computedManifestHash) {
      this(rawManifest, manifest, legacy, computedManifestHash, false);
    }
  }
}
