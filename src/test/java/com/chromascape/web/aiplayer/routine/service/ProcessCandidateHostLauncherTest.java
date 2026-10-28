package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The SC-2106 R9.2 pause lines between ChromaScape and the host process. */
class ProcessCandidateHostLauncherTest {

  @Test
  void onlyAHostStatusLineIsAPauseReport() {
    assertEquals(
        Boolean.TRUE,
        ProcessCandidateHostLauncher.pausedStatus(
            bytes("{\"schema\":\"sc2106-host-status/v1\",\"paused\":true}")));
    assertEquals(
        Boolean.FALSE,
        ProcessCandidateHostLauncher.pausedStatus(
            bytes("{\"schema\":\"sc2106-host-status/v1\",\"paused\":false}")));
    assertNull(
        ProcessCandidateHostLauncher.pausedStatus(
            bytes("{\"schema\":\"sc2106-host-outcome/v1\"}")));
    assertNull(
        ProcessCandidateHostLauncher.pausedStatus(bytes("{\"schema\":\"sc2106-host-status/v1\"}")));
    assertNull(ProcessCandidateHostLauncher.pausedStatus(bytes("not json")));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void pauseAndResumeTravelAsLinesAndTheOutcomeFollowsTheStatusLines() throws Exception {
    // A stand-in host: answers pause and resume like CandidateHostMain, then writes its outcome.
    String host =
        "read job; "
            + "read control; [ \"$control\" = pause ] && echo '{\"schema\":\"sc2106-host-status/v1\",\"paused\":true}'; "
            + "read control; [ \"$control\" = resume ] && echo '{\"schema\":\"sc2106-host-status/v1\",\"paused\":false}'; "
            + "echo '{\"schema\":\"sc2106-host-outcome/v1\",\"answered\":true}'";
    ProcessCandidateHostLauncher launcher =
        new ProcessCandidateHostLauncher(List.of("sh", "-c", host), Map.of(), null);
    AtomicBoolean pause = new AtomicBoolean();
    List<Boolean> reported = new CopyOnWriteArrayList<>();

    CompletableFuture<CandidateHostLauncher.HostOutcome> running =
        CompletableFuture.supplyAsync(
            () ->
                launcher.run(
                    Map.of("schema", "sc2106-host-job/v2"),
                    Duration.ofSeconds(30),
                    new CandidateHostLauncher.HostControl(
                        new AtomicBoolean(), pause, reported::add)));
    pause.set(true);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!reported.contains(Boolean.TRUE)) {
      assertTrue(System.nanoTime() < deadline, "the host never reported paused");
      Thread.sleep(10);
    }
    assertFalse(running.isDone(), "a status line is not the outcome");
    pause.set(false);

    CandidateHostLauncher.HostOutcome outcome = running.get(10, TimeUnit.SECONDS);
    assertFalse(outcome.lost(), String.valueOf(outcome.detail()));
    assertEquals(Boolean.TRUE, outcome.document().get("answered"));
    assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), reported);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aResumeBeforeTheFirstPollStillReachesAJobThatStartedPaused() throws Exception {
    // The host parks a startPaused job; only a resume line lets it answer.
    String host =
        "read job; read control; [ \"$control\" = resume ] && "
            + "echo '{\"schema\":\"sc2106-host-outcome/v1\",\"answered\":true}'";
    ProcessCandidateHostLauncher launcher =
        new ProcessCandidateHostLauncher(List.of("sh", "-c", host), Map.of(), null);

    CandidateHostLauncher.HostOutcome outcome =
        launcher.run(
            Map.of("schema", "sc2106-host-job/v2", "startPaused", true),
            Duration.ofSeconds(10),
            // Resumed already: the pause flag is clear before the launcher's first poll.
            new CandidateHostLauncher.HostControl(new AtomicBoolean(), new AtomicBoolean(), null));

    assertFalse(outcome.lost(), String.valueOf(outcome.detail()));
    assertEquals(Boolean.TRUE, outcome.document().get("answered"));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aHostFloodingStatusLinesIsStoppedAndNeverSucceeds() {
    String status = "{\"schema\":\"sc2106-host-status/v1\",\"paused\":true}";
    String host =
        "read job; i=0; while [ $i -le "
            + (ProcessCandidateHostLauncher.MAX_STATUS_LINES + 1)
            + " ]; do echo '"
            + status
            + "'; i=$((i+1)); done; "
            + "echo '{\"schema\":\"sc2106-host-outcome/v1\",\"answered\":true}'; sleep 60";
    ProcessCandidateHostLauncher launcher =
        new ProcessCandidateHostLauncher(List.of("sh", "-c", host), Map.of(), null);
    List<Boolean> reported = new CopyOnWriteArrayList<>();

    long started = System.nanoTime();
    CandidateHostLauncher.HostOutcome outcome =
        launcher.run(
            Map.of("schema", "sc2106-host-job/v2"),
            Duration.ofSeconds(30),
            new CandidateHostLauncher.HostControl(
                new AtomicBoolean(), new AtomicBoolean(), reported::add));

    assertTrue(outcome.lost());
    assertNull(outcome.document());
    assertEquals("HOST_STATUS_FLOOD", outcome.detail());
    assertEquals(ProcessCandidateHostLauncher.MAX_STATUS_LINES, reported.size());
    assertTrue(
        System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20),
        "the flooding host is stopped, not waited out");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aBindingIsAnsweredOnlyAfterTheHandlerDecided() {
    // The host reports a binding, then echoes the owner's answer back inside its outcome.
    String host =
        "read job; echo '{\"schema\":\"sc2106-host-binding/v1\",\"bindingId\":\"b-1\","
            + "\"generation\":1}'; read answer; "
            + "echo \"{\\\"schema\\\":\\\"sc2106-host-outcome/v1\\\",\\\"answer\\\":\\\"$answer\\\"}\"";
    ProcessCandidateHostLauncher launcher =
        new ProcessCandidateHostLauncher(List.of("sh", "-c", host), Map.of(), null);
    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();

    CandidateHostLauncher.HostOutcome accepted =
        launcher.run(
            Map.of("schema", "sc2106-host-job/v2"),
            Duration.ofSeconds(10),
            new CandidateHostLauncher.HostControl(
                new AtomicBoolean(),
                new AtomicBoolean(),
                null,
                binding -> {
                  seen.add(binding);
                  return true;
                }));
    assertFalse(accepted.lost(), String.valueOf(accepted.detail()));
    assertEquals("bound b-1", accepted.document().get("answer"));
    assertEquals(1, seen.size());
    assertEquals(1, ((Number) seen.get(0).get("generation")).intValue());

    CandidateHostLauncher.HostOutcome refused =
        launcher.run(
            Map.of("schema", "sc2106-host-job/v2"),
            Duration.ofSeconds(10),
            new CandidateHostLauncher.HostControl(
                new AtomicBoolean(),
                new AtomicBoolean(),
                null,
                binding -> {
                  throw new IllegalStateException("the database is gone");
                }));
    assertEquals("unbound b-1", refused.document().get("answer"));

    CandidateHostLauncher.HostOutcome unhandled =
        launcher.run(
            Map.of("schema", "sc2106-host-job/v2"),
            Duration.ofSeconds(10),
            new CandidateHostLauncher.HostControl(new AtomicBoolean(), new AtomicBoolean(), null));
    assertEquals("unbound b-1", unhandled.document().get("answer"), "no handler, no binding");
  }

  @Test
  void onlyAWellFormedBindingLineIsABinding() {
    assertEquals(
        "b-1",
        ProcessCandidateHostLauncher.bindingLine(
                bytes("{\"schema\":\"sc2106-host-binding/v1\",\"bindingId\":\"b-1\"}"))
            .get("bindingId"));
    assertNull(
        ProcessCandidateHostLauncher.bindingLine(
            bytes("{\"schema\":\"sc2106-host-binding/v1\",\"bindingId\":\"a b\"}")));
    assertNull(
        ProcessCandidateHostLauncher.bindingLine(
            bytes("{\"schema\":\"sc2106-host-binding/v1\",\"bindingId\":7}")));
    assertNull(
        ProcessCandidateHostLauncher.bindingLine(
            bytes(
                "{\"schema\":\"sc2106-host-binding/v1\",\"bindingId\":\""
                    + "x".repeat(ProcessCandidateHostLauncher.MAX_BINDING_ID_CHARS + 1)
                    + "\"}")));
    assertNull(
        ProcessCandidateHostLauncher.bindingLine(bytes("{\"schema\":\"sc2106-host-status/v1\"}")));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
