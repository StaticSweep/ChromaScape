package com.chromascape.web.debug.tickdebugger.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysis;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoAnalysisRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoReviewRevisionRepository;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSource;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoSourceRepository;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoCoordinatePlane;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoFrameIndexService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoPreviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewService;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewSessionService;
import com.chromascape.web.debug.tickdebugger.video.store.MouseVideoByteStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

/** Actual database protocol, migration and immutable retry regressions; no skipped acceptance. */
@Testcontainers(disabledWithoutDocker = true)
class MouseVideoReviewSessionPostgresTest {
  @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
  static AnnotationConfigApplicationContext context;
  static final ObjectMapper M = new ObjectMapper();
  MouseVideoReviewSessionService sessions;
  MouseVideoProperties properties;
  MouseVideoSource source;
  MouseVideoAnalysis analysis;
  JdbcTemplate db;
  Path manifest;
  String checksum = "sha256:" + "b".repeat(64);

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("41")
        .target("46")
        .load()
        .migrate();
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    UUID source = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "insert into mouse_video_sources(id,owner,source_kind) values(?,'legacy','UPLOAD')",
        source);
    jdbc.update(
        "insert into mouse_video_review_revisions(id,source_id,actor,reason,corrections_json) values(?,?,'legacy','old immutable','[]'::jsonb)",
        revision,
        source);
    String before =
        jdbc.queryForObject(
            "select row_to_json(r)::text from mouse_video_review_revisions r where id=?",
            String.class,
            revision);
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("41")
        .target("48")
        .load()
        .migrate();
    assertThat(
            jdbc.queryForObject(
                "select corrections_json::text from mouse_video_review_revisions where id=?",
                String.class,
                revision))
        .isEqualTo("[]");
    assertThat(
            jdbc.queryForObject(
                "select provenance_json is null and review_session_id is null and contract_version='corrections/v0' from mouse_video_review_revisions where id=?",
                Boolean.class,
                revision))
        .isTrue();
    assertThat(before).contains("old immutable");
    context = new AnnotationConfigApplicationContext(Config.class);
  }

  @AfterAll
  static void close() {
    if (context != null) context.close();
  }

  @BeforeEach
  void setup() throws Exception {
    sessions = context.getBean(MouseVideoReviewSessionService.class);
    properties = context.getBean(MouseVideoProperties.class);
    db = context.getBean(JdbcTemplate.class);
    source =
        context
            .getBean(MouseVideoSourceRepository.class)
            .saveAndFlush(new MouseVideoSource("owner", "UPLOAD"));
    source.bindOriginal(
        "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2), 123, "video/mp4");
    source = context.getBean(MouseVideoSourceRepository.class).saveAndFlush(source);
    analysis =
        new MouseVideoAnalysis(
            source.getId(),
            "owner",
            source.getOriginalChecksum(),
            "probe/v1",
            null,
            "config",
            "tools");
    analysis.complete("complete", "manifest");
    analysis = context.getBean(MouseVideoAnalysisRepository.class).saveAndFlush(analysis);
    var frames = new ArrayList<MouseVideoFrameIndexService.Frame>();
    for (int i = 0; i < 30; i++)
      frames.add(new MouseVideoFrameIndexService.Frame("" + i, "" + (i / 30.0), "1", i == 0));
    var index =
        new MouseVideoFrameIndexService.FrozenFrameIndex(
            checksum, "mouse-video-frame-index/v1", true, "1/30", "1", "30", List.copyOf(frames));
    when(context
            .getBean(MouseVideoFrameIndexService.class)
            .requireIndex("owner", source.getId(), analysis.getId()))
        .thenReturn(index);
    when(context
            .getBean(MouseVideoFrameIndexService.class)
            .bounds("owner", source.getId(), analysis.getId()))
        .thenReturn(new MouseVideoCorrectionContract.Bounds(30, 160, 96));
    manifest = Files.createTempFile("sc2150-policy-", ".json");
    writeManifest("heldout");
    properties.setCorpusManifest(manifest.toString());
  }

  @AfterEach
  void cleanup() throws Exception {
    Files.deleteIfExists(manifest);
    properties.setCorpusManifest("");
  }

  void writeManifest(String split) throws Exception {
    Files.writeString(
        manifest,
        M.writeValueAsString(
            Map.of(
                "schema",
                "review-corpus/v1",
                "sources",
                List.of(
                    Map.of(
                        "originalDigest",
                        source.getOriginalChecksum(),
                        "originGroupId",
                        source.getId().toString(),
                        "split",
                        split)))));
  }

  ObjectNode createBody() {
    String policy =
        (String)
            sessions.policy("owner", "actor", source.getId(), analysis.getId()).get("policyDigest");
    return M.createObjectNode()
        .put("requestId", UUID.randomUUID().toString())
        .put("analysisId", analysis.getId().toString())
        .put("frameIndexChecksum", checksum)
        .put("mode", "WITHOUT_SUGGESTIONS")
        .put("policyDigest", policy)
        .put("startOrdinal", 0)
        .put("endOrdinal", 29)
        .put("annotationAttestation", true);
  }

  UUID open() {
    return UUID.fromString(
        (String) sessions.create("owner", "actor", source.getId(), createBody()).get("id"));
  }

  ObjectNode snapshot(int count, int version) {
    ObjectNode body =
        M.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("analysisId", analysis.getId().toString())
            .put("expectedBaseRevision", 0)
            .put("clientEditVersion", version)
            .put("reason", "synthetic protocol test")
            .put("sourceUse", "INSPECTION_ONLY");
    var corrections = body.putArray("corrections");
    var origins = body.putObject("origins");
    for (int i = 0; i < count; i++) {
      corrections
          .addObject()
          .put("kind", "CURSOR_VISIBILITY")
          .put("frameOrdinal", i)
          .put("visibility", "HIDDEN")
          .put("unknownReason", "synthetic absent");
      origins.put("" + i, "MANUAL_HIDDEN");
    }
    return M.createObjectNode().put("snapshotJson", body.toString());
  }

  @Test
  void identicalRetriesAreReportedAsReuseRatherThanCreation() {
    var body = createBody();
    var first = sessions.createResult("owner", "actor", source.getId(), body);
    var second = sessions.createResult("owner", "actor", source.getId(), body);
    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));
    UUID id = UUID.fromString((String) first.body().get("id"));
    var request = snapshot(1, 1);
    assertThat(sessions.submitResult("owner", "actor", id, request).created()).isTrue();
    assertThat(sessions.submitResult("owner", "actor", id, request).created()).isFalse();
  }

  @Test
  void identicalRetryPreservesPinnedPlaneAndImmutableContext() throws Exception {
    Path facts = Files.createTempFile("sc2150-retry-plane-", ".json");
    try {
      Files.writeString(facts, "{\"width\":1920,\"height\":1080,\"rotationDegrees\":0}");
      when(context.getBean(MouseVideoByteStore.class).locate("manifest"))
          .thenReturn(Optional.of(facts));
      var body = createBody();
      var first = sessions.createResult("owner", "actor", source.getId(), body);
      var retry = sessions.createResult("owner", "actor", source.getId(), body);
      assertThat(first.created()).isTrue();
      assertThat(retry.created()).isFalse();
      // A lost creation response followed by an identical retry must be indistinguishable
      // from the original acknowledgement, down to the coordinate-plane document.
      assertThat(retry.body()).isEqualTo(first.body());
      assertThat(retry.body().get("coordinatePlane")).isNotNull();
      assertThat(retry.body().get("coordinatePlaneDigest")).asString().startsWith("sha256:");
      for (String key :
          List.of(
              "id",
              "sourceId",
              "analysisId",
              "originalDigest",
              "frameIndexChecksum",
              "originGroupId",
              "policyDigest",
              "split",
              "mode",
              "startOrdinal",
              "endOrdinal",
              "frameCount",
              "coordinatePlane",
              "coordinatePlaneDigest")) {
        assertThat(retry.body().get(key)).as(key).isEqualTo(first.body().get(key));
      }
      UUID id = UUID.fromString((String) first.body().get("id"));
      assertThat(
              db.queryForObject(
                  "select count(*) from mouse_video_review_sessions where request_id=?",
                  Long.class,
                  body.path("requestId").asText()))
          .isEqualTo(1L);
      assertThat(
              db.queryForObject(
                  "select latest_revision_id from mouse_video_review_sessions where id=?",
                  Object.class,
                  id))
          .isNull();
      // Resume the same session after the retry and keep the pinned plane visible.
      sessions.submit("owner", "actor", id, snapshot(30, 1));
      var resumed = sessions.get("owner", "actor", id);
      assertThat(resumed.get("coordinatePlane")).isEqualTo(first.body().get("coordinatePlane"));
      assertThat(resumed.get("coordinatePlaneDigest"))
          .isEqualTo(first.body().get("coordinatePlaneDigest"));
    } finally {
      Files.deleteIfExists(facts);
    }
  }

  @Test
  void coordinatePlaneIsPinnedAndAChangedPlaneIsRefused() throws Exception {
    Path facts = Files.createTempFile("sc2150-probe-facts-", ".json");
    try {
      Files.writeString(facts, "{\"width\":1920,\"height\":1080,\"rotationDegrees\":0}");
      when(context.getBean(MouseVideoByteStore.class).locate("manifest"))
          .thenReturn(Optional.of(facts));
      var created = sessions.create("owner", "actor", source.getId(), createBody());
      assertThat(created.get("coordinatePlane")).isNotNull();
      assertThat(created.get("coordinatePlaneDigest")).asString().startsWith("sha256:");
      JsonNode plane = (JsonNode) created.get("coordinatePlane");
      assertThat(plane.path("displayWidth").asInt()).isEqualTo(1920);
      assertThat(plane.path("displayHeight").asInt()).isEqualTo(1080);
      assertThat(plane.path("origin").asText()).isEqualTo("top-left");
      UUID id = UUID.fromString((String) created.get("id"));
      sessions.submit("owner", "actor", id, snapshot(30, 1));
      Files.writeString(facts, "{\"width\":1080,\"height\":1920,\"rotationDegrees\":90}");
      assertThatThrownBy(() -> sessions.submit("owner", "actor", id, snapshot(30, 2)))
          .hasMessageContaining("coordinate plane changed");
    } finally {
      Files.deleteIfExists(facts);
    }
  }

  @Test
  void immutableSessionCreateAndOwnerActorIsolation() {
    var body = createBody();
    var first = sessions.create("owner", "actor", source.getId(), body);
    var second = sessions.create("owner", "actor", source.getId(), body);
    assertThat(second.get("id")).isEqualTo(first.get("id"));
    body.put("endOrdinal", 28);
    assertThatThrownBy(() -> sessions.create("owner", "actor", source.getId(), body))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("different content");
    UUID id = UUID.fromString((String) first.get("id"));
    assertThatThrownBy(() -> sessions.get("owner", "other", id))
        .hasMessageContaining("not available");
    assertThatThrownBy(() -> sessions.get("other", "actor", id))
        .hasMessageContaining("not available");
  }

  @Test
  void unknownFieldsAndIdentityDriftAreRejected() throws Exception {
    var body = createBody();
    body.put("independent", true);
    assertThatThrownBy(() -> sessions.create("owner", "actor", source.getId(), body))
        .hasMessageContaining("Unknown review field");
    body.remove("independent");
    body.put("frameIndexChecksum", "changed");
    assertThatThrownBy(() -> sessions.create("owner", "actor", source.getId(), body))
        .hasMessageContaining("identity changed");
    UUID id = open();
    writeManifest("dev");
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, snapshot(30, 1)))
        .hasMessageContaining("split");
  }

  @Test
  void exposureBeforeEnrollmentCannotBeErasedByInstallingAManifest() throws Exception {
    properties.setCorpusManifest("");
    sessions.expose("owner", "actor", source.getId(), "OBSERVATIONS");
    properties.setCorpusManifest(manifest.toString());
    assertThat(
            (List<?>)
                sessions
                    .policy("owner", "actor", source.getId(), analysis.getId())
                    .get("eligibleModes"))
        .isEmpty();
    assertThatThrownBy(this::open).hasMessageContaining("not eligible");
  }

  @Test
  void heldoutAndOpenBlindRoutesRefusePredictionsBeforeAnyBytes() {
    assertThatThrownBy(() -> sessions.expose("owner", "actor", source.getId(), "OBSERVATIONS"))
        .hasMessageContaining("sealed before");
    open();
    assertThatThrownBy(() -> sessions.expose("owner", "actor", source.getId(), "EXPORT"))
        .hasMessageContaining("sealed before");
    assertThat(
            db.queryForObject(
                "select count(*) from mouse_video_review_exposures where source_id=?",
                Long.class,
                source.getId()))
        .isZero();
  }

  @Test
  void exactSnapshotRetryDenseSealAndImmutableExport() {
    UUID id = open();
    ObjectNode body = snapshot(30, 1);
    var first = sessions.submit("owner", "actor", id, body);
    var again = sessions.submit("owner", "actor", id, body);
    assertThat(again).isEqualTo(first);
    ObjectNode changed = body.deepCopy();
    changed.put("snapshotJson", body.path("snapshotJson").asText() + " ");
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, changed))
        .hasMessageContaining("different snapshot");
    var seal =
        M.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("revisionId", (String) first.get("id"))
            .put("snapshotDigest", (String) first.get("snapshotDigest"));
    assertThat(sessions.seal("owner", "actor", id, seal).get("state")).isEqualTo("SEALED");
    String export = sessions.export("owner", "actor", id);
    assertThat(export).contains("review-provenance/v1").contains("WITHOUT_SUGGESTIONS");
    assertThat(sessions.seal("owner", "actor", id, seal).get("state")).isEqualTo("SEALED");
    assertThat(sessions.export("owner", "actor", id)).isEqualTo(export);
    sessions.expose("owner", "actor", source.getId(), "EVALUATION");
    assertThat(sessions.submit("owner", "actor", id, body)).isEqualTo(first);
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, snapshot(1, 2)))
        .hasMessageContaining("no longer open");
  }

  @Test
  void incompleteTruthAndAssistedOriginsCannotBeSealed() {
    UUID id = open();
    ObjectNode bad = snapshot(1, 1);
    ObjectNode payload = (ObjectNode) read(bad.path("snapshotJson").asText());
    ((ObjectNode) payload.path("origins")).put("0", "ACCEPTED_PREDICTION");
    bad.put("snapshotJson", payload.toString());
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, bad))
        .hasMessageContaining("Label origin");
    var saved = sessions.submit("owner", "actor", id, snapshot(1, 1));
    var seal =
        M.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("revisionId", (String) saved.get("id"))
            .put("snapshotDigest", (String) saved.get("snapshotDigest"));
    assertThatThrownBy(() -> sessions.seal("owner", "actor", id, seal))
        .hasMessageContaining("coverage is incomplete");
  }

  @Test
  void concurrentExposureCannotRaceAnOpenBlindSession() throws Exception {
    writeManifest("dev");
    var create = createBody();
    RuntimeException result =
        overlap(
            () -> sessions.create("owner", "actor", source.getId(), create),
            () -> sessions.expose("owner", "actor", source.getId(), "OBSERVATIONS"));
    assertThat(result)
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("open blind review");
    assertThat(
            db.queryForObject(
                "select count(*) from mouse_video_review_exposures where source_id=?",
                Long.class,
                source.getId()))
        .isZero();
  }

  @Test
  void concurrentBlindCreationCannotEraseCommittedExposure() throws Exception {
    writeManifest("dev");
    var create = createBody();
    RuntimeException result =
        overlap(
            () -> sessions.expose("owner", "actor", source.getId(), "OBSERVATIONS"),
            () -> sessions.create("owner", "actor", source.getId(), create));
    assertThat(result).isInstanceOf(MouseVideoException.class).hasMessageContaining("not eligible");
    assertThat(
            db.queryForObject(
                "select count(*) from mouse_video_review_sessions where source_id=?",
                Long.class,
                source.getId()))
        .isZero();
  }

  RuntimeException overlap(Runnable first, Runnable second) throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    String application = "review_race_" + UUID.randomUUID().toString().replace("-", "");
    try {
      var holder =
          pool.submit(
              () ->
                  tx.executeWithoutResult(
                      status -> {
                        first.run();
                        ready.countDown();
                        try {
                          if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("Race release timed out");
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                      }));
      assertThat(ready.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var waiter =
          pool.submit(
              () -> {
                try {
                  tx.executeWithoutResult(
                      status -> {
                        db.execute("set local application_name='" + application + "'");
                        second.run();
                      });
                  return (RuntimeException) null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(
              () ->
                  db.queryForObject(
                          "select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock'",
                          Long.class,
                          application)
                      > 0);
      release.countDown();
      holder.get(15, java.util.concurrent.TimeUnit.SECONDS);
      return waiter.get(15, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void historyIsBoundedAndNeverSeedsFromAnotherSessionCursor() {
    UUID id = open();
    for (int i = 0; i < 23; i++) {
      ObjectNode request = snapshot(1, i + 1);
      ObjectNode payload = (ObjectNode) read(request.path("snapshotJson").asText());
      payload.put("expectedBaseRevision", i);
      request.put("snapshotJson", payload.toString());
      sessions.submit("owner", "actor", id, request);
    }
    var recent = sessions.history("owner", "actor", id);
    assertThat((List<?>) recent.get("items")).hasSize(20);
    UUID cursor = UUID.fromString((String) recent.get("nextCursor"));
    var older = sessions.history("owner", "actor", id, cursor, 20);
    assertThat((List<?>) older.get("items")).hasSize(3);
    assertThat(older.get("nextCursor")).isNull();
    assertThatThrownBy(() -> sessions.history("owner", "actor", id, UUID.randomUUID(), 20))
        .hasMessageContaining("not available");
  }

  @Test
  void splitChangesAndLegacyUnknownLabelsCannotRestoreEligibility() throws Exception {
    writeManifest("dev");
    sessions.policy("owner", "actor", source.getId(), analysis.getId());
    writeManifest("heldout");
    assertThatThrownBy(() -> sessions.policy("owner", "actor", source.getId(), analysis.getId()))
        .hasMessageContaining("split is immutable");
  }

  @Test
  void abandonedHistoryRemainsAccessibleButCannotBeSealed() {
    UUID id = open();
    var saved = sessions.submit("owner", "actor", id, snapshot(1, 1));
    var body =
        M.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("latestRevisionId", (String) saved.get("id"));
    assertThat(sessions.abandon("owner", "actor", id, body).get("state")).isEqualTo("ABANDONED");
    assertThat((List<?>) sessions.history("owner", "actor", id).get("items")).hasSize(1);
    assertThatThrownBy(() -> sessions.export("owner", "actor", id))
        .hasMessageContaining("Only sealed");
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, snapshot(1, 2)))
        .hasMessageContaining("no longer open");
  }

  JsonNode read(String value) {
    try {
      return M.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // Explicit registration only: never component-scan database test fixtures into the app.
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = MouseVideoSourceRepository.class)
  static class Config {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
      var f = new LocalContainerEntityManagerFactoryBean();
      f.setDataSource(ds);
      f.setPackagesToScan(MouseVideoSource.class.getPackageName());
      f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
      return f;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource ds) {
      return new JdbcTemplate(ds);
    }

    @Bean
    MouseVideoProperties properties() {
      return new MouseVideoProperties();
    }

    @Bean
    MouseVideoFrameIndexService indices() {
      return mock(MouseVideoFrameIndexService.class);
    }

    @Bean
    MouseVideoPreviewService previews() {
      return mock(MouseVideoPreviewService.class);
    }

    @Bean
    MouseVideoReviewService reviews(
        MouseVideoSourceRepository sources,
        MouseVideoAnalysisRepository analyses,
        MouseVideoReviewRevisionRepository revisions,
        MouseVideoFrameIndexService indices,
        MouseVideoPreviewService previews) {
      return new MouseVideoReviewService(sources, analyses, revisions, indices, previews);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    MouseVideoByteStore byteStore() {
      return mock(MouseVideoByteStore.class);
    }

    @Bean
    MouseVideoCoordinatePlane planes(MouseVideoByteStore store, ObjectMapper mapper) {
      return new MouseVideoCoordinatePlane(store, mapper);
    }

    @Bean
    MouseVideoReviewSessionService sessions(
        JdbcTemplate db,
        MouseVideoSourceRepository sources,
        MouseVideoAnalysisRepository analyses,
        MouseVideoFrameIndexService indices,
        MouseVideoReviewService reviews,
        MouseVideoCoordinatePlane planes,
        MouseVideoProperties p,
        ObjectMapper mapper) {
      return new MouseVideoReviewSessionService(
          db, sources, analyses, indices, reviews, planes, p, mapper);
    }
  }

  ObjectNode rebased(ObjectNode wrapped, int expectedBaseRevision) throws Exception {
    ObjectNode body = (ObjectNode) M.readTree(wrapped.path("snapshotJson").asText());
    body.put("expectedBaseRevision", expectedBaseRevision);
    return M.createObjectNode().put("snapshotJson", body.toString());
  }

  @Test
  void removingEveryLabelSavesAnEmptyRevisionAndKeepsHistory() throws Exception {
    UUID id = open();
    sessions.submit("owner", "actor", id, snapshot(30, 1));

    sessions.submit("owner", "actor", id, rebased(snapshot(0, 2), 1));

    List<Map<String, Object>> revisions =
        db.queryForList(
            "select jsonb_array_length(corrections_json) as size from mouse_video_review_revisions"
                + " where review_session_id=? order by client_edit_version",
            id);
    assertThat(revisions)
        .extracting(row -> ((Number) row.get("size")).intValue())
        .containsExactly(30, 0);
    UUID latest =
        db.queryForObject(
            "select latest_revision_id from mouse_video_review_sessions where id=?",
            UUID.class,
            id);
    assertThat(
            db.queryForObject(
                "select jsonb_array_length(corrections_json) from mouse_video_review_revisions"
                    + " where id=?",
                Integer.class,
                latest))
        .isZero();
  }

  @Test
  void sessionRangeIsBoundedAtTwoThousandFramesAndAFullRangeSnapshotSaves() {
    var frames = new ArrayList<MouseVideoFrameIndexService.Frame>();
    for (int i = 0; i < 2001; i++)
      frames.add(new MouseVideoFrameIndexService.Frame("" + i, "" + (i / 30.0), "1", i == 0));
    var index =
        new MouseVideoFrameIndexService.FrozenFrameIndex(
            checksum, "mouse-video-frame-index/v1", true, "1/30", "1", "30", List.copyOf(frames));
    when(context
            .getBean(MouseVideoFrameIndexService.class)
            .requireIndex("owner", source.getId(), analysis.getId()))
        .thenReturn(index);
    when(context
            .getBean(MouseVideoFrameIndexService.class)
            .bounds("owner", source.getId(), analysis.getId()))
        .thenReturn(new MouseVideoCorrectionContract.Bounds(2001, 160, 96));

    // 2,001 frames is refused for its size before any overlap check can apply.
    ObjectNode tooLong = createBody().put("endOrdinal", 2000);
    assertThatThrownBy(() -> sessions.create("owner", "actor", source.getId(), tooLong))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("1–2000 indexed frames");

    UUID id =
        UUID.fromString(
            (String)
                sessions
                    .create("owner", "actor", source.getId(), createBody().put("endOrdinal", 1999))
                    .get("id"));
    ObjectNode full = snapshot(2000, 1);
    assertThat(full.path("snapshotJson").asText().getBytes(StandardCharsets.UTF_8).length)
        .isLessThan(600 * 1024);
    sessions.submit("owner", "actor", id, full);
    assertThat(
            db.queryForObject(
                "select jsonb_array_length(r.corrections_json) from mouse_video_review_sessions s"
                    + " join mouse_video_review_revisions r on r.id=s.latest_revision_id"
                    + " where s.id=?",
                Integer.class,
                id))
        .isEqualTo(2000);
  }

  @Test
  void snapshotLargerThanTheWireBoundIsRefusedBeforeParsing() {
    UUID id = open();
    String oversized = "x".repeat(600 * 1024 + 1);
    ObjectNode request = M.createObjectNode().put("snapshotJson", oversized);
    assertThatThrownBy(() -> sessions.submit("owner", "actor", id, request))
        .isInstanceOf(MouseVideoException.class)
        .hasMessageContaining("exceeds request bound");
    assertThat(
            db.queryForObject(
                "select count(*) from mouse_video_review_revisions where review_session_id=?",
                Long.class,
                id))
        .isZero();
  }
}
