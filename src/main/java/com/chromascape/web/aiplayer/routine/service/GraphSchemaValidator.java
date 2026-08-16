package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Draft 2020-12 JSON-schema validation for the persisted behavior-graph definition and assistant
 * diff contracts. Every graph persisted by {@link BehaviorGraphService} and every assistant diff
 * applied to a graph must satisfy its schema before any operation is applied.
 */
@Component
public class GraphSchemaValidator {
  private static final String DEFINITION_SCHEMA = "contracts/behavior-graph-definition-v1.schema.json";
  private static final String DIFF_SCHEMA = "contracts/behavior-graph-diff-v1.schema.json";

  private final JsonSchema definitionSchema;
  private final JsonSchema diffSchema;

  public GraphSchemaValidator() {
    this.definitionSchema = load(DEFINITION_SCHEMA);
    this.diffSchema = load(DIFF_SCHEMA);
  }

  private static JsonSchema load(String location) {
    try {
      SchemaValidatorsConfig config = new SchemaValidatorsConfig();
      config.setFailFast(false);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream in = new ClassPathResource(location).getInputStream()) {
        return factory.getSchema(in, config);
      }
    } catch (IOException e) {
      throw new IllegalStateException("unable to load graph schema " + location, e);
    }
  }

  /** Validates a behavior-graph-definition document; throws on any schema violation. */
  public void validateDefinition(JsonNode document) {
    Set<ValidationMessage> messages = definitionSchema.validate(document);
    if (!messages.isEmpty()) {
      throw new IllegalArgumentException("GRAPH_SCHEMA_INVALID: " + firstMessage(messages));
    }
  }

  /** Validates a behavior-graph-diff document; throws on any schema violation. */
  public void validateDiff(JsonNode document) {
    Set<ValidationMessage> messages = diffSchema.validate(document);
    if (!messages.isEmpty()) {
      throw new IllegalArgumentException("GRAPH_DIFF_SCHEMA_INVALID: " + firstMessage(messages));
    }
  }

  /** Validates the public graph-response envelope as well as its strict nested documents. */
  public void validateResponse(JsonNode document) {
    if (document == null || !document.isObject()
        || document.size() != 6
        || !document.has("schema") || !document.has("definition")
        || !document.has("state") || !document.has("supersedes_graph_id")
        || !document.has("assistant_diff") || !document.has("control_allowed")) {
      throw new IllegalArgumentException("GRAPH_RESPONSE_SCHEMA_INVALID: required fields are missing or unknown");
    }
    if (!"behavior-graph-response/v1".equals(document.path("schema").asText())) {
      throw new IllegalArgumentException("GRAPH_RESPONSE_SCHEMA_INVALID: schema is invalid");
    }
    String state = document.path("state").asText();
    if (!"DRAFT".equals(state) && !"PUBLISHED".equals(state)) {
      throw new IllegalArgumentException("GRAPH_RESPONSE_SCHEMA_INVALID: state is invalid");
    }
    JsonNode supersedes = document.get("supersedes_graph_id");
    if (!supersedes.isNull()
        && (!supersedes.isTextual() || !supersedes.asText().matches("graph_[0-9a-f]{24}"))) {
      throw new IllegalArgumentException("GRAPH_RESPONSE_SCHEMA_INVALID: supersedes_graph_id is invalid");
    }
    if (!document.get("control_allowed").isBoolean() || document.get("control_allowed").asBoolean()) {
      throw new IllegalArgumentException("GRAPH_RESPONSE_SCHEMA_INVALID: control_allowed must be false");
    }
    validateDefinition(document.get("definition"));
    JsonNode diff = document.get("assistant_diff");
    if (!diff.isNull()) validateDiff(diff);
  }

  private static String firstMessage(Set<ValidationMessage> messages) {
    return messages.iterator().next().getMessage();
  }
}
