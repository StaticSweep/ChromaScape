package com.chromascape.web.replay.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.chromascape.web.replay.security.ReplayPrincipalService;
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
        new XrspsSessionsController(
            new XrspsSessionExecutionService(), new ReplayPrincipalService());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .defaultRequest(get("/").principal(() -> "alice"))
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

  private String createSessionId(String key) throws Exception {
    return mapper
        .readTree(
            mvc.perform(
                    post("/api/xrsps/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("id")
        .asText();
  }

  @Test
  void everySessionRouteRequiresAuthenticationAndOwner() throws Exception {
    String id = createSessionId("auth-key-001");
    for (String caller : List.of("anonymous", "bob")) {
      java.security.Principal principal = caller.equals("anonymous") ? null : () -> caller;
      var expected = caller.equals("anonymous") ? status().isUnauthorized() : status().isNotFound();
      for (String suffix : List.of("", "/observation"))
        mvc.perform(
                get("/api/xrsps/sessions/" + id + suffix)
                    .with(
                        request -> {
                          request.setUserPrincipal(principal);
                          return request;
                        }))
            .andExpect(expected);
      for (String suffix :
          List.of("/start", "/pause", "/resume", "/cancel", "/step", "/run", "/step-run")) {
        String body =
            suffix.equals("/run") ? runBody() : suffix.contains("step") ? "{\"ticks\":1}" : "{}";
        mvc.perform(
                post("/api/xrsps/sessions/" + id + suffix)
                    .with(
                        request -> {
                          request.setUserPrincipal(principal);
                          return request;
                        })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(expected);
      }
    }
    mvc.perform(
            post("/api/xrsps/sessions")
                .with(
                    request -> {
                      request.setUserPrincipal(null);
                      return request;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("auth-key-002")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/xrsps/sessions/" + id))
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.tick").value(0));
  }

  @Test
  void httpMismatchStaysLatchedAndChangedJournalConflicts() throws Exception {
    String id = createSessionId("http-latch-001");
    mvc.perform(post("/api/xrsps/sessions/" + id + "/start")).andExpect(status().isOk());
    String bad = runBody().replace("\"expectedRevision\":1", "\"expectedRevision\":0");
    String first =
        mvc.perform(
                post("/api/xrsps/sessions/" + id + "/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bad))
            .andExpect(jsonPath("$.haltReason").value("MISMATCH"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String retried =
        mvc.perform(
                post("/api/xrsps/sessions/" + id + "/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bad))
            .andExpect(jsonPath("$.submitted").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.junit.jupiter.api.Assertions.assertEquals(
        mapper.readTree(first).path("firstMismatch"),
        mapper.readTree(retried).path("firstMismatch"));
    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad.replace("\"expectedRevision\":0", "\"expectedRevision\":1")))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/xrsps/sessions/" + id + "/pause")).andExpect(status().isOk());
    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/step-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticks\":1}"))
        .andExpect(jsonPath("$.haltReason").value("MISMATCH"))
        .andExpect(jsonPath("$.submitted").value(0));
  }

  @Test
  void nullActionsAreRejectedBeforeJournalInstallation() throws Exception {
    String id = createSessionId("null-action-001");
    mvc.perform(
            post("/api/xrsps/sessions/" + id + "/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actions\":[null]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ownersHaveIndependentCreateKeysAndUniformNotFoundResponses() throws Exception {
    String alice = createSessionId("shared-http-key");
    String bobJson =
        mvc.perform(
                post("/api/xrsps/sessions")
                    .principal(() -> "bob")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("shared-http-key")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        alice, mapper.readTree(bobJson).path("id").asText());
    String foreign =
        mvc.perform(get("/api/xrsps/sessions/" + alice).principal(() -> "bob"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String missing =
        mvc.perform(get("/api/xrsps/sessions/" + UUID.randomUUID()).principal(() -> "bob"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var foreignError = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(foreign);
    var missingError = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(missing);
    foreignError.remove("correlationId");
    missingError.remove("correlationId");
    org.junit.jupiter.api.Assertions.assertEquals(missingError, foreignError);
  }
}
