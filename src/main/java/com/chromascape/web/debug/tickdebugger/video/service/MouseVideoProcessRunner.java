package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns one bounded child execution for SC-2150. Every launch is placed inside an execution-specific
 * containment boundary before the tool can spawn, so a short-lived parent cannot reparent a child
 * outside cleanup. stdout and stderr are drained concurrently; normal exit waits for both readers
 * to reach EOF and finish their callbacks before returning an immutable result. Cleanup terminates
 * only the members of this execution's containment and confirms that it is empty before returning.
 *
 * <p>On Linux the containment must be a transient systemd scope backed by cgroup v2. A process
 * group cannot contain children that create new sessions. Windows retains process-tree tracking. If
 * no containment facility can be established the runner fails closed with {@link
 * MouseVideoException#CODE_CLEANUP_UNCONFIRMED} rather than pretending the workspace is reusable.
 */
@Component
public class MouseVideoProcessRunner {
  /** A configured requirement may never fall through to an unsupported host's tree fallback. */
  static void requireRestrictedHost(String os, MouseVideoRestrictedLauncher launcher) {
    if (launcher != null && launcher.required() && !os.toLowerCase(Locale.ROOT).contains("linux")) {
      throw new MouseVideoException(
          MouseVideoException.CODE_TOOL_UNAVAILABLE,
          "Restricted video execution is unavailable on this host");
    }
  }

  private static final int READ_BUFFER_BYTES = 8 * 1024;
  private static final int MAX_LINE_BYTES = 64 * 1024;
  private static final int MAX_STDERR_BYTES = 16 * 1024;
  private static final long POLL_MILLIS = 50L;
  private static final long GRACE_MILLIS = 500L;
  private static final long FORCE_MILLIS = 1_000L;
  private static final long CONTROL_MILLIS = 3_000L;
  private static final long DRAIN_MIN_MILLIS = 5_000L;
  private static final long DRAIN_GRACE_MILLIS = 1_000L;

  private final MouseVideoProperties properties;
  private final BooleanSupplier scopeAvailable;
  private final MouseVideoRestrictedLauncher restrictedLauncher;
  private final Supplier<Containment> containmentFactory;

  @Autowired
  public MouseVideoProcessRunner(
      MouseVideoProperties properties, MouseVideoRestrictedLauncher restrictedLauncher) {
    this(properties, SystemdScopeContainment::available, restrictedLauncher);
  }

  MouseVideoProcessRunner(MouseVideoProperties properties) {
    this(properties, SystemdScopeContainment::available, null);
  }

  MouseVideoProcessRunner(MouseVideoProperties properties, BooleanSupplier scopeAvailable) {
    this(properties, scopeAvailable, null);
  }

  MouseVideoProcessRunner(
      MouseVideoProperties properties,
      BooleanSupplier scopeAvailable,
      MouseVideoRestrictedLauncher restrictedLauncher) {
    this.properties = properties;
    this.scopeAvailable = scopeAvailable;
    this.restrictedLauncher = restrictedLauncher;
    this.containmentFactory = null;
  }

  /** Test seam: supplies the containment instead of opening a systemd scope. */
  MouseVideoProcessRunner(
      MouseVideoProperties properties, Supplier<Containment> containmentFactory) {
    this.properties = properties;
    this.scopeAvailable = () -> true;
    this.restrictedLauncher = null;
    this.containmentFactory = containmentFactory;
  }

  /** Receives complete stdout lines; returning false stops consuming further output. */
  @FunctionalInterface
  public interface LineConsumer {
    boolean accept(String line);
  }

  /**
   * One finished execution. {@code halted} means a consumer or byte limit stopped consumption
   * before the tool ended. {@code outputComplete} means both readers reached EOF without a read
   * error or callback failure; it does not by itself mean consumption was exhaustive, so callers
   * that require the full stream must also reject {@code halted}.
   */
  public record Result(
      int exitCode,
      String stdout,
      String stderr,
      boolean stdoutTruncated,
      boolean halted,
      boolean outputComplete,
      boolean readFailed,
      boolean callbackFailed,
      boolean drainTimedOut) {}

  /**
   * Runs {@code command} under a wall-clock budget.
   *
   * @param stdoutConsumer when non-null, receives bounded stdout lines instead of a captured buffer
   * @param maxOutputBytes total stdout bytes read in line mode, or captured bytes in buffer mode
   */
  public Result run(
      List<String> command,
      long budgetMillis,
      MouseVideoCancellation cancellation,
      LineConsumer stdoutConsumer,
      int maxOutputBytes)
      throws IOException, InterruptedException {
    if (cancellation.isCancelled()) {
      throw new InterruptedException("Execution cancelled before process start");
    }
    Containment containment =
        containmentFactory != null
            ? containmentFactory.get()
            : Containment.open(scopeAvailable, properties, restrictedLauncher);
    Process process;
    try {
      process = containment.start(command);
    } catch (IOException | RuntimeException failure) {
      containment.close();
      throw failure;
    }
    Drain stdout = new Drain(process.getInputStream(), stdoutConsumer, maxOutputBytes);
    Drain stderr = new Drain(process.getErrorStream(), null, MAX_STDERR_BYTES);
    Thread stdoutThread = daemon(stdout, "mouse-video-stdout");
    Thread stderrThread = daemon(stderr, "mouse-video-stderr");
    stdoutThread.start();
    stderrThread.start();

    boolean cancelled = false;
    boolean timedOut = false;
    boolean interrupted = false;
    boolean rootExited = false;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMillis));
    try {
      while (true) {
        if (cancellation.isCancelled()) {
          cancelled = true;
          break;
        }
        if (stdout.halted()) {
          break;
        }
        if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
          rootExited = true;
          break;
        }
        if (System.nanoTime() >= deadline) {
          timedOut = true;
          break;
        }
      }
    } catch (InterruptedException interruptedWait) {
      interrupted = true;
      cancelled = true;
    }

    boolean cleanupInterrupted = interrupted || Thread.interrupted();
    cleanupInterrupted |= terminateIfNeeded(containment, process, rootExited);
    waitForProcessExit(process);

    long drainBudget =
        Math.max(
            DRAIN_MIN_MILLIS,
            TimeUnit.SECONDS.toMillis(Math.max(1L, properties.getChildShutdownBudgetSeconds()))
                + 2_000L);
    JoinResult drained = joinAll(drainBudget, stdoutThread, stderrThread);
    boolean drainTimedOut = !drained.finished();
    cleanupInterrupted |= drained.interrupted();
    if (drainTimedOut) {
      // Unblock readers that are still waiting on a pipe after cleanup, then give them a short
      // grace period. A reader that remains alive makes the result explicitly incomplete.
      closeQuietly(process.getInputStream());
      closeQuietly(process.getErrorStream());
      JoinResult forced = joinAll(DRAIN_GRACE_MILLIS, stdoutThread, stderrThread);
      cleanupInterrupted |= forced.interrupted();
      // Suppress any callback that could still be dispatched after this incomplete return.
      stdout.forceHalt();
      stderr.forceHalt();
    }
    closeQuietly(process.getOutputStream());
    containment.close();
    // The probe after close() is authoritative: close() stops the scope (systemd terminates every
    // remaining member) and waits for it to settle. A probe taken just after the root exits can
    // still see a deactivating scope on a busy host; letting that earlier reading stand made clean
    // jobs ambiguous even though this final probe proved the scope inactive and empty.
    boolean cleanupConfirmed = containment.isEmpty();

    boolean outputComplete = stdout.complete() && stderr.complete();
    boolean readFailed = stdout.readFailed() || stderr.readFailed();
    boolean callbackFailed = stdout.callbackFailed() || stderr.callbackFailed();
    if (cleanupInterrupted) {
      Thread.currentThread().interrupt();
    }

    if (!cleanupConfirmed) {
      throw new MouseVideoException(
          MouseVideoException.CODE_CLEANUP_UNCONFIRMED,
          "Owned process cleanup could not be confirmed; process state is ambiguous (last probe: "
              + containment.lastProbe()
              + ")");
    }
    if (cancelled || interrupted) {
      Thread.currentThread().interrupt();
      throw new InterruptedException("Execution cancelled");
    }
    if (timedOut) {
      throw new MouseVideoException(
          MouseVideoException.CODE_WORKER_TIMEOUT, "Tool execution exceeded its time budget");
    }
    int exitCode = process.isAlive() ? -1 : process.exitValue();
    return new Result(
        exitCode,
        stdout.capturedText(),
        stderr.capturedText(),
        stdout.truncated(),
        stdout.halted(),
        outputComplete,
        readFailed,
        callbackFailed,
        drainTimedOut);
  }

  private boolean terminateIfNeeded(Containment containment, Process process, boolean rootExited) {
    boolean empty = containment.isEmpty();
    if (rootExited && empty) {
      return false;
    }
    boolean interrupted = containment.terminate(GRACE_MILLIS, FORCE_MILLIS);
    // A root that exited normally can still have left children; terminate() removes them. If the
    // root itself is still running (timeout, cancellation or consumer halt) its tree is fenced the
    // same way. Re-checking emptiness is the only claim of cleanup.
    return interrupted;
  }

  private static void waitForProcessExit(Process process) {
    try {
      if (!process.waitFor(GRACE_MILLIS + FORCE_MILLIS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private record JoinResult(boolean finished, boolean interrupted) {}

  private static JoinResult joinAll(long millis, Thread... threads) {
    boolean interrupted = false;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, millis));
    for (Thread thread : threads) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return new JoinResult(false, interrupted);
      }
      try {
        thread.join(TimeUnit.NANOSECONDS.toMillis(remaining));
      } catch (InterruptedException error) {
        interrupted = true;
      }
      if (thread.isAlive()) {
        return new JoinResult(false, interrupted);
      }
    }
    return new JoinResult(true, interrupted);
  }

  private static Thread daemon(Runnable runnable, String name) {
    Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // Best-effort stream shutdown; containment emptiness is what confirms cleanup.
    }
  }

  /**
   * Execution-owned process containment. A containment is opened before the tool starts, remains
   * enumerable after the original root exits, and is the only authority for cleanup confirmation.
   */
  interface Containment extends AutoCloseable {

    Process start(List<String> command) throws IOException;

    boolean isEmpty();

    /** The last emptiness probe, for private diagnostics only; never part of a public message. */
    default String lastProbe() {
      return "";
    }

    /** Terminates every member, escalating after the grace period; returns an interrupt flag. */
    boolean terminate(long graceMillis, long forceMillis);

    @Override
    void close();

    static Containment open(
        BooleanSupplier scopeAvailable,
        MouseVideoProperties properties,
        MouseVideoRestrictedLauncher restrictedLauncher) {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      requireRestrictedHost(os, restrictedLauncher);
      if (os.contains("win")) {
        return new TreeContainment();
      }
      if (restrictedLauncher != null && restrictedLauncher.required()) {
        if (!restrictedLauncher.available()) {
          throw new MouseVideoException(
              MouseVideoException.CODE_TOOL_UNAVAILABLE,
              "Restricted launcher is required but unavailable; refusing unrestricted execution");
        }
        if (!scopeAvailable.getAsBoolean()) {
          throw new MouseVideoException(
              MouseVideoException.CODE_CLEANUP_UNCONFIRMED,
              "Restricted launcher requires cgroup containment, which is unavailable");
        }
        return new SystemdScopeContainment(properties, restrictedLauncher);
      }
      if (scopeAvailable.getAsBoolean()) {
        return new SystemdScopeContainment(properties, null);
      }
      throw new MouseVideoException(
          MouseVideoException.CODE_CLEANUP_UNCONFIRMED,
          "No execution containment facility is available; refusing to start a tool");
    }
  }

  /**
   * Scope units this JVM has started and not yet closed. Any other active {@code
   * chromascape-video-*} scope is unattributed (an earlier process, another instance, or a close
   * that left it behind), so cleanup cannot be proven while one exists.
   */
  private static final Set<String> HELD_SCOPES = ConcurrentHashMap.newKeySet();

  /** Whether this host can run executions in transient systemd scopes (probed once). */
  static boolean scopeContainmentAvailable() {
    return SystemdScopeContainment.available();
  }

  /** Snapshot of the scope units held by live executions in this JVM. */
  static Set<String> heldScopeUnits() {
    return Set.copyOf(HELD_SCOPES);
  }

  /** Transient per-execution systemd scope backed by a cgroup that survives the root process. */
  private static final class SystemdScopeContainment implements Containment {

    private static volatile Boolean available;

    private final String unit = unitName();
    private volatile String lastProbe = "none";
    private final MouseVideoProperties properties;
    private final MouseVideoRestrictedLauncher restrictedLauncher;

    SystemdScopeContainment(
        MouseVideoProperties properties, MouseVideoRestrictedLauncher restrictedLauncher) {
      this.properties = properties;
      this.restrictedLauncher = restrictedLauncher;
    }

    static boolean available() {
      Boolean cached = available;
      if (cached != null) {
        return cached;
      }
      synchronized (SystemdScopeContainment.class) {
        if (available == null) {
          if (!Files.isRegularFile(Path.of("/sys/fs/cgroup/cgroup.controllers"))) {
            available = false;
            return false;
          }
          String unit = unitName();
          Exec probe =
              exec(
                  List.of(
                      "systemd-run",
                      "--user",
                      "--scope",
                      "--quiet",
                      "--collect",
                      "--expand-environment=no",
                      "--unit=" + unit,
                      "--",
                      "/bin/true"),
                  5_000L);
          if (probe.exitCode() != 0) {
            exec(List.of("systemctl", "--user", "stop", unit + ".scope"), CONTROL_MILLIS);
          }
          available = probe.exitCode() == 0;
        }
        return available;
      }
    }

    @Override
    public Process start(List<String> command) throws IOException {
      List<String> effective =
          restrictedLauncher == null || !restrictedLauncher.required()
              ? command
              : restrictedLauncher.wrap(command);
      List<String> wrapped = new ArrayList<>(effective.size() + 16);
      wrapped.add("systemd-run");
      wrapped.add("--user");
      wrapped.add("--scope");
      wrapped.add("--quiet");
      wrapped.add("--collect");
      wrapped.add("--expand-environment=no");
      wrapped.add("--unit=" + unit);
      HELD_SCOPES.add(unit + ".scope");
      if (properties != null) {
        // The budget is a total memory ceiling, not merely a resident-set ceiling. Without a swap
        // limit the kernel reclaims anon pages to swap and the child survives an allocation that
        // exceeds the budget, so MemorySwapMax=0 makes the cgroup limit authoritative.
        wrapped.add("--property=MemoryMax=" + Math.max(1L, properties.getMaxRssBytes()));
        wrapped.add("--property=MemorySwapMax=0");
        wrapped.add("--property=CPUQuota=" + Math.max(1, properties.getCpuQuotaPercent()) + "%");
        wrapped.add("--property=TasksMax=" + Math.max(1, properties.getTasksMax()));
      }
      wrapped.add("--");
      wrapped.addAll(effective);
      return new ProcessBuilder(wrapped).start();
    }

    @Override
    public boolean isEmpty() {
      Exec state =
          exec(
              List.of(
                  "systemctl",
                  "--user",
                  "show",
                  unit + ".scope",
                  "--property=ActiveState",
                  "--value"),
              CONTROL_MILLIS);
      if (state.exitCode() != 0) {
        lastProbe = "activeState exit=" + state.exitCode();
        return false;
      }
      String value = state.output().trim();
      if (value.isEmpty() || "inactive".equals(value)) {
        lastProbe = "activeState=" + (value.isEmpty() ? "<empty>" : value);
        return true;
      }
      Exec group =
          exec(
              List.of(
                  "systemctl",
                  "--user",
                  "show",
                  unit + ".scope",
                  "--property=ControlGroup",
                  "--value"),
              CONTROL_MILLIS);
      if (group.exitCode() != 0) {
        lastProbe = "activeState=" + value + " controlGroup exit=" + group.exitCode();
        return false;
      }
      String controlGroup = group.output().trim();
      List<String> procs = null;
      IOException readFailure = null;
      if (!controlGroup.isEmpty()) {
        Path path = Path.of("/sys/fs/cgroup" + controlGroup + "/cgroup.procs");
        try {
          procs = Files.readAllLines(path);
        } catch (IOException unavailable) {
          readFailure = unavailable;
        }
      }
      Membership membership = containmentMembership(value, controlGroup, procs, readFailure);
      lastProbe =
          "activeState="
              + value
              + " controlGroup="
              + (controlGroup.isEmpty() ? "<empty>" : "set")
              + " members="
              + (procs == null ? "unread" : procs.stream().filter(p -> !p.isBlank()).count())
              + (readFailure == null
                  ? ""
                  : " readFailure=" + readFailure.getClass().getSimpleName())
              + " verdict="
              + membership;
      return membership == Membership.EMPTY;
    }

    @Override
    public String lastProbe() {
      return lastProbe;
    }

    @Override
    public boolean terminate(long graceMillis, long forceMillis) {
      kill("SIGTERM");
      if (waitEmpty(graceMillis)) {
        return false;
      }
      kill("SIGKILL");
      return waitEmpty(forceMillis);
    }

    private void kill(String signal) {
      exec(
          List.of(
              "systemctl",
              "--user",
              "kill",
              "--kill-whom=all",
              "--signal=" + signal,
              unit + ".scope"),
          CONTROL_MILLIS);
    }

    private boolean waitEmpty(long millis) {
      boolean interrupted = false;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, millis));
      while (System.nanoTime() < deadline) {
        if (isEmpty()) {
          return interrupted;
        }
        try {
          Thread.sleep(20L);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
      isEmpty();
      return interrupted;
    }

    @Override
    public void close() {
      exec(List.of("systemctl", "--user", "stop", unit + ".scope"), CONTROL_MILLIS);
      // Stop is asynchronous for a unit that is still deactivating. Give the scope a bounded
      // moment to reach a terminal state so the runner's final emptiness check does not race it.
      waitEmpty(FORCE_MILLIS);
      // Released whether or not it emptied: a scope left behind is then unattributed, which is
      // exactly what blocks a cleanup proof.
      HELD_SCOPES.remove(unit + ".scope");
    }

    private static String unitName() {
      return "chromascape-video-" + UUID.randomUUID().toString().replace("-", "");
    }
  }

  /**
   * Windows fallback: tracks the observed process tree. It cannot fence a child that both
   * re-parents and leaves the tree between scans; the Linux containment above is the qualified
   * path.
   */
  private static final class TreeContainment implements Containment {

    private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
    private Process process;

    @Override
    public Process start(List<String> command) throws IOException {
      process = new ProcessBuilder(command).start();
      return process;
    }

    @Override
    public boolean isEmpty() {
      collect();
      return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    }

    private void collect() {
      if (process != null) {
        process.descendants().forEach(descendants::add);
      }
    }

    @Override
    public boolean terminate(long graceMillis, long forceMillis) {
      collect();
      descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
      if (process.isAlive()) {
        process.destroy();
      }
      boolean interrupted = waitEmpty(graceMillis);
      for (int pass = 0; pass < 3 && !isEmpty(); pass++) {
        collect();
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
          process.destroyForcibly();
        }
        interrupted |= waitEmpty(forceMillis);
      }
      return interrupted;
    }

    private boolean waitEmpty(long millis) {
      boolean interrupted = false;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, millis));
      while (System.nanoTime() < deadline) {
        if (isEmpty()) {
          return interrupted;
        }
        try {
          Thread.sleep(10L);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
      return interrupted;
    }

    @Override
    public void close() {
      // No additional containment resource to release.
    }
  }

  private record Exec(int exitCode, String output) {}

  /**
   * One containment probe's verdict. {@code PENDING} means the control plane could not prove the
   * scope is empty yet (a live scope without a readable cgroup, or an unreadable cgroup), so
   * callers must wait or fail closed rather than claim cleanup.
   */
  enum Membership {
    EMPTY,
    PRESENT,
    PENDING
  }

  /**
   * Interprets an ActiveState/ControlGroup/cgroup.procs probe without doing I/O so the
   * disappearing-scope races can be tested deterministically.
   *
   * <p>A scope whose cgroup disappears between the two {@code systemctl show} calls has no members:
   * treating that as ambiguous made ordinary fast previews reconcile after their scope was
   * collected. A live scope with no cgroup, or a cgroup that cannot be read for any other reason,
   * stays ambiguous and fails closed.
   */
  static Membership containmentMembership(
      String activeState, String controlGroup, List<String> procs, IOException readFailure) {
    String state = activeState == null ? "" : activeState.trim();
    if (state.isEmpty() || "inactive".equals(state)) {
      return Membership.EMPTY;
    }
    String group = controlGroup == null ? "" : controlGroup.trim();
    if (group.isEmpty()) {
      return "active".equals(state) || "activating".equals(state)
          ? Membership.PENDING
          : Membership.EMPTY;
    }
    if (readFailure != null) {
      return readFailure instanceof java.nio.file.NoSuchFileException
          ? Membership.EMPTY
          : Membership.PENDING;
    }
    if (procs == null) {
      return Membership.PENDING;
    }
    return procs.stream().allMatch(String::isBlank) ? Membership.EMPTY : Membership.PRESENT;
  }

  private static Exec exec(List<String> command, long timeoutMillis) {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException unavailable) {
      return new Exec(-1, "");
    }
    try {
      if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new Exec(-1, "");
      }
      byte[] output = process.getInputStream().readAllBytes();
      return new Exec(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Exec(-1, "");
    } catch (IOException unreadable) {
      return new Exec(process.isAlive() ? -1 : process.exitValue(), "");
    }
  }

  /**
   * Concurrent bounded drain. In line mode it assembles lines without allocating an unbounded
   * buffer and stops handing lines to the consumer once the consumer or byte cap says to stop,
   * while continuing to read so the tool never blocks on a full pipe. EOF, read failures and
   * callback failures are recorded separately so callers can tell complete output from a forced or
   * failed drain.
   */
  private static final class Drain implements Runnable {

    private final InputStream input;
    private final LineConsumer consumer;
    private final int maxBytes;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final AtomicBoolean truncated = new AtomicBoolean();
    private final AtomicBoolean halted = new AtomicBoolean();
    private final AtomicBoolean eof = new AtomicBoolean();
    private final AtomicBoolean readFailed = new AtomicBoolean();
    private final AtomicBoolean callbackFailed = new AtomicBoolean();
    private long total;

    private Drain(InputStream input, LineConsumer consumer, int maxBytes) {
      this.input = input;
      this.consumer = consumer;
      this.maxBytes = Math.max(0, maxBytes);
    }

    @Override
    public void run() {
      byte[] buffer = new byte[READ_BUFFER_BYTES];
      try {
        int read;
        while ((read = input.read(buffer)) != -1) {
          total += read;
          if (consumer == null) {
            capture(buffer, read);
            continue;
          }
          if (total > maxBytes) {
            halted.set(true);
          }
          for (int index = 0; index < read; index++) {
            byte value = buffer[index];
            if (value == '\n') {
              emitLine();
            } else if (line.size() < MAX_LINE_BYTES) {
              line.write(value);
            } else {
              truncated.set(true);
              halted.set(true);
            }
          }
        }
        eof.set(true);
        if (consumer != null && line.size() > 0) {
          emitLine();
        }
      } catch (IOException failure) {
        readFailed.set(true);
      } finally {
        closeQuietly(input);
      }
    }

    private void capture(byte[] buffer, int length) {
      int remaining = maxBytes - captured.size();
      if (remaining <= 0) {
        truncated.set(true);
        return;
      }
      int accepted = Math.min(length, remaining);
      captured.write(buffer, 0, accepted);
      if (accepted < length) {
        truncated.set(true);
      }
    }

    private void emitLine() {
      if (halted.get()) {
        line.reset();
        return;
      }
      String value = line.toString(StandardCharsets.UTF_8);
      line.reset();
      try {
        if (!consumer.accept(value)) {
          halted.set(true);
        }
      } catch (RuntimeException failure) {
        callbackFailed.set(true);
        halted.set(true);
      }
    }

    String capturedText() {
      return captured.toString(StandardCharsets.UTF_8);
    }

    boolean truncated() {
      return truncated.get();
    }

    boolean halted() {
      return halted.get();
    }

    void forceHalt() {
      halted.set(true);
    }

    boolean complete() {
      return eof.get() && !readFailed.get() && !callbackFailed.get();
    }

    boolean readFailed() {
      return readFailed.get();
    }

    boolean callbackFailed() {
      return callbackFailed.get();
    }
  }
}
