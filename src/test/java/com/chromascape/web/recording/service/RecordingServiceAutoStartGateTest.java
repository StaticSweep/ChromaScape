package com.chromascape.web.recording.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecordingService#isAutoStartAllowed} per-source gating.
 *
 * <p>Verifies that TASK_AUTO honors {@code recording.auto-record-task-sessions} (so task-bound
 * sessions start even when generic {@code recording.auto-record} is off), matching the per-source
 * gating already used by {@code DebugSessionService}, and that the global {@code enabled} flag and
 * MICRO6_AUTO still gate on {@code autoRecord}.
 */
class RecordingServiceAutoStartGateTest {

  private RecordingService newService(RecordingConfig config) {
    UnifiedCaptureConfig unifiedConfig = new UnifiedCaptureConfig();
    UnifiedFrameCaptureService capture = mock(UnifiedFrameCaptureService.class);
    return new RecordingService(config, unifiedConfig, capture);
  }

  private RecordingConfig config(boolean enabled, boolean autoRecord, boolean autoRecordTaskSessions) {
    RecordingConfig c = new RecordingConfig();
    c.setEnabled(enabled);
    c.setAutoRecord(autoRecord);
    c.setAutoRecordTaskSessions(autoRecordTaskSessions);
    // Use a temp dir so constructor directory creation does not pollute the workspace.
    try {
      Path tmp = Files.createTempDirectory("rec-gate-test");
      c.setOutputDirectory(tmp.toString());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return c;
  }

  /** TASK_AUTO must be allowed when the task-session flag is on, even if autoRecord is off. */
  @Test
  void taskAuto_allowed_whenAutoRecordOffButTaskSessionsOn() {
    RecordingService svc = newService(config(true, false, true));
    assertTrue(svc.isAutoStartAllowed(RecordingTriggerSource.TASK_AUTO));
  }

  /** TASK_AUTO must be refused when the task-session flag is off. */
  @Test
  void taskAuto_refused_whenTaskSessionsOff() {
    RecordingService svc = newService(config(true, true, false));
    assertFalse(svc.isAutoStartAllowed(RecordingTriggerSource.TASK_AUTO));
  }

  /** MICRO6_AUTO gates on generic autoRecord, independent of the task-session flag. */
  @Test
  void micro6Auto_refused_whenAutoRecordOffEvenIfTaskSessionsOn() {
    RecordingService svc = newService(config(true, false, true));
    assertFalse(svc.isAutoStartAllowed(RecordingTriggerSource.MICRO6_AUTO));
  }

  /** MANUAL is never routed through auto-start in practice, but the gate must permit it. */
  @Test
  void manual_alwaysAllowed() {
    RecordingService svc = newService(config(true, false, false));
    assertTrue(svc.isAutoStartAllowed(RecordingTriggerSource.MANUAL));
  }
}
