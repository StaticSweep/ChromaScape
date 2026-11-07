package com.chromascape.web.gameplay.bootstrap;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data access for durable SC-1498 approvals. Row locking uses the store, not this type. */
@Repository
public interface BootstrapApprovalRepository
    extends JpaRepository<BootstrapApprovalEntity, String> {
  Optional<BootstrapApprovalEntity> findByScopeDigest(String scopeDigest);

  Optional<BootstrapApprovalEntity> findByOrchestrationId(String orchestrationId);

  Optional<BootstrapApprovalEntity> findByRequestId(String requestId);
}
