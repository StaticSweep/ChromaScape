package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateConflictException;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-process {@link GameplayV2GateStore} for unit tests and local tooling. It enforces the same
 * uniqueness families as the JPA store (request id, idempotency key, authority id, lease slot) but
 * is not restart-safe; production wiring always binds {@link JpaGameplayV2GateStore}.
 */
public final class InMemoryGameplayV2GateStore implements GameplayV2GateStore {
  private final Map<String, GateRow> byRequestId = new HashMap<>();
  private final Map<String, String> requestIdByKey = new HashMap<>();
  private final Map<String, String> requestIdByAuthority = new HashMap<>();
  private final Map<String, String> requestIdBySlot = new HashMap<>();
  private final Map<String, String> requestIdByActiveSlot = new HashMap<>();

  @Override
  public synchronized Optional<GateRow> findByRequestId(String requestId) {
    return Optional.ofNullable(byRequestId.get(requestId));
  }

  @Override
  public synchronized Optional<GateRow> findByIdempotencyKey(String idempotencyKey) {
    String requestId = requestIdByKey.get(idempotencyKey);
    return Optional.ofNullable(requestId == null ? null : byRequestId.get(requestId));
  }

  @Override
  public synchronized Optional<GateRow> findByAuthorityId(String authorityId) {
    String requestId = requestIdByAuthority.get(authorityId);
    return Optional.ofNullable(requestId == null ? null : byRequestId.get(requestId));
  }

  @Override
  public synchronized GateRow insertReserved(GateRow row) throws GateConflictException {
    if (byRequestId.containsKey(row.requestId())) {
      throw new GateConflictException(
          GateConflictException.Kind.REQUEST_ID, "request id already persisted");
    }
    if (requestIdByKey.containsKey(row.idempotencyKey())) {
      throw new GateConflictException(
          GateConflictException.Kind.IDEMPOTENCY_KEY, "idempotency key already owned");
    }
    if (requestIdByAuthority.containsKey(row.authorityId())) {
      throw new GateConflictException(
          GateConflictException.Kind.AUTHORITY_ID, "authority already consumed");
    }
    String slot =
        row.accountId() + "|" + row.clientId() + "|" + row.leaseGeneration() + "|" + row.fence();
    if (requestIdBySlot.containsKey(slot)) {
      throw new GateConflictException(GateConflictException.Kind.SLOT, "lease slot already owned");
    }
    String activeSlot = row.accountId() + "|" + row.clientId();
    if (requestIdByActiveSlot.containsKey(activeSlot)) {
      throw new GateConflictException(
          GateConflictException.Kind.SLOT, "account/client slot already occupied");
    }
    byRequestId.put(row.requestId(), row);
    requestIdByKey.put(row.idempotencyKey(), row.requestId());
    requestIdByAuthority.put(row.authorityId(), row.requestId());
    requestIdBySlot.put(slot, row.requestId());
    requestIdByActiveSlot.put(activeSlot, row.requestId());
    return row;
  }

  @Override
  public synchronized boolean compareAndSet(
      String requestId,
      Set<V2State> expectedStates,
      V2State next,
      String failureCode,
      boolean acknowledged,
      String receiptJson,
      List<String> appendEvidence) {
    GateRow current = byRequestId.get(requestId);
    if (current == null || !expectedStates.contains(current.state())) {
      return false;
    }
    List<String> evidence = new ArrayList<>(current.evidence());
    if (appendEvidence != null) {
      evidence.addAll(appendEvidence);
    }
    GateRow updated =
        new GateRow(
            current.requestId(),
            current.idempotencyKey(),
            current.fingerprint(),
            current.envelopeDigest(),
            current.authorityId(),
            current.accountId(),
            current.clientId(),
            current.leaseGeneration(),
            current.fence(),
            current.envelopeDeadline(),
            current.authorityExpiresAt(),
            next,
            failureCode,
            acknowledged || current.acknowledged(),
            current.bytes(),
            receiptJson != null ? receiptJson : current.receiptJson(),
            evidence,
            current.createdAt(),
            current.updatedAt());
    byRequestId.put(requestId, updated);
    if (next.provenTerminal()) {
      requestIdByActiveSlot.remove(current.accountId() + "|" + current.clientId(), requestId);
    }
    return true;
  }

  @Override
  public synchronized List<GateRow> findRecoverable() {
    return byRequestId.values().stream()
        .filter(row -> !row.state().provenTerminal() && row.state() != V2State.NEEDS_RECONCILIATION)
        .toList();
  }
}
