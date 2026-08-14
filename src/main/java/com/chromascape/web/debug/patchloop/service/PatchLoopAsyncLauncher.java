package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.event.PatchLoopIterationCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Launches async processing for patch loop iterations after database transactions commit.
 *
 * <p>Uses Spring's @TransactionalEventListener to ensure async worker starts only after the
 * iteration is successfully persisted, preventing "iteration not found" race conditions.
 */
@Component
public class PatchLoopAsyncLauncher {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopAsyncLauncher.class);

  private final PatchLoopAsyncWorker asyncWorker;

  public PatchLoopAsyncLauncher(PatchLoopAsyncWorker asyncWorker) {
    this.asyncWorker = asyncWorker;
  }

  /**
   * Handles iteration created events after transaction commit.
   *
   * <p>This listener is invoked AFTER the database transaction commits, ensuring the iteration is
   * visible to the async worker.
   *
   * @param event the iteration created event
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onIterationCreated(PatchLoopIterationCreatedEvent event) {
    logger.debug("Launching async processing for iteration: {}", event.getIterationId());
    asyncWorker.processIterationAsync(event.getIterationId());
  }
}
