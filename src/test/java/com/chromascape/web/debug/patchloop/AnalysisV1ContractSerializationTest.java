package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.patchloop.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Test suite for JSON serialization/deserialization of analysis.v1 contract DTOs. */
class AnalysisV1ContractSerializationTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
  }

  @Test
  @DisplayName("Request serializes to JSON correctly")
  void testRequestSerializesToJson() throws Exception {
    // Arrange
    AnalysisV1Request request = new AnalysisV1Request();
    request.setPrompt("Why did the script fail?");
    request.setContext("Additional context");

    AnalysisV1EvidenceBundle evidence = new AnalysisV1EvidenceBundle();
    LocalDateTime now = LocalDateTime.now();
    evidence.setTimeWindow(new TimeWindow(now.minusMinutes(5), now));
    evidence.setLogs(new ArrayList<>());
    request.setEvidence(evidence);

    // Act
    String json = objectMapper.writeValueAsString(request);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertNotNull(json);
    assertTrue(node.has("schemaVersion"));
    assertEquals("1.0.0", node.get("schemaVersion").asText());
    assertTrue(node.has("prompt"));
    assertEquals("Why did the script fail?", node.get("prompt").asText());
    assertTrue(node.has("context"));
    assertTrue(node.has("evidence"));
  }

  @Test
  @DisplayName("Request deserializes from JSON correctly")
  void testRequestDeserializesFromJson() throws Exception {
    // Arrange
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "prompt": "Test prompt",
          "context": "Test context",
          "evidence": {
            "scriptName": "TestScript",
            "timeWindow": {
              "start": "2024-01-01T10:00:00",
              "end": "2024-01-01T10:05:00"
            },
            "logs": []
          }
        }
        """;

    // Act
    AnalysisV1Request request = objectMapper.readValue(json, AnalysisV1Request.class);

    // Assert
    assertNotNull(request);
    assertEquals("1.0.0", request.getSchemaVersion());
    assertEquals("Test prompt", request.getPrompt());
    assertEquals("Test context", request.getContext());
    assertNotNull(request.getEvidence());
    assertEquals("TestScript", request.getEvidence().getScriptName());
  }

  @Test
  @DisplayName("Request without schemaVersion uses default (backward compatibility)")
  void testRequestWithoutSchemaVersionUsesDefault() throws Exception {
    // Arrange - JSON without schemaVersion
    String json =
        """
        {
          "prompt": "Test prompt",
          "evidence": {
            "scriptName": "TestScript",
            "timeWindow": {
              "start": "2024-01-01T10:00:00",
              "end": "2024-01-01T10:05:00"
            },
            "logs": []
          }
        }
        """;

    // Act
    AnalysisV1Request request = objectMapper.readValue(json, AnalysisV1Request.class);

    // Assert - getSchemaVersion() returns default even if not in JSON
    assertNotNull(request);
    assertEquals("1.0.0", request.getSchemaVersion());
  }

  @Test
  @DisplayName("Response serializes to JSON correctly")
  void testResponseSerializesToJson() throws Exception {
    // Arrange
    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("analysis-123");
    response.setStatus(AnalysisStatus.SUCCESS);

    AnalysisV1Response.DiagnosisSection diagnosis = new AnalysisV1Response.DiagnosisSection();
    diagnosis.setSummary("Bot failed due to timeout");
    diagnosis.setConfidence(ConfidenceLevel.HIGH);
    response.setDiagnosis(diagnosis);

    // Act
    String json = objectMapper.writeValueAsString(response);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertNotNull(json);
    assertTrue(node.has("schemaVersion"));
    assertEquals("1.0.0", node.get("schemaVersion").asText());
    assertTrue(node.has("analysisId"));
    assertEquals("analysis-123", node.get("analysisId").asText());
    assertTrue(node.has("status"));
    assertEquals("SUCCESS", node.get("status").asText());
    assertTrue(node.has("diagnosis"));
  }

  @Test
  @DisplayName("Response deserializes from JSON correctly")
  void testResponseDeserializesFromJson() throws Exception {
    // Arrange
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "analysisId": "analysis-456",
          "timestamp": "2024-01-01T12:00:00",
          "status": "SUCCESS",
          "diagnosis": {
            "summary": "Root cause identified",
            "rootCause": "Network timeout",
            "confidence": "HIGH",
            "recommendations": [],
            "evidenceTrail": []
          }
        }
        """;

    // Act
    AnalysisV1Response response = objectMapper.readValue(json, AnalysisV1Response.class);

    // Assert
    assertNotNull(response);
    assertEquals("1.0.0", response.getSchemaVersion());
    assertEquals("analysis-456", response.getAnalysisId());
    assertEquals(AnalysisStatus.SUCCESS, response.getStatus());
    assertNotNull(response.getDiagnosis());
    assertEquals("Root cause identified", response.getDiagnosis().getSummary());
    assertEquals(ConfidenceLevel.HIGH, response.getDiagnosis().getConfidence());
  }

  @Test
  @DisplayName("Response failure case serializes correctly")
  void testFailureResponseSerializesCorrectly() throws Exception {
    // Arrange
    AnalysisV1Response response = new AnalysisV1Response();
    response.setAnalysisId("analysis-789");
    response.setStatus(AnalysisStatus.FAILURE);

    AnalysisV1Error error = new AnalysisV1Error();
    error.setCode("INSUFFICIENT_EVIDENCE");
    error.setMessage("Not enough data");
    response.setError(error);

    // Act
    String json = objectMapper.writeValueAsString(response);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertTrue(node.has("schemaVersion"));
    assertTrue(node.has("status"));
    assertEquals("FAILURE", node.get("status").asText());
    assertTrue(node.has("error"));
    assertEquals("INSUFFICIENT_EVIDENCE", node.get("error").get("code").asText());
  }

  @Test
  @DisplayName("Evidence bundle with all types serializes correctly")
  void testEvidenceBundleSerializesCorrectly() throws Exception {
    // Arrange
    AnalysisV1EvidenceBundle bundle = new AnalysisV1EvidenceBundle();
    bundle.setScriptName("DemoScript");

    LocalDateTime now = LocalDateTime.now();
    bundle.setTimeWindow(new TimeWindow(now.minusMinutes(5), now));
    bundle.setTickWindow(new TickWindow(100L, 200L));

    bundle.setLogs(new ArrayList<>());
    bundle
        .getLogs()
        .add(new AnalysisV1EvidenceBundle.LogEvidence(now, "ERROR", "Test error", "DemoScript"));

    bundle.setTickLogs(new ArrayList<>());
    bundle
        .getTickLogs()
        .add(
            new AnalysisV1EvidenceBundle.TickLogEvidence(
                150L, now, "MOVEMENT", "INFO", "Moved to tile", null));

    bundle.setDecisions(new ArrayList<>());
    bundle
        .getDecisions()
        .add(
            new AnalysisV1EvidenceBundle.DecisionEvidence(
                now, "INTERACT", true, null, "Rock", "Mining rock"));

    // Act
    String json = objectMapper.writeValueAsString(bundle);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertTrue(node.has("scriptName"));
    assertEquals("DemoScript", node.get("scriptName").asText());
    assertTrue(node.has("timeWindow"));
    assertTrue(node.has("tickWindow"));
    assertTrue(node.has("logs"));
    assertTrue(node.get("logs").isArray());
    assertEquals(1, node.get("logs").size());
    assertTrue(node.has("tickLogs"));
    assertTrue(node.get("tickLogs").isArray());
    assertEquals(1, node.get("tickLogs").size());
    assertTrue(node.has("decisions"));
    assertTrue(node.get("decisions").isArray());
    assertEquals(1, node.get("decisions").size());
  }

  @Test
  @DisplayName("TimeWindow serialization includes start and end")
  void testTimeWindowSerialization() throws Exception {
    // Arrange
    LocalDateTime start = LocalDateTime.of(2024, 1, 1, 10, 0);
    LocalDateTime end = LocalDateTime.of(2024, 1, 1, 10, 5);
    TimeWindow window = new TimeWindow(start, end);

    // Act
    String json = objectMapper.writeValueAsString(window);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertTrue(node.has("start"));
    assertTrue(node.has("end"));
    assertNotNull(node.get("start").asText());
    assertNotNull(node.get("end").asText());
  }

  @Test
  @DisplayName("TickWindow serialization includes startTick and endTick")
  void testTickWindowSerialization() throws Exception {
    // Arrange
    TickWindow window = new TickWindow(100L, 200L);

    // Act
    String json = objectMapper.writeValueAsString(window);
    JsonNode node = objectMapper.readTree(json);

    // Assert
    assertTrue(node.has("startTick"));
    assertTrue(node.has("endTick"));
    assertEquals(100, node.get("startTick").asLong());
    assertEquals(200, node.get("endTick").asLong());
  }
}
