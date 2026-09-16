package com.chromascape.utils.core.screen.window;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linux discovery of eligible local RuneLite/Micro6 client processes via procfs.
 *
 * <p>Replaces the upstream first-match {@code cmdline.contains(RuneLite)} scan: every eligible
 * candidate is reported, sorted deterministically by (start time, pid), and consumers must resolve
 * an explicit selection or accept an {@link TargetSelectionDiagnostic#AMBIGUOUS_TARGET} refusal.
 * The frozen rule set is documented in
 * {@code docs/upstream-integration/sc-1663/process-identity-mapping.md}.
 *
 * <p>Matching is a pure predicate over in-memory command lines that are dropped immediately and are
 * never logged or included in diagnostics, because Micro6 client arguments can carry account
 * credentials. Discovery never loads or references the RemoteInput library, never initializes
 * Win32/JNA and never attaches.
 */
public class LinuxProcessManager implements ProcessManager {

  private static final Logger logger = LoggerFactory.getLogger(LinuxProcessManager.class);

  /** RuneLite client main class marker. */
  static final String RUNELITE_MAIN_CLASS = "net.runelite.client.RuneLite";

  /** Builder Goal launch entry point marker. */
  static final String BUILDER_GOAL_ENTRY_POINT = "runBuilderGoal";

  /** Command-line markers of Gradle daemons and workers, which are never the client. */
  static final String[] GRADLE_DAEMON_MARKERS = {
    "org.gradle.launcher.daemon", "gradle-daemon-main", "GradleDaemon", "gradle-worker"
  };

  /** Command-line marker of the Micro6 remote-launcher agent. */
  static final String REMOTE_LAUNCHER_AGENT_MARKER = "remote-launcher";

  /** Discovery source label for procfs-derived candidates. */
  static final String SOURCE_PROCESS = "process";

  private static final String SELECTION_REASON = "eligible local RuneLite/Micro6 client process";
  private static volatile int cachedClockTicksPerSecond;

  private final ProcFs procFs;
  private final String micro6ProjectRootPath;
  private final String micro6ProjectRootForwardSlashes;
  private final String hostId;
  private final StartTimeResolver startTimeResolver;
  private volatile TargetScanDiagnostics lastScan = TargetScanDiagnostics.EMPTY;

  /** Resolves a process start instant so identity construction is testable with synthetic pids. */
  public interface StartTimeResolver {

    /**
     * Resolves the start instant of {@code pid}.
     *
     * @return the start instant, or empty when it cannot be observed
     */
    Optional<Instant> startTimeOf(long pid);
  }

  /**
   * Creates a Linux process manager.
   *
   * @param procFs procfs adapter (production or synthetic test root)
   * @param micro6ProjectRoot configured Micro6 project root used to match Builder Goal command
   *     lines; blank disables the project-root condition
   * @param hostId local host identity recorded on discovered identities; may be empty
   */
  public LinuxProcessManager(ProcFs procFs, String micro6ProjectRoot, String hostId) {
    this(procFs, micro6ProjectRoot, hostId, defaultResolver(procFs));
  }

  /**
   * Creates a Linux process manager with an explicit start-time resolver (test seam).
   *
   * @param procFs procfs adapter (production or synthetic test root)
   * @param micro6ProjectRoot configured Micro6 project root; blank disables project-root matching
   * @param hostId local host identity recorded on discovered identities; may be empty
   * @param startTimeResolver start-time resolver for identity construction
   */
  public LinuxProcessManager(
      ProcFs procFs, String micro6ProjectRoot, String hostId, StartTimeResolver startTimeResolver) {
    this.procFs = procFs;
    if (micro6ProjectRoot == null || micro6ProjectRoot.isBlank()) {
      this.micro6ProjectRootPath = "";
      this.micro6ProjectRootForwardSlashes = "";
    } else {
      Path normalized = Path.of(micro6ProjectRoot).toAbsolutePath().normalize();
      this.micro6ProjectRootPath = normalized.toString();
      this.micro6ProjectRootForwardSlashes = normalized.toString().replace('\\', '/');
    }
    this.hostId = hostId == null ? "" : hostId;
    this.startTimeResolver = startTimeResolver;
  }

  @Override
  public List<DiscoveredTarget> discoverEligibleLocal() {
    List<DiscoveredTarget> targets = new ArrayList<>();
    int scannedEntries = 0;
    int unreadableEntries = 0;
    long selfPid = ProcessHandle.current().pid();

    for (long pid : procFs.listPidDirs()) {
      scannedEntries++;
      if (pid == selfPid) {
        // ChromaScape's own JVM is never a target even if it references the same project tree
        continue;
      }
      Optional<byte[]> cmdlineBytes = procFs.readCmdline(pid);
      if (cmdlineBytes.isEmpty()) {
        unreadableEntries++;
        continue;
      }
      String rawCmdline = new String(cmdlineBytes.get(), StandardCharsets.UTF_8);
      if (rawCmdline.isBlank()) {
        unreadableEntries++;
        continue;
      }
      if (!isEligible(pid, rawCmdline)) {
        continue;
      }
      Optional<ObservedProcess> observed = readObserved(pid);
      if (observed.isEmpty()) {
        unreadableEntries++;
        continue;
      }
      targets.add(buildTarget(observed.get()));
    }

    targets.sort(
        Comparator.comparing((DiscoveredTarget target) -> target.identity().startTime())
            .thenComparingLong(target -> target.identity().pid()));

    List<TargetSelectionDiagnostic> diagnostics = new ArrayList<>();
    if (unreadableEntries > 0) {
      diagnostics.add(TargetSelectionDiagnostic.PROC_ENTRY_UNREADABLE);
    }
    lastScan = new TargetScanDiagnostics(scannedEntries, unreadableEntries, diagnostics);
    logger.debug(
        "Discovered {} eligible local target(s) from {} procfs entries ({} unreadable)",
        targets.size(),
        scannedEntries,
        unreadableEntries);
    return targets;
  }

  @Override
  public Optional<ProcessIdentity> resolveLocal(long pid) {
    return readObserved(pid).map(ObservedProcess::identity);
  }

  @Override
  public TargetScanDiagnostics lastScanDiagnostics() {
    return lastScan;
  }

  /**
   * Applies the frozen eligibility rule set.
   *
   * @param pid candidate pid
   * @param rawCmdline raw null-delimited command line; never logged or persisted
   * @return whether the pid is an eligible local client
   */
  boolean isEligible(long pid, String rawCmdline) {
    String argv0 = argv0(rawCmdline);
    String cmdline = rawCmdline.replace('\0', ' ').trim();

    if (!isJavaExecutable(pid, argv0)) {
      // Rule 1: excludes bash Gradle wrappers, the python agent and IDE tooling
      return false;
    }
    if (isGradleDaemonCommandLine(cmdline) || isRemoteLauncherAgent(argv0, cmdline)) {
      // Rule 5: daemons/workers and the remote-launcher agent are never the client
      return false;
    }
    boolean hasRuneLiteMainClass = cmdline.contains(RUNELITE_MAIN_CLASS);
    boolean hasBuilderGoalEntryPoint = cmdline.contains(BUILDER_GOAL_ENTRY_POINT);
    if (!hasRuneLiteMainClass && !hasBuilderGoalEntryPoint) {
      // Rule 2
      return false;
    }
    if (hasBuilderGoalEntryPoint && !hasRuneLiteMainClass && !matchesMicro6ProjectRoot(cmdline)) {
      // Rule 3 (frozen in the mapping document)
      return false;
    }
    return true;
  }

  /** Stable candidate id: {@code linux:<pid>:<startTicks>}. */
  static String candidateId(long pid, long startTicks) {
    return "linux:" + pid + ":" + startTicks;
  }

  private static StartTimeResolver defaultResolver(ProcFs procFs) {
    return pid -> {
      Optional<Instant> jdk = ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant());
      if (jdk.isPresent()) {
        return jdk;
      }
      return convertFromTicks(procFs, pid);
    };
  }

  private static Optional<Instant> convertFromTicks(ProcFs procFs, long pid) {
    Optional<Integer> clockTicks = clockTicksPerSecond(procFs);
    if (clockTicks.isEmpty()) {
      return Optional.empty();
    }
    Optional<long[]> ticksAndUptime = procFs.readStartTimeTicksAndUptime(pid);
    if (ticksAndUptime.isEmpty()) {
      return Optional.empty();
    }
    long bootEpochMs = System.currentTimeMillis() - ticksAndUptime.get()[1] * 1000L;
    long startEpochMs = bootEpochMs + (ticksAndUptime.get()[0] * 1000L) / clockTicks.get();
    return Optional.of(Instant.ofEpochMilli(startEpochMs));
  }

  /**
   * Estimates the host clock tick rate once per JVM by calibrating this JVM's own procfs start
   * ticks against its JDK start instant; empty when the rate cannot be determined.
   */
  private static Optional<Integer> clockTicksPerSecond(ProcFs procFs) {
    int cached = cachedClockTicksPerSecond;
    if (cached > 0) {
      return Optional.of(cached);
    }
    long selfPid = ProcessHandle.current().pid();
    Optional<long[]> selfTicks = procFs.readStartTimeTicksAndUptime(selfPid);
    Optional<Instant> selfStart =
        ProcessHandle.of(selfPid).flatMap(handle -> handle.info().startInstant());
    if (selfTicks.isEmpty() || selfStart.isEmpty()) {
      return Optional.empty();
    }
    long bootEpochMs = System.currentTimeMillis() - selfTicks.get()[1] * 1000L;
    long startDeltaMs = selfStart.get().toEpochMilli() - bootEpochMs;
    if (startDeltaMs <= 0) {
      return Optional.empty();
    }
    long estimated = Math.round(selfTicks.get()[0] * 1000.0 / startDeltaMs);
    if (estimated < 50 || estimated > 10000) {
      return Optional.empty();
    }
    cachedClockTicksPerSecond = (int) estimated;
    return Optional.of((int) estimated);
  }

  private Optional<ObservedProcess> readObserved(long pid) {
    Optional<long[]> ticksAndUptime = procFs.readStartTimeTicksAndUptime(pid);
    if (ticksAndUptime.isEmpty()) {
      return Optional.empty();
    }
    Optional<Instant> startTime = startTimeResolver.startTimeOf(pid);
    if (startTime.isEmpty()) {
      return Optional.empty();
    }
    String bootId = procFs.readBootId().orElse("");
    ProcessIdentity identity =
        new ProcessIdentity(ProcessOrigin.LOCAL, hostId, pid, startTime.get(), bootId);
    return Optional.of(new ObservedProcess(identity, ticksAndUptime.get()[0]));
  }

  private DiscoveredTarget buildTarget(ObservedProcess observed) {
    ProcessIdentity identity = observed.identity();
    WindowInfo window =
        new WindowInfo(
            candidateId(identity.pid(), observed.startTicks()),
            null,
            null,
            null,
            (int) identity.pid(),
            false,
            null,
            identity);
    return new DiscoveredTarget(identity, window, SOURCE_PROCESS, SELECTION_REASON);
  }

  private static String argv0(String rawCmdline) {
    int separator = rawCmdline.indexOf('\0');
    String first = separator >= 0 ? rawCmdline.substring(0, separator) : rawCmdline;
    return first.trim();
  }

  private boolean isJavaExecutable(long pid, String argv0) {
    String basename = procFs.readExeTarget(pid).map(LinuxProcessManager::basename).orElse(basename(argv0));
    return basename.equals("java") || basename.equals("javaw");
  }

  private static boolean isGradleDaemonCommandLine(String cmdline) {
    for (String marker : GRADLE_DAEMON_MARKERS) {
      if (cmdline.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRemoteLauncherAgent(String argv0, String cmdline) {
    return basename(argv0).startsWith("python") || cmdline.contains(REMOTE_LAUNCHER_AGENT_MARKER);
  }

  private boolean matchesMicro6ProjectRoot(String cmdline) {
    if (micro6ProjectRootPath.isEmpty()) {
      return false;
    }
    return cmdline.contains(micro6ProjectRootPath)
        || cmdline.contains(micro6ProjectRootForwardSlashes);
  }

  private static String basename(String path) {
    if (path == null || path.isEmpty()) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private record ObservedProcess(ProcessIdentity identity, long startTicks) {}
}
