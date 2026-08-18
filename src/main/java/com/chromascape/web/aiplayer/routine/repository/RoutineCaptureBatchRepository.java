package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureBatch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineCaptureBatchRepository extends JpaRepository<RoutineCaptureBatch, Long> {
  Optional<RoutineCaptureBatch> findBySessionIdAndKindAndSequence(String sessionId, String kind, int sequence);
  List<RoutineCaptureBatch> findBySessionIdOrderByAcceptedAtAscIdAsc(String sessionId);
}
