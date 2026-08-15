package com.chromascape.web.accounttask.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StabilizationAuthFilterTest {

  private static final String TOKEN = "stabilization-read-token";
  private static final String WRITE_TOKEN = "stabilization-write-token";

  private MockHttpServletRequest request(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  private static MockHttpServletResponse handle(
      StabilizationAuthFilter filter, MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  @DisplayName("a valid token grants access")
  void validTokenGrantsAccess() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 5_000_000L);
    MockHttpServletRequest request = request("10.0.0.5");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(filter, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("an invalid token is rejected")
  void invalidTokenRejected() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 5_000_000L);
    MockHttpServletRequest request = request("127.0.0.1");
    request.addHeader("X-Stabilization-Auth", "wrong");

    MockHttpServletResponse response = handle(filter, request);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("unauthorized");
  }

  @Test
  @DisplayName("a missing token is rejected when a token is configured")
  void missingTokenRejectedWhenTokenConfigured() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 5_000_000L);

    MockHttpServletResponse response = handle(filter, request("127.0.0.1"));

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  @DisplayName("a non-loopback request is rejected when no token is configured")
  void nonLoopbackRejectedWithoutConfiguredToken() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter("", "", 5_000_000L);

    MockHttpServletResponse response = handle(filter, request("10.10.20.30"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("unauthorized_environment");
  }

  @Test
  @DisplayName("loopback development requests are permitted when no token is configured")
  void loopbackDevRequestPermitted() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter("", "", 5_000_000L);
    for (String addr : new String[] {"127.0.0.1", "127.8.1.9", "::1", "localhost"}) {
      MockHttpServletResponse response = handle(filter, request(addr));
      assertThat(response.getStatus()).as("loopback %s", addr).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("oversized request bodies are rejected with 413 before parsing")
  void oversizedBodyRejected() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 1024L);
    MockHttpServletRequest request = new MockHttpServletRequest() {
      @Override
      public long getContentLengthLong() {
        return 2048L;
      }
    };
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(filter, request);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("request_body_too_large");
  }

  @Test
  @DisplayName("triage resolution requires the write token, not the read token")
  void triageResolutionRequiresWriteToken() throws Exception {
    StabilizationAuthFilter filter =
        new StabilizationAuthFilter(TOKEN, WRITE_TOKEN, 5_000_000L);

    MockHttpServletRequest readOnly = request("127.0.0.1");
    readOnly.setMethod("POST");
    readOnly.addHeader("X-Stabilization-Auth", TOKEN);
    MockHttpServletResponse readResponse = handle(filter, readOnly);
    assertThat(readResponse.getStatus()).isEqualTo(401);

    MockHttpServletRequest write = request("127.0.0.1");
    write.setMethod("POST");
    write.addHeader("X-Stabilization-Auth", WRITE_TOKEN);
    MockHttpServletResponse writeResponse = handle(filter, write);
    assertThat(writeResponse.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("when no write token is configured the read token covers writes")
  void readTokenCoversWritesWhenNoWriteToken() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 5_000_000L);
    MockHttpServletRequest request = request("127.0.0.1");
    request.setMethod("POST");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(filter, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("blank tokens fail startup outside the explicit local-development profile")
  void blankProductionTokensFailStartup() {
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("production");

    assertThatThrownBy(() -> new StabilizationAuthFilter("", "", 5_000_000L, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tokens are required");
  }

  @Test
  @DisplayName("one blank production token also fails startup")
  void singleBlankProductionTokenFailsStartup() {
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("production");

    assertThatThrownBy(() -> new StabilizationAuthFilter("read-token", "", 5_000_000L, environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tokens are required");
  }

  @Test
  @DisplayName("the local-development profile does not require tokens")
  void localDevelopmentProfileAllowsTokenlessStartup() {
    org.springframework.core.env.StandardEnvironment environment =
        new org.springframework.core.env.StandardEnvironment();
    environment.setActiveProfiles("local-development");

    StabilizationAuthFilter filter = new StabilizationAuthFilter("", "", 5_000_000L, environment);

    assertThat(filter).isNotNull();
  }

  @Test
  @DisplayName("read-only GET endpoints never require the write token")
  void readOnlyEndpointsNeverRequireWriteToken() throws Exception {
    StabilizationAuthFilter filter =
        new StabilizationAuthFilter(TOKEN, WRITE_TOKEN, 5_000_000L);
    MockHttpServletRequest request = request("127.0.0.1");
    request.setMethod("GET");
    request.addHeader("X-Stabilization-Auth", TOKEN);

    MockHttpServletResponse response = handle(filter, request);

    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("repeated getInputStream() calls share one byte counter and never reset the bound")
  void repeatedGetInputStreamSharesOneCounter() throws Exception {
    // The bound is 1024 bytes; the body is 1536 bytes. Two getInputStream() handles must observe
    // the same running counter, so reading through the first handle and continuing through the
    // second must trip the bound instead of starting a fresh counter that could read the whole
    // body without a 413.
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 1024L);
    MockHttpServletRequest request = new MockHttpServletRequest() {
      @Override
      public long getContentLengthLong() {
        return -1L; // chunked-style: no known length, so only the streaming bound applies
      }
    };
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Stabilization-Auth", TOKEN);
    String body = "a".repeat(1536);
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest req,
          jakarta.servlet.ServletResponse res) throws java.io.IOException {
        // Two reads through two separate getInputStream() calls must observe the same counter.
        jakarta.servlet.ServletInputStream first = ((jakarta.servlet.http.HttpServletRequest) req)
            .getInputStream();
        byte[] buf = new byte[512];
        for (int i = 0; i < 2; i++) {
          int read = first.read(buf);
          if (read < 0) {
            break;
          }
        }
        jakarta.servlet.ServletInputStream second = ((jakarta.servlet.http.HttpServletRequest) req)
            .getInputStream();
        // Reading the remaining bytes via the second handle must continue the same counter and
        // trip the bound.
        int read;
        while ((read = second.read(buf)) > 0) {
          // consume
        }
      }
    });

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("request_body_too_large");
  }

  @Test
  @DisplayName("isFinished() reflects EOF, not quota exhaustion")
  void isFinishedReflectsEofNotQuota() throws Exception {
    StabilizationAuthFilter filter = new StabilizationAuthFilter(TOKEN, "", 10L);
    MockHttpServletRequest request = new MockHttpServletRequest() {
      @Override
      public long getContentLengthLong() {
        return -1L;
      }
    };
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Stabilization-Auth", TOKEN);
    request.setContent("0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain() {
      @Override
      public void doFilter(jakarta.servlet.ServletRequest req,
          jakarta.servlet.ServletResponse res) throws java.io.IOException {
        jakarta.servlet.ServletInputStream stream =
            ((jakarta.servlet.http.HttpServletRequest) req).getInputStream();
        // Exactly the bound: quota reached but EOF not yet observed -> must NOT be finished.
        for (int i = 0; i < 10; i++) {
          assertThat(stream.isFinished()).as("before byte %d", i).isFalse();
          int value = stream.read();
          assertThat(value).isNotEqualTo(-1);
        }
        assertThat(stream.isFinished()).as("quota reached, EOF not yet observed").isFalse();
        assertThat(stream.read()).as("end of body").isEqualTo(-1);
        assertThat(stream.isFinished()).as("after EOF").isTrue();
      }
    });

    assertThat(response.getStatus()).isEqualTo(200);
  }
}
