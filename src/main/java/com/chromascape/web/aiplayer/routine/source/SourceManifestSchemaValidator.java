package com.chromascape.web.aiplayer.routine.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/**
 * The strict wire-schema gate for authoritative source manifests.
 *
 * <p>This is deliberately a separate first-stage validator.  Semantic checks must never be used
 * to make an unknown-property or wrong-shape document look like a valid v1 manifest.  The schema
 * is checked in with the application and has {@code additionalProperties: false} at every
 * contract object boundary.
 */
public final class SourceManifestSchemaValidator {
  public static final String RESOURCE =
      "contracts/source/task-source-manifest-v1.schema.json";

  private static final JsonSchema SCHEMA = load();

  private SourceManifestSchemaValidator() {}

  public static void validate(JsonNode document) {
    if (document == null || !document.isObject()) {
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest does not satisfy the v1 schema");
    }
    Set<com.networknt.schema.ValidationMessage> violations = SCHEMA.validate(document);
    if (violations != null && !violations.isEmpty()) {
      // Do not expose schema paths or producer data to callers.  The stable failure code is the
      // useful contract; detailed validation remains available in local diagnostics.
      throw new SourceManifestException(
          SourceManifestFailureCode.SOURCE_MANIFEST_INVALID,
          "source manifest does not satisfy the v1 schema");
    }
  }

  private static JsonSchema load() {
    try {
      SchemaValidatorsConfig config = new SchemaValidatorsConfig();
      config.setFailFast(false);
      // The contract deliberately uses its logical wire identifier (task-source-manifest/v1)
      // rather than a network location.  Networknt's default identifier validator rejects that
      // valid relative $id while resolving the otherwise self-contained schema.  Keep schema
      // validation strict and relax only the schema-document identifier format; all instance
      // fields and additional-property rules remain enforced by the loaded schema.
      config.setSchemaIdValidator((id, rootSchema, baseLocation, idLocation, context) -> true);
      JsonSchemaFactory factory =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
        return factory.getSchema(input, config);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load source manifest schema", exception);
    }
  }
}
