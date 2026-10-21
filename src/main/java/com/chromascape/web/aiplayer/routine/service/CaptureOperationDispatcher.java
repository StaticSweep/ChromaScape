package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureOperationRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded after-commit and recovery dispatcher for capture recorder operations. */
@Service
public class CaptureOperationDispatcher {
  private static final Duration LEASE = Duration.ofMinutes(2);
  // request_json stores the whole operation payload (endpoint, idempotency_key, identity,
  // session_id, start_tick, started_at, video_capture ...) while these records deliberately read
  // only the few fields the recorder needs. A default mapper treats every other property as an
  // error, so parsing threw CAPTURE_START_REQUEST_INVALID and the capture never left STARTING.
  private static final ObjectMapper OPERATION_MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final RoutineCaptureOperationRepository operations;
  private final CaptureRecordingGateway gateway;
  private final CaptureOperationResultService results;
  private final TransactionTemplate transactions;
  private final com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator identities;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          0,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          new ThreadPoolExecutor.AbortPolicy());

  public CaptureOperationDispatcher(
      RoutineCaptureOperationRepository operations,
      CaptureRecordingGateway gateway,
      CaptureOperationResultService results,
      PlatformTransactionManager transactionManager) {
    this(operations, gateway, results, transactionManager, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CaptureOperationDispatcher(
      RoutineCaptureOperationRepository operations,
      CaptureRecordingGateway gateway,
      CaptureOperationResultService results,
      PlatformTransactionManager transactionManager,
      com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator identities) {
    this.identities = identities;
    this.operations = operations;
    this.gateway = gateway;
    this.results = results;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void dispatchAfterCommit(Long operationId) {
    Runnable dispatch = () -> submit(operationId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch.run();
            }
          });
    } else {
      dispatch.run();
    }
  }

  private void submit(Long operationId) {
    try {
      executor.execute(() -> dispatch(operationId));
    } catch (RejectedExecutionException ignored) {
      /* recovery scanner owns the durable retry */
    }
  }

  public Claim claim(Long operationId) {
    return transactions.execute(status -> claimInTransaction(operationId));
  }

  private Claim claimInTransaction(Long operationId) {
    RoutineCaptureOperation operation = operations.findWithLockById(operationId).orElse(null);
    if (operation == null || !claimable(operation, Instant.now())) return null;
    if (identities != null) {
      try {
        var payload = OPERATION_MAPPER.readTree(operation.getRequestJson());
        var identity =
            OPERATION_MAPPER.treeToValue(
                payload.path("identity"),
                com.chromascape.web.aiplayer.contract.CurrentSliceIdentity.class);
        identities.requireOperation(identity, "capture");
        if ("START".equals(operation.getOperation()))
          identities.requireOperation(identity, "video");
      } catch (com.chromascape.web.aiplayer.contract.CurrentSliceContractException e) {
        throw e;
      } catch (Exception e) {
        throw new com.chromascape.web.aiplayer.contract.CurrentSliceContractException(
            com.chromascape.web.aiplayer.contract.CurrentSliceFailureCode.IDENTITY_GROUP_MISSING,
            "CAPTURE_OPERATION_IDENTITY_INVALID");
      }
    }
    String attemptId = java.util.UUID.randomUUID().toString();
    operation.setState("LEASED");
    operation.setAttemptId(attemptId);
    operation.setLeaseExpiresAt(Instant.now().plus(LEASE));
    operation.setAttempts(operation.getAttempts() + 1);
    operation.setLastError(null);
    operations.save(operation);
    return new Claim(
        operation.getId(),
        operation.getSessionId(),
        operation.getOperation(),
        operation.getCorrelationId(),
        attemptId,
        operation.getRequestJson());
  }

  private void dispatch(Long operationId) {
    Claim claim = claim(operationId);
    if (claim == null) return;
    try {
      if ("START".equals(claim.kind())) {
        var request = parseStart(claim.requestJson());
        results.applyStart(
            claim.id(),
            claim.attemptId(),
            gateway.startOrFind(claim.sessionId(), request.taskKey(), claim.correlationId()));
      } else {
        var request = parseComplete(claim.requestJson());
        results.applyComplete(
            claim.id(),
            claim.attemptId(),
            gateway.completeOrFind(
                claim.sessionId(),
                request.debugSessionId(),
                request.recordingId(),
                request.finalTick(),
                claim.correlationId()));
      }
    } catch (Exception exception) {
      // The attempt lease expires and recovery can safely perform lookup-first reconciliation.
      markUncertain(claim.id(), claim.attemptId(), exception.getMessage());
    }
  }

  @Scheduled(fixedDelay = 5000L)
  public void recover() {
    for (RoutineCaptureOperation operation :
        operations.findClaimable(
            Instant.now(), org.springframework.data.domain.PageRequest.of(0, 16))) {
      submit(operation.getId());
    }
  }

  public void markUncertain(Long operationId, String attemptId, String error) {
    transactions.executeWithoutResult(
        status -> markUncertainInTransaction(operationId, attemptId, error));
  }

  private void markUncertainInTransaction(Long operationId, String attemptId, String error) {
    RoutineCaptureOperation operation = operations.findWithLockById(operationId).orElse(null);
    if (operation == null
        || !"LEASED".equals(operation.getState())
        || !attemptId.equals(operation.getAttemptId())) return;
    operation.setState("UNCERTAIN");
    operation.setLastError(
        error == null
            ? "CAPTURE_OPERATION_UNCERTAIN"
            : error.substring(0, Math.min(1000, error.length())));
    operation.setAttemptId(null);
    operation.setLeaseExpiresAt(null);
    operation.setNextAttemptAt(Instant.now().plusSeconds(30));
    operations.save(operation);
  }

  public void reconcile(Long operationId) {
    dispatchAfterCommit(operationId);
  }

  private boolean claimable(RoutineCaptureOperation operation, Instant now) {
    return ("PENDING".equals(operation.getState()) || "UNCERTAIN".equals(operation.getState()))
            && !operation.getNextAttemptAt().isAfter(now)
        || "LEASED".equals(operation.getState())
            && operation.getLeaseExpiresAt() != null
            && operation.getLeaseExpiresAt().isBefore(now);
  }

  StartRequest parseStart(String json) {
    try {
      return OPERATION_MAPPER.readValue(json, StartRequest.class);
    } catch (Exception e) {
      throw new IllegalStateException("CAPTURE_START_REQUEST_INVALID", e);
    }
  }

  CompleteRequest parseComplete(String json) {
    try {
      return OPERATION_MAPPER.readValue(json, CompleteRequest.class);
    } catch (Exception e) {
      throw new IllegalStateException("CAPTURE_COMPLETE_REQUEST_INVALID", e);
    }
  }

  record StartRequest(String task_key) {
    String taskKey() {
      return task_key;
    }
  }

  record CompleteRequest(String recording_id, String debug_session_id, int final_tick) {
    String recordingId() {
      return recording_id;
    }

    String debugSessionId() {
      return debug_session_id;
    }

    int finalTick() {
      return final_tick;
    }
  }

  public record Claim(
      Long id,
      String sessionId,
      String kind,
      String correlationId,
      String attemptId,
      String requestJson) {}
}
