package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.utils.core.screen.window.SelectionValidation;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import java.awt.Point;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KinputTargetInputSessionTest {
  private static final ProcessIdentity IDENTITY =
      new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");

  @Test
  void usesClientRelativeCoordinatesAndReleasesHeldInputOnClose() throws Exception {
    KinputBackend backend = mock(KinputBackend.class);
    AutoCloseable lease = mock(AutoCloseable.class);
    KinputTargetInputSession session =
        new KinputTargetInputSession(backend, null, -1, 100, 80, null, null, lease);
    session.moveMouse(new Point(10, 12));
    session.holdMouse(MouseButton.RIGHT);
    session.holdKey(17);

    session.close();

    InOrder order = inOrder(backend, lease);
    order.verify(backend).moveMouse(10, 12);
    order.verify(backend).mouseInput(10, 12, 501, Kinput.MouseButton.RIGHT);
    order.verify(backend).sendModifierKey(401, "key", 17);
    order.verify(backend).sendModifierKey(402, "key", 17);
    order.verify(backend).mouseInput(10, 12, 502, Kinput.MouseButton.RIGHT);
    order.verify(backend).destroy();
    order.verify(lease).close();
    assertFalse(session.isOpen());
  }

  @Test
  void sendsBothKeyEdgesAndFailsClosedForWheelInput() {
    KinputBackend backend = mock(KinputBackend.class);
    KinputTargetInputSession session =
        new KinputTargetInputSession(backend, null, -1, 100, 80, null, null, () -> {});

    session.sendString("a", 0, 0);
    InputSessionException failure =
        assertThrows(InputSessionException.class, () -> session.scrollMouse(1));

    verify(backend).sendKeyEvent(401, 'a');
    verify(backend).sendKeyEvent(402, 'a');
    assertEquals(InputSessionDiagnostic.INPUT_UNSUPPORTED, failure.diagnostic());
  }

  @Test
  void rejectsAChangedSelectionBeforeSendingInput() {
    KinputBackend backend = mock(KinputBackend.class);
    WindowSelectionService selection = mock(WindowSelectionService.class);
    ProcessManager processManager = mock(ProcessManager.class);
    when(selection.getSelectedGeneration()).thenReturn(8L);
    when(selection.validateSelected(processManager, 7L)).thenReturn(SelectionValidation.VALID);
    KinputTargetInputSession session =
        new KinputTargetInputSession(
            backend, IDENTITY, 7L, 100, 80, selection, processManager, () -> {});

    InputSessionException failure =
        assertThrows(InputSessionException.class, () -> session.moveMouse(new Point(1, 1)));

    assertEquals(InputSessionDiagnostic.STALE_GENERATION, failure.diagnostic());
  }

  @Test
  void destroyFailureRetainsLeaseFencesInputAndCanBeRetried() throws Exception {
    KinputBackend backend = mock(KinputBackend.class);
    AutoCloseable lease = mock(AutoCloseable.class);
    KinputTargetInputSession session =
        new KinputTargetInputSession(backend, null, -1, 100, 80, null, null, lease);
    doThrow(new IllegalStateException("destroy failed")).doNothing().when(backend).destroy();

    assertThrows(IllegalStateException.class, session::close);

    assertFalse(session.isOpen());
    assertThrows(InputSessionException.class, () -> session.moveMouse(new Point(1, 1)));
    verify(lease, never()).close();

    session.close();

    verify(backend, times(2)).destroy();
    verify(lease).close();
  }
}
