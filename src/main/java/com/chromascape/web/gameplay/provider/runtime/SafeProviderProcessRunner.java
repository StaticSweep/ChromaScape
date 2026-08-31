package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Direct, non-shell process execution with a bounded transcript and allowlisted environment. */
@Component
public final class SafeProviderProcessRunner {
  private static final Set<String> BASE_ENVIRONMENT =
      Set.of("PATH", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP", "LANG");

  private final ProviderRuntimeProperties properties;
  private final Clock clock;
  private final Map<String, Process> active = new ConcurrentHashMap<>();

  @Autowired
  public SafeProviderProcessRunner(ProviderRuntimeProperties properties) {
    this(properties, Clock.systemUTC());
  }

  SafeProviderProcessRunner(ProviderRuntimeProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public ProcessResult run(
      String turnRef,
      List<String> command,
      List<String> credentialEnvironmentNames,
      String stdin,
      Instant deadline) {
    return run(turnRef, command, credentialEnvironmentNames, stdin, deadline, Map.of());
  }

  public ProcessResult run(
      String turnRef,
      List<String> command,
      List<String> credentialEnvironmentNames,
      String stdin,
      Instant deadline,
      Map<String, String> stagedFiles) {
    Instant startedAt = clock.instant();
    if (deadline == null || !deadline.isAfter(startedAt)) {
      return ProcessResult.failure(false, FailureCode.DEADLINE_EXPIRED, 0);
    }
    Process process;
    try {
      String safeTurnRef = turnRef.replaceAll("[^A-Za-z0-9._-]", "_");
      Path root = properties.getWorkRoot().resolve("isolated").toAbsolutePath().normalize();
      Path work = root.resolve(safeTurnRef).normalize();
      if (!work.startsWith(root)) {
        return ProcessResult.failure(false, FailureCode.PROCESS_START_FAILED, 0);
      }
      Files.createDirectories(work);
      for (Map.Entry<String, String> file : stagedFiles.entrySet()) {
        if (!file.getKey().matches("[A-Za-z0-9._-]{1,80}")) {
          return ProcessResult.failure(false, FailureCode.PROCESS_START_FAILED, 0);
        }
        Files.writeString(work.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
      }
      List<String> resolvedCommand = new ArrayList<>(command.size());
      for (String argument : command) {
        resolvedCommand.add(argument.replace("${WORK_DIR}", work.toString()));
      }
      ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
      builder.directory(work.toFile());
      Map<String, String> environment = builder.environment();
      Map<String, String> source = System.getenv();
      environment.clear();
      BASE_ENVIRONMENT.forEach(
          name -> {
            String value = source.get(name);
            if (value != null && !value.isBlank()) {
              environment.put(name, value);
            }
          });
      credentialEnvironmentNames.forEach(
          name -> {
            String value = source.get(name);
            if (value != null && !value.isBlank()) {
              environment.put(name, value);
            }
          });
      environment.put("NO_COLOR", "1");
      environment.put("TERM", "dumb");
      process = builder.start();
      active.put(turnRef, process);
    } catch (IOException exception) {
      return ProcessResult.failure(false, FailureCode.PROCESS_START_FAILED, 0);
    }

    boolean submitted = false;
    try {
      process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      submitted = true;
      CompletableFuture<BoundedOutput> stdout =
          CompletableFuture.supplyAsync(
              () -> readBounded(process.getInputStream(), properties.getMaxStdoutBytes()));
      CompletableFuture<BoundedOutput> stderr =
          CompletableFuture.supplyAsync(
              () -> readBounded(process.getErrorStream(), properties.getMaxStderrBytes()));
      long remaining = Math.max(1, Duration.between(clock.instant(), deadline).toMillis());
      if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
        process.destroy();
        if (process.isAlive()) {
          process.destroyForcibly();
        }
        return ProcessResult.failure(
            true, FailureCode.UNKNOWN_PROVIDER_OUTCOME, elapsed(startedAt));
      }
      BoundedOutput out = stdout.join();
      BoundedOutput err = stderr.join();
      if (out.overflow() || err.overflow()) {
        return new ProcessResult(
            true,
            true,
            process.exitValue(),
            out.text(),
            err.text(),
            FailureCode.INVALID_OUTPUT,
            elapsed(startedAt));
      }
      FailureCode failure =
          process.exitValue() == 0 ? FailureCode.NONE : FailureCode.PROVIDER_UNAVAILABLE;
      return new ProcessResult(
          true, true, process.exitValue(), out.text(), err.text(), failure, elapsed(startedAt));
    } catch (Exception exception) {
      if (submitted) {
        return ProcessResult.failure(
            true, FailureCode.UNKNOWN_PROVIDER_OUTCOME, elapsed(startedAt));
      }
      return ProcessResult.failure(false, FailureCode.PROCESS_START_FAILED, elapsed(startedAt));
    } finally {
      active.remove(turnRef, process);
    }
  }

  public boolean cancel(String turnRef) {
    Process process = active.get(turnRef);
    if (process == null) {
      return false;
    }
    process.destroy();
    return true;
  }

  public ProbeResult probe(List<String> command, List<String> credentialEnvironmentNames) {
    List<String> probe = new ArrayList<>(command);
    probe.add("--version");
    ProcessResult result =
        run(
            "probe-" + Integer.toHexString(command.hashCode()),
            probe,
            credentialEnvironmentNames,
            "",
            clock.instant().plusSeconds(5));
    String version = firstLine(result.stdout());
    return new ProbeResult(result.exitCode() == 0, version, result.failureCode());
  }

  private BoundedOutput readBounded(InputStream input, int limit) {
    try (input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      int retained = 0;
      boolean overflow = false;
      while ((read = input.read(buffer)) >= 0) {
        int writable = Math.min(read, Math.max(0, limit - retained));
        if (writable > 0) {
          output.write(buffer, 0, writable);
          retained += writable;
        }
        overflow |= writable < read;
      }
      return new BoundedOutput(output.toString(StandardCharsets.UTF_8), overflow);
    } catch (IOException exception) {
      return new BoundedOutput("", true);
    }
  }

  private long elapsed(Instant startedAt) {
    return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
  }

  private String firstLine(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    int newline = text.indexOf('\n');
    return (newline < 0 ? text : text.substring(0, newline)).trim();
  }

  private record BoundedOutput(String text, boolean overflow) {}

  public record ProbeResult(boolean available, String version, FailureCode failureCode) {}

  public record ProcessResult(
      boolean submitted,
      boolean terminal,
      int exitCode,
      String stdout,
      String stderr,
      FailureCode failureCode,
      long elapsedMillis) {
    static ProcessResult failure(boolean submitted, FailureCode code, long elapsedMillis) {
      return new ProcessResult(submitted, false, -1, "", "", code, elapsedMillis);
    }

    public Map<String, Object> auditSummary() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("submitted", submitted);
      values.put("terminal", terminal);
      values.put("exitCode", exitCode);
      values.put("failureCode", failureCode.name());
      values.put("elapsedMillis", elapsedMillis);
      return Map.copyOf(values);
    }
  }
}
