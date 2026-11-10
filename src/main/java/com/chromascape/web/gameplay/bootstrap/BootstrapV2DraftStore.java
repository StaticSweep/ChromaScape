package com.chromascape.web.gameplay.bootstrap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable store for frozen v2 bootstrap drafts.
 *
 * <p>A draft is written once. Re-persisting the same draft returns the stored row; persisting a
 * different draft under the same request, idempotency key, scope digest or bundle digest is a
 * conflict, because an approval must never be able to point at different bytes.
 */
@Service
public class BootstrapV2DraftStore {
  @PersistenceContext private EntityManager entityManager;
  private final Clock clock;

  @Autowired
  public BootstrapV2DraftStore() {
    this(Clock.systemUTC());
  }

  BootstrapV2DraftStore(Clock clock) {
    this.clock = clock;
  }

  /** Persists one frozen draft and returns the durable view. */
  @Transactional
  public DraftView save(BootstrapV2DraftService.Preview preview) {
    var frozen = preview.frozen();
    BootstrapV2DraftEntity existing = entityManager.find(BootstrapV2DraftEntity.class, draftId(preview));
    if (existing != null) {
      if (!existing.bundleDigest.equals(frozen.bundleDigest())
          || !existing.scopeDigest.equals(frozen.scopeDigest())) {
        throw new BootstrapV2DraftException(
            "DRAFT_CONFLICT", "A different draft is already persisted for this request");
      }
      return view(existing);
    }
    BootstrapV2DraftEntity entity = new BootstrapV2DraftEntity();
    entity.draftId = draftId(preview);
    entity.providerSessionId = preview.providerSessionId();
    entity.providerTurnId = preview.providerTurnId();
    entity.requestId = preview.requestId();
    entity.idempotencyKey = preview.idempotencyKey();
    entity.recordingId = preview.recordingId();
    entity.authorityId = frozen.authorityId();
    entity.bundleBytes = new String(frozen.bytes(), StandardCharsets.UTF_8);
    entity.bundleDigest = frozen.bundleDigest();
    entity.envelopeDigest = frozen.envelopeDigest();
    entity.scopeDigest = frozen.scopeDigest();
    entity.catalogDigest = frozen.catalogDigest();
    entity.observationDigest = frozen.observationDigest();
    entity.planningInputDigest = frozen.planningInputDigest();
    entity.eligibilityDigest = frozen.eligibilityResultDigest();
    entity.goalPlanDigest = frozen.goalPlanDigest();
    entity.semanticActionDigest = frozen.semanticActionDigest();
    entity.deadline = frozen.envelopeDeadline();
    entity.createdAt = clock.instant();
    entityManager.persist(entity);
    entityManager.flush();
    return view(entity);
  }

  /** Looks a draft up by its approved scope digest. */
  @Transactional(readOnly = true)
  public Optional<DraftView> findByScopeDigest(String scopeDigest) {
    return entityManager
        .createQuery(
            "select d from BootstrapV2DraftEntity d where d.scopeDigest=:scopeDigest",
            BootstrapV2DraftEntity.class)
        .setParameter("scopeDigest", scopeDigest)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst()
        .map(BootstrapV2DraftStore::view);
  }

  private static String draftId(BootstrapV2DraftService.Preview preview) {
    return "draft-" + preview.requestId();
  }

  private static DraftView view(BootstrapV2DraftEntity entity) {
    return new DraftView(
        entity.draftId,
        entity.providerSessionId,
        entity.providerTurnId,
        entity.requestId,
        entity.idempotencyKey,
        entity.recordingId,
        entity.authorityId,
        entity.bundleBytes.getBytes(StandardCharsets.UTF_8),
        entity.bundleDigest,
        entity.envelopeDigest,
        entity.scopeDigest,
        entity.catalogDigest,
        entity.observationDigest,
        entity.planningInputDigest,
        entity.eligibilityDigest,
        entity.goalPlanDigest,
        entity.semanticActionDigest,
        entity.deadline,
        entity.createdAt);
  }

  /** Durable draft view; the bytes are the exact frozen bundle. */
  public record DraftView(
      String draftId,
      String providerSessionId,
      String providerTurnId,
      String requestId,
      String idempotencyKey,
      String recordingId,
      String authorityId,
      byte[] bundleBytes,
      String bundleDigest,
      String envelopeDigest,
      String scopeDigest,
      String catalogDigest,
      String observationDigest,
      String planningInputDigest,
      String eligibilityDigest,
      String goalPlanDigest,
      String semanticActionDigest,
      Instant deadline,
      Instant createdAt) {
    public DraftView {
      bundleBytes = bundleBytes == null ? new byte[0] : bundleBytes.clone();
    }

    public byte[] bytes() {
      return bundleBytes.clone();
    }
  }

  /** Stable draft-store failure. */
  public static final class BootstrapV2DraftException extends IllegalArgumentException {
    private final String failureCode;

    public BootstrapV2DraftException(String failureCode, String message) {
      super(message == null ? failureCode : message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
