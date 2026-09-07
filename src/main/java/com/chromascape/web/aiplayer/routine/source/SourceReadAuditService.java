package com.chromascape.web.aiplayer.routine.source;

import com.chromascape.web.aiplayer.routine.source.persistence.SourceReadAuditEvent;
import com.chromascape.web.aiplayer.routine.source.persistence.SourceReadAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded source-read audit sink; deliberately never logs content, paths, tokens, or URLs. */
@Service
public class SourceReadAuditService {
  private static final Logger log = LoggerFactory.getLogger(SourceReadAuditService.class);
  private final SourceReadAuditEventRepository events;
  private final String environment;

  @Autowired
  public SourceReadAuditService(
      SourceReadAuditEventRepository events,
      @Value("${chromascape.source.environment:}") String environment) {
    this.events = events;
    this.environment = environment;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String actor,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result) {
    recordInternal(
        actor,
        operation,
        runId,
        recordingId,
        taskKey,
        snapshotId,
        fileId,
        result,
        null,
        MDC.get("source_correlation_id"));
  }

  /** Records with an explicit request correlation id without joining the caller's transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String actor,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result,
      String correlationId) {
    recordInternal(
        actor,
        operation,
        runId,
        recordingId,
        taskKey,
        snapshotId,
        fileId,
        result,
        null,
        correlationId);
  }

  /** Records a content-addressed retention event without pretending the hash is a file id. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordContent(String actor, String operation, String contentHash, String result) {
    recordInternal(
        actor,
        operation,
        null,
        null,
        null,
        null,
        null,
        result,
        contentHash,
        MDC.get("source_correlation_id"));
  }

  private void recordInternal(
      String actor,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result,
      String contentHash,
      String correlationId) {
    String safeActor = bounded(actor);
    String safeOperation = bounded(operation);
    String safeRun = bounded(runId);
    String safeRecording = bounded(recordingId);
    String safeTask = bounded(taskKey);
    String safeSnapshot = bounded(snapshotId);
    String safeFile = bounded(fileId);
    String safeResult = bounded(result);
    String safeContentHash = bounded(contentHash);
    String safeCorrelation = bounded(correlationId);
    if (events != null) {
      try {
        events.saveAndFlush(
            SourceReadAuditEvent.create(
                safeActor,
                safeOperation,
                safeRun,
                safeRecording,
                safeTask,
                safeSnapshot,
                safeFile,
                safeResult,
                environment,
                safeContentHash,
                safeCorrelation));
      } catch (RuntimeException exception) {
        // Audit persistence must never turn a read into a write or disclose database details to a
        // caller. The structured log remains the fallback audit sink.
        log.warn("source_read_audit_persistence_failed result={}", safeResult);
      }
    }
    log.info(
        "source_read actor={} operation={} run_id={} recording_id={} task_key={} snapshot_id={} file_id={} content_hash={} result={} correlation_id={}",
        safeActor,
        safeOperation,
        safeRun,
        safeRecording,
        safeTask,
        safeSnapshot,
        safeFile,
        safeContentHash,
        safeResult,
        safeCorrelation);
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "-";
    StringBuilder safe = new StringBuilder(Math.min(value.length(), 160));
    for (int index = 0; index < value.length() && safe.length() < 160; index++) {
      char character = value.charAt(index);
      safe.append(Character.isISOControl(character) ? '_' : character);
    }
    return safe.toString();
  }
}
