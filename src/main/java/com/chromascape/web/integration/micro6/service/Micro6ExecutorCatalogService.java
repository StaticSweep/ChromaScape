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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
  private final ConcurrentHashMap<String, CachedCatalog> cacheByBaseUrl = new ConcurrentHashMap<>();
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
    return getCatalogForBaseUrl(config.getExecutorCatalog().getBaseUrl(), forceRefresh);
  }

  public JsonNode peekCatalog() {
    return peekCatalogForBaseUrl(config.getExecutorCatalog().getBaseUrl());
  }

  public JsonNode peekCatalogForBaseUrl(String baseUrl) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", java.util.List.of()));
    }
    String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
    if (normalizedBaseUrl == null) {
      return null;
    }
    if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
      return cache.get();
    }
    CachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
    return cached != null ? cached.payload() : null;
  }

  public JsonNode getCatalogForBaseUrl(String baseUrl, boolean forceRefresh) {
    if (!config.getExecutorCatalog().isEnabled()) {
      return objectMapper.valueToTree(Map.of("enabled", false, "tasks", java.util.List.of()));
    }
    long ttl = Math.max(1, config.getExecutorCatalog().getRefreshSeconds());
    String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
    if (normalizedBaseUrl == null) {
      return objectMapper.valueToTree(
          Map.of("enabled", true, "error", "Missing Micro6 executor base URL", "tasks", java.util.List.of()));
    }

    if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
      if (!forceRefresh
          && cache.get() != null
          && Instant.now().isBefore(lastRefresh.plusSeconds(ttl))) {
        return cache.get();
      }
    } else {
      CachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
      if (!forceRefresh
          && cached != null
          && Instant.now().isBefore(cached.refreshedAt().plusSeconds(ttl))) {
        return cached.payload();
      }
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(normalizedBaseUrl + "/api/v1/catalog?mode=lightweight"))
              .timeout(Duration.ofMillis(config.getExecutorCatalog().getTimeoutMs()))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Catalog fetch failed: HTTP " + response.statusCode());
      }
      JsonNode parsed = objectMapper.readTree(response.body());
      Instant refreshedAt = Instant.now();
      if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))) {
        cache.set(parsed);
        lastRefresh = refreshedAt;
      } else {
        cacheByBaseUrl.put(normalizedBaseUrl, new CachedCatalog(parsed, refreshedAt));
      }
      return parsed;
    } catch (Exception e) {
      log.warn("Failed to fetch Micro6 catalog from {}: {}", normalizedBaseUrl, e.getMessage());
      if (normalizedBaseUrl.equals(normalizeBaseUrl(config.getExecutorCatalog().getBaseUrl()))
          && cache.get() != null) {
        return cache.get();
      }
      CachedCatalog cached = cacheByBaseUrl.get(normalizedBaseUrl);
      if (cached != null) {
        return cached.payload();
      }
      Map<String, Object> fallback = new LinkedHashMap<>();
      fallback.put("enabled", true);
      fallback.put("baseUrl", normalizedBaseUrl);
      fallback.put("error", e.getMessage());
      fallback.put("tasks", java.util.List.of());
      return objectMapper.valueToTree(fallback);
    }
  }

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

  private record CachedCatalog(JsonNode payload, Instant refreshedAt) {}
}
