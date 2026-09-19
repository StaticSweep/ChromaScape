package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "script_failure_triage")
public class ScriptFailureTriage {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, unique = true, length = 255) private String triageKey;
  @Column(nullable = false, length = 200) private String scriptKey;
  @Column(nullable = false, length = 255) private String failureSignature;
  @Column(nullable = false) private Integer occurrences = 1;
  @Column(nullable = false, length = 50) private String status = "OPEN";
  @Column(length = 100) private String lastReportId;
  @Column(columnDefinition = "TEXT") private String lastErrorMessage;
  @Column(nullable = false) private LocalDateTime firstSeenAt = LocalDateTime.now();
  @Column(nullable = false) private LocalDateTime lastSeenAt = LocalDateTime.now();

  public Long getId() { return id; }
  public String getTriageKey() { return triageKey; }
  public void setTriageKey(String value) { triageKey = value; }
  public String getScriptKey() { return scriptKey; }
  public void setScriptKey(String value) { scriptKey = value; }
  public String getFailureSignature() { return failureSignature; }
  public void setFailureSignature(String value) { failureSignature = value; }
  public Integer getOccurrences() { return occurrences; }
  public void setOccurrences(Integer value) { occurrences = value; }
  public String getStatus() { return status; }
  public void setStatus(String value) { status = value; }
  public String getLastReportId() { return lastReportId; }
  public void setLastReportId(String value) { lastReportId = value; }
  public String getLastErrorMessage() { return lastErrorMessage; }
  public void setLastErrorMessage(String value) { lastErrorMessage = value; }
  public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
  public void setFirstSeenAt(LocalDateTime value) { firstSeenAt = value; }
  public LocalDateTime getLastSeenAt() { return lastSeenAt; }
  public void setLastSeenAt(LocalDateTime value) { lastSeenAt = value; }
}
