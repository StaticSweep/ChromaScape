package com.chromascape.web.replay.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** API contract for the session lifecycle and headless Fishing execution. */
class XrspsSessionsControllerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    XrspsSessionsController controller =
        new XrspsSessionsController(new XrspsSessionExecutionService());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ReplayExceptionHandler())
            .build();
  }

  private String createBody(String key) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "mode", "RECORDED_ACTIONS",
            "idempotencyKey", key,
            "scenarioRef", "scenario:fish-1"));
  }

  private String runBody() throws Exception {
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();
    return mapper.writeValueAsString(
        Map.of(
            "actions",
            List.of(
                Map.of(
                    "relativeTick",
                    0,
                    "commandId",
                    first,
                    "actionType",
                    "INTERACT_FISHING_SPOT",
                    "payloadHash",
                    "hash-a",
                    "expectedRevision",
                    0),
                Map.of(
                    "relativeTick",
                    5,
                    "commandId",
                    second,
                    "actionType",
                    "INTERACT_FISHING_SPOT",
                    "payloadHash",
                    "hash-b",
                    "expectedRevision",
                    1))));
  }

  @Test
  void fullLifecycleFromCreateToCancel() throws Exception {
    String created =
        mvc.perform(
                post("/api/xrsps/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("http-key-001")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = mapper.readTree(created).path("id").asText();

    mvc.perform(post("/api/xrsps/sessions/" + id + "/start"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(runBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true))
        .andExpect(jsonPath("$.submitted").value(2))
        .andExpect(jsonPath("$.lastTick").value(5))
        .andExpect(jsonPath("$.lastRevision").value(2));

    mvc.perform(get("/api/xrsps/sessions/" + id + "/observation"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tick").value(5))
        .andExpect(jsonPath("$.observationRevision").value(2))
        .andExpect(jsonPath("$.legalActions").isArray());

    mvc.perform(post("/api/xrsps/sessions/" + id + "/pause"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));

    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/step")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticks\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tick").value(7));

    mvc.perform(post("/api/xrsps/sessions/" + id + "/resume"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"operator stop\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void unsupportedActionsAreRefusedWithStableErrors() throws Exception {
    String created =
        mvc.perform(
                post("/api/xrsps/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("http-key-002")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = mapper.readTree(created).path("id").asText();
    mvc.perform(post("/api/xrsps/sessions/" + id + "/start")).andExpect(status().isOk());

    String badAction =
        mapper.writeValueAsString(
            Map.of(
                "actions",
                List.of(
                    Map.of(
                        "relativeTick",
                        0,
                        "commandId",
                        UUID.randomUUID().toString(),
                        "actionType",
                        "CAST_BANK_SPELL",
                        "payloadHash",
                        "hash-a",
                        "expectedRevision",
                        0))));
    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badAction))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ACTION_UNSUPPORTED"));
  }

  @Test
  void validationAndUnknownSessionsFailClosed() throws Exception {
    mvc.perform(
            post("/api/xrsps/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "mode", "RECORDED_ACTIONS",
                            "idempotencyKey", "short",
                            "scenarioRef", "scenario:fish-1"))))
        .andExpect(status().isBadRequest());

    UUID unknown = UUID.randomUUID();
    mvc.perform(get("/api/xrsps/sessions/" + unknown)).andExpect(status().isNotFound());
    mvc.perform(post("/api/xrsps/sessions/" + unknown + "/start")).andExpect(status().isNotFound());
  }
}
