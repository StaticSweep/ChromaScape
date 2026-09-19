package com.chromascape.web.gameplay.provider.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

final class ProviderRuntimeEntities {
  private ProviderRuntimeEntities() {}
}

@Entity
@Table(name = "gameplay_provider_session")
class ProviderSessionEntity {
  @Id String sessionId;
  String provider;
  String modelId;
  String state;
  String taskId;
  String runId;
  String recordingId;
  String promptDigest;
  String toolsetDigest;
  String knowledgeDigest;
  String goalPlanDigest;

  @Column(columnDefinition = "text")
  String requestJson;

  Instant deadline;
  boolean decideOnly;
  @Version long version;
  Instant createdAt;
  Instant updatedAt;

  protected ProviderSessionEntity() {}
}

@Entity
@Table(name = "gameplay_provider_budget_ledger")
class ProviderBudgetEntity {
  @Id String sessionId;
  long inputTokensUsed;
  long outputTokensUsed;
  int providerCallsUsed;
  int toolCallsUsed;
  int turnsUsed;
  long elapsedMillis;
  @Version long version;

  protected ProviderBudgetEntity() {}
}

@Entity
@Table(name = "gameplay_provider_turn")
class ProviderTurnEntity {
  @Id String turnId;
  String sessionId;
  String requestId;
  String idempotencyKey;
  String requestFingerprint;
  String state;
  String failureCode;
  String providerSessionRef;
  String providerTurnRef;
  String observationDigest;
  String eligibilityDigest;
  String decisionDigest;
  String semanticActionDigest;

  @Column(columnDefinition = "text")
  String requestJson;

  @Column(columnDefinition = "text")
  String usageJson;

  String transcriptRef;
  Instant deadline;
  boolean decideOnly;
  @Version long version;
  Instant createdAt;
  Instant updatedAt;

  protected ProviderTurnEntity() {}
}

@Entity
@Table(name = "gameplay_provider_event")
class ProviderEventEntity {
  @Id String eventId;
  String turnId;

  @Column(name = "sequence_no")
  long sequence;

  String eventType;
  String payloadDigest;
  Instant occurredAt;

  protected ProviderEventEntity() {}
}

@Entity
@Table(name = "gameplay_provider_tool_call")
class ProviderToolCallEntity {
  @Id String toolCallId;
  String turnId;
  String toolName;
  String argumentsDigest;
  String resultDigest;
  String state;
  int chargedCalls;
  Instant createdAt;

  protected ProviderToolCallEntity() {}
}

@Entity
@Table(name = "gameplay_provider_decision")
class ProviderDecisionEntity {
  @Id String decisionId;
  String turnId;
  String contractVersion;
  String decisionDigest;
  String semanticActionDigest;
  String outcome;

  @Column(columnDefinition = "text")
  String decisionJson;

  Instant createdAt;

  protected ProviderDecisionEntity() {}
}

@Entity
@Table(name = "gameplay_provider_transcript_artifact")
class ProviderTranscriptEntity {
  @Id String artifactId;
  String turnId;
  String sha256;
  String redactionVersion;
  long byteLength;
  Instant createdAt;

  protected ProviderTranscriptEntity() {}
}
