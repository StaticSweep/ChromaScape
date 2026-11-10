package com.chromascape.web.gameplay.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Read-only HTTP implementation of {@link BootstrapV2Wire}.
 *
 * <p>Only the three GET routes are ever called. A non-2xx response is a refusal, never a partial
 * value: the draft cannot be assembled from a catalog that did not attest, and substituting a
 * default would put an unverified value into an approved execution scope.
 */
@Component
public final class Micro6BootstrapV2Wire implements BootstrapV2Wire {
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final URI baseUri;
  private final String executorToken;
  private final Duration requestTimeout;

  @Autowired
  public Micro6BootstrapV2Wire(
      ObjectMapper objectMapper,
      @Value("${gameplay.bootstrap.micro6-v2-url:http://127.0.0.1:8098}") String baseUrl,
      @Value("${builder-goal.executor.auth-token:}") String executorToken,
      @Value("${gameplay.bootstrap.micro6-v2-request-timeout-ms:10000}") long requestTimeoutMs) {
    this(
        objectMapper,
        baseUrl,
        executorToken,
        Duration.ofMillis(Math.max(1000, requestTimeoutMs)),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.min(3000, Math.max(1000, requestTimeoutMs))))
            .build());
  }

  Micro6BootstrapV2Wire(
      ObjectMapper objectMapper,
      String baseUrl,
      String executorToken,
      Duration requestTimeout,
      HttpClient httpClient) {
    this.objectMapper = objectMapper;
    this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.executorToken = executorToken == null ? "" : executorToken;
    this.requestTimeout = requestTimeout;
    this.httpClient = httpClient;
  }

  @Override
  public JsonNode catalog() {
    return get("api/v2/gameplay/catalog");
  }

  @Override
  public JsonNode observe() {
    return get("api/v2/gameplay/observe");
  }

  @Override
  public JsonNode observation() {
    return get("api/v1/gameplay/observation");
  }

  private JsonNode get(String path) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(baseUri.resolve(path)).timeout(requestTimeout).GET();
      if (!executorToken.isBlank()) {
        builder.header("Authorization", executorToken);
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Micro6 " + path + " returned HTTP " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Micro6 " + path + " was interrupted", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Micro6 " + path + " could not be read", exception);
    }
  }
}
