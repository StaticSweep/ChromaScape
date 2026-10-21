package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
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
class MouseVideoServicePostgresRaceTest {

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
        .target("46")
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

  // Deliberately not a @Configuration/@TestConfiguration stereotype: it is registered explicitly
  // below, and a stereotype would be component-scanned into full application test contexts.
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
        MouseVideoJobRepository jobs,
        MouseVideoJobEventRepository events,
        MouseVideoFramePreviewRepository previews) {
      return new MouseVideoJobService(jobs, events, previews, new ObjectMapper());
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
