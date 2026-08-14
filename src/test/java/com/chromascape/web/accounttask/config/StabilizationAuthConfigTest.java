package com.chromascape.web.accounttask.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class StabilizationAuthConfigTest {

  @Test
  @DisplayName("the interceptor covers the entire stabilization surface by default")
  void interceptorCoversEntireStabilizationSurface() {
    StabilizationAuthInterceptor interceptor = new StabilizationAuthInterceptor("token");
    StabilizationAuthConfig config = new StabilizationAuthConfig(interceptor);

    InterceptorRegistry registry = mock(InterceptorRegistry.class);
    InterceptorRegistration registration = mock(InterceptorRegistration.class);
    when(registry.addInterceptor(any())).thenReturn(registration);

    config.addInterceptors(registry);

    verify(registry).addInterceptor(interceptor);
    verify(registration).addPathPatterns(List.of("/api/account-tasks/stabilization/**"));
  }

  @Test
  @DisplayName("the wildcard pattern matches every exposed stabilization endpoint")
  void wildcardMatchesEveryEndpoint() {
    AntPathMatcher matcher = new AntPathMatcher();
    for (String path :
        List.of(
            "/api/account-tasks/stabilization/harness-manifest",
            "/api/account-tasks/stabilization/reliability-matrix",
            "/api/account-tasks/stabilization/runs/run-1",
            "/api/account-tasks/stabilization/runs/run-1/attempts",
            "/api/account-tasks/stabilization/runs/run-1/attempts/attempt-1",
            "/api/account-tasks/stabilization/matrices/sha256:abc",
            "/api/account-tasks/stabilization/ingest-report",
            "/api/account-tasks/stabilization/kpi",
            "/api/account-tasks/stabilization/triage",
            "/api/account-tasks/stabilization/triage/42/resolve",
            "/api/account-tasks/stabilization/catalog")) {
      assertThat(matcher.match("/api/account-tasks/stabilization/**", path)).as(path).isTrue();
    }
  }
}
