package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureExternalBinding;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineCaptureExternalBindingRepository
    extends JpaRepository<RoutineCaptureExternalBinding, Long> {
  Optional<RoutineCaptureExternalBinding> findByCorrelationId(String correlationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from RoutineCaptureExternalBinding b where b.correlationId = :correlationId")
  Optional<RoutineCaptureExternalBinding> findWithLockByCorrelationId(
      @Param("correlationId") String correlationId);
}
