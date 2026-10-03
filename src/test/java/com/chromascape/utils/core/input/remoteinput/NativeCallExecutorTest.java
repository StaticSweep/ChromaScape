package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NativeCallExecutorTest {
  @Test
  void timeoutPermanentlyPoisonsLane() {
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");
    NativeCallExecutor executor = new NativeCallExecutor(identity, 7L);

    InputSessionException timeout =
        assertThrows(
            InputSessionException.class,
            () ->
                executor.call(
                    () -> {
                      Thread.sleep(5_000);
                      return null;
                    },
                    Duration.ofMillis(20),
                    InputSessionDiagnostic.NATIVE_CALL_TIMEOUT));
    InputSessionException poisoned =
        assertThrows(
            InputSessionException.class,
            () ->
                executor.call(
                    () -> null, Duration.ofSeconds(1), InputSessionDiagnostic.NATIVE_CALL_TIMEOUT));

    assertEquals(InputSessionDiagnostic.NATIVE_CALL_TIMEOUT, timeout.diagnostic());
    assertEquals(InputSessionDiagnostic.SESSION_POISONED, poisoned.diagnostic());
  }

  @Test
  void deliberateCloseIsNotReportedAsPoisoned() {
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");
    NativeCallExecutor executor = new NativeCallExecutor(identity, 7L);

    executor.close();

    assertEquals(
        false,
        executor.isPoisoned(),
        "a lane closed after all native work completed is not a poisoned lane");
  }
}
