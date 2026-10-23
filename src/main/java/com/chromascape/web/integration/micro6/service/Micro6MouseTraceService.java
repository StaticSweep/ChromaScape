package com.chromascape.web.integration.micro6.service;

import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.service.RecordingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Stores micro6 "Bot Mouse" trace batches next to the ChromaScape recording they were captured
 * during, as {@code <recordingId>.mouse-trace.jsonl} (SC-2150 FA12 natively labelled capture).
 *
 * <p>The recording is the one active when the batch arrives, or the one that stopped at most {@link
 * #STOP_GRACE_NANOS} earlier, so frames still in flight at stop are kept. Batches outside any
 * recording are acknowledged and discarded. Every stored line is rebuilt from a fixed set of
 * numeric/boolean fields, never copied from the request, and the file is capped at {@link
 * #MAX_FILE_BYTES}. Alignment to video frames happens later from capture clocks ({@code
 * scripts/sc2150/label_from_mouse_trace.py}); this service only persists.
 */
@Service
public class Micro6MouseTraceService {
  static final String SCHEMA = "micro6-mouse-trace/v1";
  static final int MAX_FRAMES = 5_000;
  static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
  static final long STOP_GRACE_NANOS = 5_000_000_000L;

  private static final Logger logger = LogManager.getLogger(Micro6MouseTraceService.class);
  private static final Pattern RECORDING_ID = Pattern.compile("[A-Za-z0-9_-]{1,96}");
  private static final List<String> NUMBERS = List.of("seq", "epochMs", "nanos", "x", "y", "trail");
  private static final List<String> PAIRS = List.of("canvas", "stretchedSize", "human");
  private static final List<String> HEADER_NUMBERS = List.of("pid", "startEpochMs", "startNanos");

  private final RecordingService recordingService;
  private final RecordingConfig recordingConfig;
  private final ObjectMapper mapper = new ObjectMapper();
  private final LongSupplier clock;
  private final Set<String> headersWritten = new HashSet<>();
  private String lastRecordingId;
  private long lastSeenNanos;

  /** Creates the service on the system monotonic clock. */
  @Autowired
  public Micro6MouseTraceService(
      RecordingService recordingService, RecordingConfig recordingConfig) {
    this(recordingService, recordingConfig, System::nanoTime);
  }

  Micro6MouseTraceService(
      RecordingService recordingService, RecordingConfig recordingConfig, LongSupplier clock) {
    this.recordingService = recordingService;
    this.recordingConfig = recordingConfig;
    this.clock = clock;
  }

  /** Outcome of one batch. */
  public record Result(String recordingId, int stored, int rejected, boolean capped) {}

  /**
   * Validates and stores one batch {@code {"header": {...}, "frames": [...]}}.
   *
   * @throws IllegalArgumentException when the batch is malformed or too large
   */
  public synchronized Result accept(JsonNode batch) throws IOException {
    JsonNode header = batch == null ? null : batch.get("header");
    JsonNode frames = batch == null ? null : batch.get("frames");
    if (header == null || !SCHEMA.equals(header.path("schema").asText(null))) {
      throw new IllegalArgumentException("header.schema must be " + SCHEMA);
    }
    if (frames == null || !frames.isArray() || frames.size() > MAX_FRAMES) {
      throw new IllegalArgumentException("frames must be an array of at most " + MAX_FRAMES);
    }
    for (String field : HEADER_NUMBERS) {
      if (!header.path(field).isIntegralNumber()) {
        throw new IllegalArgumentException("header." + field + " must be an integer");
      }
    }
    String recordingId = targetRecording();
    if (recordingId == null) {
      return new Result(null, 0, frames.size(), false);
    }
    Path file =
        Paths.get(recordingConfig.getOutputDirectory()).resolve(recordingId + ".mouse-trace.jsonl");
    StringBuilder lines = new StringBuilder();
    String headerKey =
        recordingId + "/" + header.path("pid").asLong() + "/" + header.path("startNanos").asLong();
    if (!headersWritten.contains(headerKey)) {
      ObjectNode clean = mapper.createObjectNode();
      clean.put("schema", SCHEMA);
      clean.put("kind", "header");
      for (String field : HEADER_NUMBERS) {
        clean.put(field, header.path(field).asLong());
      }
      clean.put("recordingId", recordingId);
      lines.append(mapper.writeValueAsString(clean)).append('\n');
    }
    int stored = 0;
    int rejected = 0;
    for (JsonNode frame : frames) {
      ObjectNode clean = sanitize(frame);
      if (clean == null) {
        rejected++;
        continue;
      }
      lines.append(mapper.writeValueAsString(clean)).append('\n');
      stored++;
    }
    byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
    long existing = Files.exists(file) ? Files.size(file) : 0L;
    if (existing + bytes.length > MAX_FILE_BYTES) {
      logger.warn("Mouse trace for {} reached {} bytes; dropping batch", recordingId, existing);
      return new Result(recordingId, 0, frames.size(), true);
    }
    Files.createDirectories(file.getParent());
    Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    headersWritten.add(headerKey);
    return new Result(recordingId, stored, rejected, false);
  }

  private String targetRecording() {
    long now = clock.getAsLong();
    String active =
        recordingService.isRecording() ? recordingService.getCurrentRecordingId() : null;
    if (active != null && RECORDING_ID.matcher(active).matches()) {
      lastRecordingId = active;
      lastSeenNanos = now;
      return active;
    }
    if (lastRecordingId != null && now - lastSeenNanos <= STOP_GRACE_NANOS) {
      return lastRecordingId;
    }
    return null;
  }

  private ObjectNode sanitize(JsonNode frame) {
    if (frame == null || !frame.isObject()) {
      return null;
    }
    ObjectNode clean = mapper.createObjectNode();
    for (String field : NUMBERS) {
      if (!frame.path(field).isIntegralNumber()) {
        return null;
      }
      clean.put(field, frame.path(field).asLong());
    }
    for (String field : List.of("visible", "stretched")) {
      if (!frame.path(field).isBoolean()) {
        return null;
      }
      clean.put(field, frame.path(field).asBoolean());
    }
    for (String field : PAIRS) {
      JsonNode pair = frame.path(field);
      if (!pair.isArray()
          || pair.size() != 2
          || !pair.get(0).isIntegralNumber()
          || !pair.get(1).isIntegralNumber()) {
        return null;
      }
      clean.putArray(field).add(pair.get(0).asLong()).add(pair.get(1).asLong());
    }
    return clean;
  }
}
