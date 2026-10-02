package com.chromascape.web.aiplayer.catalog;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorInterceptor;
import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogHttpTest {
  @Test
  void readsArePublicButProfileWritesRequireTheExistingOperatorToken() throws Exception {
    var service = mock(AiPlayerCatalogService.class);
    var store = mock(CatalogStore.class);
    var properties = new AiRoutineOperatorProperties();
    properties.setToken("fixture-operator-token");
    var mvc =
        MockMvcBuilders.standaloneSetup(new AiPlayerCatalogController(service, store))
            .addInterceptors(new AiRoutineOperatorInterceptor(properties))
            .build();
    when(service.catalog("fixture-ref", false))
        .thenReturn(new ObjectMapper().createObjectNode().put("state", "READY"));
    mvc.perform(get("/api/v1/ai-routine/catalog").param("endpointRef", "fixture-ref"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));
    mvc.perform(
            post("/api/v1/ai-routine/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/ai-routine/profiles")
                .header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    verify(service, never()).save(any());
    when(service.save(any()))
        .thenThrow(
            new CatalogProblem(
                422,
                "INVALID_SETTINGS",
                "Fix fields",
                java.util.Map.of("supplyBudget", "Outside range")));
    mvc.perform(
            post("/api/v1/ai-routine/profiles")
                .header("Authorization", "Bearer fixture-operator-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors.supplyBudget").value("Outside range"));
    properties.setToken("");
    mvc.perform(
            post("/api/v1/ai-routine/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isServiceUnavailable());
  }
}
