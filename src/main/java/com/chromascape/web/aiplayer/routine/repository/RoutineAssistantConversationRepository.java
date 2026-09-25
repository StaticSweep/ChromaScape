package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineAssistantConversationRepository
    extends JpaRepository<RoutineAssistantConversation, Long> {
  Optional<RoutineAssistantConversation> findByConversationId(String conversationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RoutineAssistantConversation> findWithLockByConversationId(String conversationId);

  @Query(
      "select c from RoutineAssistantConversation c where c.state = 'OPEN' and c.generationState = 'GENERATING' and c.generationLeaseExpiresAt is not null and c.generationLeaseExpiresAt < :cutoff")
  List<RoutineAssistantConversation> findExpiredGenerations(@Param("cutoff") Instant cutoff);

  /**
   * Reads only the run identifier, without materializing the entity — all the generator needs, and
   * cheap regardless of how large {@code generationMessage} grows.
   */
  @Query(
      "select c.runId from RoutineAssistantConversation c where c.conversationId = :conversationId")
  Optional<String> findRunIdByConversationId(@Param("conversationId") String conversationId);
}
