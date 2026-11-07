package com.chromascape.web.replay.curation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.replay.api.ReplayCurationController;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.curation.entity.CurationState;
import com.chromascape.web.replay.curation.entity.RecordingCuration;
import com.chromascape.web.replay.curation.repository.CuratedActionRepository;
import com.chromascape.web.replay.curation.repository.RecordingCurationRepository;
import com.chromascape.web.replay.error.ReplayExceptionHandler;
import com.chromascape.web.replay.security.ReplayPrincipalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Actual MVC binding, policy validation, controller and service with in-memory repository doubles.
 * Supplied servlet principals exercise authorization, not socket authentication or database
 * durability.
 */
class ReplayCurationPolicyMvcTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final RecordingCurationRepository curations = mock(RecordingCurationRepository.class);
  private final CuratedActionRepository actions = mock(CuratedActionRepository.class);
  private final ReplayRecordingResolver resolver = mock(ReplayRecordingResolver.class);
  private final Map<UUID, RecordingCuration> rows = new HashMap<>();
  private MockMvc http;
  private RecordingCuration draft;

  @BeforeEach
  void setup() {
    when(curations.findById(any(UUID.class)))
        .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
    when(curations.save(any(RecordingCuration.class)))
        .thenAnswer(
            call -> {
              RecordingCuration row = call.getArgument(0);
              rows.put(row.getId(), row);
              return row;
            });
    when(actions.findByCurationIdOrderByTickAscSequenceAsc(any())).thenReturn(List.of());
    draft = new RecordingCuration();
    draft.setId(UUID.randomUUID());
    draft.setRecordingId("policy-mvc");
    draft.setCreatedBy("alice");
    draft.setState(CurationState.DRAFT);
    ObjectNode content = json.createObjectNode();
    content.putObject("tickRange").put("start", 0).put("end", 100);
    content.putArray("actionLabels").addObject().put("tick", 7).put("status", "IGNORED");
    content.putObject("reviewMetadata").put("note", "keep");
    draft.setContentJson(content);
    draft.setContentChecksum("sha256:before");
    rows.put(draft.getId(), draft);
    http =
        MockMvcBuilders.standaloneSetup(
                new ReplayCurationController(
                    new ReplayCurationService(curations, actions, resolver, json),
                    new ReplayPrincipalService()))
            .setControllerAdvice(new ReplayExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
  }

  private ObjectNode policy() {
    ObjectNode policy = json.createObjectNode().put("schema", "xrsps-termination-policy/v1");
    policy.putArray("failure");
    policy.putArray("stop");
    policy
        .putArray("success")
        .addObject()
        .put("id", "ready")
        .putObject("predicate")
        .put("op", "exists")
        .put("pointer", "/player/inventory");
    return policy;
  }

  private String body(ObjectNode policy) throws Exception {
    return json.writeValueAsString(json.createObjectNode().set("termination", policy));
  }

  @Test
  void policyRoundTripsThroughCreateUpdateReadAndPublishWithoutTrustingBodyIdentity()
      throws Exception {
    DebugSession session = new DebugSession();
    session.setSessionId("policy-session");
    when(resolver.resolve("policy-mvc"))
        .thenReturn(new ReplayRecordingResolver.ResolvedRecording(null, session));
    ObjectNode request =
        json.createObjectNode()
            .put("tickRangeStart", 0)
            .put("tickRangeEnd", 100)
            .put("createdBy", "mallory")
            .put("role", "HELD_OUT");
    request.set("termination", policy());
    var created =
        http.perform(
                post("/api/replay/recordings/policy-mvc/curations")
                    .principal(() -> "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.createdBy").value("alice"))
            .andExpect(jsonPath("$.contentJson.termination.success[0].id").value("ready"))
            .andReturn();
    String id =
        json.readTree(created.getResponse().getContentAsByteArray()).path("curationId").asText();
    ObjectNode empty = policy();
    empty.withArray("success").removeAll();
    http.perform(
            put("/api/replay/curations/" + id)
                .principal(() -> "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(empty)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentJson.termination.success").isEmpty());
    http.perform(post("/api/replay/curations/" + id + "/publish").principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PUBLISHED"));
    http.perform(get("/api/replay/curations/" + id).principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.contentJson.termination.schema").value("xrsps-termination-policy/v1"));
    assertEquals("alice", rows.get(UUID.fromString(id)).getCreatedBy());
  }

  @Test
  void omittedPolicyAndLabelsSurviveJsonUpdateAndPublishedEditsAreInert() throws Exception {
    ((ObjectNode) draft.getContentJson()).set("termination", policy());
    http.perform(
            put("/api/replay/curations/" + draft.getId())
                .principal(() -> "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initialStateOverrides\":{\"worldId\":301}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contentJson.termination.success[0].id").value("ready"))
        .andExpect(jsonPath("$.contentJson.actionLabels[0].tick").value(7))
        .andExpect(jsonPath("$.contentJson.reviewMetadata.note").value("keep"));
    http.perform(
            post("/api/replay/curations/" + draft.getId() + "/publish").principal(() -> "alice"))
        .andExpect(status().isOk());
    var before = draft.getContentJson().deepCopy();
    http.perform(
            put("/api/replay/curations/" + draft.getId())
                .principal(() -> "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(policy())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CURATION_IMMUTABLE"));
    assertEquals(before, draft.getContentJson());
    verify(actions, never()).deleteByCurationId(any());
  }

  @Test
  void malformedPolicyCannotMutateUpdateOrPublishAndCreateDoesNotResolveSource() throws Exception {
    ObjectNode invalid = policy().put("callback", "untrusted");
    var before = draft.getContentJson().deepCopy();
    http.perform(
            put("/api/replay/curations/" + draft.getId())
                .principal(() -> "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(invalid)))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(before, draft.getContentJson());
    ((ObjectNode) draft.getContentJson()).set("termination", invalid);
    http.perform(
            post("/api/replay/curations/" + draft.getId() + "/publish").principal(() -> "alice"))
        .andExpect(status().isUnprocessableEntity());
    http.perform(
            post("/api/replay/recordings/policy-mvc/curations")
                .principal(() -> "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(invalid)))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(CurationState.DRAFT, draft.getState());
    assertEquals("sha256:before", draft.getContentChecksum());
    verify(curations, never()).save(any());
    verifyNoInteractions(resolver);
  }

  @Test
  void foreignAndMissingRowsHaveSame404AndMissingPrincipalCannotAuthor() throws Exception {
    String request = body(policy());
    for (UUID id : List.of(draft.getId(), UUID.randomUUID())) {
      http.perform(
              put("/api/replay/curations/" + id)
                  .principal(() -> "mallory")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(request))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("CURATION_NOT_PUBLISHED"));
      http.perform(post("/api/replay/curations/" + id + "/publish").principal(() -> "mallory"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("CURATION_NOT_PUBLISHED"));
    }
    http.perform(
            put("/api/replay/curations/" + draft.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/api/replay/recordings/policy-mvc/curations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isUnauthorized());
    verify(curations, never()).save(any());
    verifyNoInteractions(resolver);
  }
}
