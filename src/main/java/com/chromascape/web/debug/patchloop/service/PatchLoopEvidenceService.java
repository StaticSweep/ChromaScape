package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle.DecisionEvidence;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle.LogEvidence;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle.TickLogEvidence;
import com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto;
import com.chromascape.web.debug.patchloop.dto.EvidenceType;
import com.chromascape.web.debug.patchloop.dto.TickWindow;
import com.chromascape.web.debug.patchloop.dto.TimeWindow;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for collecting evidence from logs, tick logs, and decisions.
 *
 * <p>Responsible for gathering evidence within a time/tick window for analysis.
 */
@Service
public class PatchLoopEvidenceService {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopEvidenceService.class);

  private static final int DEFAULT_MAX_LOG_ENTRIES = 500;
  private static final int DEFAULT_MAX_TICK_LOGS = 200;
  private static final int DEFAULT_MAX_DECISIONS = 100;
  private static final int DEFAULT_TIME_WINDOW_SECONDS = 60;

  private final LogRepository logRepository;
  private final TickLogRepository tickLogRepository;
  private final DecisionRepository decisionRepository;
  private final DebugSessionRepository debugSessionRepository;

  public PatchLoopEvidenceService(
      LogRepository logRepository,
      TickLogRepository tickLogRepository,
      DecisionRepository decisionRepository,
      DebugSessionRepository debugSessionRepository) {
    this.logRepository = logRepository;
    this.tickLogRepository = tickLogRepository;
    this.decisionRepository = decisionRepository;
    this.debugSessionRepository = debugSessionRepository;
  }

  /**
   * Collects evidence for the given iteration.
   *
   * @param iteration the iteration to collect evidence for
   * @return evidence bundle
   */
  @Transactional(readOnly = true)
  public AnalysisV1EvidenceBundle collectEvidence(PatchLoopIteration iteration) {
    return collectEvidence(iteration, null);
  }

  /**
   * Collects evidence for the given iteration using the provided config.
   *
   * @param iteration the iteration to collect evidence for
   * @param config evidence collection configuration (nullable)
   * @return evidence bundle
   */
  @Transactional(readOnly = true)
  public AnalysisV1EvidenceBundle collectEvidence(
      PatchLoopIteration iteration, EvidenceConfigDto config) {
    logger.info("Collecting evidence for iteration {}", iteration.getIterationId());

    AnalysisV1EvidenceBundle bundle = new AnalysisV1EvidenceBundle();
    bundle.setScriptName(iteration.getScriptName());

    // Parse config or use defaults
    int timeWindowSeconds =
        config != null && config.getTimeWindowSeconds() != null
            ? config.getTimeWindowSeconds()
            : DEFAULT_TIME_WINDOW_SECONDS;

    int maxLogs =
        config != null && config.getMaxLogEntries() != null
            ? config.getMaxLogEntries()
            : DEFAULT_MAX_LOG_ENTRIES;

    int maxTickLogs =
        config != null && config.getMaxTickLogs() != null
            ? config.getMaxTickLogs()
            : DEFAULT_MAX_TICK_LOGS;

    // Determine which evidence types to include (default: all)
    List<EvidenceType> includeTypes =
        config != null && config.getIncludeTypes() != null && !config.getIncludeTypes().isEmpty()
            ? config.getIncludeTypes()
            : Arrays.asList(EvidenceType.values());

    // Determine time window
    TimeWindow timeWindow = determineTimeWindow(iteration, timeWindowSeconds);
    bundle.setTimeWindow(timeWindow);

    // Determine tick window (optional)
    Optional<TickWindow> tickWindow = determineTickWindow(iteration);
    tickWindow.ifPresent(bundle::setTickWindow);

    // Collect evidence from each source based on includeTypes
    List<LogEvidence> logs;
    if (includeTypes.contains(EvidenceType.GENERAL_LOGS)) {
      logs = collectLogs(timeWindow, maxLogs);
    } else {
      logs = Collections.emptyList();
    }
    bundle.setLogs(logs);

    List<TickLogEvidence> tickLogs = Collections.emptyList();
    if (includeTypes.contains(EvidenceType.TICK_LOGS)
        && tickWindow.isPresent()
        && iteration.getRunSessionId() != null) {
      tickLogs = collectTickLogs(iteration.getRunSessionId(), tickWindow.get(), maxTickLogs);
    }
    bundle.setTickLogs(tickLogs);

    List<DecisionEvidence> decisions;
    if (includeTypes.contains(EvidenceType.DECISIONS)) {
      decisions = collectDecisions(timeWindow, iteration.getScriptName(), DEFAULT_MAX_DECISIONS);
    } else {
      decisions = Collections.emptyList();
    }
    bundle.setDecisions(decisions);

    // Update iteration with counts
    iteration.setEvidenceLogCount(logs.size());
    iteration.setEvidenceTickLogCount(tickLogs.size());
    iteration.setEvidenceDecisionCount(decisions.size());
    iteration.setEvidenceStartTime(timeWindow.getStart());
    iteration.setEvidenceEndTime(timeWindow.getEnd());

    logger.info(
        "Collected evidence: {} logs, {} tick logs, {} decisions",
        logs.size(),
        tickLogs.size(),
        decisions.size());

    return bundle;
  }

  /**
   * Determines the time window for evidence collection.
   *
   * @param iteration the iteration
   * @param timeWindowSeconds the time window in seconds (used as fallback)
   * @return time window
   */
  private TimeWindow determineTimeWindow(PatchLoopIteration iteration, int timeWindowSeconds) {
    // Use run times if available
    if (iteration.getRunStartTime() != null && iteration.getRunEndTime() != null) {
      return new TimeWindow(iteration.getRunStartTime(), iteration.getRunEndTime());
    }

    // Otherwise, use configured time window before iteration creation
    LocalDateTime end = iteration.getCreatedAt();
    LocalDateTime start = end.minusSeconds(timeWindowSeconds);
    return new TimeWindow(start, end);
  }

  /**
   * Determines the tick window for evidence collection (if debug session exists).
   *
   * @param iteration the iteration
   * @return tick window if available
   */
  private Optional<TickWindow> determineTickWindow(PatchLoopIteration iteration) {
    if (iteration.getRunSessionId() == null) {
      // Try to find most recent session for this script
      Optional<DebugSession> session =
          debugSessionRepository.findFirstByScriptNameOrderByStartTimeDesc(
              iteration.getScriptName());

      if (session.isPresent()) {
        iteration.setRunSessionId(session.get().getSessionId());
        if (session.get().getRecordingId() != null) {
          iteration.setRunRecordingId(session.get().getRecordingId());
        }
        return Optional.of(
            new TickWindow(session.get().getStartTick(), session.get().getEndTick()));
      }
      return Optional.empty();
    }

    // Load session by ID
    Optional<DebugSession> session =
        debugSessionRepository.findBySessionId(iteration.getRunSessionId());
    if (session.isEmpty()) {
      logger.warn("Debug session {} not found", iteration.getRunSessionId());
      return Optional.empty();
    }

    DebugSession s = session.get();
    return Optional.of(new TickWindow(s.getStartTick(), s.getEndTick()));
  }

  /**
   * Collects general application logs within the time window.
   *
   * @param timeWindow the time window
   * @param maxEntries maximum number of log entries to collect
   * @return list of log evidence
   */
  private List<LogEvidence> collectLogs(TimeWindow timeWindow, int maxEntries) {
    List<LogEntity> logs =
        logRepository.findByTimestampBetween(timeWindow.getStart(), timeWindow.getEnd());

    return logs.stream()
        .limit(maxEntries)
        .map(
            log ->
                new LogEvidence(
                    log.getTimestamp(), log.getLevel(), log.getMessage(), log.getSource()))
        .collect(Collectors.toList());
  }

  /**
   * Collects tick logs within the tick window.
   *
   * @param sessionId the debug session ID
   * @param tickWindow the tick window
   * @param maxEntries maximum number of tick logs to collect
   * @return list of tick log evidence
   */
  private List<TickLogEvidence> collectTickLogs(
      String sessionId, TickWindow tickWindow, int maxEntries) {
    List<TickLog> tickLogs =
        tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            sessionId, tickWindow.getStartTick(), tickWindow.getEndTick());

    return tickLogs.stream()
        .limit(maxEntries)
        .map(
            log -> {
              String details = log.getDetails() != null ? log.getDetails().toString() : null;
              return new TickLogEvidence(
                  log.getTick(),
                  log.getTimestamp(),
                  log.getSubsystem(),
                  log.getSeverity(),
                  log.getMessage(),
                  details);
            })
        .collect(Collectors.toList());
  }

  /**
   * Collects decisions within the time window.
   *
   * @param timeWindow the time window
   * @param scriptName filter by script name (optional)
   * @param maxEntries maximum number of decisions to collect
   * @return list of decision evidence
   */
  private List<DecisionEvidence> collectDecisions(
      TimeWindow timeWindow, String scriptName, int maxEntries) {
    List<DecisionEntity> decisions;

    if (scriptName != null && !scriptName.isEmpty()) {
      decisions =
          decisionRepository.findByScriptNameAndTimestampBetween(
              scriptName, timeWindow.getStart(), timeWindow.getEnd());
    } else {
      decisions =
          decisionRepository.findByTimestampBetween(timeWindow.getStart(), timeWindow.getEnd());
    }

    return decisions.stream()
        .limit(maxEntries)
        .map(
            d ->
                new DecisionEvidence(
                    d.getTimestamp(),
                    d.getDecisionType() != null ? d.getDecisionType().name() : "UNKNOWN",
                    d.getSuccess(),
                    d.getFailureCode(),
                    d.getTargetEntity(),
                    d.getDecisionReason()))
        .collect(Collectors.toList());
  }

  /**
   * Validates that the evidence bundle has sufficient data for analysis.
   *
   * @param bundle the evidence bundle
   * @return true if sufficient, false otherwise
   */
  public boolean validateEvidenceSufficiency(AnalysisV1EvidenceBundle bundle) {
    if (bundle == null) {
      return false;
    }

    if (bundle.getTimeWindow() == null || !bundle.getTimeWindow().isValid()) {
      logger.warn("Invalid time window in evidence bundle");
      return false;
    }

    if (!bundle.hasEvidence()) {
      logger.warn("No evidence found in bundle");
      return false;
    }

    return true;
  }
}
