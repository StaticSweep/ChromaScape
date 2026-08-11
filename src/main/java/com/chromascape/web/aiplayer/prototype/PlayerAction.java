package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * A raw {@code PLAYER_ACTION}/{@code MENU_CLICK} captured by Micro6 and persisted in {@code
 * tick_logs}.
 *
 * <p>{@link #sourceTick()} is the authoritative tick captured at click time; {@link #storedTick()}
 * is the enclosing batch tick used for repository ordering only.
 *
 * @param sourceTick the authoritative tick captured at click time
 * @param sequence the session-scoped monotonic sequence (disambiguates same-tick clicks)
 * @param option the raw menu option text
 * @param target the raw menu target text
 * @param menuAction the raw RuneLite menu action name
 * @param identifier the menu entry identifier
 * @param param0 the first action parameter
 * @param param1 the second action parameter
 * @param storedTick the enclosing tick batch tick
 */
public record PlayerAction(
    @JsonProperty("sourceTick") long sourceTick,
    @JsonProperty("sequence") int sequence,
    @JsonProperty("option") String option,
    @JsonProperty("target") String target,
    @JsonProperty("menuAction") String menuAction,
    @JsonProperty("identifier") int identifier,
    @JsonProperty("param0") int param0,
    @JsonProperty("param1") int param1,
    @JsonProperty("storedTick") long storedTick) {

  /** Parses a player action from persisted log details. */
  public static PlayerAction fromDetails(Map<String, Object> details, long storedTick) {
    if (details == null) {
      return null;
    }
    return new PlayerAction(
        longValue(details.get("sourceTick")),
        intValue(details.get("sequence")),
        stringValue(details.get("option")),
        stringValue(details.get("target")),
        stringValue(details.get("menuAction")),
        intValue(details.get("identifier")),
        intValue(details.get("param0")),
        intValue(details.get("param1")),
        storedTick);
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        return 0L;
      }
    }
    return 0L;
  }

  private static int intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  private static String stringValue(Object value) {
    return value != null ? String.valueOf(value) : "";
  }
}
