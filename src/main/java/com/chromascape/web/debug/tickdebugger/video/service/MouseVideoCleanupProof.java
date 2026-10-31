package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Proves, before a NEEDS_RECONCILIATION job is settled by its owner, that nothing it may have
 * started is still alive. Scope units are not recorded per job, so the proof is deliberately global
 * and fails closed:
 *
 * <ul>
 *   <li>no worker for the job is registered in this instance;
 *   <li>no workspace of any generation of the job is still mounted;
 *   <li>no {@code chromascape-video-*} scope is active other than those held by live executions in
 *       this JVM. An unattributed scope may belong to this job, so its presence blocks the proof;
 *   <li>scope state must be readable: where it is not (no systemd user manager, a failed query),
 *       cleanup cannot be proven at all.
 * </ul>
 */
@Component
public class MouseVideoCleanupProof {

  /** Outcome; {@code blocker} is the owner-facing reason when cleanup is not proven. */
  public record Result(boolean proven, String blocker, Map<String, Object> evidence) {}

  private static final long QUERY_MILLIS = 5_000L;

  private final MouseVideoWorkerRegistry workers;
  private final Path jobsRoot;
  private final Supplier<List<String>> mountPoints;
  private final Supplier<Optional<List<String>>> activeScopes;
  private final Supplier<Set<String>> heldScopes;

  @Autowired
  public MouseVideoCleanupProof(MouseVideoWorkerRegistry workers, MouseVideoProperties properties) {
    this(
        workers,
        properties.rootPath().resolve("jobs"),
        MouseVideoCleanupProof::readMountPoints,
        MouseVideoCleanupProof::queryActiveScopes,
        MouseVideoProcessRunner::heldScopeUnits);
  }

  /** Test seam over the host probes. */
  MouseVideoCleanupProof(
      MouseVideoWorkerRegistry workers,
      Path jobsRoot,
      Supplier<List<String>> mountPoints,
      Supplier<Optional<List<String>>> activeScopes,
      Supplier<Set<String>> heldScopes) {
    this.workers = workers;
    this.jobsRoot = jobsRoot.toAbsolutePath().normalize();
    this.mountPoints = mountPoints;
    this.activeScopes = activeScopes;
    this.heldScopes = heldScopes;
  }

  /** Checks every condition and reports the first that fails, with what was observed. */
  public Result prove(UUID jobId) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    boolean owned = workers.isOwned(jobId);
    evidence.put("workerRegistered", owned);
    if (owned) {
      return new Result(false, "A worker for this job is still running in this server", evidence);
    }
    String prefix = jobsRoot.resolve(jobId.toString()).toString() + "-";
    List<String> mounted = new ArrayList<>();
    for (String point : mountPoints.get()) {
      if (point.startsWith(prefix)) {
        mounted.add(point.substring(jobsRoot.toString().length() + 1));
      }
    }
    evidence.put("workspacesMounted", mounted);
    if (!mounted.isEmpty()) {
      return new Result(false, "A workspace of this job is still mounted", evidence);
    }
    Optional<List<String>> active = activeScopes.get();
    if (active.isEmpty()) {
      evidence.put("scopes", "unreadable");
      return new Result(false, "Process scopes cannot be inspected on this host", evidence);
    }
    // Read held after active: a scope that closed in between then counts as unattributed, which
    // only refuses a proof; it can never hide a live scope.
    Set<String> held = heldScopes.get();
    Set<String> unattributed = new TreeSet<>(active.get());
    unattributed.removeAll(held);
    evidence.put("activeScopes", active.get().size());
    evidence.put("heldScopes", held.size());
    evidence.put("unattributedScopes", List.copyOf(unattributed));
    if (!unattributed.isEmpty()) {
      return new Result(
          false,
          "A video process scope not held by this server is still active; it may belong to this job",
          evidence);
    }
    return new Result(true, null, evidence);
  }

  /** Mount points from this process's mount table, with the kernel's octal escapes decoded. */
  static List<String> readMountPoints() {
    try {
      List<String> points = new ArrayList<>();
      for (String line :
          Files.readAllLines(Path.of("/proc/self/mountinfo"), StandardCharsets.UTF_8)) {
        String[] fields = line.split(" ");
        if (fields.length > 4) {
          points.add(unescape(fields[4]));
        }
      }
      return points;
    } catch (IOException unreadable) {
      // No mount table (not Linux): there is nothing mounted that this check could see.
      return List.of();
    }
  }

  static String unescape(String field) {
    StringBuilder out = new StringBuilder(field.length());
    for (int index = 0; index < field.length(); index++) {
      char c = field.charAt(index);
      if (c == '\\' && isOctal(field, index + 1)) {
        out.append((char) Integer.parseInt(field.substring(index + 1, index + 4), 8));
        index += 3;
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static boolean isOctal(String field, int from) {
    if (from + 3 > field.length()) {
      return false;
    }
    for (int index = from; index < from + 3; index++) {
      char c = field.charAt(index);
      if (c < '0' || c > '7') {
        return false;
      }
    }
    return true;
  }

  /**
   * Active, activating or deactivating {@code chromascape-video-*} scopes of the user manager, or
   * empty when the manager cannot be queried.
   */
  static Optional<List<String>> queryActiveScopes() {
    try {
      Process process =
          new ProcessBuilder(
                  "systemctl",
                  "--user",
                  "list-units",
                  "--type=scope",
                  "--all",
                  "--plain",
                  "--no-legend",
                  "--no-pager",
                  "chromascape-video-*")
              .redirectErrorStream(true)
              .start();
      byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(QUERY_MILLIS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      if (process.exitValue() != 0) {
        return Optional.empty();
      }
      return Optional.of(parseActiveScopes(new String(output, StandardCharsets.UTF_8)));
    } catch (IOException unavailable) {
      return Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** Parses {@code UNIT LOAD ACTIVE SUB DESCRIPTION} rows, keeping every non-inactive unit. */
  static List<String> parseActiveScopes(String listing) {
    List<String> active = new ArrayList<>();
    for (String line : listing.split("\n")) {
      String[] columns = line.trim().split("\\s+");
      if (columns.length >= 3
          && columns[0].startsWith("chromascape-video-")
          && columns[0].endsWith(".scope")
          && !"inactive".equals(columns[2])) {
        active.add(columns[0]);
      }
    }
    return active;
  }
}
