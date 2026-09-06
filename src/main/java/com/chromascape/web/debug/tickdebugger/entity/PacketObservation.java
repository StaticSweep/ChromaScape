package com.chromascape.web.debug.tickdebugger.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable RSProx packet observation (SC-1618).
 *
 * <p>One row per {@code rsprox-packet/v1} envelope, persisted in the same transaction as the
 * enclosing tick batch before the durable ACK. Unique {@code (session_id, source_session_id,
 * packet_sequence)} gives retry idempotency; a reused sequence with different content fails with
 * {@code PACKET_IDENTITY_CONFLICT}.
 */
@Entity
@Table(
    name = "packet_observations",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_packet_observations_session_source_sequence",
          columnNames = {"session_id", "source_session_id", "packet_sequence"})
    },
    indexes = {
      @Index(
          name = "idx_packet_observations_session_tick_seq",
          columnList = "session_id, tick, packet_sequence"),
      @Index(name = "idx_packet_observations_direction", columnList = "session_id, direction"),
      @Index(name = "idx_packet_observations_protocol", columnList = "session_id, protocol_name"),
      @Index(name = "idx_packet_observations_status", columnList = "session_id, decode_status")
    })
public class PacketObservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  @Column(nullable = false)
  private Long tick;

  @Column(name = "source_session_id", nullable = false, length = 100)
  private String sourceSessionId;

  @Column(name = "packet_sequence", nullable = false)
  private Long packetSequence;

  @Column(name = "captured_at")
  private LocalDateTime capturedAt;

  @Column(name = "capture_delta_millis")
  private Long captureDeltaMillis;

  @Column(nullable = false, length = 20)
  private String direction;

  @Column(nullable = false)
  private Integer opcode;

  @Column(name = "protocol_name", nullable = false, length = 128)
  private String protocolName;

  @Column(name = "encoded_size", nullable = false)
  private Integer encodedSize;

  @Column(name = "decoded_type", length = 128)
  private String decodedType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "decoded_payload")
  private JsonNode decodedPayload;

  @Column(name = "sanitized_payload")
  private byte[] sanitizedPayload;

  @Column(name = "decode_status", nullable = false, length = 20)
  private String decodeStatus;

  @Column(name = "decode_error", length = 512)
  private String decodeError;

  @Column(nullable = false)
  private boolean synthetic;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected PacketObservation() {}

  public PacketObservation(
      String sessionId, long tick, String sourceSessionId, long packetSequence) {
    this.sessionId = sessionId;
    this.tick = tick;
    this.sourceSessionId = sourceSessionId;
    this.packetSequence = packetSequence;
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getTick() {
    return tick;
  }

  public void setTick(Long tick) {
    this.tick = tick;
  }

  public String getSourceSessionId() {
    return sourceSessionId;
  }

  public void setSourceSessionId(String sourceSessionId) {
    this.sourceSessionId = sourceSessionId;
  }

  public Long getPacketSequence() {
    return packetSequence;
  }

  public void setPacketSequence(Long packetSequence) {
    this.packetSequence = packetSequence;
  }

  public LocalDateTime getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(LocalDateTime capturedAt) {
    this.capturedAt = capturedAt;
  }

  public Long getCaptureDeltaMillis() {
    return captureDeltaMillis;
  }

  public void setCaptureDeltaMillis(Long captureDeltaMillis) {
    this.captureDeltaMillis = captureDeltaMillis;
  }

  public String getDirection() {
    return direction;
  }

  public void setDirection(String direction) {
    this.direction = direction;
  }

  public Integer getOpcode() {
    return opcode;
  }

  public void setOpcode(Integer opcode) {
    this.opcode = opcode;
  }

  public String getProtocolName() {
    return protocolName;
  }

  public void setProtocolName(String protocolName) {
    this.protocolName = protocolName;
  }

  public Integer getEncodedSize() {
    return encodedSize;
  }

  public void setEncodedSize(Integer encodedSize) {
    this.encodedSize = encodedSize;
  }

  public String getDecodedType() {
    return decodedType;
  }

  public void setDecodedType(String decodedType) {
    this.decodedType = decodedType;
  }

  public JsonNode getDecodedPayload() {
    return decodedPayload;
  }

  public void setDecodedPayload(JsonNode decodedPayload) {
    this.decodedPayload = decodedPayload;
  }

  public byte[] getSanitizedPayload() {
    return sanitizedPayload;
  }

  public void setSanitizedPayload(byte[] sanitizedPayload) {
    this.sanitizedPayload = sanitizedPayload;
  }

  public String getDecodeStatus() {
    return decodeStatus;
  }

  public void setDecodeStatus(String decodeStatus) {
    this.decodeStatus = decodeStatus;
  }

  public String getDecodeError() {
    return decodeError;
  }

  public void setDecodeError(String decodeError) {
    this.decodeError = decodeError;
  }

  public boolean isSynthetic() {
    return synthetic;
  }

  public void setSynthetic(boolean synthetic) {
    this.synthetic = synthetic;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
