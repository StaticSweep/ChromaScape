package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.dto.RoutineApiDtos;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantEvent;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantEventRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the asynchronous proposal generator away from {@code @Lob} entities.
 *
 * <p>The generator runs on its own executor: no transaction, no open session, connection in
 * auto-commit. {@code RoutineAssistantConversation.generationMessage} and {@code
 * BehaviorGraphDefinition.identityJson} are both mapped {@code @Lob}, which failed twice over --
 * loading the conversation threw "Unable to access lob stream", and the graph lookup's {@code like}
 * predicate threw "Large Objects may not be used in auto-commit mode". Each was caught and written
 * as a silent {@code generation.failed}, leaving the acceptance driver to poll a dead conversation
 * for its full 1800 s timeout.
 */
class RoutineAssistantGenerationContextTest {

  private static Map<String, Object> validDiff() {
    Map<String, Object> authority = new LinkedHashMap<>();
    for (String key :
        List.of(
            "can_write_source",
            "can_approve_patch",
            "can_publish_graph",
            "can_activate_model",
            "can_dispatch_input")) {
      authority.put(key, false);
    }
    Map<String, Object> diff = new LinkedHashMap<>();
    diff.put("schema", "behavior-graph-diff/v1");
    diff.put("base_graph_id", "graph_0123456789abcdef01234567");
    diff.put(
        "base_graph_hash",
        "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    diff.put("control_allowed", false);
    diff.put("authority", authority);
    diff.put("operations", List.of());
    diff.put("reason", "minimal deterministic fishing draft");
    return diff;
  }

  @Test
  void generatesProposalWithoutLoadingLobBackedEntities() {
    RoutineAssistantConversationRepository conversations =
        mock(RoutineAssistantConversationRepository.class);
    RoutineAssistantConversationStore store = mock(RoutineAssistantConversationStore.class);
    RoutineAssistantProvider provider = mock(RoutineAssistantProvider.class);

    // Reading the conversation straight from the executor thread is the defect. Make both shapes
    // fail the way the database did, so a regression to a direct read fails this test. The graph
    // lookup cannot regress the same way any more: the service no longer holds that repository.
    when(conversations.findByConversationId(anyString()))
        .thenThrow(new IllegalStateException("Unable to access lob stream"));
    when(conversations.findRunIdByConversationId(anyString()))
        .thenThrow(new IllegalStateException("Large Objects may not be used in auto-commit mode."));

    // The transactional store is the only sanctioned reader.
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("run_id", "run_1");
    context.put("graph_id", "graph_0123456789abcdef01234567");
    context.put(
        "graph_hash", "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    when(store.generationContext("conv_1")).thenReturn(context);
    when(store.beginGeneration(anyString(), anyString(), anyString())).thenReturn("token_1");
    when(store.appendGenerationEvent(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(1L);
    when(provider.propose(anyString(), anyString(), any())).thenReturn(Map.of("diff", validDiff()));
    when(store.commitProposalIfCurrentToken(anyString(), anyString(), any(), anyString()))
        .thenReturn(true);

    RoutineAssistantService service =
        new RoutineAssistantService(
            provider,
            new ObjectMapper(),
            conversations,
            mock(RoutineAssistantEventRepository.class),
            mock(RoutineAssistantProposalRepository.class),
            mock(CurrentSliceRunRepository.class),
            mock(BehaviorGraphService.class),
            new GraphSchemaValidator(),
            store);

    service.message("conv_1", new RoutineApiDtos.AssistantMessageRequest("propose a graph"));

    verify(store, timeout(5000))
        .commitProposalIfCurrentToken(eq("conv_1"), eq("token_1"), any(), anyString());
    verify(store, never()).failGenerationIfCurrentToken(anyString(), anyString(), anyString());
    verify(conversations, never()).findByConversationId(anyString());
    verify(conversations, never()).findRunIdByConversationId(anyString());
  }

  @Test
  void statusReportsTerminalGenerationFailureSoPollersCanStop() {
    RoutineAssistantConversationRepository conversations =
        mock(RoutineAssistantConversationRepository.class);
    RoutineAssistantEventRepository events = mock(RoutineAssistantEventRepository.class);
    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId("conv_1");
    conversation.setRunId("run_1");
    conversation.setState("OPEN");
    RoutineAssistantEvent failure = new RoutineAssistantEvent();
    failure.setConversationId("conv_1");
    failure.setEventId(3L);
    failure.setType("generation.failed");
    failure.setDataJson(
        "{\"code\":\"ASSISTANT_GENERATION_FAILED\",\"message\":\"Unable to access lob stream\"}");
    when(conversations.findByConversationId("conv_1")).thenReturn(Optional.of(conversation));
    when(events.findFirstByConversationIdAndTypeOrderByEventIdDesc("conv_1", "generation.failed"))
        .thenReturn(Optional.of(failure));

    RoutineAssistantService service =
        new RoutineAssistantService(
            mock(RoutineAssistantProvider.class),
            new ObjectMapper(),
            conversations,
            events,
            mock(RoutineAssistantProposalRepository.class),
            mock(CurrentSliceRunRepository.class),
            mock(BehaviorGraphService.class),
            mock(GraphSchemaValidator.class),
            mock(RoutineAssistantConversationStore.class));

    Map<String, Object> status = service.status("conv_1");

    assertThat(status).containsEntry("generation_state", "IDLE");
    assertThat(status.get("last_generation_failure"))
        .isInstanceOfSatisfying(
            Map.class,
            value -> assertThat(value).containsEntry("message", "Unable to access lob stream"));
  }
}
