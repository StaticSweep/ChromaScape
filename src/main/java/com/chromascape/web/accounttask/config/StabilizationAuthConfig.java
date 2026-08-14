package com.chromascape.web.accounttask.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the stabilization authorization interceptor on the entire stabilization surface: {@code
 * /api/account-tasks/stabilization/**}. Every endpoint is protected by default — {@code
 * /harness-manifest}, {@code /reliability-matrix}, {@code /runs/**}, {@code /matrices/**}, {@code
 * /ingest-report}, {@code /kpi}, {@code /triage}, {@code /triage/{id}/resolve} and {@code /catalog}
 * — so no stabilization endpoint is ever reachable without an explicit authorization decision.
 */
@Configuration
public class StabilizationAuthConfig implements WebMvcConfigurer {

  private final StabilizationAuthInterceptor interceptor;

  public StabilizationAuthConfig(StabilizationAuthInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(interceptor)
        .addPathPatterns(List.of("/api/account-tasks/stabilization/**"));
  }
}
