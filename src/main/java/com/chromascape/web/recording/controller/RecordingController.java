package com.chromascape.web.recording.controller;

import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for game recording and replay functionality.
 *
 * <p>Provides endpoints to start/stop recordings, list available recordings, and download video
 * files.
 */
@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

  private static final Logger logger = LogManager.getLogger(RecordingController.class);

  private final RecordingService recordingService;

  @Autowired
  public RecordingController(RecordingService recordingService) {
    this.recordingService = recordingService;
  }

  /**
   * Starts a new recording session.
   *
   * @return Response with recording ID if successful
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> startRecording() {
    String recordingId = recordingService.startRecording();

    if (recordingId != null) {
      Map<String, Object> response = new HashMap<>();
      response.put("status", "recording");
      response.put("recordingId", recordingId);
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
