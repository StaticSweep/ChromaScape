package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoFinalizationService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual service transactions overlap at a PostgreSQL-confirmed row-lock wait, not a timed guess.
 */
@Testcontainers(disabledWithoutDocker = true)
class MouseVideoIndependentReviewProbeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static AnnotationConfigApplicationContext context;
  private MouseVideoJobRepository jobs;
  private MouseVideoJobService service;
  private MouseVideoFinalizationService finalization;
  private MouseVideoJob claim;

  @BeforeAll
  static void initialize() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("41")
        .target("43")
        .load()
        .migrate();
    context = new AnnotationConfigApplicationContext(DatabaseConfig.class);
  }

  @AfterAll
  static void close() {
    if (context != null) {
      context.close();
    }
  }

  @BeforeEach
  void createClaim() {
    jobs = context.getBean(MouseVideoJobRepository.class);
    service = context.getBean(MouseVideoJobService.class);
    finalization = context.getBean(MouseVideoFinalizationService.class);
    MouseVideoSource source =
        context
            .getBean(MouseVideoSourceRepository.class)
            .save(new MouseVideoSource("owner", MouseVideoSource.KIND_UPLOAD));
    MouseVideoJob queued =
        service.create(
            source.getId(),
            "owner",
            MouseVideoJob.KIND_IMPORT,
            UUID.randomUUID().toString(),
            "digest");
    claim = service.claim(queued.getId(), "worker", 120L).job();
  }

  @Test
  void heartbeatAndProgressSerializeWithoutAHealthyJobFailure() throws Exception {
    assertThat(contend(this::heartbeat, this::progress)).isNull();
    MouseVideoJob current = current();
    assertThat(current.getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(current.getProgress()).isEqualTo(50);
    assertThat(current.getLeaseOwner()).isEqualTo("worker");
    assertThat(service.leaseValid(current.getId())).isTrue();
  }

  @Test
  void progressAndHeartbeatSerializeInTheOppositeOrder() throws Exception {
    assertThat(contend(this::progress, this::heartbeat)).isNull();
    assertThat(current().getProgress()).isEqualTo(50);
    assertThat(service.leaseValid(claim.getId())).isTrue();
  }

  @Test
  void heartbeatAndFinalizationCommitOneSuccessfulAnalysis() throws Exception {
    assertThat(contend(this::heartbeat, this::finalizeClaim)).isNull();
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_SUCCEEDED);
    assertThat(analyses()).hasSize(1);
  }

  @Test
  void cancellationWinningTheLockFencesFinalization() throws Exception {
    assertThat(
            contend(
                () -> service.requestCancellation(claim.getId(), "owner", 0L), this::finalizeClaim))
        .isInstanceOf(MouseVideoException.class);
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(analyses()).isEmpty();
  }

  @Test
  void replacementWinningTheLockRefusesStaleWorkerReconciliation() throws Exception {
    assertThat(
            contend(
                this::replaceClaim,
                () ->
                    service.reconcileExecution(
                        claim.getId(),
                        claim.getGeneration(),
                        claim.getCancellationRevision(),
                        "worker",
                        "stale cleanup")))
        .isInstanceOf(MouseVideoException.class);
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(current().getLeaseOwner()).isEqualTo("replacement");
    assertThat(current().getCleanupReason()).isNull();
  }

  @Test
  void replacementCancellationCannotBeSettledByTheOldExecution() throws Exception {
    assertThat(
            contend(
                () -> {
                  replaceClaim();
                  service.requestCancellation(claim.getId(), "owner", 0L);
                },
                () ->
                    service.confirmExecutionCancelled(
                        claim.getId(),
                        claim.getGeneration(),
                        claim.getCancellationRevision(),
                        "worker")))
        .isInstanceOf(MouseVideoException.class);
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
    assertThat(current().getLeaseOwner()).isEqualTo("replacement");
  }

  @Test
  void expiredClaimReconciliationFencesTheWaitingHeartbeat() throws Exception {
    assertThat(
            contend(
                () -> {
                  expireClaim();
                  service.reconcileExpiredClaims();
                },
                this::heartbeat))
        .isInstanceOf(MouseVideoException.class);
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_NEEDS_RECONCILIATION);
    assertThat(service.leaseValid(claim.getId())).isFalse();
  }

  @Test
  void startupWaitingOnAHeartbeatPreservesHealthyOwnership() throws Exception {
    assertThat(contend(this::heartbeat, () -> service.reconcileOnStartup())).isNull();
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(current().getGeneration()).isEqualTo(claim.getGeneration());
    assertThat(current().getLeaseOwner()).isEqualTo("worker");
  }

  @Test
  void expiryReconciliationWaitingOnAHeartbeatPreservesHealthyOwnership() throws Exception {
    assertThat(contend(this::heartbeat, () -> service.reconcileExpiredClaims())).isNull();
    assertThat(current().getStatus()).isEqualTo(MouseVideoJob.STATUS_RUNNING);
    assertThat(current().getGeneration()).isEqualTo(claim.getGeneration());
    assertThat(current().getLeaseOwner()).isEqualTo("worker");
  }


  @Test
  void retainedRequestContextAcceptsWorkerSettlementBeforeSecondLock() throws Exception {
    var factory = context.getBean(EntityManagerFactory.class);
    var requestEntityManager = factory.createEntityManager();
    var pool = Executors.newSingleThreadExecutor();
    org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
        factory, new org.springframework.orm.jpa.EntityManagerHolder(requestEntityManager));
    try {
      var requestCopy = service.requestCancellation(claim.getId(), "owner", 0L);
      assertThat(requestEntityManager.contains(requestCopy)).isTrue();
      assertThat(requestCopy.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
      pool.submit(() -> service.confirmExecutionCancelled(
          claim.getId(), claim.getGeneration(), claim.getCancellationRevision(), "worker"))
          .get(10L, TimeUnit.SECONDS);
      // The worker committed independently; the request still retains its pre-commit copy.
      assertThat(requestCopy.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
      var settled = service.confirmCancelled(claim.getId(), "owner");
      assertThat(settled.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLED);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(factory);
      requestEntityManager.close();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void metadataFilesystemReadFailureRemainsAnInfrastructureFailure() throws Exception {
    Class<?> readerClass = Class.forName(
        "com.chromascape.web.debug.tickdebugger.video.MouseVideoMultipartReader$Reader");
    var constructor = readerClass.getDeclaredConstructor(
        java.nio.file.Path.class, ObjectMapper.class, long.class, long.class);
    constructor.setAccessible(true);
    var reader = constructor.newInstance(java.nio.file.Path.of("/tmp"), new ObjectMapper(), 1024L, 2048L);
    var parse = readerClass.getDeclaredMethod("parseMetadata", java.nio.file.Path.class);
    parse.setAccessible(true);
    Throwable failure = null;
    try {
      parse.invoke(reader, java.nio.file.Path.of("/tmp/sc2150-absent-" + UUID.randomUUID()));
    } catch (java.lang.reflect.InvocationTargetException wrapped) {
      failure = wrapped.getCause();
    }
    assertThat(failure).isInstanceOf(java.nio.file.NoSuchFileException.class);
  }

  @Test
  void existingPersistedFailureDoesNotExposeItsPrivatePath() {
    service.fail(claim.getId(), claim.getGeneration(), MouseVideoException.CODE_DECODER_FAILED,
        "ffprobe failed: /tmp/sc2150-media/bytes/private-file", false);
    var persisted = service.requireOwned(claim.getId(), "owner");
    assertThat(service.view(persisted).errorMessage()).doesNotContain("/tmp/sc2150-media");
  }


  @Test
  void controllerCancellationAcceptsWorkerSettlementWithRetainedRequestContext() throws Exception {
    var factory = context.getBean(EntityManagerFactory.class);
    var requestEntityManager = factory.createEntityManager();
    var pool = Executors.newSingleThreadExecutor();
    var registry = org.mockito.Mockito.mock(
        com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWorkerRegistry.class);
    org.mockito.Mockito.when(registry.requestCancel(claim.getId())).thenReturn(true);
    org.mockito.Mockito.when(registry.awaitCleanup(org.mockito.ArgumentMatchers.eq(claim.getId()),
        org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
      var cached = requestEntityManager.find(MouseVideoJob.class, claim.getId());
      assertThat(cached.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
      pool.submit(() -> service.confirmExecutionCancelled(claim.getId(), claim.getGeneration(),
          claim.getCancellationRevision(), "worker")).get(10L, TimeUnit.SECONDS);
      assertThat(cached.getStatus()).isEqualTo(MouseVideoJob.STATUS_CANCELLING);
      return true;
    });
    var controller = new com.chromascape.web.debug.tickdebugger.video.controller.MouseVideoController(
        new com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess(),
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoImportService.class),
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoCatalogService.class),
        service,
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoExportService.class),
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewService.class),
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRequestGuard.class),
        org.mockito.Mockito.mock(com.chromascape.web.debug.tickdebugger.video.service.MouseVideoWriteNonceService.class),
        new com.chromascape.web.debug.tickdebugger.video.service.MouseVideoTelemetry(),
        registry, new ObjectMapper());
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
    org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
        factory, new org.springframework.orm.jpa.EntityManagerHolder(requestEntityManager));
    try {
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
          "/api/debug/mouse-videos/jobs/{id}/cancel", claim.getId())
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content("{\"expectedRevision\":0}").principal(() -> "owner"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("CANCELLED"));
      org.mockito.Mockito.verify(registry).awaitCleanup(claim.getId(), 30000L);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(factory);
      requestEntityManager.close();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10L, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void heartbeat() {
    service.heartbeat(claim.getId(), claim.getGeneration(), "worker", 120L);
  }

  private void progress() {
    service.progress(
        claim.getId(), claim.getGeneration(), "worker", MouseVideoJob.STAGE_PUBLISH, 50);
  }

  private void finalizeClaim() {
    MouseVideoAnalysis analysis =
        new MouseVideoAnalysis(
            claim.getSourceId(),
            "owner",
            "sha256:original",
            "probe/v1",
            null,
            "config",
            "toolchain");
    analysis.complete("probe-only", null);
    finalization.finalizeSuccess(
        claim.getId(), claim.getGeneration(), "worker", analysis, null, List.of());
  }

  private void expireClaim() {
    MouseVideoJob current = jobs.findForUpdate(claim.getId()).orElseThrow();
    current.setLeaseExpiresAt(Instant.EPOCH);
    jobs.saveAndFlush(current);
  }

  private void replaceClaim() {
    expireClaim();
    assertThat(service.claim(claim.getId(), "replacement", 120L).acquired()).isTrue();
  }

  private MouseVideoJob current() {
    return service.find(claim.getId()).orElseThrow();
  }

  private List<MouseVideoAnalysis> analyses() {
    return context
        .getBean(MouseVideoAnalysisRepository.class)
        .findBySourceIdAndOwnerOrderByCreatedAtDesc(claim.getSourceId(), "owner");
  }

  private Throwable contend(Runnable first, Runnable second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    String application = "sc2150-" + UUID.randomUUID();
    var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    try {
      var holder =
          pool.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        jobs.findForUpdate(claim.getId()).orElseThrow();
                        first.run();
                        locked.countDown();
                        try {
                          assertThat(release.await(15L, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                      }));
      assertThat(locked.await(15L, TimeUnit.SECONDS)).isTrue();
      var waiter =
          pool.submit(
              () -> {
                try {
                  transaction.executeWithoutResult(
                      status -> {
                        jdbc.execute("SET LOCAL application_name = '" + application + "'");
                        second.run();
                      });
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(20))
          .until(
              () ->
                  jdbc.queryForObject(
                          "SELECT count(*) FROM pg_stat_activity WHERE application_name = ?"
                              + " AND wait_event_type = 'Lock'",
                          Long.class,
                          application)
                      > 0);
      release.countDown();
      holder.get(10L, TimeUnit.SECONDS);
      return waiter.get(10L, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10L, TimeUnit.SECONDS)).isTrue();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = MouseVideoJobRepository.class)
  static class DatabaseConfig {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan(MouseVideoJob.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    MouseVideoJobService jobService(
        MouseVideoJobRepository jobs, MouseVideoJobEventRepository events) {
      return new MouseVideoJobService(jobs, events, new ObjectMapper());
    }

    @Bean
    MouseVideoFinalizationService finalization(
        MouseVideoJobRepository jobs,
        MouseVideoJobEventRepository events,
        MouseVideoAnalysisRepository analyses,
        MouseVideoArtifactRefRepository artifacts,
        MouseVideoSourceRepository sources) {
      return new MouseVideoFinalizationService(
          jobs, events, analyses, artifacts, sources, new ObjectMapper());
    }
  }
}
