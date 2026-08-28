package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BehaviorGraphDefinitionRepository extends JpaRepository<BehaviorGraphDefinition, Long> {
  Optional<BehaviorGraphDefinition> findByGraphId(String graphId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select g from BehaviorGraphDefinition g where g.graphId = :graphId")
  Optional<BehaviorGraphDefinition> findWithLockByGraphId(@Param("graphId") String graphId);

  Optional<BehaviorGraphDefinition> findFirstByIdentityJsonContainingOrderByCreatedAtDesc(String token);
  Optional<BehaviorGraphDefinition> findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc(String state, String token);

  /**
   * Reads only the graph reference of the newest matching definition. The entity carries three
   * {@code @Lob} columns, so callers running outside a transaction — the asynchronous Assistant
   * proposal generator — must not load it whole.
   */
  @Query("select g.graphId, g.graphHash from BehaviorGraphDefinition g"
      + " where g.state = :state and g.identityJson like %:token%"
      + " order by g.createdAt desc")
  List<Object[]> findGraphReferences(@Param("state") String state, @Param("token") String token);
}
