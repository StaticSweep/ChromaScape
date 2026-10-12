package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.MeasuredVideoIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for tick-to-video frame mapping.
 *
 * <p>Calculates the relationship between game ticks and video frames for synchronized replay. Uses
 * the standard RuneScape tick rate of 600ms per tick.
 */
@Service
public class TickVideoSyncService {

  private static final Logger log = LoggerFactory.getLogger(TickVideoSyncService.class);

  /** Standard RuneScape tick duration in milliseconds. */
  private static final double TICK_DURATION_MS = 600.0;

  private final DebugSessionRepository sessionRepository;
  private final RecordingSessionRepository recordingSessionRepository;

  private final TickSnapshotRepository snapshots;

  @org.springframework.beans.factory.annotation.Autowired
  public TickVideoSyncService(
      DebugSessionRepository sessions,
      RecordingSessionRepository recordings,
      TickSnapshotRepository snapshots) {
    this.sessionRepository = sessions;
    this.recordingSessionRepository = recordings;
    this.snapshots = snapshots;
  }

  public MeasuredVideoIndex.Mapping measuredMapping(String sessionId, long tick) {
    try {
      DebugSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (session == null || session.getRecordingId() == null || snapshots == null) {
        return MeasuredVideoIndex.unavailable("CAPTURE_CALIBRATION_MISSING");
      }
      var snapshot = snapshots.findBySessionIdAndTick(sessionId, tick).orElse(null);
      var q = snapshot == null ? null : snapshot.getCaptureQuality();
      if (q == null) {
        return MeasuredVideoIndex.unavailable("CAPTURE_CALIBRATION_MISSING");
      }
      RecordingSession recording =
          recordingSessionRepository.findByRecordingId(session.getRecordingId()).orElse(null);
      if (recording == null || recording.getVideoPath() == null) {
        return MeasuredVideoIndex.unavailable("VIDEO_INDEX_MISSING");
      }
      var index =
          MeasuredVideoIndex.read(
              java.nio.file.Path.of(recording.getVideoPath()), recording.getRecordingId());
      return measuredMapping(q, sessionId, tick, index);
    } catch (Exception invalid) {
      return MeasuredVideoIndex.unavailable("VIDEO_INDEX_OR_CAPTURE_INVALID");
    }
  }

  private MeasuredVideoIndex.Mapping measuredMapping(
      JsonNode q, String session, long tick, MeasuredVideoIndex.Index index) {
    try {
      if (q == null) {
        return MeasuredVideoIndex.unavailable("CAPTURE_CALIBRATION_MISSING");
      }
      CaptureQualityContract.validate(q, session, tick, new ObjectMapper());
      if (!q.path("qualityCodes").isEmpty()
          || !"MEASURED".equals(q.path("clock").path("status").asText())) {
        return MeasuredVideoIndex.unavailable("CAPTURE_QUALITY_UNAVAILABLE");
      }
      return MeasuredVideoIndex.map(
          index,
          q.path("tickCapturedAtEpochMillis").doubleValue()
              + q.path("clock").path("serverOffsetMillis").doubleValue(),
          q.path("clock").path("uncertaintyMillis").doubleValue());
    } catch (Exception invalid) {
      return MeasuredVideoIndex.unavailable("CAPTURE_QUALITY_INVALID");
    }
  }

  public java.util.Map<Long, MeasuredVideoIndex.Mapping> measurements(String sessionId) {
    var results = new java.util.LinkedHashMap<Long, MeasuredVideoIndex.Mapping>();
    if (snapshots == null) {
      return results;
    }
    MeasuredVideoIndex.Index index = null;
    try {
      var session = sessionRepository.findBySessionId(sessionId).orElse(null);
      var recording =
          session == null || session.getRecordingId() == null
              ? null
              : recordingSessionRepository.findByRecordingId(session.getRecordingId()).orElse(null);
      if (recording != null && recording.getVideoPath() != null) {
        index =
            MeasuredVideoIndex.read(
                java.nio.file.Path.of(recording.getVideoPath()), recording.getRecordingId());
      }
    } catch (Exception invalid) {
      /* Missing, partial or corrupt media remains explicitly unavailable. */
    }
    for (var snapshot : snapshots.findBySessionIdOrderByTick(sessionId)) {
      results.put(
          snapshot.getTick(),
          measuredMapping(snapshot.getCaptureQuality(), sessionId, snapshot.getTick(), index));
    }
    return results;
  }

  private boolean hasCaptureQuality(String sessionId, long tick) {
    return snapshots != null
        && snapshots
            .findBySessionIdAndTick(sessionId, tick)
            .map(s -> s.getCaptureQuality() != null)
            .orElse(false);
  }

  public com.chromascape.web.aiplayer.combat.CombatTickRowService.VideoSync combatVideoSync(
      String sessionId, long tick) {
    var session = sessionRepository.findBySessionId(sessionId).orElse(null);
    var recording =
        session == null || session.getRecordingId() == null
            ? null
            : recordingSessionRepository.findByRecordingId(session.getRecordingId()).orElse(null);
    return new com.chromascape.web.aiplayer.combat.CombatTickRowService.VideoSync(
        session == null || session.getStartTick() == null ? 0 : session.getStartTick(),
        recording == null ? null : recording.getFps(),
        measuredMapping(sessionId, tick),
        measuredMapping(sessionId, tick + 1));
  }

  public TickVideoSyncService(
      DebugSessionRepository sessionRepository,
      RecordingSessionRepository recordingSessionRepository) {
    this.sessionRepository = sessionRepository;
    this.recordingSessionRepository = recordingSessionRepository;
    this.snapshots = null;
  }

  /**
   * Get the video frame number for a given tick.
   *
   * <p>Formula: frame = (tick - startTick) * (fps * 0.6)
   *
   * @param sessionId the debug session ID
   * @param tick the tick number
   * @return the video frame number, or null if no video is available
   */
  public Integer getFrameForTick(String sessionId, long tick) {
    if (hasCaptureQuality(sessionId, tick)) {
      var measured = measuredMapping(sessionId, tick);
      return "MEASURED".equals(measured.status()) ? measured.frameIndex() : null;
    }
    try {
      DebugSession debugSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (debugSession == null || debugSession.getRecordingId() == null) {
        return null;
      }

      RecordingSession recording =
          recordingSessionRepository.findByRecordingId(debugSession.getRecordingId()).orElse(null);

      if (recording == null || recording.getFps() == null) {
        return null;
      }

      int fps = recording.getFps();
      long startTick = debugSession.getStartTick() != null ? debugSession.getStartTick() : 0;

      // frame = (tick - startTick) * fps * 0.6
      long frame = Math.round((tick - startTick) * fps * 0.6);

      return (int) Math.max(0, frame);

    } catch (Exception e) {
      log.error("Error calculating frame for tick {}: {}", tick, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Get the tick number for a given video frame.
   *
   * <p>Formula: tick = startTick + (frame / (fps * 0.6))
   *
   * @param sessionId the debug session ID
   * @param frame the video frame number
   * @return the tick number, or null if calculation fails
   */
  public Long getTickForFrame(String sessionId, int frame) {
    Long measured = measuredTickForMedia(sessionId, frame, null);
    if (measured != null || hasAnyCaptureQuality(sessionId)) {
      return measured;
    }
    try {
      DebugSession debugSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (debugSession == null || debugSession.getRecordingId() == null) {
        return null;
      }

      RecordingSession recording =
          recordingSessionRepository.findByRecordingId(debugSession.getRecordingId()).orElse(null);

      if (recording == null || recording.getFps() == null) {
        return null;
      }

      int fps = recording.getFps();
      long startTick = debugSession.getStartTick() != null ? debugSession.getStartTick() : 0;

      // tick = startTick + (frame / (fps * 0.6))
      long tick = Math.round(startTick + (frame / (fps * 0.6)));

      return Math.max(startTick, tick);

    } catch (Exception e) {
      log.error("Error calculating tick for frame {}: {}", frame, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Get the video time in milliseconds for a given tick.
   *
   * <p>Formula: timeMs = (tick - startTick) * 600
   *
   * @param sessionId the debug session ID
   * @param tick the tick number
   * @return the video time in milliseconds, or null if calculation fails
   */
  public Long getVideoTimeForTick(String sessionId, long tick) {
    if (hasCaptureQuality(sessionId, tick)) {
      var measured = measuredMapping(sessionId, tick);
      return "MEASURED".equals(measured.status()) ? Math.round(measured.mediaTimeMillis()) : null;
    }
    try {
      DebugSession debugSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (debugSession == null) {
        return null;
      }

      long startTick = debugSession.getStartTick() != null ? debugSession.getStartTick() : 0;

      // timeMs = (tick - startTick) * 600
      long timeMs = (tick - startTick) * 600;

      return Math.max(0, timeMs);

    } catch (Exception e) {
      log.error("Error calculating video time for tick {}: {}", tick, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Get the tick number for a given video time in milliseconds.
   *
   * <p>Formula: tick = startTick + (timeMs / 600)
   *
   * @param sessionId the debug session ID
   * @param timeMs the video time in milliseconds
   * @return the tick number, or null if calculation fails
   */
  public Long getTickForVideoTime(String sessionId, long timeMs) {
    Long measured = measuredTickForMedia(sessionId, null, timeMs);
    if (measured != null || hasAnyCaptureQuality(sessionId)) {
      return measured;
    }
    try {
      DebugSession debugSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (debugSession == null) {
        return null;
      }

      long startTick = debugSession.getStartTick() != null ? debugSession.getStartTick() : 0;

      // tick = startTick + (timeMs / 600)
      long tick = startTick + (timeMs / 600);

      return Math.max(startTick, tick);

    } catch (Exception e) {
      log.error("Error calculating tick for video time {}: {}", timeMs, e.getMessage(), e);
      return null;
    }
  }

  private boolean hasAnyCaptureQuality(String sessionId) {
    return snapshots != null
        && snapshots.findBySessionIdOrderByTick(sessionId).stream()
            .anyMatch(s -> s.getCaptureQuality() != null);
  }

  private Long measuredTickForMedia(String sessionId, Integer frame, Long timeMillis) {
    if (snapshots == null) {
      return null;
    }
    try {
      var session = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (session == null || session.getRecordingId() == null) {
        return null;
      }
      var recording =
          recordingSessionRepository.findByRecordingId(session.getRecordingId()).orElse(null);
      if (recording == null || recording.getVideoPath() == null) {
        return null;
      }
      var index =
          MeasuredVideoIndex.read(
              java.nio.file.Path.of(recording.getVideoPath()), recording.getRecordingId());
      if (!index.qualityCodes().isEmpty()) {
        return null;
      }
      double media;
      if (frame != null) {
        if (frame < 0 || frame >= index.frames().size()) {
          return null;
        }
        media = index.frames().get(frame).mediaMicros() / 1000.0;
      } else {
        media = timeMillis;
      }
      if (media < 0
          || media > index.frames().get(index.frames().size() - 1).mediaMicros() / 1000.0) {
        return null;
      }
      Long best = null;
      double delta = Double.POSITIVE_INFINITY;
      for (var s : snapshots.findBySessionIdOrderByTick(sessionId)) {
        var mapping = measuredMapping(s.getCaptureQuality(), sessionId, s.getTick(), index);
        if (!"MEASURED".equals(mapping.status())) {
          continue;
        }
        double distance = Math.abs(mapping.mediaTimeMillis() - media);
        if (distance < delta) {
          delta = distance;
          best = s.getTick();
        }
      }
      return delta <= 400 ? best : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  /**
   * Get sync information for a session.
   *
   * @param sessionId the debug session ID
   * @return sync information including FPS, start tick, etc.
   */
  public SyncInfo getSyncInfo(String sessionId) {
    SyncInfo info = new SyncInfo();
    info.sessionId = sessionId;

    try {
      DebugSession debugSession = sessionRepository.findBySessionId(sessionId).orElse(null);
      if (debugSession != null) {
        info.hasVideo = debugSession.getRecordingId() != null;
        info.startTick = debugSession.getStartTick() != null ? debugSession.getStartTick() : 0;
        info.endTick = debugSession.getEndTick();

        if (debugSession.getRecordingId() != null) {
          RecordingSession recording =
              recordingSessionRepository
                  .findByRecordingId(debugSession.getRecordingId())
                  .orElse(null);

          if (recording != null) {
            info.fps = recording.getFps();
            info.totalFrames =
                recording.getFrameCount() != null ? recording.getFrameCount().intValue() : null;
            info.durationMs = recording.getDurationMs();
          }
        }
      }

    } catch (Exception e) {
      log.error("Error getting sync info for session {}: {}", sessionId, e.getMessage(), e);
    }

    if (snapshots != null && info.hasVideo) {
      java.util.List<Double> residuals = new java.util.ArrayList<>();
      java.util.Set<String> codes = new java.util.TreeSet<>();
      for (var mapping : measurements(sessionId).values()) {
        if ("MEASURED".equals(mapping.status())) {
          residuals.add(Math.abs(mapping.residualMillis()) + mapping.uncertaintyMillis());
        } else {
          codes.addAll(mapping.qualityCodes());
        }
      }
      residuals.sort(Double::compare);
      info.calibratedTicks = residuals.size();
      info.calibrationStatus =
          residuals.isEmpty() ? "UNAVAILABLE" : codes.isEmpty() ? "MEASURED" : "PARTIAL";
      info.calibrationQualityCodes = java.util.List.copyOf(codes);
      if (!residuals.isEmpty()) {
        info.maxAlignmentErrorMillis = residuals.get(residuals.size() - 1);
        info.p95AlignmentErrorMillis =
            residuals.get(Math.max(0, (int) Math.ceil(residuals.size() * .95) - 1));
      }
    }
    return info;
  }

  /** Sync information DTO. */
  public static class SyncInfo {
    public String sessionId;
    public boolean hasVideo;
    public long startTick;
    public Long endTick;
    public Integer fps;
    public Integer totalFrames;
    public Long durationMs;
    public String calibrationSchema = "tick-video-calibration/v1";
    public String calibrationStatus = "UNAVAILABLE";
    public java.util.List<String> calibrationQualityCodes =
        java.util.List.of("CAPTURE_CALIBRATION_MISSING");
    public int calibratedTicks;
    public Double maxAlignmentErrorMillis;
    public Double p95AlignmentErrorMillis;

    public long getTickRange() {
      if (endTick != null) {
        return endTick - startTick;
      }
      return 0;
    }

    public double getDurationSeconds() {
      if (durationMs != null) {
        return durationMs / 1000.0;
      }
      if (endTick != null) {
        return (endTick - startTick) * 0.6;
      }
      return 0;
    }
  }
}
