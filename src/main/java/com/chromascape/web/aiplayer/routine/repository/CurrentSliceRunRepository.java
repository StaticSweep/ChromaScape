package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CurrentSliceRunRepository extends JpaRepository<CurrentSliceRun, Long> {
  Optional<CurrentSliceRun> findByRunId(String runId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<CurrentSliceRun> findWithLockByRunId(String runId);
}
