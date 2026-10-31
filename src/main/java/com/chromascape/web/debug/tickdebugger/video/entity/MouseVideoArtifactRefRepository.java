package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Verified artifact registration for external video sources and analyses. */
@Repository
public interface MouseVideoArtifactRefRepository
    extends JpaRepository<MouseVideoArtifactRef, UUID> {

  List<MouseVideoArtifactRef> findBySourceIdAndAnalysisIdIsNull(UUID sourceId);

  List<MouseVideoArtifactRef> findByAnalysisId(UUID analysisId);

  List<MouseVideoArtifactRef> findByExportId(UUID exportId);

  Optional<MouseVideoArtifactRef> findBySourceIdAndRoleAndAnalysisIdIsNull(
      UUID sourceId, String role);

  Optional<MouseVideoArtifactRef> findBySourceIdAndRoleAndAnalysisIdIsNullAndState(
      UUID sourceId, String role, String state);

  Optional<MouseVideoArtifactRef> findByAnalysisIdAndRole(UUID analysisId, String role);

  Optional<MouseVideoArtifactRef> findByAnalysisIdAndRoleAndState(
      UUID analysisId, String role, String state);

  Optional<MouseVideoArtifactRef> findFirstBySourceIdAndRoleAndStateOrderByCreatedAtDesc(
      UUID sourceId, String role, String state);

  @Query("select distinct a.checksum from MouseVideoArtifactRef a")
  List<String> findDistinctChecksums();

  /** Fence reference commits against collection, including commits surviving their JVM. */
  @Query(
      value =
          "SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('mouse-video-publication', 0))) locked",
      nativeQuery = true)
  @org.springframework.data.jpa.repository.QueryHints(
      @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "10000"))
  Boolean lockPublication();

  /** Frozen templates are live analysis inputs even before observation artifacts exist. */
  @Query(
      value =
          "SELECT DISTINCT template_checksum FROM mouse_video_analyses WHERE template_checksum IS NOT NULL",
      nativeQuery = true)
  List<String> findDistinctTemplateChecksums();
}
