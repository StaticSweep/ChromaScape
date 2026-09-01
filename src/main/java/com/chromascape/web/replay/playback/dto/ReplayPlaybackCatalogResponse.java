package com.chromascape.web.replay.playback.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** Paginated catalog response for observed replay evidence. */
public class ReplayPlaybackCatalogResponse {

  public static final String SCHEMA = "replay-playback-catalog/v1";

  private final String schema = SCHEMA;
  private List<ReplayPlaybackCatalogEntry> entries;
  private String nextCursor;
  private boolean hasMore;

  /** Retained only for source compatibility with the pre-v1 DTO; it is not part of the v1 wire schema. */
  @JsonIgnore private int legacyTotal;

  public ReplayPlaybackCatalogResponse() {
    this.entries = List.of();
  }

  public ReplayPlaybackCatalogResponse(
      List<ReplayPlaybackCatalogEntry> entries, String nextCursor, boolean hasMore) {
    this.entries = entries;
    this.nextCursor = nextCursor;
    this.hasMore = hasMore;
  }

  /** Compatibility constructor used by older callers while the route migrates to v1. */
  public ReplayPlaybackCatalogResponse(List<ReplayPlaybackCatalogEntry> entries, int total) {
    this(entries, null, false);
    this.legacyTotal = total;
  }

  public String getSchema() {
    return schema;
  }

  public List<ReplayPlaybackCatalogEntry> getEntries() {
    return entries;
  }

  public void setEntries(List<ReplayPlaybackCatalogEntry> entries) {
    this.entries = entries;
  }

  public String getNextCursor() {
    return nextCursor;
  }

  public void setNextCursor(String nextCursor) {
    this.nextCursor = nextCursor;
  }

  public boolean isHasMore() {
    return hasMore;
  }

  public void setHasMore(boolean hasMore) {
    this.hasMore = hasMore;
  }

  /** @deprecated v1 deliberately does not expose an unbounded total count. */
  @Deprecated
  @JsonIgnore
  public int getTotal() {
    return legacyTotal;
  }

  /** @deprecated v1 deliberately does not expose an unbounded total count. */
  @Deprecated
  @JsonIgnore
  public void setTotal(int total) {
    this.legacyTotal = total;
  }
}
