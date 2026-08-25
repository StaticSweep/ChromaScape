package com.chromascape.web.replay.manifest.repository;

import com.chromascape.web.replay.manifest.entity.ScenarioManifest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioManifestRepository extends JpaRepository<ScenarioManifest, UUID> {

    Optional<ScenarioManifest> findByManifestChecksum(String checksum);

    Optional<ScenarioManifest> findByCurationIdAndPublishState(UUID curationId, String publishState);
}