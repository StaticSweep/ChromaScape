package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RemoteInputAttachmentCoordinatorTest {
  @Test
  void leaseIsExclusiveAndIdempotentlyReleased() {
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");
    RemoteInputAttachmentCoordinator coordinator = new RemoteInputAttachmentCoordinator();
    RemoteInputAttachmentCoordinator.Lease lease = coordinator.acquire(identity);

    InputSessionException duplicate =
        assertThrows(InputSessionException.class, () -> coordinator.acquire(identity));
    assertEquals(InputSessionDiagnostic.TARGET_ALREADY_ATTACHED, duplicate.diagnostic());

    lease.close();
    lease.close();
    assertDoesNotThrow(() -> coordinator.acquire(identity).close());
  }
}
