package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bundle of evidence collected for analysis.
 *
 * <p>Contains logs, tick logs, and decisions from a specific time/tick window.
 */
public class AnalysisV1EvidenceBundle {

  @JsonProperty("scriptName")
  private String scriptName;

  @JsonProperty("timeWindow")
  private TimeWindow timeWindow;

  @JsonProperty("tickWindow")
  private TickWindow tickWindow;

  @JsonProperty("logs")
  private List<LogEvidence> logs;

  @JsonProperty("tickLogs")
  private List<TickLogEvidence> tickLogs;

  @JsonProperty("decisions")
  private List<DecisionEvidence> decisions;

  public AnalysisV1EvidenceBundle() {
    this.logs = new ArrayList<>();
    this.tickLogs = new ArrayList<>();
    this.decisions = new ArrayList<>();
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public TimeWindow getTimeWindow() {
    return timeWindow;
  }

  public void setTimeWindow(TimeWindow timeWindow) {
    this.timeWindow = timeWindow;
  }

  public TickWindow getTickWindow() {
    return tickWindow;
  }

  public void setTickWindow(TickWindow tickWindow) {
    this.tickWindow = tickWindow;
  }

  public List<LogEvidence> getLogs() {
    return logs != null ? logs : new ArrayList<>();
  }

  public void setLogs(List<LogEvidence> logs) {
    this.logs = logs;
  }

  public List<TickLogEvidence> getTickLogs() {
    return tickLogs != null ? tickLogs : new ArrayList<>();
  }

  public void setTickLogs(List<TickLogEvidence> tickLogs) {
    this.tickLogs = tickLogs;
  }

  public List<DecisionEvidence> getDecisions() {
    return decisions != null ? decisions : new ArrayList<>();
  }

  public void setDecisions(List<DecisionEvidence> decisions) {
    this.decisions = decisions;
  }

  /** Returns the total count of evidence items across all types. */
  public int getTotalEvidenceCount() {
    return getLogs().size() + getTickLogs().size() + getDecisions().size();
  }

  /** Returns true if there is at least one piece of evidence. */
  public boolean hasEvidence() {
    return getTotalEvidenceCount() > 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AnalysisV1EvidenceBundle that = (AnalysisV1EvidenceBundle) o;
    return Objects.equals(scriptName, that.scriptName)
        && Objects.equals(timeWindow, that.timeWindow)
        && Objects.equals(tickWindow, that.tickWindow)
        && Objects.equals(logs, that.logs)
        && Objects.equals(tickLogs, that.tickLogs)
        && Objects.equals(decisions, that.decisions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scriptName, timeWindow, tickWindow, logs, tickLogs, decisions);
  }

  @Override
  public String toString() {
    return "AnalysisV1EvidenceBundle{"
        + "scriptName='"
        + scriptName
        + '\''
        + ", timeWindow="
        + timeWindow
        + ", tickWindow="
        + tickWindow
        + ", logs="
        + logs.size()
        + ", tickLogs="
        + tickLogs.size()
        + ", decisions="
        + decisions.size()
        + '}';
  }

  /** Evidence from general application logs. */
  public static class LogEvidence {

    @JsonProperty("timestamp")
    private LocalDateTime timestamp;

    @JsonProperty("level")
    private String level;

    @JsonProperty("message")
    private String message;

    @JsonProperty("source")
    private String source;

    public LogEvidence() {}

    public LogEvidence(LocalDateTime timestamp, String level, String message, String source) {
      this.timestamp = timestamp;
      this.level = level;
      this.message = message;
      this.source = source;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
    }

    public String getLevel() {
      return level;
    }

    public void setLevel(String level) {
      this.level = level;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LogEvidence that = (LogEvidence) o;
      return Objects.equals(timestamp, that.timestamp)
          && Objects.equals(level, that.level)
          && Objects.equals(message, that.message)
          && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timestamp, level, message, source);
    }

    @Override
    public String toString() {
      return "LogEvidence{"
          + "timestamp="
          + timestamp
          + ", level='"
          + level
          + '\''
          + ", message='"
          + message
          + '\''
          + ", source='"
          + source
          + '\''
          + '}';
    }
  }

  /** Evidence from game tick logs. */
  public static class TickLogEvidence {

    @JsonProperty("tick")
    private Long tick;

    @JsonProperty("timestamp")
    private LocalDateTime timestamp;

    @JsonProperty("subsystem")
    private String subsystem;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("message")
    private String message;

    @JsonProperty("details")
    private String details;

    public TickLogEvidence() {}

    public TickLogEvidence(
        Long tick,
        LocalDateTime timestamp,
        String subsystem,
        String severity,
        String message,
        String details) {
      this.tick = tick;
      this.timestamp = timestamp;
      this.subsystem = subsystem;
      this.severity = severity;
      this.message = message;
      this.details = details;
    }

    public Long getTick() {
      return tick;
    }

    public void setTick(Long tick) {
      this.tick = tick;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
    }

    public String getSubsystem() {
      return subsystem;
    }

    public void setSubsystem(String subsystem) {
      this.subsystem = subsystem;
    }

    public String getSeverity() {
      return severity;
    }

    public void setSeverity(String severity) {
      this.severity = severity;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getDetails() {
      return details;
    }

    public void setDetails(String details) {
      this.details = details;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TickLogEvidence that = (TickLogEvidence) o;
      return Objects.equals(tick, that.tick)
          && Objects.equals(timestamp, that.timestamp)
          && Objects.equals(subsystem, that.subsystem)
          && Objects.equals(severity, that.severity)
          && Objects.equals(message, that.message)
          && Objects.equals(details, that.details);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tick, timestamp, subsystem, severity, message, details);
    }

    @Override
    public String toString() {
      return "TickLogEvidence{"
          + "tick="
          + tick
          + ", timestamp="
          + timestamp
          + ", subsystem='"
          + subsystem
          + '\''
          + ", severity='"
          + severity
          + '\''
          + ", message='"
          + message
          + '\''
          + ", details='"
          + details
          + '\''
          + '}';
    }
  }

  /** Evidence from bot decision records. */
  public static class DecisionEvidence {

    @JsonProperty("timestamp")
    private LocalDateTime timestamp;

    @JsonProperty("decisionType")
    private String decisionType;

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("failureCode")
    private String failureCode;

    @JsonProperty("targetEntity")
    private String targetEntity;

    @JsonProperty("context")
    private String context;

    public DecisionEvidence() {}

    public DecisionEvidence(
        LocalDateTime timestamp,
        String decisionType,
        Boolean success,
        String failureCode,
        String targetEntity,
        String context) {
      this.timestamp = timestamp;
      this.decisionType = decisionType;
      this.success = success;
      this.failureCode = failureCode;
      this.targetEntity = targetEntity;
      this.context = context;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
    }

    public String getDecisionType() {
      return decisionType;
    }

    public void setDecisionType(String decisionType) {
      this.decisionType = decisionType;
    }

    public Boolean getSuccess() {
      return success;
    }

    public void setSuccess(Boolean success) {
      this.success = success;
    }

    public String getFailureCode() {
      return failureCode;
    }

    public void setFailureCode(String failureCode) {
      this.failureCode = failureCode;
    }

    public String getTargetEntity() {
      return targetEntity;
    }

    public void setTargetEntity(String targetEntity) {
      this.targetEntity = targetEntity;
    }

    public String getContext() {
      return context;
    }

    public void setContext(String context) {
      this.context = context;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DecisionEvidence that = (DecisionEvidence) o;
      return Objects.equals(timestamp, that.timestamp)
          && Objects.equals(decisionType, that.decisionType)
          && Objects.equals(success, that.success)
          && Objects.equals(failureCode, that.failureCode)
          && Objects.equals(targetEntity, that.targetEntity)
          && Objects.equals(context, that.context);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timestamp, decisionType, success, failureCode, targetEntity, context);
    }

    @Override
    public String toString() {
      return "DecisionEvidence{"
          + "timestamp="
          + timestamp
          + ", decisionType='"
          + decisionType
          + '\''
          + ", success="
          + success
          + ", failureCode='"
          + failureCode
          + '\''
          + ", targetEntity='"
          + targetEntity
          + '\''
          + ", context='"
          + context
          + '\''
          + '}';
    }
  }
}
