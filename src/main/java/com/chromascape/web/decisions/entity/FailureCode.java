package com.chromascape.web.decisions.entity;

/**
 * Enumeration of failure codes for script decision failures.
 *
 * <p>These codes provide machine-readable labels for common failure modes, enabling AI-powered
 * analysis and pattern detection.
 */
public enum FailureCode {

  // ==================== Interaction Failures ====================

  /**
   * Click did not hit the intended target. Used when actual click position deviates from expected
   * target.
   */
  CLICK_MISS,

  /**
   * Click was blocked by UI element or overlay. Used when click was intercepted before reaching
   * target.
   */
  CLICK_BLOCKED,

  /** Target entity could not be found. Used when entity lookup returns null or invalid result. */
  ENTITY_NOT_FOUND,

  /**
   * Target entity is out of interaction range. Used when distance check fails before interaction.
   */
  ENTITY_OUT_OF_RANGE,

  /** Target entity is not visible on screen. Used when entity exists but cannot be clicked. */
  ENTITY_NOT_VISIBLE,

  // ==================== Navigation Failures ====================

  /** No valid path could be calculated. Used when pathfinding returns empty or null. */
  PATH_NOT_FOUND,

  /** Path is blocked by obstacle. Used when navigation cannot proceed along calculated path. */
  PATH_BLOCKED,

  /** Character is stuck in a movement loop. Used when position repeats without progress. */
  STUCK_IN_LOOP,

  /** Destination is unreachable. Used when target location is invalid or inaccessible. */
  DESTINATION_UNREACHABLE,

  // ==================== Timing Failures ====================

  /** Operation timed out while waiting. Used when condition wait exceeds timeout threshold. */
  TIMEOUT_WAITING,

  /** Animation did not complete in time. Used when animation wait exceeds expected duration. */
  TIMEOUT_ANIMATION,

  /** Server response timeout. Used when game server does not respond within expected time. */
  TIMEOUT_SERVER,

  // ==================== State Failures ====================

  /** Inventory is full. Used when attempting to add item to full inventory. */
  INVENTORY_FULL,

  /** Required resources are missing. Used when consumables or tools are not available. */
  OUT_OF_RESOURCES,

  /** Game state does not match expected condition. Used when precondition validation fails. */
  WRONG_GAME_STATE,

  /** Player is in wrong location. Used when location check fails before action. */
  WRONG_LOCATION,

  /** Required level/stat is not met. Used when skill level requirement is not satisfied. */
  INSUFFICIENT_LEVEL,

  // ==================== Combat Failures ====================

  /** Player died during action. Used when death event interrupts script flow. */
  PLAYER_DIED,

  /** Target entity died or disappeared. Used when combat target becomes invalid. */
  TARGET_DIED,

  /** Health is too low for action. Used when safety check prevents combat action. */
  LOW_HEALTH,

  /** Under attack and should retreat. Used when danger level exceeds safe threshold. */
  DANGER_OVERRIDE,

  // ==================== UI Failures ====================

  /** Required widget/interface not found. Used when UI component lookup fails. */
  WIDGET_NOT_FOUND,

  /** Interface is not open. Used when required UI window is closed. */
  INTERFACE_NOT_OPEN,

  /** Menu option not available. Used when right-click menu option is missing. */
  MENU_OPTION_NOT_FOUND,

  // ==================== System Failures ====================

  /** Client is not logged in. Used when connection status prevents action. */
  NOT_LOGGED_IN,

  /** Connection lost to game server. Used when network disconnect is detected. */
  CONNECTION_LOST,

  /** Script was interrupted or stopped. Used when external event stops script execution. */
  SCRIPT_INTERRUPTED,

  /** Unknown or uncategorized failure. Used when failure does not match any specific code. */
  UNKNOWN
}
