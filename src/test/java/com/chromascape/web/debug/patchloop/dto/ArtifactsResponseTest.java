package com.chromascape.web.debug.patchloop.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test suite for ArtifactsResponse DTOs, focusing on rollback field serialization and
 * deserialization.
 */
class ArtifactsResponseTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  @DisplayName("ValidationArtifact has rolledBack and rollbackReason fields")
  void testValidationArtifactHasRollbackFields() {
    // Arrange & Act
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();

    // Assert - getters should exist and return null by default
    assertNull(artifact.getRolledBack(), "rolledBack should be null by default");
    assertNull(artifact.getRollbackReason(), "rollbackReason should be null by default");
  }

  @Test
  @DisplayName("ValidationArtifact setters and getters work for rollback fields")
  void testValidationArtifactRollbackSettersGetters() {
    // Arrange
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();

    // Act
    artifact.setRolledBack(true);
    artifact.setRollbackReason("Test failed after patch application");

    // Assert
    assertTrue(artifact.getRolledBack(), "rolledBack should be true");
    assertEquals("Test failed after patch application", artifact.getRollbackReason());
  }

  @Test
  @DisplayName("ValidationArtifact serialization includes rollback fields")
  void testValidationArtifactSerializationIncludesRollbackFields() throws JsonProcessingException {
    // Arrange
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();
    artifact.setPatchId("patch-123");
    artifact.setAttemptNumber(1);
    artifact.setPassed(false);
    artifact.setOutput("Test failed");
    artifact.setExitCode(1);
    artifact.setDurationMs(5000L);
    artifact.setTimestamp("2025-01-15T10:30:00");
    artifact.setRolledBack(true);
    artifact.setRollbackReason("Test failed after patch application");

    // Act
    String json = objectMapper.writeValueAsString(artifact);

    // Assert
    assertNotNull(json);
    assertTrue(json.contains("\"rolledBack\":true"), "JSON should contain rolledBack field");
    assertTrue(
        json.contains("\"rollbackReason\":\"Test failed after patch application\""),
        "JSON should contain rollbackReason field");
  }

  @Test
  @DisplayName("ValidationArtifact serialization includes null rollback fields")
  void testValidationArtifactSerializationIncludesNullRollbackFields() throws JsonProcessingException {
    // Arrange
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();
    artifact.setPatchId("patch-123");
    artifact.setAttemptNumber(1);
    artifact.setPassed(true);
    // Rollback fields not set (null)

    // Act
    String json = objectMapper.writeValueAsString(artifact);

    // Assert
    assertNotNull(json);
    assertTrue(json.contains("\"patchId\":\"patch-123\""), "JSON should contain patchId");
    // Jackson includes null fields when @JsonProperty is used
    assertTrue(json.contains("\"rolledBack\":null"), "JSON should contain null rolledBack field");
    assertTrue(json.contains("\"rollbackReason\":null"), "JSON should contain null rollbackReason field");
  }

  @Test
  @DisplayName("ValidationArtifact deserialization populates rollback fields")
  void testValidationArtifactDeserializationPopulatesRollbackFields()
      throws JsonProcessingException {
    // Arrange
    String json =
        """
        {
          "patchId": "patch-456",
          "attemptNumber": 2,
          "passed": false,
          "output": "Tests failed",
          "exitCode": 1,
          "durationMs": 3000,
          "timestamp": "2025-01-15T11:00:00",
          "rolledBack": true,
          "rollbackReason": "Automated rollback due to test failure"
        }
        """;

    // Act
    ArtifactsResponse.ValidationArtifact artifact =
        objectMapper.readValue(json, ArtifactsResponse.ValidationArtifact.class);

    // Assert
    assertEquals("patch-456", artifact.getPatchId());
    assertEquals(2, artifact.getAttemptNumber());
    assertFalse(artifact.getPassed());
    assertTrue(artifact.getRolledBack(), "rolledBack should be true");
    assertEquals(
        "Automated rollback due to test failure", artifact.getRollbackReason());
  }

  @Test
  @DisplayName("ValidationArtifact deserialization handles missing rollback fields")
  void testValidationArtifactDeserializationHandlesMissingRollbackFields()
      throws JsonProcessingException {
    // Arrange - JSON without rollback fields (legacy format)
    String json =
        """
        {
          "patchId": "patch-789",
          "attemptNumber": 1,
          "passed": true,
          "output": "Tests passed",
          "exitCode": 0,
          "durationMs": 2000,
          "timestamp": "2025-01-15T12:00:00"
        }
        """;

    // Act
    ArtifactsResponse.ValidationArtifact artifact =
        objectMapper.readValue(json, ArtifactsResponse.ValidationArtifact.class);

    // Assert
    assertEquals("patch-789", artifact.getPatchId());
    assertTrue(artifact.getPassed());
    assertNull(artifact.getRolledBack(), "rolledBack should be null when not in JSON");
    assertNull(artifact.getRollbackReason(), "rollbackReason should be null when not in JSON");
  }

  @Test
  @DisplayName("ValidationArtifact handles false rolledBack with reason")
  void testValidationArtifactFalseRolledBackWithReason() throws JsonProcessingException {
    // Arrange - Edge case: rolledBack=false but with a reason (shouldn't happen in practice)
    String json =
        """
        {
          "patchId": "patch-999",
          "attemptNumber": 1,
          "passed": true,
          "rolledBack": false,
          "rollbackReason": "N/A"
        }
        """;

    // Act
    ArtifactsResponse.ValidationArtifact artifact =
        objectMapper.readValue(json, ArtifactsResponse.ValidationArtifact.class);

    // Assert
    assertFalse(artifact.getRolledBack());
    assertEquals("N/A", artifact.getRollbackReason());
  }

  @Test
  @DisplayName("ValidationArtifact round-trip serialization preserves rollback fields")
  void testValidationArtifactRoundTripPreservesRollbackFields() throws JsonProcessingException {
    // Arrange
    ArtifactsResponse.ValidationArtifact original = new ArtifactsResponse.ValidationArtifact();
    original.setPatchId("patch-000");
    original.setAttemptNumber(3);
    original.setPassed(false);
    original.setRolledBack(true);
    original.setRollbackReason("Integration test rollback");

    // Act - serialize then deserialize
    String json = objectMapper.writeValueAsString(original);
    ArtifactsResponse.ValidationArtifact deserialized =
        objectMapper.readValue(json, ArtifactsResponse.ValidationArtifact.class);

    // Assert
    assertEquals(original.getPatchId(), deserialized.getPatchId());
    assertEquals(original.getAttemptNumber(), deserialized.getAttemptNumber());
    assertEquals(original.getPassed(), deserialized.getPassed());
    assertEquals(original.getRolledBack(), deserialized.getRolledBack());
    assertEquals(original.getRollbackReason(), deserialized.getRollbackReason());
  }

  @Test
  @DisplayName("ValidationArtifact serialization with rolledBack=false includes field")
  void testValidationArtifactSerializationWithFalseRolledBack() throws JsonProcessingException {
    // Arrange
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();
    artifact.setPatchId("patch-111");
    artifact.setPassed(true);
    artifact.setRolledBack(false); // Explicitly set to false
    artifact.setRollbackReason(null); // No reason needed

    // Act
    String json = objectMapper.writeValueAsString(artifact);

    // Assert
    assertTrue(json.contains("\"rolledBack\":false"), "JSON should contain rolledBack:false");
  }

  @Test
  @DisplayName("ValidationArtifact empty rollbackReason serializes correctly")
  void testValidationArtifactEmptyRollbackReason() throws JsonProcessingException {
    // Arrange
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();
    artifact.setRolledBack(true);
    artifact.setRollbackReason(""); // Empty string

    // Act
    String json = objectMapper.writeValueAsString(artifact);

    // Assert
    assertTrue(json.contains("\"rolledBack\":true"));
    assertTrue(json.contains("\"rollbackReason\":\"\""));
  }
}
