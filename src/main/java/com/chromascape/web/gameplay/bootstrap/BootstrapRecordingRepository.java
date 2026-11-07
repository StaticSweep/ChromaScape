package com.chromascape.web.gameplay.bootstrap;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data access for durable SC-1498 recording projections. */
@Repository
public interface BootstrapRecordingRepository
    extends JpaRepository<BootstrapRecordingEntity, String> {
  Optional<BootstrapRecordingEntity> findByRecordingId(String recordingId);
}
