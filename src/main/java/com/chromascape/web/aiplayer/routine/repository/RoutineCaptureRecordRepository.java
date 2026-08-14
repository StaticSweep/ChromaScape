package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineCaptureRecordRepository extends JpaRepository<RoutineCaptureRecord, Long> {
  Optional<RoutineCaptureRecord> findBySessionId(String sessionId);
  Optional<RoutineCaptureRecord> findByRecordingId(String recordingId);
  Optional<RoutineCaptureRecord> findByStartIdempotencyKey(String idempotencyKey);
}
