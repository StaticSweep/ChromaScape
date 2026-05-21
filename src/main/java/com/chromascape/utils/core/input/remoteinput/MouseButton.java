package com.chromascape.utils.core.input.remoteinput;

/**
 * Enum containing the available mouse buttons for use with RemoteInput. Namely, the functions
 * {@link RemoteInput#holdMouse(MouseButton)}, {@link RemoteInput#releaseMouse(MouseButton)}, and
 * {@link RemoteInput#isMouseHeld(MouseButton)}. The ordinal of each mouse button refers to the
 * integer value required by RI.
 */
public enum MouseButton {
  right,
  left,
  middle
}
