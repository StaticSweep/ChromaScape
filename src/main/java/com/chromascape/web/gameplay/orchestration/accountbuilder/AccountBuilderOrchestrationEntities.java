package com.chromascape.web.gameplay.orchestration.accountbuilder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

final class AccountBuilderOrchestrationEntities {
  private AccountBuilderOrchestrationEntities() {}
}

@Entity(name = "AccountBuilderOrchestrationEntity")
@Table(name = "ab_orchestration")
class AccountBuilderOrchestrationEntity {
  @Id
  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "request_id", nullable = false, unique = true, length = 128)
  String requestId;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
  String idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, length = 80)
  String requestFingerprint;

  @Column(name = "envelope_digest", nullable = false, length = 80)
  String envelopeDigest;

  @Column(name = "source_type", nullable = false, length = 128)
  String sourceType;

  @Column(name = "mode", nullable = false, length = 32)
  String mode;

  @Column(name = "state", nullable = false, length = 32)
  String state;

  @Column(name = "failure_code", nullable = false, length = 64)
  String failureCode;

  @Column(name = "request_json", nullable = false, columnDefinition = "text")
  String requestJson;

  @Column(name = "view_json", nullable = false, columnDefinition = "text")
  String viewJson;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "retry_allowed", nullable = false)
  boolean retryAllowed;

  @Column(name = "view_version", nullable = false)
  long viewVersion;

  @Version
  @Column(name = "entity_version", nullable = false)
  long entityVersion;
}

@Entity(name = "AccountBuilderOrchestrationEventEntity")
@Table(name = "ab_orchestration_event")
class AccountBuilderOrchestrationEventEntity {
  @Id
  @Column(name = "event_id", nullable = false, length = 64)
  String eventId;

  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "sequence_number", nullable = false)
  long sequence;

  @Column(name = "event_type", nullable = false, length = 64)
  String eventType;

  @Column(name = "payload_digest", nullable = false, length = 80)
  String payloadDigest;

  @Column(name = "occurred_at", nullable = false)
  Instant occurredAt;
}

/** Every immutable contract digest bound to an orchestration at claim time. */
@Entity(name = "AccountBuilderOrchestrationSnapshotBindingEntity")
@Table(name = "ab_snapshot_binding")
class AccountBuilderOrchestrationSnapshotBindingEntity {
  @Id
  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "decision_digest", nullable = false, length = 80)
  String decisionDigest;

  @Column(name = "semantic_action_digest", nullable = false, length = 80)
  String semanticActionDigest;

  @Column(name = "catalog_digest", nullable = false, length = 80)
  String catalogDigest;

  @Column(name = "capability_digest", nullable = false, length = 80)
  String capabilityDigest;

  @Column(name = "goal_plan_digest", nullable = false, length = 80)
  String goalPlanDigest;

  @Column(name = "task_skill_digests", nullable = false, columnDefinition = "text")
  String taskSkillDigestsJson;

  @Column(name = "tool_schema_digest", nullable = false, length = 80)
  String toolSchemaDigest;

  @Column(name = "knowledge_digest", nullable = false, length = 80)
  String knowledgeDigest;

  @Column(name = "policy_digest", nullable = false, length = 80)
  String policyDigest;

  @Column(name = "verifier_digest", nullable = false, length = 80)
  String verifierDigest;

  @Column(name = "build_id", nullable = false, length = 128)
  String buildId;

  @Column(name = "expected_observation_revision", nullable = false)
  long expectedObservationRevision;

  @Column(name = "expected_task_revision", nullable = false)
  long expectedTaskRevision;
}

/** Durable lease, generation, fence and expiry for one orchestration claim. */
@Entity(name = "AccountBuilderOrchestrationLeaseEntity")
@Table(name = "ab_orchestration_lease")
class AccountBuilderOrchestrationLeaseEntity {
  @Id
  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "reservation_id", nullable = false, unique = true, length = 128)
  String reservationId;

  @Column(name = "generation", nullable = false)
  long generation;

  @Column(name = "fence", nullable = false)
  long fence;

  @Column(name = "fence_token_digest", nullable = false, length = 80)
  String fenceTokenDigest;

  @Column(name = "expires_at", nullable = false)
  Instant expiresAt;

  @Column(name = "state", nullable = false, length = 32)
  String state;

  @Version
  @Column(name = "entity_version", nullable = false)
  long entityVersion;
}

/** Append-only normalized gateway response. */
@Entity(name = "AccountBuilderOrchestrationReceiptEntity")
@Table(name = "ab_orchestration_receipt")
class AccountBuilderOrchestrationReceiptEntity {
  @Id
  @Column(name = "receipt_id", nullable = false, length = 128)
  String receiptId;

  @Column(name = "receipt_identity", nullable = false, length = 256)
  String receiptIdentity;

  @Column(name = "receipt_digest", nullable = false, length = 80)
  String receiptDigest;

  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "sequence_number", nullable = false)
  long sequence;

  @Column(name = "phase", nullable = false, length = 64)
  String phase;

  @Column(name = "outcome", nullable = false, length = 32)
  String outcome;

  @Column(name = "stable_code", nullable = false, length = 64)
  String stableCode;

  @Column(name = "receipt_json", nullable = false, columnDefinition = "text")
  String receiptJson;

  @Column(name = "evidence_digest", nullable = true, length = 80)
  String evidenceDigest;

  @Column(name = "occurred_at", nullable = false)
  Instant occurredAt;
}

/** Exactly one immutable terminal result per orchestration. */
@Entity(name = "AccountBuilderOrchestrationTerminalResultEntity")
@Table(name = "ab_orchestration_terminal_result")
class AccountBuilderOrchestrationTerminalResultEntity {
  @Id
  @Column(name = "orchestration_id", nullable = false, length = 128)
  String orchestrationId;

  @Column(name = "outcome", nullable = false, length = 32)
  String outcome;

  @Column(name = "stable_code", nullable = false, length = 64)
  String stableCode;

  @Column(name = "retry_allowed", nullable = false)
  boolean retryAllowed;

  @Column(name = "reconciled", nullable = false)
  boolean reconciled;

  @Column(name = "evidence_digest", nullable = true, length = 80)
  String evidenceDigest;

  @Column(name = "terminal_at", nullable = false)
  Instant terminalAt;
}

/** Durable ownership marker for a one-use human authority. Secrets are intentionally absent. */
@Entity(name = "AccountBuilderAuthorityConsumptionEntity")
@Table(name = "ab_authority_consumption")
class AccountBuilderAuthorityConsumptionEntity {
  @Id
  @Column(name = "authority_id", nullable = false, length = 128)
  String authorityId;

  @Column(name = "orchestration_id", nullable = false, unique = true, length = 128)
  String orchestrationId;

  @Column(name = "envelope_digest", nullable = false, length = 80)
  String envelopeDigest;

  @Column(name = "approved_scope_digest", nullable = false, length = 80)
  String approvedScopeDigest;

  @Column(name = "operator_id", nullable = false, length = 128)
  String operatorId;

  @Column(name = "expires_at", nullable = false)
  Instant expiresAt;

  @Column(name = "consumed_at", nullable = false)
  Instant consumedAt;
}
