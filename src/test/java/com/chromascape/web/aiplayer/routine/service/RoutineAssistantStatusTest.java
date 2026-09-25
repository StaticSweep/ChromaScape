package com.chromascape.web.aiplayer.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantConversation;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import com.chromascape.web.aiplayer.routine.repository.CurrentSliceRunRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantConversationRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantEventRepository;
import com.chromascape.web.aiplayer.routine.repository.RoutineAssistantProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutineAssistantStatusTest {

  @Test
  void returnsBoundedLatestProposalForAcceptancePolling() {
    RoutineAssistantConversationRepository conversations =
        mock(RoutineAssistantConversationRepository.class);
    RoutineAssistantProposalRepository proposals = mock(RoutineAssistantProposalRepository.class);
    RoutineAssistantConversation conversation = new RoutineAssistantConversation();
    conversation.setConversationId("conv_1");
    conversation.setRunId("run_1");
    conversation.setState("OPEN");
    RoutineAssistantProposal proposal = new RoutineAssistantProposal();
    proposal.setProposalId("proposal_1");
    proposal.setConversationId("conv_1");
    proposal.setState("PROPOSED");
    proposal.setDiffJson("{\"schema\":\"behavior-graph-diff/v1\",\"control_allowed\":false}");
    when(conversations.findByConversationId("conv_1")).thenReturn(Optional.of(conversation));
    when(proposals.findFirstByConversationIdOrderByIdDesc("conv_1"))
        .thenReturn(Optional.of(proposal));

    RoutineAssistantService service =
        new RoutineAssistantService(
            mock(RoutineAssistantProvider.class),
            new ObjectMapper(),
            conversations,
            mock(RoutineAssistantEventRepository.class),
            proposals,
            mock(CurrentSliceRunRepository.class),
            mock(BehaviorGraphService.class),
            mock(GraphSchemaValidator.class),
            mock(RoutineAssistantConversationStore.class));

    Map<String, Object> status = service.status("conv_1");

    assertThat(status).containsEntry("conversation_id", "conv_1");
    assertThat(status).containsEntry("control_allowed", false);
    assertThat(status.get("latest_proposal"))
        .isInstanceOfSatisfying(
            Map.class,
            value ->
                assertThat(value)
                    .containsEntry("proposal_id", "proposal_1")
                    .containsEntry("state", "PROPOSED")
                    .containsEntry("control_allowed", false));
  }
}
