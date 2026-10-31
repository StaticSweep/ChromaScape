package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Immutable analyses and their idempotent dedup identity. */
@Repository
public interface MouseVideoAnalysisRepository extends JpaRepository<MouseVideoAnalysis, UUID> {
  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('mouse-video-publication', 0))) locked",
      nativeQuery = true)
  @org.springframework.data.jpa.repository.QueryHints(
      @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "10000"))
  Boolean lockPublication();

  Optional<MouseVideoAnalysis> findByIdAndOwner(UUID id, String owner);

  Optional<MouseVideoAnalysis> findBySourceIdAndOriginalDigestAndConfigDigestAndToolchainDigest(
      UUID sourceId, String originalDigest, String configDigest, String toolchainDigest);

  List<MouseVideoAnalysis> findBySourceIdAndOwnerOrderByCreatedAtDesc(UUID sourceId, String owner);

  /**
   * The newest completed analysis for a source, ordered by completion time, creation time and a
   * stable identifier tie-break. Only a COMPLETE analysis may own derivative artifacts.
   */
  Optional<MouseVideoAnalysis>
      findFirstBySourceIdAndOwnerAndStatusOrderByCompletedAtDescCreatedAtDescIdDesc(
          UUID sourceId, String owner, String status);
}
