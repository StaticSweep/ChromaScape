package com.chromascape.web.debug.tickdebugger.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for accessing {@link TickOverlay} entities. */
@Repository
public interface TickOverlayRepository extends JpaRepository<TickOverlay, Long> {

  /** Find all overlays for a session, ordered by tick. */
  List<TickOverlay> findBySessionIdOrderByTickAsc(String sessionId);

  /** Find overlays for a specific tick. */
  List<TickOverlay> findBySessionIdAndTick(String sessionId, Long tick);

  /** Find overlays for a session within a tick range. */
  List<TickOverlay> findBySessionIdAndTickBetweenOrderByTickAsc(
      String sessionId, Long startTick, Long endTick);

  /** Find overlays by type for a session. */
  List<TickOverlay> findBySessionIdAndOverlayTypeOrderByTickAsc(
      String sessionId, String overlayType);

  /** Find overlays by type for a specific tick. */
  List<TickOverlay> findBySessionIdAndTickAndOverlayType(
      String sessionId, Long tick, String overlayType);

  /** Delete all overlays for a specific session. */
  void deleteBySessionId(String sessionId);

  /** Delete overlays for a specific tick. */
  void deleteBySessionIdAndTick(String sessionId, Long tick);

  /** Count overlays for a session. */
  long countBySessionId(String sessionId);

  /** Count overlays by type for a session. */
  long countBySessionIdAndOverlayType(String sessionId, String overlayType);
}
