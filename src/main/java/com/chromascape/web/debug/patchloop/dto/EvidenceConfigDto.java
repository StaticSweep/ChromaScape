package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for evidence collection.
 *
 * <p>Optional settings to control what evidence is collected and how much.
 */
public class EvidenceConfigDto {

  /** Time window in seconds (default: 60) */
  @JsonProperty("timeWindowSeconds")
  private Integer timeWindowSeconds;

  /** Evidence types to include (default: all) */
  @JsonProperty("includeTypes")
  private List<EvidenceType> includeTypes;

  /** Maximum log entries to collect (default: 500) */
  @JsonProperty("maxLogEntries")
  private Integer maxLogEntries;

  /** Maximum tick logs to collect (default: 200) */
  @JsonProperty("maxTickLogs")
  private Integer maxTickLogs;

  public EvidenceConfigDto() {
    this.timeWindowSeconds = 60;
    this.includeTypes = new ArrayList<>();
    this.maxLogEntries = 500;
    this.maxTickLogs = 200;
  }

  public Integer getTimeWindowSeconds() {
    return timeWindowSeconds;
  }

  public void setTimeWindowSeconds(Integer timeWindowSeconds) {
    this.timeWindowSeconds = timeWindowSeconds;
  }

  public List<EvidenceType> getIncludeTypes() {
    return includeTypes;
  }

  public void setIncludeTypes(List<EvidenceType> includeTypes) {
    this.includeTypes = includeTypes;
  }

  public Integer getMaxLogEntries() {
    return maxLogEntries;
  }

  public void setMaxLogEntries(Integer maxLogEntries) {
    this.maxLogEntries = maxLogEntries;
  }

  public Integer getMaxTickLogs() {
    return maxTickLogs;
  }

  public void setMaxTickLogs(Integer maxTickLogs) {
    this.maxTickLogs = maxTickLogs;
  }
}
