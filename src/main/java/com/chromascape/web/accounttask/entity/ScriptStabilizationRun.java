package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Single execution row from stabilization harness. */
@Entity
@Table(name = "script_stabilization_run")
public class ScriptStabilizationRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String reportId;

  @Column(nullable = false, length = 200)
  private String scriptKey;

  @Column(length = 50)
  private String family;

  @Column(length = 20, nullable = false)
  private String outcome; // PASS | FAIL | TIMEOUT

  @Column(length = 50)
  private String status;

  @Column private Long durationMs;

  @Column(length = 255)
  private String failureSignature;

  @Column(columnDefinition = "TEXT")
  private String errorMessage;

  @Column(nullable = false)
  private LocalDateTime executedAt;

  public ScriptStabilizationRun() {
    this.executedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getReportId() {
    return reportId;
  }

  public void setReportId(String reportId) {
    this.reportId = reportId;
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

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public String getFailureSignature() {
    return failureSignature;
  }

  public void setFailureSignature(String failureSignature) {
    this.failureSignature = failureSignature;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public LocalDateTime getExecutedAt() {
    return executedAt;
  }

  public void setExecutedAt(LocalDateTime executedAt) {
    this.executedAt = executedAt;
  }
}
