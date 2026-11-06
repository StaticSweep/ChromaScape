package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoArtifactRefRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreview;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoFramePreviewRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoIntakeReservationRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobEventRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoOwnerBudgetRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSchedulingRequestRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAdmission;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoFrameIndexService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoJobService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoQuotaService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRegistrationService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoSchedulingService;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
 * Real-PostgreSQL scheduling races: admission is bounded under concurrency, a raced request cannot
 * return another body's winner, coalesced aliases keep changed-body conflict protection, and
 * cancelling a queued preview settles the preview row.
 */
@Testcontainers(disabledWithoutDocker = true)
class MouseVideoSchedulingPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static AnnotationConfigApplicationContext context;
  private static MouseVideoSchedulingService scheduling;
  private static MouseVideoJobService jobService;
  private static String checksum;

  @BeforeAll
  static void initialize() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("41")
        .target("84")
        .load()
        .migrate();
    context = new AnnotationConfigApplicationContext(DatabaseConfig.class);
    scheduling = context.getBean(MouseVideoSchedulingService.class);
    jobService = context.getBean(MouseVideoJobService.class);
    MouseVideoByteStore store = context.getBean(MouseVideoByteStore.class);
    MouseVideoByteStore.StagedArtifact staged =
        store.stage(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 100L);
    store.publish(staged);
    checksum = staged.checksum();
  }

  @AfterAll
  static void close() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void concurrentDistinctAnalysesCannotExceedTheOwnerAdmissionLimit() throws Exception {
    String owner = "owner-admission-" + UUID.randomUUID();
    // One source per contender: analysis identity is (source, config), and the server pins a single
    // cursor-template pack, so distinct sources are what make these four requests distinct work.
    List<UUID> sources =
        java.util.stream.IntStream.range(0, 4).mapToObj(index -> newSource(owner)).toList();
    int contenders = 4;
    var pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch start = new CountDownLatch(1);
    List<Object> outcomes = new CopyOnWriteArrayList<>();
    try {
      for (int i = 0; i < contenders; i++) {
        int index = i;
        pool.submit(
            () -> {
              try {
                start.await(10L, TimeUnit.SECONDS);
                outcomes.add(
                    scheduling.scheduleAnalysis(
                        owner,
                        sources.get(index),
                        UUID.randomUUID().toString(),
                        "visual/v1",
                        null));
              } catch (RuntimeException failure) {
                outcomes.add(failure);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outcomes.add(interrupted);
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60L, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    long admitted =
        outcomes.stream().filter(o -> o instanceof MouseVideoSchedulingService.Receipt).count();
    long quotaRejected =
        outcomes.stream()
            .filter(
                o ->
                    o instanceof MouseVideoException exception
                        && MouseVideoException.CODE_QUOTA_EXCEEDED.equals(exception.getCode()))
            .count();
    // waitingSlots=1 means at most two active/queued jobs for this owner.
    assertThat(admitted).isEqualTo(2L);
    assertThat(quotaRejected).isEqualTo(2L);
    MouseVideoJobRepository jobs = context.getBean(MouseVideoJobRepository.class);
    assertThat(jobs.countByOwnerAndStatusIn(owner, List.of("QUEUED", "RUNNING", "CANCELLING")))
        .isEqualTo(2L);
  }

  /**
   * Deterministic stale-count race: each contender waits at a barrier immediately after its real
   * database count. Without a shared admission authority all four would observe the same count and
   * commit; with the transaction-scoped advisory lock only one contender can be between count and
   * insert, so the barrier times out and every later contender re-counts committed occupancy.
   */
  @Test
  void barrierAfterTheCountCannotOverAdmitDistinctAnalyses() throws Exception {
    String owner = "owner-barrier-" + UUID.randomUUID();
    List<UUID> sources =
        java.util.stream.IntStream.range(0, 4).mapToObj(index -> newSource(owner)).toList();
    int contenders = 4;
    MouseVideoJobRepository realJobs = context.getBean(MouseVideoJobRepository.class);
    CyclicBarrier afterCount = new CyclicBarrier(contenders);
    MouseVideoJobRepository gatedJobs =
        mock(MouseVideoJobRepository.class, AdditionalAnswers.delegatesTo(realJobs));
    doAnswer(
            invocation -> {
              long count =
                  realJobs.countByOwnerAndStatusIn(
                      invocation.getArgument(0), invocation.getArgument(1));
              try {
                afterCount.await(2L, TimeUnit.SECONDS);
              } catch (TimeoutException | BrokenBarrierException serialized) {
                // Expected when admission is serialized: no other contender can reach the count.
              }
              return count;
            })
        .when(gatedJobs)
        .countByOwnerAndStatusIn(anyString(), any());
    MouseVideoSchedulingService gated =
        new MouseVideoSchedulingService(
            context.getBean(MouseVideoProperties.class),
            context.getBean(MouseVideoSourceRepository.class),
            context.getBean(MouseVideoAnalysisRepository.class),
            gatedJobs,
            context.getBean(MouseVideoJobEventRepository.class),
            context.getBean(MouseVideoFramePreviewRepository.class),
            context.getBean(MouseVideoFrameIndexService.class),
            context.getBean(MouseVideoByteStore.class),
            context.getBean(MouseVideoSchedulingRequestRepository.class),
            new ObjectMapper(),
            context.getBean(PlatformTransactionManager.class));
    var pool = Executors.newFixedThreadPool(contenders);
    List<Object> outcomes = new CopyOnWriteArrayList<>();
    try {
      for (int i = 0; i < contenders; i++) {
        int index = i;
        pool.submit(
            () -> {
              try {
                outcomes.add(
                    gated.scheduleAnalysis(
                        owner,
                        sources.get(index),
                        UUID.randomUUID().toString(),
                        "visual/v1",
                        null));
              } catch (RuntimeException failure) {
                outcomes.add(failure);
              }
            });
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(60L, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(outcomes).hasSize(contenders);
    assertThat(
            outcomes.stream().filter(o -> o instanceof MouseVideoSchedulingService.Receipt).count())
        .isEqualTo(2L);
    assertThat(
            realJobs.countByOwnerAndStatusIn(
                owner, List.of("QUEUED", "RUNNING", "CANCELLING", "NEEDS_RECONCILIATION")))
        .isEqualTo(2L);
  }

  @Test
  void importRegistrationAndSchedulingShareOneOwnerAdmissionLimit() {
    String owner = "owner-import-" + UUID.randomUUID();
    UUID sourceId = newSource(owner);
    MouseVideoRegistrationService registration =
        context.getBean(MouseVideoRegistrationService.class);
    MouseVideoAccess.Actor actor = new MouseVideoAccess.Actor(owner, owner);
    scheduling.scheduleAnalysis(owner, sourceId, UUID.randomUUID().toString(), "visual/v1", null);
    registration.registerYouTube(actor, youtubeMetadata(), "dQw4w9WgXcQ");
    assertThatThrownBy(() -> registration.checkUploadAdmission(owner, UUID.randomUUID().toString()))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
    var existing =
        context.getBean(MouseVideoJobRepository.class).findAll().stream()
            .filter(job -> owner.equals(job.getOwner()))
            .findFirst()
            .orElseThrow();
    registration.checkUploadAdmission(owner, existing.getRequestId());

    // waitingSlots=1: one scheduled analysis plus one import fill both slots.
    assertThatThrownBy(() -> registration.registerYouTube(actor, youtubeMetadata(), "9bZkp7q19f0"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
    // A distinct source is distinct work; the same source would coalesce onto the scheduled
    // analysis and legitimately bypass admission.
    UUID saturated = newSource(owner);
    assertThatThrownBy(
            () ->
                scheduling.scheduleAnalysis(
                    owner, saturated, UUID.randomUUID().toString(), "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_QUOTA_EXCEEDED);
    assertThat(
            context
                .getBean(MouseVideoJobRepository.class)
                .countByOwnerAndStatusIn(owner, MouseVideoAdmission.OCCUPYING_STATUSES))
        .isEqualTo(2L);
  }

  @Test
  void unknownLengthIntakeReservesItsMaximumBeforeReading() {
    String owner = "quota-unknown-" + UUID.randomUUID();
    var held = context.getBean(MouseVideoQuotaService.class).reserve(owner, "attempt", 0L);
    assertThat(held.getDeclaredBytes())
        .isEqualTo(context.getBean(MouseVideoProperties.class).getMaxUploadBytes());
  }

  @Test
  void releasedIntakeCanBeReheldWithoutLosingItsAccounting() {
    String owner = "quota-rehold-" + UUID.randomUUID();
    var quota = context.getBean(MouseVideoQuotaService.class);
    var first = quota.reserve(owner, "attempt", 10L);
    quota.release(first.getId());
    var second = quota.reserve(owner, "attempt", 100L);
    assertThat(second.isHeld()).isTrue();
    assertThat(second.getDeclaredBytes()).isEqualTo(100L);
    quota.release(second.getId());
    assertThat(
            context
                .getBean(MouseVideoOwnerBudgetRepository.class)
                .findById(owner)
                .orElseThrow()
                .getReservedBytes())
        .isZero();
  }

  @Test
  void releasedIntakeCannotBeCommitted() {
    String owner = "quota-expired-" + UUID.randomUUID();
    var quota = context.getBean(MouseVideoQuotaService.class);
    var held = quota.reserve(owner, "attempt", 10L);
    quota.release(held.getId());
    assertThatThrownBy(() -> quota.commit(held.getId(), "digest", checksum, 10L))
        .isInstanceOf(MouseVideoException.class);
  }

  @Test
  void firstOwnerConcurrentIntakesAreAtomicAndBounded() throws Exception {
    String owner = "quota-concurrent-" + UUID.randomUUID();
    var quota = context.getBean(MouseVideoQuotaService.class);
    var pool = Executors.newFixedThreadPool(9);
    var start = new CountDownLatch(1);
    var outcomes = new CopyOnWriteArrayList<Object>();
    try {
      for (int i = 0; i < 9; i++)
        pool.submit(
            () -> {
              try {
                start.await();
                outcomes.add(quota.reserve(owner, UUID.randomUUID().toString(), 0L));
              } catch (Exception e) {
                outcomes.add(e);
              }
            });
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
      assertThat(outcomes).hasSize(9);
      assertThat(
              outcomes.stream()
                  .filter(
                      o ->
                          o
                              instanceof
                              com.chromascape.web.debug.tickdebugger.video.entity
                                  .MouseVideoIntakeReservation)
                  .count())
          .isEqualTo(8);
      assertThat(outcomes.stream().filter(o -> o instanceof MouseVideoException).count())
          .isEqualTo(1);
      assertThat(
              context
                  .getBean(MouseVideoOwnerBudgetRepository.class)
                  .findById(owner)
                  .orElseThrow()
                  .getReservedBytes())
          .isEqualTo(context.getBean(MouseVideoProperties.class).getMaxSourceDiskBytes());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void budgetRefreshPreservesAnotherTransactionsReservation() throws Exception {
    String owner = "quota-stale-" + UUID.randomUUID();
    var quota = context.getBean(MouseVideoQuotaService.class);
    var held = quota.reserve(owner, "first", 100L);
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    var pool = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(
          status -> {
            assertThat(
                    context
                        .getBean(MouseVideoOwnerBudgetRepository.class)
                        .findById(owner)
                        .orElseThrow()
                        .getReservedBytes())
                .isEqualTo(100L);
            try {
              pool.submit(() -> quota.reserve(owner, "second", 50L)).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            quota.release(held.getId());
          });
      assertThat(
              context
                  .getBean(MouseVideoOwnerBudgetRepository.class)
                  .findById(owner)
                  .orElseThrow()
                  .getReservedBytes())
          .isEqualTo(50L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void duplicateUploadReleasesItsAttemptWithoutChargingTwice() {
    String owner = "quota-retry-" + UUID.randomUUID();
    var quota = context.getBean(MouseVideoQuotaService.class);
    var registration = context.getBean(MouseVideoRegistrationService.class);
    var actor = new MouseVideoAccess.Actor(owner, owner);
    var metadata = youtubeMetadata();
    var first = quota.reserve(owner, "first", 100L);
    var source = registration.registerUpload(actor, metadata, first.getId(), checksum, 4L);
    var second = quota.reserve(owner, "second", 100L);
    var retry = registration.registerUpload(actor, metadata, second.getId(), checksum, 4L);
    assertThat(retry.source().getId()).isEqualTo(source.source().getId());
    var budget =
        context.getBean(MouseVideoOwnerBudgetRepository.class).findById(owner).orElseThrow();
    assertThat(budget.getReservedBytes()).isZero();
    assertThat(budget.getChargedBytes()).isEqualTo(4L);
    assertThat(
            context
                .getBean(MouseVideoIntakeReservationRepository.class)
                .findById(second.getId())
                .orElseThrow()
                .getState())
        .isEqualTo("RELEASED");
  }

  private static MouseVideoImportMetadata youtubeMetadata() {
    return new MouseVideoImportMetadata(
        UUID.randomUUID().toString(), "UNKNOWN", null, null, null, null);
  }

  @Test
  void coalescedAliasKeepsChangedBodyConflictProtection() {
    String owner = "owner-alias-" + UUID.randomUUID();
    UUID sourceId = newSource(owner);
    MouseVideoSchedulingService.Receipt first =
        scheduling.scheduleAnalysis(owner, sourceId, "req-a", "visual/v1", null);
    // Complete the analysis directly, then coalesce a second request identifier onto it.
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(
            status -> {
              MouseVideoAnalysisRepository analyses =
                  context.getBean(MouseVideoAnalysisRepository.class);
              MouseVideoAnalysis analysis = analyses.findById(first.analysisId()).orElseThrow();
              analysis.complete("ready", "sha256:" + "m".repeat(64));
              analyses.save(analysis);
            });
    MouseVideoSchedulingService.Receipt coalesced =
        scheduling.scheduleAnalysis(owner, sourceId, "req-b", "visual/v1", null);
    assertThat(coalesced.state()).isEqualTo("READY");
    assertThat(coalesced.analysisId()).isEqualTo(first.analysisId());

    // A reused request identifier with a changed target is still a conflict, never a silent alias:
    // the server pins one cursor-template pack, so a distinct source is the reachable body change.
    UUID otherSource = newSource(owner);
    assertThatThrownBy(
            () -> scheduling.scheduleAnalysis(owner, otherSource, "req-b", "visual/v1", null))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_CONFLICT);
    // A pack the server does not serve is refused before any analysis identity is frozen.
    assertThatThrownBy(
            () -> scheduling.scheduleAnalysis(owner, sourceId, "req-c", "visual/v1", "pack-y"))
        .isInstanceOf(MouseVideoException.class)
        .extracting(error -> ((MouseVideoException) error).getCode())
        .isEqualTo(MouseVideoException.CODE_INVALID_REQUEST);
  }

  @Test
  void cancellingAQueuedPreviewSettlesThePreviewRow() {
    String owner = "owner-preview-" + UUID.randomUUID();
    UUID sourceId = newSource(owner);
    MouseVideoSchedulingService.Receipt analysis =
        scheduling.scheduleAnalysis(
            owner, sourceId, UUID.randomUUID().toString(), "visual/v1", null);
    MouseVideoSchedulingService.Receipt preview =
        scheduling.schedulePreview(
            owner, sourceId, analysis.analysisId(), UUID.randomUUID().toString(), 0L, 640, 360);
    assertThat(preview.state()).isEqualTo("PENDING");

    jobService.requestCancellation(preview.jobId(), owner, 0L);

    MouseVideoFramePreview settled =
        context
            .getBean(MouseVideoFramePreviewRepository.class)
            .findById(preview.previewId())
            .orElseThrow();
    assertThat(settled.getState()).isEqualTo(MouseVideoFramePreview.STATE_CANCELLED);
    assertThat(jobService.find(preview.jobId()).orElseThrow().getStatus())
        .isEqualTo(MouseVideoJob.STATUS_CANCELLED);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void publicationCommitAndCollectionSerializeAcrossRepositoryTransactions(boolean filesystemGuard)
      throws Exception {
    String owner = "owner-gc-" + UUID.randomUUID();
    UUID sourceId = newSource(owner);
    MouseVideoByteStore store = context.getBean(MouseVideoByteStore.class);
    var artifactRepository = context.getBean(MouseVideoArtifactRefRepository.class);
    var previewRepository = context.getBean(MouseVideoFramePreviewRepository.class);
    var properties = context.getBean(MouseVideoProperties.class);
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    byte[] content =
        ("gc-commit-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var old = store.stage(new ByteArrayInputStream(content), 1000);
    Files.setLastModifiedTime(
        store.publish(old), java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
    var replacement = store.stage(new ByteArrayInputStream(content), 1000);
    var inserted = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var publisher =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        try (var publication = filesystemGuard ? store.publicationLease() : null) {
                          artifactRepository.lockPublication();
                          try {
                            if (filesystemGuard) store.publish(replacement);
                            else store.discard(replacement);
                          } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                          }
                          var reference =
                              new com.chromascape.web.debug.tickdebugger.video.entity
                                  .MouseVideoArtifactRef(
                                  owner,
                                  sourceId,
                                  null,
                                  com.chromascape.web.debug.tickdebugger.video.entity
                                      .MouseVideoArtifactRef.ROLE_MANIFEST,
                                  old.checksum(),
                                  content.length,
                                  "application/json");
                          reference.finalizeRow();
                          artifactRepository.saveAndFlush(reference);
                          inserted.countDown();
                          try {
                            if (!commit.await(10, TimeUnit.SECONDS))
                              throw new AssertionError("commit barrier timed out");
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                          }
                        }
                      }));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
      var collector =
          pool.submit(
              () ->
                  tx.execute(
                      status ->
                          new com.chromascape.web.debug.tickdebugger.video.service
                                  .MouseVideoOrphanSweeper(
                                  properties, artifactRepository, previewRepository, store)
                              .sweepOnce(java.time.Instant.EPOCH.plusSeconds(1), false)));
      Thread.sleep(200);
      assertThat(collector.isDone()).isFalse();
      commit.countDown();
      publisher.get(15, TimeUnit.SECONDS);
      collector.get(15, TimeUnit.SECONDS);
      assertThat(store.verify(old.checksum())).isTrue();
    } finally {
      commit.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void frozenTemplateIsRetainedByTheRealAnalysisReferenceQuery() throws Exception {
    var properties = context.getBean(MouseVideoProperties.class);
    String previous = properties.getVisualCursorTemplate();
    var store = context.getBean(MouseVideoByteStore.class);
    Path template = properties.rootPath().resolve("gc-template.png");
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB),
        "png",
        template.toFile());
    try {
      properties.setVisualCursorTemplate(template.toString());
      String owner = "owner-template-gc-" + UUID.randomUUID();
      UUID sourceId = newSource(owner);
      var receipt =
          scheduling.scheduleAnalysis(
              owner, sourceId, UUID.randomUUID().toString(), "visual/v1", null);
      var analysis =
          context
              .getBean(MouseVideoAnalysisRepository.class)
              .findById(receipt.analysisId())
              .orElseThrow();
      var artifacts = context.getBean(MouseVideoArtifactRefRepository.class);
      assertThat(artifacts.findDistinctTemplateChecksums())
          .contains(analysis.getTemplateChecksum());
      Files.setLastModifiedTime(
          store.locate(analysis.getTemplateChecksum()).orElseThrow(),
          java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
      new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
          .executeWithoutResult(
              status ->
                  new com.chromascape.web.debug.tickdebugger.video.service.MouseVideoOrphanSweeper(
                          properties,
                          artifacts,
                          context.getBean(MouseVideoFramePreviewRepository.class),
                          store)
                      .sweepOnce(java.time.Instant.EPOCH.plusSeconds(1), false));
      assertThat(store.verify(analysis.getTemplateChecksum())).isTrue();
    } finally {
      properties.setVisualCursorTemplate(previous);
    }
  }

  @Test
  void publicationFenceWaitHasAnActualPostgresQueryTimeout() throws Exception {
    try (var holder = context.getBean(DataSource.class).getConnection()) {
      holder.setAutoCommit(false);
      try {
        holder
            .createStatement()
            .execute("SELECT pg_advisory_xact_lock(hashtextextended('mouse-video-publication',0))");
        long started = System.nanoTime();
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                () ->
                    tx.executeWithoutResult(
                        status ->
                            context
                                .getBean(MouseVideoArtifactRefRepository.class)
                                .lockPublication()))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
            .isBetween(8L, 20L);
      } finally {
        holder.rollback();
      }
    }
  }

  private UUID newSource(String owner) {
    MouseVideoSource source = new MouseVideoSource(owner, MouseVideoSource.KIND_UPLOAD);
    source.bindOriginal(checksum, 4L, "video/mp4");
    source.qualifyOriginal("video/mp4");
    return context.getBean(MouseVideoSourceRepository.class).save(source).getId();
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
      factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    MouseVideoProperties properties() throws Exception {
      MouseVideoProperties properties = new MouseVideoProperties();
      properties.setEnabled(true);
      properties.setDirectory(Files.createTempDirectory("sc2150-scheduling").toString());
      properties.setWaitingSlots(1);
      return properties;
    }

    @Bean
    MouseVideoByteStore byteStore(MouseVideoProperties properties) throws Exception {
      return new MouseVideoByteStore(properties);
    }

    @Bean
    MouseVideoFrameIndexService frameIndex() {
      MouseVideoFrameIndexService index = mock(MouseVideoFrameIndexService.class);
      when(index.requireIndex(any(), any(), any()))
          .thenReturn(
              new MouseVideoFrameIndexService.FrozenFrameIndex(
                  "sha256:" + "i".repeat(64),
                  "frame-index/v2",
                  true,
                  "1/10",
                  "1",
                  "10",
                  List.of(new MouseVideoFrameIndexService.Frame("0", "0.0", "0.1", true))));
      return index;
    }

    @Bean
    MouseVideoSchedulingService scheduling(
        MouseVideoProperties properties,
        MouseVideoSourceRepository sources,
        MouseVideoAnalysisRepository analyses,
        MouseVideoJobRepository jobs,
        MouseVideoJobEventRepository events,
        MouseVideoFramePreviewRepository previews,
        MouseVideoFrameIndexService frameIndex,
        MouseVideoByteStore byteStore,
        MouseVideoSchedulingRequestRepository requests,
        PlatformTransactionManager transactionManager) {
      return new MouseVideoSchedulingService(
          properties,
          sources,
          analyses,
          jobs,
          events,
          previews,
          frameIndex,
          byteStore,
          requests,
          new ObjectMapper(),
          transactionManager);
    }

    @Bean
    MouseVideoQuotaService quota(
        MouseVideoProperties properties,
        MouseVideoOwnerBudgetRepository budgets,
        MouseVideoIntakeReservationRepository reservations,
        EntityManagerFactory factory) {
      return new MouseVideoQuotaService(
          properties,
          budgets,
          reservations,
          org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(
              factory));
    }

    @Bean
    MouseVideoRegistrationService registration(
        MouseVideoSourceRepository sources,
        MouseVideoJobRepository jobs,
        MouseVideoArtifactRefRepository artifacts,
        MouseVideoJobEventRepository events,
        MouseVideoQuotaService quota,
        MouseVideoProperties properties) {
      return new MouseVideoRegistrationService(
          sources, jobs, artifacts, events, quota, properties, new ObjectMapper());
    }

    @Bean
    MouseVideoJobService jobService(
        MouseVideoJobRepository jobs,
        MouseVideoJobEventRepository events,
        MouseVideoFramePreviewRepository previews) {
      return new MouseVideoJobService(jobs, events, previews, new ObjectMapper());
    }
  }
}
