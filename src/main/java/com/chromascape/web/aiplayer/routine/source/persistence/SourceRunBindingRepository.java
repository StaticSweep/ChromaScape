package com.chromascape.web.aiplayer.routine.source.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SourceRunBindingRepository extends JpaRepository<SourceRunBinding, UUID> {
  Optional<SourceRunBinding> findByRunId(String runId);
  Optional<SourceRunBinding> findByRecordingId(String recordingId);
}
