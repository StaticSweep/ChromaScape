package com.chromascape.web.debug.tickdebugger.video.entity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Exact-frame preview metadata keyed by its coalescing identity and owner/request pair. */
@Repository
public interface MouseVideoFramePreviewRepository
    extends JpaRepository<MouseVideoFramePreview, UUID> {

  Optional<MouseVideoFramePreview> findByIdAndOwner(UUID id, String owner);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from MouseVideoFramePreview p where p.id = :id")
  Optional<MouseVideoFramePreview> findForUpdate(@Param("id") UUID id);

  Optional<MouseVideoFramePreview> findByOwnerAndRequestId(String owner, String requestId);

  Optional<MouseVideoFramePreview>
      findByAnalysisIdAndIndexChecksumAndFrameOrdinalAndPreviewMethodAndMaxWidthAndMaxHeight(
          UUID analysisId,
          String indexChecksum,
          long frameOrdinal,
          String previewMethod,
          int maxWidth,
          int maxHeight);

  List<MouseVideoFramePreview> findByAnalysisIdOrderByFrameOrdinalAsc(UUID analysisId);

  Optional<MouseVideoFramePreview> findFirstByAnalysisIdAndFrameOrdinalAndStateOrderByCreatedAtDesc(
      UUID analysisId, long frameOrdinal, String state);

  @Query("select distinct p.checksum from MouseVideoFramePreview p where p.checksum is not null")
  List<String> findDistinctChecksums();

  @Query(
      value =
          "SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('mouse-video-publication', 0))) locked",
      nativeQuery = true)
  @org.springframework.data.jpa.repository.QueryHints(
      @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "10000"))
  Boolean lockPublication();

  @Query(
      "select distinct p.checksum from MouseVideoFramePreview p"
          + " where p.analysisId = :analysisId and p.checksum is not null")
  List<String> findDistinctChecksumsByAnalysis(@Param("analysisId") UUID analysisId);
}
