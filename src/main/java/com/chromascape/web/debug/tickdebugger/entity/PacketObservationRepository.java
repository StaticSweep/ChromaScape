package com.chromascape.web.debug.tickdebugger.entity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for durable RSProx packet observations (SC-1618). */
@Repository
public interface PacketObservationRepository extends JpaRepository<PacketObservation, Long> {

  Optional<PacketObservation> findBySessionIdAndSourceSessionIdAndPacketSequence(
      String sessionId, String sourceSessionId, Long packetSequence);

  List<PacketObservation> findBySessionIdAndTickOrderByPacketSequenceAsc(
      String sessionId, Long tick);

  List<PacketObservation> findBySessionIdAndTickBetweenOrderByTickAscPacketSequenceAsc(
      String sessionId, Long startTick, Long endTick);

  @Query(
      "SELECT p FROM PacketObservation p WHERE p.sessionId = :sessionId"
          + " AND (:tick IS NULL OR p.tick = :tick)"
          + " AND (:startTick IS NULL OR p.tick >= :startTick)"
          + " AND (:endTick IS NULL OR p.tick <= :endTick)"
          + " AND (:direction IS NULL OR p.direction = :direction)"
          + " AND (:protocolName IS NULL OR LOWER(p.protocolName) LIKE LOWER(CONCAT('%', :protocolName, '%')))"
          + " AND (:opcode IS NULL OR p.opcode = :opcode)"
          + " AND (:decodeStatus IS NULL OR p.decodeStatus = :decodeStatus)"
          + " AND (:search IS NULL OR LOWER(p.protocolName) LIKE LOWER(CONCAT('%', :search, '%'))"
          + " OR (p.decodedType IS NOT NULL AND LOWER(p.decodedType) LIKE LOWER(CONCAT('%', :search, '%')))"
          + " OR (p.decodeError IS NOT NULL AND LOWER(p.decodeError) LIKE LOWER(CONCAT('%', :search, '%'))))"
          + " AND (:resumeTick IS NULL OR p.tick > :resumeTick"
          + " OR (p.tick = :resumeTick AND p.packetSequence > :resumeSequence)"
          + " OR (p.tick = :resumeTick AND p.packetSequence = :resumeSequence AND p.id > :resumeId))"
          + " ORDER BY p.tick ASC, p.packetSequence ASC, p.id ASC")
  List<PacketObservation> searchPacketsPage(
      @Param("sessionId") String sessionId,
      @Param("tick") Long tick,
      @Param("startTick") Long startTick,
      @Param("endTick") Long endTick,
      @Param("direction") String direction,
      @Param("protocolName") String protocolName,
      @Param("opcode") Integer opcode,
      @Param("decodeStatus") String decodeStatus,
      @Param("search") String search,
      @Param("resumeTick") Long resumeTick,
      @Param("resumeSequence") Long resumeSequence,
      @Param("resumeId") Long resumeId,
      org.springframework.data.domain.Pageable pageable);

  @Query(
      "SELECT p.tick AS tick, COUNT(p) AS packetCount FROM PacketObservation p"
          + " WHERE p.sessionId = :sessionId AND p.tick BETWEEN :startTick AND :endTick"
          + " GROUP BY p.tick")
  List<TickPacketCount> countBySessionIdAndTickBetween(
      @Param("sessionId") String sessionId,
      @Param("startTick") Long startTick,
      @Param("endTick") Long endTick);

  interface TickPacketCount {
    Long getTick();

    Long getPacketCount();
  }

  long countBySessionId(String sessionId);

  void deleteBySessionId(String sessionId);
}
