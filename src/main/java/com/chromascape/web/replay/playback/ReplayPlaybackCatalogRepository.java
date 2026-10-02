package com.chromascape.web.replay.playback;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Native bounded projection for the observed playback catalog. */
@Component
@Transactional(readOnly = true)
public class ReplayPlaybackCatalogRepository {

  private static final String FIND_CATALOG_ROWS_SQL =
      """
      WITH catalog_page AS (
        SELECT rs.id AS cursor_id,
               ds.recording_id AS recording_id,
               ds.session_id AS session_id,
               rs.account_name AS account_name,
               rs.script_name AS script_name,
               rs.start_time AS start_time,
               rs.end_time AS end_time,
               ds.start_tick AS start_tick,
               ds.end_tick AS end_tick,
               rs.task_key, rs.task_args, rs.settings_hash
          FROM recording_sessions rs
          JOIN LATERAL (
            SELECT linked.* FROM debug_sessions linked
            WHERE linked.recording_id = rs.recording_id
            ORDER BY linked.start_time DESC, linked.id DESC LIMIT 1
          ) ds ON TRUE
         WHERE ds.recording_id IS NOT NULL
           AND (CAST(:accountName AS VARCHAR) IS NULL
                OR rs.account_name = CAST(:accountName AS VARCHAR))
           AND (CAST(:taskKey AS VARCHAR) IS NULL OR rs.task_key = CAST(:taskKey AS VARCHAR))
           AND (
             CAST(:cursorStartTime AS TIMESTAMP) IS NULL
             OR rs.start_time < CAST(:cursorStartTime AS TIMESTAMP)
             OR (rs.start_time = CAST(:cursorStartTime AS TIMESTAMP)
                 AND rs.id < CAST(:cursorId AS BIGINT))
           )
         ORDER BY rs.start_time DESC, rs.id DESC
         LIMIT :pageSize OFFSET :rowOffset
      )
      SELECT cp.cursor_id AS cursor_id,
             cp.recording_id AS recording_id,
             cp.session_id AS session_id,
             cp.account_name AS account_name,
             cp.script_name AS script_name,
             cp.start_time AS start_time,
             cp.end_time AS end_time,
             cp.start_tick AS start_tick,
             cp.end_tick AS end_tick,
             snapshot_counts.tick_count AS tick_count,
             snapshot_counts.position_frame_count AS position_frame_count,
             log_counts.log_count AS log_count,
             cp.task_key, cp.task_args, cp.settings_hash
        FROM catalog_page cp
        LEFT JOIN LATERAL (
          SELECT COUNT(ts.id) AS tick_count,
                 COUNT(ts.id) FILTER (
                   WHERE jsonb_typeof(ts.player_state) = 'object'
                     AND (
                       (jsonb_exists(ts.player_state, 'worldX')
                        AND jsonb_exists(ts.player_state, 'worldY'))
                       OR (jsonb_exists(ts.player_state, 'x')
                           AND jsonb_exists(ts.player_state, 'z'))
                       OR (jsonb_exists(ts.player_state, 'x')
                           AND jsonb_exists(ts.player_state, 'y'))
                       OR (
                         jsonb_typeof(ts.player_state -> 'position') = 'object'
                         AND jsonb_exists(ts.player_state -> 'position', 'x')
                         AND (
                           jsonb_exists(ts.player_state -> 'position', 'y')
                           OR jsonb_exists(ts.player_state -> 'position', 'z')
                         )
                       )
                     )
                 ) AS position_frame_count
            FROM tick_snapshots ts
           WHERE ts.session_id = cp.session_id
        ) snapshot_counts ON TRUE
        LEFT JOIN LATERAL (
          SELECT COUNT(tl.id) AS log_count
            FROM tick_logs tl
           WHERE tl.session_id = cp.session_id
        ) log_counts ON TRUE
       ORDER BY cp.start_time DESC, cp.cursor_id DESC
      """;

  @PersistenceContext private EntityManager entityManager;

  /**
   * Reads the catalog ordering tuple and all page aggregates without one query per catalog row.
   * LATERAL aggregates use the session indexes and remain bounded by the keyset-selected page.
   */
  @SuppressWarnings("unchecked")
  public List<Object[]> findCatalogRows(
      String accountName,
      LocalDateTime cursorStartTime,
      Long cursorId,
      int pageSize,
      int rowOffset) {
    return findCatalogRows(accountName, null, cursorStartTime, cursorId, pageSize, rowOffset);
  }

  @SuppressWarnings("unchecked")
  public List<Object[]> findCatalogRows(
      String accountName,
      String taskKey,
      LocalDateTime cursorStartTime,
      Long cursorId,
      int pageSize,
      int rowOffset) {
    Query query = entityManager.createNativeQuery(FIND_CATALOG_ROWS_SQL);
    query.setParameter("taskKey", taskKey);
    query.setParameter("accountName", accountName);
    query.setParameter("cursorStartTime", cursorStartTime);
    query.setParameter("cursorId", cursorId);
    query.setParameter("pageSize", pageSize);
    query.setParameter("rowOffset", rowOffset);
    return query.getResultList();
  }
}
