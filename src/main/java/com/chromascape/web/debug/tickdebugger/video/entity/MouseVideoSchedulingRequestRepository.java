package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Durable scheduling request identities, including coalesced aliases. */
@Repository
public interface MouseVideoSchedulingRequestRepository
    extends JpaRepository<MouseVideoSchedulingRequest, UUID> {

  Optional<MouseVideoSchedulingRequest> findByOwnerAndRequestId(String owner, String requestId);
}
