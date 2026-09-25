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
      throw new GameplayContractViolation("INVALID_" + definition.toUpperCase(), message);
    }
  }

  public static final class GameplayContractViolation extends IllegalArgumentException {
    private final String failureCode;

    public GameplayContractViolation(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
