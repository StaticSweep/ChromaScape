package com.chromascape.web.debug.tickdebugger.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for immutable typed recording actions. */
@Repository
public interface ReplayRecordingActionRepository
    extends JpaRepository<ReplayRecordingAction, UUID> {

  Optional<ReplayRecordingAction> findBySessionIdAndTickAndSequence(
      String sessionId, long tick, int sequence);

  List<ReplayRecordingAction> findBySessionIdOrderByTickAscSequenceAsc(String sessionId);

  List<ReplayRecordingAction> findByRecordingIdOrderByTickAscSequenceAsc(String recordingId);

  List<ReplayRecordingAction> findBySessionIdAndTickBetweenOrderByTickAscSequenceAsc(
      String sessionId, long startTick, long endTick);

  long countBySessionId(String sessionId);
}
