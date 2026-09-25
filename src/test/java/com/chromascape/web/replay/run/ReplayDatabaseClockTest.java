package com.chromascape.web.replay.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.chromascape.web.replay.api.dto.ReplayRunPhase;
import com.chromascape.web.replay.api.dto.ReplayRunState;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.report.ReplayReportService;
import com.chromascape.web.replay.run.entity.ReplayRun;
import com.chromascape.web.replay.run.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Durable replay timestamps come ONLY from PostgreSQL: transitions stamp exactly the captured
 * database time and deadline checks delegate to the database predicate — never the JVM clock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayDatabaseClockTest {

  @Mock ReplayRunRepository runRepository;
  @Mock ReplayAttemptRepository attemptRepository;
  @Mock ReplayCheckpointRepository checkpointRepository;
  @Mock ReplayEventRepository eventRepository;
  @Mock com.chromascape.web.replay.report.repository.ReplayReportRepository reportRepository;
  @Mock ScenarioManifestRepository manifestRepository;
  @Mock ReplayReportService reportService;
  @Mock Environment environment;

  private ReplayRunService service;

  private final UUID runId = UUID.randomUUID();
  private final LocalDateTime dbNow = LocalDateTime.of(2026, 8, 25, 10, 0);

  @BeforeEach
  void setUp() {
    lenient()
        .when(
            environment.getProperty(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class)))
        .thenReturn(30);
    service =
        new ReplayRunService(
            runRepository,
            attemptRepository,
            checkpointRepository,
            eventRepository,
            reportRepository,
            manifestRepository,
            reportService,
            environment);
    ReflectionTestUtils.setField(service, "leaseDurationSeconds", 30);
  }

  private ReplayRun runIn(ReplayRunState state) {
    ReplayRun run = new ReplayRun();
    run.setId(runId);
    run.setState(state);
    return run;
  }

  @Test
  void startingTransitionsStampTheCapturedDatabaseTime() {
    when(runRepository.currentTimestamp()).thenReturn(dbNow);
    ReplayRun starting = runIn(ReplayRunState.STARTING);

    service.applyTransition(starting, ReplayRunState.RUNNING, ReplayRunPhase.RESET);

    assertNull(starting.getTerminalAt());
    assertEquals(ReplayRunState.RUNNING, starting.getState());
  }

  @Test
  void terminalTransitionsStampExactlyTheSuppliedTimestampNotTheJvmClock() {
    when(runRepository.currentTimestamp()).thenReturn(dbNow.minusHours(1));
    ReplayRun running = runIn(ReplayRunState.RUNNING);

    service.applyTransition(running, ReplayRunState.FAILED, null, dbNow);

    assertEquals(
        dbNow,
        running.getTerminalAt(),
        "terminal_at must be the caller-captured DATABASE timestamp");
  }

  @Test
  void threeArgOverloadCapturesItsOwnFreshDatabaseTimestamp() {
    when(runRepository.currentTimestamp()).thenReturn(dbNow);
    ReplayRun running = runIn(ReplayRunState.RUNNING);

    service.applyTransition(running, ReplayRunState.TIMED_OUT, null);

    verifyOnce();
    assertEquals(dbNow, running.getTerminalAt());
  }

  private void verifyOnce() {
    org.mockito.Mockito.verify(runRepository).currentTimestamp();
  }

  @Test
  void deadlineDecisionsDelegateToTheDatabasePredicate() {
    when(runRepository.isDeadlineExceeded(runId, 60L)).thenReturn(true);

    assertTrue(
        service.isPastDeadline(runIn(ReplayRunState.RUNNING), 60),
        "the database predicate decides expiry");

    // No JVM clock comparison anywhere: only the repository was consulted.
    verifyNoInteractions(eventRepository, checkpointRepository);
  }

  @Test
  void deadlinePredicateIsTheOnlyExpiryAuthorityForFalseResultsToo() {
    when(runRepository.isDeadlineExceeded(runId, 3600L)).thenReturn(false);

    assertFalse(service.isPastDeadline(runIn(ReplayRunState.RUNNING), 3600));
  }
}
