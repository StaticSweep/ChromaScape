package com.chromascape.web.debug.tickdebugger.entity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for durable tick-batch delivery receipts. */
@Repository
public interface ReplayTickBatchReceiptRepository
    extends JpaRepository<ReplayTickBatchReceipt, UUID> {

  Optional<ReplayTickBatchReceipt> findBySessionIdAndBatchId(String sessionId, UUID batchId);

  Optional<ReplayTickBatchReceipt> findBySessionIdAndTick(String sessionId, long tick);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO replay_tick_batch_receipts
              (id, session_id, batch_id, tick, payload_checksum, schema_name, persisted_at)
          VALUES (gen_random_uuid(), :sessionId, :batchId, :tick, :payloadChecksum, :schemaName,
              :persistedAt)
          ON CONFLICT DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("sessionId") String sessionId,
      @Param("batchId") UUID batchId,
      @Param("tick") long tick,
      @Param("payloadChecksum") String payloadChecksum,
      @Param("schemaName") String schemaName,
      @Param("persistedAt") java.time.LocalDateTime persistedAt);
}
