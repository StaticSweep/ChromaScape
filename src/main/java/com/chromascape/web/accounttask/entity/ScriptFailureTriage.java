package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Deduplicated triage record keyed by script + failure signature. */
@Entity
@Table(name = "script_failure_triage")
public class ScriptFailureTriage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 255)
  private String triageKey;

  @Column(nullable = false, length = 200)
  private String scriptKey;

  @Column(length = 50)
  private String family;

  @Column(nullable = false, length = 255)
  private String failureSignature;

  @Column(nullable = false)
  private Integer occurrences = 1;

  @Column(length = 50, nullable = false)
  private String status = "OPEN"; // OPEN | IN_PROGRESS | RESOLVED

  @Column(length = 100)
  private String lastReportId;

  @Column(columnDefinition = "TEXT")
  private String lastErrorMessage;

  @Column(length = 20)
  private String lastOutcome;

  @Column(length = 50)
  private String lastStatus;

  @Column
  private Long lastDurationMs;

  @Column
  private LocalDateTime lastExecutedAt;

  @Column(nullable = false)
  private Boolean backlogReady = false;

  @Column(nullable = false)
  private Integer backlogPriority = 0;

  @Column(nullable = false)
  private LocalDateTime firstSeenAt;

  @Column(nullable = false)
  private LocalDateTime lastSeenAt;

  public ScriptFailureTriage() {
    this.firstSeenAt = LocalDateTime.now();
    this.lastSeenAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getTriageKey() {
    return triageKey;
  }

  public void setTriageKey(String triageKey) {
    this.triageKey = triageKey;
  }

  public String getScriptKey() {
    return scriptKey;
  }

  public void setScriptKey(String scriptKey) {
    this.scriptKey = scriptKey;
  }

  public String getFamily() {
    return family;
  }

  public void setFamily(String family) {
    this.family = family;
  }

  public String getFailureSignature() {
    return failureSignature;
  }

  public void setFailureSignature(String failureSignature) {
    this.failureSignature = failureSignature;
  }

  public Integer getOccurrences() {
    return occurrences;
  }

  public void setOccurrences(Integer occurrences) {
    this.occurrences = occurrences;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getLastReportId() {
    return lastReportId;
  }

  public void setLastReportId(String lastReportId) {
    this.lastReportId = lastReportId;
  }

  public String getLastErrorMessage() {
    return lastErrorMessage;
  }

  public void setLastErrorMessage(String lastErrorMessage) {
    this.lastErrorMessage = lastErrorMessage;
  }

  public String getLastOutcome() {
    return lastOutcome;
  }

  public void setLastOutcome(String lastOutcome) {
    this.lastOutcome = lastOutcome;
  }

  public String getLastStatus() {
    return lastStatus;
  }

  public void setLastStatus(String lastStatus) {
    this.lastStatus = lastStatus;
  }

  public Long getLastDurationMs() {
    return lastDurationMs;
  }

  public void setLastDurationMs(Long lastDurationMs) {
    this.lastDurationMs = lastDurationMs;
  }

  public LocalDateTime getLastExecutedAt() {
    return lastExecutedAt;
  }

  public void setLastExecutedAt(LocalDateTime lastExecutedAt) {
    this.lastExecutedAt = lastExecutedAt;
  }

  public Boolean getBacklogReady() {
    return backlogReady;
  }

  public void setBacklogReady(Boolean backlogReady) {
    this.backlogReady = backlogReady;
  }

  public Integer getBacklogPriority() {
    return backlogPriority;
  }

  public void setBacklogPriority(Integer backlogPriority) {
    this.backlogPriority = backlogPriority;
  }

  public LocalDateTime getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(LocalDateTime firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public LocalDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(LocalDateTime lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}
