package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.contract.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Micro6SourceManifestClient {
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper mapper;
  @Value("${micro6.executor-catalog.base-url:http://127.0.0.1:8098}") private String baseUrl;

  public Micro6SourceManifestClient(ObjectMapper mapper) { this.mapper = mapper; }

  public String manifest(String taskKey) { return get("/api/v1/source-manifests/" + encode(taskKey)); }
  public String file(String snapshotId, String fileId) { return get("/api/v1/source-snapshots/" + encode(snapshotId) + "/files/" + encode(fileId)); }

  /** Resolves the exact embedded source span; caller-supplied snapshot, file, lines, and preimage are ignored. */
  public SourceBinding resolveFishingSource() {
    try {
      JsonNode manifest = mapper.readTree(manifest("FISHING_TRAINING"));
      String snapshot = text(manifest, "source_snapshot_id");
      String manifestHash = text(manifest, "source_manifest_hash");
      String buildId = text(manifest, "client_build_id");
      String fileId = null;
      String relativePath = null;
      String selectedContentHash = null;
      int selectedCount = 0;
      for (JsonNode entry : manifest.path("files")) {
        if ("src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java".equals(entry.path("relative_path").asText())) {
          selectedCount++;
          fileId = entry.path("file_id").asText();
          relativePath = entry.path("relative_path").asText();
          selectedContentHash = entry.path("content_hash").asText();
        }
      }
      if (snapshot == null || selectedCount != 1 || fileId == null || relativePath == null || selectedContentHash == null) throw new IllegalStateException("SOURCE_FILE_NOT_FOUND");
      if (!validManifestHash(manifest, manifestHash)) throw new IllegalStateException("SOURCE_MANIFEST_HASH_MISMATCH");
      String source = file(snapshot, fileId).replace("\r\n", "\n").replace("\r", "\n");
      if (!selectedContentHash.equals(sha256(source))) throw new IllegalStateException("SOURCE_FILE_HASH_MISMATCH");
      String[] lines = source.split("\\n", -1);
      int start = -1;
      for (int i = 0; i < lines.length; i++) if (lines[i].contains("private Fish getFishType(String fishName)")) { start = i; break; }
      if (start < 0) throw new IllegalStateException("SOURCE_SYMBOL_NOT_FOUND");
      int end = -1;
      for (int i = start + 1; i < lines.length; i++) {
        if (lines[i].startsWith("    @Override") || lines[i].startsWith("    public String getName")) { end = i; break; }
      }
      if (end < 0) throw new IllegalStateException("SOURCE_SYMBOL_END_NOT_FOUND");
      List<String> span = new ArrayList<>();
      for (int i = start; i < end; i++) span.add(lines[i]);
      String preimage = sha256(String.join("\n", span));
      Map<String, PinnedFile> pinnedFiles = new LinkedHashMap<>();
      for (JsonNode entry : manifest.path("files")) {
        String path = entry.path("relative_path").asText();
        String id = entry.path("file_id").asText();
        String hash = entry.path("content_hash").asText();
        long size = entry.path("size_bytes").asLong(-1);
        if (path.isBlank() || id.isBlank() || !hash.matches("sha256:[0-9a-f]{64}") || size < 0) throw new IllegalStateException("SOURCE_MANIFEST_ENTRY_INVALID");
        pinnedFiles.put(path, new PinnedFile(id, path, hash, size));
      }
      return new SourceBinding(buildId, snapshot, manifestHash, fileId, relativePath, "runelite-client/" + relativePath, selectedContentHash, start + 1, end, preimage, source, mapper.writeValueAsString(manifest), pinnedFiles);
    } catch (Exception e) {
      throw new IllegalStateException("SOURCE_SNAPSHOT_NOT_FOUND", e);
    }
  }

  private String get(String path) {
    try {
      HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException("SOURCE_FILE_NOT_FOUND");
      return response.body();
    } catch (Exception e) { throw new IllegalStateException("SOURCE_SNAPSHOT_NOT_FOUND", e); }
  }
  private static String text(JsonNode node, String name) { JsonNode value = node.get(name); return value == null || value.isNull() ? null : value.asText(); }
  private static String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static boolean validManifestHash(JsonNode manifest, String declared) {
    if (declared == null || !declared.matches("sha256:[0-9a-f]{64}")) return false;
    JsonNode copy = manifest.deepCopy();
    if (copy.isObject()) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("source_manifest_hash");
      ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("source_snapshot_id");
    }
    return CanonicalJsonHasher.hash(copy, null).equals(declared);
  }

  private static String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64); for (byte b : bytes) result.append(String.format("%02x", b));
      return "sha256:" + result;
    } catch (Exception e) { throw new IllegalStateException("SOURCE_HASH_FAILED", e); }
  }
  public record PinnedFile(String fileId, String relativePath, String contentHash, long sizeBytes) {}
  public record SourceBinding(String clientBuildId, String sourceSnapshotId, String sourceManifestHash, String fileId, String relativePath, String cloneRelativePath, String contentHash, int startLine, int endLine, String preimageHash, String source, String manifestJson, Map<String, PinnedFile> pinnedFiles) {}
}
