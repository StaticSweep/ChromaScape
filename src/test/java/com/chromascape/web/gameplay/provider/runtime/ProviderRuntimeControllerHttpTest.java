package com.chromascape.web.gameplay.provider.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.Readiness;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProviderRuntimeControllerHttpTest {
  private static final String OPERATOR_KEY = "0123456789abcdef0123456789abcdef";
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    GameplayProviderRuntime runtime = mock(GameplayProviderRuntime.class);
    ProviderRuntimeProperties properties = new ProviderRuntimeProperties();
    properties.setOperatorKey(OPERATOR_KEY);
    when(runtime.readiness())
        .thenReturn(new Readiness(ProviderRuntimeModels.VERSION, true, true, Map.of()));
    mvc =
        MockMvcBuilders.standaloneSetup(new ProviderRuntimeController(runtime, properties)).build();
  }

  @Test
  void missingOperatorKeyFailsClosedOverHttp() throws Exception {
    mvc.perform(get("/api/v1/gameplay/provider-runtime/readiness"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_UNAUTHORIZED"));
  }

  @Test
  void invalidOperatorKeyFailsClosedOverHttp() throws Exception {
    mvc.perform(
            get("/api/v1/gameplay/provider-runtime/readiness")
                .header("X-Gameplay-Operator-Key", "wrong-key"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_UNAUTHORIZED"));
  }

  @Test
  void validOperatorKeyReturnsBoundedReadinessOverHttp() throws Exception {
    mvc.perform(
            get("/api/v1/gameplay/provider-runtime/readiness")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractVersion").value(ProviderRuntimeModels.VERSION))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.operatorKeyConfigured").value(true));
  }
}
