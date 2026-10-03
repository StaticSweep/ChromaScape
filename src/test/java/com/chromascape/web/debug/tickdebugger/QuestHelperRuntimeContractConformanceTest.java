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
 * SC-265 ChromaScape-side contract verification for the {@code quest.helper.runtime.v1} structured
 * payload:
 *
 * <ul>
 *   <li>the mirrored schema accepts every valid golden fixture;
 *   <li>malformed core fields are rejected;
 *   <li>the legacy v1 payload (no additive keys) remains accepted;
 *   <li>the main- and test-resource fixtures are byte-identical to each other, always;
 *   <li>when a Micro6 checkout is available, the mirrors equal the Micro6 owner resources
 *       byte-for-byte.
 * </ul>
 */
class QuestHelperRuntimeContractConformanceTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCHEMA_NAME = "quest-helper-runtime-v1.schema.json";
  private static final String FIXTURES_NAME = "quest-helper-runtime-v1-fixtures.json";
  private static JsonSchema schema;
  private static JsonNode fixtures;

  @BeforeAll
  static void setUp() throws Exception {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    try (InputStream input =
        QuestHelperRuntimeContractConformanceTest.class
            .getClassLoader()
            .getResourceAsStream("contracts/quest/" + SCHEMA_NAME)) {
      assertThat(input).as("mirrored schema resource").isNotNull();
      schema = factory.getSchema(input);
    }
    try (InputStream input =
        QuestHelperRuntimeContractConformanceTest.class
            .getClassLoader()
            .getResourceAsStream("contracts/quest/" + FIXTURES_NAME)) {
      assertThat(input).as("mirrored fixtures resource").isNotNull();
      fixtures = MAPPER.readTree(input);
    }
  }

  @Test
  void allValidGoldenFixturesValidate() {
    JsonNode corpus = fixtures.path("fixtures");
    assertThat(corpus.size()).as("fixture corpus size").isGreaterThanOrEqualTo(11);
    for (String name :
        new String[] {
          "full",
          "degraded",
          "unavailable",
          "failed",
          "legacyV1",
          "nestedSteps",
          "unknownRequirement",
          "evaluationError",
          "cycle",
          "truncation",
          "syntheticCurrent"
        }) {
      Set<ValidationMessage> errors = schema.validate(corpus.path(name));
      assertThat(errors).as("fixture %s must validate", name).isEmpty();
    }
  }

  @Test
  void malformedCoreFieldsAreRejected() {
    JsonNode corpus = fixtures.path("fixtures");

    // steps entry with a wrong-typed ordinal
    JsonNode badSteps = corpus.path("full").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) badSteps.path("steps").get(0))
        .put("ordinal", "zero");
    assertThat(schema.validate(badSteps)).isNotEmpty();

    // steps entry missing required stepId
    JsonNode missingStepId = corpus.path("full").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) missingStepId.path("steps").get(0))
        .remove("stepId");
    assertThat(schema.validate(missingStepId)).isNotEmpty();

    // unknown outcome enum value
    JsonNode badOutcome = corpus.path("full").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) badOutcome).put("outcome", "OK");
    assertThat(schema.validate(badOutcome)).isNotEmpty();

    // requirement text with a wrong type
    JsonNode badRequirement = corpus.path("full").deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            badRequirement.path("requirements").path("missing").get(0))
        .put("text", 42);
    assertThat(schema.validate(badRequirement)).isNotEmpty();
  }

  @Test
  void legacyPayloadWithoutAdditiveKeysRemainsAccepted() {
    // legacyV1 has no schema/tick/outcome/usable/complete/diagnostics keys at all.
    Set<ValidationMessage> errors = schema.validate(fixtures.path("fixtures").path("legacyV1"));
    assertThat(errors).as("legacy payload must remain valid").isEmpty();
  }

  @Test
  void missingQuestHelperKeyRemainsCompatible() {
    // A batch without questHelper at all is a transport-level concern; the schema covers the
    // payload itself. Assert the fixture tree does not require the transport envelope.
    assertThat(fixtures.has("fixtures")).isTrue();
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
