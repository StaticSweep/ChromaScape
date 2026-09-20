/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.debug.tickdebugger;

import static com.chromascape.web.debug.tickdebugger.QuestContractMirrorSupport.assertByteIdentical;
import static com.chromascape.web.debug.tickdebugger.QuestContractMirrorSupport.mainMirror;
import static com.chromascape.web.debug.tickdebugger.QuestContractMirrorSupport.resolveMicro6QuestDir;
import static com.chromascape.web.debug.tickdebugger.QuestContractMirrorSupport.testMirror;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SC-266 ChromaScape-side contract verification for the {@code quest.script.v1} quest script
 * authoring contract:
 *
 * <ul>
 *   <li>the mirrored schema accepts every valid golden fixture;
 *   <li>structurally malformed scripts are rejected;
 *   <li>semantically invalid scripts remain schema-valid (semantic linting is Micro6's job);
 *   <li>the main- and test-resource mirrors are byte-identical to each other, always;
 *   <li>when a Micro6 checkout is available (configured or found at a known root), the mirror is
 *       byte-identical to the Micro6 owner for the schema and every fixture copy.
 * </ul>
 *
 * <p>Micro6 owns the contract. ChromaScape stores the mirror unchanged and proves drift. Normal
 * ChromaScape CI does not provision Micro6, so the cross-repository comparison is skipped there;
 * the in-repository mirror gate still runs.
 */
class QuestScriptContractConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCHEMA_RESOURCE = "contracts/quest/quest-script-v1.schema.json";
  private static final String FIXTURES_RESOURCE = "contracts/quest/quest-script-v1-fixtures.json";
  private static final String SCHEMA_NAME = "quest-script-v1.schema.json";
  private static final String FIXTURES_NAME = "quest-script-v1-fixtures.json";

  private static JsonSchema schema;
  private static JsonNode fixtures;

  @BeforeAll
  static void setUp() throws Exception {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    try (InputStream input =
        QuestScriptContractConformanceTest.class
            .getClassLoader()
            .getResourceAsStream(SCHEMA_RESOURCE)) {
      assertThat(input).as("mirrored quest script schema resource").isNotNull();
      schema = factory.getSchema(input);
    }
    try (InputStream input =
        QuestScriptContractConformanceTest.class
            .getClassLoader()
            .getResourceAsStream(FIXTURES_RESOURCE)) {
      assertThat(input).as("mirrored quest script fixtures resource").isNotNull();
      fixtures = MAPPER.readTree(input);
    }
  }

  @Test
  void allValidFixturesValidate() {
    JsonNode valid = fixtures.path("valid");
    assertThat(valid.size()).as("valid fixture count").isGreaterThanOrEqualTo(2);
    for (JsonNode script : valid) {
      Set<ValidationMessage> errors = schema.validate(script);
      assertThat(errors).as("valid fixture must validate: %s", errors).isEmpty();
    }
  }

  @Test
  void structurallyInvalidFixturesAreRejected() {
    JsonNode invalid = fixtures.path("invalidSchema");
    assertThat(invalid.size()).as("invalid schema fixture count").isGreaterThanOrEqualTo(4);
    for (JsonNode entry : invalid) {
      String name = entry.path("name").asText();
      Set<ValidationMessage> errors = schema.validate(entry.path("script"));
      assertThat(errors).as("fixture %s must be rejected", name).isNotEmpty();
    }
  }

  @Test
  void semanticallyInvalidFixturesRemainSchemaValid() {
    // Semantic problems (duplicate ids, dangling transitions, unreachable completion) are graph
    // checks owned by Micro6's QuestScriptValidator, not the JSON schema. The corpus must keep
    // those cases schema-valid so linting, not schema rejection, is what catches them.
    JsonNode invalid = fixtures.path("invalidSemantic");
    assertThat(invalid.size()).as("semantic fixture count").isGreaterThanOrEqualTo(5);
    for (JsonNode entry : invalid) {
      Set<ValidationMessage> errors = schema.validate(entry.path("script"));
      assertThat(errors)
          .as("semantic fixture %s must remain schema-valid", entry.path("name").asText())
          .isEmpty();
    }
  }

  @Test
  void mainAndTestFixtureMirrorsAreByteIdentical() throws Exception {
    assertByteIdentical(mainMirror(FIXTURES_NAME), testMirror(FIXTURES_NAME));
  }

  @Test
  void mirroredContractIsByteIdenticalToMicro6Owner() throws Exception {
    Optional<Path> micro6 = resolveMicro6QuestDir();
    assumeTrue(
        micro6.isPresent(),
        "Micro6 checkout not available; configure BUILDER_GOAL_MICRO6_PROJECT_DIR for the "
            + "cross-repository mirror gate");

    assertByteIdentical(micro6.get().resolve(SCHEMA_NAME), mainMirror(SCHEMA_NAME));
    assertByteIdentical(micro6.get().resolve(FIXTURES_NAME), mainMirror(FIXTURES_NAME));
    assertByteIdentical(micro6.get().resolve(FIXTURES_NAME), testMirror(FIXTURES_NAME));
  }
}
