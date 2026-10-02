package com.chromascape.web.aiplayer.prototype;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical normalization and classification of captured player actions.
 *
 * <p>Normalization strips RuneLite markup, replaces non-breaking spaces, trims, collapses repeated
 * whitespace, and lowercases with {@link Locale#ROOT}. Server-side code owns this normalization;
 * the client preserves raw option and target text.
 */
public final class FishingActionNormalizer {

  /** Supported fishing options. */
  public static final Set<String> SUPPORTED_OPTIONS =
      com.chromascape.web.recording.provenance.BehaviorSemanticContract.actionIds().stream()
          .filter(id -> id.startsWith("INTERACT_NPC_"))
          .map(id -> id.substring("INTERACT_NPC_".length()).toLowerCase(java.util.Locale.ROOT))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  /** Supported NPC menu-action families. */
  public static final Set<String> SUPPORTED_MENU_ACTIONS =
      Set.of(
          "NPC_FIRST_OPTION",
          "NPC_SECOND_OPTION",
          "NPC_THIRD_OPTION",
          "NPC_FOURTH_OPTION",
          "NPC_FIFTH_OPTION");

  /** The canonical fishing spot target. */
  public static final String FISHING_SPOT_TARGET = "fishing spot";

  /** How a captured player action is classified for prototype one. */
  public enum ActionClass {
    /** A supported fishing NPC interaction. */
    SUPPORTED_FISHING,
    /** A {@code CANCEL} action that is ignored. */
    IGNORED,
    /** Any other gameplay action; makes the recording unsuitable for prototype one. */
    UNSUPPORTED
  }

  /** Classification of a single player action. */
  public record ActionClassification(ActionClass actionClass, SemanticAction semanticAction) {

    public static ActionClassification ignored() {
      return new ActionClassification(ActionClass.IGNORED, null);
    }

    public static ActionClassification unsupported() {
      return new ActionClassification(ActionClass.UNSUPPORTED, null);
    }

    public static ActionClassification supported(SemanticAction semanticAction) {
      return new ActionClassification(ActionClass.SUPPORTED_FISHING, semanticAction);
    }
  }

  private FishingActionNormalizer() {}

  /** Canonicalizes a fishing option, returning null when it is not a supported fishing option. */
  public static String normalizeOption(String rawOption) {
    String normalized = canonicalize(rawOption);
    if (normalized == null || !SUPPORTED_OPTIONS.contains(normalized)) {
      return null;
    }
    return normalized;
  }

  /** Canonicalizes a target/name string (markup stripped, whitespace collapsed, lowercased). */
  public static String canonicalize(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.replaceAll("(?i)<[^>]*>", "").replace('\u00a0', ' ');
    cleaned = cleaned.trim().replaceAll("\\s+", " ");
    if (cleaned.isEmpty()) {
      return null;
    }
    return cleaned.toLowerCase(Locale.ROOT);
  }

  /**
   * Classifies a captured player action.
   *
   * @param action the raw captured action
   * @return the classification; never null
   */
  public static ActionClassification classify(PlayerAction action) {
    if (action == null) {
      return ActionClassification.unsupported();
    }
    String menuAction = action.menuAction() != null ? action.menuAction() : "";
    String option = action.option() != null ? action.option() : "";
    if ("CANCEL".equalsIgnoreCase(menuAction) || "Cancel".equalsIgnoreCase(option.trim())) {
      return ActionClassification.ignored();
    }
    if (!SUPPORTED_MENU_ACTIONS.contains(menuAction)) {
      return ActionClassification.unsupported();
    }
    String normalizedOption = normalizeOption(option);
    if (normalizedOption == null) {
      return ActionClassification.unsupported();
    }
    String normalizedTarget = canonicalize(action.target());
    if (normalizedTarget == null || !normalizedTarget.contains(FISHING_SPOT_TARGET)) {
      return ActionClassification.unsupported();
    }
    return ActionClassification.supported(
        SemanticAction.interactNpc(normalizedOption, FISHING_SPOT_TARGET));
  }
}
