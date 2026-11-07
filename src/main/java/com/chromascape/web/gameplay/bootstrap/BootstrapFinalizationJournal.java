package com.chromascape.web.gameplay.bootstrap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable finalization intent for one SC-1498 bootstrap recording.
 *
 * <p>Stopping the external recorder is irreversible, so the intent must be committed before that
 * call. Each method opens its own transaction: the intent survives a crash between the stop and the
 * projection update, and recovery can distinguish a run that asked to stop from one that crashed
 * mid-recording.
 */
@Repository
public class BootstrapFinalizationJournal {
  private final EntityManager entityManager;
  private final Clock clock;

  @Autowired
  public BootstrapFinalizationJournal(EntityManager entityManager) {
    this(entityManager, Clock.systemUTC());
  }

  BootstrapFinalizationJournal(EntityManager entityManager, Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  /** Records the intent to stop once. Replaying the same intent never rewrites the first instant. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requestFinalization(String recordingId) {
    BootstrapRecordingEntity entity = lockByRecordingId(recordingId);
    if (entity == null || entity.finalizationRequestedAt != null) {
      return;
    }
    entity.finalizationRequestedAt = clock.instant();
    entityManager.flush();
  }

  /** True when the run already asked to stop this recording, so its artifact may be recovered. */
  @Transactional(readOnly = true)
  public boolean finalizationRequested(String recordingId) {
    BootstrapRecordingEntity entity = lockByRecordingId(recordingId);
    return entity != null && entity.finalizationRequestedAt != null;
  }

  private BootstrapRecordingEntity lockByRecordingId(String recordingId) {
    var values =
        entityManager
            .createQuery(
                "select r from BootstrapRecordingEntity r where r.recordingId=:recordingId",
                BootstrapRecordingEntity.class)
            .setParameter("recordingId", recordingId)
            .setMaxResults(1)
            .getResultList();
    if (values.isEmpty()) {
      return null;
    }
    return entityManager.find(
        BootstrapRecordingEntity.class, values.get(0).orchestrationId, LockModeType.PESSIMISTIC_WRITE);
  }
}
