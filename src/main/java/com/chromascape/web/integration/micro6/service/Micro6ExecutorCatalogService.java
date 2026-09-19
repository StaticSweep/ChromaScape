package com.chromascape.web.integration.micro6.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-through access to the Micro6 catalog used by the stabilization API. */
@Service
public class Micro6ExecutorCatalogService {
  private final URI catalogUri;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final AtomicReference<JsonNode> cached = new AtomicReference<>();

  public Micro6ExecutorCatalogService(
      @Value("${micro6.executor-catalog-url:http://127.0.0.1:8098/api/v1/catalog}") String url,
      ObjectMapper objectMapper) {
    this.catalogUri = URI.create(url);
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  public JsonNode getCatalog(boolean refresh) {
    JsonNode existing = cached.get();
    if (!refresh && existing != null) {
      return existing.deepCopy();
    }
    HttpRequest request = HttpRequest.newBuilder(catalogUri).timeout(Duration.ofSeconds(5)).GET().build();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Micro6 catalog returned HTTP " + response.statusCode());
      }
      JsonNode catalog = objectMapper.readTree(response.body());
      cached.set(catalog.deepCopy());
      return catalog;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read Micro6 catalog", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading Micro6 catalog", exception);
    }
  }
}
