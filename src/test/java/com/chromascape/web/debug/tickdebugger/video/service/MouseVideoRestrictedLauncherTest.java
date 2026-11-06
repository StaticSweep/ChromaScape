package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real restricted-launcher probe: the sandbox must start a harmless child, expose no network
 * namespace and keep the writable job root while an absolute host path outside the assigned roots
 * is absent. This is an availability/containment smoke test, not a full hostile-escape
 * qualification.
 */
class MouseVideoRestrictedLauncherTest {

  @TempDir Path root;

  @Test
  void availabilityIsNotReportedAsEnforcementWhenRestrictionIsDisabled() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    properties.setRestrictedLauncher(false);
    assertThat(new MouseVideoRestrictedLauncher(properties).status())
        .containsEntry("networkNamespaceIsolated", false)
        .containsEntry("workspaceDiskQuotaEnforced", false)
        .containsEntry("perJobWritableMount", false)
        .containsEntry("sharedMediaRootWritable", true);
  }

  @Test
  void requiredRestrictionRefusesUnsupportedHostsBeforeAnyFallback() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    properties.setRestrictedLauncher(true);
    var launcher = new MouseVideoRestrictedLauncher(properties);
    for (String os : List.of("Windows 11", "Mac OS X")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> MouseVideoProcessRunner.requireRestrictedHost(os, launcher))
          .isInstanceOf(com.chromascape.web.debug.tickdebugger.video.MouseVideoException.class);
    }
    MouseVideoProcessRunner.requireRestrictedHost("Linux", launcher);
  }

  @Test
  void wrapsWithoutAShellAndRunsUnderUnsharedNamespaces() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("sandbox-root").toString());
    properties.setRestrictedLauncher(true);
    MouseVideoRestrictedLauncher launcher = new MouseVideoRestrictedLauncher(properties);
    assumeTrue(launcher.available(), "bubblewrap with user namespaces is required");

    List<String> wrapped = launcher.wrap(List.of("/bin/true"));
    assertThat(wrapped).contains("--unshare-all", "--clearenv", "--die-with-parent");
    assertThat(wrapped).doesNotContain("sh", "-c");

    Process network =
        new ProcessBuilder(
                launcher.wrap(
                    List.of(
                        "/bin/bash",
                        "-c",
                        "(exec 3<>/dev/tcp/1.1.1.1/80) >/dev/null 2>&1 && echo reachable || echo"
                            + " isolated")))
            .redirectErrorStream(true)
            .start();
    assertThat(network.waitFor(20, TimeUnit.SECONDS)).isTrue();
    String output = new String(network.getInputStream().readAllBytes());
    assertThat(output.trim()).isEqualTo("isolated");
  }

  @Test
  void sandboxedChildCannotTruncateASiblingOriginalButCanWriteItsOwnJob() throws Exception {
    MouseVideoProperties properties = new MouseVideoProperties();
    Path mediaRoot = root.resolve("sandbox-root");
    properties.setDirectory(mediaRoot.toString());
    properties.setRestrictedLauncher(true);
    MouseVideoRestrictedLauncher launcher = new MouseVideoRestrictedLauncher(properties);
    assumeTrue(launcher.available(), "bubblewrap with user namespaces is required");

    try (MouseVideoWorkspace volume = MouseVideoWorkspace.open(properties, "job-1")) {
      Path job = volume.path();
      Path sibling = mediaRoot.resolve("bytes").resolve("ab").resolve("original.bin");
      Files.createDirectories(job);
      Files.createDirectories(sibling.getParent());
      Files.writeString(sibling, "original-bytes");

      List<String> wrapped =
          launcher.wrap(
              List.of(
                  "/bin/bash",
                  "-c",
                  "truncate -s 0 "
                      + sibling
                      + " 2>/dev/null; echo done > "
                      + job.resolve("out.txt")));
      // The whole media root must never appear as a writable bind; only the job directory may.
      for (int i = 0; i + 2 < wrapped.size(); i++) {
        if ("--bind".equals(wrapped.get(i))) {
          assertThat(wrapped.get(i + 1)).startsWith(job.toString());
        }
      }

      Process process = new ProcessBuilder(wrapped).redirectErrorStream(true).start();
      assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
      assertThat(Files.readString(sibling)).isEqualTo("original-bytes");
      assertThat(Files.readString(job.resolve("out.txt")).trim()).isEqualTo("done");
    }
  }

  @Test
  void reportsReadinessFailureWhenUnavailable() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.resolve("missing").toString());
    properties.setRestrictedLauncher(true);
    MouseVideoRestrictedLauncher launcher = new MouseVideoRestrictedLauncher(properties);
    if (launcher.available()) {
      assertThat(launcher.status())
          .containsEntry("required", true)
          .containsEntry("available", true);
    } else {
      assertThat(launcher.status())
          .containsEntry("required", true)
          .containsEntry("available", false);
    }
    assertThat(Files.isDirectory(root)).isTrue();
  }
}
