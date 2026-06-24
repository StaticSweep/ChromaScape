package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2PatchDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class Micro6TaskSourcePatchService {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String micro6BaseUrl;

  public Micro6TaskSourcePatchService(
      ObjectMapper objectMapper,
      @Value("${micro6.executor-catalog.base-url:http://127.0.0.1:8098}") String micro6BaseUrl) {
    this.objectMapper = objectMapper;
    this.micro6BaseUrl = micro6BaseUrl;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public PatchApplyResult applyPatch(
      String taskKey, String sourceRunId, AnalysisV2PatchDraft patchDraft) throws Exception {
    return applyPatch(micro6BaseUrl, taskKey, sourceRunId, patchDraft);
  }

  public PatchApplyResult applyPatch(
      String targetBaseUrl, String taskKey, String sourceRunId, AnalysisV2PatchDraft patchDraft)
      throws Exception {
    if (taskKey == null || taskKey.isBlank()) {
      throw new IllegalArgumentException("taskKey is required");
    }
    if (patchDraft == null) {
      throw new IllegalArgumentException("patchDraft is required");
    }
    String resolvedBaseUrl = normalizeBaseUrl(targetBaseUrl);
    if (resolvedBaseUrl == null) {
      throw new IllegalArgumentException("Micro6 target base URL is required");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source_run_entry_id", sourceRunId);
    body.put("patch_id", patchDraft.getPatchId());
    body.put("description", patchDraft.getDescription());
    body.put("affected_files", patchDraft.getAffectedFiles());
    body.put("changes", patchDraft.getChanges());
    body.put("rationale", patchDraft.getRationale());
    body.put("dry_run", false);

    String encodedTaskKey = URLEncoder.encode(taskKey, StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(resolvedBaseUrl + "/api/v1/tasks/" + encodedTaskKey + "/apply-patch"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(
          "Micro6 patch apply failed (HTTP "
              + response.statusCode()
              + "): "
              + response.body());
    }

    JsonNode parsed = objectMapper.readTree(response.body());
    boolean success = parsed.path("success").asBoolean(false);
    if (!success) {
      throw new IllegalStateException(
          parsed.path("message").asText("Micro6 task patch apply failed"));
    }

    return new PatchApplyResult(
        parsed.path("task_key").asText(taskKey),
        parsed.path("revision_id").asText(null),
        parsed.path("applied_change_count").asInt(0),
        parsed.path("applied_file_count").asInt(0),
        parsed.path("message").asText("Patch applied"));
  }

  public record PatchApplyResult(
      String taskKey,
      String revisionId,
      int appliedChangeCount,
      int appliedFileCount,
      String message) {}

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.isBlank() ? null : normalized;
  }
}
