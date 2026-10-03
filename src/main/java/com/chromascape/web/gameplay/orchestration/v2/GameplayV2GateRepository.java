package com.chromascape.web.gameplay.orchestration.v2;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access for the durable SC-1449 v2 live-gate rows. */
public interface GameplayV2GateRepository extends JpaRepository<GameplayV2GateEntity, String> {
  Optional<GameplayV2GateEntity> findByIdempotencyKey(String idempotencyKey);

  Optional<GameplayV2GateEntity> findByAuthorityId(String authorityId);

  List<GameplayV2GateEntity> findByStateIn(List<String> states);

  /**
   * Conditional durable transition: the update applies only while the row still sits in one of the
   * expected states, so racing callers cannot both win the compare-and-set.
   */
  @Modifying
  @Query(
      "UPDATE GameplayV2GateEntity e SET e.state = :next, e.failureCode = :failureCode, "
          + "e.acknowledged = :acknowledged, e.receiptJson = COALESCE(:receiptJson, e.receiptJson), "
          + "e.evidenceRefs = CONCAT(e.evidenceRefs, :evidenceSuffix), "
          + "e.entityVersion = e.entityVersion + 1, e.updatedAt = :updatedAt "
          + "WHERE e.requestId = :requestId AND e.state IN :expectedStates")
  int compareAndSet(
      @Param("requestId") String requestId,
      @Param("expectedStates") List<String> expectedStates,
      @Param("next") String next,
      @Param("failureCode") String failureCode,
      @Param("acknowledged") boolean acknowledged,
      @Param("receiptJson") String receiptJson,
      @Param("evidenceSuffix") String evidenceSuffix,
      @Param("updatedAt") java.time.Instant updatedAt);
}
