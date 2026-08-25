package com.chromascape.web.replay.kernel;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of a single xRSPS kernel child process.
 *
 * Protocol: newline-delimited JSON-RPC over stdin/stdout, launched as
 * {@code node <entrypoint> --stdio}.
 *
 * Guarantees:
 *  - every request carries a unique id; responses are correlated by id and
 *    mismatched ids are skipped (never resolved to the wrong caller);
 *  - reads are bounded: a reader thread feeds a queue so the caller's
 *    deadline is enforced via poll(timeout), never an indefinite readLine();
 *  - oversized stdout lines and stderr volume are capped by configuration;
 *  - the child environment is restricted to a minimal allow-list;
 *  - dispose is attempted before process destruction and descendant
 *    processes are terminated on close.
 */
public class XrspsKernelProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(XrspsKernelProcess.class);

    private final ReplayProperties.Xrsps config;
    private final ObjectMapper mapper;
    private final KernelProtocolCodec codec;

    private Process process;
    private BufferedWriter stdin;
    private final BlockingQueue<KernelProtocolCodec.KernelMessage> inbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private volatile long stderrBytes = 0;
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private Thread readerThread;
    private Thread stderrThread;

    public XrspsKernelProcess(ReplayProperties.Xrsps config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.codec = new KernelProtocolCodec(mapper);
    }

    public void start() {
        if (config.getEntrypoint() == null || config.getEntrypoint().isBlank()) {
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    false,
                    "xRSPS kernel entrypoint is not configured",
                    "Set REPLAY_XRSPS_ENTRYPOINT to the compiled worker main.js", null, null);
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(config.getExecutable());
            command.add(config.getEntrypoint());
            command.add("--stdio");

            ProcessBuilder pb = new ProcessBuilder(command);
            if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
                pb.directory(new File(config.getWorkingDirectory()));
            }
            pb.redirectErrorStream(false);
            // Restrict inherited environment: only what Node needs to run.
            pb.environment().clear();
            Map<String, String> env = pb.environment();
            env.put("PATH", System.getenv("PATH") != null ? System.getenv("PATH") : "/usr/bin:/bin:/usr/local/bin");
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null) env.put("SystemRoot", systemRoot);
            String comspec = System.getenv("COMSPEC");
            if (comspec != null) env.put("COMSPEC", comspec);
            String tmp = System.getenv("TEMP");
            if (tmp != null) env.put("TEMP", tmp);
            String tmpdir = System.getenv("TMP");
            if (tmp != null) env.put("TMP", tmp);

            process = pb.start();

            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedReader stderrReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            readerThread = new Thread(() -> drainStdout(stdoutReader), "xrsps-stdout-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            stderrThread = new Thread(() -> drainStderr(stderrReader), "xrsps-stderr-reader");
            stderrThread.setDaemon(true);
            stderrThread.start();

            log.info("Started xRSPS kernel process: {} {} --stdio", config.getExecutable(), config.getEntrypoint());
        } catch (IOException e) {
            close();
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    true,
                    "Failed to start xRSPS kernel process",
                    "Verify REPLAY_XRSPS_EXECUTABLE and REPLAY_XRSPS_ENTRYPOINT point at the built worker",
                    null, e);
        }
    }

    /** Sends a request and waits for the response with the matching id. */
    public KernelProtocolCodec.KernelMessage send(String method, JsonNode params) {
        return send(method, params, config.getRequestTimeoutMs());
    }

    public KernelProtocolCodec.KernelMessage send(String method, JsonNode params, long timeoutMs) {
        ensureAlive();
        String id = String.valueOf(messageIdCounter.getAndIncrement());

        try {
            String request = codec.encode(id, method, params);
            synchronized (stdin) {
                stdin.write(request);
                stdin.flush();
            }
        } catch (IOException e) {
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, false,
                    "Failed to send kernel request", null, null, e);
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                            org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, true,
                            "xRSPS kernel request timed out after " + timeoutMs + " ms",
                            "Increase replay.xrsps.request-timeout-ms or investigate the worker",
                            "method=" + method, null);
                }
                KernelProtocolCodec.KernelMessage msg = inbox.poll(remaining, TimeUnit.NANOSECONDS);
                if (msg == null) {
                    continue; // deadline check loops
                }
                if (!id.equals(msg.id())) {
                    // Response for an abandoned/timed-out earlier request; skip it.
                    log.debug("Skipping kernel response with unexpected id {} (expected {})", msg.id(), id);
                    continue;
                }
                return msg;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReplayException(ReplayErrorCode.INTERNAL_ERROR,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, true,
                    "Interrupted while waiting for kernel response", null, null, e);
        }
    }

    private void drainStdout(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.getBytes(StandardCharsets.UTF_8).length > config.getMaxStdoutLineBytes()) {
                    log.error("xRSPS kernel emitted an oversized stdout line; terminating process");
                    inbox.offer(KernelProtocolCodec.KernelMessage.fatal(
                            "INTERNAL_ERROR", "Oversized stdout line from kernel"));
                    destroyProcessTree();
                    return;
                }
                KernelProtocolCodec.KernelMessage msg = codec.decode(line);
                if (msg != null && msg.isResponse()) {
                    if (!inbox.offer(msg)) {
                        log.warn("Kernel response queue full; dropping message id {}", msg.id());
                    }
                }
                if (disposed.get()) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // Stream closed during shutdown is expected.
        } finally {
            if (!disposed.get()) {
                inbox.offer(KernelProtocolCodec.KernelMessage.exitWithoutResponse());
            }
        }
    }

    private void drainStderr(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                stderrBytes += line.length() + 1;
                if (stderrBytes > config.getMaxStderrBytes()) {
                    log.warn("xRSPS kernel exceeded max stderr bytes; further output suppressed");
                    return;
                }
                log.debug("xRSPS stderr: {}", line);
            }
        } catch (IOException ignored) {
            // Stream closed during shutdown is expected.
        }
    }

    private void ensureAlive() {
        if (disposed.get() || process == null || !process.isAlive()) {
            throw new ReplayException(ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, false,
                    "xRSPS kernel process is not running", null, null, null);
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive() && !disposed.get();
    }

    @Override
    public void close() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        // Best-effort graceful dispose before tearing down streams.
        try {
            if (stdin != null && process != null && process.isAlive()) {
                synchronized (stdin) {
                    stdin.write(codec.encode(String.valueOf(messageIdCounter.getAndIncrement()), "dispose", null));
                    stdin.flush();
                }
            }
        } catch (IOException e) {
            log.debug("Graceful dispose write failed: {}", e.getMessage());
        }

        destroyProcessTree();

        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
        log.info("xRSPS kernel process disposed");
    }

    private void destroyProcessTree() {
        if (process == null) {
            return;
        }
        // Terminate descendants first so no orphaned children survive.
        process.descendants().forEach(handle -> {
            handle.destroy();
            long deadline = System.currentTimeMillis() + config.getShutdownTimeoutMs();
            try {
                while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25);
                }
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            } catch (InterruptedException e) {
                handle.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        });
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // expected during teardown
        }
    }
}
