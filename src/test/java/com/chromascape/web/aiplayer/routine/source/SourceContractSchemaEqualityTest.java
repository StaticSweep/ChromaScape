package com.chromascape.web.aiplayer.routine.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * SC-810 cross-repository contract proof: the canonical {@code task-source-manifest/v1} JSON schema
 * is owned by Micro6 and mirrored byte-for-byte into ChromaScape. Any drift between the two files
 * fails this test, so neither repository can silently verify against a different contract.
 *
 * <p>The Micro6 checkout is located through {@code BUILDER_GOAL_MICRO6_PROJECT_DIR} (the same
 * variable the runtime uses), falling back to the documented sibling checkout. The test fails
 * closed when the mirror cannot be found — it never waives itself.
 */
class SourceContractSchemaEqualityTest {

  private static final String SCHEMA_RESOURCE = "contracts/source/task-source-manifest-v1.schema.json";

  private static final String SCHEMA_IN_MICRO6 =
      "src/main/resources/micro6-contracts/task-source-manifest-v1.schema.json";

  /**
   * Documented sibling checkouts, in preference order. The Windows path is the canonical project
   * identity; the WSL mount is the same checkout seen from Linux. A bare Windows path is not a
   * usable {@link Path} on Linux, so listing both keeps the fallback meaningful on either platform
   * instead of silently resolving to an unreachable relative name.
   */
  private static final String[] FALLBACK_ROOTS = {
    "C:\\Dev\\IdeaProjects\\micro6", "/mnt/c/Dev/IdeaProjects/micro6",
  };

  private static Path schemaUnder(String root) {
    return Path.of(root).resolve("runelite-client").resolve(SCHEMA_IN_MICRO6);
  }

  private static Path micro6Schema() {
    String configured = System.getenv("BUILDER_GOAL_MICRO6_PROJECT_DIR");
    if (configured == null || configured.isBlank()) {
      configured = System.getProperty("builder.goal.micro6.project-dir", "");
    }
    if (configured != null && !configured.isBlank()) {
      return schemaUnder(configured.trim());
    }
    for (String root : FALLBACK_ROOTS) {
      Path candidate = schemaUnder(root);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    // Nothing resolved: return the canonical location so the assertion reports it and fails closed.
    return schemaUnder(FALLBACK_ROOTS[0]);
  }

  @Test
  void canonicalSchemaIsByteIdenticalAcrossRepositories() throws Exception {
    Path mirrored = micro6Schema();
    assertTrue(Files.isRegularFile(mirrored),
        "Micro6 canonical schema is missing at " + mirrored + "; SC-810 requires both "
            + "repositories to share one byte-identical task-source-manifest/v1 contract");

    byte[] micro6Bytes = Files.readAllBytes(mirrored);
    try (var stream = getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
      assertTrue(stream != null, "ChromaScape mirror schema is missing on the classpath");
      assertArrayEquals(micro6Bytes, stream.readAllBytes(),
          "task-source-manifest/v1 schema must be byte-identical in Micro6 (owner) and "
              + "ChromaScape (mirror)");
    }
  }
}
