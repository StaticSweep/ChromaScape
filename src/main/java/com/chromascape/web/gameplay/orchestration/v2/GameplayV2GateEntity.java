package com.chromascape.web.gameplay.orchestration.v2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Durable SC-1449 v2 live-gate row: identity, authority, slot, state and validated bytes. */
@Entity(name = "GameplayV2GateEntity")
@Table(name = "gameplay_v2_gate_request")
class GameplayV2GateEntity {
  @Id
  @Column(name = "request_id", nullable = false, length = 128)
  String requestId;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
  String idempotencyKey;

  @Column(name = "fingerprint", nullable = false, length = 128)
  String fingerprint;

  @Column(name = "envelope_digest", nullable = false, length = 128)
  String envelopeDigest;

  @Column(name = "authority_id", nullable = false, unique = true, length = 128)
  String authorityId;

  @Column(name = "account_id", nullable = false, length = 128)
  String accountId;

  @Column(name = "client_id", nullable = false, length = 128)
  String clientId;

  @Column(name = "lease_generation", nullable = false)
  long leaseGeneration;

  @Column(name = "fence", nullable = false)
  long fence;

  @Column(name = "envelope_deadline", nullable = false)
  Instant envelopeDeadline;

  @Column(name = "authority_expires_at", nullable = false)
  Instant authorityExpiresAt;

  @Column(name = "state", nullable = false, length = 32)
  String state;

  @Column(name = "failure_code", length = 64)
  String failureCode;

  @Column(name = "acknowledged", nullable = false)
  boolean acknowledged;

  @Column(name = "request_bytes", nullable = false, columnDefinition = "bytea")
  byte[] requestBytes;

  @Column(name = "receipt_json", columnDefinition = "text")
  String receiptJson;

  @Column(name = "evidence_refs", nullable = false, columnDefinition = "text")
  String evidenceRefs;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  long entityVersion;
}
