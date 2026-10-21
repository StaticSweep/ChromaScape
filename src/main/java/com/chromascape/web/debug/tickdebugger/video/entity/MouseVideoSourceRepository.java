package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Owner-scoped access to external video sources. */
@Repository
public interface MouseVideoSourceRepository extends JpaRepository<MouseVideoSource, UUID> {

  Optional<MouseVideoSource> findByIdAndOwner(UUID id, String owner);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from MouseVideoSource s where s.id = :id and s.owner = :owner")
  Optional<MouseVideoSource> findForUpdateByIdAndOwner(
      @Param("id") UUID id, @Param("owner") String owner);

  Page<MouseVideoSource> findByOwnerOrderByCreatedAtDesc(String owner, Pageable pageable);

  Page<MouseVideoSource> findByOwnerAndSourceKindOrderByCreatedAtDesc(
      String owner, String sourceKind, Pageable pageable);
}
