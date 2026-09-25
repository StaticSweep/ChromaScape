package com.chromascape.web.replay.curation.repository;

import com.chromascape.web.replay.curation.entity.RecordingCuration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecordingCurationRepository extends JpaRepository<RecordingCuration, UUID> {

  List<RecordingCuration> findByRecordingIdOrderByVersionDesc(String recordingId);

  long countByRecordingId(String recordingId);

  List<RecordingCuration> findByRecordingIdAndCreatedByOrderByVersionDesc(
      String recordingId, String createdBy);

  Optional<RecordingCuration> findByRecordingIdAndVersion(String recordingId, int version);

  Optional<RecordingCuration> findByContentChecksum(String checksum);

  /**
   * ONE authoritative database timestamp for durable curation stamps. Curation creation/publication
   * never reads the JVM clock.
   */
  @Query(value = "SELECT now() AT TIME ZONE 'UTC'", nativeQuery = true)
  java.time.LocalDateTime currentTimestamp();
}
