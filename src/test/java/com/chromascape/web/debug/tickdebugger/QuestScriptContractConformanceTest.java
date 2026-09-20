/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.debug.tickdebugger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>the mirror equals the Micro6 resource byte-for-byte when a Micro6 checkout is available.
 * </ul>
 *
 * <p>Micro6 owns the contract. ChromaScape stores the mirror unchanged and proves drift.
 */
class QuestScriptContractConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCHEMA_RESOURCE = "contracts/quest/quest-script-v1.schema.json";
  private static final String FIXTURES_RESOURCE =
      "contracts/quest/quest-script-v1-fixtures.json";
  private static final String MICRO6_RESOURCE =
      "src/main/resources/micro6-contracts/quest/quest-script-v1.schema.json";

  private static final String[] MICRO6_FALLBACK_ROOTS = {
    "C:\\Dev\\IdeaProjects\\micro6",
    "/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/micro6",
    "/run/media/ikiddoi/dev-hdd/dev/Worktrees/micro6-sc265-luna",
  };

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
  void mirroredSchemaIsByteIdenticalToMicro6Owner() throws Exception {
    Path micro6 = resolveMicro6Resource();
    assertThat(Files.isRegularFile(micro6))
        .as(
            "Micro6 quest script schema missing at %s; SC-266 requires a byte-identical "
                + "quest.script.v1 contract in Micro6 (owner) and ChromaScape (mirror)",
            micro6)
        .isTrue();
    byte[] micro6Bytes = Files.readAllBytes(micro6);
    try (InputStream mirror =
        getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      assertThat(mirror).as("ChromaScape mirror schema on classpath").isNotNull();
      assertThat(mirror.readAllBytes())
          .as("quest-script-v1 schema must be byte-identical across repositories")
          .isEqualTo(micro6Bytes);
    }
  }

  private static Path resolveMicro6Resource() {
    String configured = System.getenv("BUILDER_GOAL_MICRO6_PROJECT_DIR");
    if (configured == null || configured.isBlank()) {
      configured = System.getProperty("builder.goal.micro6.project-dir", "");
    }
    if (configured != null && !configured.isBlank()) {
      Path path = Path.of(configured.trim()).resolve("runelite-client").resolve(MICRO6_RESOURCE);
      if (Files.isRegularFile(path)) {
        return path;
      }
    }
    for (String root : MICRO6_FALLBACK_ROOTS) {
      Path candidate = Path.of(root).resolve("runelite-client").resolve(MICRO6_RESOURCE);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return Path.of(MICRO6_FALLBACK_ROOTS[0]).resolve("runelite-client").resolve(MICRO6_RESOURCE);
  }
}
