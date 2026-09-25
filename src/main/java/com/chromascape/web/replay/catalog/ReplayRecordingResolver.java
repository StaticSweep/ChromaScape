package com.chromascape.web.replay.catalog;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a recording ID to its constituent {@link RecordingSession} and optional {@link
 * DebugSession}, providing a single runtime-neutral lookup point for all replay modes.
 */
@Service
@Transactional(readOnly = true)
public class ReplayRecordingResolver {

  private final RecordingSessionRepository recordingSessionRepository;
  private final DebugSessionRepository debugSessionRepository;

  public ReplayRecordingResolver(
      RecordingSessionRepository recordingSessionRepository,
      DebugSessionRepository debugSessionRepository) {
    this.recordingSessionRepository = recordingSessionRepository;
    this.debugSessionRepository = debugSessionRepository;
  }

  /**
   * Returns the resolved recording and debug session, or throws a stable {@link ReplayException}
   * with {@code RECORDING_NOT_FOUND}.
   */
  public ResolvedRecording resolve(String recordingId) {
    RecordingSession recording =
        recordingSessionRepository
            .findByRecordingId(recordingId)
            .orElseThrow(
                () ->
                    ReplayException.notFound(
                        ReplayErrorCode.RECORDING_NOT_FOUND,
                        "Recording " + recordingId + " not found"));

    Optional<DebugSession> debugSession = debugSessionRepository.findByRecordingId(recordingId);

    return new ResolvedRecording(recording, debugSession.orElse(null));
  }

  public record ResolvedRecording(RecordingSession recording, DebugSession debugSession) {
    public boolean hasDebugSession() {
      return debugSession != null;
    }
  }
}
