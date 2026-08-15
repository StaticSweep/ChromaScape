package com.chromascape.web.accounttask.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the stabilization authorization filter on the entire stabilization surface:
 * {@code /api/account-tasks/stabilization/*}. A request filter authenticates before multipart
 * parsing so no stabilization endpoint — {@code /harness-manifest},
 * {@code /reliability-matrix}, {@code /report-bundles}, {@code /runs/**}, {@code /matrices/**},
 * {@code /ingest-report}, {@code /kpi}, {@code /triage}, {@code /triage/{id}/resolve} or
 * {@code /catalog} — is ever parsed or served without an explicit authorization decision.
 */
@Configuration
public class StabilizationAuthConfig {

  private final StabilizationAuthFilter filter;

  public StabilizationAuthConfig(StabilizationAuthFilter filter) {
    this.filter = filter;
  }

  @Bean
  public FilterRegistrationBean<StabilizationAuthFilter> stabilizationAuthFilterRegistration() {
    FilterRegistrationBean<StabilizationAuthFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.addUrlPatterns("/api/account-tasks/stabilization/*");
    registration.setName("stabilizationAuthFilter");
    registration.setOrder(1);
    return registration;
  }
}
