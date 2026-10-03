package com.chromascape.utils.core.input.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
  void injectionTimeoutIsATotalDeadlineNotExtendedByASlowNativeInjectCall() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(0L);
    doAnswer(
            invocation -> {
              Thread.sleep(350);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        new RemoteInputSessionOptions(
            true,
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(20),
            8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    // The pre-fix behaviour granted the inject call its own full 500ms budget on top of the total
    // deadline (>= ~1000ms); anything comfortably under that proves the deadline is shared.
    assertTrue(
        elapsedMillis < 1_500,
        "a 350ms inject call plus polling must stay near the 500ms budget, took " + elapsedMillis);
    assertTrue(
        failure.getMessage().contains("injectionCallReturned=+"),
        "timeout detail must carry the stage timeline: " + failure.getMessage());
    assertTrue(failure.getMessage().contains("totalDeadline=500ms"), failure.getMessage());
    assertTrue(failure.getMessage().contains("injectionDeadlineReached=+"), failure.getMessage());
    verify(nativeApi, times(1)).EIOS_Inject_PID(42);
    // The lane was never poisoned, so the identity lease must have been released on failure.
    coordinator.acquire(IDENTITY).close();
  }

  @Test
  void slowNativeInjectCallThatExceedsTheWholeBudgetIsBoundedByTheRemainingBudget() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(0L);
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        new RemoteInputSessionOptions(
            true,
            Duration.ofMillis(300),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(20),
            8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(
        elapsedMillis < 2_500,
        "the inject call must be cut at the remaining budget, not run 5s; took " + elapsedMillis);
  }

  @Test
  void clientRegisteringDuringTheInjectCallIsObservedByThePostInjectionPoll() {
    AtomicInteger polls = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean injected =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(
            invocation -> {
              polls.incrementAndGet();
              return injected.get() ? 1L : 0L;
            });
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(42);
    doAnswer(
            invocation -> {
              // The client registers while the native injection call is still running.
              Thread.sleep(200);
              injected.set(true);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        new RemoteInputSessionOptions(
            true,
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(20),
            8);

    RemoteInputSession session = open(options);

    assertEquals(2, polls.get(), "exactly one poll before and one after the injection call");
    session.close();
  }

  @Test
  void clientRegisteringAfterInjectionIsObservedWithinTheDeadline() {
    AtomicInteger polls = new AtomicInteger();
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(invocation -> polls.incrementAndGet() >= 4 ? 1L : 0L);
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(42);

    RemoteInputSession session = open(defaultOptions());

    verify(nativeApi, times(1)).EIOS_Inject_PID(42);
    assertTrue(polls.get() >= 4, "the client must have been polled until it registered");
    session.close();
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
    assertTrue(session.isPoisoned(), "a timed-out lane must report the session as poisoned");
    InputSessionException closeFailure = assertThrows(InputSessionException.class, session::close);
    assertEquals(InputSessionDiagnostic.SESSION_POISONED, closeFailure.diagnostic());
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

  @Test
  void capturesDetachedRenderedBgraPixels() {
    configureAlreadyInjected();
    Memory pixels = new Memory(16);
    pixels.write(
        0,
        new byte[] {
          0,
          0,
          (byte) 255,
          0,
          0,
          (byte) 255,
          0,
          1,
          (byte) 255,
          0,
          0,
          2,
          (byte) 255,
          (byte) 255,
          (byte) 255,
          3
        },
        0,
        16);
    when(nativeApi.EIOS_GetImageBuffer(new Pointer(1))).thenReturn(pixels);
    RemoteInputSession session = open(defaultOptions());
    configureDimensions(2, 2);

    var first = session.captureFrame();
    pixels.setByte(2, (byte) 0);
    final var second = session.captureFrame();

    assertEquals(2, first.image().getWidth());
    assertEquals(new Rectangle(0, 0, 2, 2), session.targetBounds());
    assertEquals(0xFFFF0000, first.image().getRGB(0, 0));
    assertEquals(0xFF000000, second.image().getRGB(0, 0));
    assertNotSame(first.image(), second.image());
    session.close();
  }

  @Test
  void refusesNullImageBufferWithoutReturningAnOldFrame() {
    configureAlreadyInjected();
    configureDimensions(2, 2);
    RemoteInputSession session = open(defaultOptions());
    when(nativeApi.EIOS_GetImageBuffer(new Pointer(1))).thenReturn(null);

    InputSessionException failure =
        assertThrows(InputSessionException.class, session::captureFrame);

    assertEquals(InputSessionDiagnostic.IMAGE_BUFFER_UNAVAILABLE, failure.diagnostic());
    session.close();
  }

  @Test
  void refusesFrameWhenDimensionsChangeDuringCopy() {
    configureAlreadyInjected();
    AtomicInteger dimensionsCall = new AtomicInteger();
    doAnswer(
            invocation -> {
              int call = dimensionsCall.getAndIncrement();
              ((IntByReference) invocation.getArgument(1)).setValue(call >= 2 ? 3 : 2);
              ((IntByReference) invocation.getArgument(2)).setValue(2);
              return null;
            })
        .when(nativeApi)
        .EIOS_GetTargetDimensions(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    Memory pixels = new Memory(16);
    when(nativeApi.EIOS_GetImageBuffer(new Pointer(1))).thenReturn(pixels);
    RemoteInputSession session = open(defaultOptions());

    InputSessionException failure =
        assertThrows(InputSessionException.class, session::captureFrame);

    assertEquals(InputSessionDiagnostic.FRAME_RESIZED_DURING_CAPTURE, failure.diagnostic());
    session.close();
  }

  @Test
  void refusesInvalidDimensionsBeforeReadingTheNativePointer() {
    configureAlreadyInjected();
    configureDimensions(2, 2);
    RemoteInputSession session = open(defaultOptions());
    configureDimensions(0, 2);

    InputSessionException failure =
        assertThrows(InputSessionException.class, session::captureFrame);

    assertEquals(InputSessionDiagnostic.INVALID_FRAME_DIMENSIONS, failure.diagnostic());
    verify(nativeApi, times(0)).EIOS_GetImageBuffer(new Pointer(1));
    session.close();
  }

  @Test
  void heldKeyReleaseTimeoutPoisonsAndRetainsTheLease() {
    configureAlreadyInjected();
    RemoteInputSession session = open(fastOptions());
    session.holdKey(65);
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_ReleaseKey(new Pointer(1), 65);

    InputSessionException failure = assertThrows(InputSessionException.class, session::close);

    assertEquals(InputSessionDiagnostic.RELEASE_TIMEOUT, failure.diagnostic());
    assertTrue(session.isPoisoned());
    InputSessionException second = assertThrows(InputSessionException.class, session::close);
    assertEquals(InputSessionDiagnostic.SESSION_POISONED, second.diagnostic());
    assertEquals(
        InputSessionDiagnostic.TARGET_ALREADY_ATTACHED,
        assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY))
            .diagnostic());
  }

  @Test
  void heldButtonReleaseTimeoutPoisonsAndRetainsTheLease() {
    configureAlreadyInjected();
    RemoteInputSession session = open(fastOptions());
    session.holdMouse(MouseButton.LEFT);
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_ReleaseMouse(new Pointer(1), 5, 6, MouseButton.LEFT.nativeValue());

    InputSessionException failure = assertThrows(InputSessionException.class, session::close);

    assertEquals(InputSessionDiagnostic.RELEASE_TIMEOUT, failure.diagnostic());
    assertTrue(session.isPoisoned());
    assertEquals(
        InputSessionDiagnostic.TARGET_ALREADY_ATTACHED,
        assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY))
            .diagnostic());
  }

  @Test
  void targetReleaseTimeoutPoisonsAndRetainsTheLease() {
    configureAlreadyInjected();
    RemoteInputSession session = open(fastOptions());
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_ReleaseTarget(new Pointer(1));

    InputSessionException failure = assertThrows(InputSessionException.class, session::close);

    assertEquals(InputSessionDiagnostic.RELEASE_TIMEOUT, failure.diagnostic());
    assertTrue(session.isPoisoned());
    assertEquals(
        InputSessionDiagnostic.SESSION_POISONED,
        assertThrows(InputSessionException.class, session::close).diagnostic());
    assertEquals(
        InputSessionDiagnostic.TARGET_ALREADY_ATTACHED,
        assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY))
            .diagnostic());
  }

  @Test
  void nonTimeoutReleaseFailureIsObservableAndDoesNotReleaseTheLease() {
    configureAlreadyInjected();
    RemoteInputSession session = open(fastOptions());
    session.holdKey(65);
    doThrow(
            new InputSessionException(
                InputSessionDiagnostic.NATIVE_CALL_FAILED, IDENTITY, GENERATION, "release failed"))
        .when(nativeApi)
        .EIOS_ReleaseKey(new Pointer(1), 65);

    InputSessionException failure = assertThrows(InputSessionException.class, session::close);

    assertEquals(InputSessionDiagnostic.NATIVE_CALL_FAILED, failure.diagnostic());
    assertFalse(session.isOpen());
    // The lane was not poisoned, so the identity lease is still held by the failed (not closed)
    // session: a retry through /session/close must remain possible rather than silently succeeding.
    assertEquals(
        InputSessionDiagnostic.TARGET_ALREADY_ATTACHED,
        assertThrows(InputSessionException.class, () -> coordinator.acquire(IDENTITY))
            .diagnostic());
  }

  @Test
  void slowNativeClientEnumerationReceivesOnlyTheRemainingBudget() {
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return 0L;
            });
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(
        elapsedMillis < 1_500,
        "a stalled GetClients must be cut at the remaining budget, took " + elapsedMillis);
    verify(nativeApi, times(1)).EIOS_GetClients(anyByte());
  }

  @Test
  void slowFirstPidLookupExhaustsTheTotalDeadline() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(1L);
    when(nativeApi.EIOS_GetClientPID(anyLong()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return 0;
            });
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(elapsedMillis < 1_500, "the first pid read must not get a full extra budget");
  }

  @Test
  void multiplePidLookupsCannotMultiplyTheTimeout() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(4L);
    when(nativeApi.EIOS_GetClientPID(anyLong()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(400);
              return 99;
            });
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(
        elapsedMillis < 1_500,
        "four 400ms pid reads must share one 500ms budget, took " + elapsedMillis);
  }

  @Test
  void largeClientCountCannotExpandTheWallClockBudget() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(1_000L);
    when(nativeApi.EIOS_GetClientPID(anyLong()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(100);
              return 99;
            });
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 4_096);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(
        elapsedMillis < 1_500,
        "a large maxClients must not turn into a 100s enumeration, took " + elapsedMillis);
  }

  @Test
  void injectionReturningAtTheDeadlineDoesNotReceiveAnotherFullPoll() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(0L);
    doAnswer(
            invocation -> {
              Thread.sleep(400);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    // Only the pre-injection poll: the inject call already consumed the whole budget, so no fresh
    // post-injection poll is granted.
    verify(nativeApi, times(1)).EIOS_GetClients(anyByte());
  }

  @Test
  void timeoutDiagnosticCarriesOrderedStageTimelineWithoutPointers() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(0L);
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    String message = failure.getMessage();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    assertTrue(message.contains("totalDeadline=300ms"), message);
    assertTrue(message.contains("injectionDeadlineReached=+"), message);
    int requested = message.indexOf("attachmentRequested");
    int injected = message.indexOf("injectionCallEntered");
    int returned = message.indexOf("injectionCallReturned");
    int polled = message.indexOf("firstClientPoll");
    int selected = message.indexOf("selectedPidObserved");
    int deadline = message.indexOf("injectionDeadlineReached");
    int total = message.indexOf("totalDeadline");
    assertTrue(
        requested < injected
            && injected < returned
            && returned < polled
            && polled < selected
            && selected < deadline
            && deadline < total,
        "stage timeline must be internally ordered: " + message);
    assertFalse(message.contains("0x"), "diagnostic must not leak native pointers: " + message);
    assertFalse(
        message.toLowerCase(java.util.Locale.ROOT).contains("cmd"),
        "diagnostic must not leak command lines: " + message);
  }

  @Test
  void enumerationConsumingMostOfTheDeadlineLeavesInjectionOnlyTheRemainder() {
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(250);
              return 0L;
            });
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    // A stale budget would hand the injection another ~300ms on top of the 250ms enumeration
    // (elapsed >= ~550ms); the fixed behaviour cuts the inject call at the actual ~50ms remainder.
    assertTrue(
        elapsedMillis < 500,
        "injection must not receive a fresh budget after enumeration consumed most of the "
            + "deadline, took "
            + elapsedMillis
            + "ms");
    assertTrue(failure.getMessage().contains("totalDeadline=300ms"), failure.getMessage());
    // The injection either ran and returned within the remainder, or was never started because
    // the remainder had already fallen below the minimum call budget; both prove no fresh grant.
    String message = failure.getMessage();
    assertTrue(
        message.contains("injectionCallReturned=+")
            || message.contains(
                "was not started because the total attachment deadline was reached"),
        message);
    // The inject call was cut (or never started), so no further poll can be granted either.
    verify(nativeApi, times(1)).EIOS_GetClients(anyByte());
    verify(nativeApi, atMost(1)).EIOS_Inject_PID(42);
  }

  @Test
  void pidEnumerationConsumingMostOfTheDeadlineBoundsInjection() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(2L);
    when(nativeApi.EIOS_GetClientPID(anyLong()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(130);
              return 99;
            });
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    // Two 130ms pid reads (~260ms) must leave the injection only the actual ~40ms remainder
    // instead of the stale full 300ms budget (which would run to >= ~560ms).
    assertTrue(
        elapsedMillis < 500,
        "injection must be bounded by the post-enumeration remainder, took "
            + elapsedMillis
            + "ms");
    verify(nativeApi, atMost(1)).EIOS_Inject_PID(42);
  }

  @Test
  void remainingBudgetBelowTheValidationIntervalIsNotExpanded() {
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(290);
              return 0L;
            });
    doAnswer(
            invocation -> {
              Thread.sleep(5_000);
              return null;
            })
        .when(nativeApi)
        .EIOS_Inject_PID(42);
    RemoteInputSessionOptions options =
        timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(250), 8);

    long started = System.nanoTime();
    InputSessionException failure = assertThrows(InputSessionException.class, () -> open(options));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertEquals(InputSessionDiagnostic.INJECTION_TIMEOUT, failure.diagnostic());
    // ~10ms must remain ~10ms: expanding it to the 250ms validation interval would push the
    // total elapsed time to >= ~540ms.
    assertTrue(
        elapsedMillis < 450,
        "a small remaining budget must never be expanded to the validation interval, took "
            + elapsedMillis
            + "ms");
    verify(nativeApi, times(1)).EIOS_GetClients(anyByte());
    verify(nativeApi, atMost(1)).EIOS_Inject_PID(42);
  }

  @Test
  void clientRegisteringJustBeforeTheDeadlineIsStillObserved() {
    AtomicInteger getClientsCalls = new AtomicInteger();
    when(nativeApi.EIOS_GetClients(anyByte()))
        .thenAnswer(
            invocation -> {
              int call = getClientsCalls.getAndIncrement();
              if (call == 0) {
                Thread.sleep(100);
                return 0L;
              }
              // Second poll returns the registered client late but within the total deadline.
              Thread.sleep(150);
              return 1L;
            });
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(42);

    RemoteInputSession session =
        open(timedOptions(Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(20), 8));

    assertEquals(2, getClientsCalls.get(), "the late registration must be observed, not timed out");
    verify(nativeApi, times(1)).EIOS_Inject_PID(42);
    session.close();
    verify(nativeApi).EIOS_ReleaseTarget(new Pointer(1));
  }

  private void configureAlreadyInjected() {
    when(nativeApi.EIOS_GetClients(anyByte())).thenReturn(1L);
    when(nativeApi.EIOS_GetClientPID(0)).thenReturn(42);
  }

  private void configureDimensions(int width, int height) {
    doAnswer(
            invocation -> {
              ((IntByReference) invocation.getArgument(1)).setValue(width);
              ((IntByReference) invocation.getArgument(2)).setValue(height);
              return null;
            })
        .when(nativeApi)
        .EIOS_GetTargetDimensions(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
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
        Duration.ofMillis(500),
        Duration.ofMillis(500),
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

  private static RemoteInputSessionOptions timedOptions(
      Duration injectTimeout,
      Duration nativeCallTimeout,
      Duration validationInterval,
      int maxClients) {
    return new RemoteInputSessionOptions(
        true,
        injectTimeout,
        Duration.ofSeconds(1),
        nativeCallTimeout,
        Duration.ofSeconds(1),
        validationInterval,
        maxClients);
  }
}
