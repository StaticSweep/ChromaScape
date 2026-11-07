package com.chromascape.web.debug.tickdebugger.entity;

import com.chromascape.web.debug.tickdebugger.dto.SessionCountProjection;
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

  interface EvidenceMetadata {
    Long getTick();

    Integer getSchemaVersion();
  }

  /** Catalog continuity/schema checks do not need every historical world JSON payload. */
  @Query(
      "SELECT t.tick AS tick, t.schemaVersion AS schemaVersion FROM TickSnapshot t "
          + "WHERE t.sessionId = :sessionId ORDER BY t.tick ASC, t.id ASC")
  List<EvidenceMetadata> findEvidenceMetadataBySessionId(@Param("sessionId") String sessionId);

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

  /**
   * Count snapshots grouped by session ID for a list of sessions.
   *
   * @param sessionIds the sessions to count snapshots for
   * @return list of session ID/count projections
   */
  @Query(
      "SELECT t.sessionId AS sessionId, COUNT(t) AS count FROM TickSnapshot t "
          + "WHERE t.sessionId IN :sessionIds GROUP BY t.sessionId")
  List<SessionCountProjection> countBySessionIdIn(@Param("sessionIds") List<String> sessionIds);

  /** Paginated fetch of snapshots for a session from a given tick onward. */
  Page<TickSnapshot> findBySessionIdAndTickGreaterThanEqualOrderByTick(
      String sessionId, Long fromTick, Pageable pageable);

  /** Bounded replay page ordered deterministically when more than one row shares a tick. */
  Page<TickSnapshot> findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
      String sessionId, Long fromTick, Pageable pageable);

  /** Bounded replay page strictly after a compound {@code (tick,id)} cursor. */
  @Query(
      "SELECT t FROM TickSnapshot t "
          + "WHERE t.sessionId = :sessionId "
          + "AND (t.tick > :cursorTick "
          + "OR (t.tick = :cursorTick AND t.id > :cursorId)) "
          + "ORDER BY t.tick ASC, t.id ASC")
  Page<TickSnapshot> findBySessionIdAndAfterCursor(
      @Param("sessionId") String sessionId,
      @Param("cursorTick") Long cursorTick,
      @Param("cursorId") Long cursorId,
      Pageable pageable);

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
              (jsonb_exists(t.player_state, 'worldX') AND jsonb_exists(t.player_state, 'worldY'))
              OR (jsonb_exists(t.player_state, 'x') AND jsonb_exists(t.player_state, 'z'))
              OR (jsonb_exists(t.player_state, 'x') AND jsonb_exists(t.player_state, 'y'))
              OR (
                jsonb_typeof(t.player_state -> 'position') = 'object'
                AND jsonb_exists(t.player_state -> 'position', 'x')
                AND (
                  jsonb_exists(t.player_state -> 'position', 'y')
                  OR jsonb_exists(t.player_state -> 'position', 'z')
                )
              )
            )
          """,
      nativeQuery = true)
  long countWithPositionTelemetry(@Param("sessionId") String sessionId);
}
