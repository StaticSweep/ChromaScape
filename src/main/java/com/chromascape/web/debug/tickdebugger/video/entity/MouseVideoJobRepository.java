package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.LockModeType;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Durable external-video job store keyed by the (owner, request) idempotency pair. */
@Repository
public interface MouseVideoJobRepository extends JpaRepository<MouseVideoJob, UUID> {

  Optional<MouseVideoJob> findByIdAndOwner(UUID id, String owner);

  Optional<MouseVideoJob> findByOwnerAndRequestId(String owner, String requestId);

  List<MouseVideoJob> findBySourceIdAndOwnerOrderByCreatedAtAsc(UUID sourceId, String owner);

  List<MouseVideoJob> findByStatusIn(List<String> statuses);

  List<MouseVideoJob> findByStatusOrderByCreatedAtAsc(String status);

  List<MouseVideoJob> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

  List<MouseVideoJob> findByKindAndStatusOrderByCreatedAtAsc(String kind, String status);

  Optional<MouseVideoJob> findFirstByPreviewIdAndStatusIn(UUID previewId, List<String> statuses);

  Optional<MouseVideoJob> findFirstByAnalysisIdAndKindAndStatusIn(
      UUID analysisId, String kind, List<String> statuses);

  long countByOwnerAndStatusIn(String owner, List<String> statuses);

  /**
   * Transaction-scoped PostgreSQL advisory lock serializing admission for one owner. Callers must
   * acquire it first inside their transaction, before counting or inserting jobs, so the
   * count-then-insert sequence is atomic across instances. It is released at commit/rollback.
   */
  @Query(
      value =
          "SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) locked",
      nativeQuery = true)
  Boolean lockAdmission(@Param("lockKey") String lockKey);

  /** Select IDs without caching stale job entities before their row locks are acquired. */
  @Query("select j.id from MouseVideoJob j where j.status in :statuses order by j.id")
  List<UUID> findIdsByStatusIn(@Param("statuses") List<String> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from MouseVideoJob j where j.id = :id")
  Optional<MouseVideoJob> findForUpdate(@Param("id") UUID id);

  /**
   * Database-clock lease validity. Application wall time is never authoritative across processes,
   * so lease comparisons are delegated to PostgreSQL.
   */
  @Query(
      value =
          "SELECT COALESCE((SELECT lease_expires_at > now() FROM mouse_video_jobs WHERE id = :id),"
              + " false)",
      nativeQuery = true)
  boolean leaseValid(@Param("id") UUID id);

  /** Authoritative database clock used for lease expiry so application wall time never leaks in. */
  @Query(value = "SELECT now()", nativeQuery = true)
  Timestamp databaseNow();

  long countByStatus(String status);
}
