package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Server-owned restricted launcher for decoder/analysis children.
 *
 * <p>The launcher builds a bubblewrap sandbox with a read-only runtime, a private tmpfs, a writable
 * job root only, a cleared environment and no network namespace. It never accepts caller-supplied
 * paths or commands. Availability is probed once; when the operator requires restricted execution
 * and the probe fails, callers must treat that as a readiness failure rather than silently falling
 * back to an unrestricted child.
 */
@Component
public class MouseVideoRestrictedLauncher {

  private final MouseVideoProperties properties;
  private volatile Boolean available;

  public MouseVideoRestrictedLauncher(MouseVideoProperties properties) {
    this.properties = properties;
  }

  /** Truthful capability state for health/readiness responses. */
  public Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    boolean active = required() && available();
    status.put("required", properties.isRestrictedLauncher());
    status.put("bwrapInstalled", Files.isRegularFile(Path.of("/usr/bin/bwrap")));
    status.put("available", available());
    status.put("networkNamespaceIsolated", active);
    status.put("writableRootConfigured", true);
    status.put("perJobWritableMount", active);
    status.put("sharedMediaRootWritable", !active);
    status.put("cpuQuotaPercent", properties.getCpuQuotaPercent());
    status.put("tasksMax", properties.getTasksMax());
    status.put("memoryMaxBytes", properties.getMaxRssBytes());
    status.put("maxJobDiskBytes", properties.getMaxJobDiskBytes());
    status.put("boundedWorkspaceToolsAvailable", MouseVideoWorkspace.toolsAvailable());
    status.put("workspaceDiskQuotaEnforced", active);
    return status;
  }

  public boolean required() {
    return properties.isRestrictedLauncher();
  }

  /** Probes an actual confined launch, not merely the presence of the binary. */
  public boolean available() {
    Boolean cached = available;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (available == null) {
        available = probe();
      }
      return available;
    }
  }

  /** Wraps a fixed server command. The command itself is never interpolated through a shell. */
  public List<String> wrap(List<String> command) throws IOException {
    if (!available()) {
      throw new IOException("Restricted launcher is unavailable");
    }
    return buildWrap(command, inferContext(command));
  }

  /**
   * Wraps with an explicit per-execution context: only {@code writableWorkspace} (which must be a
   * single job directory under the media root) is writable, and only the listed existing files are
   * visible read-only. The shared media root is never mounted.
   */
  public List<String> wrap(
      List<String> command, Path writableWorkspace, Collection<Path> readOnlyInputs)
      throws IOException {
    if (!available()) {
      throw new IOException("Restricted launcher is unavailable");
    }
    Path root = properties.rootPath();
    Context context = new Context();
    if (writableWorkspace != null) {
      Path workspace = writableWorkspace.toAbsolutePath().normalize();
      if (!workspace.startsWith(root.resolve("jobs"))
          && !workspace.startsWith(root.resolve("staging"))) {
        throw new IOException("Writable workspace must be one job directory under the media root");
      }
      Files.createDirectories(workspace);
      context.writable.add(workspace);
    }
    if (readOnlyInputs != null) {
      for (Path input : readOnlyInputs) {
        if (input != null && Files.isRegularFile(input)) {
          context.readOnlyFiles.add(input.toAbsolutePath().normalize());
        }
      }
    }
    return buildWrap(command, context);
  }

  private static final class Context {
    private final Set<Path> writable = new LinkedHashSet<>();
    private final Set<Path> readOnlyFiles = new LinkedHashSet<>();
  }

  private Context inferContext(List<String> command) throws IOException {
    Path root = properties.rootPath();
    Files.createDirectories(root);
    Context context = new Context();
    for (String token : command) {
      if (token == null) {
        continue;
      }
      for (String rawCandidate : extractPaths(token)) {
        inferPath(context, root, rawCandidate);
      }
    }
    return context;
  }

  private static java.util.List<String> extractPaths(String token) {
    java.util.List<String> paths = new java.util.ArrayList<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("/[^\\s'\"\\\\;|&<>]+").matcher(token);
    while (matcher.find()) {
      paths.add(matcher.group());
    }
    return paths;
  }

  private void inferPath(Context context, Path root, String token) throws IOException {
    String candidate = token;
    int pattern = candidate.indexOf('%');
    if (pattern >= 0) {
      candidate = candidate.substring(0, pattern);
    }
    Path path;
    try {
      path = Path.of(candidate).toAbsolutePath().normalize();
    } catch (RuntimeException invalid) {
      return;
    }
    if (isRuntimePath(path)) {
      return;
    }
    Path jobDir = jobDirectory(root, path);
    if (jobDir != null) {
      Files.createDirectories(jobDir);
      context.writable.add(jobDir);
      return;
    }
    Path existing = nearestExisting(path);
    if (existing != null && Files.isRegularFile(existing) && existing.equals(path)) {
      context.readOnlyFiles.add(path);
    } else if (existing != null && Files.isRegularFile(existing) && !path.startsWith(root)) {
      context.readOnlyFiles.add(existing);
    }
    // Non-existent outputs outside a job directory get no mount: the child cannot create
    // them, which fails closed instead of widening the writable surface.
  }

  private static Path jobDirectory(Path root, Path path) {
    Path jobs = root.resolve("jobs");
    if (!path.startsWith(jobs) || jobs.equals(path)) {
      return null;
    }
    Path relative = jobs.relativize(path);
    if (relative.getNameCount() < 1) {
      return null;
    }
    return jobs.resolve(relative.getName(0));
  }

  private static Path nearestExisting(Path path) {
    Path current = path;
    while (current != null && !Files.exists(current)) {
      current = current.getParent();
    }
    return current;
  }

  private static boolean isRuntimePath(Path path) {
    for (String runtime :
        List.of("/usr", "/lib", "/lib64", "/bin", "/sbin", "/etc", "/proc", "/dev")) {
      if (path.startsWith(runtime)) {
        return true;
      }
    }
    return false;
  }

  private List<String> buildWrap(List<String> command, Context context) throws IOException {
    List<String> wrapped = new ArrayList<>();
    wrapped.add("bwrap");
    wrapped.add("--die-with-parent");
    wrapped.add("--unshare-all");
    wrapped.add("--new-session");
    wrapped.add("--proc");
    wrapped.add("/proc");
    wrapped.add("--dev");
    wrapped.add("/dev");
    wrapped.add("--size");
    wrapped.add(Long.toString(properties.getMaxJobDiskBytes() / 2));
    wrapped.add("--tmpfs");
    wrapped.add("/tmp");
    for (String runtime : List.of("/usr", "/lib", "/lib64", "/bin", "/sbin")) {
      if (Files.exists(Path.of(runtime))) {
        wrapped.add("--ro-bind");
        wrapped.add(runtime);
        wrapped.add(runtime);
      }
    }
    if (Files.isRegularFile(Path.of("/etc/ld.so.cache"))) {
      wrapped.add("--ro-bind");
      wrapped.add("/etc/ld.so.cache");
      wrapped.add("/etc/ld.so.cache");
    }
    Set<Path> createdDirs = new LinkedHashSet<>();
    for (Path dir : context.writable) {
      if (!MouseVideoWorkspace.isBoundedMount(dir, properties.getMaxJobDiskBytes() / 2)) {
        throw new IOException("Restricted execution requires a bounded job filesystem");
      }
      addDirs(wrapped, createdDirs, dir);
      wrapped.add("--bind");
      wrapped.add(dir.toString());
      wrapped.add(dir.toString());
    }
    for (Path file : context.readOnlyFiles) {
      boolean insideWritable = context.writable.stream().anyMatch(file::startsWith);
      if (insideWritable || isRuntimePath(file)) {
        continue;
      }
      if (file.getParent() != null) {
        addDirs(wrapped, createdDirs, file.getParent());
      }
      wrapped.add("--ro-bind");
      wrapped.add(file.toString());
      wrapped.add(file.toString());
    }
    Path home = context.writable.isEmpty() ? Path.of("/tmp") : context.writable.iterator().next();
    wrapped.add("--remount-ro");
    wrapped.add("/");
    wrapped.add("--remount-ro");
    wrapped.add("/dev");
    wrapped.add("--chdir");
    wrapped.add(home.toString());
    wrapped.add("--clearenv");
    wrapped.add("--setenv");
    wrapped.add("PATH");
    wrapped.add("/usr/bin:/bin");
    wrapped.add("--setenv");
    wrapped.add("HOME");
    wrapped.add(home.toString());
    wrapped.add("--setenv");
    wrapped.add("TMPDIR");
    wrapped.add("/tmp");
    wrapped.add("--setenv");
    wrapped.add("LANG");
    wrapped.add("C.UTF-8");
    wrapped.add("--");
    wrapped.addAll(command);
    return List.copyOf(wrapped);
  }

  private static void addDirs(List<String> wrapped, Set<Path> created, Path dir) {
    List<Path> chain = new ArrayList<>();
    Path current = dir;
    while (current != null && current.getParent() != null) {
      chain.add(0, current);
      current = current.getParent();
    }
    for (Path step : chain) {
      if (created.add(step)) {
        wrapped.add("--dir");
        wrapped.add(step.toString());
      }
    }
  }

  private boolean probe() {
    Path root = properties.rootPath();
    try {
      if (!MouseVideoWorkspace.toolsAvailable()
          || properties.getMaxJobDiskBytes() < 32L * 1024 * 1024) {
        return false;
      }
      Files.createDirectories(root);
      String execution = "readiness-" + java.util.UUID.randomUUID();
      MouseVideoProperties probeProperties = new MouseVideoProperties();
      probeProperties.setDirectory(root.toString());
      probeProperties.setRestrictedLauncher(true);
      probeProperties.setMaxJobDiskBytes(properties.getMaxJobDiskBytes());
      try (MouseVideoWorkspace volume = MouseVideoWorkspace.open(probeProperties, execution)) {
        Context context = new Context();
        context.writable.add(volume.path());
        List<String> command = buildWrap(List.of("/bin/true"), context);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          return false;
        }
        boolean success = process.exitValue() == 0;
        volume.close();
        Files.deleteIfExists(root.resolve("work-volumes").resolve(execution + ".ext2"));
        Files.deleteIfExists(volume.path());
        return success;
      }
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  /** Reads the child's effective cgroup limits for operator evidence (best-effort, private). */
  public Map<String, String> readEffectiveLimits(Path cgroupProcs) {
    Map<String, String> limits = new LinkedHashMap<>();
    Path directory = cgroupProcs == null ? null : cgroupProcs.getParent();
    if (directory == null) {
      return limits;
    }
    for (String file : List.of("memory.max", "cpu.max", "pids.max", "cgroup.procs")) {
      Path path = directory.resolve(file);
      try {
        if (Files.isRegularFile(path)) {
          limits.put(file, Files.readString(path, StandardCharsets.UTF_8).trim());
        }
      } catch (IOException ignored) {
        // Diagnostics only; absence is reported by omission.
      }
    }
    return limits;
  }
}
