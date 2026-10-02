package com.chromascape.web.recording.controller;

import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.dto.RecordingSessionMetadataResponse;
import com.chromascape.web.recording.dto.RecordingSessionMetadataUpdateRequest;
import com.chromascape.web.recording.dto.RecordingStartRequest;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.recording.exception.RecordingAlreadyInProgressException;
import com.chromascape.web.recording.exception.RecordingDisabledException;
import com.chromascape.web.recording.exception.RecordingException;
import com.chromascape.web.recording.provenance.ProvenanceException;
import com.chromascape.web.recording.provenance.RecordingProvenance;
import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import com.chromascape.web.recording.service.RecordingSessionMetadataService;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
  private final RecordingSessionMetadataService metadataService;
  private final DebugSessionService debugSessionService;

  @Autowired
  public RecordingController(
      RecordingService recordingService,
      RecordingSessionRepository recordingSessionRepository,
      WindowSelectionService windowSelectionService,
      RecordingConfig recordingConfig,
      RecordingSessionMetadataService metadataService,
      DebugSessionService debugSessionService) {
    this.recordingService = recordingService;
    this.recordingSessionRepository = recordingSessionRepository;
    this.windowSelectionService = windowSelectionService;
    this.recordingConfig = recordingConfig;
    this.metadataService = metadataService;
    this.debugSessionService = debugSessionService;
  }

  /**
   * Starts a new recording session.
   *
   * @return Response with recording ID if successful
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> startRecording(
      @RequestBody RecordingStartRequest request) {
    String accountName = request.accountName();
    String scriptName = request.scriptName();
    String windowTitle = request.windowTitle();

    // Get account from window selection if not provided
    if (accountName == null || accountName.isEmpty()) {
      WindowInfo window = windowSelectionService.getSelectedWindow();
      accountName = window != null ? window.getAccountName() : null;
    }

    try {
      RecordingProvenance provenance =
          RecordingProvenance.capture(request.taskKey(), request.taskArgs());
      if (request.runContext() != null && !request.runContext().isNull()) {
        var response =
            debugSessionService
                .startSessionWithOutcome(
                    accountName,
                    scriptName,
                    true,
                    RecordingTriggerSource.MANUAL,
                    null,
                    request.taskKey(),
                    request.taskArgs(),
                    request.runContext())
                .response();
        Map<String, Object> result = new HashMap<>();
        result.put("recordingId", response.getRecordingId());
        result.put("sessionId", response.getSessionId());
        result.put("status", response.getRecordingId() == null ? "tick-only" : "recording");
        result.put("account", accountName);
        result.put("runContext", response.getRunContext());
        result.put("recordingFailureReason", response.getRecordingFailureReason());
        return ResponseEntity.ok(result);
      }
      String recordingId = recordingService.startRecording(RecordingTriggerSource.MANUAL);
      LocalDateTime preciseStartTime = recordingService.getRecordingStartTimeAsLocalDateTime();

      if (preciseStartTime == null) {
        throw new RecordingException(
            "Failed to get recording start time",
            "Recording started but could not retrieve start time. Please try again.");
      }

      // Create RecordingSession entity with precise start time
      RecordingSession session =
          new RecordingSession(
              recordingId,
              accountName,
              scriptName,
              preciseStartTime,
              RecordingSession.SessionTrigger.MANUAL,
              null); // videoPath will be set on stop

      session.captureProvenance(provenance);
      recordingSessionRepository.save(session);
      logger.info("Created recording session: {} for account: {}", recordingId, accountName);

      Map<String, Object> response = new HashMap<>();
      response.put("status", "recording");
      response.put("recordingId", recordingId);
      response.put("account", accountName);
      response.put("message", "Recording started");
      return ResponseEntity.ok(response);

    } catch (ProvenanceException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("errorType", e.getCode(), "message", "Invalid recording provenance"));
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
      session.setVideoCodec(metadata.getVideoCodec());

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
    RecordingTriggerSource triggerSource = recordingService.getTriggerSource();
    Map<String, Object> response = new HashMap<>();
    response.put("recording", recordingService.isRecording());
    response.put("recordingId", recordingService.getCurrentRecordingId());
    response.put("startTime", recordingService.getRecordingStartTime());
    response.put("triggerSource", triggerSource != null ? triggerSource.name() : null);
    return ResponseEntity.ok(response);
  }

  /**
   * Get recording metrics for monitoring frame drops and encoder lag.
   *
   * <p>Returns current buffer depth, dropped frames, and recording duration.
   *
   * @return Response with recording metrics
   */
  @GetMapping("/metrics")
  public ResponseEntity<Map<String, Object>> getMetrics() {
    try {
      Map<String, Object> metrics = recordingService.getMetrics();
      return ResponseEntity.ok(metrics);
    } catch (Exception e) {
      logger.error("Error getting recording metrics", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Force reset the recording state.
   *
   * <p>This is a recovery endpoint to reset stuck recording state. Use with caution as it may leave
   * incomplete recordings.
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
                    String recordingId = file.getName().replace(".mp4", "");
                    info.put("id", recordingId);
                    info.put("filename", file.getName());
                    info.put("size", file.length());
                    info.put("lastModified", file.lastModified());
                    info.put("url", "/api/recordings/download/" + file.getName());

                    // Include database metadata if available
                    RecordingSession session =
                        recordingSessionRepository.findByRecordingId(recordingId).orElse(null);
                    if (session != null) {
                      if (session.getDurationMs() != null) {
                        info.put("durationMs", session.getDurationMs());
                      }
                      if (session.getFps() != null) {
                        info.put("fps", session.getFps());
                      }
                    }

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

      List<Map<String, Object>> response = sessions.stream().map(this::catalogEntry).toList();

      logger.info("Returned {} recording sessions", response.size());
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      logger.error("Failed to list recording sessions: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  @Autowired private com.chromascape.web.recording.service.RecordingCatalogPager catalogPager;

  /** Bounded versioned route; the original array route remains source/wire compatible. */
  @GetMapping("/v1/sessions")
  public ResponseEntity<Map<String, Object>> catalogPage(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String accountName,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String taskKey,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
    var page = catalogPager.page(accountName, taskKey, limit, cursor);
    Map<String, Object> response = new HashMap<>();
    response.put("entries", page.entries().stream().map(this::catalogEntry).toList());
    response.put("nextCursor", page.nextCursor());
    response.put("hasMore", page.hasMore());
    return ResponseEntity.ok(response);
  }

  private Map<String, Object> catalogEntry(RecordingSession session) {

    Map<String, Object> map = new HashMap<>();
    map.put("id", session.getId());
    map.put("recordingId", session.getRecordingId());
    map.put("accountName", session.getAccountName());
    map.put("scriptName", session.getScriptName());
    map.put("taskKey", session.getTaskKey());
    map.put("taskArgs", session.getTaskArgs());
    map.put("settingsHash", session.getSettingsHash());
    map.put("startTime", session.getStartTime());
    map.put("endTime", session.getEndTime());
    map.put("triggerType", session.getTriggerType());
    map.put("videoCodec", session.getVideoCodec());
    map.put("replayName", session.getReplayName());
    map.put("replayTags", metadataService.parseTags(session.getReplayTagsJson()));
    map.put(
        "displayName",
        metadataService.computeDisplayName(
            session.getReplayName(),
            session.getAccountName(),
            session.getScriptName(),
            metadataService.formatStartTime(session.getStartTime())));

    // Playability flags for the replay selector
    boolean hasVideoPath = session.getVideoPath() != null && !session.getVideoPath().isBlank();
    boolean hasConfiguredVideoFile = sessionVideoFileExists(session);
    boolean hasOrphanVideoFile = videoFileExists(session.getRecordingId());
    boolean finalized = session.getEndTime() != null;
    String status;
    boolean playable;
    if (!finalized) {
      status = "RECORDING";
      playable = false;
    } else if (!hasVideoPath && hasOrphanVideoFile) {
      status = "UNFINALIZED";
      playable = false;
    } else if (!hasVideoPath || !hasConfiguredVideoFile) {
      status = "MISSING_VIDEO";
      playable = false;
    } else {
      status = "FINALIZED";
      playable = true;
    }
    map.put("playable", playable);
    map.put("status", status);
    map.put("videoAvailable", playable);
    map.put(
        "videoSizeBytes",
        hasConfiguredVideoFile
            ? sessionVideoFileSize(session)
            : (hasOrphanVideoFile ? videoFileSize(session.getRecordingId()) : null));
    if (!playable) {
      if (!finalized) {
        map.put("videoError", "Recording is still in progress");
      } else if (!hasVideoPath && hasOrphanVideoFile) {
        map.put("videoError", "Recording was not finalized properly - video path not set");
      } else {
        map.put("videoError", "Video file not found on disk");
      }
    }

    return map;
  }

  /**
   * Gets a specific recording by recording ID.
   *
   * <p>If the recording has no video path but the .mp4 file exists on disk, the metadata will be
   * backfilled automatically before the response is returned.
   *
   * @param recordingId The recording ID
   * @return Response with recording details
   */
  @GetMapping("/{recordingId}")
  public ResponseEntity<Map<String, Object>> getRecording(@PathVariable String recordingId) {
    try {
      // Attempt orphan repair if the video path is missing but the .mp4 exists, then re-read
      final boolean repaired;
      if (videoFileExists(recordingId)) {
        repaired = debugSessionService.repairOrphanedRecordingSession(recordingId);
      } else {
        repaired = false;
      }

      return recordingSessionRepository
          .findByRecordingId(recordingId)
          .map(
              session -> {
                Map<String, Object> response = buildRecordingResponse(session);

                if (repaired) {
                  response.put("repaired", true);
                }

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
   * Updates replay metadata (name and/or tags) for a recording session.
   *
   * @param recordingId the recording ID
   * @param request the metadata update request
   * @return updated metadata response
   */
  @PatchMapping("/sessions/{recordingId}/metadata")
  public ResponseEntity<?> updateMetadata(
      @PathVariable String recordingId,
      @RequestBody RecordingSessionMetadataUpdateRequest request) {
    try {
      RecordingSession session =
          recordingSessionRepository.findByRecordingId(recordingId).orElse(null);

      if (session == null) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Recording not found: " + recordingId));
      }

      // Apply replayName if provided
      if (request.isReplayNameSet()) {
        String name = request.getReplayName();
        if (name != null && name.isBlank()) {
          name = null;
        }
        if (name != null) {
          metadataService.validateReplayName(name);
        }
        session.setReplayName(name);
      }

      // Apply replayTags if provided
      if (request.isReplayTagsSet()) {
        List<String> normalized = metadataService.normalizeTags(request.getReplayTags());
        metadataService.validateTags(normalized);
        session.setReplayTagsJson(metadataService.serializeTags(normalized));
      }

      recordingSessionRepository.save(session);

      List<String> tags = metadataService.parseTags(session.getReplayTagsJson());
      String displayName =
          metadataService.computeDisplayName(
              session.getReplayName(),
              session.getAccountName(),
              session.getScriptName(),
              metadataService.formatStartTime(session.getStartTime()));

      return ResponseEntity.ok(
          new RecordingSessionMetadataResponse(
              session.getRecordingId(),
              session.getReplayName(),
              tags,
              displayName,
              LocalDateTime.now()));

    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
      logger.error("Failed to update metadata for {}: {}", recordingId, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("error", "Unexpected error"));
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

  /** Returns the absolute path to the .mp4 file for a recording ID, if it exists. */
  private Path videoFilePath(String recordingId) {
    if (recordingId == null || recordingId.isBlank()) {
      return null;
    }
    return Paths.get(recordingConfig.getOutputDirectory(), recordingId + ".mp4").toAbsolutePath();
  }

  /** Returns true if the .mp4 file exists on disk for the given recording ID. */
  private boolean videoFileExists(String recordingId) {
    Path path = videoFilePath(recordingId);
    return path != null && Files.exists(path);
  }

  /** Returns the size in bytes of the .mp4 file for the given recording ID, or null if missing. */
  private Long videoFileSize(String recordingId) {
    Path path = videoFilePath(recordingId);
    if (path == null || !Files.exists(path)) {
      return null;
    }
    try {
      return Files.size(path);
    } catch (Exception e) {
      logger.warn("Failed to read video file size for {}: {}", recordingId, e.getMessage());
      return null;
    }
  }

  private boolean sessionVideoFileExists(RecordingSession session) {
    if (session.getVideoPath() == null || session.getVideoPath().isBlank()) {
      return false;
    }
    return Files.exists(Paths.get(session.getVideoPath()));
  }

  private Long sessionVideoFileSize(RecordingSession session) {
    if (session.getVideoPath() == null || session.getVideoPath().isBlank()) {
      return null;
    }
    try {
      Path path = Paths.get(session.getVideoPath());
      return Files.exists(path) ? Files.size(path) : null;
    } catch (Exception e) {
      logger.warn(
          "Failed to read configured video file size for {}: {}",
          session.getRecordingId(),
          e.getMessage());
      return null;
    }
  }

  /** Builds a common response map from a {@link RecordingSession}. */
  private Map<String, Object> buildRecordingResponse(RecordingSession session) {
    Map<String, Object> response = new HashMap<>();
    response.put("id", session.getId());
    response.put("recordingId", session.getRecordingId());
    response.put("accountName", session.getAccountName());
    response.put("scriptName", session.getScriptName());
    response.put("startTime", session.getStartTime());
    response.put("endTime", session.getEndTime());
    response.put("triggerType", session.getTriggerType());
    response.put("replayName", session.getReplayName());
    response.put("replayTags", metadataService.parseTags(session.getReplayTagsJson()));
    response.put(
        "displayName",
        metadataService.computeDisplayName(
            session.getReplayName(),
            session.getAccountName(),
            session.getScriptName(),
            metadataService.formatStartTime(session.getStartTime())));

    // Playability flags
    boolean hasVideoPath = session.getVideoPath() != null && !session.getVideoPath().isBlank();
    boolean hasConfiguredVideoFile = sessionVideoFileExists(session);
    boolean hasOrphanVideoFile = videoFileExists(session.getRecordingId());
    boolean finalized = session.getEndTime() != null;
    String status;
    boolean playable;
    if (!finalized) {
      status = "RECORDING";
      playable = false;
    } else if (!hasVideoPath && hasOrphanVideoFile) {
      status = "UNFINALIZED";
      playable = false;
    } else if (!hasVideoPath || !hasConfiguredVideoFile) {
      status = "MISSING_VIDEO";
      playable = false;
    } else {
      status = "FINALIZED";
      playable = true;
    }
    response.put("playable", playable);
    response.put("status", status);
    response.put("videoAvailable", playable);
    response.put(
        "videoSizeBytes",
        hasConfiguredVideoFile
            ? sessionVideoFileSize(session)
            : (hasOrphanVideoFile ? videoFileSize(session.getRecordingId()) : null));
    if (!playable) {
      if (!finalized) {
        response.put("videoError", "Recording is still in progress");
      } else if (!hasVideoPath && hasOrphanVideoFile) {
        response.put("videoError", "Recording was not finalized properly - video path not set");
      } else {
        response.put("videoError", "Video file not found on disk");
      }
    }

    return response;
  }
}
