package com.chromascape.web.debug.tickdebugger.dto;

import java.util.Map;

/**
 * DTO representing a single tick card with summarized section data.
 *
 * <p>Used by the replay page to render tick-based log organization with category filters and
 * section counts.
 */
public class TickCardData {
  private Long tick;
  private String playerSummary;
  private Map<String, Integer> sectionCounts;
  private Map<String, Object> sectionData;

  public TickCardData() {}

  public TickCardData(
      Long tick,
      String playerSummary,
      Map<String, Integer> sectionCounts,
      Map<String, Object> sectionData) {
    this.tick = tick;
    this.playerSummary = playerSummary;
    this.sectionCounts = sectionCounts;
    this.sectionData = sectionData;
  }

  public Long getTick() {
    return tick;
  }

  public void setTick(Long tick) {
    this.tick = tick;
  }

  public String getPlayerSummary() {
    return playerSummary;
  }

  public void setPlayerSummary(String playerSummary) {
    this.playerSummary = playerSummary;
  }

  public Map<String, Integer> getSectionCounts() {
    return sectionCounts;
  }

  public void setSectionCounts(Map<String, Integer> sectionCounts) {
    this.sectionCounts = sectionCounts;
  }

  public Map<String, Object> getSectionData() {
    return sectionData;
  }

  public void setSectionData(Map<String, Object> sectionData) {
    this.sectionData = sectionData;
  }
}
