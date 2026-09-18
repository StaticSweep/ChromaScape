package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputGeometry;
import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.input.InputSessionState;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.ProcessManagerFactory;
import com.chromascape.utils.core.screen.window.TargetDiscoveryService;
import com.chromascape.utils.core.screen.window.TargetSelectionDiagnostic;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Linux target-owned input session bound to the audited SC-1662 RemoteInput ABI. */
public final class RemoteInputSession implements TargetInputSession {
  private static final Logger logger = LogManager.getLogger(RemoteInputSession.class);

  private final RemoteInputInterface nativeApi;
  private final Pointer target;
  private final ProcessIdentity identity;
  private final long generation;
  private final WindowSelectionService selectionService;
  private final ProcessManager processManager;
  private final RemoteInputSessionOptions options;
  private final NativeCallExecutor calls;
  private final RemoteInputAttachmentCoordinator.Lease lease;
  private final InputGeometry geometry;
  private final Set<MouseButton> heldButtons = EnumSet.noneOf(MouseButton.class);
  private final Set<Integer> heldKeys = new HashSet<>();
  private Point mousePosition;
  private InputSessionState state = InputSessionState.ATTACHED;
  private boolean targetReleased;

  private RemoteInputSession(
      RemoteInputInterface nativeApi,
      Pointer target,
      ProcessIdentity identity,
      long generation,
      WindowSelectionService selectionService,
      ProcessManager processManager,
      RemoteInputSessionOptions options,
      NativeCallExecutor calls,
      RemoteInputAttachmentCoordinator.Lease lease,
      int width,
      int height,
      Point mousePosition) {
    this.nativeApi = nativeApi;
    this.target = target;
    this.identity = identity;
    this.generation = generation;
    this.selectionService = selectionService;
    this.processManager = processManager;
    this.options = options;
    this.calls = calls;
    this.lease = lease;
    this.geometry = InputGeometry.clientRelative(width, height);
    this.mousePosition = new Point(mousePosition);
  }

  /** Opens a session after exact target resolution, validation, and lease acquisition. */
  public static RemoteInputSession open(
      TargetDiscoveryService discovery,
      WindowSelectionService selectionService,
      ProcessManagerFactory processManagerFactory,
      RemoteInputInterface nativeApi,
      RemoteInputAttachmentCoordinator coordinator,
      RemoteInputSessionOptions options) {
    if (!options.attachmentEnabled()) {
      throw failure(
          InputSessionDiagnostic.ATTACHMENT_DISABLED,
          null,
          selectionService.getSelectedGeneration(),
          "RemoteInput attachment is disabled");
    }
    if (!selectionService.hasSelection()) {
      throw failure(
          InputSessionDiagnostic.NO_SELECTION,
          null,
          selectionService.getSelectedGeneration(),
          "An explicit target selection is required");
    }

    long generation = selectionService.getSelectedGeneration();
    TargetDiscoveryService.TargetResolution resolution = discovery.resolveSelectedTarget();
    if (!resolution.isResolved()) {
      InputSessionDiagnostic diagnostic = resolutionDiagnostic(resolution);
      throw failure(
          diagnostic,
          selectionService.getSelectedIdentity(),
          generation,
          "Selected target was not resolved");
    }

    ProcessIdentity identity = resolution.target().orElseThrow().identity();
    if (identity == null) {
      throw failure(
          InputSessionDiagnostic.IDENTITY_UNAVAILABLE,
          null,
          generation,
          "Target identity is unavailable");
    }
    if (!identity.isLocal()) {
      throw failure(InputSessionDiagnostic.NOT_LOCAL, identity, generation, "Target is not local");
    }
    if (nativeApi == null) {
      throw failure(
          InputSessionDiagnostic.LOAD_FAILED,
          identity,
          generation,
          "RemoteInput binding is unavailable");
    }

    ProcessManager processManager = processManagerFactory.getProcessManager();
    SelectionFencing.requireValid(selectionService, processManager, identity, generation);
    RemoteInputAttachmentCoordinator.Lease lease = coordinator.acquire(identity);
    NativeCallExecutor calls = new NativeCallExecutor(identity, generation);
    Pointer target = null;
    boolean targetReleased = false;
    try {
      ensureInjectedClient(nativeApi, calls, options, identity, generation);
      SelectionFencing.requireValid(selectionService, processManager, identity, generation);
      target =
          calls.call(
              () -> nativeApi.EIOS_RequestTarget(Long.toString(identity.pid())),
              options.pairTimeout(),
              InputSessionDiagnostic.PAIR_TIMEOUT);
      if (target == null || Pointer.nativeValue(target) == 0) {
        throw failure(
            InputSessionDiagnostic.INVALID_TARGET_HANDLE,
            identity,
            generation,
            "Native target handle was null or zero");
      }

      SelectionFencing.requireValid(selectionService, processManager, identity, generation);
      Pointer pairedTarget = target;
      IntByReference width = new IntByReference();
      IntByReference height = new IntByReference();
      calls.call(
          () -> {
            nativeApi.EIOS_GetTargetDimensions(pairedTarget, width, height);
            return null;
          },
          options.nativeCallTimeout(),
          InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
      if (width.getValue() <= 0 || height.getValue() <= 0) {
        throw failure(
            InputSessionDiagnostic.NATIVE_CALL_FAILED,
            identity,
            generation,
            "Native target dimensions were invalid");
      }

      IntByReference mouseX = new IntByReference();
      IntByReference mouseY = new IntByReference();
      calls.call(
          () -> {
            nativeApi.EIOS_GetMousePosition(pairedTarget, mouseX, mouseY);
            return null;
          },
          options.nativeCallTimeout(),
          InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
      return new RemoteInputSession(
          nativeApi,
          target,
          identity,
          generation,
          selectionService,
          processManager,
          options,
          calls,
          lease,
          width.getValue(),
          height.getValue(),
          new Point(mouseX.getValue(), mouseY.getValue()));
    } catch (RuntimeException openFailure) {
      boolean poisoned = calls.isPoisoned();
      if (target != null && Pointer.nativeValue(target) != 0 && !poisoned) {
        Pointer failedTarget = target;
        try {
          calls.call(
              () -> {
                nativeApi.EIOS_ReleaseTarget(failedTarget);
                return null;
              },
              options.releaseTimeout(),
              InputSessionDiagnostic.RELEASE_TIMEOUT);
          targetReleased = true;
        } catch (RuntimeException cleanupFailure) {
          openFailure.addSuppressed(cleanupFailure);
          poisoned = calls.isPoisoned();
        }
      }
      calls.close();
      if (!poisoned && (target == null || targetReleased)) {
        lease.close();
      }
      throw openFailure;
    }
  }

  private static InputSessionDiagnostic resolutionDiagnostic(
      TargetDiscoveryService.TargetResolution resolution) {
    if (resolution.diagnostic() == null) {
      return InputSessionDiagnostic.STALE_SELECTION;
    }
    if (resolution.diagnostic() == TargetSelectionDiagnostic.AMBIGUOUS_TARGET) {
      return InputSessionDiagnostic.AMBIGUOUS_TARGET;
    }
    if (resolution.diagnostic() == TargetSelectionDiagnostic.STALE_SELECTION) {
      return InputSessionDiagnostic.STALE_SELECTION;
    }
    if (resolution.diagnostic() == TargetSelectionDiagnostic.NO_ELIGIBLE_TARGET) {
      return InputSessionDiagnostic.TARGET_EXITED;
    }
    return InputSessionDiagnostic.UNSUPPORTED_PLATFORM;
  }

  private static void ensureInjectedClient(
      RemoteInputInterface nativeApi,
      NativeCallExecutor calls,
      RemoteInputSessionOptions options,
      ProcessIdentity identity,
      long generation) {
    long deadline = System.nanoTime() + options.injectTimeout().toNanos();
    boolean injected = false;
    while (true) {
      long count =
          calls.call(
              () -> nativeApi.EIOS_GetClients(RemoteInputInterface.NATIVE_FALSE),
              options.nativeCallTimeout(),
              InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
      validateClientCount(count, options.maxClients(), identity, generation);
      if (selectedClientPresent(nativeApi, calls, count, identity, options, generation)) {
        return;
      }
      if (!injected) {
        calls.call(
            () -> {
              nativeApi.EIOS_Inject_PID(Math.toIntExact(identity.pid()));
              return null;
            },
            options.injectTimeout(),
            InputSessionDiagnostic.INJECTION_TIMEOUT);
        injected = true;
      }
      if (System.nanoTime() >= deadline) {
        throw failure(
            InputSessionDiagnostic.INJECTION_TIMEOUT,
            identity,
            generation,
            "Injected client for the selected pid did not register before the deadline");
      }
      try {
        Thread.sleep(options.validationInterval().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InputSessionException(
            InputSessionDiagnostic.INJECTION_TIMEOUT,
            identity,
            generation,
            "Attachment interrupted while waiting for the injected client",
            e);
      }
    }
  }

  private static boolean selectedClientPresent(
      RemoteInputInterface nativeApi,
      NativeCallExecutor calls,
      long count,
      ProcessIdentity identity,
      RemoteInputSessionOptions options,
      long generation) {
    for (long index = 0; index < count; index++) {
      long clientIndex = index;
      int pid =
          calls.call(
              () -> nativeApi.EIOS_GetClientPID(clientIndex),
              options.nativeCallTimeout(),
              InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
      if (pid <= 0) {
        throw failure(
            InputSessionDiagnostic.CLIENT_ENUMERATION_INVALID,
            identity,
            generation,
            "Native client enumeration returned an invalid pid");
      }
      if (pid == identity.pid()) {
        return true;
      }
    }
    return false;
  }

  private static void validateClientCount(
      long count, int max, ProcessIdentity identity, long generation) {
    if (count < 0 || count > max) {
      throw failure(
          InputSessionDiagnostic.CLIENT_ENUMERATION_INVALID,
          identity,
          generation,
          "Native client count was outside the configured bound");
    }
  }

  private static InputSessionException failure(
      InputSessionDiagnostic diagnostic, ProcessIdentity identity, long generation, String detail) {
    return new InputSessionException(diagnostic, identity, generation, detail);
  }

  private void checkOpen() {
    if (calls.isPoisoned()) {
      state = InputSessionState.POISONED;
    }
    if (state == InputSessionState.POISONED) {
      throw failure(
          InputSessionDiagnostic.SESSION_POISONED, identity, generation, "Session is poisoned");
    }
    if (state != InputSessionState.ATTACHED) {
      throw failure(
          InputSessionDiagnostic.SESSION_CLOSED, identity, generation, "Session is not attached");
    }
    SelectionFencing.requireValid(selectionService, processManager, identity, generation);
  }

  private <T> T nativeCall(
      Callable<T> operation, Duration timeout, InputSessionDiagnostic timeoutDiagnostic) {
    try {
      return calls.call(operation, timeout, timeoutDiagnostic);
    } catch (InputSessionException callFailure) {
      if (calls.isPoisoned()) {
        state = InputSessionState.POISONED;
      }
      throw callFailure;
    }
  }

  private void nativeAction(Runnable action) {
    checkOpen();
    nativeCall(
        () -> {
          action.run();
          return null;
        },
        options.nativeCallTimeout(),
        InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
  }

  @Override
  public synchronized ProcessIdentity identity() {
    return identity;
  }

  @Override
  public synchronized long selectionGeneration() {
    return generation;
  }

  @Override
  public synchronized Rectangle getTargetBounds() {
    checkOpen();
    return new Rectangle(geometry.publicMovementBounds());
  }

  @Override
  public synchronized Point getMousePosition() {
    checkOpen();
    return new Point(mousePosition);
  }

  @Override
  public synchronized void moveMouse(Point clientPoint) {
    checkOpen();
    Point point = geometry.toClientPoint(clientPoint);
    nativeAction(() -> nativeApi.EIOS_MoveMouse(target, point.x, point.y));
    mousePosition = point;
  }

  @Override
  public synchronized void holdMouse(MouseButton button) {
    checkOpen();
    nativeAction(
        () ->
            nativeApi.EIOS_HoldMouse(
                target, mousePosition.x, mousePosition.y, button.nativeValue()));
    heldButtons.add(button);
  }

  @Override
  public synchronized void releaseMouse(MouseButton button) {
    checkOpen();
    nativeAction(
        () ->
            nativeApi.EIOS_ReleaseMouse(
                target, mousePosition.x, mousePosition.y, button.nativeValue()));
    heldButtons.remove(button);
  }

  @Override
  public synchronized boolean isMouseHeld(MouseButton button) {
    checkOpen();
    byte held =
        nativeCall(
            () -> nativeApi.EIOS_IsMouseButtonHeld(target, button.nativeValue()),
            options.nativeCallTimeout(),
            InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
    return RemoteInputInterface.fromNativeBoolean(held);
  }

  @Override
  public synchronized void scrollMouse(int notches) {
    checkOpen();
    nativeAction(
        () -> nativeApi.EIOS_ScrollMouse(target, mousePosition.x, mousePosition.y, notches));
  }

  @Override
  public synchronized void holdKey(int javaKeyCode) {
    checkOpen();
    int nativeCode = RemoteInputKeyMapper.toNativeKeyCode(javaKeyCode);
    nativeAction(() -> nativeApi.EIOS_HoldKey(target, nativeCode));
    heldKeys.add(javaKeyCode);
  }

  @Override
  public synchronized void releaseKey(int javaKeyCode) {
    checkOpen();
    int nativeCode = RemoteInputKeyMapper.toNativeKeyCode(javaKeyCode);
    nativeAction(() -> nativeApi.EIOS_ReleaseKey(target, nativeCode));
    heldKeys.remove(javaKeyCode);
  }

  @Override
  public synchronized boolean isKeyHeld(int javaKeyCode) {
    checkOpen();
    int nativeCode = RemoteInputKeyMapper.toNativeKeyCode(javaKeyCode);
    byte held =
        nativeCall(
            () -> nativeApi.EIOS_IsKeyHeld(target, nativeCode),
            options.nativeCallTimeout(),
            InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
    return RemoteInputInterface.fromNativeBoolean(held);
  }

  @Override
  public synchronized void sendString(String text, int keyWaitMillis, int modifierWaitMillis) {
    if (keyWaitMillis < 0 || modifierWaitMillis < 0) {
      throw new IllegalArgumentException("Delays must not be negative");
    }
    nativeAction(() -> nativeApi.EIOS_SendString(target, text, keyWaitMillis, modifierWaitMillis));
  }

  @Override
  public synchronized boolean isOpen() {
    return state == InputSessionState.ATTACHED && !calls.isPoisoned();
  }

  InputSessionState state() {
    return state;
  }

  @Override
  public synchronized void close() {
    if (state == InputSessionState.CLOSED
        || state == InputSessionState.CLOSING
        || state == InputSessionState.POISONED) {
      return;
    }
    if (calls.isPoisoned()) {
      state = InputSessionState.POISONED;
      retainPoisonedLease();
      return;
    }

    state = InputSessionState.CLOSING;
    RuntimeException releaseFailure = releaseHeldInputs();
    if (calls.isPoisoned()) {
      state = InputSessionState.POISONED;
      retainPoisonedLease();
      return;
    }
    if (releaseFailure != null) {
      state = InputSessionState.FAILED;
      throw releaseFailure;
    }

    if (!targetReleased) {
      try {
        nativeCall(
            () -> {
              nativeApi.EIOS_ReleaseTarget(target);
              return null;
            },
            options.releaseTimeout(),
            InputSessionDiagnostic.RELEASE_TIMEOUT);
        targetReleased = true;
      } catch (RuntimeException failure) {
        if (calls.isPoisoned()) {
          state = InputSessionState.POISONED;
          retainPoisonedLease();
          return;
        }
        state = InputSessionState.FAILED;
        throw failure;
      }
    }

    calls.close();
    lease.close();
    state = InputSessionState.CLOSED;
  }

  private RuntimeException releaseHeldInputs() {
    RuntimeException firstFailure = null;
    for (int key : Set.copyOf(heldKeys)) {
      try {
        int nativeCode = RemoteInputKeyMapper.toNativeKeyCode(key);
        nativeCall(
            () -> {
              nativeApi.EIOS_ReleaseKey(target, nativeCode);
              return null;
            },
            options.releaseTimeout(),
            InputSessionDiagnostic.RELEASE_TIMEOUT);
        heldKeys.remove(key);
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    for (MouseButton button : Set.copyOf(heldButtons)) {
      try {
        nativeCall(
            () -> {
              nativeApi.EIOS_ReleaseMouse(
                  target, mousePosition.x, mousePosition.y, button.nativeValue());
              return null;
            },
            options.releaseTimeout(),
            InputSessionDiagnostic.RELEASE_TIMEOUT);
        heldButtons.remove(button);
      } catch (RuntimeException failure) {
        if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    }
    return firstFailure;
  }

  private void retainPoisonedLease() {
    logger.warn(
        "RemoteInput session lane timed out; attachment lease retained for pid {}", identity.pid());
  }
}
