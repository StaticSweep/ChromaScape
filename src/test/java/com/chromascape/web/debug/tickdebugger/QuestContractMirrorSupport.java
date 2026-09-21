/*
 * Copyright (c) 2026, ChromaScape
 * All rights reserved.
 */
package com.chromascape.web.debug.tickdebugger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Shared helpers for the SC-265/SC-266 cross-repository quest contract mirrors.
 *
 * <p>ChromaScape stores the contract mirrors under {@code src/main/resources/contracts/quest} and
 * {@code src/test/resources/contracts/quest}. The Micro6 owner checkout is optional: normal
 * ChromaScape CI does not provision it, so a cross-repository comparison is skipped when the
 * checkout cannot be located, while the in-repository mirror copies are always compared.
 */
final class QuestContractMirrorSupport {
  static final String MAIN_MIRROR_DIR = "src/main/resources/contracts/quest";
  static final String TEST_MIRROR_DIR = "src/test/resources/contracts/quest";
  static final String MICRO6_QUEST_DIR =
      "runelite-client/src/main/resources/micro6-contracts/quest";

  private static final String[] MICRO6_FALLBACK_ROOTS = {
    "C:\\Dev\\IdeaProjects\\micro6",
    "C:\\Dev\\IdeaProjects\\micro6-sc266-verify",
    "/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/micro6",
    "/run/media/ikiddoi/dev-hdd/dev/Worktrees/micro6-sc265-luna",
  };

  private QuestContractMirrorSupport() {}

  /**
   * Resolve the Micro6 quest contract directory. The documented repository settings are {@code
   * BUILDER_GOAL_MICRO6_PROJECT_DIR} / {@code builder.goal.micro6.project-dir}; an explicitly
   * configured location that does not contain the contracts fails loudly instead of silently
   * skipping the comparison.
   *
   * @return the Micro6 quest directory when it exists, otherwise empty
   */
  static Optional<Path> resolveMicro6QuestDir() {
    String configured = System.getenv("BUILDER_GOAL_MICRO6_PROJECT_DIR");
    if (configured == null || configured.isBlank()) {
      configured = System.getProperty("builder.goal.micro6.project-dir", "");
    }
    if (configured != null && !configured.isBlank()) {
      Path configuredDir = Path.of(configured.trim()).resolve(MICRO6_QUEST_DIR);
      if (!Files.isDirectory(configuredDir)) {
        throw new IllegalStateException(
            "The configured Micro6 project does not contain the quest contracts: " + configuredDir);
      }
      return Optional.of(configuredDir);
    }
    for (String root : MICRO6_FALLBACK_ROOTS) {
      Path candidate = Path.of(root).resolve(MICRO6_QUEST_DIR);
      if (Files.isDirectory(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  static Path mainMirror(String name) {
    return Path.of(MAIN_MIRROR_DIR, name);
  }

  static Path testMirror(String name) {
    return Path.of(TEST_MIRROR_DIR, name);
  }

  static void assertByteIdentical(Path source, Path mirror) throws IOException {
    assertThat(source).as("contract source missing: %s", source).isRegularFile();
    assertThat(mirror).as("contract mirror missing: %s", mirror).isRegularFile();
    String sourceDigest = sha256(Files.readAllBytes(source));
    String mirrorDigest = sha256(Files.readAllBytes(mirror));
    assertThat(mirrorDigest)
        .as("contract bytes differ: %s [%s] vs %s [%s]", source, sourceDigest, mirror, mirrorDigest)
        .isEqualTo(sourceDigest);
  }

  private static String sha256(byte[] bytes) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
    return lowercaseHex(digest.digest(bytes));
  }

  private static String lowercaseHex(byte[] digest) {
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
