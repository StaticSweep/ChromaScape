package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Transactionally locked owner budget accounting. */
@Repository
public interface MouseVideoOwnerBudgetRepository
    extends JpaRepository<MouseVideoOwnerBudget, String> {

  @org.springframework.data.jpa.repository.Modifying
  @Query(
      value =
          "insert into mouse_video_owner_budgets(owner) values (:owner) on conflict (owner) do nothing",
      nativeQuery = true)
  void ensureOwner(@Param("owner") String owner);

  @Query(value = "select now()", nativeQuery = true)
  java.time.Instant databaseNow();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from MouseVideoOwnerBudget b where b.owner = :owner")
  Optional<MouseVideoOwnerBudget> findForUpdate(@Param("owner") String owner);
}
