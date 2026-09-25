package com.chromascape.web.aiplayer.routine.service;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureExternalBinding;
import com.chromascape.web.aiplayer.routine.repository.RoutineCaptureExternalBindingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits the external correlation binding before recorder I/O. An attempted-but-unresolved binding
 * is deliberately treated as UNCERTAIN on retry; the caller must prove the external outcome before
 * another start/stop can ever be considered.
 */
@Service
public class CaptureExternalBindingService {
  private final RoutineCaptureExternalBindingRepository repository;

  public CaptureExternalBindingService(RoutineCaptureExternalBindingRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public StartPreparation prepareStart(String sessionId, String correlationId) {
    RoutineCaptureExternalBinding binding =
        repository.findWithLockByCorrelationId(correlationId).orElse(null);
    if (binding == null) {
      binding = new RoutineCaptureExternalBinding();
      binding.setCorrelationId(correlationId);
      binding.setSessionId(sessionId);
      binding.setDebugSessionId(deterministicDebugSessionId(correlationId));
      binding.setStartAttempted(true);
      repository.save(binding);
      return new StartPreparation(binding.getDebugSessionId(), null, true);
    }
    if (binding.getRecordingId() != null && !binding.getRecordingId().isBlank()) {
      return new StartPreparation(binding.getDebugSessionId(), binding.getRecordingId(), false);
    }
    return new StartPreparation(binding.getDebugSessionId(), null, false);
  }

  @Transactional
  public void recordStart(String correlationId, String debugSessionId, String recordingId) {
    RoutineCaptureExternalBinding binding =
        repository
            .findWithLockByCorrelationId(correlationId)
            .orElseThrow(() -> new IllegalStateException("CAPTURE_CORRELATION_BINDING_MISSING"));
    if (debugSessionId != null && !debugSessionId.isBlank())
      binding.setDebugSessionId(debugSessionId);
    binding.setRecordingId(recordingId);
    binding.setStartAttempted(true);
    repository.save(binding);
  }

  @Transactional
  public CompletePreparation prepareComplete(
      String sessionId, String correlationId, String debugSessionId, String recordingId) {
    RoutineCaptureExternalBinding binding =
        repository.findWithLockByCorrelationId(correlationId).orElse(null);
    if (binding == null) {
      binding = new RoutineCaptureExternalBinding();
      binding.setCorrelationId(correlationId);
      binding.setSessionId(sessionId);
      binding.setDebugSessionId(
          debugSessionId == null || debugSessionId.isBlank()
              ? deterministicDebugSessionId(correlationId)
              : debugSessionId);
      binding.setRecordingId(recordingId);
      binding.setStartAttempted(true);
      binding.setCompletionAttempted(true);
      repository.save(binding);
      return new CompletePreparation(
          binding.getDebugSessionId(), binding.getRecordingId(), true, false);
    }
    if (binding.getDebugSessionId() == null || binding.getDebugSessionId().isBlank()) {
      binding.setDebugSessionId(debugSessionId);
    }
    if (binding.getRecordingId() == null || binding.getRecordingId().isBlank()) {
      binding.setRecordingId(recordingId);
    }
    if (binding.isCompleted()) {
      return new CompletePreparation(
          binding.getDebugSessionId(), binding.getRecordingId(), false, true);
    }
    if (binding.isCompletionAttempted()) {
      return new CompletePreparation(
          binding.getDebugSessionId(), binding.getRecordingId(), false, false);
    }
    binding.setCompletionAttempted(true);
    repository.save(binding);
    return new CompletePreparation(
        binding.getDebugSessionId(), binding.getRecordingId(), true, false);
  }

  @Transactional
  public void recordComplete(String correlationId) {
    repository
        .findWithLockByCorrelationId(correlationId)
        .ifPresent(
            binding -> {
              binding.setCompleted(true);
              binding.setCompletionAttempted(true);
              repository.save(binding);
            });
  }

  @Transactional(readOnly = true)
  public RoutineCaptureExternalBinding find(String correlationId) {
    return repository.findByCorrelationId(correlationId).orElse(null);
  }

  private static String deterministicDebugSessionId(String correlationId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(correlationId.getBytes(StandardCharsets.UTF_8));
      return "capture_debug_" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (Exception exception) {
      throw new IllegalStateException("CAPTURE_CORRELATION_ID_INVALID", exception);
    }
  }

  public record StartPreparation(String debugSessionId, String recordingId, boolean execute) {}

  public record CompletePreparation(
      String debugSessionId, String recordingId, boolean execute, boolean alreadyCompleted) {}
}
