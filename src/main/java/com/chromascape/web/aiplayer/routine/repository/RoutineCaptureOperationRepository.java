package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureOperation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface RoutineCaptureOperationRepository extends JpaRepository<RoutineCaptureOperation, Long> {
  Optional<RoutineCaptureOperation> findBySessionIdAndOperation(String sessionId, String operation);
  Optional<RoutineCaptureOperation> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from RoutineCaptureOperation o where o.id = :id")
  Optional<RoutineCaptureOperation> findWithLockById(@Param("id") Long id);

  @Query("select o from RoutineCaptureOperation o where o.sessionId = :sessionId order by o.createdAt desc")
  List<RoutineCaptureOperation> findBySessionId(@Param("sessionId") String sessionId, Pageable pageable);

  @Query("select o from RoutineCaptureOperation o where "
      + "(o.state = 'PENDING' and o.nextAttemptAt <= :now) "
      + "or (o.state = 'LEASED' and o.leaseExpiresAt < :now) "
      + "or (o.state = 'UNCERTAIN' and o.nextAttemptAt <= :now) "
      + "order by o.createdAt asc")
  List<RoutineCaptureOperation> findClaimable(@Param("now") Instant now, Pageable pageable);
}
