package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateConflictException;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2GateStore.GateRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed durable store for SC-1449 v2 live-gate rows. Uniqueness of the request id, idempotency
 * key, authority id and account/client lease slot is enforced by the database; state transitions
 * are conditional single-statement compare-and-sets. No method performs network I/O.
 */
@Repository
public class JpaGameplayV2GateStore implements GameplayV2GateStore {
  private static final List<String> RECOVERABLE_STATES =
      List.of(
          V2State.RECEIVED.name(),
          V2State.VALIDATED.name(),
          V2State.AUTHORIZED.name(),
          V2State.RESERVED.name(),
          V2State.DELIVERY_PENDING.name(),
          V2State.STARTING.name(),
          V2State.STOPPING.name(),
          V2State.DELIVERED.name(),
          V2State.ACTIVE.name());

  private final GameplayV2GateRepository repository;
  private final EntityManager entityManager;
  private final Clock clock;

  public JpaGameplayV2GateStore(
      GameplayV2GateRepository repository, EntityManager entityManager, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional
  public Optional<GateRow> findByRequestId(String requestId) {
    return repository.findById(requestId).map(JpaGameplayV2GateStore::toRow);
  }

  @Override
  @Transactional
  public Optional<GateRow> findByIdempotencyKey(String idempotencyKey) {
    return repository.findByIdempotencyKey(idempotencyKey).map(JpaGameplayV2GateStore::toRow);
  }

  @Override
  @Transactional
  public Optional<GateRow> findByAuthorityId(String authorityId) {
    return repository.findByAuthorityId(authorityId).map(JpaGameplayV2GateStore::toRow);
  }

  /**
   * {@inheritDoc}
   *
   * <p>A uniqueness violation is detected by the database at flush, which poisons the persistence
   * context. {@code rollbackOn} is therefore explicit: without it the checked {@link
   * GateConflictException} would not roll the transaction back, and the caller would see an {@code
   * UnexpectedRollbackException} at commit instead of the conflict it can act on.
   */
  @Override
  @Transactional(rollbackOn = {GateConflictException.class, PersistenceException.class})
  public GateRow insertReserved(GateRow row) throws GateConflictException {
    GameplayV2GateEntity entity = new GameplayV2GateEntity();
    entity.requestId = row.requestId();
    entity.idempotencyKey = row.idempotencyKey();
    entity.fingerprint = row.fingerprint();
    entity.envelopeDigest = row.envelopeDigest();
    entity.authorityId = row.authorityId();
    entity.accountId = row.accountId();
    entity.clientId = row.clientId();
    entity.leaseGeneration = row.leaseGeneration();
    entity.fence = row.fence();
    entity.envelopeDeadline = row.envelopeDeadline();
    entity.authorityExpiresAt = row.authorityExpiresAt();
    entity.state = row.state().name();
    entity.failureCode = row.failureCode();
    entity.acknowledged = row.acknowledged();
    entity.requestBytes = row.bytes();
    entity.receiptJson = row.receiptJson();
    entity.evidenceRefs = String.join("\n", row.evidence());
    entity.createdAt = row.createdAt();
    entity.updatedAt = row.updatedAt();
    try {
      entityManager.persist(entity);
      entityManager.flush();
    } catch (PersistenceException exception) {
      throw new GateConflictException(
          conflictKind(exception),
          "gameplay v2 gate insert violated a uniqueness constraint: " + exception.getMessage());
    }
    return toRow(entity);
  }

  /**
   * Names the constraint family a failed insert hit, so the gate can answer with the right stable
   * failure code instead of a generic conflict. The constraint names come from V40/V41 and are the
   * only place the database tells us which invariant it defended.
   */
  private static GateConflictException.Kind conflictKind(PersistenceException exception) {
    String message = collectMessages(exception).toLowerCase(java.util.Locale.ROOT);
    if (message.contains("uq_gameplay_v2_gate_idempotency")) {
      return GateConflictException.Kind.IDEMPOTENCY_KEY;
    }
    if (message.contains("uq_gameplay_v2_gate_authority")) {
      return GateConflictException.Kind.AUTHORITY_ID;
    }
    if (message.contains("uq_gameplay_v2_gate_active_slot")
        || message.contains("uq_gameplay_v2_gate_slot")) {
      return GateConflictException.Kind.SLOT;
    }
    if (message.contains("gameplay_v2_gate_request_pkey")
        || exception instanceof jakarta.persistence.EntityExistsException) {
      return GateConflictException.Kind.REQUEST_ID;
    }
    return GateConflictException.Kind.UNKNOWN;
  }

  private static String collectMessages(Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        builder.append(current.getMessage()).append('\n');
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return builder.toString();
  }

  @Override
  @Transactional
  public boolean compareAndSet(
      String requestId,
      Set<V2State> expectedStates,
      V2State next,
      String failureCode,
      boolean acknowledged,
      String receiptJson,
      List<String> appendEvidence) {
    List<String> expected = expectedStates.stream().map(Enum::name).collect(Collectors.toList());
    String suffix = evidenceSuffix(appendEvidence);
    int updated =
        repository.compareAndSet(
            requestId,
            expected,
            next.name(),
            failureCode,
            acknowledged,
            receiptJson,
            suffix,
            clock.instant());
    return updated == 1;
  }

  @Override
  @Transactional
  public List<GateRow> findRecoverable() {
    return repository.findByStateIn(RECOVERABLE_STATES).stream()
        .map(JpaGameplayV2GateStore::toRow)
        .collect(Collectors.toList());
  }

  private static String evidenceSuffix(List<String> appendEvidence) {
    if (appendEvidence == null || appendEvidence.isEmpty()) {
      return "";
    }
    return "\n" + String.join("\n", appendEvidence);
  }

  private static GateRow toRow(GameplayV2GateEntity entity) {
    return new GateRow(
        entity.requestId,
        entity.idempotencyKey,
        entity.fingerprint,
        entity.envelopeDigest,
        entity.authorityId,
        entity.accountId,
        entity.clientId,
        entity.leaseGeneration,
        entity.fence,
        entity.envelopeDeadline,
        entity.authorityExpiresAt,
        V2State.valueOf(entity.state),
        entity.failureCode,
        entity.acknowledged,
        entity.requestBytes == null ? new byte[0] : entity.requestBytes,
        entity.receiptJson,
        splitEvidence(entity.evidenceRefs),
        entity.createdAt,
        entity.updatedAt);
  }

  private static List<String> splitEvidence(String evidenceRefs) {
    if (evidenceRefs == null || evidenceRefs.isBlank()) {
      return List.of();
    }
    return Arrays.stream(evidenceRefs.split("\n")).filter(s -> !s.isBlank()).toList();
  }
}
