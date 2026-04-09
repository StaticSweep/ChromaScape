package com.chromascape.utils.core.screen.window;

/**
 * A class whose sole responsibility is to provide a native Linux implementation to return the
 * process ID of RuneLite.
 */
public class LinuxProcessManager implements ProcessManager {

  /**
   * To provide a linux native way of grabbing and returning the Process ID of RuneLite. This is to
   * be used by RemoteInput.
   *
   * @return An integer Process ID
   */
  @Override
  public int getPid() {
    return -1;
  }
}
