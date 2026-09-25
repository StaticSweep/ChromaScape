package com.chromascape.web.gateway.context;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentity;
import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureRecordRepository;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Resolves a debugger context only after an independent authenticated-session lookup. */
@Service
public class DebuggerContextService {

  private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

  private final DebugSessionRepository debugSessions;
  private final RoutineCaptureRecordRepository captures;
  private final ObjectMapper mapper;
  private final CurrentSliceIdentityValidator currentSliceIdentities;
  private final DebuggerContextValidator contexts;

  public DebuggerContextService(
      DebugSessionRepository debugSessions,
      RoutineCaptureRecordRepository captures,
      ObjectMapper mapper,
      CurrentSliceIdentityValidator currentSliceIdentities,
      DebuggerContextValidator contexts) {
    this.debugSessions = debugSessions;
    this.captures = captures;
    this.mapper = mapper;
    this.currentSliceIdentities = currentSliceIdentities;
    this.contexts = contexts;
  }

  public DebuggerContext resolve(
      Principal principal,
      String sessionId,
      String requestedMode,
      Long selectedTick,
      String patchIterationId,
      long contextRevision) {
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
    }
    DebuggerContext.Mode mode =
        validateSelectors(
            sessionId, requestedMode, selectedTick, patchIterationId, contextRevision);
    DebugSession debugSession =
        debugSessions
            .findBySessionId(sessionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Debug session not found"));
    // Authorization is intentionally independent of every caller-supplied identity value. The
    // current security principal must own the stored session (or carry the explicit operator role
    // supplied by the trusted gateway).
    if (!principal.getName().equals(debugSession.getAccountName())
        && !principal.getName().startsWith("operator:")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Debug session is not authorized");
    }
    RoutineCaptureRecord capture =
        captures
            .findBySessionId(sessionId)
            .or(() -> captures.findByDebugSessionId(sessionId))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Session has no current-slice identity"));
    CurrentSliceIdentity identity;
    try {
      identity =
          currentSliceIdentities.validate(
              mapper.readValue(capture.getIdentityJson(), CurrentSliceIdentity.class));
      if (identity.capture() == null
          || !capture.getSessionId().equals(identity.capture().sessionId())) {
        throw new IllegalArgumentException("stored capture identity does not match its record");
      }
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Stored current-slice identity is invalid", exception);
    }
    String iteration =
        patchIterationId == null || patchIterationId.isBlank()
            ? identity.run().attemptId()
            : patchIterationId;
    try {
      return contexts.map(
          identity,
          accountCorrelationId(debugSession.getAccountName()),
          debugSession.getSessionId(),
          iteration,
          mode,
          selectedTick,
          contextRevision);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Stored debugger identity is invalid", exception);
    }
  }

  private static DebuggerContext.Mode validateSelectors(
      String sessionId,
      String requestedMode,
      Long selectedTick,
      String patchIterationId,
      long contextRevision) {
    if (sessionId == null || !SELECTOR.matcher(sessionId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid debug session selector");
    }
    DebuggerContext.Mode mode = parseMode(requestedMode);
    if (contextRevision < 1 || contextRevision > 999_999_999L) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid context revision");
    }
    if (selectedTick != null && (selectedTick < 0 || selectedTick > 9_999_999_999_999_999L)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid selected tick");
    }
    if (patchIterationId != null
        && !patchIterationId.isBlank()
        && !SELECTOR.matcher(patchIterationId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid patch iteration selector");
    }
    if (mode == DebuggerContext.Mode.replay && selectedTick == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Replay context requires selected tick");
    }
    return mode;
  }

  private static DebuggerContext.Mode parseMode(String mode) {
    if ("live".equals(mode)) return DebuggerContext.Mode.live;
    if ("history".equals(mode) || "recording".equals(mode)) {
      return DebuggerContext.Mode.recording;
    }
    if ("replay".equals(mode)) return DebuggerContext.Mode.replay;
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported debugger mode");
  }

  private static String accountCorrelationId(String accountName) {
    if (accountName == null || accountName.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Debug session has no account identity");
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(accountName.getBytes(StandardCharsets.UTF_8));
      return "account:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
