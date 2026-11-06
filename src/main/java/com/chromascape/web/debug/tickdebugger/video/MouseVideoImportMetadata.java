package com.chromascape.web.debug.tickdebugger.video;

import java.util.Locale;

/**
 * Declared acquisition metadata for an external-video import. Every field is untrusted caller
 * metadata: the owner and audit actor are derived from the authenticated principal, never from this
 * record. Values are normalized and length-bounded before they participate in request identity.
 */
public record MouseVideoImportMetadata(
    String requestId,
    String declaredOrigin,
    String taskKey,
    String skill,
    String contextLabel,
    String placeLabel) {

  public static final int MAX_LABEL = 120;

  public MouseVideoImportMetadata {
    requestId = trim(requestId);
    declaredOrigin = normalizeOrigin(declaredOrigin);
    taskKey = label(taskKey);
    skill = label(skill);
    contextLabel = label(contextLabel);
    placeLabel = label(placeLabel);
  }

  public static String normalizeOrigin(String declared) {
    if (declared == null) {
      return "UNKNOWN";
    }
    return switch (declared.trim().toUpperCase(Locale.ROOT)) {
      case "HUMAN" -> "HUMAN";
      case "GENERATED" -> "GENERATED";
      default -> "UNKNOWN";
    };
  }

  private static String label(String value) {
    String trimmed = trim(value);
    if (trimmed == null) {
      return null;
    }
    return trimmed.length() <= MAX_LABEL ? trimmed : trimmed.substring(0, MAX_LABEL);
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
