package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskCitationValidationServiceTest {

  private static final String ITEM_ID = "item_validation";

  @Mock private TaskEvidenceCitationRepository citationRepository;

  private TaskCitationValidationService service;

  @BeforeEach
  void setUp() {
    service = new TaskCitationValidationService(citationRepository);
  }

  @Test
  @DisplayName("hasRequiredEvidenceTriplet is true when LOG, TICK_LOG, and RECORDING_SESSION exist")
  void hasRequiredEvidenceTriplet_returnsTrueWhenAllRequiredEvidenceExists() {
    when(citationRepository.findByItemId(ITEM_ID))
        .thenReturn(
            List.of(
                citation(EvidenceType.LOG, 1L),
                citation(EvidenceType.TICK_LOG, 2L),
                citation(EvidenceType.RECORDING_SESSION, 3L)));

    TaskCitationValidationService.CitationValidationResult result = service.validateCitations(ITEM_ID);

    assertThat(result.hasRequiredEvidenceTriplet()).isTrue();
  }

  @Test
  @DisplayName("hasRequiredEvidenceTriplet is false when LOG is missing")
  void hasRequiredEvidenceTriplet_returnsFalseWhenLogIsMissing() {
    when(citationRepository.findByItemId(ITEM_ID))
        .thenReturn(List.of(citation(EvidenceType.TICK_LOG, 2L), citation(EvidenceType.RECORDING_SESSION, 3L)));

    TaskCitationValidationService.CitationValidationResult result = service.validateCitations(ITEM_ID);

    assertThat(result.hasRequiredEvidenceTriplet()).isFalse();
  }

  @Test
  @DisplayName("hasRequiredEvidenceTriplet is false when TICK_LOG is missing")
  void hasRequiredEvidenceTriplet_returnsFalseWhenTickLogIsMissing() {
    when(citationRepository.findByItemId(ITEM_ID))
        .thenReturn(List.of(citation(EvidenceType.LOG, 1L), citation(EvidenceType.RECORDING_SESSION, 3L)));

    TaskCitationValidationService.CitationValidationResult result = service.validateCitations(ITEM_ID);

    assertThat(result.hasRequiredEvidenceTriplet()).isFalse();
  }

  @Test
  @DisplayName("hasRequiredEvidenceTriplet is false when RECORDING_SESSION is missing")
  void hasRequiredEvidenceTriplet_returnsFalseWhenRecordingSessionIsMissing() {
    when(citationRepository.findByItemId(ITEM_ID))
        .thenReturn(List.of(citation(EvidenceType.LOG, 1L), citation(EvidenceType.TICK_LOG, 2L)));

    TaskCitationValidationService.CitationValidationResult result = service.validateCitations(ITEM_ID);

    assertThat(result.hasRequiredEvidenceTriplet()).isFalse();
  }

  private TaskEvidenceCitation citation(EvidenceType type, Long evidenceId) {
    TaskEvidenceCitation citation = new TaskEvidenceCitation();
    citation.setItemId(ITEM_ID);
    citation.setPlanId("plan_validation");
    citation.setEvidenceType(type);
    citation.setEvidenceId(evidenceId);
    return citation;
  }
}
