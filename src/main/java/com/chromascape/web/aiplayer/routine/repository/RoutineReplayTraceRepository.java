package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineReplayTrace;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineReplayTraceRepository extends JpaRepository<RoutineReplayTrace, Long> {
  Optional<RoutineReplayTrace> findByTraceId(String traceId);

  Optional<RoutineReplayTrace> findFirstByModelIdAndTraceModeOrderByCreatedAtDesc(
      String modelId, String traceMode);
}
