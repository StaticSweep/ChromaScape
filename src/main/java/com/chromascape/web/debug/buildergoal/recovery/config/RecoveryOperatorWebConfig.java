package com.chromascape.web.debug.buildergoal.recovery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the recovery operator Bearer-token interceptor on the compile-recovery mutation
 * surface. The interceptor only authenticates non-GET requests, so snapshot, artifact, and status
 * reads remain governed by the debugger's read policy.
 */
@Configuration
public class RecoveryOperatorWebConfig implements WebMvcConfigurer {

  private final RecoveryOperatorInterceptor interceptor;

  public RecoveryOperatorWebConfig(RecoveryOperatorInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(interceptor)
        .addPathPatterns("/api/debug/builder-goals/runs/*/compile-recovery/**");
  }
}
