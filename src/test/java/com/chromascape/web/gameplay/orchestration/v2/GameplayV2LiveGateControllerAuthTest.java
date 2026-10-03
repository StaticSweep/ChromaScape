package com.chromascape.web.gameplay.orchestration.v2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Operator authentication and the closed start contract for the SC-1449 live gate.
 *
 * <p>The controller used to accept any non-empty {@code X-Gameplay-Operator-Key}, which meant the
 * single character {@code "x"} was enough to reach live gameplay once the flag was on. These tests
 * pin the four cases that matter — missing, blank, wrong and valid — plus the fail-closed behaviour
 * when no secret is configured at all, and they assert that no service call happens on a rejected
 * request.
 */
class GameplayV2LiveGateControllerAuthTest {

  private static final String HEADER = "X-Gameplay-Operator-Key";
  private static final String SECRET = "sc1449-operator-secret-value-0123456789";

  private GameplayV2LiveGateService service;

  private MockMvc mvc(String configuredKey, boolean liveEnabled) {
    service = mock(GameplayV2LiveGateService.class);
    return MockMvcBuilders.standaloneSetup(
            new GameplayV2LiveGateController(
                service, new GameplayV2OperatorAuthenticator(configuredKey), liveEnabled))
        .build();
  }

  @Test
  void missingOperatorKeyIsRejectedAndReachesNoService() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_AUTHENTICATION_FAILED"));

    verifyNoInteractions(service);
  }

  @Test
  void blankOperatorKeyIsRejectedAndReachesNoService() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, "   "))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_AUTHENTICATION_FAILED"));

    verifyNoInteractions(service);
  }

  @Test
  void wrongOperatorKeyIsRejectedAndReachesNoService() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    // The single-character key that the header-presence check used to accept.
    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, "x"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_AUTHENTICATION_FAILED"));

    // A near-miss of the real secret is rejected just the same.
    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, SECRET + "X"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/internal/gameplay/v2/status/req-1")
                .header(HEADER, SECRET.substring(0, SECRET.length() - 1)))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(service);
  }

  @Test
  void validOperatorKeyReachesTheService() throws Exception {
    MockMvc mvc = mvc(SECRET, true);
    when(service.status("req-1"))
        .thenReturn(
            new V2StatusResult(V2State.ACTIVE, null, false, "req-1", "digest-1", List.of()));

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, SECRET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.requestId").value("req-1"));

    verify(service).status("req-1");
  }

  @Test
  void anUnconfiguredSecretClosesTheBoundaryEntirely() throws Exception {
    MockMvc mvc = mvc("", true);

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, ""))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_KEY_NOT_CONFIGURED"));

    verifyNoInteractions(service);
  }

  @Test
  void aTooShortSecretClosesTheBoundaryEntirely() throws Exception {
    // A short secret is not a secret. Presenting it exactly must still fail.
    MockMvc mvc = mvc("short-key", true);

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, "short-key"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_KEY_NOT_CONFIGURED"));

    verifyNoInteractions(service);
  }

  @Test
  void authenticationHappensBeforeTheLiveFlagIsConsulted() throws Exception {
    // A disabled gate must not become an oracle that tells an unauthenticated caller the feature
    // exists and is merely off.
    MockMvc mvc = mvc(SECRET, false);

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, "x"))
        .andExpect(status().isUnauthorized());

    mvc.perform(get("/api/internal/gameplay/v2/status/req-1").header(HEADER, SECRET))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.failureCode").value("V2_DISABLED"));

    verifyNoInteractions(service);
  }

  @Test
  void startTakesTheClosedStartRequestAndNeverTheBundle() throws Exception {
    MockMvc mvc = mvc(SECRET, true);
    when(service.start("req-1", 7L, 9L))
        .thenReturn(
            new V2StartResult(V2State.STARTING, null, false, "req-1", "digest-1", List.of()));

    mvc.perform(
            post("/api/internal/gameplay/v2/start")
                .header(HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-1\",\"leaseGeneration\":7,\"fence\":9}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("STARTING"));

    verify(service).start("req-1", 7L, 9L);
  }

  @Test
  void startWithoutARequestIdIsRejectedBeforeTheService() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    mvc.perform(
            post("/api/internal/gameplay/v2/start")
                .header(HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"leaseGeneration\":7,\"fence\":9}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.failureCode").value("BAD_REQUEST"));

    mvc.perform(
            post("/api/internal/gameplay/v2/start")
                .header(HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"   \",\"leaseGeneration\":7,\"fence\":9}"))
        .andExpect(status().isBadRequest());

    verify(service, never()).start(anyString(), anyLong(), anyLong());
  }

  @Test
  void startWithoutAuthenticationNeverStarts() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    mvc.perform(
            post("/api/internal/gameplay/v2/start")
                .header(HEADER, "x")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-1\",\"leaseGeneration\":7,\"fence\":9}"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).start(anyString(), anyLong(), anyLong());
  }

  @Test
  void reserveAndStopAlsoRequireAuthentication() throws Exception {
    MockMvc mvc = mvc(SECRET, true);

    mvc.perform(
            post("/api/internal/gameplay/v2/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(
            post("/api/internal/gameplay/v2/stop")
                .header(HEADER, "x")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-1\",\"reason\":\"operator\"}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(post("/api/internal/gameplay/v2/reconcile/req-1"))
        .andExpect(status().isUnauthorized());

    verify(service, never()).reserve(any());
    verify(service, never()).stop(anyString(), anyString(), anyBoolean());
    verify(service, never()).reconcile(anyString());
  }
}
