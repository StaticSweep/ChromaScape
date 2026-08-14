package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.CurrentSliceRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrentSliceRunRepository extends JpaRepository<CurrentSliceRun, Long> { Optional<CurrentSliceRun> findByRunId(String runId); }
