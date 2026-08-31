package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.routine.service.CurrentSliceRunService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the short database transaction that atomically binds a run to its verified source.
 *
 * <p>The remote HTTP fetch and manifest verification happen in {@link SourceManifestFetchService}
 * with no transaction active. This service then commits every database write for the flow —
 * manifest reference, run, source binding and run-stage advance — in exactly one transaction, so
 * any failure rolls back all of them together. Persistence helpers join this transaction (they use
 * default {@code REQUIRED} propagation; no {@code REQUIRES_NEW}, no self-invoked transactions).
 */
@Service
public class SourceRunOrchestrationService {
  private final SourceManifestFetchService fetch;
  private final SourceManifestBindingService bindings;
  private final CurrentSliceRunService runs;
  private final TransactionTemplate transactions;

  public SourceRunOrchestrationService(
      SourceManifestFetchService fetch,
      SourceManifestBindingService bindings,
      CurrentSliceRunService runs,
      PlatformTransactionManager transactionManager) {
    this.fetch = fetch;
    this.bindings = bindings;
    this.runs = runs;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Maximum attempts for the whole short write transaction after an immutable-identity conflict. */
  private static final int MAX_CONFLICT_RETRIES = 3;

  /**
   * Stabilization-run flow: fetch and verify outside the transaction, then one transaction that
   * persists/reuses the manifest, creates the run, creates the source binding and advances the run
   * identity to {@code SOURCE_BOUND}. Any error rolls back all database writes.
   *
   * <p>A concurrent writer that wins the same immutable manifest identity causes the first
   * transaction to roll back; the whole short transaction is then retried (fresh transaction, no
   * rollback-only state) so the retry resolves the winner and validates exact bytes.
   */
  public Map<String, Object> createSourceBoundRun(String attemptId, CurrentSliceIdentity identity) {
    String runId = identity.run().runId();
    String taskKey = identity.task().taskKey();
    SourceManifestFetchService.FetchedSource fetched = fetch.fetchForRun(runId, taskKey, identity);
    SourceManifestException lastConflict = null;
    for (int attempt = 1; attempt <= MAX_CONFLICT_RETRIES; attempt++) {
      try {
        Map<String, Object> result = transactions.execute(status -> {
          bindings.verifyAndPersist(taskKey, fetched.manifestJson(), fetched.expected());
          runs.create(attemptId, identity);
          bindings.bindRun(runId, null, taskKey, fetched.verified());
          return runs.get(runId);
        });
        if (result == null) {
          throw new IllegalStateException("source-bound run orchestration produced no result");
        }
        return result;
      } catch (SourceManifestException exception) {
        if (!SourceManifestBindingService.isManifestConflict(exception)
            || attempt == MAX_CONFLICT_RETRIES) {
          throw exception;
        }
        lastConflict = exception;
      }
    }
    throw lastConflict == null ? new IllegalStateException("source-bound run orchestration failed")
        : lastConflict;
  }
}
