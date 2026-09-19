package com.chromascape.web.accounttask.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.controller.StabilizationController;
import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Authorization coverage for every stabilization endpoint through the real interceptor (SC-572).
 * Each endpoint is exercised with a valid token, an invalid token, a missing token (token
 * configured), a non-loopback request with no configured token, a loopback development request,
 * and — for the ingestion endpoints — an oversized body.
 */
@ExtendWith(MockitoExtension.class)
class StabilizationEndpointAuthTest {

  private static final String TOKEN = "stabilization-read-token";
  private static final String WRITE_TOKEN = "stabilization-write-token";

  private final ObjectMapper mapper = new ObjectMapper();

  @Mock private StabilizationIngestionService ingestionService;
  @Mock private Micro6ExecutorCatalogService catalogService;
  @Mock private StabilizationArtifactStore artifactStore;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() throws Exception {
    StabilizationController controller = new StabilizationController(ingestionService, catalogService, artifactStore);
    StabilizationAuthFilter interceptor =
        new StabilizationAuthFilter(TOKEN, WRITE_TOKEN, 1024L);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addFilters(interceptor)
            .build();
    lenient().when(ingestionService.getRunByRunId(any())).thenReturn(Optional.empty());
    lenient().when(ingestionService.getRunByRunIdAndAttemptId(any(), any())).thenReturn(Optional.empty());
    lenient().when(ingestionService.getRunAttempts(any())).thenReturn(List.of(new ScriptStabilizationRun()));
    lenient().when(ingestionService.getMatrixDocumentByHash(any())).thenReturn(Optional.empty());
    lenient().when(ingestionService.computeKpis()).thenReturn(Map.of("window_24h_count", 0));
    lenient().when(ingestionService.listOpenTriage()).thenReturn(List.of(new ScriptFailureTriage()));
    lenient().when(ingestionService.resolveTriage(42L)).thenReturn(new ScriptFailureTriage());
    lenient().when(ingestionService.ingestReport(any())).thenReturn(Map.of("reportId", "r"));
    lenient().when(ingestionService.ingestHarnessManifest(any())).thenReturn(Map.of("runId", "run"));
    lenient().when(ingestionService.ingestReliabilityMatrix(any())).thenReturn(Map.of("matrixHash", "m"));
    lenient().when(catalogService.getCatalog(false)).thenReturn(mapper.createObjectNode());
  }

  private record Endpoint(String name, MockHttpServletRequestBuilder request) {
  }

  private List<Endpoint> endpoints() throws Exception {
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema_version", "harness-run-manifest/v1");
    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "reliability-matrix/v1");
    ObjectNode report = mapper.createObjectNode();
    report.put("report_id", "r");
    return List.of(
        new Endpoint("POST /ingest-report", MockMvcRequestBuilders.post(
            "/api/account-tasks/stabilization/ingest-report")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(report))),
        new Endpoint("POST /harness-manifest", MockMvcRequestBuilders.post(
            "/api/account-tasks/stabilization/harness-manifest")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(manifest))),
        new Endpoint("POST /reliability-matrix", MockMvcRequestBuilders.post(
            "/api/account-tasks/stabilization/reliability-matrix")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(matrix))),
        new Endpoint("GET /runs/{runId}", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/runs/run-1")),
        new Endpoint("GET /runs/{runId}/attempts", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/runs/run-1/attempts")),
        new Endpoint("GET /runs/{runId}/attempts/{attemptId}", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/runs/run-1/attempts/attempt-1")),
        new Endpoint("GET /matrices/{matrixHash}", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/matrices/sha256:abc")),
        new Endpoint("GET /kpi", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/kpi")),
        new Endpoint("GET /triage", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/triage")),
        new Endpoint("POST /triage/{id}/resolve", MockMvcRequestBuilders.post(
            "/api/account-tasks/stabilization/triage/42/resolve")),
        new Endpoint("GET /catalog", MockMvcRequestBuilders.get(
            "/api/account-tasks/stabilization/catalog")));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String addr) {
    return request -> {
      request.setRemoteAddr(addr);
      return request;
    };
  }

  @Test
  @DisplayName("every endpoint accepts a valid read token (write endpoints need the write token)")
  void validTokenAcceptedOnEveryEndpoint() throws Exception {
    for (Endpoint endpoint : endpoints()) {
      MvcResult result = mockMvc.perform(endpoint.request()
          .header("X-Stabilization-Auth",
              endpoint.name().startsWith("POST ") ? WRITE_TOKEN : TOKEN))
          .andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isNotEqualTo(401);
    }
  }

  @Test
  @DisplayName("every endpoint rejects an invalid token")
  void invalidTokenRejectedOnEveryEndpoint() throws Exception {
    for (Endpoint endpoint : endpoints()) {
      MvcResult result = mockMvc.perform(endpoint.request()
          .header("X-Stabilization-Auth", "wrong"))
          .andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("every endpoint rejects a missing token when a token is configured")
  void missingTokenRejectedOnEveryEndpoint() throws Exception {
    for (Endpoint endpoint : endpoints()) {
      MvcResult result = mockMvc.perform(endpoint.request()).andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("every endpoint rejects non-loopback requests when no token is configured")
  void nonLoopbackRejectedWithoutConfiguredToken() throws Exception {
    StabilizationController controller = new StabilizationController(ingestionService, catalogService, artifactStore);
    MockMvc noTokenMockMvc = MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new StabilizationAuthFilter("", "", 1024L))
        .build();
    for (Endpoint endpoint : endpoints()) {
      MvcResult result = noTokenMockMvc.perform(endpoint.request()
          .with(remoteAddr("10.0.0.7")))
          .andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isEqualTo(401);
    }
  }

  @Test
  @DisplayName("every endpoint permits loopback development requests when no token is configured")
  void loopbackDevRequestPermittedOnEveryEndpoint() throws Exception {
    StabilizationController controller = new StabilizationController(ingestionService, catalogService, artifactStore);
    MockMvc noTokenMockMvc = MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new StabilizationAuthFilter("", "", 1024L))
        .build();
    for (Endpoint endpoint : endpoints()) {
      MvcResult result = noTokenMockMvc.perform(endpoint.request()
          .with(remoteAddr("127.0.0.1")))
          .andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isNotEqualTo(401);
    }
  }

  @Test
  @DisplayName("oversized bodies on the ingestion endpoints are rejected with 413")
  void oversizedBodyRejectedOnIngestionEndpoints() throws Exception {
    byte[] oversized = new byte[2048];
    java.util.Arrays.fill(oversized, (byte) 'x');
    for (Endpoint endpoint : endpoints()) {
      if (!endpoint.name().startsWith("POST /")) {
        continue;
      }
      // The size guard is installed only after authorization succeeds: a POST requires the write
      // token, and only then is the oversized body rejected with 413.
      MvcResult result = mockMvc.perform(endpoint.request()
          .header("X-Stabilization-Auth", WRITE_TOKEN)
          .contentType(MediaType.APPLICATION_JSON)
          .content(oversized))
          .andReturn();
      assertThat(result.getResponse().getStatus())
          .as(endpoint.name())
          .isEqualTo(413);
    }
  }

  @Test
  @DisplayName("triage resolution with only the read token is rejected")
  void triageResolveWithReadTokenRejected() throws Exception {
    MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(
        "/api/account-tasks/stabilization/triage/42/resolve")
        .header("X-Stabilization-Auth", TOKEN))
        .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
  }
}
