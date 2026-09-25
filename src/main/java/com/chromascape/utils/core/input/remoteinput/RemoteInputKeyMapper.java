package com.chromascape.utils.core.input.remoteinput;

/**
 * Converts Java/AWT key codes to the pinned RemoteInput native key domain.
 *
 * <p>The pinned RemoteInput plugin consumes <b>native (Windows-style VK) key codes</b> and converts
 * them itself: {@code InputOutput.cxx} classifies {@code control_keys} against VK values (for
 * example {@code VK_RETURN = 0x0D}, {@code VK_LEFT_CONTROL = 0xA2}, {@code VK_LEFT_ALT = 0xA4}) and
 * posts AWT {@code KeyEvent}s through {@code GetJavaKeyCode(native_key_code)} (whose table maps
 * {@code 0x0D → 0x0A}, {@code 0xA2 → 0x0011}, {@code 0xA4 → 0x0012}). AWT key codes therefore must
 * be translated at this boundary, before crossing {@code EIOS_HoldKey}/{@code EIOS_ReleaseKey}/
 * {@code EIOS_IsKeyHeld}.
 */
public final class RemoteInputKeyMapper {

  private RemoteInputKeyMapper() {}

  /**
   * Maps a Java/AWT {@link java.awt.event.KeyEvent} key code to the pinned native key code.
   *
   * @param javaKeyCode the AWT key code
   * @return the native key code consumed by the RemoteInput plugin
   */
  public static int toNativeKeyCode(int javaKeyCode) {
    return switch (javaKeyCode) {
      case 10 -> 13; // VK_ENTER -> VK_RETURN
      case 17 -> 162; // VK_CONTROL -> VK_LEFT_CONTROL
      case 18 -> 164; // VK_ALT -> VK_LEFT_ALT
      case 65406 -> 165; // VK_ALT_GRAPH -> VK_ALT_GR (native posts AWT ALT)
      case 157 -> 91; // VK_META -> VK_LWIN (native posts AWT VK_WINDOWS)
      default -> javaKeyCode; // identity for the common VK-aligned AWT codes
    };
  }
}
