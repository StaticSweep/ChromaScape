package com.chromascape.web.integration.micro6.service;

import com.chromascape.web.integration.micro6.config.Micro6Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Fetches and caches Micro6 local executor task catalog from /api/v1/catalog. */
@Service
public class Micro6ExecutorCatalogService {
  private static final Logger log = LoggerFactory.getLogger(Micro6ExecutorCatalogService.class);

  private final Micro6Config config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final AtomicReference<JsonNode> cache = new AtomicReference<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  public Micro6ExecutorCatalogService(Micro6Config config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public JsonNode getCatalog(boolean forceRefresh) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", java.util.List.of()));
    }
    long ttl = Math.max(1, config.getExecutorCatalog().getRefreshSeconds());
    if (!forceRefresh
        && cache.get() != null
        && Instant.now().isBefore(lastRefresh.plusSeconds(ttl))) {
      return cache.get();
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getExecutorCatalog().getBaseUrl() + "/api/v1/catalog"))
              .timeout(Duration.ofMillis(config.getExecutorCatalog().getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Catalog fetch failed: HTTP " + response.statusCode());
      }
      JsonNode parsed = objectMapper.readTree(response.body());
      cache.set(parsed);
      lastRefresh = Instant.now();
      return parsed;
    } catch (Exception e) {
      log.warn("Failed to fetch Micro6 catalog: {}", e.getMessage());
      if (cache.get() != null) {
        return cache.get();
      }
      String errorMessage =
          e.getMessage() != null && !e.getMessage().isBlank()
              ? e.getMessage()
              : "Micro6 catalog unavailable";
      return objectMapper.valueToTree(
          Map.of("enabled", true, "error", errorMessage, "tasks", java.util.List.of()));
    }
  }

  public JsonNode getTaskConfig(String taskKey, String displayName) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("success", false, "error", "executor_catalog_disabled"));
    }

    try {
      StringBuilder url = new StringBuilder(config.getExecutorCatalog().getBaseUrl() + "/api/v1/config");
      String sep = "?";
      if (taskKey != null && !taskKey.isBlank()) {
        url.append(sep)
            .append("task_key=")
            .append(URLEncoder.encode(taskKey, StandardCharsets.UTF_8));
        sep = "&";
      }
      if (displayName != null && !displayName.isBlank()) {
        url.append(sep)
            .append("display_name=")
            .append(URLEncoder.encode(displayName, StandardCharsets.UTF_8));
      }

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url.toString()))
              .timeout(Duration.ofMillis(config.getExecutorCatalog().getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Config fetch failed: HTTP " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (Exception e) {
      log.warn("Failed to fetch Micro6 task config: {}", e.getMessage());
      String errorMessage =
          e.getMessage() != null && !e.getMessage().isBlank()
              ? e.getMessage()
              : "Micro6 task config unavailable";
      return objectMapper.valueToTree(Map.of("success", false, "error", errorMessage));
    }
  }
}
