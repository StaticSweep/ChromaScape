package com.chromascape.utils.core.input;

import com.chromascape.utils.core.input.remoteinput.MouseButton;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * A serialized, lifecycle-fenced input session bound to one exact process identity.
 *
 * <p>All public movement coordinates are <b>client-relative</b> pixels inside {@link
 * #getTargetBounds()}: the origin is the client area's top-left corner, matching the coordinates
 * both backends consume (RemoteInput's {@code EIOS_*} family and KInput's posted AWT events).
 * Callers translate screen coordinates to client coordinates exactly once, before calling this
 * interface.
 *
 * <p>Every operation revalidates the selection generation and the process identity, so a stale or
 * replaced target can never receive input.
 */
public interface TargetInputSession extends AutoCloseable {
  /** Returns the exact process identity this session is bound to. */
  ProcessIdentity identity();

  /** Returns the selection generation this session was opened against. */
  long selectionGeneration();

  /** Returns the client-relative movement bounds (origin {@code (0, 0)}). */
  Rectangle getTargetBounds();

  /** Returns the last known client-relative mouse position. */
  Point getMousePosition();

  /**
   * Moves the mouse to a client-relative point.
   *
   * @param clientPoint the client-relative destination
   * @throws IllegalArgumentException when the point is outside the target bounds
   */
  void moveMouse(Point clientPoint);

  /** Presses and holds a mouse button at the current position. */
  void holdMouse(MouseButton button);

  /** Releases a held mouse button at the current position. */
  void releaseMouse(MouseButton button);

  /** Returns whether the given mouse button is currently held by this session. */
  boolean isMouseHeld(MouseButton button);

  /**
   * Scrolls the mouse wheel at the current position.
   *
   * @param notches the number of notches; positive scrolls down
   * @throws InputSessionException with {@link InputSessionDiagnostic#INPUT_UNSUPPORTED} when the
   *     backend cannot express wheel input
   */
  void scrollMouse(int notches);

  /** Presses and holds a key. */
  void holdKey(int javaKeyCode);

  /** Releases a held key. */
  void releaseKey(int javaKeyCode);

  /** Returns whether the given key is currently held by this session. */
  boolean isKeyHeld(int javaKeyCode);

  /**
   * Types a string, honoring the typed-key and modifier delays.
   *
   * @param text the text to type
   * @param keyWaitMillis milliseconds a pressed key stays down; must not be negative
   * @param modifierWaitMillis milliseconds to wait after a key release; must not be negative
   */
  void sendString(String text, int keyWaitMillis, int modifierWaitMillis);

  /** Returns whether the session is attached and usable. */
  boolean isOpen();

  /**
   * Closes the session, releasing every held input and the native target. Idempotent; a poisoned
   * session stays poisoned and retains its attachment lease.
   */
  @Override
  void close();
}
