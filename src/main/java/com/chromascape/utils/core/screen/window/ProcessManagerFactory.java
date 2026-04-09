package com.chromascape.utils.core.screen.window;

/**
 * Factory class to return the OS specific implementation of a {@link ProcessManager}. Detects OS by
 * OS-name and returns the native implementor of the PM interface.
 */
public class ProcessManagerFactory {

  /**
   * A factory that creates an OS specific {@link ProcessManager}.
   *
   * @return The OS specific ProcessManager implementor, used to extract Process ID.
   */
  public static ProcessManager getProcessManager() {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("win")) {
      return new WindowsProcessManager();
    }
    if (os.contains("mac")) {
      return new MacProcessManager();
    }
    return new LinuxProcessManager();
  }
}
