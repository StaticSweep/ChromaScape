package com.chromascape.web.debug.patchloop.event;

/**
 * Event published after a patch loop iteration is created and persisted to the database.
 *
 * <p>This event is used to trigger async processing after the transaction commits, preventing race
 * conditions where the async worker might try to fetch an iteration before it's committed.
 */
public class PatchLoopIterationCreatedEvent {

  private final String iterationId;

  public PatchLoopIterationCreatedEvent(String iterationId) {
    this.iterationId = iterationId;
  }

  public String getIterationId() {
    return iterationId;
  }
}
