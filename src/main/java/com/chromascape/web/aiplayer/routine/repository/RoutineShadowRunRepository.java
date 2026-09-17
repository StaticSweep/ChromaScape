package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineShadowRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RoutineShadowRunRepository extends JpaRepository<RoutineShadowRun, Long> {
  Optional<RoutineShadowRun> findByShadowRunId(String shadowRunId);

  Optional<RoutineShadowRun> findByModelIdAndSessionId(String modelId, String sessionId);

  List<RoutineShadowRun> findBySessionIdAndState(String sessionId, String state);

  List<RoutineShadowRun> findByState(String state);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RoutineShadowRun> findWithLockByShadowRunId(String shadowRunId);
}
