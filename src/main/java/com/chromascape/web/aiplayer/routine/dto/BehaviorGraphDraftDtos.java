package com.chromascape.web.aiplayer.routine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public final class BehaviorGraphDraftDtos {
  private BehaviorGraphDraftDtos() {}

  public record DraftUpdateRequest(
      String schema,
      @JsonProperty("base_revision") long baseRevision,
      JsonNode definition,
      JsonNode layout) {}

  public record DraftCreateRequest(
      String schema,
      @JsonProperty("draft_id") String draftId,
      @JsonProperty("graph_id") String graphId,
      @JsonProperty("task_key") String taskKey,
      @JsonProperty("profile_id") String profileId,
      @JsonProperty("profile_version") String profileVersion) {}

  public record PublishRequest(
      String schema,
      @JsonProperty("base_revision") long baseRevision,
      @JsonProperty("graph_hash") String graphHash,
      @JsonProperty("layout_hash") String layoutHash) {}
}
