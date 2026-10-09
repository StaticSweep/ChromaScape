package com.chromascape.web.replay.session;

/**
 * One recorded action in a compiled journal: the relative tick it was captured at, its stable
 * command identity, and the recorded expectation (observation revision) the server-computed outcome
 * is checked against.
 */
public record XrspsRecordedAction(
    long relativeTick,
    String commandId,
    String actionType,
    String payloadHash,
    long expectedRevision,
    com.fasterxml.jackson.databind.JsonNode payload) {
  /** Diagnostic compatibility; production dispatch requires the actual semantic payload. */
  public XrspsRecordedAction(
      long relativeTick,
      String commandId,
      String actionType,
      String payloadHash,
      long expectedRevision) {
    this(relativeTick, commandId, actionType, payloadHash, expectedRevision, null);
  }
}
