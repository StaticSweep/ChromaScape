package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineCaptureRecordRepository extends JpaRepository<RoutineCaptureRecord, Long> {
  Optional<RoutineCaptureRecord> findBySessionId(String sessionId);
  Optional<RoutineCaptureRecord> findByRecordingId(String recordingId);
  Optional<RoutineCaptureRecord> findByStartIdempotencyKey(String idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RoutineCaptureRecord c where c.startIdempotencyKey = :idempotencyKey")
  Optional<RoutineCaptureRecord> findWithLockByStartIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  /** Pessimistic write lock so concurrent start/ingest/complete reconcile the same row. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from RoutineCaptureRecord c where c.sessionId = :sessionId")
  Optional<RoutineCaptureRecord> findWithLockBySessionId(@Param("sessionId") String sessionId);
}
