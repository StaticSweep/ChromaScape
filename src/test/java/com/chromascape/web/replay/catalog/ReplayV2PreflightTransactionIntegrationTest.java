package com.chromascape.web.replay.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.schema.AiPlayerSchemaTarget;
import com.chromascape.web.debug.tickdebugger.entity.*;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.conversion.*;
import com.chromascape.web.replay.curation.entity.*;
import com.chromascape.web.replay.curation.repository.*;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real PostgreSQL transactions and Spring interceptors; the expected refusal must return data. */
class ReplayV2PreflightTransactionIntegrationTest {
  DataSourceTransactionManager manager;
  JdbcTemplate jdbc;
  ReplayRecordingResolver resolver;
  RecordingIntegrityService integrity;
  TickSnapshotRepository snapshots;
  ReplayActionConversionService conversion;
  ScenarioManifestRepository manifests;
  RecordingCurationRepository curations;
  CuratedActionRepository actions;
  ReplayV2PreflightService preflight;

  @BeforeEach
  void configure() {
    String url = System.getenv("SC665_TEST_JDBC_URL");
    AiPlayerSchemaTarget.validate(url, Set.of(AiPlayerSchemaTarget.PROFILE));
    var source = new DriverManagerDataSource(url, "sc665", "sc665-disposable-test");
    manager = new DataSourceTransactionManager(source);
    jdbc = new JdbcTemplate(source);
    resolver = mock(ReplayRecordingResolver.class);
    integrity = mock(RecordingIntegrityService.class);
    snapshots = mock(TickSnapshotRepository.class);
    conversion = mock(ReplayActionConversionService.class);
    manifests = mock(ScenarioManifestRepository.class);
    curations = mock(RecordingCurationRepository.class);
    actions = mock(CuratedActionRepository.class);
    var session =
        new DebugSession(
            "session",
            "authored-account",
            "authored-test",
            LocalDateTime.of(2026, 10, 31, 0, 0),
            1L);
    session.setEndTime(LocalDateTime.of(2026, 10, 31, 0, 1));
    var resolved =
        new ReplayRecordingResolver.ResolvedRecording(mock(RecordingSession.class), session);
    when(resolver.resolve("authored-recording")).thenReturn(resolved);
    when(integrity.evaluate(resolved))
        .thenReturn(new ReplayEligibility(true, false, false, List.of()));
    var curation = new RecordingCuration();
    curation.setRecordingId("authored-recording");
    curation.setDebugSessionId("session");
    curation.setCreatedBy("reviewer");
    curation.setRole(CurationRole.HELD_OUT);
    curation.setState(CurationState.PUBLISHED);
    curation.setVersion(1);
    var content = new ObjectMapper().createObjectNode();
    content.putObject("tickRange").put("start", 1).put("end", 2);
    content.putObject("initialStateOverrides");
    content.putArray("actionLabels");
    curation.setContentJson(content);
    curation.setContentChecksum(
        new CanonicalProjectionService()
            .sha256Hex(new CanonicalProjectionService().canonicalJsonBytes(content)));
    when(curations.findByRecordingIdAndCreatedByOrderByVersionDesc(
            "authored-recording", "reviewer"))
        .thenReturn(List.of(curation));
    var capability = new ReplayCapabilityService(); // truthful unavailable state
    var compiler =
        proxied(
            new ScenarioManifestV2Compiler(
                resolver,
                snapshots,
                conversion,
                curations,
                actions,
                manifests,
                capability,
                new CanonicalProjectionService(),
                new ObjectMapper()));
    preflight =
        proxied(
            new ReplayV2PreflightService(
                resolver, integrity, snapshots, conversion, capability, compiler));
  }

  @SuppressWarnings("unchecked")
  <T> T proxied(T target) {
    var interceptor =
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource());
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(interceptor);
    return (T) proxy.getProxy();
  }

  @Test
  void unavailableRuntimeReturnsBlockedWithoutPoisoningReadOnlyTransaction() {
    var result =
        assertDoesNotThrow(
            () ->
                preflight.preflight(
                    "authored-recording", ConversionMode.CAPTURED_ONLY, null, "reviewer"));
    assertEquals("BLOCKED", result.preflightState());
    assertFalse(result.simulationEligible());
    assertFalse(result.certificationEligible());
    assertEquals("XRSPS_CAPABILITY_UNAVAILABLE", result.blockingReasons().get(0).code().name());
    verifyNoInteractions(snapshots, conversion, manifests, actions);
    verify(curations, never()).save(any(RecordingCuration.class));
    assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void unexpectedDatabaseFailuresStillPropagateAndRollback() {
    when(resolver.resolve(anyString()))
        .thenThrow(new DataAccessResourceFailureException("authored database failure"));
    assertThrows(
        DataAccessResourceFailureException.class,
        () ->
            preflight.preflight(
                "authored-recording", ConversionMode.CAPTURED_ONLY, null, "reviewer"));
    verifyNoInteractions(snapshots, conversion, manifests, actions);
    verify(curations, never()).save(any(RecordingCuration.class));
    assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
  }
}
