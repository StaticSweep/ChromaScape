package com.chromascape.web.replay.curation.repository;

import com.chromascape.web.replay.curation.entity.RecordingCuration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingCurationRepository extends JpaRepository<RecordingCuration, UUID> {

    List<RecordingCuration> findByRecordingIdOrderByVersionDesc(String recordingId);

    Optional<RecordingCuration> findByRecordingIdAndVersion(String recordingId, int version);

    Optional<RecordingCuration> findByContentChecksum(String checksum);

    long countByRecordingId(String recordingId);
}