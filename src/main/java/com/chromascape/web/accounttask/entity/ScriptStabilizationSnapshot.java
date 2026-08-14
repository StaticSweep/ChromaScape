package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Historical KPI snapshot captured from a stabilization ingest. */
@Entity
@Table(name = "script_stabilization_snapshot")
public class ScriptStabilizationSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String reportId;

  @Column(nullable = false, length = 200)
  private String scriptKey;

  @Column(length = 50)
  private String family;

  @Column(nullable = false)
  private Integer window24hCount;

  @Column(nullable = false)
  private Integer window7dCount;

  @Column(nullable = false)
  private Integer passCount24h;

  @Column(nullable = false)
  private Integer failCount24h;

  @Column(nullable = false)
  private Integer timeoutCount24h;

  @Column(nullable = false)
  private Double passRate24h;

  @Column(nullable = false)
  private Double passRate7d;

  @Column(nullable = false)
  private Double timeoutRate24h;

  @Column(nullable = false)
  private Boolean regression;

  @Column(length = 100)
  private String baselineReportId;

  @Column(nullable = false)
  private Double baselinePassRate24h;

  @Column(nullable = false)
  private Integer failureClusterCount;

  @Column(length = 255)
  private String topFailureSignature;

  @Column(length = 200)
  private String matrixHash;

  @Column(length = 200)
  private String planHash;

  @Column(length = 200)
  private String buildId;

  @Column(length = 200)
  private String catalogHash;

  @Column private Double firstAttemptPassRate;

  @Column private Double eventualPassRate;

  @Column private Double evidenceCompleteRate;

  @Column private Double cleanupSuccessRate;

  @Column(length = 50)
  private String comparisonStatus;

  @Column(length = 255)
  private String groupKey;

  @Column(length = 200)
  private String artifactDigest;

  @Column(length = 200)
  private String executorInstanceId;

  @Column(length = 200)
  private String sourceSnapshotId;

  @Column private Integer retryCount;

  @Column(columnDefinition = "TEXT")
  private String matrixJson;

  @Column(columnDefinition = "TEXT")
  private String incomparableJson;

  @Column(nullable = false)
  private LocalDateTime capturedAt;

  public ScriptStabilizationSnapshot() {
    this.capturedAt = LocalDateTime.now();
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

  public Integer getWindow24hCount() {
    return window24hCount;
  }

  public void setWindow24hCount(Integer window24hCount) {
    this.window24hCount = window24hCount;
  }

  public Integer getWindow7dCount() {
    return window7dCount;
  }

  public void setWindow7dCount(Integer window7dCount) {
    this.window7dCount = window7dCount;
  }

  public Integer getPassCount24h() {
    return passCount24h;
  }

  public void setPassCount24h(Integer passCount24h) {
    this.passCount24h = passCount24h;
  }

  public Integer getFailCount24h() {
    return failCount24h;
  }

  public void setFailCount24h(Integer failCount24h) {
    this.failCount24h = failCount24h;
  }

  public Integer getTimeoutCount24h() {
    return timeoutCount24h;
  }

  public void setTimeoutCount24h(Integer timeoutCount24h) {
    this.timeoutCount24h = timeoutCount24h;
  }

  public Double getPassRate24h() {
    return passRate24h;
  }

  public void setPassRate24h(Double passRate24h) {
    this.passRate24h = passRate24h;
  }

  public Double getPassRate7d() {
    return passRate7d;
  }

  public void setPassRate7d(Double passRate7d) {
    this.passRate7d = passRate7d;
  }

  public Double getTimeoutRate24h() {
    return timeoutRate24h;
  }

  public void setTimeoutRate24h(Double timeoutRate24h) {
    this.timeoutRate24h = timeoutRate24h;
  }

  public Boolean getRegression() {
    return regression;
  }

  public void setRegression(Boolean regression) {
    this.regression = regression;
  }

  public String getBaselineReportId() {
    return baselineReportId;
  }

  public void setBaselineReportId(String baselineReportId) {
    this.baselineReportId = baselineReportId;
  }

  public Double getBaselinePassRate24h() {
    return baselinePassRate24h;
  }

  public void setBaselinePassRate24h(Double baselinePassRate24h) {
    this.baselinePassRate24h = baselinePassRate24h;
  }

  public Integer getFailureClusterCount() {
    return failureClusterCount;
  }

  public void setFailureClusterCount(Integer failureClusterCount) {
    this.failureClusterCount = failureClusterCount;
  }

  public String getTopFailureSignature() {
    return topFailureSignature;
  }

  public void setTopFailureSignature(String topFailureSignature) {
    this.topFailureSignature = topFailureSignature;
  }

  public String getMatrixHash() {
    return matrixHash;
  }

  public void setMatrixHash(String matrixHash) {
    this.matrixHash = matrixHash;
  }

  public String getPlanHash() {
    return planHash;
  }

  public void setPlanHash(String planHash) {
    this.planHash = planHash;
  }

  public String getBuildId() {
    return buildId;
  }

  public void setBuildId(String buildId) {
    this.buildId = buildId;
  }

  public String getCatalogHash() {
    return catalogHash;
  }

  public void setCatalogHash(String catalogHash) {
    this.catalogHash = catalogHash;
  }

  public Double getFirstAttemptPassRate() {
    return firstAttemptPassRate;
  }

  public void setFirstAttemptPassRate(Double firstAttemptPassRate) {
    this.firstAttemptPassRate = firstAttemptPassRate;
  }

  public Double getEventualPassRate() {
    return eventualPassRate;
  }

  public void setEventualPassRate(Double eventualPassRate) {
    this.eventualPassRate = eventualPassRate;
  }

  public Double getEvidenceCompleteRate() {
    return evidenceCompleteRate;
  }

  public void setEvidenceCompleteRate(Double evidenceCompleteRate) {
    this.evidenceCompleteRate = evidenceCompleteRate;
  }

  public Double getCleanupSuccessRate() {
    return cleanupSuccessRate;
  }

  public void setCleanupSuccessRate(Double cleanupSuccessRate) {
    this.cleanupSuccessRate = cleanupSuccessRate;
  }

  public String getComparisonStatus() {
    return comparisonStatus;
  }

  public void setComparisonStatus(String comparisonStatus) {
    this.comparisonStatus = comparisonStatus;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public String getArtifactDigest() {
    return artifactDigest;
  }

  public void setArtifactDigest(String artifactDigest) {
    this.artifactDigest = artifactDigest;
  }

  public String getExecutorInstanceId() {
    return executorInstanceId;
  }

  public void setExecutorInstanceId(String executorInstanceId) {
    this.executorInstanceId = executorInstanceId;
  }

  public String getSourceSnapshotId() {
    return sourceSnapshotId;
  }

  public void setSourceSnapshotId(String sourceSnapshotId) {
    this.sourceSnapshotId = sourceSnapshotId;
  }

  public Integer getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(Integer retryCount) {
    this.retryCount = retryCount;
  }

  public String getMatrixJson() {
    return matrixJson;
  }

  public void setMatrixJson(String matrixJson) {
    this.matrixJson = matrixJson;
  }

  public String getIncomparableJson() {
    return incomparableJson;
  }

  public void setIncomparableJson(String incomparableJson) {
    this.incomparableJson = incomparableJson;
  }

  public LocalDateTime getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(LocalDateTime capturedAt) {
    this.capturedAt = capturedAt;
  }
}
