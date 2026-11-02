package com.chromascape.web.replay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.provenance.BehaviorRunContextService;
import com.chromascape.web.replay.api.ReplayScenarioController;
import com.chromascape.web.replay.api.ReplayV2Controller;
import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.api.dto.ReplayStopPolicy;
import com.chromascape.web.replay.artifact.ReplayArtifactService;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import com.chromascape.web.replay.artifact.repository.ArtifactRefRepository;
import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.capability.ReplayCapabilityService;
import com.chromascape.web.replay.catalog.RecordingIntegrityService;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.catalog.ReplayV2PreflightService;
import com.chromascape.web.replay.catalog.dto.ReplayEligibility;
import com.chromascape.web.replay.compiler.ScenarioCompiler;
import com.chromascape.web.replay.config.ReplayProperties;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.conversion.ReplayActionConversionService;
import com.chromascape.web.replay.conversion.ReplayGap;
import com.chromascape.web.replay.curation.entity.CurationRole;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.chromascape.web.replay.manifest.ScenarioManifestImportValidator;
import com.chromascape.web.replay.manifest.ScenarioManifestV2Compiler;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.report.repository.ReplayReportRepository;
import com.chromascape.web.replay.run.ReplayRunService;
import com.chromascape.web.replay.run.ReplayV2RunService;
import com.chromascape.web.replay.run.dto.CreateXrspsRunRequest;
import com.chromascape.web.replay.run.repository.ReplayAttemptRepository;
import com.chromascape.web.replay.run.repository.ReplayCheckpointRepository;
import com.chromascape.web.replay.run.repository.ReplayEventRepository;
import com.chromascape.web.replay.run.repository.ReplayMismatchRepository;
import com.chromascape.web.replay.run.repository.ReplayRunRepository;
import com.chromascape.web.replay.run.repository.StateHashSampleRepository;
import com.chromascape.web.replay.security.LocalReplayPrincipalConfig;
import com.chromascape.web.replay.security.LocalReplayPrincipalFilter;
import com.chromascape.web.replay.security.ReplayOwnershipService;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.chromascape.web.replay.viewer.ReplayViewerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Actual compiler, JPA repositories and Spring transaction proxies against disposable PostgreSQL.
 * Recording/tick/action input, catalog integrity and viewer services are fixtures; artifact storage
 * is real and test-isolated. Standalone MockMvc exercises MVC binding, validation, controllers,
 * exception advice and exports against the same services and database, with a supplied principal.
 * Additional real loopback HTTP cases use the production local-development principal bridge and its
 * production-profile refusal, without supplying a servlet principal. All source inputs and
 * terminalization remain authored fixtures, not capture, upstream authentication or certification.
 */
@EnabledIfEnvironmentVariable(named = "SC274_TEST_JDBC_URL", matches = ".+")
class Sc274CompilerTransactionIntegrationTest {
  private static final String RECORDING = "authored-sc274-transaction";
  private static final String SESSION = "authored-sc274-transaction-session";
  private static final String OWNER = "sc274-owner";
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final CanonicalProjectionService CANONICAL = new CanonicalProjectionService();
  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate admin;
  private static JdbcTemplate jdbc;
  private static DataSource contextDataSource;
  private static String schema;
  @TempDir static Path artifactRoot;
  private ScenarioManifestV2Compiler compiler;
  private ReplayV2RunService service;
  private ReplayActionConversionService converter;
  private RecordingCuration source;
  private MockMvc http;

  @BeforeAll
  static void connect() {
    String url = System.getenv("SC274_TEST_JDBC_URL");
    // An explicit test-only URL and one random schema; never use application datasource settings.
    schema = "sc274_tx_" + UUID.randomUUID().toString().replace("-", "");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, "test", "test"));
    admin.execute("CREATE SCHEMA " + schema);
    var dataSource =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "test", "test");
    contextDataSource = dataSource;
    jdbc = new JdbcTemplate(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .locations(ReplayMigrationIntegrationTest.replayMigrations())
        .defaultSchema(schema)
        .schemas(schema)
        .load()
        .migrate();
    openContext();
  }

  /** Rebuilds the Spring context over the same schema, as a real service restart would. */
  private static void openContext() {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(DataSource.class, () -> contextDataSource);
    context.register(Persistence.class);
    context.refresh();
  }

  @AfterAll
  static void clean() {
    try {
      if (context != null) {
        context.close();
      }
    } finally {
      if (admin != null && schema != null) {
        assertThat(schema).matches("sc274_tx_[0-9a-f]{32}");
        admin.execute("DROP SCHEMA " + schema + " CASCADE");
        assertThat(
                admin.queryForObject(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class,
                    schema))
            .isZero();
      }
    }
  }

  @BeforeEach
  void seed() {
    jdbc.execute(
        "TRUNCATE recording_curations, scenario_manifests, curated_actions, replay_runs CASCADE");
    compiler = context.getBean(ScenarioManifestV2Compiler.class);
    service = context.getBean(ReplayV2RunService.class);
    converter =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
            context.getBean(ReplayActionConversionService.class));
    reset(converter);
    when(converter.convert(eq(SESSION), eq(0L), eq(0L), any())).thenReturn(conversion(false));
    assertThat(AopUtils.isAopProxy(compiler)).isTrue();
    assertThat(AopUtils.isAopProxy(service)).isTrue();
    source = new RecordingCuration();
    source.setRecordingId(RECORDING);
    source.setDebugSessionId(SESSION);
    source.setVersion(1);
    source.setSchemaVersion(1);
    source.setState(CurationState.PUBLISHED);
    source.setRole(CurationRole.HELD_OUT);
    source.setCreatedBy(OWNER);
    source.setCreatedAt(LocalDateTime.of(2026, 10, 15, 0, 0));
    source.setPublishedAt(source.getCreatedAt());
    ObjectNode content = JSON.createObjectNode();
    content.putObject("tickRange").put("start", 0).put("end", 0);
    content.putArray("actionLabels");
    content.putObject("initialStateOverrides");
    source.setContentJson(content);
    source.setContentChecksum(CANONICAL.sha256Hex(CANONICAL.canonicalJsonBytes(content)));
    source = context.getBean(RecordingCurationRepository.class).saveAndFlush(source);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    http =
        MockMvcBuilders.standaloneSetup(
                new ReplayV2Controller(service),
                new ReplayScenarioController(
                    mock(ScenarioCompiler.class),
                    context.getBean(ScenarioManifestRepository.class),
                    new ReplayPrincipalService(),
                    new ReplayOwnershipService(context.getBean(RecordingCurationRepository.class))))
            .setControllerAdvice(new ReplayExceptionHandler())
            .setMessageConverters(
                new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(JSON))
            .build();
  }

  @Test
  void httpPreviewCreateRetryAndStalePreviewKeepTheExactSelectedInput() throws Exception {
    var preview =
        http.perform(preflight().principal(() -> OWNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.simulationEligible").value(true))
            .andExpect(jsonPath("$.certificationEligible").value(false))
            .andExpect(jsonPath("$.compiledInput.curationId").value(source.getId().toString()))
            .andReturn();
    var input = JSON.readTree(preview.getResponse().getContentAsByteArray()).path("compiledInput");
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    String body =
        JSON.writeValueAsString(
            request("http-selected-key", input.path("manifestChecksum").asText()));
    var first =
        http.perform(create(body).principal(() -> OWNER))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.manifestId").value(input.path("manifestId").asText()))
            .andReturn();
    String runId =
        JSON.readTree(first.getResponse().getContentAsByteArray()).path("runId").asText();
    http.perform(create(body).principal(() -> OWNER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(runId));
    assertThat(counts()).containsExactly(2L, 1L, 1L, 1L);
    var stale = request("http-stale-key", "sha256:" + "f".repeat(64));
    http.perform(create(JSON.writeValueAsString(stale)).principal(() -> OWNER))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    assertThat(counts()).containsExactly(2L, 1L, 1L, 1L);
    assertSourceUnchanged();
  }

  @Test
  void socketRequestsCannotSupplyTheirOwnPrincipalWhenLocalBridgeIsDisabled() throws Exception {
    List<Long> before = counts();
    long inserts = insertCount();
    try (SocketBoundary socket = new SocketBoundary(false, artifactRoot.resolve("socket-denied"))) {
      for (Map<String, String> headers :
          List.of(
              Map.<String, String>of(),
              Map.of("X-User", OWNER, "X-Remote-User", OWNER),
              Map.of("Authorization", "Bearer authored-not-an-authentication-token"))) {
        var read = socket.send("GET", "/api/replay/scenarios/" + UUID.randomUUID(), null, headers);
        assertThat(read.statusCode()).isEqualTo(401);
        assertThat(JSON.readTree(read.body()).path("message").asText())
            .isEqualTo("Authentication is required");
        var create =
            socket.send(
                "POST",
                "/api/replay/v2/recordings/" + RECORDING + "/xrsps-runs",
                JSON.writeValueAsBytes(request("socket-denied", null)),
                headers);
        assertThat(create.statusCode()).isEqualTo(401);
      }
    }
    assertThat(counts()).isEqualTo(before);
    assertThat(insertCount()).isEqualTo(inserts);
    verifyNoInteractions(converter);
  }

  @ParameterizedTest
  @EnumSource(CurationRole.class)
  void socketLocalPrincipalPreservesRolesAndRejectsForeignPrivateArtifacts(CurationRole role)
      throws Exception {
    // The foreign and local sources are explicitly authored fixtures in this disposable schema.
    var foreign = service.create(RECORDING, request("socket-foreign", null), OWNER).response();
    String localOwner = System.getProperty("user.name");
    if (localOwner == null || localOwner.isBlank()) localOwner = "local-development";
    assertThat(localOwner).isNotEqualTo(OWNER);
    RecordingCuration localSource = new RecordingCuration();
    org.springframework.beans.BeanUtils.copyProperties(
        source, localSource, "id", "version", "createdBy", "role");
    localSource.setVersion(
        jdbc.queryForObject(
            "SELECT coalesce(max(version), 0) + 1 FROM recording_curations WHERE recording_id=?",
            Integer.class,
            RECORDING));
    localSource.setCreatedBy(localOwner);
    localSource.setRole(role);
    localSource.setContentJson(source.getContentJson().deepCopy());
    source = context.getBean(RecordingCurationRepository.class).saveAndFlush(localSource);

    try (SocketBoundary socket =
        new SocketBoundary(true, artifactRoot.resolve("socket-" + role.name()))) {
      String createPath = "/api/replay/v2/recordings/" + RECORDING + "/xrsps-runs";
      byte[] requestBytes = JSON.writeValueAsBytes(request("socket-local", null));
      var created = socket.send("POST", createPath, requestBytes, Map.of());
      assertThat(created.statusCode()).isEqualTo(201);
      var result = JSON.readTree(created.body());
      UUID runId = UUID.fromString(result.path("runId").asText());
      UUID manifestId = UUID.fromString(result.path("manifestId").asText());
      List<Long> afterCreate = counts();
      var retry = socket.send("POST", createPath, requestBytes, Map.of());
      assertThat(retry.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(retry.body()).path("runId").asText()).isEqualTo(runId.toString());
      assertThat(counts()).isEqualTo(afterCreate);

      var privateManifest =
          socket.send("GET", "/api/replay/scenarios/" + manifestId, null, Map.of());
      assertThat(privateManifest.statusCode()).isEqualTo(200);
      var manifest =
          context.getBean(ScenarioManifestRepository.class).findById(manifestId).orElseThrow();
      byte[] content = CANONICAL.canonicalJsonBytes(manifest.getManifestJson());
      publishManifest(runId, content); // Authored terminalization, never worker qualification.
      var exported =
          socket.send(
              "GET", "/api/replay/v2/runs/" + runId + "/artifacts/manifest", null, Map.of());
      assertThat(exported.statusCode()).isEqualTo(200);
      assertThat(exported.body()).isEqualTo(content);
      assertThat(exported.headers().firstValue("Cache-Control")).contains("no-store");
      assertThat(exported.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
      assertThat(exported.headers().firstValue("X-Replay-Artifact-Checksum"))
          .contains(CANONICAL.sha256Hex(content));
      var imported = ScenarioManifestImportValidator.validate(exported.body());
      ScenarioManifestImportValidator.requireSimulationAllowed(imported);
      assertThat(imported.path("curation").path("datasetRole").asText()).isEqualTo(role.name());

      List<Long> beforeRefusals = counts();
      long inserts = insertCount();
      for (String suffix : List.of("", "/artifacts/manifest")) {
        var foreignRead =
            socket.send(
                "GET",
                "/api/replay/v2/runs/" + foreign.runId() + suffix,
                null,
                Map.of("X-User", OWNER));
        var missingRead =
            socket.send("GET", "/api/replay/v2/runs/" + UUID.randomUUID() + suffix, null, Map.of());
        assertThat(foreignRead.statusCode()).isEqualTo(404);
        assertThat(missingRead.statusCode()).isEqualTo(404);
        assertSameError(foreignRead.body(), missingRead.body());
      }
      var foreignManifest =
          socket.send("GET", "/api/replay/scenarios/" + foreign.manifestId(), null, Map.of());
      var missingManifest =
          socket.send("GET", "/api/replay/scenarios/" + UUID.randomUUID(), null, Map.of());
      assertThat(foreignManifest.statusCode()).isEqualTo(404);
      assertThat(missingManifest.statusCode()).isEqualTo(404);
      assertSameError(foreignManifest.body(), missingManifest.body());
      assertThat(
              socket
                  .send("POST", createPath, "{".getBytes(StandardCharsets.UTF_8), Map.of())
                  .statusCode())
          .isEqualTo(400);
      var stale = request("socket-stale", "sha256:" + "f".repeat(64));
      assertThat(
              socket.send("POST", createPath, JSON.writeValueAsBytes(stale), Map.of()).statusCode())
          .isEqualTo(409);
      assertThat(counts()).isEqualTo(beforeRefusals);
      assertThat(insertCount()).isEqualTo(inserts);
    }
  }

  /** Real loopback socket and production principal bridge, with no supplied servlet principal. */
  private static final class SocketBoundary implements AutoCloseable {
    private final AnnotationConfigWebApplicationContext web =
        new AnnotationConfigWebApplicationContext();
    private final HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private WebServer server;

    SocketBoundary(boolean localDevelopment, Path root) throws Exception {
      Files.createDirectories(root);
      var environment = new MockEnvironment();
      if (localDevelopment) environment.setActiveProfiles("local-development");
      else environment.setActiveProfiles("production");
      web.setEnvironment(environment);
      web.setParent(context);
      web.register(SocketMvc.class);
      var factory = new TomcatServletWebServerFactory(0);
      factory.setAddress(InetAddress.getByName("127.0.0.1"));
      factory.setBaseDirectory(root.toFile());
      try {
        server =
            factory.getWebServer(
                servletContext -> {
                  var dispatcher =
                      servletContext.addServlet("dispatcher", new DispatcherServlet(web));
                  dispatcher.setLoadOnStartup(1);
                  dispatcher.addMapping("/");
                  new LocalReplayPrincipalConfig()
                      .localReplayPrincipalFilterRegistration(
                          new LocalReplayPrincipalFilter(environment))
                      .onStartup(servletContext);
                });
        server.start();
      } catch (Exception failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    HttpResponse<byte[]> send(String method, String path, byte[] body, Map<String, String> headers)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
              .timeout(Duration.ofSeconds(10));
      headers.forEach(request::header);
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Override
    public void close() {
      try {
        if (server != null) server.stop();
      } finally {
        web.close();
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  static class SocketMvc implements WebMvcConfigurer {
    @Bean
    ReplayV2Controller replayV2SocketController(ReplayV2RunService runs) {
      return new ReplayV2Controller(runs);
    }

    @Bean
    ReplayScenarioController replayScenarioSocketController(
        ScenarioManifestRepository manifests, RecordingCurationRepository curations) {
      return new ReplayScenarioController(
          mock(ScenarioCompiler.class),
          manifests,
          new ReplayPrincipalService(),
          new ReplayOwnershipService(curations));
    }

    @Bean
    ReplayExceptionHandler socketReplayExceptionHandler() {
      return new ReplayExceptionHandler();
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
      converters.add(new ByteArrayHttpMessageConverter());
      converters.add(new MappingJackson2HttpMessageConverter(JSON));
    }
  }

  @Test
  void httpSourceGapRemainsVisibleAtFullCoverageAndCreateRollsBack() throws Exception {
    when(converter.convert(eq(SESSION), eq(0L), eq(0L), any())).thenReturn(conversion(true));
    http.perform(preflight().principal(() -> OWNER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.simulationEligible").value(false))
        .andExpect(jsonPath("$.certificationEligible").value(false))
        .andExpect(jsonPath("$.actionCoveragePct").value(100.0))
        .andExpect(jsonPath("$.gaps[0].code").value("TYPED_ACTION_STREAM_INCOMPLETE"));
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    long before = insertCount();
    http.perform(
            create(JSON.writeValueAsString(request("http-gap-key", null))).principal(() -> OWNER))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SIM_INITIAL_STATE_INCOMPLETE"));
    assertThat(insertCount() - before).isEqualTo(3);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    assertSourceUnchanged();
  }

  @Test
  void httpMissingForeignAndDraftSelectionsAreIndistinguishable() throws Exception {
    var foreignPreview =
        http.perform(preflight().principal(() -> "other"))
            .andExpect(status().isNotFound())
            .andReturn();
    var missingPreview =
        http.perform(
                get("/api/replay/v2/recordings/{id}/preflight", RECORDING)
                    .param("curationId", UUID.randomUUID().toString())
                    .principal(() -> "other"))
            .andExpect(status().isNotFound())
            .andReturn();
    assertSameError(
        foreignPreview.getResponse().getContentAsByteArray(),
        missingPreview.getResponse().getContentAsByteArray());
    String body = JSON.writeValueAsString(request("http-foreign-key", null));
    var foreignCreate =
        http.perform(create(body).principal(() -> "other"))
            .andExpect(status().isNotFound())
            .andReturn();
    var absent = (ObjectNode) JSON.readTree(body);
    absent.put("curationId", UUID.randomUUID().toString());
    var missingCreate =
        http.perform(create(JSON.writeValueAsString(absent)).principal(() -> "other"))
            .andExpect(status().isNotFound())
            .andReturn();
    assertSameError(
        foreignCreate.getResponse().getContentAsByteArray(),
        missingCreate.getResponse().getContentAsByteArray());
    jdbc.update("UPDATE recording_curations SET state='DRAFT' WHERE id=?", source.getId());
    var draft =
        http.perform(preflight().principal(() -> OWNER))
            .andExpect(status().isNotFound())
            .andReturn();
    assertSameError(
        foreignPreview.getResponse().getContentAsByteArray(),
        draft.getResponse().getContentAsByteArray());
    verifyNoInteractions(converter);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
  }

  @Test
  void httpUnauthenticatedAndMalformedRequestsCannotReachTheCompiler() throws Exception {
    String body = JSON.writeValueAsString(request("http-invalid-key", null));
    http.perform(preflight()).andExpect(status().isUnauthorized());
    http.perform(create(body)).andExpect(status().isUnauthorized());
    for (String identity : new String[] {"", " "}) {
      http.perform(preflight().principal(() -> identity)).andExpect(status().isUnauthorized());
      http.perform(create(body).principal(() -> identity)).andExpect(status().isUnauthorized());
    }
    for (String field :
        new String[] {"conversionMode", "openViewer", "stopPolicy", "idempotencyKey"}) {
      ObjectNode invalid = (ObjectNode) JSON.readTree(body);
      invalid.remove(field);
      http.perform(create(JSON.writeValueAsString(invalid)).principal(() -> OWNER))
          .andExpect(status().isBadRequest());
    }
    for (String[] invalid :
        new String[][] {
          {"curationId", "not-a-uuid"}, {"expectedManifestChecksum", "sha256:bad"},
          {"idempotencyKey", "short"}, {"idempotencyKey", "forbidden/key"},
          {"conversionMode", "INVENT_HIDDEN_STATE"}, {"stopPolicy", "not-a-policy"}
        }) {
      ObjectNode request = (ObjectNode) JSON.readTree(body);
      request.put(invalid[0], invalid[1]);
      http.perform(create(JSON.writeValueAsString(request)).principal(() -> OWNER))
          .andExpect(status().isBadRequest());
    }
    http.perform(create("{invalid").principal(() -> OWNER)).andExpect(status().isBadRequest());
    http.perform(
            get("/api/replay/v2/recordings/{id}/preflight", RECORDING)
                .param("curationId", "invalid")
                .principal(() -> OWNER))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(converter);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
  }

  @ParameterizedTest
  @EnumSource(CurationRole.class)
  void httpManifestExportImportPreservesPublishedRoleAndPrivateLineage(CurationRole role)
      throws Exception {
    source.setRole(role);
    source = context.getBean(RecordingCurationRepository.class).saveAndFlush(source);
    var run = service.create(RECORDING, request("http-export-key", null), OWNER).response();
    var manifest =
        context.getBean(ScenarioManifestRepository.class).findById(run.manifestId()).orElseThrow();
    http.perform(get("/api/replay/scenarios/{id}", manifest.getId()).principal(() -> OWNER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.manifestChecksum").value(manifest.getManifestChecksum()));
    http.perform(get("/api/replay/scenarios/{id}", manifest.getId()).principal(() -> "other"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Manifest not found"));
    byte[] content = CANONICAL.canonicalJsonBytes(manifest.getManifestJson());
    publishManifest(run.runId(), content);
    var response =
        http.perform(
                get("/api/replay/v2/runs/{id}/artifacts/manifest", run.runId())
                    .principal(() -> OWNER))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Replay-Artifact-Checksum", CANONICAL.sha256Hex(content)))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        "attachment; filename=\"replay-" + run.runId() + "-manifest.json\""))
            .andReturn();
    assertThat(response.getResponse().getContentAsByteArray()).isEqualTo(content);
    var imported =
        ScenarioManifestImportValidator.validate(response.getResponse().getContentAsByteArray());
    ScenarioManifestImportValidator.requireSimulationAllowed(imported);
    assertThat(imported.path("curation").path("datasetRole").asText()).isEqualTo(role.name());
    assertThat(imported.path("curation").path("sourceCuration").path("contentSnapshot"))
        .isEqualTo(source.getContentJson());
    assertThat(imported.path("curation").path("sourceCuration").path("id").asText())
        .isEqualTo(source.getId().toString());
    assertThat(
            context
                .getBean(RecordingCurationRepository.class)
                .findById(manifest.getCurationId())
                .orElseThrow()
                .getRole())
        .isEqualTo(role);
    assertThat(
            context
                .getBean(RecordingCurationRepository.class)
                .findById(source.getId())
                .orElseThrow()
                .getRole())
        .isEqualTo(role);
    assertThat(counts()).containsExactly(2L, 1L, 1L, 1L);
  }

  @Test
  void httpPersistedConversionOwnershipRejectsEveryChangedSourcePin() throws Exception {
    var compiled = compiler.compile(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    UUID conversionId = compiled.manifest().getCurationId();
    final var originalConversion =
        context
            .getBean(RecordingCurationRepository.class)
            .findById(conversionId)
            .orElseThrow()
            .getContentJson();
    String route = "/api/replay/scenarios/" + compiled.manifest().getId();
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isOk());
    // These are deliberate corruptions of this suite's disposable schema, not supported edits.
    String[][] changes = {
      {"created_by", "another-owner", OWNER},
      {"state", "DRAFT", "PUBLISHED"},
      {"role", "TRAIN", "HELD_OUT"},
      {"recording_id", "another-recording", RECORDING},
      {"debug_session_id", "another-session", SESSION},
      {"content_checksum", "sha256:" + "f".repeat(64), source.getContentChecksum()}
    };
    for (String[] change : changes) {
      jdbc.update(
          "UPDATE recording_curations SET " + change[0] + "=? WHERE id=?",
          change[1],
          source.getId());
      http.perform(get(route).principal(() -> OWNER))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Manifest not found"));
      jdbc.update(
          "UPDATE recording_curations SET " + change[0] + "=? WHERE id=?",
          change[2],
          source.getId());
      http.perform(get(route).principal(() -> OWNER)).andExpect(status().isOk());
    }
    jdbc.update("UPDATE recording_curations SET version=99 WHERE id=?", source.getId());
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isNotFound());
    jdbc.update("UPDATE recording_curations SET version=1 WHERE id=?", source.getId());
    jdbc.update(
        "UPDATE recording_curations SET"
            + " content_json=jsonb_set(content_json,'{tickRange,end}','1'::jsonb) WHERE id=?",
        source.getId());
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isNotFound());
    jdbc.update(
        "UPDATE recording_curations SET content_json=cast(? as jsonb) WHERE id=?",
        JSON.writeValueAsString(source.getContentJson()),
        source.getId());
    jdbc.update(
        "UPDATE recording_curations SET"
            + " content_json=jsonb_set(content_json,'{sourceCuration,role}','\"TRAIN\"'::jsonb)"
            + " WHERE id=?",
        conversionId);
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isNotFound());
    jdbc.update(
        "UPDATE recording_curations SET content_json=cast(? as jsonb) WHERE id=?",
        JSON.writeValueAsString(originalConversion),
        conversionId);
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isOk());
    assertSourceUnchanged();
    assertThat(counts()).containsExactly(2L, 1L, 1L, 0L);
  }

  @Test
  void httpForeignAndMissingRunsAreUniformEvenForArtifactsAndEvidence() throws Exception {
    var run = service.create(RECORDING, request("http-private-run", null), OWNER).response();
    for (String suffix :
        new String[] {
          "",
          "/events",
          "/report",
          "/mismatches",
          "/mismatches/first",
          "/artifacts/manifest",
          "/artifacts/unknown"
        }) {
      var foreign =
          http.perform(get("/api/replay/v2/runs/" + run.runId() + suffix).principal(() -> "other"))
              .andExpect(status().isNotFound())
              .andReturn();
      var missing =
          http.perform(
                  get("/api/replay/v2/runs/" + UUID.randomUUID() + suffix).principal(() -> "other"))
              .andExpect(status().isNotFound())
              .andReturn();
      assertSameError(
          foreign.getResponse().getContentAsByteArray(),
          missing.getResponse().getContentAsByteArray());
      http.perform(get("/api/replay/v2/runs/" + run.runId() + suffix))
          .andExpect(status().isUnauthorized());
    }
    for (String suffix : new String[] {"/cancel", "/viewer-session"}) {
      var foreign =
          http.perform(post("/api/replay/v2/runs/" + run.runId() + suffix).principal(() -> "other"))
              .andExpect(status().isNotFound())
              .andReturn();
      var missing =
          http.perform(
                  post("/api/replay/v2/runs/" + UUID.randomUUID() + suffix)
                      .principal(() -> "other"))
              .andExpect(status().isNotFound())
              .andReturn();
      assertSameError(
          foreign.getResponse().getContentAsByteArray(),
          missing.getResponse().getContentAsByteArray());
    }
    assertThat(
            context
                .getBean(ReplayRunRepository.class)
                .findById(run.runId())
                .orElseThrow()
                .getState()
                .name())
        .isEqualTo("QUEUED");
    assertThat(context.getBean(ArtifactRefRepository.class).count()).isZero();
  }

  @Test
  void httpExportsRequireTerminalFinalizedAndChecksumVerifiedContent() throws Exception {
    var run = service.create(RECORDING, request("http-corrupt-export", null), OWNER).response();
    String route = "/api/replay/v2/runs/" + run.runId() + "/artifacts/manifest";
    http.perform(get(route).principal(() -> OWNER))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RUN_STATE_INVALID"));
    jdbc.update(
        "UPDATE replay_runs SET state='SUCCEEDED', terminal_at=clock_timestamp() WHERE id=?",
        run.runId());
    http.perform(get(route).principal(() -> OWNER))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRACE_NOT_AVAILABLE"));
    var manifest =
        context.getBean(ScenarioManifestRepository.class).findById(run.manifestId()).orElseThrow();
    byte[] content = CANONICAL.canonicalJsonBytes(manifest.getManifestJson());
    var ref = publishManifest(run.runId(), content);
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isOk());
    String hex = ref.getChecksum().substring("sha256:".length());
    Path artifact = artifactRoot.resolve(hex.substring(0, 2)).resolve(hex);
    // Same-length corruption exercises the real content digest, not only the size bound.
    byte[] changed = content.clone();
    changed[changed.length - 1] = ' ';
    Files.write(artifact, changed);
    http.perform(get(route).principal(() -> OWNER))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    // Cross-owner refusal happens before reading even a corrupt file.
    http.perform(get(route).principal(() -> "other")).andExpect(status().isNotFound());
    assertThat(Files.readAllBytes(artifact)).isEqualTo(changed);
    Files.write(artifact, content);
    http.perform(get(route).principal(() -> OWNER)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder preflight() {
    return get("/api/replay/v2/recordings/{id}/preflight", RECORDING)
        .param("curationId", source.getId().toString());
  }

  private MockHttpServletRequestBuilder create(String body) {
    return post("/api/replay/v2/recordings/{id}/xrsps-runs", RECORDING)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static void assertSameError(byte[] first, byte[] second) throws Exception {
    ObjectNode left = (ObjectNode) JSON.readTree(first);
    ObjectNode right = (ObjectNode) JSON.readTree(second);
    assertThat(left.path("correlationId").asText()).isNotBlank();
    assertThat(right.path("correlationId").asText()).isNotBlank();
    left.remove("correlationId");
    right.remove("correlationId");
    // The requested UUID is public caller input; compare error templates across different IDs.
    String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    left.put("message", left.path("message").asText().replaceAll(uuid, "<requested-id>"));
    right.put("message", right.path("message").asText().replaceAll(uuid, "<requested-id>"));
    assertThat(left).isEqualTo(right);
    assertThat(new String(first, StandardCharsets.UTF_8))
        .doesNotContain(RECORDING, SESSION, OWNER, "C:\\", "/api/");
  }

  private ArtifactRef publishManifest(UUID runId, byte[] content) {
    var artifacts = context.getBean(ReplayArtifactService.class);
    var prepared =
        artifacts.prepareArtifact(runId, ArtifactKind.MANIFEST, content, "application/json");
    var ref =
        new ArtifactRef(
            prepared.proposedReferenceId(),
            runId,
            prepared.kind(),
            prepared.checksum(),
            prepared.sizeBytes());
    ref.setMediaType(prepared.mediaType());
    ref.setCreatedAt(Instant.now());
    var repository = context.getBean(ArtifactRefRepository.class);
    ref = repository.saveAndFlush(ref);
    assertThat(artifacts.publishArtifacts(runId).published()).hasSize(1);
    // Fixture terminalization only: this suite does not run a worker or qualify its fencing.
    ref.setState(ArtifactRef.STATE_FINALIZED);
    ref.setFinalizedAt(Instant.now());
    ref = repository.saveAndFlush(ref);
    jdbc.update(
        "UPDATE replay_runs SET state='SUCCEEDED', terminal_at=clock_timestamp() WHERE id=?",
        runId);
    return ref;
  }

  @Test
  void stalePreviewRefusalRollsBackFlushedCompilerCurationActionAndManifest() {
    var preview = compiler.preview(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    preview.requireSimulationAllowed();
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    long beforeInserts = insertCount();
    var error =
        assertThrows(
            ReplayException.class,
            () ->
                service.create(
                    RECORDING, request("stale-preview-key", "sha256:" + "f".repeat(64)), OWNER));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.CHECKSUM_MISMATCH);
    assertThat(error.getHttpStatus().value()).isEqualTo(409);
    assertThat(insertCount() - beforeInserts).isEqualTo(3);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    assertSourceUnchanged();
  }

  @Test
  void incompleteSourceAuditRefusalRollsBackEveryCompilerWrite() {
    when(converter.convert(eq(SESSION), eq(0L), eq(0L), any())).thenReturn(conversion(true));
    long beforeInserts = insertCount();
    var error =
        assertThrows(
            ReplayException.class,
            () -> service.create(RECORDING, request("incomplete-key", null), OWNER));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE);
    assertThat(insertCount() - beforeInserts).isEqualTo(3);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    assertSourceUnchanged();
  }

  @Test
  void matchingPreviewCommitsOnceAndRetryDoesNotRecompileOrCreateAnotherRun() {
    var preview = compiler.preview(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    var request = request("matching-preview-key", preview.manifest().getManifestChecksum());
    var first = service.create(RECORDING, request, OWNER);
    assertThat(first.created()).isTrue();
    assertThat(counts()).containsExactly(2L, 1L, 1L, 1L);
    assertThat(first.response().manifestId()).isEqualTo(preview.manifest().getId());
    var retry = service.create(RECORDING, request, OWNER);
    assertThat(retry.created()).isFalse();
    assertThat(retry.response().runId()).isEqualTo(first.response().runId());
    assertThat(counts()).containsExactly(2L, 1L, 1L, 1L);
    verify(converter, times(2)).convert(eq(SESSION), eq(0L), eq(0L), any());
    var conflict =
        assertThrows(
            ReplayException.class,
            () ->
                service.create(
                    RECORDING, request("matching-preview-key", "sha256:" + "f".repeat(64)), OWNER));
    assertThat(conflict.getErrorCode()).isEqualTo(ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT);
    verifyNoMoreInteractions(converter);
    assertSourceUnchanged();
  }

  @Test
  void refusedCreateRollsBackChildRepairWithoutDeletingExistingImmutableRows() {
    var existing = compiler.compile(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    assertThat(counts()).containsExactly(2L, 1L, 1L, 0L);
    jdbc.update(
        "DELETE FROM curated_actions WHERE curation_id=?", existing.manifest().getCurationId());
    // First prove that this exact persisted conversion repairs a missing child on commit.
    compiler.compile(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    assertThat(counts()).containsExactly(2L, 1L, 1L, 0L);
    jdbc.update(
        "DELETE FROM curated_actions WHERE curation_id=?", existing.manifest().getCurationId());
    var error =
        assertThrows(
            ReplayException.class,
            () ->
                service.create(
                    RECORDING, request("repair-stale-key", "sha256:" + "f".repeat(64)), OWNER));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.CHECKSUM_MISMATCH);
    assertThat(counts()).containsExactly(2L, 0L, 1L, 0L);
    var retained =
        context
            .getBean(ScenarioManifestRepository.class)
            .findById(existing.manifest().getId())
            .orElseThrow();
    assertThat(retained.getManifestJson()).isEqualTo(existing.manifest().getManifestJson());
    assertThat(retained.getManifestChecksum()).isEqualTo(existing.manifest().getManifestChecksum());
    assertSourceUnchanged();
  }

  @Test
  void canonicalJsonbComparisonStillRejectsChangedContentWithAnUnchangedChecksum() {
    var existing = compiler.compile(RECORDING, ConversionMode.AUTO, source.getId(), OWNER);
    jdbc.update(
        "UPDATE recording_curations SET content_json=jsonb_set(content_json,"
            + " '{actions,0,envelope,arguments,option}', '\"bait\"'::jsonb) WHERE id=?",
        existing.manifest().getCurationId());
    var error =
        assertThrows(
            ReplayException.class,
            () -> compiler.compile(RECORDING, ConversionMode.AUTO, source.getId(), OWNER));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.CURATION_REQUIRED);
    assertThat(counts()).containsExactly(2L, 1L, 1L, 0L);
    assertThat(
            jdbc.queryForObject(
                "SELECT content_json #>> '{actions,0,envelope,arguments,option}' FROM"
                    + " recording_curations WHERE id=?",
                String.class,
                existing.manifest().getCurationId()))
        .isEqualTo("bait");
    assertSourceUnchanged();
  }

  @Test
  void foreignPublishedSourceIsRejectedWithoutAnyPersistenceSideEffects() {
    long beforeInserts = insertCount();
    var error =
        assertThrows(
            ReplayException.class,
            () -> service.create(RECORDING, request("foreign-source-key", null), "another-owner"));
    assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.CURATION_NOT_PUBLISHED);
    assertThat(insertCount()).isEqualTo(beforeInserts);
    assertThat(counts()).containsExactly(1L, 0L, 0L, 0L);
    verifyNoInteractions(converter);
    assertSourceUnchanged();
  }

  @Test
  void preflightIsZeroWriteAndSurvivesAServiceRestart() {
    // Row 5 uses an incomplete stream: playback/import stays available, causal simulation refuses.
    when(converter.convert(eq(SESSION), eq(0L), eq(0L), any())).thenReturn(conversion(true));
    ReplayV2PreflightService before = context.getBean(ReplayV2PreflightService.class);
    var first = before.preflight(RECORDING, ConversionMode.CAPTURED_ONLY, source.getId(), OWNER);
    List<Long> baseline = counts();
    assertThat(first.simulationEligible()).isFalse();
    assertThat(first.playbackEligible()).isTrue();
    assertThat(first.blockingReasons()).isNotEmpty();

    context.close();
    openContext();

    // The restarted context owns fresh mocks; re-seed only the conversion evidence it needs.
    ReplayActionConversionService restartedConverter =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
            (ReplayActionConversionService) context.getBean(ReplayActionConversionService.class));
    when(restartedConverter.convert(eq(SESSION), eq(0L), eq(0L), any()))
        .thenReturn(conversion(true));

    ReplayV2PreflightService after = context.getBean(ReplayV2PreflightService.class);
    var second = after.preflight(RECORDING, ConversionMode.CAPTURED_ONLY, source.getId(), OWNER);
    assertThat(second.simulationEligible()).isFalse();
    assertThat(second.blockingReasons())
        .extracting(reason -> reason.code().name())
        .containsExactlyElementsOf(
            first.blockingReasons().stream().map(reason -> reason.code().name()).toList());
    assertThat(counts()).isEqualTo(baseline);
  }

  private CreateXrspsRunRequest request(String key, String expected) {
    return new CreateXrspsRunRequest(
        ConversionMode.AUTO, false, ReplayStopPolicy.RUN_TO_END, key, source.getId(), expected);
  }

  private List<Long> counts() {
    return List.of("recording_curations", "curated_actions", "scenario_manifests", "replay_runs")
        .stream()
        .map(table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
        .toList();
  }

  private long insertCount() {
    return context
        .getBean(EntityManagerFactory.class)
        .unwrap(SessionFactory.class)
        .getStatistics()
        .getEntityInsertCount();
  }

  private void assertSourceUnchanged() {
    var retained =
        context.getBean(RecordingCurationRepository.class).findById(source.getId()).orElseThrow();
    assertThat(retained.getContentJson()).isEqualTo(source.getContentJson());
    assertThat(retained.getContentChecksum()).isEqualTo(source.getContentChecksum());
    assertThat(retained.getRole()).isEqualTo(CurationRole.HELD_OUT);
    assertThat(retained.getCreatedBy()).isEqualTo(OWNER);
  }

  private static ReplayActionConversionResult conversion(boolean incomplete) {
    var action = new RecordingAction();
    action.setSchema(RecordingAction.SCHEMA);
    action.setSessionId(SESSION);
    action.setTick(0);
    action.setSequence(0);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", "authored-fishing"));
    action.setArguments(Map.of("option", "net"));
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-10-15T00:00:00Z");
    return new ReplayActionConversionResult(
        SESSION,
        0,
        0,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        List.of(
            new ConvertedReplayAction(
                action, ActionProvenance.CAPTURED, "authored-transaction-test", null, null)),
        incomplete
            ? List.of(
                new ReplayGap(
                    0,
                    "TYPED_ACTION_STREAM_INCOMPLETE",
                    "authored missing action",
                    ActionProvenance.DEFAULTED,
                    null))
            : List.of(),
        List.of(),
        1,
        1,
        100.0,
        "sha256:" + (incomplete ? "e" : "d").repeat(64));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackages = "com.chromascape.web.replay")
  static class Persistence {
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("com.chromascape.web.replay");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(
          Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.generate_statistics", "true"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    ReplayRecordingResolver resolver() {
      final var resolver = mock(ReplayRecordingResolver.class);
      var session = new DebugSession();
      session.setSessionId(SESSION);
      session.setStartTime(LocalDateTime.of(2026, 10, 15, 0, 0));
      session.setEndTime(session.getStartTime().plusMinutes(1));
      ObjectNode identity = JSON.createObjectNode();
      identity
          .put("tickBatchVersion", 3)
          .putObject("client")
          .put("schema", "debugger-client-identity/v1")
          .put("clientBuildId", "authored-client");
      session.bindCaptureIdentity(identity);
      var recording = new RecordingSession();
      recording.setRecordingId(RECORDING);
      ObjectNode runContext = JSON.createObjectNode();
      runContext
          .put("schemaVersion", 1)
          .put("runId", UUID.randomUUID().toString())
          .put("taskKey", "FISHING_TRAINING")
          .put("behaviorProfileId", "authored-fishing")
          .put("behaviorProfileVersion", 1)
          .put("semanticContractDigest", "sha256:" + "a".repeat(64))
          .put("settingsHash", "sha256:" + "b".repeat(64))
          .put("graphRevision", "sha256:" + "c".repeat(64));
      recording.bindRunContext(
          new BehaviorRunContextService(null, null, null, null, JSON)
              .bind(runContext, RECORDING, SESSION),
          "CAPTURED");
      when(resolver.resolve(RECORDING))
          .thenReturn(new ReplayRecordingResolver.ResolvedRecording(recording, session));
      return resolver;
    }

    @Bean
    TickSnapshotRepository snapshots() {
      final var repository = mock(TickSnapshotRepository.class);
      ObjectNode player = JSON.createObjectNode();
      player
          .put("worldId", 301)
          .put("regionId", 12850)
          .put("collisionRevision", "sha256:" + "c".repeat(64));
      player.putObject("position").put("x", 3080).put("y", 3227).put("plane", 0);
      player.putObject("skills").putObject("fishing").put("level", 20).put("xp", 4470);
      var snapshot = new TickSnapshot(SESSION, 0L, LocalDateTime.of(2026, 10, 15, 0, 0), player);
      snapshot.setSchemaVersion(1);
      snapshot.setInventory(JSON.createArrayNode());
      snapshot.setEquipment(JSON.createArrayNode());
      var npcs = JSON.createArrayNode();
      npcs.addObject()
          .put("stableId", "authored-fishing")
          .putObject("position")
          .put("x", 3081)
          .put("y", 3227)
          .put("plane", 0);
      snapshot.setNpcs(npcs);
      snapshot.setGameObjects(JSON.createArrayNode());
      snapshot.setGroundItems(JSON.createArrayNode());
      when(repository.findMinTickBySessionId(SESSION)).thenReturn(Optional.of(0L));
      when(repository.findMaxTickBySessionId(SESSION)).thenReturn(Optional.of(0L));
      when(repository.findBySessionIdAndTickBetweenOrderByTick(SESSION, 0L, 0L))
          .thenReturn(List.of(snapshot));
      return repository;
    }

    @Bean
    ReplayActionConversionService converter() {
      return mock(ReplayActionConversionService.class);
    }

    @Bean
    ReplayCapabilityService capability() {
      var service = new ReplayCapabilityService();
      service.updateFromHandshake(
          CapabilityManifest.current(
              new CapabilityManifest.CapabilityModes(false, false, true),
              new CapabilityManifest.XrspsIdentity(
                  "0.1.0", "a".repeat(40), "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64)),
              false));
      return service;
    }

    @Bean
    ScenarioManifestV2Compiler compiler(
        ReplayRecordingResolver resolver,
        TickSnapshotRepository snapshots,
        ReplayActionConversionService converter,
        RecordingCurationRepository curations,
        CuratedActionRepository actions,
        ScenarioManifestRepository manifests,
        ReplayCapabilityService capability) {
      return new ScenarioManifestV2Compiler(
          resolver,
          snapshots,
          converter,
          curations,
          actions,
          manifests,
          capability,
          CANONICAL,
          JSON);
    }

    @Bean
    ReplayRunService runs(
        ReplayRunRepository runs,
        ReplayAttemptRepository attempts,
        ReplayCheckpointRepository checkpoints,
        ReplayEventRepository events,
        ReplayReportRepository reports,
        ScenarioManifestRepository manifests) {
      return new ReplayRunService(
          runs, attempts, checkpoints, events, reports, manifests, new MockEnvironment());
    }

    @Bean
    ReplayV2PreflightService preflight(
        ReplayRecordingResolver resolver,
        TickSnapshotRepository snapshots,
        ReplayActionConversionService converter,
        ReplayCapabilityService capability,
        ScenarioManifestV2Compiler compiler) {
      var integrity = mock(RecordingIntegrityService.class);
      when(integrity.evaluate(any()))
          .thenReturn(new ReplayEligibility(true, true, false, List.of()));
      return new ReplayV2PreflightService(
          resolver, integrity, snapshots, converter, capability, compiler);
    }

    @Bean
    ReplayProperties properties() {
      var properties = new ReplayProperties();
      properties.setEnabled(true);
      properties.getArtifacts().setRoot(artifactRoot.toString());
      return properties;
    }

    @Bean
    ReplayArtifactService artifacts(ArtifactRefRepository repository, ReplayProperties properties) {
      return new ReplayArtifactService(repository, properties);
    }

    @Bean
    ReplayV2RunService service(
        ScenarioManifestV2Compiler compiler,
        ReplayRunService runs,
        ScenarioManifestRepository manifests,
        ArtifactRefRepository artifacts,
        ReplayReportRepository reports,
        ReplayMismatchRepository mismatches,
        StateHashSampleRepository hashes,
        ReplayV2PreflightService preflight,
        ReplayArtifactService artifactService,
        ReplayProperties properties) {
      return new ReplayV2RunService(
          preflight,
          compiler,
          runs,
          manifests,
          mock(ReplayViewerService.class),
          artifacts,
          artifactService,
          reports,
          mismatches,
          hashes,
          CANONICAL,
          JSON,
          properties);
    }
  }
}
