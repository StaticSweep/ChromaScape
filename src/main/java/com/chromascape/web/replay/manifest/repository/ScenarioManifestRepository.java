package com.chromascape.web.replay.manifest.repository;

import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ScenarioManifestRepository extends JpaRepository<ScenarioManifest, UUID> {

  Optional<ScenarioManifest> findByManifestChecksum(String checksum);

  Optional<ScenarioManifest> findByCurationIdAndPublishState(UUID curationId, String publishState);

  /** Authoritative database timestamp for manifest publication stamps. */
  @Query(value = "SELECT now() AT TIME ZONE 'UTC'", nativeQuery = true)
  LocalDateTime currentTimestamp();
}
