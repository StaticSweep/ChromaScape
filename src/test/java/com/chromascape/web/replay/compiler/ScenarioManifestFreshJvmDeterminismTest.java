package com.chromascape.web.replay.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.manifest.FreshJvmCompileMain;
import com.chromascape.web.replay.manifest.NonterminatingChildMain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Fresh-JVM determinism: two isolated Java processes compile the same immutable fixture input and
 * must produce byte-identical canonical manifests and identical checksums.
 *
 * <p>This complements the in-process golden test. In-process equality cannot detect state that is
 * reset per Spring context but differs per JVM (default locale, timezone, hash iteration order,
 * static caches), so the assertion is made across process boundaries.
 *
 * <p>Every child this test starts is reaped in a {@code finally} block with bounded graceful and
 * forced termination, so a timed-out, interrupted or failing run cannot leak a compiler JVM. The
 * cleanup mechanism itself is covered by {@link
 * #cleanupTerminatesAnIntentionallyNonterminatingChild()}.
 */
class ScenarioManifestFreshJvmDeterminismTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long CHILD_TIMEOUT_SECONDS = 300;
  private static final long GRACEFUL_TERMINATION_SECONDS = 10;
  private static final long FORCED_TERMINATION_SECONDS = 10;

  @Test
  void twoFreshJvmsProduceByteIdenticalManifestsAndChecksums() throws Exception {
    runFreshJvmDeterminism(
        Files.createTempDirectory("sc274-fresh-jvm-"),
        ScenarioManifestFreshJvmDeterminismTest::spawn);
  }

  /**
   * The determinism scenario, parameterized by the child factory so spawn-failure cleanup can be
   * exercised without weakening the assertions of the primary test.
   */
  static void runFreshJvmDeterminism(Path base, ChildSpawner spawner) throws Exception {
    Path first = Files.createDirectories(base.resolve("process-a"));
    Path second = Files.createDirectories(base.resolve("process-b"));

    Process firstProcess = null;
    Process secondProcess = null;
    Throwable primaryFailure = null;
    try {
      firstProcess = spawner.spawn(first, FreshJvmCompileMain.class.getName());
      secondProcess = spawner.spawn(second, FreshJvmCompileMain.class.getName());

      assertTrue(
          firstProcess.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "first compiler JVM timed out; diagnostics: " + first + "; log: " + log(first));
      assertTrue(
          secondProcess.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "second compiler JVM timed out; diagnostics: " + second + "; log: " + log(second));

      assertEquals(0, firstProcess.exitValue(), log(first));
      assertEquals(0, secondProcess.exitValue(), log(second));

      byte[] firstManifest = Files.readAllBytes(first.resolve("manifest.json"));
      byte[] secondManifest = Files.readAllBytes(second.resolve("manifest.json"));
      assertTrue(firstManifest.length > 0, "first manifest was empty");
      assertArrayEquals(firstManifest, secondManifest, "canonical manifest bytes differ");

      JsonNode firstResult = MAPPER.readTree(first.resolve("result.json").toFile());
      JsonNode secondResult = MAPPER.readTree(second.resolve("result.json").toFile());

      assertManifestIdentityMetadata(firstResult, secondResult);
      assertEquals(
          sha256Hex(firstManifest),
          requireTextualField(firstResult, "manifestSha256"),
          "reported manifest SHA-256 does not match the manifest bytes");
      assertEquals("2.4.0", requireTextualField(firstResult, "compilerVersion"));
      assertEquals(1, firstResult.path("actionCount").asInt());
      assertEquals(0, firstResult.path("unsupportedActionCount").asInt());

      long firstPid = firstResult.path("pid").asLong();
      long secondPid = secondResult.path("pid").asLong();
      assertTrue(firstPid > 0, "first child did not report its PID");
      assertTrue(secondPid > 0, "second child did not report its PID");
      assertNotEquals(firstPid, secondPid, "both compiles ran in the same child JVM");
      assertNotEquals(ProcessHandle.current().pid(), firstPid, "compile ran in the test JVM");
      assertNotEquals(ProcessHandle.current().pid(), secondPid, "compile ran in the test JVM");
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      reapAll(
          primaryFailure,
          new Child(firstProcess, "first", first),
          new Child(secondProcess, "second", second));
    }
  }

  /**
   * Proves the cleanup helper terminates a live, deliberately nonterminating child within its
   * bounded graceful/forced window and confirms exit, so the harness cannot leak a child on a
   * timeout or assertion failure in the determinism test.
   */
  @Test
  void cleanupTerminatesAnIntentionallyNonterminatingChild() throws Exception {
    Path output = Files.createDirectories(Files.createTempDirectory("sc274-fresh-jvm-cleanup-"));
    Process child = spawn(output, NonterminatingChildMain.class.getName());
    Throwable primaryFailure = null;
    try {
      assertFalse(
          child.waitFor(2, TimeUnit.SECONDS),
          "nonterminating child exited early; diagnostics: " + output + "; log: " + log(output));
      assertTrue(child.isAlive(), "nonterminating child is not running before cleanup");
      assertTrue(
          log(output).contains("nonterminating child started"),
          "nonterminating child did not report startup; log: " + log(output));
    } catch (Throwable failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      reapAll(primaryFailure, new Child(child, "nonterminating", output));
    }
    assertFalse(child.isAlive(), "cleanup left the nonterminating child running");
    long pid = child.pid();
    assertFalse(
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "process handle " + pid + " survived cleanup");
  }

  /**
   * A cleanup failure for one child must never skip cleanup of the others. The first child here can
   * never confirm exit; the second is a real nonterminating process and must still be terminated.
   */
  @Test
  void cleanupAttemptsEveryChildEvenWhenFirstCannotBeConfirmed() throws Exception {
    Path output = Files.createDirectories(Files.createTempDirectory("sc274-cleanup-aggregate-"));
    Process unconfirmed = neverExitsProcess();
    Process real = spawn(output, NonterminatingChildMain.class.getName());
    Throwable primary = new AssertionError("primary test failure");
    try {
      assertFalse(
          real.waitFor(2, TimeUnit.SECONDS),
          "nonterminating child exited early; log: " + log(output));
      reapAll(
          primary, new Child(unconfirmed, "unconfirmed", output), new Child(real, "real", output));
    } finally {
      if (real.isAlive()) {
        real.destroyForcibly();
        real.waitFor(5, TimeUnit.SECONDS);
      }
    }
    assertFalse(real.isAlive(), "the second child was skipped after the first cleanup failed");
    assertEquals(1, primary.getSuppressed().length, "the failed cleanup was not reported");
    assertTrue(
        primary.getSuppressed()[0].getMessage().contains("unconfirmed"),
        "the reported cleanup failure did not name the failed child");
  }

  /** A spawn failure for the second child must still reap the first child that already started. */
  @Test
  void spawnFailureStillReapsTheEarlierChild() throws Exception {
    Path base = Files.createTempDirectory("sc274-spawn-failure-");
    List<Process> started = new java.util.ArrayList<>();
    ChildSpawner flaky =
        (output, mainClass) -> {
          if (started.size() == 1) throw new IOException("simulated second spawn failure");
          Process child = spawn(output, NonterminatingChildMain.class.getName());
          started.add(child);
          return child;
        };

    IOException failure =
        assertThrows(IOException.class, () -> runFreshJvmDeterminism(base, flaky));
    assertEquals("simulated second spawn failure", failure.getMessage());
    assertEquals(1, started.size(), "expected exactly one child to have started");
    assertFalse(started.get(0).isAlive(), "the first child leaked after the second spawn failed");
  }

  /**
   * An interrupting caller must not turn bounded cleanup into an unbounded or skipped wait. The
   * interrupt flag is cleared for the bounded wait and restored afterwards.
   */
  @Test
  void cleanupRestoresInterruptFlagAndStillReapsChild() throws Exception {
    Path output = Files.createDirectories(Files.createTempDirectory("sc274-cleanup-interrupt-"));
    Process child = spawn(output, NonterminatingChildMain.class.getName());
    try {
      assertFalse(
          child.waitFor(2, TimeUnit.SECONDS),
          "nonterminating child exited early; log: " + log(output));
      Thread.currentThread().interrupt();
      AssertionError cleanup = reap(child, "interrupted", output);
      assertNull(cleanup, "cleanup failed while the caller was interrupted");
      assertTrue(Thread.interrupted(), "cleanup did not restore the caller's interrupt flag");
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(5, TimeUnit.SECONDS);
      }
    }
    assertFalse(child.isAlive(), "cleanup left the interrupted child running");
  }

  /**
   * Refuses absent, blank, null or differing manifest identity metadata in either child result. A
   * missing JSON field is a Java {@code null} node, not a {@code MissingNode}, so the check must
   * look for {@code null} and re-verify both textual form and cross-process equality.
   */
  @Test
  void refusesMissingBlankOrDifferingCurationAndManifestIdentityMetadata() {
    ObjectNode complete = MAPPER.createObjectNode();
    complete.put("manifestChecksum", "sha256:manifest");
    complete.put("manifestSha256", "a".repeat(64));
    complete.put("compilerVersion", "2.4.0");
    complete.put("publishedCurationChecksum", "sha256:curation");

    assertDoesNotThrow(() -> assertManifestIdentityMetadata(complete, complete.deepCopy()));

    ObjectNode missingCuration = complete.deepCopy();
    missingCuration.remove("publishedCurationChecksum");
    assertRefused(complete, missingCuration, "publishedCurationChecksum");

    ObjectNode blankCuration = complete.deepCopy();
    blankCuration.put("publishedCurationChecksum", "   ");
    assertRefused(complete, blankCuration, "publishedCurationChecksum");

    ObjectNode nullCuration = complete.deepCopy();
    nullCuration.putNull("publishedCurationChecksum");
    assertRefused(complete, nullCuration, "publishedCurationChecksum");

    ObjectNode differingCuration = complete.deepCopy();
    differingCuration.put("publishedCurationChecksum", "sha256:different");
    assertRefused(complete, differingCuration, "publishedCurationChecksum");

    ObjectNode missingManifestChecksum = complete.deepCopy();
    missingManifestChecksum.remove("manifestChecksum");
    assertRefused(complete, missingManifestChecksum, "manifestChecksum");

    ObjectNode blankCompilerVersion = complete.deepCopy();
    blankCompilerVersion.put("compilerVersion", "");
    assertRefused(complete, blankCompilerVersion, "compilerVersion");

    ObjectNode differingManifestSha = complete.deepCopy();
    differingManifestSha.put("manifestSha256", "b".repeat(64));
    assertRefused(complete, differingManifestSha, "manifestSha256");
  }

  /**
   * Requires both child results to carry the same textual, nonblank manifest identity fields. Any
   * missing, non-textual, blank or differing value fails the assertion.
   */
  static void assertManifestIdentityMetadata(JsonNode firstResult, JsonNode secondResult) {
    assertEquals(
        requireTextualField(firstResult, "manifestChecksum"),
        requireTextualField(secondResult, "manifestChecksum"),
        "manifestChecksum differs between the two child results");
    assertEquals(
        requireTextualField(firstResult, "manifestSha256"),
        requireTextualField(secondResult, "manifestSha256"),
        "manifestSha256 differs between the two child results");
    assertEquals(
        requireTextualField(firstResult, "compilerVersion"),
        requireTextualField(secondResult, "compilerVersion"),
        "compilerVersion differs between the two child results");
    assertEquals(
        requireTextualField(firstResult, "publishedCurationChecksum"),
        requireTextualField(secondResult, "publishedCurationChecksum"),
        "publishedCurationChecksum differs between the two child results");
  }

  static String requireTextualField(JsonNode result, String field) {
    JsonNode value = result.get(field);
    assertNotNull(value, "child result is missing field " + field);
    assertTrue(
        value.isTextual(),
        "field " + field + " is not textual but " + value.getNodeType() + ": " + value);
    String text = value.asText();
    assertFalse(text.isBlank(), "field " + field + " is blank");
    return text;
  }

  private static void assertRefused(JsonNode first, JsonNode second, String expectedField) {
    AssertionError failure =
        assertThrows(AssertionError.class, () -> assertManifestIdentityMetadata(first, second));
    assertNotNull(failure.getMessage(), "refusal carried no message for " + expectedField);
    assertTrue(
        failure.getMessage().contains(expectedField),
        "expected the refusal to name " + expectedField + " but was: " + failure.getMessage());
  }

  /** A child process plus the diagnostics that identify it. */
  private record Child(Process process, String label, Path output) {}

  /** Child factory, injectable so spawn failure can be exercised. */
  @FunctionalInterface
  interface ChildSpawner {
    Process spawn(Path output, String mainClass) throws IOException;
  }

  /**
   * Bounded cleanup for every started child. Requests graceful termination, escalates to forced
   * termination, and confirms exit. A cleanup failure for one child never skips the rest: when the
   * test body already failed the cleanup failures are attached as suppressed, otherwise the first
   * cleanup failure is thrown with the others suppressed. Child diagnostics stay in the output
   * directory.
   */
  private static void reapAll(Throwable primary, Child... children) {
    java.util.List<AssertionError> failures = new java.util.ArrayList<>();
    for (Child child : children) {
      AssertionError failure = reap(child.process(), child.label(), child.output());
      if (failure != null) failures.add(failure);
    }
    if (failures.isEmpty()) return;
    if (primary != null) {
      for (AssertionError failure : failures) primary.addSuppressed(failure);
      return;
    }
    AssertionError first = failures.remove(0);
    for (AssertionError failure : failures) first.addSuppressed(failure);
    throw first;
  }

  /**
   * Bounded cleanup for one child. The caller's interrupt flag is captured and cleared before the
   * bounded waits, and restored afterwards, so a pending interrupt can neither break the wait into
   * an immediate failure nor prevent confirmation of exit. Returns the cleanup failure instead of
   * throwing so callers can aggregate every child.
   */
  private static AssertionError reap(Process process, String label, Path output) {
    if (process == null || !process.isAlive()) {
      return null;
    }
    boolean[] interrupted = {Thread.interrupted()};
    try {
      try {
        process.destroy();
        if (!awaitTermination(process, GRACEFUL_TERMINATION_SECONDS, interrupted)) {
          process.destroyForcibly();
          if (!awaitTermination(process, FORCED_TERMINATION_SECONDS, interrupted)) {
            return cleanupFailure(label, output);
          }
        }
        return null;
      } catch (RuntimeException failure) {
        return cleanupFailure(label, output, failure);
      }
    } finally {
      if (interrupted[0]) Thread.currentThread().interrupt();
    }
  }

  /**
   * Waits up to the bound while deliberately ignoring interrupts (recording them for the caller) so
   * cleanup still confirms a bounded exit. The remaining time is recomputed from a monotonic
   * deadline rather than restarting the full window on every interrupt.
   */
  private static boolean awaitTermination(Process process, long seconds, boolean[] interrupted) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    for (; ; ) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return !process.isAlive();
      try {
        return process.waitFor(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException ignored) {
        interrupted[0] = true;
      }
    }
  }

  private static AssertionError cleanupFailure(String label, Path output) {
    return cleanupFailure(label, output, null);
  }

  private static AssertionError cleanupFailure(String label, Path output, Throwable cause) {
    AssertionError failure =
        new AssertionError(
            "child process '"
                + label
                + "' survived bounded graceful/forced termination; diagnostics retained at "
                + output
                + "; log: "
                + log(output));
    if (cause != null) failure.initCause(cause);
    return failure;
  }

  /** A process that reports itself live and can never confirm exit. */
  private static Process neverExitsProcess() {
    return new Process() {
      @Override
      public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
      }

      @Override
      public InputStream getInputStream() {
        return InputStream.nullInputStream();
      }

      @Override
      public InputStream getErrorStream() {
        return InputStream.nullInputStream();
      }

      @Override
      public int waitFor() {
        throw new IllegalThreadStateException("never exits");
      }

      @Override
      public boolean waitFor(long timeout, TimeUnit unit) {
        return false;
      }

      @Override
      public int exitValue() {
        throw new IllegalThreadStateException("never exits");
      }

      @Override
      public void destroy() {
        throw new IllegalStateException("simulated undestroyable child");
      }

      @Override
      public Process destroyForcibly() {
        return this;
      }

      @Override
      public boolean isAlive() {
        return true;
      }
    };
  }

  /** Shared with the refusal-stability lane so both child processes launch identically. */
  static Process spawn(Path output, String mainClass) throws IOException {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
            .toString();
    // The Gradle test runtime classpath exceeds the Windows command-line limit, so the child
    // configuration is passed through a Java argument file instead of argv.
    // Java argument files treat backslash as an escape, so Windows paths are written with
    // forward slashes; the JVM accepts them on both the classpath and Path arguments.
    Path argumentFile = output.resolve("process.args");
    Files.writeString(
        argumentFile,
        "-cp\n\""
            + System.getProperty("java.class.path").replace('\\', '/')
            + "\"\n"
            + mainClass
            + "\n\""
            + output.toAbsolutePath().toString().replace('\\', '/')
            + "\"\n",
        StandardCharsets.UTF_8);
    ProcessBuilder builder = new ProcessBuilder(executable, "@" + argumentFile.toAbsolutePath());
    builder.redirectErrorStream(true);
    builder.redirectOutput(output.resolve("process.log").toFile());
    return builder.start();
  }

  private static String log(Path output) {
    try {
      return Files.readString(output.resolve("process.log"), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "unable to read process log: " + error.getMessage();
    }
  }

  private static String sha256Hex(byte[] input) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
    StringBuilder builder = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      builder.append(Character.forDigit((value >> 4) & 0xF, 16));
      builder.append(Character.forDigit(value & 0xF, 16));
    }
    return builder.toString();
  }
}
