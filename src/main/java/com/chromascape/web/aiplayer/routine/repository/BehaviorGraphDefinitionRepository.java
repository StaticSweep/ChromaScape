package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphDefinition;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BehaviorGraphDefinitionRepository
    extends JpaRepository<BehaviorGraphDefinition, Long> {
  Optional<BehaviorGraphDefinition> findByGraphId(String graphId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select g from BehaviorGraphDefinition g where g.graphId = :graphId")
  Optional<BehaviorGraphDefinition> findWithLockByGraphId(@Param("graphId") String graphId);

  /** Newest definitions in one state. */
  List<BehaviorGraphDefinition> findByStateOrderByCreatedAtDesc(String state);

  /**
   * Newest definition in one state whose identity contains the given fragment.
   *
   * <p>This query was impossible while {@code identityJson} was mapped {@code @Lob}: PostgreSQL
   * kept the JSON in {@code pg_largeobject} and only the object id in the column, so every {@code
   * like} matched nothing and binding one off a transaction created a large object instead. The
   * attribute now maps to {@code text}, as its Flyway declaration always said, so identity content
   * is ordinary SQL again.
   */
  Optional<BehaviorGraphDefinition> findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc(
      String state, String identityFragment);
}
