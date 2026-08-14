package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineModel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineModelRepository extends JpaRepository<RoutineModel, Long> {
  Optional<RoutineModel> findByModelId(String modelId);
}
