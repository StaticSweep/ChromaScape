package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Intake reservation lookups and expiry sweeps. */
@Repository
public interface MouseVideoIntakeReservationRepository
    extends JpaRepository<MouseVideoIntakeReservation, UUID> {

  Optional<MouseVideoIntakeReservation> findByOwnerAndRequestId(String owner, String requestId);

  @Query("select r.owner from MouseVideoIntakeReservation r where r.id = :id")
  Optional<String> findOwner(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from MouseVideoIntakeReservation r where r.id = :id")
  Optional<MouseVideoIntakeReservation> findForUpdate(@Param("id") UUID id);

  List<MouseVideoIntakeReservation> findByStateAndExpiresAtBefore(String state, Instant cutoff);
}
