package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputGeometry;
import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.input.InputSessionState;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
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
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Linux target-owned input session bound to the audited SC-1662 RemoteInput ABI. */
public final class RemoteInputSession implements TargetInputSession, TargetFrameSource {
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
  private final FrameFreshnessTracker frameFreshness =
      new FrameFreshnessTracker(Duration.ofSeconds(2), Clock.systemUTC());
  private final Set<MouseButton> heldButtons = EnumSet.noneOf(MouseButton.class);
  private final Set<Integer> heldKeys = new HashSet<>();
  private Point mousePosition;
  private Rectangle currentFrameBounds;
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
    this.currentFrameBounds = new Rectangle(0, 0, width, height);
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

  /**
   * Ensures the selected pid is registered as a RemoteInput client, injecting once if needed.
   *
   * <p>{@code options.injectTimeout()} is the <em>total</em> attachment deadline. The native {@code
   * EIOS_Inject_PID} call is bounded by the time remaining until that deadline (never by a fresh
   * full timeout), exactly one client poll follows a returned injection, and every further poll
   * observes the same deadline, so the wait can overrun the configured budget by at most one
   * bounded native call. Structured stage timestamps are recorded for evidence and reported on both
   * success and timeout; they never include pointers, secrets, or command lines.
   */
  private static void ensureInjectedClient(
      RemoteInputInterface nativeApi,
      NativeCallExecutor calls,
      RemoteInputSessionOptions options,
      ProcessIdentity identity,
      long generation) {
    AttachmentTimeline timeline = new AttachmentTimeline(options.injectTimeout());
    long deadline = timeline.startNanos + options.injectTimeout().toNanos();
    boolean injected = false;
    while (true) {
      timeline.markPoll();
      long count =
          calls.call(
              () -> nativeApi.EIOS_GetClients(RemoteInputInterface.NATIVE_FALSE),
              options.nativeCallTimeout(),
              InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
      validateClientCount(count, options.maxClients(), identity, generation);
      if (selectedClientPresent(nativeApi, calls, count, identity, options, generation)) {
        timeline.markSelectedObserved();
        logger.info(
            "RemoteInput attachment for pid {} observed the injected client: {}",
            identity.pid(),
            timeline.describe());
        return;
      }
      long remainingNanos = deadline - System.nanoTime();
      if (!injected) {
        Duration injectBudget = remainingBudget(remainingNanos, options.validationInterval());
        timeline.markInjectEntered();
        try {
          calls.call(
              () -> {
                nativeApi.EIOS_Inject_PID(Math.toIntExact(identity.pid()));
                return null;
              },
              injectBudget,
              InputSessionDiagnostic.INJECTION_TIMEOUT);
        } catch (InputSessionException injectFailure) {
          timeline.markInjectReturned();
          if (injectFailure.diagnostic() == InputSessionDiagnostic.INJECTION_TIMEOUT) {
            timeline.markDeadlineReached();
          }
          logger.warn(
              "RemoteInput injection call for pid {} failed ({}): {}",
              identity.pid(),
              injectFailure.diagnostic(),
              timeline.describe());
          throw injectFailure;
        }
        timeline.markInjectReturned();
        injected = true;
        // A client that registered while the injection call was running must still be observed:
        // poll once more before the deadline is allowed to fail the attachment.
        continue;
      }
      if (remainingNanos <= 0) {
        timeline.markDeadlineReached();
        logger.warn(
            "RemoteInput attachment for pid {} reached the injection deadline: {}",
            identity.pid(),
            timeline.describe());
        throw failure(
            InputSessionDiagnostic.INJECTION_TIMEOUT,
            identity,
            generation,
            "Injected client for the selected pid did not register before the deadline ("
                + timeline.describe()
                + ")");
      }
      try {
        Thread.sleep(
            Math.min(
                options.validationInterval().toMillis(),
                Math.max(1L, Duration.ofNanos(remainingNanos).toMillis())));
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

  /**
   * Bounds the injection call by the time left before the total deadline while always leaving at
   * least one polling interval so a returned injection can still be observed.
   */
  private static Duration remainingBudget(long remainingNanos, Duration minimum) {
    Duration remaining = Duration.ofNanos(Math.max(0L, remainingNanos));
    return remaining.compareTo(minimum) < 0 ? minimum : remaining;
  }

  /** Monotonic stage timestamps for one attachment attempt, relative to the request. */
  private static final class AttachmentTimeline {
    private final long startNanos = System.nanoTime();
    private final Duration budget;
    private long injectEnteredNanos = -1;
    private long injectReturnedNanos = -1;
    private long firstPollNanos = -1;
    private int polls;
    private long selectedObservedNanos = -1;
    private long deadlineReachedNanos = -1;

    AttachmentTimeline(Duration budget) {
      this.budget = budget;
    }

    void markPoll() {
      polls++;
      if (firstPollNanos < 0) {
        firstPollNanos = System.nanoTime();
      }
    }

    void markInjectEntered() {
      injectEnteredNanos = System.nanoTime();
    }

    void markInjectReturned() {
      injectReturnedNanos = System.nanoTime();
    }

    void markSelectedObserved() {
      selectedObservedNanos = System.nanoTime();
    }

    void markDeadlineReached() {
      deadlineReachedNanos = System.nanoTime();
    }

    String describe() {
      return "attachmentRequested=+0ms"
          + " injectionCallEntered="
          + offset(injectEnteredNanos)
          + " injectionCallReturned="
          + offset(injectReturnedNanos)
          + " firstClientPoll="
          + offset(firstPollNanos)
          + " clientPolls="
          + polls
          + " selectedPidObserved="
          + offset(selectedObservedNanos)
          + " injectionDeadlineReached="
          + offset(deadlineReachedNanos)
          + " totalDeadline="
          + budget.toMillis()
          + "ms";
    }

    private String offset(long nanos) {
      return nanos < 0 ? "n/a" : "+" + Duration.ofNanos(nanos - startNanos).toMillis() + "ms";
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

  /**
   * Copies the current rendered BGRA buffer on the session's serialized native lane.
   *
   * <p>The native mapping has a fixed lifetime and capacity. Dimensions are bounded before the
   * pointer read and checked again afterward; a concurrent canvas resize is rejected instead of
   * returning a wrongly shaped or previous frame. Detach cannot race this method because both are
   * synchronized on the session and all native work uses the same bounded executor.
   */
  @Override
  public synchronized CapturedFrame captureFrame() {
    checkOpen();
    RawFrame raw =
        nativeCall(
            () -> copyNativeFrame(),
            options.nativeCallTimeout(),
            InputSessionDiagnostic.NATIVE_CALL_TIMEOUT);
    checkOpen();
    FrameFreshnessTracker.Observation observation = frameFreshness.observe(raw.bgra());
    currentFrameBounds = new Rectangle(0, 0, raw.width(), raw.height());
    return new CapturedFrame(
        RemoteInputFrameDecoder.decode(raw.bgra(), raw.width(), raw.height()),
        observation.freshness(),
        observation.observedAt(),
        observation.contentChangedAt(),
        identity,
        generation);
  }

  private RawFrame copyNativeFrame() {
    IntByReference widthBefore = new IntByReference();
    IntByReference heightBefore = new IntByReference();
    nativeApi.EIOS_GetTargetDimensions(target, widthBefore, heightBefore);
    int byteCount;
    try {
      byteCount =
          RemoteInputFrameDecoder.checkedByteCount(widthBefore.getValue(), heightBefore.getValue());
    } catch (ArithmeticException | IllegalArgumentException invalid) {
      throw failure(
          InputSessionDiagnostic.INVALID_FRAME_DIMENSIONS,
          identity,
          generation,
          "Native frame dimensions were outside the supported bound");
    }

    Pointer imageBuffer = nativeApi.EIOS_GetImageBuffer(target);
    if (imageBuffer == null || Pointer.nativeValue(imageBuffer) == 0) {
      throw failure(
          InputSessionDiagnostic.IMAGE_BUFFER_UNAVAILABLE,
          identity,
          generation,
          "Native image buffer was null or zero");
    }
    byte[] bgra = imageBuffer.getByteArray(0, byteCount);

    IntByReference widthAfter = new IntByReference();
    IntByReference heightAfter = new IntByReference();
    nativeApi.EIOS_GetTargetDimensions(target, widthAfter, heightAfter);
    if (widthBefore.getValue() != widthAfter.getValue()
        || heightBefore.getValue() != heightAfter.getValue()) {
      throw failure(
          InputSessionDiagnostic.FRAME_RESIZED_DURING_CAPTURE,
          identity,
          generation,
          "Target dimensions changed while copying the rendered frame");
    }
    return new RawFrame(widthBefore.getValue(), heightBefore.getValue(), bgra);
  }

  private record RawFrame(int width, int height, byte[] bgra) {}

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
  public synchronized Rectangle targetBounds() {
    checkOpen();
    return new Rectangle(currentFrameBounds);
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
