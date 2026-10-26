package com.chromascape.web.aiplayer.routine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.contract.CurrentSliceIdentityValidator;
import com.chromascape.web.aiplayer.routine.config.AiRoutineOperatorProperties;
import com.chromascape.web.aiplayer.routine.config.Sc967CandidateProperties;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import com.chromascape.web.aiplayer.routine.repository.PatchCandidateRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Only an approved, registered candidate with a verifiable bundle is handed out to execute. */
class PatchCandidateRegisteredCandidateTest {
  private final PatchCandidateRunRepository repository = mock(PatchCandidateRunRepository.class);

  @Test
  void everyStateButRegisteredIsRefused() {
    for (String state :
        List.of(
            "PATCH_PROPOSED",
            "APPROVED",
            "BUILD_QUEUED",
            "BUILDING",
            "BUILT",
            "REGISTERING",
            "REGISTERING_UNCERTAIN",
            "REJECTED",
            "FAILED")) {
      PatchCandidateRun entity = entity(state);
      when(repository.findByProposalId("p")).thenReturn(Optional.of(entity));
      assertEquals("CANDIDATE_NOT_REGISTERED", refusal(), state);
    }
  }

  @Test
  void aRegisteredCandidateWithoutApprovalIsRefused() {
    PatchCandidateRun entity = entity("REGISTERED");
    entity.setApprovalHash(null);
    when(repository.findByProposalId("p")).thenReturn(Optional.of(entity));
    assertEquals("PATCH_NOT_APPROVED", refusal());
  }

  @Test
  void aRegisteredCandidateWhoseBundleCannotBeVerifiedIsRefused() {
    when(repository.findByProposalId("p")).thenReturn(Optional.of(entity("REGISTERED")));
    assertEquals("CANDIDATE_BUNDLE_INVALID", refusal());
  }

  private String refusal() {
    return assertThrows(
            PatchCandidateWorkflowService.PatchWorkflowException.class,
            () -> service().registeredCandidate("p"))
        .code();
  }

  private static PatchCandidateRun entity(String state) {
    PatchCandidateRun entity = new PatchCandidateRun();
    entity.setProposalId("p");
    entity.setState(state);
    entity.setIdentityJson("{\"control_allowed\":false}");
    entity.setApproverId("operator-1");
    entity.setApprovedAt(Instant.EPOCH);
    entity.setApprovalHash("sha256:" + "a".repeat(64));
    return entity;
  }

  private PatchCandidateWorkflowService service() {
    return new PatchCandidateWorkflowService(
        new ObjectMapper(),
        mock(IsolatedPatchWorker.class),
        repository,
        mock(PatchCandidateBuildDispatcher.class),
        mock(CurrentSliceRunService.class),
        mock(Micro6SourceManifestClient.class),
        mock(CurrentSliceIdentityValidator.class),
        mock(CandidateRegistryClient.class),
        new AiRoutineOperatorProperties(),
        new Sc967CandidateProperties());
  }
}
