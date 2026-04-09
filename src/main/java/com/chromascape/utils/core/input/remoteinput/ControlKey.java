package com.chromascape.utils.core.input.remoteinput;

/**
 * Key value pair enum containing keys and their Java key code. Used to define the preferred Java
 * keycode for RemoteInput. e.g. enter is typically 10, we want 13.
 */
public enum ControlKey {
  VK_LEFT_CONTROL(162),
  VK_LEFT_ALT(164),
  VK_RIGHT_ALT(165),
  VK_LEFT_WINDOWS(91),
  VK_RETURN(13);

  public final int nativeCode;

  /**
   * Constructs the enum with an extra value, which contains the java keycode.
   *
   * @param nativeCode the Java keycode
   */
  ControlKey(int nativeCode) {
    this.nativeCode = nativeCode;
  }
}
