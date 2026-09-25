package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable typed view over a validated task-source manifest. */
public final class TaskSourceManifest {
  private final JsonNode document;
  private final List<File> files;
  private final Map<String, File> byId;
  private final List<Symbol> symbols;
  private final List<GeneratedMapping> generatedMappings;

  TaskSourceManifest(
      JsonNode document,
      List<File> files,
      List<Symbol> symbols,
      List<GeneratedMapping> generatedMappings) {
    this.document = document.deepCopy();
    this.files = List.copyOf(files);
    Map<String, File> ids = new LinkedHashMap<>();
    this.files.forEach(file -> ids.put(file.fileId(), file));
    this.byId = Collections.unmodifiableMap(ids);
    this.symbols = List.copyOf(symbols);
    this.generatedMappings = List.copyOf(generatedMappings);
  }

  public JsonNode document() {
    return document.deepCopy();
  }

  public String schema() {
    return text("schema");
  }

  public String taskKey() {
    return text("task_key");
  }

  public String taskFamily() {
    return text("task_family");
  }

  public String sourceSnapshotId() {
    return text("source_snapshot_id");
  }

  public String manifestHash() {
    return SourceManifestContract.declaredManifestHash(document);
  }

  public String retentionClass() {
    return text("retention_class");
  }

  public String confidentiality() {
    return text("confidentiality");
  }

  public String status() {
    return text("status");
  }

  public String text(String field) {
    JsonNode value = document.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  public List<File> files() {
    return files;
  }

  public Map<String, File> filesById() {
    return byId;
  }

  public List<Symbol> symbols() {
    return symbols;
  }

  public List<GeneratedMapping> generatedMappings() {
    return generatedMappings;
  }

  public File file(String fileId) {
    return byId.get(fileId);
  }

  public List<Symbol> symbolsForFile(String fileId) {
    return symbols.stream().filter(symbol -> fileId.equals(symbol.fileId())).toList();
  }

  public record File(
      String fileId,
      String relativePath,
      String module,
      String language,
      String contentHash,
      long sizeBytes,
      String classification,
      String inclusionReason) {}

  public record Symbol(
      String sourceSymbolId,
      String fileId,
      String kind,
      String qualifiedName,
      String signature,
      String enclosingSymbolId,
      int startByte,
      int endByte,
      int startLine,
      int endLine,
      int startColumn,
      int endColumn,
      boolean generated) {}

  public record GeneratedMapping(
      String generatedFileId,
      List<String> authoredFileIds,
      String mappingKind,
      String tool,
      String toolVersion,
      String description) {
    public GeneratedMapping {
      authoredFileIds = List.copyOf(authoredFileIds);
    }
  }
}
