package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCuration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineCurationRepository extends JpaRepository<RoutineCuration, Long> {
  Optional<RoutineCuration> findByRecordingId(String recordingId);
}
