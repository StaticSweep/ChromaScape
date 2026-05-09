package com.chromascape.web.debug.tickdebugger.entity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for accessing {@link TickSnapshot} entities. */
@Repository
public interface TickSnapshotRepository extends JpaRepository<TickSnapshot, Long> {

  /** Find all snapshots for a session, ordered by tick. */
  List<TickSnapshot> findBySessionIdOrderByTick(String sessionId);

  /** Find a specific snapshot by session and tick. */
  Optional<TickSnapshot> findBySessionIdAndTick(String sessionId, Long tick);

  /** Find snapshots for a session within a tick range. */
  List<TickSnapshot> findBySessionIdAndTickBetweenOrderByTick(
      String sessionId, Long startTick, Long endTick);

  /** Find the latest snapshot for a session. */
  Optional<TickSnapshot> findFirstBySessionIdOrderByTickDesc(String sessionId);

  /** Find the earliest snapshot for a session. */
  Optional<TickSnapshot> findFirstBySessionIdOrderByTickAsc(String sessionId);

  /** Delete all snapshots for a specific session. */
  void deleteBySessionId(String sessionId);

  /** Count snapshots for a session. */
  long countBySessionId(String sessionId);

  /** Paginated fetch of snapshots for a session from a given tick onward. */
  Page<TickSnapshot> findBySessionIdAndTickGreaterThanEqualOrderByTick(
      String sessionId, Long fromTick, Pageable pageable);

  @Query("SELECT MAX(t.tick) FROM TickSnapshot t WHERE t.sessionId = :sessionId")
  Optional<Long> findMaxTickBySessionId(@Param("sessionId") String sessionId);

  @Query("SELECT MIN(t.tick) FROM TickSnapshot t WHERE t.sessionId = :sessionId")
  Optional<Long> findMinTickBySessionId(@Param("sessionId") String sessionId);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tick_snapshots t
          WHERE t.session_id = :sessionId
            AND (
              (t.player_state ? 'worldX' AND t.player_state ? 'worldY')
              OR (t.player_state ? 'x' AND t.player_state ? 'z')
            )
          """,
      nativeQuery = true)
  long countWithPositionTelemetry(@Param("sessionId") String sessionId);
}
