package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.recording.service.RecordingIdPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Transactional durability for SC-1498 human approvals.
 *
 * <p>Approvals are created {@code APPROVED}, bound exactly once to {@code BOUND}, or terminally
 * {@code CANCELLED}/{@code EXPIRED}. Binding is row-locked so two identical submissions observe the
 * same durable owner while conflicting reuse receives a stable conflict. Only digests are
 * persisted; HMAC secrets and raw signatures never reach this store.
 */
@Repository
public class BootstrapApprovalStore {
  private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

  private final EntityManager entityManager;
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;

  @Autowired
  public BootstrapApprovalStore(EntityManager entityManager, CanonicalGameplayJson canonicalJson) {
    this(entityManager, canonicalJson, Clock.systemUTC());
  }

  BootstrapApprovalStore(
      EntityManager entityManager, CanonicalGameplayJson canonicalJson, Clock clock) {
    this.entityManager = entityManager;
    this.canonicalJson = canonicalJson;
    this.clock = clock;
  }

  /** Creates a durable approval or returns the identical durable owner on idempotent replay. */
  @Transactional
  public Claim create(
      String providerSessionId,
      String providerTurnId,
      String orchestrationId,
      String requestId,
      String recordingId,
      String requestFingerprint,
      String scopeDigest,
      String approvedBy,
      Instant expiresAt) {
    requireId(providerSessionId, "Provider session id is required");
    requireId(providerTurnId, "Provider turn id is required");
    requireId(orchestrationId, "Orchestration id is required");
    requireId(requestId, "Request id is required");
    requireId(recordingId, "Recording id is required");
    if (!RecordingIdPolicy.isPlatformSafe(recordingId)) {
      throw new BootstrapApprovalException(
          "RECORDING_ID_UNSAFE", "The recording id cannot be used as an output filename");
    }
    requireDigest(requestFingerprint, "Request fingerprint is required");
    requireDigest(scopeDigest, "Scope digest is required");
    if (approvedBy == null || !approvedBy.matches(ID_PATTERN)) {
      throw new BootstrapApprovalException(
          "APPROVER_INVALID", "A stable human operator id is required");
    }
    // One clock read, normalized to the precision PostgreSQL actually stores, so the validation the
    // caller sees is the comparison the database enforces: an expiry that would be stored at or
    // before approved_at is refused with a stable code instead of a constraint error.
    Instant now = truncate(clock.instant());
    Instant normalizedExpiry = expiresAt == null ? null : truncate(expiresAt);
    if (normalizedExpiry == null
        || !normalizedExpiry.isAfter(now)
        || normalizedExpiry.isAfter(now.plusSeconds(900))) {
      throw new BootstrapApprovalException(
          "AUTHORITY_EXPIRY_INVALID", "Authority must expire within 15 minutes");
    }
    String approvalId = "approval_" + UUID.randomUUID().toString().replace("-", "");
    Instant truncatedExpiry = normalizedExpiry;
    int inserted =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO gameplay_bootstrap_approval (
                    approval_id, scope_digest, provider_session_id, provider_turn_id,
                    orchestration_id, request_id, recording_id, request_fingerprint,
                    approved_by, approved_at, expires_at, state,
                    bound_authority_id, signature_digest,
                    entity_version, created_at, updated_at
                ) VALUES (
                    :approvalId, :scopeDigest, :sessionId, :turnId,
                    :orchestrationId, :requestId, :recordingId, :fingerprint,
                    :approvedBy, :approvedAt, :expiresAt, 'APPROVED',
                    NULL, NULL,
                    0, :createdAt, :updatedAt
                ) ON CONFLICT DO NOTHING
                """)
            .setParameter("approvalId", approvalId)
            .setParameter("scopeDigest", scopeDigest)
            .setParameter("sessionId", providerSessionId)
            .setParameter("turnId", providerTurnId)
            .setParameter("orchestrationId", orchestrationId)
            .setParameter("requestId", requestId)
            .setParameter("recordingId", recordingId)
            .setParameter("fingerprint", requestFingerprint)
            .setParameter("approvedBy", approvedBy)
            .setParameter("approvedAt", now)
            .setParameter("expiresAt", truncatedExpiry)
            .setParameter("createdAt", now)
            .setParameter("updatedAt", now)
            .executeUpdate();
    if (inserted == 0) {
      BootstrapApprovalEntity owner =
          findOwnerByAnyIdentity(orchestrationId, requestId, recordingId, scopeDigest);
      if (owner == null) {
        throw new BootstrapApprovalException(
            "APPROVAL_CONFLICT", "An approval identity conflict did not expose its owner");
      }
      if (!Objects.equals(owner.providerSessionId, providerSessionId)
          || !Objects.equals(owner.providerTurnId, providerTurnId)
          || !Objects.equals(owner.recordingId, recordingId)
          || !Objects.equals(owner.requestFingerprint, requestFingerprint)
          || !Objects.equals(owner.scopeDigest, scopeDigest)
          || !Objects.equals(owner.approvedBy, approvedBy)) {
        throw new BootstrapApprovalException(
            "APPROVAL_CONFLICT", "Approval identity was reused with different scope");
      }
      return new Claim(toView(refreshExpiry(owner)), false);
    }
    BootstrapApprovalEntity entity =
        entityManager.find(
            BootstrapApprovalEntity.class, approvalId, LockModeType.PESSIMISTIC_WRITE);
    if (entity == null) {
      throw new BootstrapApprovalException(
          "APPROVAL_UNAVAILABLE", "The created approval owner could not be loaded");
    }
    return new Claim(toView(entity), true);
  }

  /** Atomically binds an APPROVED approval once. Identical replay returns the same owner. */
  @Transactional
  public ApprovalView bind(String approvalId, String authorityId, String signatureDigest) {
    requireId(approvalId, "Approval id is required");
    requireId(authorityId, "Authority id is required");
    requireDigest(signatureDigest, "Signature digest is required");
    BootstrapApprovalEntity entity =
        entityManager.find(
            BootstrapApprovalEntity.class, approvalId, LockModeType.PESSIMISTIC_WRITE);
    if (entity == null) {
      throw new BootstrapApprovalException(
          "APPROVAL_MISSING_OR_USED", "Pending approval is absent, expired, or already used");
    }
    if ("BOUND".equals(entity.state)) {
      if (Objects.equals(entity.boundAuthorityId, authorityId)
          && Objects.equals(entity.signatureDigest, signatureDigest)) {
        return toView(entity);
      }
      throw new BootstrapApprovalException(
          "APPROVAL_CONFLICT", "Approval is already bound to a different authority");
    }
    if (!"APPROVED".equals(entity.state)) {
      throw new BootstrapApprovalException(
          "APPROVAL_MISSING_OR_USED", "Pending approval is absent, expired, or already used");
    }
    if (!entity.expiresAt.isAfter(clock.instant())) {
      entity.state = "EXPIRED";
      entity.updatedAt = clock.instant();
      entityManager.flush();
      throw new BootstrapApprovalException(
          "AUTHORITY_EXPIRED", "Pending approval expired before binding");
    }
    entity.state = "BOUND";
    entity.boundAuthorityId = authorityId;
    entity.signatureDigest = signatureDigest;
    entity.updatedAt = clock.instant();
    entityManager.flush();
    return toView(entity);
  }

  @Transactional
  public ApprovalView find(String approvalId) {
    BootstrapApprovalEntity entity = entityManager.find(BootstrapApprovalEntity.class, approvalId);
    if (entity == null) {
      throw new BootstrapApprovalException(
          "APPROVAL_MISSING_OR_USED", "Pending approval is absent, expired, or already used");
    }
    return toView(refreshExpiry(entity));
  }

  @Transactional
  public Optional<ApprovalView> findByOrchestration(String orchestrationId) {
    List<BootstrapApprovalEntity> values =
        entityManager
            .createQuery(
                "select a from BootstrapApprovalEntity a where a.orchestrationId=:id",
                BootstrapApprovalEntity.class)
            .setParameter("id", orchestrationId)
            .setMaxResults(1)
            .getResultList();
    if (values.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(toView(refreshExpiry(values.get(0))));
  }

  @Transactional
  public void cancel(String approvalId) {
    BootstrapApprovalEntity entity =
        entityManager.find(
            BootstrapApprovalEntity.class, approvalId, LockModeType.PESSIMISTIC_WRITE);
    if (entity == null || "BOUND".equals(entity.state)) {
      return;
    }
    if (!"APPROVED".equals(entity.state)) {
      return;
    }
    entity.state = "CANCELLED";
    entity.updatedAt = clock.instant();
    entityManager.flush();
  }

  @Transactional
  public int expireBefore(Instant now) {
    return entityManager
        .createQuery(
            "update BootstrapApprovalEntity a set a.state='EXPIRED', a.updatedAt=:now"
                + " where a.state='APPROVED' and a.expiresAt <= :now")
        .setParameter("now", now)
        .executeUpdate();
  }

  public String digestSignature(String signature) {
    return canonicalJson.digest(signature == null ? "" : signature);
  }

  private BootstrapApprovalEntity refreshExpiry(BootstrapApprovalEntity entity) {
    if ("APPROVED".equals(entity.state) && !entity.expiresAt.isAfter(clock.instant())) {
      entity.state = "EXPIRED";
      entity.updatedAt = clock.instant();
      entityManager.flush();
    }
    return entity;
  }

  private BootstrapApprovalEntity findOwnerByAnyIdentity(
      String orchestrationId, String requestId, String recordingId, String scopeDigest) {
    List<BootstrapApprovalEntity> values =
        entityManager
            .createQuery(
                "select a from BootstrapApprovalEntity a where a.orchestrationId=:orchestrationId"
                    + " or a.requestId=:requestId or a.recordingId=:recordingId"
                    + " or a.scopeDigest=:scopeDigest",
                BootstrapApprovalEntity.class)
            .setParameter("orchestrationId", orchestrationId)
            .setParameter("requestId", requestId)
            .setParameter("recordingId", recordingId)
            .setParameter("scopeDigest", scopeDigest)
            .getResultList();
    if (values.isEmpty()) {
      return null;
    }
    return values.get(0);
  }

  /** Counts durable approvals still awaiting a one-use bind, for operator availability. */
  @Transactional
  public long countPending() {
    return entityManager
        .createQuery(
            "select count(a) from BootstrapApprovalEntity a where a.state='APPROVED'", Long.class)
        .getSingleResult();
  }

  private ApprovalView toView(BootstrapApprovalEntity entity) {
    return new ApprovalView(
        entity.approvalId,
        entity.scopeDigest,
        entity.providerSessionId,
        entity.providerTurnId,
        entity.orchestrationId,
        entity.requestId,
        entity.recordingId,
        entity.requestFingerprint,
        entity.approvedBy,
        entity.approvedAt,
        entity.expiresAt,
        entity.state,
        entity.boundAuthorityId,
        entity.signatureDigest,
        entity.createdAt,
        entity.updatedAt);
  }

  private void requireId(String value, String message) {
    if (value == null || !value.matches(ID_PATTERN)) {
      throw new BootstrapApprovalException("INVALID_REQUEST", message);
    }
  }

  private void requireDigest(String value, String message) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new BootstrapApprovalException("INVALID_REQUEST", message);
    }
  }

  /** PostgreSQL {@code TIMESTAMPTZ} stores microseconds; validate and persist at that precision. */
  private Instant truncate(Instant value) {
    return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public record Claim(ApprovalView view, boolean created) {}

  public record ApprovalView(
      String approvalId,
      String scopeDigest,
      String providerSessionId,
      String providerTurnId,
      String orchestrationId,
      String requestId,
      String recordingId,
      String requestFingerprint,
      String approvedBy,
      Instant approvedAt,
      Instant expiresAt,
      String state,
      String boundAuthorityId,
      String signatureDigest,
      Instant createdAt,
      Instant updatedAt) {}

  public static final class BootstrapApprovalException extends RuntimeException {
    private final String failureCode;

    public BootstrapApprovalException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
