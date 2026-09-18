package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.screen.window.DiscoveredTarget;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.utils.core.screen.window.SelectionValidation;
import com.chromascape.utils.core.screen.window.TargetDiscoveryService;
import com.chromascape.utils.core.screen.window.WindowInfo;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteInputSessionTest {
  private static final long GENERATION = 7;
  private static final ProcessIdentity IDENTITY =
      new ProcessIdentity(ProcessOrigin.LOCAL, "host", 42, Instant.EPOCH, "boot");

  private final TargetDiscoveryService discovery = mock(TargetDiscoveryService.class);
  private final WindowSelectionService selection = mock(WindowSelectionService.class);
  private final ProcessManagerFactory processManagers = mock(ProcessManagerFactory.class);
  private final ProcessManager processManager = mock(ProcessManager.class);
  private final RemoteInputInterface nativeApi = mock(RemoteInputInterface.class);
  private RemoteInputAttachmentCoordinator coordinator;

  @BeforeEach
  void configureValidTarget() {
    coordinator = new RemoteInputAttachmentCoordinator();
    WindowInfo window =
        new WindowInfo(
            "1", null, "target", null, 42, true, new Rectangle(10, 20, 800, 600), IDENTITY);
    DiscoveredTarget target = new DiscoveredTarget(IDENTITY, window, "process", "eligible");
    when(selection.hasSelection()).thenReturn(true);
    when(selection.getSelectedGeneration()).thenReturn(GENERATION);
    when(selection.getSelectedIdentity()).thenReturn(IDENTITY);
    when(selection.validateSelected(processManager, GENERATION))
        .thenReturn(SelectionValidation.VALID);
    when(processManagers.getProcessManager()).thenReturn(processManager);
    when(discovery.resolveSelectedTarget())
        .thenReturn(
            new TargetDiscoveryService.TargetResolution(
                List.of(target), Optional.of(target), null));
    when(nativeApi.EIOS_RequestTarget("42")).thenReturn(new Pointer(1));
    doAnswer(
            invocation -> {
              ((IntByReference) invocation.getArgument(1)).setValue(800);
              ((IntByReference) invocation.getArgument(2)).setValue(600);
              return null;
            })
        .when(nativeApi)
        .EIOS_GetTargetDimensions(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    doAnswer(
            invocation -> {
              ((IntByReference) invocation.getArgument(1)).setValue(5);
              ((IntByReference) invocation.getArgument(2)).setValue(6);
              return null;
            })
        .when(nativeApi)
        .EIOS_GetMousePosition(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unrelatedClientDoesNotSuppressExactPidInjection() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(1L, 2L);
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(99);
    when(nativeApi.EIOS_GetClientPID(1)).thenReturn(42);
    doAnswer(
            invocation -> {
              assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY));
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);

    RemoteInputSession session = open(defaultOptions());

    verify(nativeApi).EIOS_Inject_PID(42);
    assertEquals(new Point(5, 6), session.getMousePosition());
    session.close();
    verify(nativeApi).EIOS_ReleaseTarget(new Pointer(1));
  }

  @Test
  void everyOperationRejectsAChangedGeneration() {
    configureAlreadyInjected();
    RemoteInputSession session = open(defaultOptions());
    when(selection.getSelectedGeneration()).thenReturn(GENERATION + 1);

    InputSessionException failure =
        assertThrows(InputSessionException.class, () -> session.moveMouse(new Point(1, 1)));

    assertEquals(InputSessionDiagnostic.STALE_GENERATION, failure.diagnostic());
    session.close();
  }

  @Test
  void closeReleasesHeldInputBeforeTargetAndIsIdempotent() {
    configureAlreadyInjected();
    RemoteInputSession session = open(defaultOptions());
    session.moveMouse(new Point(20, 30));
    session.holdKey(65);
    session.holdMouse(MouseButton.LEFT);

    session.close();
    session.close();

    verify(nativeApi).EIOS_ReleaseKey(new Pointer(1), 65);
    verify(nativeApi).EIOS_ReleaseMouse(new Pointer(1), 20, 30, MouseButton.LEFT.nativeValue());
    verify(nativeApi).EIOS_ReleaseTarget(new Pointer(1));
    assertFalse(session.isOpen());
  }

  @Test
  void timedOutLanePoisonsSessionAndRetainsLease() {
    configureAlreadyInjected();
    RemoteInputSession session = open(fastOptions());
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_MoveMouse(new Pointer(1), 1, 1);

    InputSessionException failure =
        assertThrows(InputSessionException.class, () -> session.moveMouse(new Point(1, 1)));

    assertEquals(InputSessionDiagnostic.NATIVE_CALL_TIMEOUT, failure.diagnostic());
    assertFalse(session.isOpen());
    session.close();
    InputSessionException duplicate =
        assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY));
    assertEquals(InputSessionDiagnostic.TARGET_ALREADY_ATTACHED, duplicate.diagnostic());
  }

  @Test
  void rejectsOutOfRangeClientCount() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(5L);
    InputSessionException failure =
        assertThrows(InputSessionException.class, () -> open(options(2)));
    assertEquals(InputSessionDiagnostic.CLIENT_ENUMERATION_INVALID, failure.diagnostic());
  }

  @Test
  void repeatedOpenCloseCyclesDoNotLeakTheIdentityLease() {
    configureAlreadyInjected();

    for (int cycle = 0; cycle < 25; cycle++) {
      RemoteInputSession session = open(defaultOptions());
      session.close();
    }

    verify(nativeApi, times(25)).EIOS_ReleaseTarget(new Pointer(1));
  }

  private void configureAlreadyInjected() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(1L);
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(42);
  }

  private RemoteInputSession open(RemoteInputSessionOptions options) {
    return RemoteInputSession.open(
        discovery, selection, processManagers, nativeApi, coordinator, options);
  }

  private static RemoteInputSessionOptions defaultOptions() {
    return new RemoteInputSessionOptions(
        true,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofMillis(1),
        8);
  }

  private static RemoteInputSessionOptions fastOptions() {
    return new RemoteInputSessionOptions(
        true,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        Duration.ofMillis(20),
        Duration.ofMillis(20),
        Duration.ofMillis(1),
        8);
  }

  private static RemoteInputSessionOptions options(int maxClients) {
    RemoteInputSessionOptions defaults = defaultOptions();
    return new RemoteInputSessionOptions(
        true,
        defaults.injectTimeout(),
        defaults.pairTimeout(),
        defaults.nativeCallTimeout(),
        defaults.releaseTimeout(),
        defaults.validationInterval(),
        maxClients);
  }
}
