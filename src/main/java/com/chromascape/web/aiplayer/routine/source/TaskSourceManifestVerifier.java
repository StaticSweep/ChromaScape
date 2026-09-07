package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict verifier for the authoritative {@code task-source-manifest/v1} contract.
 *
 * <p>The public surface of this class accepts only signed v1 documents. Every v1 document must
 * carry a valid Ed25519 signature; there is no configuration switch that can weaken that rule. A
 * caller may use {@link #verify(JsonNode)} when loading a detached manifest, or {@link
 * #verify(JsonNode, ExpectedIdentity)} when binding it to an attested run.
 */
public final class TaskSourceManifestVerifier {
  private static final Pattern STATUS =
      Pattern.compile("AVAILABLE|INDEX_PARTIAL|UNAVAILABLE|REDACTED|PRUNED|GENERATED_ONLY");
  private static final Pattern CLASSIFICATION =
      Pattern.compile("SOURCE|GENERATED|TRANSFORMED|VENDOR|TEST|UNRELATED|SENSITIVE|EXCLUDED");
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");
  private static final Pattern SAFE_ENVIRONMENT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final SourceManifestSignatureVerifier signatureVerifier;

  /** Creates an unusable verifier; all manifests fail signature verification until trust is set. */
  public TaskSourceManifestVerifier() {
    this(null);
  }

  public TaskSourceManifestVerifier(SourceManifestSignatureVerifier signatureVerifier) {
    this.signatureVerifier = signatureVerifier;
  }

  /** Verifies a complete v1 document without binding it to a launch identity. */
  public Verified verify(JsonNode manifest) {
    try {
      if (manifest == null || !manifest.isObject()) {
        invalid("source manifest must be a JSON object");
      }

      // The schema version is the only field inspected before the schema gate.  This preserves a
      // distinct unsupported-schema failure while ensuring all shape/unknown-property checks run
      // before any semantic or cryptographic interpretation.
      String schema = text(manifest, "schema");
      if (!SourceManifestContract.SCHEMA.equals(schema)) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_SCHEMA_UNSUPPORTED,
            "source manifest schema is unsupported");
      }
      // Keep a missing signature distinguishable from a generic schema failure.  The schema still
      // requires the complete signature object, but the stable failure code for an unsigned v1
      // publication is part of the trust boundary and is useful to callers deciding whether a
      // producer is merely unavailable or has emitted unauthenticated data.
      if (!manifest.has("signature")) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
            "source manifest signature is missing");
      }
      SourceManifestSchemaValidator.validate(manifest);

      validateRootSemantics(manifest);
      String manifestHash = SourceManifestContract.declaredManifestHash(manifest);
      String computedHash = hash(SourceManifestContract.canonicalHashBytes(manifest));
      if (!computedHash.equals(manifestHash)
          || !SourceManifestContract.snapshotForHash(manifestHash)
              .equals(SourceManifestContract.declaredSnapshotId(manifest))) {
        invalid("source manifest identity does not match its canonical bytes");
      }

      // Signature verification is mandatory even for terminal statuses.  An unsigned terminal
      // response could otherwise forge REDACTED/PRUNED and conceal a still-readable snapshot.
      validateSignature(manifest);
      if (signatureVerifier == null || !signatureVerifier.verify(manifest)) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
            "source manifest signature is invalid");
      }

      String status = text(manifest, "status");
      validateRepositoryAndBuild(manifest);
      List<TaskSourceManifest.File> files = parseFiles(manifest);
      Map<String, TaskSourceManifest.File> filesById = new HashMap<>();
      files.forEach(file -> filesById.put(file.fileId(), file));
      validateClosure(manifest, filesById);
      List<TaskSourceManifest.Symbol> symbols = parseSymbols(manifest, filesById);
      List<TaskSourceManifest.GeneratedMapping> generatedMappings =
          parseGeneratedMappings(manifest, filesById);
      validateEntryPoint(manifest, filesById, symbols);
      validateStatusInvariants(manifest, status, filesById);

      return new Verified(
          manifest.deepCopy(),
          new TaskSourceManifest(manifest, files, symbols, generatedMappings),
          computedHash,
          true);
    } catch (SourceManifestException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest could not be verified",
          exception);
    }
  }

  /** Verifies a manifest and requires every supplied launch identity to match exactly. */
  public Verified verify(JsonNode manifest, ExpectedIdentity expected) {
    Verified verified = verify(manifest);
    if (expected == null) return verified;

    compareRequired(expected.taskKey(), verified.manifest().taskKey(), "task");
    JsonNode repository = manifest.get("repository");
    JsonNode build = manifest.get("build");
    compareRequired(expected.repositoryId(), text(repository, "repository_id"), "repository");
    compareRequired(expected.commitId(), text(repository, "commit_id"), "commit");
    compareRequired(expected.treeId(), text(repository, "tree_id"), "tree");
    compareRequired(expected.buildId(), text(build, "build_id"), "build");
    compareRequired(expected.artifactDigest(), text(build, "artifact_digest"), "artifact");
    compareRequired(expected.snapshotId(), verified.manifest().sourceSnapshotId(), "snapshot");
    compareRequired(expected.manifestHash(), verified.manifest().manifestHash(), "manifest");
    return verified;
  }

  private static void validateRootSemantics(JsonNode manifest) {
    String taskKey = text(manifest, "task_key");
    if (!SourceManifestContract.isTaskKey(taskKey)) invalid("task key is invalid");
    requiredText(manifest, "task_family", 80);
    requiredText(manifest, "catalog_version", 80);
    requiredText(manifest, "settings_schema_version", 80);
    requiredText(manifest, "parser_version", 80);
    requiredText(manifest, "indexer_version", 80);
    requiredText(manifest, "environment", 64);
    String environment = text(manifest, "environment");
    if (!SAFE_ENVIRONMENT.matcher(environment).matches()) {
      invalid("source environment is invalid");
    }
    String status = text(manifest, "status");
    if (status == null || !STATUS.matcher(status).matches()) invalid("source status is invalid");
    if (text(manifest, "retention_class") == null || text(manifest, "confidentiality") == null) {
      invalid("source retention metadata is required");
    }
    JsonNode files = manifest.get("files");
    JsonNode symbols = manifest.get("symbols");
    if (files == null
        || !files.isArray()
        || files.isEmpty()
        || files.size() > SourceManifestContract.MAX_FILES) {
      invalid("source file index is invalid");
    }
    if (symbols == null
        || !symbols.isArray()
        || symbols.isEmpty()
        || symbols.size() > SourceManifestContract.MAX_SYMBOLS) {
      invalid("source symbol index is invalid");
    }
    JsonNode generatedMappings = manifest.get("generated_mappings");
    if (generatedMappings == null
        || !generatedMappings.isArray()
        || generatedMappings.size() > SourceManifestContract.MAX_GENERATED_MAPPINGS) {
      invalid("source generated mapping index is invalid");
    }
  }

  private static void validateRepositoryAndBuild(JsonNode manifest) {
    JsonNode repository = manifest.get("repository");
    JsonNode build = manifest.get("build");
    if (repository == null || !repository.isObject() || build == null || !build.isObject()) {
      invalid("repository and build identities are required");
    }
    String repositoryId = requiredText(repository, "repository_id", 200);
    if (!SAFE_ID.matcher(repositoryId).matches()) {
      invalid("repository identity is not a logical identifier");
    }
    String commitId = text(repository, "commit_id");
    String treeId = text(repository, "tree_id");
    if (!SourceManifestContract.isGitObjectId(commitId)
        || !SourceManifestContract.isGitObjectId(treeId)) {
      invalid("repository commit/tree identity is invalid");
    }
    JsonNode clean = repository.get("clean");
    if (clean == null || !clean.isBoolean()) invalid("repository clean state is invalid");
    if (!clean.booleanValue()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_DIRTY_TREE, "source repository is not clean");
    }
    String buildId = requiredText(build, "build_id", 200);
    if (!SAFE_ID.matcher(buildId).matches()) invalid("build identity is invalid");
    String artifactDigest = text(build, "artifact_digest");
    if (!SourceManifestContract.isHash(artifactDigest)) {
      invalid("launched artifact digest is invalid");
    }
    String timestamp = text(build, "build_timestamp");
    if (timestamp != null) {
      try {
        Instant.parse(timestamp);
      } catch (RuntimeException exception) {
        invalid("build timestamp is invalid");
      }
    }
  }

  private static List<TaskSourceManifest.File> parseFiles(JsonNode manifest) {
    List<TaskSourceManifest.File> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<String> paths = new HashSet<>();
    long totalBytes = 0L;
    for (JsonNode file : manifest.get("files")) {
      if (file == null || !file.isObject()) invalid("source file entry is invalid");
      String id = text(file, "file_id");
      String path = text(file, "relative_path");
      String contentHash = text(file, "content_hash");
      long size = integral(file, "size_bytes", -1L);
      if (id == null || !SourceManifestContract.FILE_ID.matcher(id).matches() || !ids.add(id))
        invalid("source file identity is invalid");
      if (!SourceManifestContract.isSafeRelativePath(path) || !paths.add(path)) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_UNSAFE_PATH, "source file path is unsafe");
      }
      if (!SourceManifestContract.isHash(contentHash)
          || size < 0
          || size > SourceManifestContract.MAX_FILE_BYTES) {
        invalid("source file content metadata is invalid");
      }
      String expectedFileId = SourceManifestContract.fileId(path, contentHash);
      if (!id.equals(expectedFileId)) invalid("source file identity is not deterministic");
      String module = text(file, "module");
      if (module != null && !module.isBlank() && !SourceManifestContract.isSafeModule(module)) {
        invalid("source module identity is invalid");
      }
      String language = text(file, "language");
      if (language != null && language.length() > 40) invalid("source language is invalid");
      String classification = text(file, "classification");
      if (classification == null || !CLASSIFICATION.matcher(classification).matches()) {
        invalid("source classification is invalid");
      }
      String reason = text(file, "inclusion_reason");
      if (reason == null || reason.isBlank() || reason.length() > 1000) {
        invalid("source inclusion reason is invalid");
      }
      try {
        totalBytes = Math.addExact(totalBytes, size);
      } catch (ArithmeticException exception) {
        invalid("source closure byte count overflowed");
      }
      if (totalBytes > SourceManifestContract.MAX_CLOSURE_BYTES) {
        invalid("source closure is too large");
      }
      result.add(
          new TaskSourceManifest.File(
              id, path, module, language, contentHash, size, classification, reason));
    }
    return result;
  }

  private static void validateClosure(
      JsonNode manifest, Map<String, TaskSourceManifest.File> filesById) {
    JsonNode closure = manifest.get("closure");
    if (closure == null || closure.isNull()) return;
    if (!closure.isArray() || closure.size() > SourceManifestContract.MAX_CLOSURE_EDGES) {
      invalid("source closure index is invalid");
    }
    Set<String> edges = new HashSet<>();
    for (JsonNode edge : closure) {
      if (edge == null || !edge.isObject()) invalid("source closure edge is invalid");
      String from = text(edge, "from_path");
      String to = text(edge, "to_path");
      if (!SourceManifestContract.isSafeRelativePath(from)
          || !SourceManifestContract.isSafeRelativePath(to)) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_UNSAFE_PATH, "source closure path is unsafe");
      }
      if (filesById.values().stream().noneMatch(file -> from.equals(file.relativePath()))
          || filesById.values().stream().noneMatch(file -> to.equals(file.relativePath()))) {
        invalid("source closure references a file outside the file index");
      }
      String kind = text(edge, "edge_kind");
      String reason = text(edge, "reason");
      if (kind == null
          || kind.isBlank()
          || kind.length() > 40
          || reason == null
          || reason.isBlank()
          || reason.length() > 1000) {
        invalid("source closure edge metadata is invalid");
      }
      if (!edges.add(from + "\u0000" + to + "\u0000" + kind + "\u0000" + reason)) {
        invalid("source closure contains duplicate edges");
      }
    }
  }

  private static List<TaskSourceManifest.Symbol> parseSymbols(
      JsonNode manifest, Map<String, TaskSourceManifest.File> filesById) {
    List<TaskSourceManifest.Symbol> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    JsonNode symbolsNode = manifest.get("symbols");
    if (symbolsNode == null || symbolsNode.isNull()) return result;
    if (!symbolsNode.isArray() || symbolsNode.size() > SourceManifestContract.MAX_SYMBOLS) {
      invalid("source symbol index is invalid");
    }
    for (JsonNode symbol : symbolsNode) {
      if (symbol == null || !symbol.isObject()) invalid("source symbol entry is invalid");
      String id = text(symbol, "source_symbol_id");
      String fileId = text(symbol, "file_id");
      String kind = text(symbol, "kind");
      String qualifiedName = text(symbol, "qualified_name");
      String signature = text(symbol, "signature");
      int startByte = integralInt(symbol, "start_byte", -1);
      int endByte = integralInt(symbol, "end_byte", -1);
      int startLine = integralInt(symbol, "start_line", -1);
      int endLine = integralInt(symbol, "end_line", -1);
      int startColumn = integralInt(symbol, "start_column", -1);
      int endColumn = integralInt(symbol, "end_column", -1);
      TaskSourceManifest.File file = filesById.get(fileId);
      if (id == null
          || !SourceManifestContract.SYMBOL_ID.matcher(id).matches()
          || !ids.add(id)
          || file == null
          || kind == null
          || kind.isBlank()
          || kind.length() > 40
          || qualifiedName == null
          || qualifiedName.isBlank()
          || qualifiedName.length() > 500
          || signature != null && signature.length() > 1000
          || startByte < 0
          || endByte <= startByte
          || endByte > file.sizeBytes()
          || startLine < 1
          || endLine < startLine
          || startColumn < 1
          || endColumn < 1) {
        invalid("source symbol range or identity is invalid");
      }
      String expectedId =
          SourceManifestContract.symbolId(
              file.relativePath(), kind, qualifiedName, signature, startByte, endByte);
      if (!id.equals(expectedId)) invalid("source symbol identity is not deterministic");
      String enclosing = text(symbol, "enclosing_symbol_id");
      if (enclosing != null && !SourceManifestContract.SYMBOL_ID.matcher(enclosing).matches()) {
        invalid("source enclosing symbol identity is invalid");
      }
      JsonNode generatedNode = symbol.get("generated");
      if (generatedNode != null && !generatedNode.isBoolean()) {
        invalid("source generated flag is invalid");
      }
      result.add(
          new TaskSourceManifest.Symbol(
              id,
              fileId,
              kind,
              qualifiedName,
              signature,
              enclosing,
              startByte,
              endByte,
              startLine,
              endLine,
              startColumn,
              endColumn,
              generatedNode != null && generatedNode.booleanValue()));
    }
    for (TaskSourceManifest.Symbol symbol : result) {
      if (symbol.enclosingSymbolId() != null && !ids.contains(symbol.enclosingSymbolId())) {
        invalid("source enclosing symbol is not indexed");
      }
      if (symbol.sourceSymbolId().equals(symbol.enclosingSymbolId())) {
        invalid("source symbol cannot enclose itself");
      }
    }
    return result;
  }

  private static List<TaskSourceManifest.GeneratedMapping> parseGeneratedMappings(
      JsonNode manifest, Map<String, TaskSourceManifest.File> filesById) {
    List<TaskSourceManifest.GeneratedMapping> result = new ArrayList<>();
    Set<String> identities = new HashSet<>();
    for (JsonNode mapping : manifest.get("generated_mappings")) {
      if (mapping == null || !mapping.isObject()) invalid("source generated mapping is invalid");
      String generatedFileId = text(mapping, "generated_file_id");
      TaskSourceManifest.File generated = filesById.get(generatedFileId);
      String mappingKind = text(mapping, "mapping_kind");
      String tool = requiredText(mapping, "tool", 200);
      String toolVersion = requiredText(mapping, "tool_version", 80);
      String description = requiredText(mapping, "description", 1000);
      JsonNode authoredNode = mapping.get("authored_file_ids");
      if (generated == null
          || !("GENERATED".equals(generated.classification())
              || "TRANSFORMED".equals(generated.classification()))
          || !("GENERATED".equals(mappingKind) || "TRANSFORMED".equals(mappingKind))
          || authoredNode == null
          || !authoredNode.isArray()
          || authoredNode.isEmpty()
          || authoredNode.size() > SourceManifestContract.MAX_FILES) {
        invalid("source generated mapping identity is invalid");
      }
      List<String> authored = new ArrayList<>();
      Set<String> authoredSet = new HashSet<>();
      for (JsonNode authoredNodeValue : authoredNode) {
        String authoredFileId =
            authoredNodeValue != null && authoredNodeValue.isTextual()
                ? authoredNodeValue.textValue()
                : null;
        TaskSourceManifest.File authoredFile = filesById.get(authoredFileId);
        if (authoredFile == null
            || !"SOURCE".equals(authoredFile.classification())
            || !authoredSet.add(authoredFileId)) {
          invalid("source generated mapping references invalid authored files");
        }
        authored.add(authoredFileId);
      }
      String identity =
          generatedFileId
              + "\u0000"
              + String.join("\u0000", authored)
              + "\u0000"
              + mappingKind
              + "\u0000"
              + tool
              + "\u0000"
              + toolVersion
              + "\u0000"
              + description;
      if (!identities.add(identity)) invalid("source generated mapping is duplicated");
      result.add(
          new TaskSourceManifest.GeneratedMapping(
              generatedFileId, authored, mappingKind, tool, toolVersion, description));
    }
    return result;
  }

  private static void validateEntryPoint(
      JsonNode manifest,
      Map<String, TaskSourceManifest.File> filesById,
      List<TaskSourceManifest.Symbol> symbols) {
    JsonNode entry = manifest.get("entry_point");
    String className = text(entry, "class_name");
    String fileId = text(entry, "file_id");
    String symbolId = text(entry, "source_symbol_id");
    if (className == null
        || className.isBlank()
        || fileId == null
        || !filesById.containsKey(fileId)
        || symbolId == null
        || !SourceManifestContract.SYMBOL_ID.matcher(symbolId).matches()) {
      invalid("source entry point is invalid");
    }
    TaskSourceManifest.Symbol entrySymbol =
        symbols.stream()
            .filter(symbol -> symbolId.equals(symbol.sourceSymbolId()))
            .findFirst()
            .orElse(null);
    if (entrySymbol == null
        || !fileId.equals(entrySymbol.fileId())
        || !(className.equals(entrySymbol.qualifiedName())
            || entrySymbol.qualifiedName().startsWith(className + "#"))) {
      invalid("source entry point symbol is not indexed");
    }
  }

  private static void validateStatusInvariants(
      JsonNode manifest, String status, Map<String, TaskSourceManifest.File> filesById) {
    TaskSourceManifest.File entryFile = filesById.get(text(manifest.get("entry_point"), "file_id"));
    if (entryFile == null) invalid("source entry file is missing");
    if ("AVAILABLE".equals(status)) {
      if (!Boolean.TRUE.equals(manifest.path("repository").path("clean").booleanValue())) {
        throw new SourceManifestException(
            SourceManifestFailureCode.SOURCE_DIRTY_TREE, "source repository is not clean");
      }
      if (!"SOURCE".equals(entryFile.classification())) {
        invalid("available source entry must be authored SOURCE");
      }
    } else {
      SourceManifestFailureCode failure =
          switch (status) {
            case "INDEX_PARTIAL" -> SourceManifestFailureCode.SOURCE_INDEX_PARTIAL;
            case "UNAVAILABLE" -> SourceManifestFailureCode.SOURCE_UNAVAILABLE;
            case "REDACTED" -> SourceManifestFailureCode.SOURCE_REDACTED;
            case "PRUNED" -> SourceManifestFailureCode.SOURCE_PRUNED;
            case "GENERATED_ONLY" -> SourceManifestFailureCode.SOURCE_GENERATED_ONLY;
            default -> null;
          };
      if (failure != null) {
        // Non-AVAILABLE status is a valid authenticated publication.  It is represented in
        // Verified so callers that need the terminal state can inspect it; content reads decide
        // whether the status is usable.
      }
    }
  }

  private static void validateSignature(JsonNode manifest) {
    JsonNode signature = manifest.get("signature");
    if (signature == null
        || !signature.isObject()
        || !"Ed25519".equals(text(signature, "algorithm"))
        || text(signature, "key_id") == null
        || text(signature, "key_id").isBlank()
        || text(signature, "value") == null
        || text(signature, "value").isBlank()
        || !SourceManifestContract.isHash(text(signature, "signed_bytes_hash"))) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest signature is malformed");
    }
    String keyId = text(signature, "key_id");
    if (!SAFE_ID.matcher(keyId).matches()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest signature key identity is invalid");
    }
    try {
      byte[] value = java.util.Base64.getDecoder().decode(text(signature, "value"));
      if (value.length != 64) throw new IllegalArgumentException("signature length");
    } catch (IllegalArgumentException exception) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest signature is malformed",
          exception);
    }
    String expectedSignedBytesHash = hash(SourceManifestContract.canonicalSignatureBytes(manifest));
    if (!expectedSignedBytesHash.equals(text(signature, "signed_bytes_hash"))) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_SIGNATURE_INVALID,
          "source manifest signed bytes identity is invalid");
    }
  }

  private static String requiredText(JsonNode node, String field, int maxLength) {
    String value = text(node, field);
    if (value == null || value.isBlank() || value.length() > maxLength) {
      invalid("source " + field + " is required");
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.isISOControl(value.charAt(index))) invalid("source " + field + " is invalid");
    }
    return value;
  }

  private static void compareRequired(String expected, String actual, String label) {
    if (expected == null || expected.isBlank() || actual == null || !expected.equals(actual)) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_BUILD_MISMATCH, "source " + label + " identity differs");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static long integral(JsonNode node, String field, long fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToLong()
        ? value.longValue()
        : fallback;
  }

  private static int integralInt(JsonNode node, String field, int fallback) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.intValue()
        : fallback;
  }

  private static String hash(byte[] bytes) {
    byte[] digest = SourceManifestCanonicalJson.digest(bytes);
    StringBuilder result = new StringBuilder("sha256:");
    for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
    return result.toString();
  }

  private static void invalid(String message) {
    throw new SourceManifestException(SourceManifestFailureCode.SOURCE_MANIFEST_INVALID, message);
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
      String computedManifestHash,
      boolean signatureVerified) {}
}
