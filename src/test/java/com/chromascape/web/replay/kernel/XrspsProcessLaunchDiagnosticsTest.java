package com.chromascape.web.replay.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.web.replay.config.ReplayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Launches one generated fixture three ways and preserves bounded evidence for Windows failures.
 * The same resolved Node executable, absolute script, and working directory are used for direct
 * execution, a minimal Java {@link ProcessBuilder}, and {@link XrspsKernelProcess}.
 */
class XrspsProcessLaunchDiagnosticsTest {

  private static final int MAX_CAPTURE_BYTES = 64 * 1024;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DIAGNOSTIC_ROOT =
      Path.of("build", "replay-certification", "process-diagnostics")
          .resolve("XrspsProcessLaunchDiagnosticsTest-" + UUID.randomUUID())
          .toAbsolutePath();

  @Test
  void resolvedNodeProcessBuilderAndKernelLaunchesShareTheSameWindowsPaths() throws Exception {
    Path diagnosticRoot = DIAGNOSTIC_ROOT;
    Path fixtureRoot = diagnosticRoot.resolve("fixtures");
    Files.createDirectories(fixtureRoot);
    Path script = fixtureRoot.resolve("launch-probe.js").toAbsolutePath();
    Path directPid = fixtureRoot.resolve("direct.pid").toAbsolutePath();
    Path javaPid = fixtureRoot.resolve("java-processbuilder.pid").toAbsolutePath();
    Path kernelPid = fixtureRoot.resolve("kernel.pid").toAbsolutePath();
    Path directStderr = fixtureRoot.resolve("direct.stderr").toAbsolutePath();
    Path javaStderr = fixtureRoot.resolve("java-processbuilder.stderr").toAbsolutePath();
    Path kernelStderr = fixtureRoot.resolve("kernel.stderr").toAbsolutePath();
    String fixture = fixtureScript(directPid, javaPid, kernelPid, directStderr, javaStderr, kernelStderr);
    Files.writeString(script, fixture, StandardCharsets.UTF_8);

    ProcessResult where = runProcess(resolveCommand("node"), Path.of(".").toAbsolutePath());
    assertEquals(0, where.exitCode(), "node lookup must succeed");
    String resolvedNode = firstNonBlankLine(where.stdout());
    Path resolvedNodePath = Path.of(resolvedNode).toAbsolutePath();
    assertTrue(Files.isRegularFile(resolvedNodePath), "resolved Node executable must exist");

    ProcessResult nodeVersion =
        runProcess(List.of(resolvedNode, "--version"), Path.of(".").toAbsolutePath());
    ProcessResult nodeExecPath =
        runProcess(List.of(resolvedNode, "-p", "process.execPath"), Path.of(".").toAbsolutePath());
    ProcessResult nodeArch =
        runProcess(List.of(resolvedNode, "-p", "process.arch"), Path.of(".").toAbsolutePath());
    ProcessResult gradle = runProcess(gradleCommand(), Path.of(".").toAbsolutePath());
    assertEquals(0, nodeVersion.exitCode(), "resolved Node --version must succeed");
    assertEquals(0, nodeExecPath.exitCode(), "resolved Node process.execPath must succeed");
    assertEquals(0, nodeArch.exitCode(), "resolved Node process.arch must succeed");
    assertEquals(0, gradle.exitCode(), "the repository Gradle wrapper must be callable");

    ProcessResult direct =
        runProcess(List.of(resolvedNode, script.toString(), "--direct"), fixtureRoot);
    ProcessResult javaProcessBuilder =
        runProcess(List.of(resolvedNode, script.toString(), "--java-processbuilder"), fixtureRoot);
    assertEquals(0, direct.exitCode(), "direct resolved-Node fixture execution");
    assertEquals(0, javaProcessBuilder.exitCode(), "minimal Java ProcessBuilder fixture execution");
    assertTrue(direct.stdout().contains("direct-ok"));
    assertTrue(javaProcessBuilder.stdout().contains("java-processbuilder-ok"));

    ReplayProperties.Xrsps config = new ReplayProperties.Xrsps();
    config.setExecutable(resolvedNodePath.toString());
    config.setEntrypoint(script.toString());
    config.setWorkingDirectory(fixtureRoot.toString());
    config.setRequestTimeoutMs(3000);
    config.setShutdownTimeoutMs(2000);
    long kernelPidValue = -1L;
    boolean aliveAfterStart = false;
    Integer kernelExitCode = null;
    long kernelStderrBytes;
    long kernelStderrBytesRead;
    String kernelStderrPreview;
    String kernelResponse = "<no response>";
    XrspsKernelProcess kernel = new XrspsKernelProcess(config, MAPPER);
    try {
      kernel.start();
      kernelPidValue = kernel.processPid();
      aliveAfterStart = kernel.isAlive();
      var response = kernel.send("probe", null, 3000);
      assertFalse(response.isError(), "kernel probe must receive a correlated response");
      kernelResponse = response.result().toString();
      assertTrue(response.result().path("probe").asBoolean());
    } finally {
      kernel.close();
      kernelExitCode = kernel.processExitCode();
      kernelStderrBytesRead = kernel.stderrBytesRead();
      kernelStderrPreview = kernel.stderrPreview();
    }
    try (var kernelExitProbe = Files.list(fixtureRoot)) {
      // Listing forces the fixture sidecars to be visible before the diagnostic document is read.
      assertTrue(kernelExitProbe.anyMatch(path -> path.equals(kernelPid)));
    }
    kernelStderrBytes = Files.exists(kernelStderr) ? Files.size(kernelStderr) : 0L;
    assertTrue(kernelPidValue > 0, "kernel must expose a positive child PID");
    assertFalse(ProcessHandle.of(kernelPidValue).map(ProcessHandle::isAlive).orElse(false));
    assertEquals(0, kernelExitCode, "dispose acknowledgement must produce natural exit");

    ObjectNode evidence = MAPPER.createObjectNode();
    evidence.put("osName", System.getProperty("os.name"));
    evidence.put("osArch", System.getProperty("os.arch"));
    evidence.put("javaVersion", System.getProperty("java.version"));
    evidence.put("javaDataModel", System.getProperty("sun.arch.data.model", "unknown"));
    evidence.put("whereCommand", String.join("\n", resolveCommand("node")));
    evidence.put("whereNode", bounded(where.stdout()));
    evidence.put("nodeVersion", bounded(nodeVersion.stdout()));
    evidence.put("nodeExecPath", bounded(nodeExecPath.stdout()));
    evidence.put("nodeArchitecture", bounded(nodeArch.stdout()));
    evidence.put("gradleVersion", bounded(gradle.stdout()));
    evidence.put("path", bounded(System.getenv("PATH")));
    evidence.put("workingDirectory", Path.of("").toAbsolutePath().toString());
    evidence.put("temporaryDirectoryRaw", System.getProperty("java.io.tmpdir"));
    evidence.put("temporaryDirectoryAbsolute", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
    evidence.put("resolvedNode", resolvedNodePath.toString());
    evidence.put("fixturePath", script.toString());
    evidence.put("fixtureBytesBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(script)));
    evidence.put("directPidPath", directPid.toString());
    evidence.put("javaProcessBuilderPidPath", javaPid.toString());
    evidence.put("kernelPidPath", kernelPid.toString());
    evidence.put("directPidExists", Files.isRegularFile(directPid));
    evidence.put("javaProcessBuilderPidExists", Files.isRegularFile(javaPid));
    evidence.put("kernelPidExists", Files.isRegularFile(kernelPid));
    evidence.put("directPid", readText(directPid));
    evidence.put("javaProcessBuilderPid", readText(javaPid));
    evidence.put("kernelPid", readText(kernelPid));
    evidence.put("directStdout", bounded(direct.stdout()));
    evidence.put("directStderr", bounded(direct.stderr()));
    evidence.put("directExitCode", direct.exitCode());
    evidence.put("javaProcessBuilderStdout", bounded(javaProcessBuilder.stdout()));
    evidence.put("javaProcessBuilderStderr", bounded(javaProcessBuilder.stderr()));
    evidence.put("javaProcessBuilderExitCode", javaProcessBuilder.exitCode());
    evidence.put("kernelAliveAfterStart", aliveAfterStart);
    evidence.put("kernelPid", kernelPidValue);
    evidence.put("kernelResponse", kernelResponse);
    evidence.put("kernelExitCode", kernelExitCode);
    evidence.put("kernelAliveAfterClose", ProcessHandle.of(kernelPidValue).map(ProcessHandle::isAlive).orElse(false));
    evidence.put("kernelStderrSidecar", readText(kernelStderr));
    evidence.put("kernelStderrBytesRead", kernelStderrBytes);
    evidence.put("kernelStderrBytesConsumed", kernelStderrBytesRead);
    evidence.put("kernelStderrPreview", bounded(kernelStderrPreview));
    evidence.put("scriptBytesCaptured", true);
    Files.writeString(
        diagnosticRoot.resolve("launch-diagnostics.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
        StandardCharsets.UTF_8);
  }

  private static List<String> resolveCommand(String executable) {
    return isWindows() ? List.of("where", executable) : List.of("which", executable);
  }

  private static List<String> gradleCommand() {
    return isWindows()
        ? List.of("cmd", "/c", "gradlew.bat", "--version", "--no-daemon")
        : List.of("./gradlew", "--version", "--no-daemon");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  private static String firstNonBlankLine(String output) {
    return output.lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElseThrow(() -> new AssertionError("node lookup returned no executable: " + output));
  }

  private static String fixtureScript(
      Path directPid,
      Path javaPid,
      Path kernelPid,
      Path directStderr,
      Path javaStderr,
      Path kernelStderr) {
    return "const fs=require('fs');\n"
        + "const readline=require('readline');\n"
        + "const args=process.argv.slice(2);\n"
        + "const mode=args.includes('--stdio')?'kernel':args.includes('--java-processbuilder')?'java-processbuilder':'direct';\n"
        + "const paths={direct:{pid:'" + js(directPid) + "',stderr:'" + js(directStderr) + "'},"
        + "'java-processbuilder':{pid:'" + js(javaPid) + "',stderr:'" + js(javaStderr) + "'},"
        + "kernel:{pid:'" + js(kernelPid) + "',stderr:'" + js(kernelStderr) + "'}};\n"
        + "fs.writeFileSync(paths[mode].pid,String(process.pid));\n"
        + "fs.writeFileSync(paths[mode].stderr,mode+'-stderr\\n');\n"
        + "process.stderr.write(mode+'-stderr\\n');\n"
        + "if(mode!=='kernel'){process.stdout.write(mode+'-ok\\n');process.exit(0);}\n"
        + "const rl=readline.createInterface({input:process.stdin});\n"
        + "rl.on('line',line=>{let msg;try{msg=JSON.parse(line);}catch{return;};"
        + "if(msg.method==='probe')process.stdout.write(JSON.stringify({id:msg.id,ok:true,result:{probe:true}})+'\\n');"
        + "if(msg.method==='dispose'){process.stdout.write(JSON.stringify({id:msg.id,ok:true,result:{disposed:true}})+'\\n');setTimeout(()=>process.exit(0),25);}});\n";
  }

  private static String js(Path path) {
    return path.toString().replace("\\", "\\\\").replace("'", "\\'");
  }

  private static String readText(Path path) throws IOException {
    return Files.exists(path) ? bounded(Files.readString(path, StandardCharsets.UTF_8)) : "<missing>";
  }

  private static String bounded(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= MAX_CAPTURE_BYTES
        ? value
        : value.substring(0, MAX_CAPTURE_BYTES) + "...[truncated]";
  }

  private static ProcessResult runProcess(List<String> command, Path workingDirectory)
      throws Exception {
    Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
    ExecutorService readers = Executors.newFixedThreadPool(2);
    Future<String> stdout = readers.submit(() -> readBounded(process.getInputStream()));
    Future<String> stderr = readers.submit(() -> readBounded(process.getErrorStream()));
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("process timed out: " + command);
      }
      return new ProcessResult(
          process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
    } finally {
      readers.shutdownNow();
    }
  }

  private static String readBounded(java.io.InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    int retained = 0;
    while ((read = input.read(buffer)) != -1) {
      int copy = Math.min(read, MAX_CAPTURE_BYTES - retained);
      if (copy > 0) {
        output.write(buffer, 0, copy);
        retained += copy;
      }
    }
    return bounded(output.toString(StandardCharsets.UTF_8));
  }

  private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
