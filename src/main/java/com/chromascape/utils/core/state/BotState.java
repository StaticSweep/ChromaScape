package com.chromascape.utils.core.state;

/**
 * definitions of the various high-level semantic states the bot can be in.
 *
 * <p>These states are used for visualization on the frontend to give the user insight into what the
 * bot is currently "thinking" or doing.
 */
public enum BotState {

  /** The bot is actively scanning the screen for targets (e.g. finding colours). */
  SEARCHING("Searching", "primary"),

  /** The bot is performing an input action (e.g. clicking, typing). */
  ACTING("Acting", "success"),

  /** The bot is waiting or idle (e.g. sleeping between actions). */
  WAITING("Waiting", "warning"),

  /** The bot has encountered an error or exception. */
  ERROR("Error", "danger");

  private final String displayName;
  private final String cssClass;

  BotState(String displayName, String cssClass) {
    this.displayName = displayName;
    this.cssClass = cssClass;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getCssClass() {
    return cssClass;
  }
}
