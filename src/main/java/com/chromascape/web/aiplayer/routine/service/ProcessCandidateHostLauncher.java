package com.chromascape.web.aiplayer.routine.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the Micro6 host ({@code CandidateHostMain --run}) as a child process for one job.
 *
 * <p>The job is written as one line and stdin stays open: closing it is how the host learns its
 * owner is gone, so it is closed only to cancel or after the outcome arrived. Cancellation first
 * sends {@code cancel}, then waits a grace period for the host's own outcome; only then is the
 * process tree destroyed. Stdout is bounded to one outcome line; stderr keeps a bounded tail.
 *
 * <p>SC-2106 R9.2: a change of the pause flag is sent as a {@code pause} or {@code resume} line.
 * The host answers with {@code sc2106-host-status/v1} lines when the run actually parks or resumes;
 * those reach the listener, and the outcome is the first other line. Status lines belong to a
 * {@code sc2106-host-job/v2} job, which a host without pause control refuses before launching
 * anything. At most {@value #MAX_STATUS_LINES} status lines are accepted; a host that writes more
 * is stopped and its run reported lost, never successful.
 */
public class ProcessCandidateHostLauncher implements CandidateHostLauncher {
  static final int MAX_OUTCOME_BYTES = 32 << 20;
  static final int STDERR_TAIL_BYTES = 64 << 10;
  static final String STATUS_SCHEMA = "sc2106-host-status/v1";
  static final int MAX_STATUS_LINES = 1024;

  /** What {@link #readOutcome} returns when the host exceeded {@link #MAX_STATUS_LINES}. */
  private static final byte[] STATUS_FLOOD = new byte[0];

  private static final Duration CANCEL_GRACE = Duration.ofSeconds(30);
  private static final long POLL_MILLIS = 100L;

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  private final List<String> command;
  private final Map<String, String> environment;
  private final Path directory;

  /**
   * @param command the host command, ending in {@code CandidateHostMain --run}
   * @param environment variables added to the host's environment, such as {@code
   *     SC2106_TRUST_POLICY}
   * @param directory the host's working directory
   */
  public ProcessCandidateHostLauncher(
      List<String> command, Map<String, String> environment, Path directory) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("a host command is required");
    }
    this.command = List.copyOf(command);
    this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    this.directory = directory;
  }

  @Override
  public HostOutcome run(Map<String, Object> job, Duration timeout, AtomicBoolean cancel) {
    return run(job, timeout, new HostControl(cancel, new AtomicBoolean(), null));
  }

  @Override
  public HostOutcome run(Map<String, Object> job, Duration timeout, HostControl control) {
    AtomicBoolean cancel = control.cancel();
    AtomicBoolean pause = control.pause() == null ? new AtomicBoolean() : control.pause();
    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().putAll(environment);
      if (directory != null) {
        builder.directory(directory.toFile());
      }
      process = builder.start();
    } catch (IOException e) {
      // Nothing was handed over, so nothing can have run.
      return new HostOutcome(null, false, "HOST_START_FAILED: " + e.getMessage(), null, "");
    }
    CompletableFuture<byte[]> stdout =
        CompletableFuture.supplyAsync(
            () -> readOutcome(process.getInputStream(), control.listener()));
    CompletableFuture<String> stderr =
        CompletableFuture.supplyAsync(() -> tail(process.getErrorStream()));
    OutputStream stdin = process.getOutputStream();
    try {
      stdin.write(MAPPER.writeValueAsBytes(job));
      stdin.write('\n');
      stdin.flush();
    } catch (IOException e) {
      destroy(process);
      // The host may have read part of the job; treat it as handed over.
      return new HostOutcome(
          null, true, "HOST_JOB_WRITE_FAILED: " + e.getMessage(), null, stderr.join());
    }

    long deadline = System.nanoTime() + timeout.toNanos();
    boolean cancelSent = false;
    // A job that starts paused is already parked on the host; a resume before the first poll
    // must still be sent.
    boolean pauseSent = Boolean.TRUE.equals(job.get("startPaused"));
    long cancelDeadline = Long.MAX_VALUE;
    while (!stdout.isDone()) {
      long now = System.nanoTime();
      boolean paused = pause.get();
      if (!cancelSent && paused != pauseSent) {
        pauseSent = paused;
        try {
          stdin.write((paused ? "pause\n" : "resume\n").getBytes(StandardCharsets.UTF_8));
          stdin.flush();
        } catch (IOException ignored) {
          // A host that no longer reads stdin ends with its outcome or the deadline.
        }
      }
      if (!cancelSent && (cancel.get() || now >= deadline)) {
        cancelSent = true;
        cancelDeadline = now + CANCEL_GRACE.toNanos();
        try {
          stdin.write("cancel\n".getBytes(StandardCharsets.UTF_8));
          stdin.flush();
        } catch (IOException ignored) {
          // A host that no longer reads stdin is handled by the grace deadline.
        }
      }
      if (cancelSent && now >= cancelDeadline) {
        destroy(process);
        return new HostOutcome(
            null,
            true,
            cancel.get() ? "HOST_CANCEL_UNACKNOWLEDGED" : "HOST_TIMEOUT",
            null,
            stderr.join());
      }
      sleep(cancel);
    }
    byte[] line = stdout.join();
    if (line == STATUS_FLOOD) {
      destroy(process);
      return new HostOutcome(null, true, "HOST_STATUS_FLOOD", null, stderr.join());
    }
    closeQuietly(stdin);
    Integer exit = awaitExit(process);
    String errTail = stderr.join();
    if (line == null || line.length == 0) {
      return new HostOutcome(null, true, "HOST_EXITED_WITHOUT_OUTCOME", exit, errTail);
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> document = MAPPER.readValue(line, Map.class);
      return new HostOutcome(document, false, null, exit, errTail);
    } catch (IOException e) {
      return new HostOutcome(null, true, "HOST_OUTCOME_UNREADABLE", exit, errTail);
    }
  }

  /**
   * The first stdout line that is not a host status line, or null on EOF or when a line exceeds the
   * bound. Status lines are handed to {@code listener}; past {@link #MAX_STATUS_LINES} of them this
   * returns {@link #STATUS_FLOOD}.
   */
  private static byte[] readOutcome(InputStream in, PauseListener listener) {
    for (int statusLines = 1; ; statusLines++) {
      byte[] line = readLine(in);
      if (line == null) {
        return null;
      }
      Boolean paused = pausedStatus(line);
      if (paused == null) {
        return line;
      }
      if (statusLines > MAX_STATUS_LINES) {
        return STATUS_FLOOD;
      }
      if (listener != null) {
        listener.onPause(paused);
      }
    }
  }

  /** The {@code paused} flag of a host status line, or null when the line is not one. */
  static Boolean pausedStatus(byte[] line) {
    try {
      Map<?, ?> document = MAPPER.readValue(line, Map.class);
      if (STATUS_SCHEMA.equals(document.get("schema"))
          && document.get("paused") instanceof Boolean paused) {
        return paused;
      }
    } catch (IOException | RuntimeException e) {
      // Not a status line; the caller treats it as the outcome.
    }
    return null;
  }

  /** One line of stdout, or null on EOF or when it exceeds the bound. */
  private static byte[] readLine(InputStream in) {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    try {
      int value;
      while ((value = in.read()) >= 0) {
        if (value == '\n') {
          return line.toByteArray();
        }
        if (line.size() >= MAX_OUTCOME_BYTES) {
          return null;
        }
        line.write(value);
      }
    } catch (IOException e) {
      return null;
    }
    return line.size() == 0 ? null : line.toByteArray();
  }

  private static String tail(InputStream in) {
    byte[] ring = new byte[STDERR_TAIL_BYTES];
    long total = 0;
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = in.read(buffer)) >= 0) {
        for (int i = 0; i < read; i++) {
          ring[(int) (total++ % ring.length)] = buffer[i];
        }
      }
    } catch (IOException ignored) {
      // Keep whatever arrived.
    }
    int size = (int) Math.min(total, ring.length);
    byte[] out = new byte[size];
    int start = (int) (total <= ring.length ? 0 : total % ring.length);
    for (int i = 0; i < size; i++) {
      out[i] = ring[(start + i) % ring.length];
    }
    return new String(out, StandardCharsets.UTF_8);
  }

  private static Integer awaitExit(Process process) {
    try {
      if (process.waitFor(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
        return process.exitValue();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    destroy(process);
    return null;
  }

  private static void destroy(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    try {
      process.waitFor(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The host has already answered.
    }
  }

  /** An interrupted caller cancels the run; the host still gets its grace period to answer. */
  private static void sleep(AtomicBoolean cancel) {
    try {
      Thread.sleep(POLL_MILLIS);
    } catch (InterruptedException e) {
      cancel.set(true);
    }
  }
}
