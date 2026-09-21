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
  private static final String DEFINITION_SCHEMA =
      "contracts/behavior-graph-definition-v1.schema.json";
  private static final String DIFF_SCHEMA = "contracts/behavior-graph-diff-v1.schema.json";
  private static final String DEFINITION_V2_SCHEMA =
      "contracts/behavior-graph-definition-v2.schema.json";
  private static final String LAYOUT_V1_SCHEMA = "contracts/behavior-graph-layout-v1.schema.json";
  private static final String DIFF_V2_SCHEMA = "contracts/behavior-graph-diff-v2.schema.json";
  private static final String DRAFT_REQUEST_SCHEMA =
      "contracts/behavior-graph-draft-request-v1.schema.json";
  private static final String DRAFT_RESPONSE_SCHEMA =
      "contracts/behavior-graph-draft-response-v1.schema.json";
  private static final String TRACE_SCHEMA = "contracts/behavior-graph-trace-v1.schema.json";
  private static final String PUBLISH_REQUEST_SCHEMA =
      "contracts/behavior-graph-publish-request-v1.schema.json";
  private static final String DRAFT_CREATE_REQUEST_SCHEMA =
      "contracts/behavior-graph-draft-create-request-v1.schema.json";
  private static final String PUBLICATION_RESPONSE_SCHEMA =
      "contracts/behavior-graph-publication-response-v1.schema.json";

  private final JsonSchema definitionSchema;
  private final JsonSchema diffSchema;
  private final JsonSchema definitionV2Schema,
      layoutV1Schema,
      diffV2Schema,
      draftRequestSchema,
      draftResponseSchema,
      traceSchema,
      publishRequestSchema,
      publicationResponseSchema,
      draftCreateRequestSchema;

  public GraphSchemaValidator() {
    this.definitionSchema = load(DEFINITION_SCHEMA);
    this.diffSchema = load(DIFF_SCHEMA);
    this.definitionV2Schema = load(DEFINITION_V2_SCHEMA);
    this.layoutV1Schema = load(LAYOUT_V1_SCHEMA);
    this.diffV2Schema = load(DIFF_V2_SCHEMA);
    this.draftRequestSchema = load(DRAFT_REQUEST_SCHEMA);
    this.draftResponseSchema = load(DRAFT_RESPONSE_SCHEMA);
    this.traceSchema = load(TRACE_SCHEMA);
    this.publishRequestSchema = load(PUBLISH_REQUEST_SCHEMA);
    this.publicationResponseSchema = load(PUBLICATION_RESPONSE_SCHEMA);
    this.draftCreateRequestSchema = load(DRAFT_CREATE_REQUEST_SCHEMA);
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
    validateDefinitionV1(document);
  }

  public void validateDefinitionV1(JsonNode document) {
    validateLegacy(
        definitionSchema, document, "behavior-graph-definition/v1", "GRAPH_SCHEMA_INVALID");
  }

  public void validateDefinitionV2(JsonNode document) {
    validate(definitionV2Schema, document, "behavior-graph-definition/v2", "GRAPH_SCHEMA_INVALID");
    validateDisabled(document);
  }

  public void validateLayoutV1(JsonNode document) {
    validate(layoutV1Schema, document, "behavior-graph-layout/v1", "LAYOUT_SCHEMA_INVALID");
  }

  /** Validates a behavior-graph-diff document; throws on any schema violation. */
  public void validateDiff(JsonNode document) {
    validateDiffV1(document);
  }

  public void validateDiffV1(JsonNode document) {
    validateLegacy(diffSchema, document, "behavior-graph-diff/v1", "GRAPH_DIFF_SCHEMA_INVALID");
  }

  public void validateDiffV2(JsonNode document) {
    validate(diffV2Schema, document, "behavior-graph-diff/v2", "GRAPH_DIFF_SCHEMA_INVALID");
  }

  public void validateDraftRequest(JsonNode document) {
    validateLegacy(
        draftRequestSchema, document, "behavior-graph-draft-request/v1", "MALFORMED_CONTRACT");
  }

  public void validateDraftResponse(JsonNode document) {
    validateLegacy(
        draftResponseSchema, document, "behavior-graph-draft-response/v1", "MALFORMED_CONTRACT");
    validateDisabled(document);
  }

  public void validatePublishRequest(JsonNode document) {
    validateLegacy(
        publishRequestSchema, document, "behavior-graph-publish-request/v1", "MALFORMED_CONTRACT");
  }

  public void validateDraftCreateRequest(JsonNode document) {
    validateLegacy(
        draftCreateRequestSchema,
        document,
        "behavior-graph-draft-create-request/v1",
        "MALFORMED_CONTRACT");
  }

  public void validatePublicationResponse(JsonNode document) {
    validateLegacy(
        publicationResponseSchema,
        document,
        "behavior-graph-publication-response/v1",
        "MALFORMED_CONTRACT");
    validateDisabled(document);
  }

  public void validateTrace(JsonNode document) {
    validate(traceSchema, document, "behavior-graph-trace/v1", "MALFORMED_CONTRACT");
  }

  private static void validate(JsonSchema schema, JsonNode document, String expected, String code) {
    if (document == null
        || !document.isObject()
        || !expected.equals(document.path("schema").asText())
        || !expected.equals(document.path("schema_version").asText()))
      throw new IllegalArgumentException(code + ": schema/schema_version must equal " + expected);
    Set<ValidationMessage> messages = schema.validate(document);
    if (!messages.isEmpty())
      throw new IllegalArgumentException(code + ": " + firstMessage(messages));
  }

  private static void validateLegacy(
      JsonSchema schema, JsonNode document, String expected, String code) {
    if (document == null
        || !document.isObject()
        || !expected.equals(document.path("schema").asText()))
      throw new IllegalArgumentException(code + ": schema must equal " + expected);
    Set<ValidationMessage> messages = schema.validate(document);
    if (!messages.isEmpty())
      throw new IllegalArgumentException(code + ": " + firstMessage(messages));
  }

  private static void validateDisabled(JsonNode document) {
    if (document.has("control_allowed") && document.path("control_allowed").asBoolean(true))
      throw new IllegalArgumentException("CAPABILITY_MISMATCH: control_allowed must be false");
    JsonNode authority = document.path("authority");
    if (authority.isObject())
      authority
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().asBoolean(false))
                  throw new IllegalArgumentException(
                      "CAPABILITY_MISMATCH: authority must be false");
              });
  }

  /** Validates the public graph-response envelope as well as its strict nested documents. */
  public void validateResponse(JsonNode document) {
    if (document == null
        || !document.isObject()
        || document.size() != 6
        || !document.has("schema")
        || !document.has("definition")
        || !document.has("state")
        || !document.has("supersedes_graph_id")
        || !document.has("assistant_diff")
        || !document.has("control_allowed")) {
      throw new IllegalArgumentException(
          "GRAPH_RESPONSE_SCHEMA_INVALID: required fields are missing or unknown");
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
      throw new IllegalArgumentException(
          "GRAPH_RESPONSE_SCHEMA_INVALID: supersedes_graph_id is invalid");
    }
    if (!document.get("control_allowed").isBoolean()
        || document.get("control_allowed").asBoolean()) {
      throw new IllegalArgumentException(
          "GRAPH_RESPONSE_SCHEMA_INVALID: control_allowed must be false");
    }
    validateDefinition(document.get("definition"));
    JsonNode diff = document.get("assistant_diff");
    if (!diff.isNull()) validateDiff(diff);
  }

  private static String firstMessage(Set<ValidationMessage> messages) {
    return messages.iterator().next().getMessage();
  }
}
