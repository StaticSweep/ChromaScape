package com.chromascape.web.replay.report.repository;

import com.chromascape.web.replay.report.entity.ReplayReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ReplayReportRepository extends JpaRepository<ReplayReport, UUID> {

    Optional<ReplayReport> findByRunId(UUID runId);
}