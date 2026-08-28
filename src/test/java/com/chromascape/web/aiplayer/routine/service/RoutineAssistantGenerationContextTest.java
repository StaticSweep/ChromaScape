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
import com.chromascape.web.aiplayer.routine.repository.BehaviorGraphDefinitionRepository;
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
 * <p>The generator runs on its own executor with no transaction and no open session.
 * {@code RoutineAssistantConversation.generationMessage} and every {@code BehaviorGraphDefinition}
 * JSON column are mapped {@code @Lob}, and Hibernate materializes a Lob through the connection that
 * loaded the row. Loading either entity there threw "Unable to access lob stream" as soon as a
 * generation message existed. The generator caught it, wrote a silent {@code generation.failed}
 * event, and the acceptance driver then polled a dead conversation for its full 1800 s timeout.
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
    BehaviorGraphDefinitionRepository graphs = mock(BehaviorGraphDefinitionRepository.class);
    RoutineAssistantConversationStore store = mock(RoutineAssistantConversationStore.class);
    RoutineAssistantProvider provider = mock(RoutineAssistantProvider.class);

    // Any load of the whole entity is the defect, so make it fail exactly the way Hibernate does.
    when(conversations.findByConversationId(anyString()))
        .thenThrow(new IllegalStateException("Unable to access lob stream"));
    when(graphs.findFirstByStateAndIdentityJsonContainingOrderByCreatedAtDesc(
            anyString(), anyString()))
        .thenThrow(new IllegalStateException("Unable to access lob stream"));

    when(conversations.findRunIdByConversationId("conv_1")).thenReturn(Optional.of("run_1"));
    when(graphs.findGraphReferences(eq("PUBLISHED"), anyString()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {
                  "graph_0123456789abcdef01234567",
                  "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                }));
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
            graphs,
            mock(CurrentSliceRunRepository.class),
            mock(BehaviorGraphService.class),
            new GraphSchemaValidator(),
            store);

    service.message("conv_1", new RoutineApiDtos.AssistantMessageRequest("propose a graph"));

    verify(store, timeout(5000))
        .commitProposalIfCurrentToken(eq("conv_1"), eq("token_1"), any(), anyString());
    verify(store, never()).failGenerationIfCurrentToken(anyString(), anyString(), anyString());
    verify(conversations, never()).findByConversationId(anyString());
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
            mock(BehaviorGraphDefinitionRepository.class),
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
