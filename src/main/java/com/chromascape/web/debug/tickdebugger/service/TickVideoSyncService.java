package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
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

  public TickVideoSyncService(
      DebugSessionRepository sessionRepository,
      RecordingSessionRepository recordingSessionRepository) {
    this.sessionRepository = sessionRepository;
    this.recordingSessionRepository = recordingSessionRepository;
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
