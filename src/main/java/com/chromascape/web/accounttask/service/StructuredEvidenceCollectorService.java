package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Collects structured evidence from multiple telemetry sources for task synthesis.
 *
 * <p>Queries logs, decisions, tick data, and recordings within specified time windows to build the
 * evidence base used by the synthesis service.
 */
@Service
public class StructuredEvidenceCollectorService {

  private static final Logger logger =
      LoggerFactory.getLogger(StructuredEvidenceCollectorService.class);

  private final LogRepository logRepository;
  private final DecisionRepository decisionRepository;
  private final TickLogRepository tickLogRepository;
  private final RecordingSessionRepository recordingSessionRepository;
  private final TaskEvidenceCitationRepository citationRepository;

  public StructuredEvidenceCollectorService(
      LogRepository logRepository,
      DecisionRepository decisionRepository,
      TickLogRepository tickLogRepository,
      RecordingSessionRepository recordingSessionRepository,
      TaskEvidenceCitationRepository citationRepository) {
    this.logRepository = logRepository;
    this.decisionRepository = decisionRepository;
    this.tickLogRepository = tickLogRepository;
    this.recordingSessionRepository = recordingSessionRepository;
    this.citationRepository = citationRepository;
  }

  /**
   * Collects all available evidence within the given time window.
   *
   * @param start beginning of the evidence window
   * @param end end of the evidence window
   * @param sourceSessionId optional session filter
   * @return collected evidence summary
   */
  public EvidenceSummary collectEvidence(
      LocalDateTime start, LocalDateTime end, String sourceSessionId) {
    logger.info("Collecting evidence from {} to {} (session: {})", start, end, sourceSessionId);

    EvidenceSummary summary = new EvidenceSummary();

    // Collect logs
    List<LogEntity> logs;
    if (sourceSessionId != null) {
      logs = logRepository.findByTimestampBetween(start, end);
    } else {
      logs = logRepository.findByTimestampBetween(start, end);
    }
    summary.setLogs(logs);
    logger.debug("Collected {} logs", logs.size());

    // Collect decisions
    List<DecisionEntity> decisions = decisionRepository.findByTimestampBetween(start, end);
    summary.setDecisions(decisions);
    logger.debug("Collected {} decisions", decisions.size());

    // Collect tick logs if session is specified
    if (sourceSessionId != null) {
      List<TickLog> tickLogs =
          tickLogRepository.findBySessionIdAndTimestampBetweenOrderByTickAsc(
              sourceSessionId, start, end);
      summary.setTickLogs(tickLogs);
      logger.debug("Collected {} tick logs", tickLogs.size());
    }

    // Collect recording sessions
    List<RecordingSession> recordings =
        recordingSessionRepository.findByStartTimeBetweenOrderByStartTimeDesc(start, end);
    summary.setRecordingSessions(recordings);
    logger.debug("Collected {} recording sessions", recordings.size());

    return summary;
  }

  /**
   * Creates and persists evidence citations for a task plan item.
   *
   * @param planId the plan ID
   * @param itemId the item ID
   * @param evidence the collected evidence
   * @return the created citations
   */
  public List<TaskEvidenceCitation> createCitations(
      String planId, String itemId, EvidenceSummary evidence) {
    List<TaskEvidenceCitation> citations = new ArrayList<>();

    // Create citations for key logs
    for (LogEntity log : evidence.getLogs()) {
      if (log.getLevel() != null
          && ("ERROR".equals(log.getLevel()) || "WARN".equals(log.getLevel()))
          && citations.size() < 50) {
        TaskEvidenceCitation citation = new TaskEvidenceCitation();
        citation.setPlanId(planId);
        citation.setItemId(itemId);
        citation.setEvidenceType(EvidenceType.LOG);
        citation.setEvidenceId(log.getId());
        citation.setSnippet(
            log.getMessage() != null && log.getMessage().length() > 500
                ? log.getMessage().substring(0, 500)
                : log.getMessage());
        citation.setEvidenceTimestamp(log.getTimestamp());
        citation.setRelevanceScore(0.8);
        citations.add(citationRepository.save(citation));
      }
    }

    // Create citations for failed decisions
    for (DecisionEntity decision : evidence.getDecisions()) {
      if (!Boolean.TRUE.equals(decision.getSuccess()) && citations.size() < 50) {
        TaskEvidenceCitation citation = new TaskEvidenceCitation();
        citation.setPlanId(planId);
        citation.setItemId(itemId);
        citation.setEvidenceType(EvidenceType.DECISION);
        citation.setEvidenceId(decision.getId());
        citation.setSnippet(decision.getDecisionReason());
        citation.setEvidenceTimestamp(decision.getTimestamp());
        citation.setRelevanceScore(0.9);
        citations.add(citationRepository.save(citation));
      }
    }

    // Create citations for tick logs (state/tick evidence)
    for (TickLog tickLog : evidence.getTickLogs()) {
      if (citations.size() >= 50) {
        break;
      }
      TaskEvidenceCitation citation = new TaskEvidenceCitation();
      citation.setPlanId(planId);
      citation.setItemId(itemId);
      citation.setEvidenceType(EvidenceType.TICK_LOG);
      citation.setEvidenceId(tickLog.getId());
      citation.setSnippet(
          String.format(
              "Tick %d @ %s",
              tickLog.getTick(),
              tickLog.getMessage() != null ? tickLog.getMessage() : "no message"));
      citation.setEvidenceTimestamp(tickLog.getTimestamp());
      citation.setRelevanceScore(0.85);
      citations.add(citationRepository.save(citation));
    }

    // Create citations for recording sessions
    for (RecordingSession session : evidence.getRecordingSessions()) {
      if (citations.size() >= 50) {
        break;
      }
      TaskEvidenceCitation citation = new TaskEvidenceCitation();
      citation.setPlanId(planId);
      citation.setItemId(itemId);
      citation.setEvidenceType(EvidenceType.RECORDING_SESSION);
      citation.setEvidenceId(session.getId());
      citation.setSnippet(
          String.format("Recording: %s (%s)", session.getRecordingId(), session.getScriptName()));
      citation.setEvidenceTimestamp(session.getStartTime());
      citation.setRelevanceScore(0.7);
      citations.add(citationRepository.save(citation));
    }

    logger.info("Created {} evidence citations for item {}", citations.size(), itemId);
    return citations;
  }

  /** Summary of collected evidence from multiple sources. */
  public static class EvidenceSummary {
    private List<LogEntity> logs = List.of();
    private List<DecisionEntity> decisions = List.of();
    private List<TickLog> tickLogs = List.of();
    private List<RecordingSession> recordingSessions = List.of();

    public List<LogEntity> getLogs() {
      return logs;
    }

    public void setLogs(List<LogEntity> logs) {
      this.logs = logs;
    }

    public List<DecisionEntity> getDecisions() {
      return decisions;
    }

    public void setDecisions(List<DecisionEntity> decisions) {
      this.decisions = decisions;
    }

    public List<TickLog> getTickLogs() {
      return tickLogs;
    }

    public void setTickLogs(List<TickLog> tickLogs) {
      this.tickLogs = tickLogs;
    }

    public List<RecordingSession> getRecordingSessions() {
      return recordingSessions;
    }

    public void setRecordingSessions(List<RecordingSession> recordingSessions) {
      this.recordingSessions = recordingSessions;
    }
  }
}
