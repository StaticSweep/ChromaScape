package com.chromascape.utils.core.screen.window;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for verifying process information using Win32 APIs.
 *
 * <p>Used to distinguish between standalone Java game clients (RuneLite, Leeuwbot, VitaLite) and
 * IDE-embedded Java processes that should be excluded from window detection.
 */
public class ProcessVerificationUtil {

  private static final Logger logger = LoggerFactory.getLogger(ProcessVerificationUtil.class);
  private static final int PROCESS_QUERY_INFORMATION = 0x0400;
  private static final int PROCESS_VM_READ = 0x0010;

  /** JNA interface for Kernel32 process management functions. */
  public interface Kernel32 extends StdCallLibrary {
    Kernel32 INSTANCE = Native.load("Kernel32", Kernel32.class);

    /**
     * Opens an existing local process object.
     *
     * @param dwDesiredAccess The access to the process object
     * @param bInheritHandle If true, processes created by this process will inherit the handle
     * @param dwProcessId The identifier of the local process to be opened
     * @return Handle to the specified process, or null on failure
     */
    HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);

    /**
     * Closes an open object handle.
     *
     * @param hHandle A valid handle to an open object
     * @return true if the function succeeds, false otherwise
     */
    boolean CloseHandle(HANDLE hHandle);
  }

  /** JNA interface for Psapi (Process Status API) functions. */
  public interface Psapi extends StdCallLibrary {
    Psapi INSTANCE = Native.load("psapi", Psapi.class);

    /**
     * Retrieves the fully qualified path for the file containing the specified module.
     *
     * @param hProcess A handle to the process
     * @param hModule A handle to the module (null for the process itself)
     * @param lpFilename Buffer to receive the file path
     * @param nSize Size of the buffer in characters
     * @return Number of characters copied to the buffer, or 0 on failure
     */
    int GetModuleFileNameExA(HANDLE hProcess, HMODULE hModule, byte[] lpFilename, int nSize);
  }

  /**
   * Gets the executable path for a given process ID.
   *
   * @param pid Process ID to query
   * @return Full path to the executable, or null if unable to determine
   */
  public static String getProcessExecutablePath(int pid) {
    HANDLE hProcess =
        Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid);

    if (hProcess == null || Pointer.nativeValue(hProcess.getPointer()) == 0) {
      logger.debug("Could not open process handle for PID: {}", pid);
      return null;
    }

    try {
      byte[] exePath = new byte[260]; // MAX_PATH on Windows
      int result = Psapi.INSTANCE.GetModuleFileNameExA(hProcess, null, exePath, exePath.length);

      if (result > 0) {
        return Native.toString(exePath).trim();
      }

      logger.debug("GetModuleFileNameExA returned 0 for PID: {}", pid);
      return null;
    } finally {
      Kernel32.INSTANCE.CloseHandle(hProcess);
    }
  }

  /**
   * Checks if a process is a standalone Java JAR process (not running from an IDE).
   *
   * <p>Works with:
   *
   * <ul>
   *   <li>System Java: C:\Program Files\Java\jdk-xx\bin\javaw.exe
   *   <li>Bundled Java: C:\Users\xxx\RuneLite\jre\bin\javaw.exe (RuneLite/Leeuwbot/VitaLite)
   * </ul>
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>IDE Java: C:\Program Files\JetBrains\IntelliJ IDEA\jbr\bin\java.exe
   * </ul>
   *
   * @param pid Process ID to check
   * @return true if the process is a standalone Java executable, false otherwise
   */
  public static boolean isStandaloneJavaProcess(int pid) {
    String execPath = getProcessExecutablePath(pid);

    if (execPath == null) {
      logger.debug("Could not get executable path for PID: {}", pid);
      return false;
    }

    String pathLower = execPath.toLowerCase();

    // Must be java.exe or javaw.exe
    if (!pathLower.endsWith("java.exe") && !pathLower.endsWith("javaw.exe")) {
      logger.debug("Not a Java process (PID: {}): {}", pid, execPath);
      return false;
    }

    // Exclude IDE-embedded Java (IntelliJ, Eclipse, VS Code, NetBeans, Android Studio)
    if (pathLower.contains("jetbrains")
        || pathLower.contains("intellij")
        || pathLower.contains("idea")
        || pathLower.contains("eclipse")
        || pathLower.contains("vscode")
        || pathLower.contains("visual studio code")
        || pathLower.contains("netbeans")
        || pathLower.contains("android studio")) {
      logger.debug("Excluding IDE Java process (PID: {}): {}", pid, execPath);
      return false;
    }

    logger.debug("Valid standalone Java process (PID: {}): {}", pid, execPath);
    return true;
  }
}
