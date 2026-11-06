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
 *
 * <p>SC-2106 R4.2: a {@code sc2106-host-binding/v1} line carries the runner's worker binding. It
 * goes to the control's binding handler, which commits it before this side answers {@code bound
 * <bindingId>}; a refusal, a handler failure or a missing handler answers {@code unbound
 * <bindingId>}. Binding lines count against the same bound as status lines.
 *
 * <p>SC-2106 P3 (R4.3): a {@code sc2106-host-command-intent/v1} line asks to send one gated worker
 * call. The control's command handler records it before this side answers {@code permit
 * <intentId>}; otherwise {@code deny <intentId> <code>}. A {@code sc2106-host-command-receipt/v1}
 * line reports what the worker answered and gets {@code recorded} or {@code unrecorded}. Command
 * lines have their own bound of {@value #MAX_COMMAND_LINES}.
 */
public class ProcessCandidateHostLauncher implements CandidateHostLauncher {
  static final int MAX_OUTCOME_BYTES = 32 << 20;
  static final int STDERR_TAIL_BYTES = 64 << 10;
  static final String STATUS_SCHEMA = "sc2106-host-status/v1";
  static final String BINDING_SCHEMA = "sc2106-host-binding/v1";

  /** The longest binding id answered; the runner sends a UUID. */
  static final int MAX_BINDING_ID_CHARS = 128;

  static final int MAX_STATUS_LINES = 1024;

  /** SC-2106 P3: a runner's request to send one gated worker call. */
  static final String COMMAND_INTENT_SCHEMA = "sc2106-host-command-intent/v1";

  /** SC-2106 P3: what the worker answered to a permitted call. */
  static final String COMMAND_RECEIPT_SCHEMA = "sc2106-host-command-receipt/v1";

  /** Intent and receipt lines accepted per run; the runner's step budget keeps a run far below. */
  static final int MAX_COMMAND_LINES = 1 << 18;

  /** What {@link #readOutcome} returns when the host exceeded {@link #MAX_STATUS_LINES}. */
  private static final byte[] STATUS_FLOOD = new byte[0];

  /** What {@link #readOutcome} returns when the host exceeded {@link #MAX_COMMAND_LINES}. */
  private static final byte[] COMMAND_FLOOD = new byte[0];

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
    OutputStream stdin = process.getOutputStream();
    CompletableFuture<byte[]> stdout =
        CompletableFuture.supplyAsync(
            () ->
                readOutcome(
                    process.getInputStream(),
                    control.listener(),
                    binding -> answerBinding(stdin, control.binding(), binding),
                    command -> answerCommand(stdin, control.commands(), command)));
    CompletableFuture<String> stderr =
        CompletableFuture.supplyAsync(() -> tail(process.getErrorStream()));
    try {
      synchronized (stdin) {
        stdin.write(MAPPER.writeValueAsBytes(job));
        stdin.write('\n');
        stdin.flush();
      }
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
          writeLine(stdin, paused ? "pause" : "resume");
        } catch (IOException ignored) {
          // A host that no longer reads stdin ends with its outcome or the deadline.
        }
      }
      if (!cancelSent && (cancel.get() || now >= deadline)) {
        cancelSent = true;
        cancelDeadline = now + CANCEL_GRACE.toNanos();
        try {
          writeLine(stdin, "cancel");
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
    if (line == COMMAND_FLOOD) {
      destroy(process);
      return new HostOutcome(null, true, "HOST_COMMAND_FLOOD", null, stderr.join());
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
  private static byte[] readOutcome(
      InputStream in,
      PauseListener listener,
      java.util.function.Consumer<Map<String, Object>> bindings,
      java.util.function.Consumer<Map<String, Object>> commands) {
    int commandLines = 0;
    for (int statusLines = 1; ; statusLines++) {
      byte[] line = readLine(in);
      if (line == null) {
        return null;
      }
      // Command lines scale with the run's steps, so they have their own, larger bound (P3).
      Map<String, Object> command = commandLine(line);
      if (command != null) {
        if (++commandLines > MAX_COMMAND_LINES) {
          return COMMAND_FLOOD;
        }
        commands.accept(command);
        statusLines--;
        continue;
      }
      Map<String, Object> binding = bindingLine(line);
      Boolean paused = binding == null ? pausedStatus(line) : null;
      if (binding == null && paused == null) {
        return line;
      }
      if (statusLines > MAX_STATUS_LINES) {
        return STATUS_FLOOD;
      }
      if (binding != null) {
        bindings.accept(binding);
        continue;
      }
      if (listener != null) {
        listener.onPause(paused);
      }
    }
  }

  /**
   * A host command intent or receipt line with a usable {@code intentId}, or null when the line is
   * not one.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> commandLine(byte[] line) {
    try {
      Map<String, Object> document = MAPPER.readValue(line, Map.class);
      Object schema = document.get("schema");
      if ((COMMAND_INTENT_SCHEMA.equals(schema) || COMMAND_RECEIPT_SCHEMA.equals(schema))
          && document.get("intentId") instanceof String id
          && !id.isBlank()
          && id.length() <= MAX_BINDING_ID_CHARS
          && id.chars().noneMatch(Character::isWhitespace)) {
        return document;
      }
    } catch (IOException | RuntimeException e) {
      // Not a command line.
    }
    return null;
  }

  /**
   * Answers one command line. An intent gets {@code permit <intentId>} only after {@code handler}
   * durably permitted it, otherwise {@code deny <intentId> <code>}; a receipt gets {@code recorded
   * <intentId>} or {@code unrecorded <intentId>}. Runs on the stdout reader, so no answer is
   * written before the decision returns.
   */
  private static void answerCommand(
      OutputStream stdin, CommandHandler handler, Map<String, Object> command) {
    Object id = command.get("intentId");
    String answer;
    if (COMMAND_INTENT_SCHEMA.equals(command.get("schema"))) {
      String refusal;
      try {
        refusal = handler == null ? "NO_COMMAND_HANDLER" : handler.permit(command);
      } catch (RuntimeException e) {
        refusal = "COMMAND_HANDLER_FAILED";
      }
      answer = refusal == null ? "permit " + id : "deny " + id + " " + refusal;
    } else {
      boolean recorded;
      try {
        recorded = handler != null && handler.receipt(command);
      } catch (RuntimeException e) {
        recorded = false;
      }
      answer = (recorded ? "recorded " : "unrecorded ") + id;
    }
    try {
      writeLine(stdin, answer);
    } catch (IOException ignored) {
      // A host that no longer reads stdin times its runner out; nothing was permitted for it.
    }
  }

  /** A host binding line with a usable {@code bindingId}, or null when the line is not one. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> bindingLine(byte[] line) {
    try {
      Map<String, Object> document = MAPPER.readValue(line, Map.class);
      if (BINDING_SCHEMA.equals(document.get("schema"))
          && document.get("bindingId") instanceof String id
          && !id.isBlank()
          && id.length() <= MAX_BINDING_ID_CHARS
          && id.chars().noneMatch(Character::isWhitespace)) {
        return document;
      }
    } catch (IOException | RuntimeException e) {
      // Not a binding line.
    }
    return null;
  }

  /**
   * Answers one binding: {@code bound} only after {@code handler} accepted (committed) it. Runs on
   * the stdout reader, so no answer is written before the decision returns.
   */
  private static void answerBinding(
      OutputStream stdin, BindingHandler handler, Map<String, Object> binding) {
    boolean accepted;
    try {
      accepted = handler != null && handler.accept(binding);
    } catch (RuntimeException e) {
      accepted = false;
    }
    try {
      writeLine(stdin, (accepted ? "bound " : "unbound ") + binding.get("bindingId"));
    } catch (IOException ignored) {
      // A host that no longer reads stdin times its runner out; nothing was accepted on its behalf.
    }
  }

  private static void writeLine(OutputStream stdin, String line) throws IOException {
    synchronized (stdin) {
      stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      stdin.flush();
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
