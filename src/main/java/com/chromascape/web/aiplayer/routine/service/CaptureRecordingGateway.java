package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Correlation-aware boundary around the debugger/recorder. Lookup is deliberately the first step
 * for every retry, so an uncertain transport result never blindly starts or stops a second recorder
 * session.
 */
@Component
public class CaptureRecordingGateway {
  private final DebugSessionService debugSessions;
  private final RecordingSessionRepository recordings;
  private final CaptureExternalBindingService bindings;

  @Autowired
  public CaptureRecordingGateway(
      DebugSessionService debugSessions,
      RecordingSessionRepository recordings,
      CaptureExternalBindingService bindings) {
    this.debugSessions = debugSessions;
    this.recordings = recordings;
    this.bindings = bindings;
  }

  /**
   * Compatibility constructor for isolated adapter tests that do not bootstrap the binding store.
   */
  public CaptureRecordingGateway(
      DebugSessionService debugSessions, RecordingSessionRepository recordings) {
    this.debugSessions = debugSessions;
    this.recordings = recordings;
    this.bindings = null;
  }

  public StartResult startOrFind(String sessionId, String taskKey, String correlationId) {
    // The correlation id is the external idempotency binding. A lookup by it must precede every
    // recorder call; the capture session id is only a legacy fallback for sessions created before
    // the outbox existed.
    Optional<DebugSession> existing = optional(debugSessions.getSession(correlationId));
    if (existing.isEmpty() && sessionId != null && !sessionId.isBlank()) {
      existing = optional(debugSessions.getSession(sessionId));
    }
    if (bindings != null) {
      var bound = bindings.find(correlationId);
      if (bound != null && bound.getRecordingId() != null && !bound.getRecordingId().isBlank()) {
        return new StartResult(
            true, false, bound.getDebugSessionId(), bound.getRecordingId(), null);
      }
      if (bound != null
          && bound.getDebugSessionId() != null
          && !bound.getDebugSessionId().isBlank()) {
        Optional<DebugSession> boundExternal =
            optional(debugSessions.getSession(bound.getDebugSessionId()));
        if (boundExternal.isPresent()
            && boundExternal.get().getRecordingId() != null
            && !boundExternal.get().getRecordingId().isBlank()) {
          bindings.recordStart(
              correlationId,
              boundExternal.get().getSessionId(),
              boundExternal.get().getRecordingId());
          return new StartResult(
              true,
              false,
              boundExternal.get().getSessionId(),
              boundExternal.get().getRecordingId(),
              null);
        }
      }
    }
    if (existing.isPresent()
        && existing.get().getRecordingId() != null
        && !existing.get().getRecordingId().isBlank()) {
      if (bindings != null) {
        var preparation = bindings.prepareStart(sessionId, correlationId);
        bindings.recordStart(
            correlationId, existing.get().getSessionId(), existing.get().getRecordingId());
      }
      return new StartResult(
          true, false, existing.get().getSessionId(), existing.get().getRecordingId(), null);
    }
    CaptureExternalBindingService.StartPreparation preparation =
        bindings == null
            ? new CaptureExternalBindingService.StartPreparation(sessionId, null, true)
            : bindings.prepareStart(sessionId, correlationId);
    if (!preparation.execute()) {
      return new StartResult(
          false, true, preparation.debugSessionId(), null, "CAPTURE_START_OUTCOME_UNCERTAIN");
    }
    try {
      SessionStartResponse response =
          debugSessions.startCaptureSession(
              null, taskKey, true, RecordingTriggerSource.MANUAL, preparation.debugSessionId());
      if (response.getSessionId() == null
          || response.getRecordingId() == null
          || response.getRecordingId().isBlank()) {
        return new StartResult(
            false,
            true,
            response.getSessionId(),
            response.getRecordingId(),
            "VIDEO_CAPTURE_UNAVAILABLE");
      }
      if (bindings != null)
        bindings.recordStart(correlationId, response.getSessionId(), response.getRecordingId());
      return new StartResult(true, false, response.getSessionId(), response.getRecordingId(), null);
    } catch (Exception exception) {
      // A failed call does not prove that the recorder did not act. The next reconciliation will
      // perform the same lookup using this immutable session/correlation binding.
      return new StartResult(
          false, true, sessionId, null, message(exception, "CAPTURE_START_UNCERTAIN"));
    }
  }

  public CompleteResult completeOrFind(
      String sessionId,
      String debugSessionId,
      String recordingId,
      int finalTick,
      String correlationId) {
    String effectiveDebugSessionId = debugSessionId;
    String effectiveRecordingId = recordingId;
    CaptureExternalBindingService.CompletePreparation preparation = null;
    if (bindings != null) {
      var bound = bindings.find(correlationId);
      if (bound != null) {
        if (effectiveDebugSessionId == null || effectiveDebugSessionId.isBlank())
          effectiveDebugSessionId = bound.getDebugSessionId();
        if (effectiveRecordingId == null || effectiveRecordingId.isBlank())
          effectiveRecordingId = bound.getRecordingId();
      }
    }
    Optional<CompleteResult> alreadyFinalized =
        lookupComplete(effectiveDebugSessionId, sessionId, effectiveRecordingId);
    if (alreadyFinalized.isPresent()) return alreadyFinalized.get();
    if (bindings != null) {
      preparation =
          bindings.prepareComplete(
              sessionId, correlationId, effectiveDebugSessionId, effectiveRecordingId);
      effectiveDebugSessionId = preparation.debugSessionId();
      effectiveRecordingId = preparation.recordingId();
      if (preparation.alreadyCompleted() || !preparation.execute()) {
        return CompleteResult.uncertain("CAPTURE_COMPLETE_OUTCOME_UNCERTAIN");
      }
    }
    try {
      DebugSession session =
          effectiveDebugSessionId == null || effectiveDebugSessionId.isBlank()
              ? null
              : optional(debugSessions.getSession(effectiveDebugSessionId)).orElse(null);
      if (session == null && sessionId != null && !sessionId.isBlank())
        session = optional(debugSessions.getSession(sessionId)).orElse(null);
      if (session == null
          || (session.getRecordingId() != null
              && effectiveRecordingId != null
              && !effectiveRecordingId.equals(session.getRecordingId()))) {
        return CompleteResult.uncertain("CAPTURE_SESSION_NOT_FOUND");
      }
      debugSessions.stopCaptureSession(session.getSessionId(), (long) finalTick, true);
      Optional<CompleteResult> finalized =
          lookupComplete(session.getSessionId(), sessionId, effectiveRecordingId);
      if (finalized.isPresent() && bindings != null) bindings.recordComplete(correlationId);
      return finalized.orElseGet(() -> CompleteResult.uncertain("VIDEO_CAPTURE_MISSING"));
    } catch (Exception exception) {
      return CompleteResult.uncertain(message(exception, "CAPTURE_COMPLETE_UNCERTAIN"));
    }
  }

  private Optional<CompleteResult> lookupComplete(
      String debugSessionId, String legacySessionId, String recordingId) {
    DebugSession session = null;
    if (debugSessionId != null && !debugSessionId.isBlank()) {
      session = debugSessions.getSession(debugSessionId).orElse(null);
    }
    if (session == null && legacySessionId != null && !legacySessionId.isBlank()) {
      session = debugSessions.getSession(legacySessionId).orElse(null);
    }
    if (session != null
        && session.getLifecycleState() != DebugSessionLifecycleState.FINALIZED
        && session.getEndTime() == null) {
      return Optional.empty();
    }
    if (recordingId == null || recordingId.isBlank()) return Optional.empty();
    RecordingSession recording = recordings.findByRecordingId(recordingId).orElse(null);
    if (recording == null
        || recording.getVideoPath() == null
        || !Files.isRegularFile(Path.of(recording.getVideoPath()))) {
      return Optional.empty();
    }
    String videoHash;
    try {
      videoHash = sha256(Path.of(recording.getVideoPath()));
    } catch (Exception exception) {
      return Optional.of(CompleteResult.uncertain("VIDEO_HASH_UNAVAILABLE"));
    }
    return Optional.of(
        new CompleteResult(true, false, recordingId, recording.getVideoPath(), videoHash, null));
  }

  private static String message(Exception exception, String fallback) {
    return exception.getMessage() == null || exception.getMessage().isBlank()
        ? fallback
        : exception.getMessage().substring(0, Math.min(1000, exception.getMessage().length()));
  }

  private static <T> Optional<T> optional(Optional<T> value) {
    return value == null ? Optional.empty() : value;
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
    }
    StringBuilder hex = new StringBuilder("sha256:");
    for (byte value : digest.digest()) hex.append(String.format("%02x", value));
    return hex.toString();
  }

  public record StartResult(
      boolean success,
      boolean uncertain,
      String debugSessionId,
      String recordingId,
      String error) {}

  public record CompleteResult(
      boolean success,
      boolean uncertain,
      String recordingId,
      String videoPath,
      String videoSha256,
      String error) {
    static CompleteResult uncertain(String error) {
      return new CompleteResult(false, true, null, null, null, error);
    }
  }
}
