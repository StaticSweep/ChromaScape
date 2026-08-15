package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.StabilizationRunAggregate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Aggregate run rows keyed by {@code run_id}; one row per verified report bundle. */
public interface StabilizationRunAggregateRepository
    extends JpaRepository<StabilizationRunAggregate, Long> {

  Optional<StabilizationRunAggregate> findByRunId(String runId);

  boolean existsByRunId(String runId);
}
