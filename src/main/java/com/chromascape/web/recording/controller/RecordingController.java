package com.chromascape.web.recording.controller;

import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.exception.RecordingAlreadyInProgressException;
import com.chromascape.web.recording.exception.RecordingDisabledException;
import com.chromascape.web.recording.exception.RecordingException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
  private final RecordingConfig recordingConfig;

  @Autowired
  public RecordingController(
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      WindowSelectionService windowSelectionService,
      RecordingConfig recordingConfig) {
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.windowSelectionService = windowSelectionService;
    this.recordingConfig = recordingConfig;
  }

  /**
   * Starts a new recording session.
   *
   * @return Response with recording ID if successful
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> startRecording(
      @RequestBody Map<String, String> request) {
    String accountName = request.get("accountName");
    String scriptName = request.get("scriptName");
    String windowTitle = request.get("windowTitle");

    // Get account from window selection if not provided
    if (accountName == null || accountName.isEmpty()) {
      WindowInfo window = windowSelectionService.getSelectedWindow();
      accountName = window != null ? window.getAccountName() : "default";
    }

    try {
      String recordingId = recordingService.startRecording();

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

    } catch (RecordingAlreadyInProgressException e) {
      logger.warn("Attempt to start recording while already in progress");
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("errorType", "ALREADY_IN_PROGRESS");
      response.put("message", e.getUserMessage());
      response.put("currentRecordingId", recordingService.getCurrentRecordingId());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

    } catch (RecordingDisabledException e) {
      logger.warn("Attempt to start recording while disabled");
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("errorType", "DISABLED");
      response.put("message", e.getUserMessage());
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);

    } catch (FrameCaptureException e) {
      logger.warn("Failed to capture frame for recording");
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("errorType", "CAPTURE_FAILED");
      response.put("message", e.getUserMessage());
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);

    } catch (RecordingException e) {
      logger.error("Recording error: {}", e.getMessage(), e);
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("errorType", "RECORDING_ERROR");
      response.put("message", e.getUserMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);

    } catch (Exception e) {
      logger.error("Unexpected error starting recording: {}", e.getMessage(), e);
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("errorType", "UNEXPECTED");
      response.put("message", "An unexpected error occurred. Please try again.");
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
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

    if (metadata == null) {
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("message", "No recording in progress");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    try {
      // Find the recording session - throw if not found
      RecordingSession session =
          recordingSessionRepository
              .findByRecordingId(metadata.getId())
              .orElseThrow(
                  () -> {
                    logger.error("Recording session not found in database: {}", metadata.getId());
                    return new IllegalStateException(
                        "Recording session not found: " + metadata.getId());
                  });

      // Validate video file exists and is not empty
      File videoFile = new File(metadata.getFilePath());
      if (!videoFile.exists()) {
        logger.error("Video file does not exist: {}", metadata.getFilePath());
        throw new IllegalStateException("Video file was not created: " + metadata.getFilePath());
      }
      if (videoFile.length() == 0) {
        logger.error("Video file is empty: {}", metadata.getFilePath());
        throw new IllegalStateException("Video file is empty: " + metadata.getFilePath());
      }

      // Update session with metadata
      session.setEndTime(LocalDateTime.now());
      session.setVideoPath(metadata.getFilePath());
      session.setDurationMs(metadata.getDuration());
      session.setFrameCount(metadata.getFrameCount());
      session.setFps(metadata.getFps());

      // Save to database
      RecordingSession saved = recordingSessionRepository.save(session);

      // Validate save was successful
      if (saved.getVideoPath() == null) {
        logger.error("Failed to persist videoPath for recording: {}", metadata.getId());
        throw new IllegalStateException("Database save failed to persist video path");
      }

      logger.info(
          "Stopped recording: {} - Video: {}, Duration: {}ms, Frames: {}",
          metadata.getId(),
          metadata.getFilePath(),
          metadata.getDuration(),
          metadata.getFrameCount());

      // Return success response
      Map<String, Object> response = new HashMap<>();
      response.put("status", "stopped");
      response.put("recordingId", metadata.getId());
      response.put("duration", metadata.getDuration());
      response.put("frameCount", metadata.getFrameCount());
      response.put("filePath", metadata.getFilePath());
      response.put("message", "Recording saved successfully");
      return ResponseEntity.ok(response);

    } catch (IllegalStateException e) {
      logger.error("Failed to finalize recording {}: {}", metadata.getId(), e.getMessage());
      Map<String, Object> errorResponse = new HashMap<>();
      errorResponse.put("status", "error");
      errorResponse.put("message", "Failed to save recording: " + e.getMessage());
      errorResponse.put("recordingId", metadata.getId());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    } catch (Exception e) {
      logger.error("Unexpected error stopping recording: {}", e.getMessage(), e);
      Map<String, Object> errorResponse = new HashMap<>();
      errorResponse.put("status", "error");
      errorResponse.put("message", "Unexpected error: " + e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
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
    response.put("startTime", recordingService.getRecordingStartTime());
    return ResponseEntity.ok(response);
  }

  /**
   * Force reset the recording state.
   *
   * <p>This is a recovery endpoint to reset stuck recording state. Use with caution as it may
   * leave incomplete recordings.
   *
   * @return Response indicating whether state was reset
   */
  @PostMapping("/reset")
  public ResponseEntity<Map<String, Object>> resetRecordingState() {
    logger.warn("Force reset recording state requested");

    boolean wasRecording = recordingService.forceResetRecordingState();

    Map<String, Object> response = new HashMap<>();
    response.put("reset", wasRecording);
    response.put(
        "message",
        wasRecording
            ? "Recording state has been reset. Previous recording may be incomplete."
            : "No recording was in progress.");

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
      Path recordingsDir = Paths.get(recordingConfig.getOutputDirectory());
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

                // Validate and add video URL
                if (session.getVideoPath() != null) {
                  File videoFile = new File(session.getVideoPath());
                  if (videoFile.exists()) {
                    String filename = videoFile.getName();
                    response.put("videoUrl", "/api/recordings/download/" + filename);
                    logger.debug("Generated video URL for {}: {}", recordingId, filename);
                  } else {
                    logger.warn(
                        "Video file missing for recording {}: {}",
                        recordingId,
                        session.getVideoPath());
                    response.put("videoError", "Video file not found on disk");
                  }
                } else {
                  logger.warn("Video path is null for recording: {}", recordingId);
                  response.put(
                      "videoError", "Recording was not finalized properly - video path not set");
                }

                return ResponseEntity.ok(response);
              })
          .orElseGet(
              () -> {
                logger.warn("Recording session not found: {}", recordingId);
                return ResponseEntity.notFound().build();
              });
    } catch (Exception e) {
      logger.error("Failed to get recording {}: {}", recordingId, e.getMessage(), e);
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
  public ResponseEntity<Resource> downloadRecording(@PathVariable String filename) {
    try {
      // Security check: ensure filename doesn't contain path traversal
      if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
        logger.warn("Path traversal attempt blocked: {}", filename);
        return ResponseEntity.badRequest().build();
      }

      Path videoPath = Paths.get(recordingConfig.getOutputDirectory(), filename).toAbsolutePath();

      // Ensure file exists and is an MP4
      if (!Files.exists(videoPath) || !filename.endsWith(".mp4")) {
        logger.warn("Recording file not found: {}", filename);
        return ResponseEntity.notFound().build();
      }

      File file = videoPath.toFile();
      Resource resource = new FileSystemResource(file);

      logger.debug("Serving recording: {} ({} bytes)", filename, file.length());

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
          .contentType(MediaType.parseMediaType("video/mp4"))
          .body(resource);

    } catch (Exception e) {
      logger.error("Failed to download recording {}: {}", filename, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
