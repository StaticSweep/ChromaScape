package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Immutable export snapshots, unique by (owner, request) and by (source, request digest). */
@Repository
public interface MouseVideoExportRepository extends JpaRepository<MouseVideoExport, UUID> {

  Optional<MouseVideoExport> findByIdAndOwner(UUID id, String owner);

  Optional<MouseVideoExport> findByOwnerAndRequestId(String owner, String requestId);

  Optional<MouseVideoExport> findBySourceIdAndRequestDigest(UUID sourceId, String requestDigest);

  List<MouseVideoExport> findBySourceIdAndOwnerOrderByCreatedAtAsc(UUID sourceId, String owner);

  List<MouseVideoExport> findAllByOrderByCreatedAtAsc();
}
