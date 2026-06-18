package com.chromascape.web.debug.patchloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopEvidenceService;
import com.chromascape.web.debug.patchloop.service.PatchLoopSessionResolverService;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test suite for PatchLoopEvidenceService. */
@ExtendWith(MockitoExtension.class)
class PatchLoopEvidenceServiceTest {

  @Mock private LogRepository logRepository;
  @Mock private TickLogRepository tickLogRepository;
  @Mock private DecisionRepository decisionRepository;
  @Mock private PatchLoopSessionResolverService sessionResolverService;

  private PatchLoopEvidenceService evidenceService;

  @BeforeEach
  void setUp() {
    evidenceService =
        new PatchLoopEvidenceService(
            logRepository, tickLogRepository, decisionRepository, sessionResolverService);
    lenient().when(sessionResolverService.resolveForIteration(any())).thenReturn(Optional.empty());
  }

  @Test
  @DisplayName("Evidence collected from all three sources with default config")
  void testCollectEvidenceFromAllSources() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockData();

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration);

    // Assert
    assertNotNull(bundle);
    assertEquals("TestScript", bundle.getScriptName());
    assertNotNull(bundle.getTimeWindow());
    assertEquals(2, bundle.getLogs().size());
    assertEquals(1, bundle.getTickLogs().size());
    assertEquals(1, bundle.getDecisions().size());

    // Verify iteration was updated with counts
    assertEquals(2, iteration.getEvidenceLogCount());
    assertEquals(1, iteration.getEvidenceTickLogCount());
    assertEquals(1, iteration.getEvidenceDecisionCount());
  }

  @Test
  @DisplayName("Evidence respects maxLogEntries config")
  void testEvidenceRespectsMaxLogEntries() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockDataWithManyLogs(100); // Return 100 logs

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setMaxLogEntries(10); // Limit to 10

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertEquals(10, bundle.getLogs().size()); // Should be limited to 10
  }

  @Test
  @DisplayName("Evidence respects maxTickLogs config")
  void testEvidenceRespectsMaxTickLogs() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockDataWithManyTickLogs(50); // Return 50 tick logs

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setMaxTickLogs(5); // Limit to 5

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertEquals(5, bundle.getTickLogs().size()); // Should be limited to 5
  }

  @Test
  @DisplayName("Evidence respects includeTypes config - only GENERAL_LOGS")
  void testEvidenceRespectsIncludeTypesGeneralLogsOnly() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockDataOnlyLogs();

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setIncludeTypes(Arrays.asList(EvidenceType.GENERAL_LOGS));

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertEquals(2, bundle.getLogs().size()); // Logs included
    assertEquals(0, bundle.getTickLogs().size()); // Tick logs excluded
    assertEquals(0, bundle.getDecisions().size()); // Decisions excluded
  }

  @Test
  @DisplayName("Evidence respects includeTypes config - only TICK_LOGS")
  void testEvidenceRespectsIncludeTypesTickLogsOnly() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockDataOnlyTickLogs();

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setIncludeTypes(Arrays.asList(EvidenceType.TICK_LOGS));

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertEquals(0, bundle.getLogs().size()); // Logs excluded
    assertEquals(1, bundle.getTickLogs().size()); // Tick logs included
    assertEquals(0, bundle.getDecisions().size()); // Decisions excluded
  }

  @Test
  @DisplayName("Evidence respects includeTypes config - only DECISIONS")
  void testEvidenceRespectsIncludeTypesDecisionsOnly() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockDataOnlyDecisions();

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setIncludeTypes(Arrays.asList(EvidenceType.DECISIONS));

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertEquals(0, bundle.getLogs().size()); // Logs excluded
    assertEquals(0, bundle.getTickLogs().size()); // Tick logs excluded
    assertEquals(1, bundle.getDecisions().size()); // Decisions included
  }

  @Test
  @DisplayName("Evidence uses default values when config is null")
  void testEvidenceUsesDefaultsWhenConfigNull() {
    // Arrange
    PatchLoopIteration iteration = createTestIteration();
    setupMockData();

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, null);

    // Assert
    assertNotNull(bundle);
    // Should collect all evidence types with default limits
    assertEquals(2, bundle.getLogs().size());
    assertEquals(1, bundle.getTickLogs().size());
    assertEquals(1, bundle.getDecisions().size());
  }

  @Test
  @DisplayName("Evidence respects timeWindowSeconds config")
  void testEvidenceRespectsTimeWindowSeconds() {
    // Arrange
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("test-iter");
    iteration.setScriptName("TestScript");
    iteration.setStatus(PatchLoopStatus.COLLECTING_EVIDENCE);
    iteration.setCreatedAt(LocalDateTime.now());
    // No run times, so will use time window from creation

    EvidenceConfigDto config = new EvidenceConfigDto();
    config.setTimeWindowSeconds(120); // 2 minutes instead of default 60

    setupMockDataOnlyLogs(); // Only need one evidence source to verify time window

    // Act
    AnalysisV1EvidenceBundle bundle = evidenceService.collectEvidence(iteration, config);

    // Assert
    assertNotNull(bundle);
    assertNotNull(bundle.getTimeWindow());

    // Verify time window is approximately 120 seconds
    long secondsDiff =
        java.time.Duration.between(
                bundle.getTimeWindow().getStart(), bundle.getTimeWindow().getEnd())
            .getSeconds();
    assertTrue(secondsDiff >= 115 && secondsDiff <= 125); // Allow small variance
  }

  @Test
  @DisplayName("Sufficiency validation passes with evidence")
  void testSufficiencyValidationPassesWithEvidence() {
    // Arrange
    AnalysisV1EvidenceBundle bundle = new AnalysisV1EvidenceBundle();
    bundle.setTimeWindow(new TimeWindow(LocalDateTime.now().minusMinutes(5), LocalDateTime.now()));
    bundle.setLogs(new ArrayList<>());
    bundle
        .getLogs()
        .add(
            new AnalysisV1EvidenceBundle.LogEvidence(
                LocalDateTime.now(), "ERROR", "Test", "Script"));

    // Act
    boolean isSufficient = evidenceService.validateEvidenceSufficiency(bundle);

    // Assert
    assertTrue(isSufficient);
  }

  @Test
  @DisplayName("Sufficiency validation fails without evidence")
  void testSufficiencyValidationFailsWithoutEvidence() {
    // Arrange
    AnalysisV1EvidenceBundle bundle = new AnalysisV1EvidenceBundle();
    bundle.setTimeWindow(new TimeWindow(LocalDateTime.now().minusMinutes(5), LocalDateTime.now()));
    bundle.setLogs(new ArrayList<>());
    bundle.setTickLogs(new ArrayList<>());
    bundle.setDecisions(new ArrayList<>());

    // Act
    boolean isSufficient = evidenceService.validateEvidenceSufficiency(bundle);

    // Assert
    assertFalse(isSufficient);
  }

  @Test
  @DisplayName("Sufficiency validation fails with null bundle")
  void testSufficiencyValidationFailsWithNullBundle() {
    // Act
    boolean isSufficient = evidenceService.validateEvidenceSufficiency(null);

    // Assert
    assertFalse(isSufficient);
  }

  // ========== Helper Methods ==========

  private PatchLoopIteration createTestIteration() {
    LocalDateTime now = LocalDateTime.now();
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("test-iteration-123");
    iteration.setScriptName("TestScript");
    iteration.setStatus(PatchLoopStatus.COLLECTING_EVIDENCE);
    iteration.setRunStartTime(now.minusMinutes(5));
    iteration.setRunEndTime(now);
    iteration.setRunSessionId("session-123");
    return iteration;
  }

  private void setupMockData() {
    LocalDateTime now = LocalDateTime.now();

    // Mock logs
    List<LogEntity> logs = new ArrayList<>();
    LogEntity log1 = new LogEntity();
    log1.setTimestamp(now.minusMinutes(2));
    log1.setLevel("ERROR");
    log1.setMessage("Error 1");
    log1.setSource("TestScript");
    logs.add(log1);

    LogEntity log2 = new LogEntity();
    log2.setTimestamp(now.minusMinutes(1));
    log2.setLevel("WARN");
    log2.setMessage("Warning 1");
    log2.setSource("TestScript");
    logs.add(log2);

    when(logRepository.findByTimestampBetween(any(), any())).thenReturn(logs);

    // Mock tick logs
    List<TickLog> tickLogs = new ArrayList<>();
    TickLog tickLog = new TickLog();
    tickLog.setSessionId("session-123");
    tickLog.setTick(150L);
    tickLog.setTimestamp(now.minusMinutes(3));
    tickLog.setSubsystem("MOVEMENT");
    tickLog.setSeverity("INFO");
    tickLog.setMessage("Moved to tile");
    tickLogs.add(tickLog);

    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            anyString(), anyLong(), anyLong()))
        .thenReturn(tickLogs);

    // Mock decisions
    List<DecisionEntity> decisions = new ArrayList<>();
    DecisionEntity decision = new DecisionEntity();
    decision.setTimestamp(now.minusMinutes(4));
    decision.setSuccess(true);
    decision.setTargetEntity("Rock");
    decision.setDecisionReason("Mining rock");
    decisions.add(decision);

    when(decisionRepository.findByScriptNameAndTimestampBetween(anyString(), any(), any()))
        .thenReturn(decisions);

    stubResolvedSession("session-123", 100L, 200L);
  }

  private void setupMockDataWithManyLogs(int count) {
    LocalDateTime now = LocalDateTime.now();
    List<LogEntity> logs = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      LogEntity log = new LogEntity();
      log.setTimestamp(now.minusMinutes(i));
      log.setLevel("INFO");
      log.setMessage("Log " + i);
      log.setSource("TestScript");
      logs.add(log);
    }

    when(logRepository.findByTimestampBetween(any(), any())).thenReturn(logs);

    // Mock empty tick logs and decisions
    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            anyString(), anyLong(), anyLong()))
        .thenReturn(new ArrayList<>());
    when(decisionRepository.findByScriptNameAndTimestampBetween(anyString(), any(), any()))
        .thenReturn(new ArrayList<>());

    stubResolvedSession("session-123", 100L, 200L);
  }

  private void setupMockDataWithManyTickLogs(int count) {
    LocalDateTime now = LocalDateTime.now();
    List<TickLog> tickLogs = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      TickLog tickLog = new TickLog();
      tickLog.setSessionId("session-123");
      tickLog.setTick(100L + i);
      tickLog.setTimestamp(now.minusMinutes(i));
      tickLog.setSubsystem("MOVEMENT");
      tickLog.setSeverity("INFO");
      tickLog.setMessage("Tick log " + i);
      tickLogs.add(tickLog);
    }

    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            anyString(), anyLong(), anyLong()))
        .thenReturn(tickLogs);

    // Mock empty logs and decisions
    when(logRepository.findByTimestampBetween(any(), any())).thenReturn(new ArrayList<>());
    when(decisionRepository.findByScriptNameAndTimestampBetween(anyString(), any(), any()))
        .thenReturn(new ArrayList<>());

    stubResolvedSession("session-123", 100L, 200L);
  }

  private void setupMockDataOnlyLogs() {
    LocalDateTime now = LocalDateTime.now();

    // Mock logs
    List<LogEntity> logs = new ArrayList<>();
    LogEntity log1 = new LogEntity();
    log1.setTimestamp(now.minusMinutes(2));
    log1.setLevel("ERROR");
    log1.setMessage("Error 1");
    log1.setSource("TestScript");
    logs.add(log1);

    LogEntity log2 = new LogEntity();
    log2.setTimestamp(now.minusMinutes(1));
    log2.setLevel("WARN");
    log2.setMessage("Warning 1");
    log2.setSource("TestScript");
    logs.add(log2);

    when(logRepository.findByTimestampBetween(any(), any())).thenReturn(logs);
  }

  private void setupMockDataOnlyTickLogs() {
    LocalDateTime now = LocalDateTime.now();

    // Mock tick logs
    List<TickLog> tickLogs = new ArrayList<>();
    TickLog tickLog = new TickLog();
    tickLog.setSessionId("session-123");
    tickLog.setTick(150L);
    tickLog.setTimestamp(now.minusMinutes(3));
    tickLog.setSubsystem("MOVEMENT");
    tickLog.setSeverity("INFO");
    tickLog.setMessage("Moved to tile");
    tickLogs.add(tickLog);

    when(tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAsc(
            anyString(), anyLong(), anyLong()))
        .thenReturn(tickLogs);

    stubResolvedSession("session-123", 100L, 200L);
  }

  private void setupMockDataOnlyDecisions() {
    LocalDateTime now = LocalDateTime.now();

    // Mock decisions
    List<DecisionEntity> decisions = new ArrayList<>();
    DecisionEntity decision = new DecisionEntity();
    decision.setTimestamp(now.minusMinutes(4));
    decision.setSuccess(true);
    decision.setTargetEntity("Rock");
    decision.setDecisionReason("Mining rock");
    decisions.add(decision);

    when(decisionRepository.findByScriptNameAndTimestampBetween(anyString(), any(), any()))
        .thenReturn(decisions);
  }

  private void stubResolvedSession(String sessionId, long startTick, long endTick) {
    DebugSession session = new DebugSession();
    session.setSessionId(sessionId);
    session.setStartTick(startTick);
    session.setEndTick(endTick);
    when(sessionResolverService.resolveForIteration(any()))
        .thenReturn(
            Optional.of(
                new PatchLoopSessionResolverService.ResolvedSessionContext(
                    session, PatchLoopSessionResolverService.SOURCE_RUN_SESSION)));
  }
}
