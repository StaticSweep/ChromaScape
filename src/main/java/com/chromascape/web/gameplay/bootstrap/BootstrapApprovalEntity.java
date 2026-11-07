package com.chromascape.web.gameplay.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Durable pending human approval for one SC-1498 bootstrap run.
 *
 * <p>Only safe digests are persisted. HMAC secrets, raw signatures and fence tokens stay
 * memory-only in {@code HumanGameplayAuthorityService}.
 */
@Entity(name = "BootstrapApprovalEntity")
@Table(name = "gameplay_bootstrap_approval")
public class BootstrapApprovalEntity {
  @Id
  @Column(name = "approval_id", nullable = false, length = 128)
  public String approvalId;

  @Column(name = "scope_digest", nullable = false, unique = true, length = 80)
  public String scopeDigest;

  @Column(name = "provider_session_id", nullable = false, length = 160)
  public String providerSessionId;

  @Column(name = "provider_turn_id", nullable = false, length = 160)
  public String providerTurnId;

  @Column(name = "orchestration_id", nullable = false, unique = true, length = 128)
  public String orchestrationId;

  @Column(name = "request_id", nullable = false, unique = true, length = 128)
  public String requestId;

  @Column(name = "recording_id", nullable = false, unique = true, length = 160)
  public String recordingId;

  @Column(name = "request_fingerprint", nullable = false, length = 80)
  public String requestFingerprint;

  @Column(name = "approved_by", nullable = false, length = 128)
  public String approvedBy;

  @Column(name = "approved_at", nullable = false)
  public Instant approvedAt;

  @Column(name = "expires_at", nullable = false)
  public Instant expiresAt;

  @Column(name = "state", nullable = false, length = 16)
  public String state;

  @Column(name = "bound_authority_id", length = 128)
  public String boundAuthorityId;

  @Column(name = "signature_digest", length = 80)
  public String signatureDigest;

  @Version
  @Column(name = "entity_version", nullable = false)
  public long entityVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
