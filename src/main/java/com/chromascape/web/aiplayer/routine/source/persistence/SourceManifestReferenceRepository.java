package com.chromascape.web.aiplayer.routine.source.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SourceManifestReferenceRepository extends JpaRepository<SourceManifestReference, UUID> {
  Optional<SourceManifestReference> findByManifestHash(String manifestHash);
  Optional<SourceManifestReference> findBySnapshotId(String snapshotId);
}
