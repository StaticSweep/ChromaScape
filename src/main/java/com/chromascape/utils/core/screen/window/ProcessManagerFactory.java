package com.chromascape.utils.core.screen.window;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single platform switch between discovery implementations.
 *
 * <p>On Linux it must never initialize or reference Win32/JNA classes (machine-checked by the
 * compiled-class isolation tests); on Windows it preserves the existing enumeration behavior.
 * Unsupported platforms (macOS/unknown) fail bounded with a diagnostic instead of throwing at
 * startup.
 */
@Component
public class ProcessManagerFactory {

  /** Platforms with a discovery implementation mapping. */
  public enum Platform {
    /** Windows via {@link WindowsProcessManager}. */
    WINDOWS,
    /** Linux via {@link LinuxProcessManager}. */
    LINUX,
    /** macOS/unknown via {@link UnsupportedProcessManager}. */
    UNSUPPORTED
  }

  private final ProcFs procFs;
  private final String micro6ProjectRoot;
  private final String localMachineUuid;

  /**
   * Spring constructor resolving the Micro6 project root and the local host identity from
   * configuration.
   *
   * @param micro6ProjectRoot Micro6 project root used to match Builder Goal command lines
   * @param localMachineUuid local host identity ({@code builder-goal.launcher.local-machine-uuid})
   */
  @Autowired
  public ProcessManagerFactory(
      @Value("${builder-goal.recovery.micro6-project-dir:../micro6}") String micro6ProjectRoot,
      @Value("${builder-goal.launcher.local-machine-uuid:}") String localMachineUuid) {
    this(ProcFs.production(), micro6ProjectRoot, localMachineUuid);
  }

  /**
   * Non-Spring convenience constructor: production procfs, no project-root matching, no host id.
   */
  public ProcessManagerFactory() {
    this(ProcFs.production(), "", "");
  }

  /**
   * Test constructor with an injectable procfs root.
   *
   * @param procFs procfs adapter handed to the Linux implementation
   * @param micro6ProjectRoot Micro6 project root used to match Builder Goal command lines
   * @param localMachineUuid local host identity recorded on discovered identities
   */
  ProcessManagerFactory(ProcFs procFs, String micro6ProjectRoot, String localMachineUuid) {
    this.procFs = procFs;
    this.micro6ProjectRoot = micro6ProjectRoot;
    this.localMachineUuid = localMachineUuid;
  }

  /**
   * Returns the discovery implementation for the current platform. Instantiated lazily per call so
   * no platform-specific class initializes until discovery is actually used.
   */
  public ProcessManager getProcessManager() {
    return switch (currentPlatform()) {
      case WINDOWS -> new WindowsProcessManager(localMachineUuid);
      case LINUX -> new LinuxProcessManager(procFs, micro6ProjectRoot, localMachineUuid);
      case UNSUPPORTED -> new UnsupportedProcessManager();
    };
  }

  /** Platform of the running JVM. */
  public static Platform currentPlatform() {
    return platformFor(System.getProperty("os.name"));
  }

  /**
   * Maps an {@code os.name} value to a discovery platform. Package-visible for tests.
   *
   * @param osName operating system name property
   * @return the platform mapping
   */
  static Platform platformFor(String osName) {
    String os = osName == null ? "" : osName.toLowerCase();
    if (os.contains("win")) {
      return Platform.WINDOWS;
    }
    if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
      return Platform.LINUX;
    }
    return Platform.UNSUPPORTED;
  }
}
