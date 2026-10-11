package com.chromascape.web.replay.kernel;

import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * THE single Java process-launch environment builder for the xRSPS worker. Both {@code
 * XrspsKernelProcess} (--stdio) and {@code XrspsStartupValidator} (--capabilities) must launch
 * through it so Windows paths, working directory, encoding, and the environment allowlist stay
 * identical everywhere.
 *
 * <p>Hard rules: executable, entrypoint, and working directory come ONLY from server configuration
 * — never from user or API input; the child environment carries only the minimal set Node needs on
 * Windows ({@code PATH}, {@code SystemRoot}, {@code COMSPEC}, {@code TEMP}, {@code TMP}, {@code
 * PATHEXT}); configured paths are validated absolute, existing, and typed.
 */
public final class XrspsProcessBuilderFactory {

  private static final String[] ALLOWED_ENV_KEYS = {
    "PATH", "SystemRoot", "COMSPEC", "TEMP", "TMP", "PATHEXT"
  };

  private XrspsProcessBuilderFactory() {}

  /**
   * Builds a validated process launch for the given worker mode.
   *
   * @param mode exactly {@code --stdio} or {@code --capabilities}
   */
  public static ProcessBuilder create(ReplayProperties.Xrsps config, String mode) {
    if (!java.util.Set.of("--stdio", "--capabilities").contains(mode))
      throw unavailable("Unsupported worker mode", null);
    validate(config);
    // Every worker gets its own user/PID/network/mount namespaces and read-only approved
    // code root. No host home, service credentials, sockets, or network interfaces are mounted.
    // A denied sandbox launch prevents execution; there is no unsandboxed fallback.
    Path isolation = Path.of("/usr/bin/bwrap");
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")
        || !Files.isExecutable(isolation)) {
      throw unavailable(
          "Offline worker isolation is unavailable",
          "Run workers on an approved Linux host with bubblewrap and unprivileged namespaces");
    }
    var command = new java.util.ArrayList<String>();
    java.util.Collections.addAll(
        command,
        isolation.toString(),
        "--unshare-all",
        "--new-session",
        "--die-with-parent",
        "--ro-bind",
        "/usr",
        "/usr",
        "--ro-bind",
        "/lib",
        "/lib",
        "--ro-bind",
        "/lib64",
        "/lib64",
        "--proc",
        "/proc",
        "--dev",
        "/dev",
        "--tmpfs",
        "/tmp");
    Path entryRoot = Path.of(config.getEntrypoint()).getParent();
    Path workRoot =
        config.getWorkingDirectory() == null || config.getWorkingDirectory().isBlank()
            ? entryRoot
            : Path.of(config.getWorkingDirectory());
    if (workRoot.getNameCount() < 2
        || Path.of(System.getProperty("user.home")).startsWith(workRoot))
      throw unavailable(
          "Worker staging root is too broad",
          "Use an integrity-checked worker package/cache staging root");
    java.util.Collections.addAll(command, "--ro-bind", workRoot.toString(), workRoot.toString());
    if (!entryRoot.startsWith(workRoot))
      java.util.Collections.addAll(
          command, "--ro-bind", entryRoot.toString(), entryRoot.toString());
    if (config.getExecutable().startsWith("/")
        && !config.getExecutable().startsWith("/usr/")
        && !Path.of(config.getExecutable()).startsWith(workRoot))
      java.util.Collections.addAll(
          command, "--ro-bind", config.getExecutable(), config.getExecutable());
    java.util.Collections.addAll(command, "--chdir", workRoot.toString(), "--");
    command.add(config.getExecutable());
    command.add(config.getEntrypoint());
    command.add(mode);

    ProcessBuilder pb = new ProcessBuilder(command);
    if (config.getWorkingDirectory() != null && !config.getWorkingDirectory().isBlank()) {
      pb.directory(new File(config.getWorkingDirectory()));
    }
    pb.redirectErrorStream(false);
    Map<String, String> env = pb.environment();
    env.clear();
    for (String key : ALLOWED_ENV_KEYS) {
      String value = System.getenv(key);
      if (value != null && !value.isBlank()) {
        env.put(key, value);
      }
    }
    env.putIfAbsent("PATH", "/usr/bin:/bin:/usr/local/bin");
    return pb;
  }

  /** Fail-closed validation of every server-owned path before any process exists. */
  private static void validate(ReplayProperties.Xrsps config) {
    if (config.getEntrypoint() == null || config.getEntrypoint().isBlank()) {
      throw unavailable(
          "xRSPS kernel entrypoint is not configured",
          "Set REPLAY_XRSPS_ENTRYPOINT to the compiled worker main.js");
    }
    Path entrypoint = Path.of(config.getEntrypoint());
    if (!entrypoint.isAbsolute()) {
      throw unavailable("xRSPS entrypoint must be an absolute path", null);
    }
    if (!Files.isRegularFile(entrypoint) || !entrypoint.toString().endsWith(".js")) {
      throw unavailable("xRSPS entrypoint must be an existing regular .js file", null);
    }
    String executable = config.getExecutable();
    if (executable == null || executable.isBlank())
      throw unavailable("Worker executable is not configured", null);
    if (executable.contains("/") || executable.contains("\\") || executable.contains(":")) {
      Path exe = Path.of(executable);
      if (!exe.isAbsolute() || !Files.isRegularFile(exe) || !Files.isExecutable(exe)) {
        throw unavailable("Configured xRSPS executable is not an existing launchable file", null);
      }
    }
    String workDir = config.getWorkingDirectory();
    if (workDir != null && !workDir.isBlank()) {
      Path dir = Path.of(workDir);
      if (!dir.isAbsolute() || !Files.isDirectory(dir)) {
        throw unavailable("Configured xRSPS working directory is missing or relative", null);
      }
    }
  }

  private static ReplayException unavailable(String message, String remediation) {
    return new ReplayException(
        ReplayErrorCode.XRSPS_CAPABILITY_UNAVAILABLE,
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        false,
        message,
        remediation,
        null,
        null);
  }
}
