package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for exporting debug sessions.
 *
 * <p>Provides endpoints for exporting debug session data as ZIP files containing metadata, logs
 * CSV, state snapshots JSON, and optionally the video file.
 */
@RestController
@RequestMapping("/api/debug/export")
public class DebugExportController {

  private static final Logger log = LoggerFactory.getLogger(DebugExportController.class);
  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

  private final DebugSessionRepository sessionRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final TickLogRepository logRepository;
  private final RecordingSessionRepository recordingSessionRepository;
  private final ObjectMapper objectMapper;

  public DebugExportController(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      RecordingSessionRepository recordingSessionRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.recordingSessionRepository = recordingSessionRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Export a debug session as a ZIP file.
   *
   * @param sessionId the session ID to export
   * @return ZIP file containing session data
   */
  @PostMapping("/{sessionId}")
  public ResponseEntity<Resource> exportSession(@PathVariable String sessionId) {
    try {
      // Get the debug session
      DebugSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (session == null) {
        return ResponseEntity.notFound().build();
      }

      // Create temporary ZIP file
      File tempZip = File.createTempFile("debug_export_" + sessionId + "_", ".zip");

      try (ZipOutputStream zipOut = new ZipOutputStream(new java.io.FileOutputStream(tempZip))) {
        // 1. Add session metadata
        addSessionMetadata(zipOut, session);

        // 2. Add logs as CSV
        addLogsCsv(zipOut, sessionId);

        // 3. Add state snapshots as JSON
        addSnapshotsJson(zipOut, sessionId);

        // 4. Add video file if available
        if (session.getRecordingId() != null) {
          addVideoFile(zipOut, session.getRecordingId());
        }
      }

      // Create resource and send
      Resource resource = new FileSystemResource(tempZip);

      String filename =
          String.format(
              "debug_session_%s_%s.zip",
              session.getScriptName() != null ? session.getScriptName() : "unknown",
              session.getStartTime().format(TIMESTAMP_FORMATTER));

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
      headers.setContentDispositionFormData("attachment", filename);

      return ResponseEntity.ok().headers(headers).body(resource);

    } catch (Exception e) {
      log.error("Error exporting session {}: {}", sessionId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /** Add session metadata to the ZIP. */
  private void addSessionMetadata(ZipOutputStream zipOut, DebugSession session) throws IOException {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("sessionId", session.getSessionId());
    metadata.put("accountName", session.getAccountName());
    metadata.put("scriptName", session.getScriptName());
    metadata.put("startTime", session.getStartTime().toString());
    metadata.put("endTime", session.getEndTime() != null ? session.getEndTime().toString() : null);
    metadata.put("startTick", session.getStartTick());
    metadata.put("endTick", session.getEndTick());
    metadata.put("recordingId", session.getRecordingId());

    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);

    ZipEntry entry = new ZipEntry("session-metadata.json");
    zipOut.putNextEntry(entry);
    zipOut.write(json.getBytes());
    zipOut.closeEntry();
  }

  /** Add logs as CSV to the ZIP. */
  private void addLogsCsv(ZipOutputStream zipOut, String sessionId) throws IOException {
    List<TickLog> logs = logRepository.findBySessionIdOrderByTickAsc(sessionId);

    ZipEntry entry = new ZipEntry("logs.csv");
    zipOut.putNextEntry(entry);

    // CSV header
    String header = "Tick,Timestamp,Subsystem,Severity,EventType,Message,Details\n";
    zipOut.write(header.getBytes());

    // CSV rows
    for (TickLog log : logs) {
      StringBuilder row = new StringBuilder();
      row.append(log.getTick()).append(",");
      row.append(escapeCsv(log.getTimestamp().toString())).append(",");
      row.append(escapeCsv(log.getSubsystem())).append(",");
      row.append(escapeCsv(log.getSeverity())).append(",");
      row.append(escapeCsv(log.getEventType() != null ? log.getEventType() : "")).append(",");
      row.append(escapeCsv(log.getMessage())).append(",");
      row.append(escapeCsv(log.getDetails() != null ? log.getDetails().toString() : ""));
      row.append("\n");

      zipOut.write(row.toString().getBytes());
    }

    zipOut.closeEntry();

    log.info("Added {} log entries to export", logs.size());
  }

  /** Add state snapshots as JSON to the ZIP. */
  private void addSnapshotsJson(ZipOutputStream zipOut, String sessionId) throws IOException {
    List<TickSnapshot> snapshots = snapshotRepository.findBySessionIdOrderByTick(sessionId);

    Map<String, Object> data = new HashMap<>();
    data.put("sessionId", sessionId);
    data.put("count", snapshots.size());
    data.put("snapshots", snapshots);

    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);

    ZipEntry entry = new ZipEntry("state-snapshots.json");
    zipOut.putNextEntry(entry);
    zipOut.write(json.getBytes());
    zipOut.closeEntry();

    log.info("Added {} snapshots to export", snapshots.size());
  }

  /** Add video file to the ZIP if available. */
  private void addVideoFile(ZipOutputStream zipOut, String recordingId) throws IOException {
    RecordingSession recording =
        recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
    if (recording == null || recording.getVideoPath() == null) {
      return;
    }

    File videoFile = new File(recording.getVideoPath());
    if (!videoFile.exists()) {
      log.warn("Video file not found: {}", recording.getVideoPath());
      return;
    }

    ZipEntry entry = new ZipEntry("video.mp4");
    zipOut.putNextEntry(entry);

    try (FileInputStream fis = new FileInputStream(videoFile)) {
      byte[] buffer = new byte[8192];
      int len;
      while ((len = fis.read(buffer)) > 0) {
        zipOut.write(buffer, 0, len);
      }
    }

    zipOut.closeEntry();

    log.info("Added video file to export: {}", recording.getVideoPath());
  }

  /** Escape a value for CSV format. */
  private String escapeCsv(String value) {
    if (value == null) {
      return "";
    }

    // Escape quotes and wrap in quotes if contains comma, quote, or newline
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    return value;
  }
}
