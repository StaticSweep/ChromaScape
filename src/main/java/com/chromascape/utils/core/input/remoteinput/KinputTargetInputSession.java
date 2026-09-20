package com.chromascape.utils.core.input.remoteinput;

import com.chromascape.utils.core.input.InputGeometry;
import com.chromascape.utils.core.input.InputSessionDiagnostic;
import com.chromascape.utils.core.input.InputSessionException;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import com.chromascape.utils.core.screen.window.ProcessManager;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Windows KInput adapter over the neutral, selection-fenced input-session contract. */
public final class KinputTargetInputSession implements TargetInputSession {
  private static final int KEY_PRESSED = 401;
  private static final int KEY_RELEASED = 402;
  private static final int MOUSE_PRESSED = 501;
  private static final int MOUSE_RELEASED = 502;

  private final KinputBackend backend;
  private final ProcessIdentity identity;
  private final long generation;
  private final WindowSelectionService selectionService;
  private final ProcessManager processManager;
  private final AutoCloseable lease;
  private final InputGeometry geometry;
  private final Point mouse = new Point();
  private final Set<MouseButton> heldButtons = EnumSet.noneOf(MouseButton.class);
  private final Set<Integer> heldKeys = new HashSet<>();
  private boolean open = true;
  private boolean closing;

  /** Creates a Windows session whose coordinates are client-relative. */
  public KinputTargetInputSession(
      KinputBackend backend,
      ProcessIdentity identity,
      long generation,
      int width,
      int height,
      WindowSelectionService selectionService,
      ProcessManager processManager,
      AutoCloseable lease) {
    this.backend = backend;
    this.identity = identity;
    this.generation = generation;
    this.selectionService = selectionService;
    this.processManager = processManager;
    this.lease = lease;
    this.geometry = InputGeometry.clientRelative(width, height);
  }

  private void checkOpen() {
    if (!open || closing) {
      throw new InputSessionException(
          InputSessionDiagnostic.SESSION_CLOSED, identity, generation, "Input session is closed");
    }
    if (selectionService != null) {
      SelectionFencing.requireValid(selectionService, processManager, identity, generation);
    }
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
    return new Point(mouse);
  }

  @Override
  public synchronized void moveMouse(Point clientPoint) {
    checkOpen();
    Point point = geometry.toClientPoint(clientPoint);
    backend.moveMouse(point.x, point.y);
    mouse.setLocation(point);
  }

  @Override
  public synchronized void holdMouse(MouseButton button) {
    checkOpen();
    backend.mouseInput(mouse.x, mouse.y, MOUSE_PRESSED, kinputButton(button));
    heldButtons.add(button);
  }

  @Override
  public synchronized void releaseMouse(MouseButton button) {
    checkOpen();
    backend.mouseInput(mouse.x, mouse.y, MOUSE_RELEASED, kinputButton(button));
    heldButtons.remove(button);
  }

  @Override
  public synchronized boolean isMouseHeld(MouseButton button) {
    checkOpen();
    return heldButtons.contains(button);
  }

  @Override
  public synchronized void scrollMouse(int notches) {
    checkOpen();
    throw new InputSessionException(
        InputSessionDiagnostic.INPUT_UNSUPPORTED,
        identity,
        generation,
        "KInput cannot express wheel rotation");
  }

  @Override
  public synchronized void holdKey(int javaKeyCode) {
    checkOpen();
    backend.sendModifierKey(KEY_PRESSED, "key", javaKeyCode);
    heldKeys.add(javaKeyCode);
  }

  @Override
  public synchronized void releaseKey(int javaKeyCode) {
    checkOpen();
    backend.sendModifierKey(KEY_RELEASED, "key", javaKeyCode);
    heldKeys.remove(javaKeyCode);
  }

  @Override
  public synchronized boolean isKeyHeld(int javaKeyCode) {
    checkOpen();
    return heldKeys.contains(javaKeyCode);
  }

  @Override
  public synchronized void sendString(String text, int keyWaitMillis, int modifierWaitMillis) {
    checkOpen();
    if (keyWaitMillis < 0 || modifierWaitMillis < 0) {
      throw new IllegalArgumentException("Delays must not be negative");
    }
    for (char character : text.toCharArray()) {
      backend.sendKeyEvent(KEY_PRESSED, character);
      sleep(keyWaitMillis);
      backend.sendKeyEvent(KEY_RELEASED, character);
      sleep(modifierWaitMillis);
    }
  }

  private void sleep(long millis) {
    if (millis == 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InputSessionException(
          InputSessionDiagnostic.NATIVE_CALL_FAILED,
          identity,
          generation,
          "String input was interrupted",
          e);
    }
  }

  private static Kinput.MouseButton kinputButton(MouseButton button) {
    return switch (button) {
      case LEFT -> Kinput.MouseButton.LEFT;
      case MIDDLE -> Kinput.MouseButton.MIDDLE;
      case RIGHT -> Kinput.MouseButton.RIGHT;
    };
  }

  @Override
  public synchronized boolean isOpen() {
    return open && !closing;
  }

  @Override
  public synchronized void close() {
    if (!open) {
      return;
    }
    closing = true;
    RuntimeException firstFailure = null;
    for (int key : Set.copyOf(heldKeys)) {
      try {
        backend.sendModifierKey(KEY_RELEASED, "key", key);
        heldKeys.remove(key);
      } catch (RuntimeException failure) {
        firstFailure = firstFailure == null ? failure : firstFailure;
      }
    }
    for (MouseButton button : Set.copyOf(heldButtons)) {
      try {
        backend.mouseInput(mouse.x, mouse.y, MOUSE_RELEASED, kinputButton(button));
        heldButtons.remove(button);
      } catch (RuntimeException failure) {
        firstFailure = firstFailure == null ? failure : firstFailure;
      }
    }
    boolean destroyed = false;
    try {
      backend.destroy();
      destroyed = true;
    } catch (RuntimeException failure) {
      firstFailure = firstFailure == null ? failure : firstFailure;
    }
    if (destroyed) {
      try {
        lease.close();
      } catch (Exception failure) {
        if (firstFailure == null) {
          firstFailure =
              new IllegalStateException("Failed to release KInput attachment lease", failure);
        }
      }
      open = false;
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }
}
