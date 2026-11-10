package com.chromascape.web.gameplay.bootstrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Durable SC-1429/v2 bootstrap draft.
 *
 * <p>Written once and never updated: the frozen bundle bytes and every digest are the substrate the
 * human approves and the gateway transmits. A changed draft is a new row, and the database rejects
 * any update or delete.
 */
@Entity(name = "BootstrapV2DraftEntity")
@Table(name = "gameplay_bootstrap_v2_draft")
public class BootstrapV2DraftEntity {
  @Id
  @Column(name = "draft_id", nullable = false, length = 128)
  public String draftId;

  @Column(name = "provider_session_id", nullable = false, length = 160)
  public String providerSessionId;

  @Column(name = "provider_turn_id", nullable = false, length = 160)
  public String providerTurnId;

  @Column(name = "request_id", nullable = false, unique = true, length = 128)
  public String requestId;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
  public String idempotencyKey;

  @Column(name = "recording_id", nullable = false, length = 160)
  public String recordingId;

  @Column(name = "authority_id", nullable = false, length = 128)
  public String authorityId;

  @Column(name = "bundle_bytes", nullable = false, columnDefinition = "text")
  public String bundleBytes;

  @Column(name = "bundle_digest", nullable = false, unique = true, length = 80)
  public String bundleDigest;

  @Column(name = "envelope_digest", nullable = false, length = 80)
  public String envelopeDigest;

  @Column(name = "scope_digest", nullable = false, unique = true, length = 80)
  public String scopeDigest;

  @Column(name = "catalog_digest", nullable = false, length = 80)
  public String catalogDigest;

  @Column(name = "observation_digest", nullable = false, length = 80)
  public String observationDigest;

  @Column(name = "planning_input_digest", nullable = false, length = 80)
  public String planningInputDigest;

  @Column(name = "eligibility_digest", nullable = false, length = 80)
  public String eligibilityDigest;

  @Column(name = "goal_plan_digest", nullable = false, length = 80)
  public String goalPlanDigest;

  @Column(name = "semantic_action_digest", nullable = false, length = 80)
  public String semanticActionDigest;

  @Column(name = "deadline", nullable = false)
  public Instant deadline;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  protected BootstrapV2DraftEntity() {}
}
