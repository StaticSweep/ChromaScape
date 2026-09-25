package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureBatch;
import com.chromascape.web.aiplayer.routine.entity.RoutineShadowRun;
import com.chromascape.web.aiplayer.routine.event.RoutineCaptureBatchAccepted;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureBatchRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineShadowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Dispatches accepted capture batches after commit without holding up ingestion. */
@Component
public class RoutineShadowCoordinator {
  private final RoutineShadowService shadows;
  private final RoutineShadowRunRepository runs;
  private final RoutineCaptureBatchRepository batches;
  private final ObjectMapper mapper;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "routine-shadow-coordinator");
            thread.setDaemon(true);
            return thread;
          });

  public RoutineShadowCoordinator(
      RoutineShadowService shadows,
      RoutineShadowRunRepository runs,
      RoutineCaptureBatchRepository batches,
      ObjectMapper mapper) {
    this.shadows = shadows;
    this.runs = runs;
    this.batches = batches;
    this.mapper = mapper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void accepted(RoutineCaptureBatchAccepted event) {
    executor.submit(
        () -> {
          try {
            shadows.process(event);
          } catch (RuntimeException failure) {
            shadows.failForSession(
                event.sessionId(), "SHADOW_PROCESSING_FAILED", failure.getMessage());
          }
        });
  }

  /** Replays durable accepted batches after a process restart or a failed async delivery. */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000L)
  public void recover() {
    for (RoutineShadowRun run : runs.findByState("ACTIVE")) {
      for (RoutineCaptureBatch batch :
          batches.findBySessionIdOrderByAcceptedAtAscIdAsc(run.getSessionId())) {
        try {
          JsonNode parsed = mapper.readTree(batch.getValuesJson());
          List<JsonNode> values = new java.util.ArrayList<>();
          parsed.elements().forEachRemaining(values::add);
          accepted(
              new RoutineCaptureBatchAccepted(
                  batch.getSessionId(), batch.getKind(), batch.getSequence(), values));
        } catch (Exception failure) {
          shadows.failForSession(
              run.getSessionId(), "SHADOW_RECOVERY_FAILED", failure.getMessage());
        }
      }
    }
  }
}
