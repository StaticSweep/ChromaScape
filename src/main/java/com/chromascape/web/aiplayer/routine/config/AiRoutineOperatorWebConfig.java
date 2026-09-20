package com.chromascape.web.aiplayer.routine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the operator Bearer-token interceptor on every AI-routine mutation endpoint. The
 * interceptor itself authenticates only non-GET/HEAD/OPTIONS requests, so read-only GET/SSE
 * endpoints remain unauthenticated. Capture endpoints ({@code /capture/**}) are excluded here and
 * keep their separate capture token.
 */
@Configuration
public class AiRoutineOperatorWebConfig implements WebMvcConfigurer {

  private final AiRoutineOperatorInterceptor interceptor;

  public AiRoutineOperatorWebConfig(AiRoutineOperatorInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(interceptor)
        .addPathPatterns("/api/v1/ai-routine/**")
        .excludePathPatterns(
            "/api/v1/ai-routine/capture/**",
            // CaptureSessionClient creates its server-owned run with the capture bearer. Applying
            // operator auth here as well makes the request impossible when the two credentials are
            // correctly distinct; RoutineController performs capture-token auth on every route.
            "/api/v1/ai-routine/runs/runelite",
            "/api/v1/ai-routine/runs/from-runelite-capture",
            "/api/v1/ai-routine/runs/from-stabilization/**");
  }
}
