package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A fixed-size filesystem for a single execution. The trusted FUSE server stays outside the decoder
 * cgroup, and the backing image is never visible to the sandbox. Both file data and metadata
 * consume the same finite volume. Confirmed closure removes the private image; crash remnants are
 * aged out only when unmounted. An unavailable mount fails before any worker starts.
 */
public final class MouseVideoWorkspace implements AutoCloseable {

  private final Path path;
  private final Process server;
  private final Path image;
  private boolean closed;

  private MouseVideoWorkspace(Path path, Process server, Path image) {
    this.path = path;
    this.server = server;
    this.image = image;
  }

  public Path path() {
    return path;
  }

  public static boolean toolsAvailable() {
    return Files.isExecutable(Path.of("/usr/bin/fuse2fs"))
        && Files.isExecutable(Path.of("/usr/sbin/mke2fs"))
        && Files.isExecutable(Path.of("/usr/bin/fusermount3"))
        && Files.isExecutable(Path.of("/usr/bin/setpriv"))
        && Files.exists(Path.of("/dev/fuse"));
  }

  /** Creates a fresh execution workspace; never mounts over or reuses existing job output. */
  public static MouseVideoWorkspace open(MouseVideoProperties properties, String execution)
      throws IOException {
    if (!execution.matches("[a-zA-Z0-9-]+")) {
      throw new IOException("Invalid execution workspace identity");
    }
    Path root = properties.rootPath();
    Files.createDirectories(root);
    Path jobs = root.resolve("jobs");
    Files.createDirectories(jobs);
    if (!jobs.toRealPath().startsWith(root.toRealPath())) {
      throw new IOException("Workspace root escapes the media store");
    }
    Path workspace = jobs.resolve(execution);
    if (!properties.isRestrictedLauncher()) {
      Files.createDirectories(workspace);
      return new MouseVideoWorkspace(workspace, null, null);
    }
    if (!toolsAvailable() || properties.getMaxJobDiskBytes() < 32L * 1024 * 1024) {
      throw new IOException("Bounded workspace prerequisites are unavailable");
    }
    Path volumes = root.resolve("work-volumes");
    Files.createDirectories(volumes);
    if (!volumes.toRealPath().startsWith(root.toRealPath())) {
      throw new IOException("Volume root escapes the media store");
    }
    Files.createDirectory(workspace);
    Path image = volumes.resolve(execution + ".ext2");
    // The private /tmp tmpfs gets the other half of the execution's aggregate writable budget.
    long volumeBytes = properties.getMaxJobDiskBytes() / 2;
    try (var channel =
        java.nio.channels.FileChannel.open(
            image, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(volumeBytes - 1);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    run(List.of("/usr/sbin/mke2fs", "-q", "-F", "-t", "ext2", "-m", "0", image.toString()));
    Process process =
        new ProcessBuilder(
                "/usr/bin/setpriv",
                "--pdeathsig",
                "TERM",
                "--",
                "/usr/bin/fuse2fs",
                "-f",
                "-o",
                "fakeroot,nodev,nosuid,noexec,cache_size=4M",
                image.toString(),
                workspace.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    boolean mounted = false;
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (process.isAlive() && System.nanoTime() < deadline) {
        if (isBoundedMount(workspace, volumeBytes)) {
          mounted = true;
          return new MouseVideoWorkspace(workspace, process, image);
        }
        Thread.sleep(20);
      }
      throw new IOException("Bounded workspace could not be mounted");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while mounting workspace", interrupted);
    } finally {
      if (!mounted) {
        process.destroyForcibly();
      }
    }
  }

  /** Checks an actual mount, rather than trusting a directory or a post-exit byte count. */
  static boolean isBoundedMount(Path path, long maximum) throws IOException {
    var store = Files.getFileStore(path);
    return store.type().equals("fuse.ext4") && store.getTotalSpace() <= maximum;
  }

  /** Ages out private diagnostic volumes only after they are no longer mounted. */
  static int sweepUnmounted(MouseVideoProperties properties, java.time.Instant cutoff) {
    Path volumes = properties.rootPath().resolve("work-volumes");
    if (!Files.isDirectory(volumes) || Files.isSymbolicLink(volumes)) {
      return 0;
    }
    int removed = 0;
    try (var files = Files.list(volumes)) {
      for (Path image : files.toList()) {
        String filename = image.getFileName().toString();
        if (!filename.matches("[a-zA-Z0-9-]+\\.ext2") || Files.isSymbolicLink(image)) {
          continue;
        }
        Path workspace =
            properties
                .rootPath()
                .resolve("jobs")
                .resolve(filename.substring(0, filename.length() - 5));
        try {
          if (Files.isSymbolicLink(workspace)
              || Files.getLastModifiedTime(image).toInstant().isAfter(cutoff)
              || (Files.exists(workspace)
                  && Files.getFileStore(workspace).type().startsWith("fuse"))) {
            continue;
          }
          if (Files.deleteIfExists(image)) {
            removed++;
          }
          if (Files.isDirectory(workspace)) {
            Files.delete(workspace); // An unexpected non-empty directory is preserved.
          }
        } catch (IOException uncertain) {
          // A mounted/disconnected/raced volume is retained for reconciliation.
        }
      }
    } catch (IOException unavailable) {
      // Retry next sweep. Never infer that inaccessible volumes are safe to remove.
    }
    return removed;
  }

  private static void run(List<String> command) throws IOException {
    Process process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Workspace helper timed out");
      }
      if (process.exitValue() != 0) {
        throw new IOException("Workspace helper failed");
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Workspace helper interrupted", interrupted);
    }
  }

  @Override
  public void close() throws IOException {
    if (server != null && !closed) {
      // Never lazy-unmount an execution that may still have open files. Failure leaves its volume
      // intact for operator reconciliation, instead of deleting bytes or concealing live writers.
      boolean interrupted = Thread.interrupted();
      try {
        run(List.of("/usr/bin/fusermount3", "-u", path.toString()));
        if (!server.waitFor(5, TimeUnit.SECONDS)) {
          throw new IOException("Workspace server did not stop after unmount");
        }
        closed = true;
        try {
          Files.deleteIfExists(image);
        } catch (IOException retained) {
          // The server is stopped and the mount is gone. An inaccessible private image may be
          // collected later; it does not imply that an execution is still writing.
          org.slf4j.LoggerFactory.getLogger(MouseVideoWorkspace.class)
              .warn("Closed video workspace image retained for sweep: {}", image);
        }
      } catch (InterruptedException failure) {
        interrupted = true;
        throw new MouseVideoException(
            MouseVideoException.CODE_CLEANUP_UNCONFIRMED, "Workspace cleanup interrupted", failure);
      } catch (IOException failure) {
        throw new MouseVideoException(
            MouseVideoException.CODE_CLEANUP_UNCONFIRMED,
            "Workspace cleanup could not be confirmed",
            failure);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
