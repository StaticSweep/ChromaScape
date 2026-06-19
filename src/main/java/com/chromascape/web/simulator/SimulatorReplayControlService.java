package com.chromascape.web.simulator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service that proxies replay control requests to the OpenRune ReplayControlService HTTP API.
 *
 * <p>Translates ChromaScape web UI actions into HTTP calls to the local OpenRune server's replay
 * control API.
 */
@Service
public class SimulatorReplayControlService {

  private static final Logger logger = LogManager.getLogger(SimulatorReplayControlService.class);

  private final SimulatorReplayControlProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  /**
   * Creates a new SimulatorReplayControlService.
   *
   * @param properties the replay control configuration properties
   */
  @Autowired
  public SimulatorReplayControlService(SimulatorReplayControlProperties properties) {
    this.properties = properties;
    this.objectMapper = new ObjectMapper();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectionTimeoutMs()))
            .build();
    logger.info(
        "SimulatorReplayControlService initialized - enabled: {}, url: {}",
        properties.isEnabled(),
        properties.getOpenruneApiUrl());
  }

  /**
   * Gets the current playback status for a player.
   *
   * @param playerName the in-game player name
   * @return the status response from OpenRune
   */
  public Map<String, Object> getStatus(String playerName) {
    String path = "/api/replay/control/status";
    if (playerName != null && !playerName.isBlank()) {
      path += "?player=" + encode(playerName);
    }
    return sendGet(path);
  }

  /** Lists online DEV players available for replay control. */
  public Map<String, Object> getPlayers() {
    return sendGet("/api/replay/control/players");
  }

  /**
   * Starts a replay playback session for a player.
   *
   * @param playerName the in-game player name
   * @param sessionId the session ID or "latest"
   * @param speed optional playback speed multiplier
   * @param actorId optional actor ID to scope playback
   * @return the response from OpenRune
   */
  public Map<String, Object> start(
      String playerName, String sessionId, Double speed, String actorId) {
    return start(playerName, sessionId, speed, actorId, null, null);
  }

  /**
   * Starts a replay playback session for a player.
   *
   * @param playerName the in-game player name, or null for auto-targeting
   * @param sessionId the session ID or "latest"
   * @param speed optional playback speed multiplier
   * @param actorId optional actor ID to scope playback
   * @param recordingId optional recording ID for tick-snapshot replay
   * @param sourceMode optional replay source mode
   * @return the response from OpenRune
   */
  public Map<String, Object> start(
      String playerName,
      String sessionId,
      Double speed,
      String actorId,
      String recordingId,
      String sourceMode) {
    Map<String, Object> body = new HashMap<>();
    putIfNotBlank(body, "player", playerName);
    putIfNotBlank(body, "sessionId", sessionId);
    if (speed != null) {
      body.put("speed", speed);
    }
    putIfNotBlank(body, "actorId", actorId);
    putIfNotBlank(body, "recordingId", recordingId);
    putIfNotBlank(body, "sourceMode", sourceMode);
    return sendPost("/api/replay/control/start", body);
  }

  /**
   * Pauses the active replay playback for a player.
   *
   * @param playerName the in-game player name
   * @return the response from OpenRune
   */
  public Map<String, Object> pause(String playerName) {
    return sendPost("/api/replay/control/pause", bodyWithOptionalPlayer(playerName));
  }

  /**
   * Resumes the paused replay playback for a player.
   *
   * @param playerName the in-game player name
   * @return the response from OpenRune
   */
  public Map<String, Object> resume(String playerName) {
    return sendPost("/api/replay/control/resume", bodyWithOptionalPlayer(playerName));
  }

  /**
   * Stops the replay playback for a player.
   *
   * @param playerName the in-game player name
   * @return the response from OpenRune
   */
  public Map<String, Object> stop(String playerName) {
    return sendPost("/api/replay/control/stop", bodyWithOptionalPlayer(playerName));
  }

  /**
   * Seeks to a specific time in the replay.
   *
   * @param playerName the in-game player name
   * @param seconds the time in seconds to seek to
   * @return the response from OpenRune
   */
  public Map<String, Object> seek(String playerName, double seconds) {
    Map<String, Object> body = bodyWithOptionalPlayer(playerName);
    body.put("seconds", seconds);
    return sendPost("/api/replay/control/seek", body);
  }

  /**
   * Changes the playback speed.
   *
   * @param playerName the in-game player name
   * @param speed the new speed multiplier
   * @return the response from OpenRune
   */
  public Map<String, Object> setSpeed(String playerName, double speed) {
    Map<String, Object> body = bodyWithOptionalPlayer(playerName);
    body.put("speed", speed);
    return sendPost("/api/replay/control/speed", body);
  }

  private Map<String, Object> bodyWithOptionalPlayer(String playerName) {
    Map<String, Object> body = new HashMap<>();
    putIfNotBlank(body, "player", playerName);
    return body;
  }

  private Map<String, Object> sendGet(String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.getOpenruneApiUrl() + path))
              .header("X-API-Key", properties.getApiKey())
              .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parseResponse(response);
    } catch (SimulatorReplayControlException e) {
      throw e;
    } catch (Exception e) {
      throw new SimulatorReplayControlException("GET " + path + " failed: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> sendPost(String path, Map<String, Object> body) {
    try {
      String json = objectMapper.writeValueAsString(body);

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.getOpenruneApiUrl() + path))
              .header("Content-Type", "application/json")
              .header("X-API-Key", properties.getApiKey())
              .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parseResponse(response);
    } catch (SimulatorReplayControlException e) {
      throw e;
    } catch (Exception e) {
      throw new SimulatorReplayControlException("POST " + path + " failed: " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseResponse(HttpResponse<String> response) {
    int status = response.statusCode();

    // Handle non-JSON error bodies: propagate upstream status before attempting to parse
    if (status >= 400) {
      try {
        Map<String, Object> result =
            objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        String error = (String) result.getOrDefault("error", "Unknown error");
        throw new SimulatorReplayControlException(
            "OpenRune API returned " + status + ": " + error, status);
      } catch (SimulatorReplayControlException e) {
        throw e;
      } catch (Exception e) {
        throw new SimulatorReplayControlException(
            "OpenRune API returned " + status + " (non-JSON body)", status);
      }
    }

    try {
      return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    } catch (SimulatorReplayControlException e) {
      throw e;
    } catch (Exception e) {
      throw new SimulatorReplayControlException(
          "Failed to parse OpenRune response: " + e.getMessage(), e);
    }
  }

  private static String encode(String value) {
    try {
      return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      return value;
    }
  }

  private static void putIfNotBlank(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }
}
