package com.chromascape.web.gameplay.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Validates named SC-1429 definitions and rejects unknown security-sensitive fields. */
@Component
public final class GameplayContractValidator {
  private static final String SCHEMA =
      "contracts/gameplay/sc-1429-gameplay-contract-v1.schema.json";
  private final ObjectMapper objectMapper;
  private final JsonNode root;
  private final JsonSchemaFactory schemaFactory =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  /** Loads the frozen v1 schema using the application JSON mapper. */
  public GameplayContractValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(SCHEMA)) {
      if (input == null) {
        throw new IllegalStateException("Missing " + SCHEMA);
      }
      this.root = objectMapper.readTree(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load SC-1429 schema", exception);
    }
  }

  /** Validates a value against one named v1 schema definition. */
  public void validate(String definition, Object value) {
    JsonNode definitionNode = root.path("$defs").path(definition);
    if (definitionNode.isMissingNode()) {
      throw new IllegalArgumentException("Unknown gameplay contract definition: " + definition);
    }
    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.set("$defs", root.path("$defs"));
    wrapper.put("$ref", "#/$defs/" + definition);
    JsonSchema schema = schemaFactory.getSchema(wrapper);
    Set<ValidationMessage> errors = schema.validate(objectMapper.valueToTree(value));
    if (!errors.isEmpty()) {
      String message =
          errors.stream()
              .map(ValidationMessage::getMessage)
              .sorted(Comparator.naturalOrder())
              .collect(Collectors.joining("; "));
      throw new GameplayContractViolation(failureCode(definition), message);
    }
    validateDateTimes(definition, objectMapper.valueToTree(value));
  }

  private static String failureCode(String definition) {
    return "INVALID_" + definition.toUpperCase(Locale.ROOT);
  }

  /** JSON Schema format is annotation-only here, so enforce every v1 timestamp explicitly. */
  private void validateDateTimes(String definition, JsonNode value) {
    for (String field : timestampFields(definition)) {
      JsonNode timestamp = value.path(field);
      try {
        if (timestamp.isMissingNode() || timestamp.isNull()) {
          throw new IllegalArgumentException("missing");
        }
        java.time.Instant.parse(timestamp.asText());
      } catch (Exception exception) {
        throw new GameplayContractViolation(
            failureCode(definition), "Invalid RFC-3339 date-time for " + field);
      }
    }
  }

  private static List<String> timestampFields(String definition) {
    switch (definition) {
      case "observation":
        return List.of("capturedAt");
      case "executionEnvelope":
        return List.of("deadline");
      case "authority":
        return List.of("approvedAt", "expiresAt");
      case "receipt":
        return List.of("at");
      default:
        return List.of();
    }
  }

  /** Stable failure-code carrier for a v1 schema violation. */
  public static final class GameplayContractViolation extends IllegalArgumentException {
    private final String failureCode;

    /** Creates a violation with its stable failure code and diagnostic message. */
    public GameplayContractViolation(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    /** Returns the stable failure code. */
    public String failureCode() {
      return failureCode;
    }
  }
}
