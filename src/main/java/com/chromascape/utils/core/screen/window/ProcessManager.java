package com.chromascape.utils.core.screen.window;

/**
 * An interface to be implemented by Windows, Mac and Linux implementors. The sole responsibility of
 * each implementor is to provide an OS native method to return the Process ID of RuneLite.
 */
public interface ProcessManager {
  /**
   * To provide an OS native way of grabbing and returning the Process ID of RuneLite. This is to be
   * used by RemoteInput.
   *
   * @return An integer Process ID
   */
  int getPid();
}
