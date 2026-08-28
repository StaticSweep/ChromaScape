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

  /**
   * Newest definitions in one state, for callers that must match on identity content.
   *
   * <p>There is deliberately no {@code identityJson like ...} query here, in any dialect. The
   * attribute is mapped {@code @Lob}, so PostgreSQL stores the JSON in {@code pg_largeobject} and
   * keeps only the object id in the column: a row whose identity is a 4 KB document literally holds
   * the text {@code "95342"}. Every {@code like} against it therefore matches nothing, whether
   * written in JPQL or native SQL — and the JPQL form fails outright off a transaction, because
   * binding its parameter creates a large object. Read the rows and let Hibernate resolve each
   * {@code identityJson} through the same large-object API that wrote it.
   */
  List<BehaviorGraphDefinition> findByStateOrderByCreatedAtDesc(String state);
}
