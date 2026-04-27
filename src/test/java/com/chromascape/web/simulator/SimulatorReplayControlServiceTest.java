package com.chromascape.web.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for {@link SimulatorReplayControlService}.
 *
 * <p>Verifies that upstream responses are correctly parsed and that failures (non-2xx, domain
 * failures, transport errors) are wrapped in {@link SimulatorReplayControlException}.
 */
@ExtendWith(MockitoExtension.class)
class SimulatorReplayControlServiceTest {

  @Mock private SimulatorReplayControlProperties properties;
  @Mock private HttpClient httpClient;
  @Mock private HttpResponse<String> httpResponse;

  private SimulatorReplayControlService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    when(properties.getOpenruneApiUrl()).thenReturn("http://127.0.0.1:8095");
    when(properties.getApiKey()).thenReturn("test-key");
    when(properties.getConnectionTimeoutMs()).thenReturn(5000);
    when(properties.getReadTimeoutMs()).thenReturn(10000);

    service = new SimulatorReplayControlService(properties);
    // Inject mock HttpClient
    ReflectionTestUtils.setField(service, "httpClient", httpClient);
  }

  @Nested
  @DisplayName("Upstream 2xx success")
  class UpstreamSuccess {

    @Test
    @DisplayName("getStatus returns parsed map on 200")
    void getStatusSuccess() throws Exception {
      String json =
          objectMapper.writeValueAsString(
              Map.of("state", "RUNNING", "sessionId", "sess-1", "speed", 1.0));

      when(httpResponse.statusCode()).thenReturn(200);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var result = service.getStatus("player1");

      assertEquals("RUNNING", result.get("state"));
      assertEquals("sess-1", result.get("sessionId"));
    }

    @Test
    @DisplayName("start returns parsed map with success=true")
    void startSuccess() throws Exception {
      String json = objectMapper.writeValueAsString(Map.of("success", true, "message", "Started"));

      when(httpResponse.statusCode()).thenReturn(200);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var result = service.start("player1", "latest", 2.0, "actor1");

      assertEquals(true, result.get("success"));
      assertEquals("Started", result.get("message"));
    }

    @Test
    @DisplayName("pause returns parsed map on 200")
    void pauseSuccess() throws Exception {
      String json = objectMapper.writeValueAsString(Map.of("success", true, "message", "Paused"));

      when(httpResponse.statusCode()).thenReturn(200);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var result = service.pause("player1");
      assertEquals(true, result.get("success"));
    }
  }

  @Nested
  @DisplayName("Upstream 2xx with success=false")
  class UpstreamDomainFailure {

    @Test
    @DisplayName("getStatus propagates domain failure result on 200 with success=false")
    void getStatusDomainFailure() throws Exception {
      // The current service implementation only throws for >= 400 status codes.
      // A 200 with success=false passes through as a normal result.
      String json =
          objectMapper.writeValueAsString(Map.of("success", false, "message", "Player not found"));

      when(httpResponse.statusCode()).thenReturn(200);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      // Service returns the map as-is; the controller decides the HTTP status
      var result = service.getStatus("unknown_player");
      assertEquals(false, result.get("success"));
      assertEquals("Player not found", result.get("message"));
    }
  }

  @Nested
  @DisplayName("Upstream 4xx/5xx")
  class UpstreamHttpError {

    @Test
    @DisplayName("throws on upstream 401 with upstream status")
    void upstream401() throws Exception {
      String json =
          objectMapper.writeValueAsString(
              Map.of("success", false, "error", "Invalid or missing API key"));

      when(httpResponse.statusCode()).thenReturn(401);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex =
          assertThrows(SimulatorReplayControlException.class, () -> service.getStatus("player1"));
      assertTrue(ex.getMessage().contains("401"));
      assertTrue(ex.getMessage().contains("Invalid or missing API key"));
      assertEquals(401, ex.getUpstreamStatus());
    }

    @Test
    @DisplayName("throws on upstream 404 with upstream status")
    void upstream404() throws Exception {
      String json =
          objectMapper.writeValueAsString(
              Map.of("success", false, "error", "Player not found: test"));

      when(httpResponse.statusCode()).thenReturn(404);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex = assertThrows(SimulatorReplayControlException.class, () -> service.pause("test"));
      assertTrue(ex.getMessage().contains("404"));
      assertEquals(404, ex.getUpstreamStatus());
    }

    @Test
    @DisplayName("throws on upstream 500 with upstream status")
    void upstream500() throws Exception {
      String json =
          objectMapper.writeValueAsString(
              Map.of("success", false, "error", "Internal server error"));

      when(httpResponse.statusCode()).thenReturn(500);
      when(httpResponse.body()).thenReturn(json);
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex = assertThrows(SimulatorReplayControlException.class, () -> service.stop("player1"));
      assertTrue(ex.getMessage().contains("500"));
      assertEquals(500, ex.getUpstreamStatus());
    }
  }

  @Nested
  @DisplayName("Transport failures")
  class TransportFailures {

    @Test
    @DisplayName("throws on connection failure")
    void connectionFailure() throws Exception {
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenThrow(new IOException("Connection refused"));

      var ex =
          assertThrows(SimulatorReplayControlException.class, () -> service.getStatus("player1"));
      assertTrue(ex.getMessage().contains("Connection refused"));
    }

    @Test
    @DisplayName("throws on interrupted thread")
    void interruptedThread() throws Exception {
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenThrow(new InterruptedException("Thread interrupted"));

      var ex =
          assertThrows(SimulatorReplayControlException.class, () -> service.getStatus("player1"));
      assertNotNull(ex.getMessage());
    }
  }

  @Nested
  @DisplayName("Malformed payload")
  class MalformedPayload {

    @Test
    @DisplayName("throws on non-JSON response body on 2xx")
    void malformedJson() throws Exception {
      when(httpResponse.statusCode()).thenReturn(200);
      when(httpResponse.body()).thenReturn("not valid json <<<");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex =
          assertThrows(SimulatorReplayControlException.class, () -> service.getStatus("player1"));
      assertTrue(ex.getMessage().contains("Failed to parse") || ex.getMessage().contains("parse"));
    }
  }

  @Nested
  @DisplayName("Non-JSON upstream error bodies")
  class NonJsonUpstreamError {

    @Test
    @DisplayName("propagates upstream status when error body is plain text")
    void upstreamNonJsonErrorBody() throws Exception {
      when(httpResponse.statusCode()).thenReturn(503);
      when(httpResponse.body()).thenReturn("Service Unavailable");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex =
          assertThrows(SimulatorReplayControlException.class, () -> service.getStatus("player1"));
      assertTrue(ex.getMessage().contains("503"));
      assertTrue(ex.getMessage().contains("non-JSON body"));
      assertEquals(503, ex.getUpstreamStatus());
    }

    @Test
    @DisplayName("propagates 401 upstream status even with non-JSON body")
    void upstream401NonJsonBody() throws Exception {
      when(httpResponse.statusCode()).thenReturn(401);
      when(httpResponse.body()).thenReturn("<html>Unauthorized</html>");
      when(httpClient.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofString())))
          .thenReturn(httpResponse);

      var ex = assertThrows(SimulatorReplayControlException.class, () -> service.pause("test"));
      assertEquals(401, ex.getUpstreamStatus());
      assertTrue(ex.getMessage().contains("401"));
    }
  }
}
