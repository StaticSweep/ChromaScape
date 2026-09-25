package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.SessionContextDto;
import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.exception.PatchLoopRequestException;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Resolves the effective debug session context for patch-loop execution and evidence lookup. */
@Service
public class PatchLoopSessionResolverService {

  public static final String SOURCE_EXPLICIT_DEBUG_SESSION = "explicit_debug_session";
  public static final String SOURCE_EXPLICIT_RECORDING = "explicit_recording";
  public static final String SOURCE_RUN_SESSION = "run_session";
  public static final String SOURCE_LATEST_BY_SCRIPT = "latest_by_script";

  private final DebugSessionRepository debugSessionRepository;
  private final ObjectMapper objectMapper;

  public PatchLoopSessionResolverService(
      DebugSessionRepository debugSessionRepository, ObjectMapper objectMapper) {
    this.debugSessionRepository = debugSessionRepository;
    this.objectMapper = objectMapper;
  }

  public Optional<ResolvedSessionContext> resolveForIteration(PatchLoopIteration iteration) {
    return resolve(iteration, true);
  }

  public Optional<ResolvedSessionContext> resolveForRetest(PatchLoopIteration iteration) {
    return resolve(iteration, false);
  }

  public void applyResolvedSession(PatchLoopIteration iteration, ResolvedSessionContext resolved) {
    iteration.setRunSessionId(resolved.getSession().getSessionId());
    iteration.setRunRecordingId(resolved.getSession().getRecordingId());

    SessionContextDto sessionContext =
        readSessionContext(iteration).orElseGet(SessionContextDto::new);
    sessionContext.setDebugSessionId(resolved.getSession().getSessionId());
    if (resolved.getSession().getRecordingId() != null
        && !resolved.getSession().getRecordingId().isBlank()) {
      sessionContext.setRecordingId(resolved.getSession().getRecordingId());
    }
    sessionContext.setResolutionSource(resolved.getResolutionSource());
    iteration.setSessionContext(objectMapper.valueToTree(sessionContext));
  }

  public Optional<SessionContextDto> readSessionContext(PatchLoopIteration iteration) {
    if (iteration.getSessionContext() == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          objectMapper.treeToValue(iteration.getSessionContext(), SessionContextDto.class));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private Optional<ResolvedSessionContext> resolve(
      PatchLoopIteration iteration, boolean allowRunSessionReuse) {
    Optional<SessionContextDto> sessionContextOpt = readSessionContext(iteration);
    if (sessionContextOpt.isPresent()) {
      SessionContextDto sessionContext = sessionContextOpt.get();
      if (sessionContext.getDebugSessionId() != null
          && !sessionContext.getDebugSessionId().isBlank()) {
        DebugSession session =
            debugSessionRepository
                .findBySessionId(sessionContext.getDebugSessionId())
                .orElseThrow(
                    () ->
                        new PatchLoopRequestException(
                            PatchLoopFailureCode.SESSION_NOT_FOUND,
                            "No debug session found for debugSessionId: "
                                + sessionContext.getDebugSessionId()));
        return Optional.of(new ResolvedSessionContext(session, SOURCE_EXPLICIT_DEBUG_SESSION));
      }

      if (sessionContext.getRecordingId() != null && !sessionContext.getRecordingId().isBlank()) {
        DebugSession session =
            debugSessionRepository
                .findByRecordingId(sessionContext.getRecordingId())
                .orElseThrow(
                    () ->
                        new PatchLoopRequestException(
                            PatchLoopFailureCode.SESSION_NOT_FOUND,
                            "No debug session found for recordingId: "
                                + sessionContext.getRecordingId()));
        return Optional.of(new ResolvedSessionContext(session, SOURCE_EXPLICIT_RECORDING));
      }
    }

    if (allowRunSessionReuse
        && iteration.getRunSessionId() != null
        && !iteration.getRunSessionId().isBlank()) {
      Optional<DebugSession> session =
          debugSessionRepository.findBySessionId(iteration.getRunSessionId());
      if (session.isPresent()) {
        return Optional.of(new ResolvedSessionContext(session.get(), SOURCE_RUN_SESSION));
      }
    }

    return debugSessionRepository
        .findFirstByScriptNameOrderByStartTimeDesc(iteration.getScriptName())
        .map(session -> new ResolvedSessionContext(session, SOURCE_LATEST_BY_SCRIPT));
  }

  public static final class ResolvedSessionContext {
    private final DebugSession session;
    private final String resolutionSource;

    public ResolvedSessionContext(DebugSession session, String resolutionSource) {
      this.session = session;
      this.resolutionSource = resolutionSource;
    }

    public DebugSession getSession() {
      return session;
    }

    public String getResolutionSource() {
      return resolutionSource;
    }
  }
}
