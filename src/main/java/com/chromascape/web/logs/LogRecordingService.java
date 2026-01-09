package com.chromascape.web.logs;

import com.chromascape.web.scripts.ScriptEventEntity;
import com.chromascape.web.scripts.ScriptEventRepository;
import java.time.LocalDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for recording log entries to the database. */
@Service
public class LogRecordingService {

  private static final Logger logger = LogManager.getLogger(LogRecordingService.class);

  private final LogRepository logRepository;
  private final ScriptEventRepository scriptEventRepository;

  // Track currently active script
  private volatile String activeScript;
  private volatile Long currentScriptEventId;

  @Autowired
  public LogRecordingService(
      LogRepository logRepository, ScriptEventRepository scriptEventRepository) {
    this.logRepository = logRepository;
    this.scriptEventRepository = scriptEventRepository;
  }

  /** Record a log entry asynchronously to avoid blocking the main thread. */
  @Async
  @Transactional
  public void recordLog(String level, String message, String source) {
    try {
      LogEntity logEntity =
          new LogEntity(LocalDateTime.now(), level, message, source, activeScript);
      logRepository.save(logEntity);
      logger.debug("Recorded log to database: {}", message);
    } catch (Exception e) {
      logger.error("Failed to record log to database: {}", e.getMessage());
    }
  }

  /** Record a script start event. */
  @Transactional
  public void recordScriptStart(String scriptName) {
    try {
      // First, stop any currently active script
      if (activeScript != null) {
        recordScriptStop(activeScript);
      }

      ScriptEventEntity event =
          new ScriptEventEntity(ScriptEventEntity.EventType.START, scriptName, LocalDateTime.now());
      event = scriptEventRepository.save(event);
      activeScript = scriptName;
      currentScriptEventId = event.getId();
      logger.info("Recorded script start: {}", scriptName);
    } catch (Exception e) {
      logger.error("Failed to record script start: {}", e.getMessage());
    }
  }

  /** Record a script stop event. */
  @Transactional
  public void recordScriptStop(String scriptName) {
    try {
      ScriptEventEntity event =
          new ScriptEventEntity(ScriptEventEntity.EventType.STOP, scriptName, LocalDateTime.now());
      scriptEventRepository.save(event);
      if (scriptName.equals(activeScript)) {
        activeScript = null;
        currentScriptEventId = null;
      }
      logger.info("Recorded script stop: {}", scriptName);
    } catch (Exception e) {
      logger.error("Failed to record script stop: {}", e.getMessage());
    }
  }

  /** Get the currently active script name. */
  public String getActiveScript() {
    return activeScript;
  }

  /** Set the active script name (for manual control). */
  public void setActiveScript(String scriptName) {
    this.activeScript = scriptName;
  }
}
