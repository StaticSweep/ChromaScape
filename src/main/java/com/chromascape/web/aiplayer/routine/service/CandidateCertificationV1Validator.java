package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Strict production validator for the signed candidate-certification/v1 document. */
@Component
public final class CandidateCertificationV1Validator {
  public static final long MAX_BYTES = 2L << 20;
  private static final String RESOURCE = "contracts/candidate-certification-v1.schema.json";

  private final JsonSchema schema;

  public CandidateCertificationV1Validator() {
    try {
      SchemaValidatorsConfig config = new SchemaValidatorsConfig();
      config.setFailFast(false);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
        schema = factory.getSchema(input, config);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load SC-967 contract " + RESOURCE, exception);
    }
  }

  /** Parses and validates exact certification bytes, rejecting duplicate keys and non-contract JSON. */
  public JsonNode validate(byte[] certificationBytes) {
    if (certificationBytes == null || certificationBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("CERTIFICATION_TOO_LARGE");
    }
    JsonNode document = Sc967CanonicalJson.parse(certificationBytes);
    validate(document);
    return document;
  }

  /** Validates an already strictly parsed certification document. */
  public void validate(JsonNode document) {
    if (document == null || !document.isObject()) {
      throw new IllegalArgumentException("CERTIFICATION_INVALID");
    }
    java.util.Set<ValidationMessage> messages = schema.validate(document);
    if (!messages.isEmpty()) {
      throw new IllegalArgumentException(
          "CERTIFICATION_SCHEMA_INVALID:" + messages.iterator().next().getMessage());
    }
  }
}
