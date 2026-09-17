package com.chromascape.utils.core.screen.window;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

  @Test
  void readCmdlineAcceptsExactlyMaxFileBytes() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("42"));
    byte[] exact = new byte[ProcFs.MAX_FILE_BYTES];
    exact[0] = 'j';
    Files.write(pidDir.resolve("cmdline"), exact);

    Optional<byte[]> cmdline = new ProcFs(root).readCmdline(42L);

    assertTrue(cmdline.isPresent());
    assertEquals(ProcFs.MAX_FILE_BYTES, cmdline.get().length);
  }

  @Test
  void readCmdlineRejectsMaxFileBytesPlusOne() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("42"));
    Files.write(pidDir.resolve("cmdline"), new byte[ProcFs.MAX_FILE_BYTES + 1]);

    assertTrue(new ProcFs(root).readCmdline(42L).isEmpty());
  }

  @Test
  void readCmdlineIsEmptyForADisappearingEntry() throws Exception {
    // Broken symlink: the entry vanishes between listing and open, so the open itself must fail
    // soft instead of throwing
    Path pidDir = Files.createDirectories(root.resolve("42"));
    Files.createSymbolicLink(pidDir.resolve("cmdline"), root.resolve("gone-forever"));

    assertTrue(new ProcFs(root).readCmdline(42L).isEmpty());
  }

  @Test
  void invalidUtf8StaysBoundedAndNeverEscapesAsDiagnostics() throws Exception {
    Path pidDir = Files.createDirectories(root.resolve("42"));
    byte[] invalid = new byte[ProcFs.MAX_FILE_BYTES];
    for (int i = 0; i < invalid.length; i++) {
      invalid[i] = (byte) 0xFF;
    }
    Files.write(pidDir.resolve("cmdline"), invalid);
    ProcFs procFs = new ProcFs(root);

    // Within the cap: returned as raw bytes, no decoding exception
    Optional<byte[]> withinCap = procFs.readCmdline(42L);
    assertTrue(withinCap.isPresent());
    assertEquals(ProcFs.MAX_FILE_BYTES, withinCap.get().length);

    // Over the cap: rejected, and the rejected content never reaches any diagnostic payload
    Files.write(pidDir.resolve("cmdline"), new byte[ProcFs.MAX_FILE_BYTES + 1]);
    assertTrue(procFs.readCmdline(42L).isEmpty());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void realProcfsZeroSizeFilesAreStillReadBounded() throws Exception {
    // Procfs pseudo-files report size zero regardless of readable content; the bound must be
    // enforced on the bytes read, never on the (unreliable) size metadata
    long selfPid = ProcessHandle.current().pid();
    Path selfCmdline = Path.of("/proc", Long.toString(selfPid), "cmdline");

    Optional<byte[]> cmdline = new ProcFs(Path.of("/proc")).readCmdline(selfPid);

    assertTrue(Files.isRegularFile(selfCmdline));
    assertEquals(0L, Files.size(selfCmdline));
    assertTrue(cmdline.isPresent());
    assertTrue(cmdline.get().length > 0);
    assertTrue(cmdline.get().length <= ProcFs.MAX_FILE_BYTES);
  }

  @Test
  void listPidDirectoriesRetainsTheSmallestPidsDeterministically() throws Exception {
    // More numeric entries than the cap, created in two different random orders: the retained
    // subset and its ascending order must be identical both times
    List<Long> pids = new ArrayList<>();
    for (long pid = 0; pid <= ProcFs.MAX_PID_ENTRIES + 15; pid++) {
      pids.add(pid);
    }
    List<Long> shuffled = new ArrayList<>(pids);
    Collections.shuffle(shuffled, new Random(1663L));
    List<Long> reshuffled = new ArrayList<>(pids);
    Collections.shuffle(reshuffled, new Random(42L));

    ProcFs.PidListing firstScan = listingIn(root.resolve("a"), shuffled);
    ProcFs.PidListing secondScan = listingIn(root.resolve("b"), reshuffled);

    assertTrue(firstScan.truncated());
    assertTrue(secondScan.truncated());
    assertEquals(firstScan.pids(), secondScan.pids());
    assertEquals(ProcFs.MAX_PID_ENTRIES, firstScan.pids().size());
    assertEquals(pids.subList(0, ProcFs.MAX_PID_ENTRIES), firstScan.pids());
    assertEquals(firstScan.pids().stream().sorted().toList(), firstScan.pids());
  }

  @Test
  void listPidDirectoriesReportsNoTruncationUnderTheCap() throws Exception {
    listingIn(root, List.of(9L, 40L, 7L));

    ProcFs.PidListing listing = new ProcFs(root).listPidDirectories();

    assertFalse(listing.truncated());
    assertEquals(List.of(7L, 9L, 40L), listing.pids());
  }

  private static ProcFs.PidListing listingIn(Path procRoot, List<Long> pidsToCreate)
      throws Exception {
    for (long pid : pidsToCreate) {
      Files.createDirectories(procRoot.resolve(Long.toString(pid)));
    }
    return new ProcFs(procRoot).listPidDirectories();
  }
}
