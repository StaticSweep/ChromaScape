package com.chromascape.web.recording.controller;

import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for game recording and replay functionality.
 *
 * <p>Provides endpoints to start/stop recordings, list available recording sessions, and download
 * video files.
 */
@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

  private static final Logger logger = LogManager.getLogger(RecordingController.class);

  private final RecordingService recordingService;
  private final RecordingSessionRepository recordingSessionRepository;
  private final WindowSelectionService windowSelectionService;

  @Autowired
  public RecordingController(
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      WindowSelectionService windowSelectionService) {
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.windowSelectionService = windowSelectionService;
  }

  /**
   * Starts a new recording session.
   *
   * @return Response with recording ID if successful
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> startRecording(
      @RequestParam(required = false) String accountName,
      @RequestParam(required = false) String scriptName) {
    // Get account from window selection if not provided
    if (accountName == null) {
      WindowInfo window = windowSelectionService.getSelectedWindow();
      accountName = window != null ? window.getAccountName() : "default";
    }

    String recordingId = recordingService.startRecording();

    if (recordingId != null) {
      // Create RecordingSession entity
      RecordingSession session =
          new RecordingSession(
              recordingId,
              accountName,
              scriptName,
              LocalDateTime.now(),
              RecordingSession.SessionTrigger.MANUAL,
              null); // videoPath will be set on stop

      recordingSessionRepository.save(session);
      logger.info("Created recording session: {} for account: {}", recordingId, accountName);

      Map<String, Object> response = new HashMap<>();
      response.put("status", "recording");
      response.put("recordingId", recordingId);
      response.put("account", accountName);
      response.put("message", "Recording started");
      return ResponseEntity.ok(response);
    } else {
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("message", "Failed to start recording (may already be in progress)");
      return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
  }

  /**
   * Stops the current recording session.
   *
   * @return Response with recording metadata if successful
   */
  @PostMapping("/stop")
  public ResponseEntity<Map<String, Object>> stopRecording() {
    RecordingMetadata metadata = recordingService.stopRecording();

    if (metadata != null) {
      // Update RecordingSession entity
      recordingSessionRepository
          .findByRecordingId(metadata.getId())
          .ifPresent(
              session -> {
                session.setEndTime(LocalDateTime.now());
                session.setVideoPath(metadata.getFilePath());
                session.setDurationMs(metadata.getDuration());
                session.setFrameCount(metadata.getFrameCount());
                session.setFps(metadata.getFps());
                recordingSessionRepository.save(session);
                logger.info(
                    "Updated recording session: {} with duration: {}ms",
                    metadata.getId(),
                    metadata.getDuration());
              });

      Map<String, Object> response = new HashMap<>();
      response.put("status", "stopped");
      response.put("recordingId", metadata.getId());
      response.put("duration", metadata.getDuration());
      response.put("frameCount", metadata.getFrameCount());
      response.put("filePath", metadata.getFilePath());
      response.put("message", "Recording saved");
      return ResponseEntity.ok(response);
    } else {
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("message", "No recording in progress");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
  }

  /**
   * Gets the current recording status.
   *
   * @return Response with current state
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getStatus() {
    Map<String, Object> response = new HashMap<>();
    response.put("recording", recordingService.isRecording());
    response.put("recordingId", recordingService.getCurrentRecordingId());
    return ResponseEntity.ok(response);
  }

  /**
   * Lists all available recordings.
   *
   * @return Response with list of recordings
   */
  @GetMapping("/list")
  public ResponseEntity<List<Map<String, Object>>> listRecordings() {
    List<Map<String, Object>> recordings = new ArrayList<>();

    try {
      Path recordingsDir = Paths.get("recordings");
      if (Files.exists(recordingsDir)) {
        try (Stream<Path> paths = Files.list(recordingsDir)) {
          paths
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".mp4"))
              .forEach(
                  p -> {
                    Map<String, Object> info = new HashMap<>();
                    File file = p.toFile();
                    info.put("id", file.getName().replace(".mp4", ""));
                    info.put("filename", file.getName());
                    info.put("size", file.length());
                    info.put("lastModified", file.lastModified());
                    info.put("url", "/api/recordings/download/" + file.getName());
                    recordings.add(info);
                  });
        }
      }
    } catch (Exception e) {
      logger.error("Failed to list recordings: {}", e.getMessage());
    }

    return ResponseEntity.ok(recordings);
  }

  /**
   * Lists all recording sessions from the database.
   *
   * @return Response with list of recording sessions
   */
  @GetMapping("/sessions")
  public ResponseEntity<List<Map<String, Object>>> listRecordingSessions() {
    try {
      List<RecordingSession> sessions = recordingSessionRepository.findAllByOrderByStartTimeDesc();

      List<Map<String, Object>> response =
          sessions.stream()
              .map(
                  session -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", session.getId());
                    map.put("recordingId", session.getRecordingId());
                    map.put("accountName", session.getAccountName());
                    map.put("scriptName", session.getScriptName());
                    map.put("startTime", session.getStartTime());
                    map.put("endTime", session.getEndTime());
                    map.put("triggerType", session.getTriggerType());
                    return map;
                  })
              .toList();

      logger.info("Returned {} recording sessions", response.size());
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      logger.error("Failed to list recording sessions: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets a specific recording by recording ID.
   *
   * @param recordingId The recording ID
   * @return Response with recording details
   */
  @GetMapping("/{recordingId}")
  public ResponseEntity<Map<String, Object>> getRecording(@PathVariable String recordingId) {
    try {
      return recordingSessionRepository
          .findByRecordingId(recordingId)
          .map(
              session -> {
                Map<String, Object> response = new HashMap<>();
                response.put("id", session.getId());
                response.put("recordingId", session.getRecordingId());
                response.put("accountName", session.getAccountName());
                response.put("scriptName", session.getScriptName());
                response.put("startTime", session.getStartTime());
                response.put("endTime", session.getEndTime());
                response.put("triggerType", session.getTriggerType());
                response.put("videoPath", session.getVideoPath());

                // Add video URL if path exists
                if (session.getVideoPath() != null) {
                  File videoFile = new File(session.getVideoPath());
                  if (videoFile.exists()) {
                    String filename = videoFile.getName();
                    response.put("videoUrl", "/api/recordings/download/" + filename);
                  }
                }

                return ResponseEntity.ok(response);
              })
          .orElse(ResponseEntity.notFound().build());
    } catch (Exception e) {
      logger.error("Failed to get recording: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Downloads a specific recording file.
   *
   * @param filename The recording filename
   * @return The video file
   */
  @GetMapping("/download/{filename:.+}")
  public ResponseEntity<Resource> downloadRecording(String filename) {
    try {
      // Security check: ensure filename doesn't contain path traversal
      if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
        return ResponseEntity.badRequest().build();
      }

      Path videoPath = Paths.get("recordings", filename).toAbsolutePath();

      // Ensure file exists and is an MP4
      if (!Files.exists(videoPath) || !filename.endsWith(".mp4")) {
        return ResponseEntity.notFound().build();
      }

      File file = videoPath.toFile();
      Resource resource = new FileSystemResource(file);

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
          .contentType(MediaType.parseMediaType("video/mp4"))
          .body(resource);

    } catch (Exception e) {
      logger.error("Failed to download recording: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
