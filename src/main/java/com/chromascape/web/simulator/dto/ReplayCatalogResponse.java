package com.chromascape.web.simulator.dto;

import java.util.List;

/**
 * Response DTO for the replay catalog endpoint.
 *
 * <p>Lists all available recordings that have associated tick snapshot data suitable for replay on
 * the OpenRune simulator.
 */
public class ReplayCatalogResponse {

  private List<ReplayCatalogEntry> entries;
  private int total;

  public ReplayCatalogResponse() {}

  public ReplayCatalogResponse(List<ReplayCatalogEntry> entries, int total) {
    this.entries = entries;
    this.total = total;
  }

  public List<ReplayCatalogEntry> getEntries() {
    return entries;
  }

  public void setEntries(List<ReplayCatalogEntry> entries) {
    this.entries = entries;
  }

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }
}
