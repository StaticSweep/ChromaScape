package com.chromascape.utils.core.input.remoteinput;

/** Mouse buttons with explicit native ABI values. */
public enum MouseButton {
  /** The left button (native value {@code 1}, KInput {@code MouseButton.LEFT}). */
  LEFT(1),
  /** The middle button (native value {@code 2}, KInput {@code MouseButton.MIDDLE}). */
  MIDDLE(2),
  /** The right button (native value {@code 3}, KInput {@code MouseButton.RIGHT}). */
  RIGHT(3);

  private final int nativeValue;

  MouseButton(int nativeValue) {
    this.nativeValue = nativeValue;
  }

  /** Returns the button id used by both native backends. */
  public int nativeValue() {
    return nativeValue;
  }
}
