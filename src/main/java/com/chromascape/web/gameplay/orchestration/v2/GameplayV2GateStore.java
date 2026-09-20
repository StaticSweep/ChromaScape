package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Durable storage contract for the SC-1449 v2 live gate. Every in-flight reservation, authority
 * consumption, lease slot and receipt survives a ChromaScape restart through this boundary; the
 * gate service holds no authoritative in-memory state of its own. Storage methods never perform
 * network I/O, so no network call can run inside a persistence transaction.
 */
public interface GameplayV2GateStore {
  /** Loads the durable row by request id. */
  Optional<GateRow> findByRequestId(String requestId);

  /** Loads the durable row that owns an idempotency key. */
  Optional<GateRow> findByIdempotencyKey(String idempotencyKey);

  /** Loads the durable row that consumed an authority id. */
  Optional<GateRow> findByAuthorityId(String authorityId);

  /**
   * Persists a fresh {@code RESERVED} row. Any uniqueness conflict (request id, idempotency key,
   * authority id or account/client lease slot) raises {@link GateConflictException}; the caller
   * re-reads to disambiguate.
   */
  GateRow insertReserved(GateRow row) throws GateConflictException;

  /**
   * Atomically moves a row forward only while it sits in one of {@code expectedStates}, applying
   * the failure code, acknowledgement flag, receipt and appended evidence in the same durable
   * write. Returns {@code false} when the row was not in an expected state (lost race).
   */
  boolean compareAndSet(
      String requestId,
      Set<V2State> expectedStates,
      V2State next,
      String failureCode,
      boolean acknowledged,
      String receiptJson,
      List<String> appendEvidence);

  /** Loads every row that is neither terminal nor already {@code NEEDS_RECONCILIATION}. */
  List<GateRow> findRecoverable();

  /** One durable gate row. The bundle bytes are the exact validated canonical bytes. */
  record GateRow(
      String requestId,
      String idempotencyKey,
      String fingerprint,
      String envelopeDigest,
      String authorityId,
      String accountId,
      String clientId,
      long leaseGeneration,
      long fence,
      Instant envelopeDeadline,
      Instant authorityExpiresAt,
      V2State state,
      String failureCode,
      boolean acknowledged,
      byte[] bundleBytes,
      String receiptJson,
      List<String> evidence,
      Instant createdAt,
      Instant updatedAt) {
    /** Freezes the mutable arrays and lists at construction time. */
    public GateRow {
      bundleBytes = bundleBytes == null ? new byte[0] : bundleBytes.clone();
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** Defensive copy of the persisted canonical request bytes. */
    public byte[] bytes() {
      return bundleBytes.clone();
    }
  }

  /** Raised when an insert violates one of the durable uniqueness constraints. */
  final class GateConflictException extends Exception {
    /** The constraint family that conflicted, when the store can identify it. */
    public enum Kind {
      IDEMPOTENCY_KEY,
      REQUEST_ID,
      AUTHORITY_ID,
      SLOT,
      UNKNOWN
    }

    private final Kind kind;

    public GateConflictException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }
}
