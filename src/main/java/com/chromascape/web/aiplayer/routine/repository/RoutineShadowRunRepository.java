package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineShadowRun;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface RoutineShadowRunRepository extends JpaRepository<RoutineShadowRun, Long> {
  Optional<RoutineShadowRun> findByShadowRunId(String shadowRunId);
  Optional<RoutineShadowRun> findByModelIdAndSessionId(String modelId, String sessionId);
  List<RoutineShadowRun> findBySessionIdAndState(String sessionId, String state);
  List<RoutineShadowRun> findByState(String state);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RoutineShadowRun> findWithLockByShadowRunId(String shadowRunId);
}
