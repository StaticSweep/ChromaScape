package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcFsTest {

  // fields: pid (1) comm (2) state (3) ppid (4) pgrp (5) + 16 fillers (6-21) + starttime (22)
  private static final String STAT_WITH_STARTTIME =
      "1234 (java) S 1 1 " + "0 ".repeat(16) + "98765 0 0 0 0 0";

  @TempDir Path root;

  @Test
  void listPidDirsReturnsSortedNumericDirsOnly() throws Exception {
    Files.createDirectories(root.resolve("12"));
    Files.createDirectories(root.resolve("7"));
    Files.createDirectories(root.resolve("999"));
    Files.createDirectories(root.resolve("acpi"));

    assertEquals(List.of(7L, 12L, 999L), new ProcFs(root).listPidDirs());
  }

  @Test
  void listPidDirsOnMissingRootIsEmpty() {
    assertTrue(new ProcFs(root.resolve("missing")).listPidDirs().isEmpty());
  }

  @Test
  void readCmdlineReturnsNullDelimitedBytesAndEmptyForMissingPid() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("42"));
    Files.write(
        pidDir.resolve("cmdline"), new byte[] {'j', 'a', 'v', 'a', 0, '-', 'j', 'a', 'r', 0});

    Optional<byte[]> cmdline = new ProcFs(root).readCmdline(42L);

    assertTrue(cmdline.isPresent());
    assertEquals(10, cmdline.get().length);
    assertTrue(new ProcFs(root).readCmdline(43L).isEmpty());
  }

  @Test
  void readCmdlineFailsForDirectoryEntry() throws Exception {
    // Root-proof unreadable simulation: the cmdline path is a directory, so reads fail everywhere
    Files.createDirectories(root.resolve("42").resolve("cmdline"));

    assertTrue(new ProcFs(root).readCmdline(42L).isEmpty());
  }

  @Test
  void readStartTimeTicksAndUptimeParsesStatField22AndUptime() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("1234"));
    Files.writeString(pidDir.resolve("stat"), STAT_WITH_STARTTIME);
    Files.writeString(root.resolve("uptime"), "5678.90 12345.67\n");

    Optional<long[]> result = new ProcFs(root).readStartTimeTicksAndUptime(1234L);

    assertTrue(result.isPresent());
    assertArrayEquals(new long[] {98765L, 5678L}, result.get());
  }

  @Test
  void readStartTimeTicksAndUptimeIsEmptyForMalformedStatOrMissingUptime() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("1234"));
    Files.writeString(pidDir.resolve("stat"), "garbage without parens");
    assertTrue(new ProcFs(root).readStartTimeTicksAndUptime(1234L).isEmpty());

    Files.writeString(pidDir.resolve("stat"), STAT_WITH_STARTTIME);
    // uptime file missing: still unreadable
    assertTrue(new ProcFs(root).readStartTimeTicksAndUptime(1234L).isEmpty());
  }

  @Test
  void readStartTimeTicksAndUptimeIsEmptyForDisappearingPid() {
    assertTrue(new ProcFs(root).readStartTimeTicksAndUptime(9999L).isEmpty());
  }

  @Test
  void statParsingHandlesCommasAndSpacesInsideTheCommField() {
    assertEquals(777L, ProcFs.parseStartTimeTicks("1 (cat 5) S 1 1 " + "0 ".repeat(16) + "777 0"));
    assertEquals(-1L, ProcFs.parseStartTimeTicks("no parens at all"));
    assertEquals(-1L, ProcFs.parseStartTimeTicks("1 (short) S 1 1"));
  }

  @Test
  void bootIdIsTrimmedWhenPresentAndEmptyWhenAbsent() throws Exception {
    Files.createDirectories(root.resolve("sys").resolve("kernel").resolve("random"));
    Files.writeString(
        root.resolve("sys").resolve("kernel").resolve("random").resolve("boot_id"), "  boot-1\n");

    assertEquals(Optional.of("boot-1"), new ProcFs(root).readBootId());
    // Missing root: empty rather than throwing
    assertTrue(new ProcFs(root.resolve("other")).readBootId().isEmpty());
  }

  @Test
  void readExeTargetResolvesSymlinkAndIsEmptyWhenAbsent() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("42"));
    Files.createSymbolicLink(pidDir.resolve("exe"), Path.of("/opt/jre/bin/java"));

    assertEquals(Optional.of("/opt/jre/bin/java"), new ProcFs(root).readExeTarget(42L));
    assertTrue(new ProcFs(root).readExeTarget(43L).isEmpty());
  }
}
