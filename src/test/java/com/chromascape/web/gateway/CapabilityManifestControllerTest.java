package com.chromascape.web.gateway;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gateway.controller.CapabilityManifestController;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities.Features;
import com.chromascape.web.gateway.dto.DebuggerServiceCapabilities.Health;
import com.chromascape.web.gateway.service.CapabilityManifestService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CapabilityManifestControllerTest {

  @Test
  void exposesTheVersionedReadOnlyManifestWithoutCaching() throws Exception {
    CapabilityManifestService service = mock(CapabilityManifestService.class);
    when(service.current())
        .thenReturn(
            new DebuggerServiceCapabilities(
                "chromascape-service/v1",
                "cs-test",
                List.of("capabilities", "sessions"),
                List.of("viewport"),
                Map.of("viewport", "chromascape-legacy/v1"),
                1_048_576,
                2_097_152,
                List.of("live", "history"),
                new Features(true, false, false),
                false,
                List.of(),
                new Health("degraded", List.of("RECORDING_DISABLED"))));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new CapabilityManifestController(service)).build();

    mvc.perform(get("/api/capabilities"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.schemaVersion").value("chromascape-service/v1"))
        .andExpect(jsonPath("$.serviceBuildId").value("cs-test"))
        .andExpect(jsonPath("$.maxFrameBytes").value(2_097_152))
        .andExpect(jsonPath("$.controlAllowed").value(false))
        .andExpect(jsonPath("$.allowedMutations").isEmpty())
        .andExpect(jsonPath("$.health.status").value("degraded"));
  }
}
