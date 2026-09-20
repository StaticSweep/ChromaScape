package com.chromascape.web.gameplay.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Durable SC-1498 bootstrap recording projection.
 *
 * <p>References (never copies) SC-1431 leases/receipts/terminal rows by digest. The immutable
 * manifest artifact is published only after durable terminal reconciliation and recording
 * finalization. Safety flags are constrained false in PostgreSQL.
 */
@Entity(name = "BootstrapRecordingEntity")
@Table(name = "gameplay_bootstrap_recording")
public class BootstrapRecordingEntity {
  @Id
  @Column(name = "orchestration_id", nullable = false, length = 128)
  public String orchestrationId;

  @Column(name = "recording_id", nullable = false, unique = true, length = 160)
  public String recordingId;

  @Column(name = "provider_session_id", nullable = false, length = 160)
  public String providerSessionId;

  @Column(name = "provider_turn_id", nullable = false, length = 160)
  public String providerTurnId;

  @Column(name = "decision_digest", nullable = false, length = 80)
  public String decisionDigest;

  @Column(name = "envelope_digest", nullable = false, length = 80)
  public String envelopeDigest;

  @Column(name = "provider", nullable = false, length = 16)
  public String provider;

  @Column(name = "model_version", nullable = false, length = 160)
  public String modelVersion;

  @Column(name = "manifest_state", nullable = false, length = 16)
  public String manifestState;

  @Column(name = "terminal_outcome", length = 32)
  public String terminalOutcome;

  @Column(name = "stable_code", length = 64)
  public String stableCode;

  @Column(name = "manifest_digest", length = 80)
  public String manifestDigest;

  @Column(name = "artifact_ref", length = 500)
  public String artifactRef;

  @Column(name = "artifact_digest", length = 80)
  public String artifactDigest;

  @Column(name = "learned_model_activated", nullable = false)
  public boolean learnedModelActivated;

  @Column(name = "continuing_authority", nullable = false)
  public boolean continuingAuthority;

  @Column(name = "simulator_trace", nullable = false)
  public boolean simulatorTrace;

  @Version
  @Column(name = "entity_version", nullable = false)
  public long entityVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "finalized_at")
  public Instant finalizedAt;
}
