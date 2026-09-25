package com.chromascape.web.debug.tickdebugger.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for immutable typed recording actions. */
@Repository
public interface ReplayRecordingActionRepository
    extends JpaRepository<ReplayRecordingAction, UUID> {

  Optional<ReplayRecordingAction> findBySessionIdAndTickAndSequence(
      String sessionId, long tick, int sequence);

  /** Inserts an action without raising a PostgreSQL uniqueness exception. */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO replay_recording_actions
              (id, session_id, recording_id, tick, sequence, schema_name, action_type,
               target_kind, target_stable_id, target_json, arguments_json, source, provenance,
               confidence, captured_at, ingested_at, content_checksum)
          VALUES (gen_random_uuid(), :sessionId, :recordingId, :tick, :sequence, :schemaName,
                  :actionType, :targetKind, :targetStableId, CAST(:targetJson AS jsonb),
                  CAST(:argumentsJson AS jsonb), :source, :provenance, :confidence,
                  :capturedAt, :ingestedAt, :contentChecksum)
          ON CONFLICT (session_id, tick, sequence) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("sessionId") String sessionId,
      @Param("recordingId") String recordingId,
      @Param("tick") long tick,
      @Param("sequence") int sequence,
      @Param("schemaName") String schemaName,
      @Param("actionType") String actionType,
      @Param("targetKind") String targetKind,
      @Param("targetStableId") String targetStableId,
      @Param("targetJson") String targetJson,
      @Param("argumentsJson") String argumentsJson,
      @Param("source") String source,
      @Param("provenance") String provenance,
      @Param("confidence") java.math.BigDecimal confidence,
      @Param("capturedAt") LocalDateTime capturedAt,
      @Param("ingestedAt") LocalDateTime ingestedAt,
      @Param("contentChecksum") String contentChecksum);

  List<ReplayRecordingAction> findBySessionIdOrderByTickAscSequenceAsc(String sessionId);

  List<ReplayRecordingAction> findByRecordingIdOrderByTickAscSequenceAsc(String recordingId);

  List<ReplayRecordingAction> findBySessionIdAndTickBetweenOrderByTickAscSequenceAsc(
      String sessionId, long startTick, long endTick);

  long countBySessionId(String sessionId);
}
