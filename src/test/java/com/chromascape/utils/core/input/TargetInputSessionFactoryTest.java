package com.chromascape.utils.core.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.input.remoteinput.KinputBackend;
import com.chromascape.utils.core.input.remoteinput.RemoteInputBindingProvider;
import com.chromascape.utils.core.input.remoteinput.RemoteInputSessionOptions;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.utils.core.screen.window.SelectionValidation;
import com.chromascape.utils.core.screen.window.TargetDiscoveryService;
import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import java.awt.Rectangle;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TargetInputSessionFactoryTest {
  private static final ProcessIdentity IDENTITY =
      new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");

  @Test
  void windowsSessionUsesClientRelativeDimensionsAndFencing() {
    String originalOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
    try {
      Fixture fixture = new Fixture();
      TargetInputSession session = fixture.factory().openSelectedSession();

      assertEquals(new Rectangle(0, 0, 640, 480), session.getTargetBounds());
      session.close();
      verify(fixture.backend).destroy();
    } finally {
      restoreOs(originalOs);
    }
  }

  @Test
  void windowsBackendIsDestroyedWhenPostConstructionFenceFails() {
    String originalOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
    try {
      Fixture fixture = new Fixture();
      when(fixture.selection.validateSelected(fixture.manager, 7L))
          .thenReturn(SelectionValidation.VALID, SelectionValidation.REPLACED);

      InputSessionException failure =
          assertThrows(InputSessionException.class, () -> fixture.factory().openSelectedSession());

      assertEquals(InputSessionDiagnostic.TARGET_REPLACED, failure.diagnostic());
      verify(fixture.backend).destroy();
    } finally {
      restoreOs(originalOs);
    }
  }

  @Test
  void separateFactoriesShareTheProcessAttachmentLease() {
    String originalOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
    try {
      Fixture first = new Fixture();
      Fixture second = new Fixture();
      TargetInputSession session = first.factory().openSelectedSession();
      try {
        InputSessionException failure =
            assertThrows(InputSessionException.class, () -> second.factory().openSelectedSession());

        assertEquals(InputSessionDiagnostic.TARGET_ALREADY_ATTACHED, failure.diagnostic());
        verifyNoInteractions(second.backend);
      } finally {
        session.close();
      }
    } finally {
      restoreOs(originalOs);
    }
  }

  private static void restoreOs(String originalOs) {
    if (originalOs == null) {
      System.clearProperty("os.name");
    } else {
      System.setProperty("os.name", originalOs);
    }
  }

  private static final class Fixture {
    private final TargetDiscoveryService discovery = mock(TargetDiscoveryService.class);
    private final WindowSelectionService selection = mock(WindowSelectionService.class);
    private final ProcessManagerFactory processManagers = mock(ProcessManagerFactory.class);
    private final ProcessManager manager = mock(ProcessManager.class);
    private final RemoteInputBindingProvider bindingProvider =
        mock(RemoteInputBindingProvider.class);
    private final KinputBackend backend = mock(KinputBackend.class);

    private Fixture() {
      WindowInfo window =
          new WindowInfo(
              "1", null, "target", null, 42, true, new Rectangle(100, 200, 640, 480), IDENTITY);
      when(selection.hasSelection()).thenReturn(true);
      when(selection.getSelectedWindow()).thenReturn(window);
      when(selection.getSelectedIdentity()).thenReturn(IDENTITY);
      when(selection.getSelectedGeneration()).thenReturn(7L);
      when(processManagers.getProcessManager()).thenReturn(manager);
      when(selection.validateSelected(manager, 7L)).thenReturn(SelectionValidation.VALID);
    }

    private TargetInputSessionFactory factory() {
      return new TargetInputSessionFactory(
          discovery,
          selection,
          processManagers,
          bindingProvider,
          RemoteInputSessionOptions.DEFAULT,
          ignored -> backend);
    }
  }
}
