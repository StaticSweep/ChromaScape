package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single state/action example persisted as one JSONL line in the fishing dataset.
 *
 * @param schemaVersion the dataset schema version (1)
 * @param recordingId the source recording
 * @param sessionId the source debug session
 * @param sourceTick the authoritative action tick
 * @param snapshotTick the aligned snapshot tick (exact or {@code sourceTick - 1})
 * @param state the extracted feature state
 * @param action the demonstrated semantic action
 */
public record DatasetExample(
    @JsonProperty("schemaVersion") int schemaVersion,
    @JsonProperty("recordingId") String recordingId,
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("sourceTick") long sourceTick,
    @JsonProperty("snapshotTick") long snapshotTick,
    @JsonProperty("state") FishingState state,
    @JsonProperty("action") SemanticAction action,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @JsonProperty("frameDigest")
        String frameDigest,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @JsonProperty("semanticContractDigest")
        String semanticContractDigest) {
  public DatasetExample(
      int schemaVersion,
      String recordingId,
      String sessionId,
      long sourceTick,
      long snapshotTick,
      FishingState state,
      SemanticAction action) {
    this(
        schemaVersion, recordingId, sessionId, sourceTick, snapshotTick, state, action, null, null);
  }
}
