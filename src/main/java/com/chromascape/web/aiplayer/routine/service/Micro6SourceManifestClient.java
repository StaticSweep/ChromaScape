package com.chromascape.web.aiplayer.routine.service;

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
      JsonNode file = manifest.path("files").findValue("relative_path");
      String fileId = null;
      String relativePath = null;
      for (JsonNode entry : manifest.path("files")) {
        if ("runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/builderPlanStep/fishing/FishingExecutionTask.java".equals(entry.path("relative_path").asText())) {
          fileId = entry.path("file_id").asText();
          relativePath = entry.path("relative_path").asText();
          break;
        }
      }
      if (snapshot == null || fileId == null || relativePath == null) throw new IllegalStateException("SOURCE_FILE_NOT_FOUND");
      String source = file(snapshot, fileId).replace("\r\n", "\n").replace("\r", "\n");
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
      return new SourceBinding(buildId, snapshot, manifestHash, fileId, relativePath, start + 1, end, preimage, source);
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
  private static String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64); for (byte b : bytes) result.append(String.format("%02x", b));
      return "sha256:" + result;
    } catch (Exception e) { throw new IllegalStateException("SOURCE_HASH_FAILED", e); }
  }
  public record SourceBinding(String clientBuildId, String sourceSnapshotId, String sourceManifestHash, String fileId, String relativePath, int startLine, int endLine, String preimageHash, String source) {}
}
