package com.chromascape.web.accounttask.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class StabilizationAuthConfigTest {

  @Test
  @DisplayName("the filter registration covers the entire stabilization surface and runs early")
  void filterRegistrationCoversEntireStabilizationSurface() {
    StabilizationAuthFilter filter = new StabilizationAuthFilter("token", "", 5_000_000L);
    StabilizationAuthConfig config = new StabilizationAuthConfig(filter);

    FilterRegistrationBean<StabilizationAuthFilter> registration =
        config.stabilizationAuthFilterRegistration();

    assertThat(registration.getFilter()).isSameAs(filter);
    assertThat(registration.getUrlPatterns()).containsExactly("/api/account-tasks/stabilization/*");
    assertThat(registration.getOrder()).isEqualTo(1);
  }

  @Test
  @DisplayName("the registration url pattern covers every exposed stabilization endpoint")
  void registrationPatternMatchesEveryEndpoint() {
    // Servlet url pattern semantics: /api/account-tasks/stabilization/* covers every subpath.
    String pattern = "/api/account-tasks/stabilization/*";
    for (String path : java.util.List.of(
        "/api/account-tasks/stabilization/report-bundles",
        "/api/account-tasks/stabilization/harness-manifest",
        "/api/account-tasks/stabilization/reliability-matrix",
        "/api/account-tasks/stabilization/runs/run-1",
        "/api/account-tasks/stabilization/runs/run-1/attempts",
        "/api/account-tasks/stabilization/runs/run-1/attempts/attempt-1",
        "/api/account-tasks/stabilization/runs/run-1/transitions",
        "/api/account-tasks/stabilization/runs/run-1/evidence",
        "/api/account-tasks/stabilization/matrices/sha256:abc",
        "/api/account-tasks/stabilization/artifacts/abc",
        "/api/account-tasks/stabilization/ingest-report",
        "/api/account-tasks/stabilization/kpi",
        "/api/account-tasks/stabilization/triage",
        "/api/account-tasks/stabilization/triage/42/resolve",
        "/api/account-tasks/stabilization/catalog")) {
      String base = "/api/account-tasks/stabilization/";
      assertThat(path.startsWith(base))
          .as(path)
          .isTrue();
      assertThat(path.length()).isGreaterThan(base.length());
    }
  }
}
