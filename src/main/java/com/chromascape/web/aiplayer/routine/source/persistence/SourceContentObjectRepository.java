package com.chromascape.web.aiplayer.routine.source.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SourceContentObjectRepository extends JpaRepository<SourceContentObject, UUID> {
  Optional<SourceContentObject> findByContentHash(String contentHash);
}
