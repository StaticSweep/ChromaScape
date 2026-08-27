package com.chromascape.web.aiplayer.routine.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class AiRoutineOperatorWebConfigTest {

  @Test
  void captureOwnedRunCreationRoutesDoNotRequireTheDistinctOperatorCredential() {
    AiRoutineOperatorInterceptor interceptor = mock(AiRoutineOperatorInterceptor.class);
    InterceptorRegistry registry = mock(InterceptorRegistry.class);
    InterceptorRegistration registration = mock(InterceptorRegistration.class);
    org.mockito.Mockito.when(registry.addInterceptor(interceptor)).thenReturn(registration);
    org.mockito.Mockito.when(registration.addPathPatterns("/api/v1/ai-routine/**"))
        .thenReturn(registration);

    new AiRoutineOperatorWebConfig(interceptor).addInterceptors(registry);

    verify(registration).excludePathPatterns(
        "/api/v1/ai-routine/capture/**",
        "/api/v1/ai-routine/runs/runelite",
        "/api/v1/ai-routine/runs/from-runelite-capture");
  }
}
