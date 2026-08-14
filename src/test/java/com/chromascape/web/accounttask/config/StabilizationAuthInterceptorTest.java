package com.chromascape.web.accounttask.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StabilizationAuthInterceptorTest {

  private static final String TOKEN = "stabilization-read-token";
  private static final String WRITE_TOKEN = "stabilization-write-token";

  private MockHttpServletRequest request(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  private static MockHttpServletResponse handle(
      StabilizationAuthInterceptor interceptor, MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean allowed = interceptor.preHandle(request, response, new Object());
    assertThat(allowed).isEqualTo(response.getStatus() < 400);
    return response;
  }

  @Test
  @DisplayName("a valid token grants access")
  void validTokenGrantsAccess() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor(TOKEN);
    MockHttpServletRequest request = request("10.0.0.5");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(interceptor, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("an invalid token is rejected")
  void invalidTokenRejected() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor(TOKEN);
    MockHttpServletRequest request = request("127.0.0.1");
    request.addHeader("X-Stabilization-Auth", "wrong");

    MockHttpServletResponse response = handle(interceptor, request);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("unauthorized");
  }

  @Test
  @DisplayName("a missing token is rejected when a token is configured")
  void missingTokenRejectedWhenTokenConfigured() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor(TOKEN);

    MockHttpServletResponse response = handle(interceptor, request("127.0.0.1"));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a non-loopback request is rejected when no token is configured")
  void nonLoopbackRejectedWithoutConfiguredToken() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor("");

    MockHttpServletResponse response = handle(interceptor, request("10.10.20.30"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("unauthorized_environment");
  }

  @Test
  @DisplayName("loopback development requests are permitted when no token is configured")
  void loopbackDevRequestPermitted() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor("");
    for (String addr : new String[] {"127.0.0.1", "127.8.1.9", "::1", "localhost"}) {
      MockHttpServletResponse response = handle(interceptor, request(addr));
      assertThat(response.getStatus()).as("loopback %s", addr).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("oversized request bodies are rejected with 413 before parsing")
  void oversizedBodyRejected() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor(TOKEN, "", 1024L);
    MockHttpServletRequest request = request("127.0.0.1");
    request.setContent(new byte[2048]);
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(interceptor, request);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("request_body_too_large");
  }

  @Test
  @DisplayName("triage resolution requires the write token, not the read token")
  void triageResolutionRequiresWriteToken() throws Exception {
    StabilizationAuthInterceptor interceptor =
        new StabilizationAuthInterceptor(TOKEN, WRITE_TOKEN, 5_000_000L);

    MockHttpServletRequest readOnly = request("127.0.0.1");
    readOnly.setMethod("POST");
    readOnly.setRequestURI("/api/account-tasks/stabilization/triage/42/resolve");
    readOnly.addHeader("X-Stabilization-Auth", TOKEN);
    MockHttpServletResponse readResponse = handle(interceptor, readOnly);
    assertThat(readResponse.getStatus()).isEqualTo(401);

    MockHttpServletRequest write = request("127.0.0.1");
    write.setMethod("POST");
    write.setRequestURI("/api/account-tasks/stabilization/triage/42/resolve");
    write.addHeader("X-Stabilization-Auth", WRITE_TOKEN);
    MockHttpServletResponse writeResponse = handle(interceptor, write);
    assertThat(writeResponse.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("when no write token is configured the read token covers writes")
  void readTokenCoversWritesWhenNoWriteToken() throws Exception {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor(TOKEN);
    MockHttpServletRequest request = request("127.0.0.1");
    request.setMethod("POST");
    request.setRequestURI("/api/account-tasks/stabilization/triage/42/resolve");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(interceptor, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("read-only GET endpoints never require the write token")
  void readOnlyEndpointsNeverRequireWriteToken() throws Exception {
    StabilizationAuthInterceptor interceptor =
        new StabilizationAuthInterceptor(TOKEN, WRITE_TOKEN, 5_000_000L);
    MockHttpServletRequest request = request("127.0.0.1");
    request.setMethod("GET");
    request.setRequestURI("/api/account-tasks/stabilization/kpi");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(interceptor, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
