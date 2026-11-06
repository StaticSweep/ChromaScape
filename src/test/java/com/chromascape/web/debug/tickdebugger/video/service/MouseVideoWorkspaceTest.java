package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MouseVideoWorkspaceTest {
  @TempDir Path root;

  private MouseVideoProperties properties() {
    MouseVideoProperties properties = new MouseVideoProperties();
    properties.setDirectory(root.toString());
    properties.setRestrictedLauncher(true);
    properties.setMaxJobDiskBytes(32L * 1024 * 1024);
    return properties;
  }

  @Test
  void multipleFilesHitAnActualFilesystemLimitAndUnmountBeforeReuse() throws Exception {
    assumeTrue(MouseVideoWorkspace.toolsAvailable());
    Path path;
    try (MouseVideoWorkspace workspace = MouseVideoWorkspace.open(properties(), "bounded")) {
      path = workspace.path();
      assertThat(MouseVideoWorkspace.isBoundedMount(path, 16L * 1024 * 1024)).isTrue();
      byte[] block = new byte[1024 * 1024];
      assertThatThrownBy(
              () -> {
                for (int i = 0; i < 40; i++) {
                  Files.write(path.resolve("part-" + i), block);
                }
              })
          .isInstanceOf(IOException.class);
      long bytes;
      try (var files = Files.list(path)) {
        bytes =
            files
                .filter(Files::isRegularFile)
                .mapToLong(
                    file -> {
                      try {
                        return Files.size(file);
                      } catch (IOException e) {
                        throw new RuntimeException(e);
                      }
                    })
                .sum();
      }
      assertThat(bytes).isLessThanOrEqualTo(16L * 1024 * 1024);
      workspace.close();
      workspace.close();
    }
    // A confirmed close releases both the image and the empty mountpoint, so the execution identity
    // is free again; existing output is what must refuse reuse.
    assertThat(Files.exists(path)).isFalse();
    assertThat(Files.exists(root.resolve("work-volumes/bounded.ext2"))).isFalse();
    Files.createDirectories(root.resolve("jobs/bounded"));
    Files.writeString(root.resolve("jobs/bounded/leftover"), "unpublished");
    assertThatThrownBy(() -> MouseVideoWorkspace.open(properties(), "bounded"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void sandboxCannotBypassVolumeThroughRootOrDevAndTmpIsBounded() throws Exception {
    assumeTrue(MouseVideoWorkspace.toolsAvailable());
    MouseVideoProperties properties = properties();
    MouseVideoRestrictedLauncher launcher = new MouseVideoRestrictedLauncher(properties);
    assumeTrue(launcher.available());
    try (MouseVideoWorkspace workspace = MouseVideoWorkspace.open(properties, "sandbox")) {
      Process child =
          new ProcessBuilder(
                  launcher.wrap(
                      List.of(
                          "/bin/bash",
                          "-c",
                          "touch /bypass /dev/bypass 2>/dev/null; "
                              + "test ! -e /bypass && test ! -e /dev/bypass || exit 3; "
                              + "echo ok > "
                              + workspace.path().resolve("result")
                              + "; dd if=/dev/zero of=/tmp/fill bs=1M count=20 status=none"
                              + " 2>/dev/null; test $? -ne 0"),
                      workspace.path(),
                      List.of()))
              .start();
      assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).isZero();
      assertThat(Files.readString(workspace.path().resolve("result"))).isEqualTo("ok\n");
    }
  }

  @Test
  void closingAConfirmedExecutionRemovesItsImageAndEmptyMountpoint() throws Exception {
    assumeTrue(MouseVideoWorkspace.toolsAvailable());
    MouseVideoProperties properties = properties();
    Path workspace;
    try (MouseVideoWorkspace execution = MouseVideoWorkspace.open(properties, "released")) {
      workspace = execution.path();
      assertThat(Files.isDirectory(workspace)).isTrue();
    }
    assertThat(Files.exists(workspace)).isFalse();
    assertThat(Files.exists(root.resolve("work-volumes/released.ext2"))).isFalse();
  }

  @Test
  void sweeperPreservesMountedVolumesAndAgesOutClosedOnes() throws Exception {
    assumeTrue(MouseVideoWorkspace.toolsAvailable());
    MouseVideoProperties properties = properties();
    try (MouseVideoWorkspace workspace = MouseVideoWorkspace.open(properties, "sweep")) {
      Path image = root.resolve("work-volumes/sweep.ext2");
      Files.setLastModifiedTime(
          image, java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
      assertThat(MouseVideoWorkspace.sweepUnmounted(properties, java.time.Instant.now())).isZero();
      assertThat(Files.exists(image)).isTrue();
      Files.copy(image, root.resolve("work-volumes/orphan.ext2"));
      workspace.close();
      assertThat(Files.exists(image)).isFalse();
      assertThat(
              MouseVideoWorkspace.sweepUnmounted(
                  properties, java.time.Instant.now().plusSeconds(1)))
          .isEqualTo(1);
      assertThat(Files.exists(image)).isFalse();
    }
  }

  @Test
  void restrictedLauncherRefusesAnUnboundedWorkspace() throws Exception {
    MouseVideoProperties properties = properties();
    MouseVideoRestrictedLauncher launcher = new MouseVideoRestrictedLauncher(properties);
    assumeTrue(launcher.available());
    Path workspace = root.resolve("jobs/unbounded");
    Files.createDirectories(workspace);
    assertThatThrownBy(() -> launcher.wrap(List.of("/bin/true"), workspace, List.of()))
        .isInstanceOf(IOException.class);
  }
}
