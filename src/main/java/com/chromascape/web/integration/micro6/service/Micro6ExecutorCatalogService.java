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
    if (!forceRefresh && cache.get() != null && Instant.now().isBefore(lastRefresh.plusSeconds(ttl))) {
      return cache.get();
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.getExecutorCatalog().getBaseUrl() + "/api/v1/catalog"))
              .timeout(Duration.ofMillis(config.getExecutorCatalog().getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
      return objectMapper.valueToTree(
          Map.of("enabled", true, "error", e.getMessage(), "tasks", java.util.List.of()));
    }
  }
}
