package com.chromascape.web.replay.kernel;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalSensitiveTextSanitizer;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of a single xRSPS kernel child process.
 *
 * <p>Protocol: newline-delimited JSON-RPC over stdin/stdout, launched as {@code node <entrypoint>
 * --stdio} through {@link XrspsProcessBuilderFactory}.
 *
 * <p>Guarantees: - every request carries a unique id; responses are correlated by id. An unexpected
 * id is accepted only from the bounded set of abandoned (timed-out) ids; anything else is a
 * protocol violation that terminates the process tree. - reads are BYTE-BOUNDED: stdout lines are
 * capped while being read ({@link BoundedUtf8LineReader}), stderr volume is capped, and the
 * response inbox is a bounded queue whose overflow is itself a protocol violation. - malformed
 * output of any kind produces a stable protocol error and full process-tree destruction. - close()
 * performs the complete dispose handshake: send dispose, wait up to the shutdown deadline for the
 * matching {disposed:true} response, close stdin only after the acknowledgement, wait for natural
 * exit, then escalate to destroy/destroyForcibly and join the reader threads within bounded
 * deadlines.
 */
public class XrspsKernelProcess implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(XrspsKernelProcess.class);
  private static final int MAX_ABANDONED_IDS = 16;
  private static final int MAX_DIAGNOSTIC_PREVIEW_BYTES = 8 * 1024;
  private static final Pattern SENSITIVE_ASSIGNMENT =
      Pattern.compile(
          "(?i)(\\b[\\w.-]*(?:password|passwd|secret|token|api[-_]?key|"
              + "authorization|bearer|credential|private[-_]?key)[\\w.-]*\\b)"
              + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)");
  private static final Pattern SENSITIVE_TOKEN =
      Pattern.compile(
          "(?i)(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+|"
              + "AKIA[0-9A-Z]{16}|\\S*(?:secret|token|password|api[_-]?key)\\S*)"
              + "(?![A-Za-z0-9])");

  private final ReplayProperties.Xrsps config;
  private final ObjectMapper mapper;
  private final KernelProtocolCodec codec;

  private Process process;
  private BufferedWriter stdin;
  private final BlockingQueue<KernelProtocolCodec.KernelMessage> inbox;
  /**
   * The worker protocol is deliberately one-request-at-a-time. A shared response queue cannot
   * correlate concurrent consumers safely: either caller may otherwise remove the other caller's
   * response before inspecting its id. The same lock also owns the dispose handshake so a dispose
   * acknowledgement can never be consumed by a regular request.
   */
  private final ReentrantLock protocolLock = new ReentrantLock(true);
  private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
  private volatile boolean disposed = false;

  /** Bounded set of request ids whose callers already timed out and left. */
  private final Set<String> abandonedIds = new HashSet<>();

  /** One atomic fatal slot: the FIRST protocol fatality wins; later ones are dropped. */
  private final AtomicReference<KernelProtocolCodec.KernelMessage> deferredFatal =
      new AtomicReference<>();

  private final AtomicReference<KernelProtocolCodec.KernelMessage> observedFatal =
      new AtomicReference<>();
  private final AtomicReference<Integer> observedExitCode = new AtomicReference<>();
  private final AtomicLong stderrBytes = new AtomicLong(0);
  private final ByteArrayOutputStream stderrPreviewBytes = new ByteArrayOutputStream();
  private volatile String sanitizedStderrPreview = "";
  private final String diagnosticReference = "xrsps-" + UUID.randomUUID();
  private final AtomicInteger messageIdCounter = new AtomicInteger(1);
  private Thread readerThread;
  private Thread stderrThread;

  /** ATOMIC ownership: exactly one thread may report the first protocol violation. */
  private final AtomicBoolean violationOwned = new AtomicBoolean(false);

  /**
   * The ONE absolute shutdown deadline selected by the FIRST closing thread. Every stage of that
   * close and every concurrent closer read this exact value — never a fresh {@code
   * shutdownTimeoutMs * 2} window. Zero until the first closer selects it.
   */
  private final AtomicLong shutdownDeadlineNanos = new AtomicLong(0);

  public XrspsKernelProcess(ReplayProperties.Xrsps config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
    this.codec = new KernelProtocolCodec(mapper);
    this.inbox = new ArrayBlockingQueue<>(Math.max(2, config.getResponseQueueCapacity()));
  }

  public void start() {
    try {
      process = XrspsProcessBuilderFactory.create(config, "--stdio").start();

      stdin =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

      Thread stdout = new Thread(this::drainStdout, "xrsps-stdout-reader");
      Thread stderr =
          new Thread(() -> drainStderr(process.getErrorStream()), "xrsps-stderr-reader");
      stdout.setDaemon(true);
      stderr.setDaemon(true);
      // Readers start BEFORE any request so startup output can never deadlock on a pipe.
      stderr.start();
      stdout.start();
      this.readerThread = stdout;
      this.stderrThread = stderr;

      log.info(
          "Started xRSPS kernel process: {} {} --stdio",
          config.getExecutable(),
          config.getEntrypoint());
    } catch (IOException e) {
      terminateTree();
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Failed to start xRSPS kernel process",
          "Verify REPLAY_XRSPS_EXECUTABLE and REPLAY_XRSPS_ENTRYPOINT point at the built worker",
          null,
          e);
    }
  }

  /** Sends a request and waits for the response with the matching id. */
  public KernelProtocolCodec.KernelMessage send(String method, JsonNode params) {
    return send(method, params, config.getRequestTimeoutMs());
  }

  public KernelProtocolCodec.KernelMessage send(String method, JsonNode params, long timeoutMs) {
    protocolLock.lock();
    try {
      return sendLocked(method, params, timeoutMs);
    } finally {
      protocolLock.unlock();
    }
  }

  /** Sends one request while holding {@link #protocolLock}. */
  private KernelProtocolCodec.KernelMessage sendLocked(
      String method, JsonNode params, long timeoutMs) {
    ensureAlive();
    String id = String.valueOf(messageIdCounter.getAndIncrement());

    try {
      String request = codec.encode(id, method, params);
      synchronized (stdin) {
        stdin.write(request);
        stdin.flush();
      }
    } catch (IOException e) {
      abandon(id);
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "Failed to send kernel request (" + processDiagnostics() + ")",
          null,
          null,
          e);
    }

    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    try {
      while (true) {
        KernelProtocolCodec.KernelMessage fatal = pollDeferredFatal();
        if (fatal != null) {
          abandon(id);
          throw protocolFailure(fatal);
        }
        if (shutdownInitiated.get()) {
          abandon(id);
          throw new ReplayException(
              ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              true,
              "xRSPS kernel shutdown started while waiting for a response",
              null,
              "method=" + method,
              null);
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
          abandon(id);
          throw new ReplayException(
              ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
              org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
              true,
              "xRSPS kernel request timed out (" + processDiagnostics() + ")",
              "Increase replay.xrsps.request-timeout-ms or investigate the worker",
              "method=" + method,
              null);
        }
        // Bounded wait bounded by the remaining time; wake at most every 50 MILLISECONDS.
        long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50));
        KernelProtocolCodec.KernelMessage msg = inbox.poll(waitNanos, TimeUnit.NANOSECONDS);
        if (msg == null) {
          continue; // deadline check loops
        }
        if (msg.processExited()) {
          abandon(id);
          throw new ReplayException(
              ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              true,
              "xRSPS kernel process exited without a response (" + processDiagnostics() + ")",
              "Verify the worker entrypoint and inspect the captured process diagnostics",
              "method=" + method,
              null);
        }
        if (msg.fatal() && msg.id() == null) {
          // Fatal reader-side messages have no response id. They describe the already-observed
          // process/protocol failure and must not be mistaken for an unexpected response to this
          // request (which would hide the original diagnostic behind a false correlation error).
          abandon(id);
          throw protocolFailure(msg);
        }
        if (!id.equals(msg.id())) {
          handleUnexpectedId(msg);
          continue;
        }
        return msg;
      }
    } catch (InterruptedException e) {
      abandon(id);
      Thread.currentThread().interrupt();
      throw new ReplayException(
          ReplayErrorCode.INTERNAL_ERROR,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          true,
          "Interrupted while waiting for kernel response",
          null,
          null,
          e);
    }
  }

  /**
   * A response for an id nobody is waiting for is accepted ONLY when it belongs to a previously
   * abandoned (timed-out) request; anything else violates the one-request-at-a-time protocol.
   */
  private void handleUnexpectedId(KernelProtocolCodec.KernelMessage msg) {
    synchronized (abandonedIds) {
      if (abandonedIds.remove(msg.id())) {
        log.debug("Discarded late response for abandoned kernel request id {}", msg.id());
        return;
      }
    }
    protocolViolation("Response carried an unexpected id");
  }

  private void abandon(String id) {
    synchronized (abandonedIds) {
      if (abandonedIds.size() >= MAX_ABANDONED_IDS) {
        protocolViolation("Too many abandoned kernel requests");
        return;
      }
      abandonedIds.add(id);
    }
  }

  private void drainStdout() {
    try (BoundedUtf8LineReader reader =
        new BoundedUtf8LineReader(process.getInputStream(), config.getMaxStdoutLineBytes())) {
      // Keep draining through the COMPLETE dispose handshake: stopping just because
      // shutdownInitiated flipped would let an unrelated LATE line (e.g. an abandoned
      // response emitted right before the dispose acknowledgement) end the reader before
      // the ack arrives, breaking graceful disposal. Only EOF or a fatal read error ends
      // this loop; stream teardown during destruction surfaces as IOException below.
      while (true) {
        BoundedUtf8LineReader.Read read = reader.readLine();
        switch (read.status()) {
          case LINE -> {
            KernelProtocolCodec.KernelMessage msg = codec.decode(read.line());
            if (msg == null) {
              continue; // blank line
            }
            deliver(msg);
          }
          case OVERSIZED -> {
            protocolViolation(
                "Oversized stdout line from kernel exceeded the configured byte limit");
            return;
          }
          case MALFORMED_UTF8 -> {
            protocolViolation("Kernel emitted malformed UTF-8 on stdout");
            return;
          }
          case UNTERMINATED_AT_EOS -> {
            protocolViolation("Kernel stdout ended without newline termination");
            return;
          }
          case END_OF_STREAM -> {
            return;
          }
          default -> {
            return;
          }
        }
      }
    } catch (IOException ignored) {
      // Stream closed during teardown is expected.
    } finally {
      if (!shutdownInitiated.get()) {
        // EOF can be observed a few milliseconds before Process.isAlive() flips to false. Capture
        // the child's status before publishing the exit sentinel so the external failure keeps a
        // useful exit code without ever waiting unboundedly on a misbehaving child.
        captureProcessExitCode();
        deliver(KernelProtocolCodec.KernelMessage.exitWithoutResponse());
      }
    }
  }

  /** Bounded delivery: queue overflow is a protocol violation, never silent loss. */
  private void deliver(KernelProtocolCodec.KernelMessage msg) {
    if (msg.fatal()) {
      deferredFatal.compareAndSet(null, msg);
    }
    if (!inbox.offer(msg)) {
      protocolViolation("Kernel response queue capacity exceeded");
    }
  }

  /**
   * Returns the FIRST fatal message exactly once; subsequent polls return null. One atomic slot
   * replaces the former unbounded deque: protocol failures never accumulate unbounded.
   */
  private KernelProtocolCodec.KernelMessage pollDeferredFatal() {
    KernelProtocolCodec.KernelMessage fatal = deferredFatal.get();
    if (fatal == null) {
      return null;
    }
    return observedFatal.compareAndSet(null, fatal) ? fatal : null;
  }

  /**
   * Stderr reader with a LOGGING ceiling only. After the cap, the breach is recorded ONCE and
   * further bytes are read and discarded until EOF — this method must NEVER return while the child
   * remains alive, or a chatty worker would fill the OS pipe and deadlock.
   */
  private void drainStderr(InputStream raw) {
    byte[] chunk = new byte[4096];
    boolean breachLogged = false;
    try {
      int read;
      while ((read = raw.read(chunk)) != -1) {
        long totalBytes = stderrBytes.addAndGet(read);
        synchronized (stderrPreviewBytes) {
          int remaining =
              Math.max(0, MAX_DIAGNOSTIC_PREVIEW_BYTES - stderrPreviewBytes.size());
          if (remaining > 0) {
            stderrPreviewBytes.write(chunk, 0, Math.min(read, remaining));
          }
        }
        if (!breachLogged && totalBytes > config.getMaxStderrBytes()) {
          breachLogged = true;
          log.warn("xRSPS kernel exceeded max stderr bytes; discarding further output until EOF");
        }
      }
    } catch (IOException ignored) {
      // Stream closed during teardown is expected.
    } finally {
      logDiagnosticPreview();
    }
  }

  private void protocolViolation(String why) {
    if (!violationOwned.compareAndSet(false, true)) {
      return; // exactly one owner reports the first violation and terminates the tree
    }
    log.error("xRSPS kernel protocol violation: {}", why);
    KernelProtocolCodec.KernelMessage fatal =
        KernelProtocolCodec.KernelMessage.fatal(
            KernelProtocolCodec.PROTOCOL_ERROR_CODE, "Protocol violation: " + why);
    deferredFatal.compareAndSet(null, fatal);
    inbox.offer(fatal);
    long sharedDeadline = shutdownDeadlineNanos.get();
    if (shutdownInitiated.get() && sharedDeadline > 0) {
      terminateTreeWithinSharedDeadline(sharedDeadline);
    } else {
      terminateTree();
    }
  }

  private ReplayException protocolFailure(KernelProtocolCodec.KernelMessage fatal) {
    String code =
        fatal.error() != null
            ? fatal.error().path("code").asText(KernelProtocolCodec.PROTOCOL_ERROR_CODE)
            : KernelProtocolCodec.PROTOCOL_ERROR_CODE;
    return new ReplayException(
        ReplayErrorCode.XRSPS_PROTOCOL_ERROR,
        org.springframework.http.HttpStatus.BAD_GATEWAY,
        false,
        "xRSPS kernel protocol failure (code=" + code + ", " + processDiagnostics() + ")",
        null,
        null,
        null);
  }

  private void ensureAlive() {
    if (disposed || shutdownInitiated.get() || process == null || !process.isAlive()) {
      throw new ReplayException(
          ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          false,
          "xRSPS kernel process is not running (" + processDiagnostics() + ")",
          null,
          null,
          null);
    }
  }

  private String processDiagnostics() {
    logDiagnosticPreview();
    return "pid="
        + processPid()
        + ", exitCode="
        + processExitCode()
        + ", stderrBytes="
        + stderrBytesRead()
        + ", diagnosticRef="
        + diagnosticReference;
  }

  public boolean isAlive() {
    return process != null && process.isAlive() && !disposed && !shutdownInitiated.get();
  }

  /**
   * Returns the child PID for bounded launch diagnostics, or {@code -1} before {@link #start()}.
   */
  long processPid() {
    Process current = process;
    return current == null ? -1L : current.pid();
  }

  /**
   * Returns the child exit code once it has exited, or {@code null} while it is still running.
   */
  Integer processExitCode() {
    Integer observed = observedExitCode.get();
    if (observed != null) {
      return observed;
    }
    Process current = process;
    if (current == null || current.isAlive()) {
      return null;
    }
    try {
      int exitCode = current.exitValue();
      observedExitCode.compareAndSet(null, exitCode);
      return observedExitCode.get();
    } catch (IllegalThreadStateException stillRunning) {
      return null;
    }
  }

  /** Captures an exit status after stream EOF without allowing diagnostics to block indefinitely. */
  private void captureProcessExitCode() {
    Process current = process;
    if (current == null || observedExitCode.get() != null) {
      return;
    }
    try {
      // A child may close stdout and publish its OS exit status on slightly different scheduling
      // paths. Give that handoff a short bounded window; a child that only closes stdout while
      // remaining alive still cannot hold the reader indefinitely.
      if (current.waitFor(1, TimeUnit.SECONDS)) {
        observedExitCode.compareAndSet(null, current.exitValue());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IllegalThreadStateException ignored) {
      // The process can still be transitioning between waitFor and exitValue.
    }
  }

  /** Returns bytes consumed by the stderr drain, including bytes discarded after the log cap. */
  long stderrBytesRead() {
    return stderrBytes.get();
  }

  /** Returns the sanitized retained prefix for server-side bounded process diagnostics. */
  String stderrPreview() {
    return logDiagnosticPreview();
  }

  /** Stable correlation key for server logs and the safe external process diagnostic. */
  String diagnosticReference() {
    return diagnosticReference;
  }

  /**
   * Converts the retained child output into a bounded, log-safe preview. The raw byte buffer is
   * reset after conversion so the process object retains only sanitized text; callers never receive
   * raw worker stderr.
   */
  private String logDiagnosticPreview() {
    String preview;
    synchronized (stderrPreviewBytes) {
      if (stderrPreviewBytes.size() > 0) {
        String raw = stderrPreviewBytes.toString(StandardCharsets.UTF_8);
        preview = sanitizeDiagnosticPreview(raw);
        stderrPreviewBytes.reset();
        byte[] sanitized = preview.getBytes(StandardCharsets.UTF_8);
        stderrPreviewBytes.write(sanitized, 0, sanitized.length);
      } else {
        preview = sanitizedStderrPreview;
      }
      sanitizedStderrPreview = preview;
    }
    if (!preview.isBlank()) {
      log.debug(
          "xRSPS kernel stderr preview diagnosticRef={} preview={}",
          diagnosticReference,
          preview);
    }
    return preview;
  }

  private static String sanitizeDiagnosticPreview(String raw) {
    String normalized =
        raw.replaceAll("[\\p{Cntrl}&&[^\\t\\r\\n]]", "?")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ');
    // Apply the shared project sanitizer before the xRSPS-specific patterns. In particular, the
    // local assignment matcher would otherwise redact "Authorization: Bearer" first and leave the
    // bearer value behind, and it does not know about profile-email credential payloads.
    normalized = BuilderGoalSensitiveTextSanitizer.sanitize(normalized);
    normalized = SENSITIVE_ASSIGNMENT.matcher(normalized).replaceAll("$1$2[REDACTED]");
    normalized = SENSITIVE_TOKEN.matcher(normalized).replaceAll("[REDACTED]");
    byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_DIAGNOSTIC_PREVIEW_BYTES) {
      return normalized;
    }
    // Do not cut through a UTF-8 code point. Apart from producing replacement characters in the
    // log, decoding a partial multibyte sequence can make the retained text exceed the advertised
    // byte bound after it is re-encoded.
    int limit = MAX_DIAGNOSTIC_PREVIEW_BYTES;
    int end = limit;
    while (end > 0 && (bytes[end - 1] & 0xFF & 0xC0) == 0x80) {
      end--;
    }
    if (end < limit && end > 0) {
      int leadIndex = end - 1;
      int requiredBytes = utf8CodePointBytes(bytes[leadIndex] & 0xFF);
      if (requiredBytes > limit - leadIndex) {
        end = leadIndex;
      } else {
        end = limit;
      }
    }
    return new String(bytes, 0, end, StandardCharsets.UTF_8);
  }

  private static int utf8CodePointBytes(int leadingByte) {
    if ((leadingByte & 0x80) == 0) {
      return 1;
    }
    if ((leadingByte & 0xE0) == 0xC0) {
      return 2;
    }
    if ((leadingByte & 0xF0) == 0xE0) {
      return 3;
    }
    if ((leadingByte & 0xF8) == 0xF0) {
      return 4;
    }
    return 1;
  }

  /**
   * Complete dispose handshake:
   *
   * <ol>
   *   <li>mark shutdown initiated (response delivery stays ACTIVE);
   *   <li>the FIRST closer selects the ONE absolute shutdown deadline (monotonic clock, so
   *       wall-clock steps / NTP corrections never extend or truncate the window);
   *   <li>send JSON-RPC {@code dispose} with a fresh request id;
   *   <li>wait up to the shared deadline for the MATCHING response requiring {@code
   *       {disposed:true}};
   *   <li>close stdin ONLY after the acknowledgement so natural EOF exit remains possible;
   *   <li>await NATURAL process exit within the remaining shared budget;
   *   <li>only then destroy the tree, escalate to destroyForcibly, and reap within the single
   *       documented {@code FINAL_REAP_ALLOWANCE_MS};
   *   <li>join both reader threads against the SAME deadline — an already-expired deadline grants
   *       no additional join time.
   * </ol>
   *
   * Every concurrent closer reads the shared deadline selected by the first closer and waits only
   * until {@code disposed} flips or that deadline passes. The disposed flag flips only after
   * cleanup completes.
   */
  @Override
  public void close() {
    if (!shutdownInitiated.compareAndSet(false, true)) {
      awaitTerminationQuietly();
      return;
    }
    // One absolute deadline for the WHOLE tree, selected atomically by the first closer.
    shutdownDeadlineNanos.compareAndSet(
        0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getShutdownTimeoutMs()));
    long deadline = shutdownDeadlineNanos.get();
    try {
      boolean acked = false;
      if (stdin != null && process != null && process.isAlive()) {
        boolean protocolOwned = false;
        try {
          long remainingNanos = deadline - System.nanoTime();
          if (remainingNanos > 0) {
            protocolOwned = protocolLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
          }
          if (protocolOwned) {
            String disposeId = String.valueOf(messageIdCounter.getAndIncrement());
            try {
              synchronized (stdin) {
                stdin.write(codec.encode(disposeId, "dispose", null));
                stdin.flush();
              }
              acked = awaitDisposeAcknowledgement(disposeId, deadline);
              if (!acked) {
                log.warn("xRSPS kernel dispose acknowledgement not received within deadline");
              }
            } finally {
              protocolLock.unlock();
            }
          } else {
            log.warn("xRSPS kernel request was still in flight at the shutdown deadline");
          }
        } catch (IOException writeFailure) {
          log.debug("Graceful dispose write failed: {}", writeFailure.getMessage());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          log.debug("Interrupted while acquiring xRSPS protocol lock for dispose");
        }
      }
      if (acked && stdin != null) {
        try {
          stdin.close(); // natural EOF lets the worker exit cleanly
        } catch (IOException ignored) {
          // expected during teardown
        }
      }
      // Await NATURAL exit before any destruction.
      boolean exitedNaturally = false;
      if (process != null && acked) {
        while (System.nanoTime() < deadline) {
          if (!process.isAlive()) {
            exitedNaturally = true;
            break;
          }
          long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
          try {
            Thread.sleep(Math.min(25, Math.max(1, remainingMs)));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
      if (!exitedNaturally) {
        // The terminator consumes the SAME absolute shutdown budget: escalation can
        // never grant a fresh timeout window beyond the advertised bound.
        terminateTreeWithinSharedDeadline(deadline);
      }
    } finally {
      // Reader joins consume the REMAINING shared budget; total duration stays bounded.
      joinReaderQuietly(readerThread, deadline);
      joinReaderQuietly(stderrThread, deadline);
      disposed = true;
      log.info("xRSPS kernel process disposed");
    }
  }

  /**
   * Waits for the matching dispose response requiring {disposed:true}; consumes the SAME deadline.
   */
  private boolean awaitDisposeAcknowledgement(String disposeId, long deadlineNanos) {
    try {
      while (true) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          return false; // expired deadline: no additional wait
        }
        // Bounded 50 MILLISECOND poll; the one-millisecond rounding is allowed only
        // while positive time remains.
        long waitMs = Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        KernelProtocolCodec.KernelMessage msg = inbox.poll(waitMs, TimeUnit.MILLISECONDS);
        if (msg == null) {
          if (!process.isAlive()) {
            return false;
          }
          continue;
        }
        // A reader-side fatality or clean process-exit sentinel has no request id. It is a
        // terminal teardown signal, not an unexpected response belonging to another request. Do
        // not turn the already-known process failure into a second protocol violation while the
        // disposer is trying to reap the tree.
        if (msg.processExited() || (msg.fatal() && msg.id() == null)) {
          return false;
        }
        if (!disposeId.equals(msg.id())) {
          // Only a response for a request that explicitly timed out may be discarded. Treating an
          // arbitrary id as harmless would hide request/dispose correlation violations.
          handleUnexpectedId(msg);
          continue;
        }
        return !msg.isError()
            && msg.result() != null
            && msg.result().path("disposed").asBoolean(false);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Termination that consumes the caller's ALREADY-RUNNING absolute shutdown deadline. */
  private void terminateTreeWithinSharedDeadline(long shutdownDeadlineNanos) {
    XrspsProcessTreeTerminator.terminateWithinDeadline(process, shutdownDeadlineNanos);
  }

  /**
   * Standalone termination (protocol violations, failed starts): creates ONE fresh deadline from
   * the configured timeout via the terminator's own overload.
   */
  private void terminateTree() {
    XrspsProcessTreeTerminator.terminate(process, config.getShutdownTimeoutMs());
  }

  /**
   * Joins the reader within the REMAINING shared monotonic shutdown budget — never unbounded, and
   * never beyond the deadline: an already-expired deadline grants no additional join.
   */
  private void joinReaderQuietly(Thread thread, long shutdownDeadlineNanos) {
    if (thread == null) {
      return;
    }
    long remainingNanos = shutdownDeadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) {
      return;
    }
    long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    try {
      // Thread.join takes MILLISECONDS: passing converted nanoseconds used to wait ~46 days.
      thread.join(remainingMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A concurrent closer owns the handshake; wait only until {@code disposed} flips or the ONE
   * absolute deadline the owner selected passes. Never creates a fresh {@code shutdownTimeoutMs *
   * 2} window — every closer observes the same bound.
   */
  private void awaitTerminationQuietly() {
    long deadline = shutdownDeadlineNanos.get();
    while (!disposed) {
      if (deadline != 0 && System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (deadline == 0) {
        deadline = shutdownDeadlineNanos.get();
      }
    }
  }
}
