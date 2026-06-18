package com.chromascape.web.aiplayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanItem.ItemStatus;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PatchOutcomeEvaluationServiceTest {

  @Mock private TaskPlanItemRepository taskPlanItemRepository;
  @Mock private TaskEvidenceCitationRepository citationRepository;

  private PatchOutcomeEvaluationService service;

  @BeforeEach
  void setUp() {
    service = new PatchOutcomeEvaluationService(taskPlanItemRepository, citationRepository);
  }

  @Test
  void evaluate_reportsEligibilityAndSuccessMetrics() {
    LocalDateTime start = LocalDateTime.of(2026, 6, 15, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 6, 16, 0, 0);
    TaskPlanItem completed = item("item-1", "QuestScript", ItemStatus.COMPLETED, "SUCCESS");
    TaskPlanItem failed = item("item-2", "QuestScript", ItemStatus.FAILED, "FAILED");
    TaskPlanItem excluded = item("item-3", "BossScript", ItemStatus.COMPLETED, "SUCCESS");

    when(taskPlanItemRepository.findByCompletedAtBetween(start, end))
        .thenReturn(List.of(completed, failed, excluded));
    when(citationRepository.findByItemId("item-1"))
        .thenReturn(List.of(citation(EvidenceType.LOG), citation(EvidenceType.TICK_LOG), citation(EvidenceType.RECORDING_SESSION)));
    when(citationRepository.findByItemId("item-2"))
        .thenReturn(List.of(citation(EvidenceType.LOG), citation(EvidenceType.TICK_LOG), citation(EvidenceType.RECORDING_SESSION)));
    when(citationRepository.findByItemId("item-3")).thenReturn(List.of(citation(EvidenceType.LOG)));

    Map<String, Object> result = service.evaluate(start, end, "QuestScript");

    assertThat(result).containsEntry("totalItems", 2);
    assertThat(result).containsEntry("eligibleItems", 2);
    assertThat(result).containsEntry("excludedMissingEvidenceTriplet", 0);
    assertThat(result).containsEntry("successCount", 1L);
    assertThat(result).containsEntry("failureCount", 1L);
    assertThat(result).containsEntry("successRate", 0.5d);
    assertThat((Map<?, ?>) result.get("countsByScript")).containsEntry("QUESTSCRIPT", 2L);
    assertThat((Map<?, ?>) result.get("countsByResult")).containsEntry("FAILED", 1L);
  }

  private TaskPlanItem item(String itemId, String scriptName, ItemStatus status, String result) {
    TaskPlanItem item = new TaskPlanItem();
    item.setItemId(itemId);
    item.setScriptName(scriptName);
    item.setStatus(status);
    item.setExecutionResult(result);
    item.setCompletedAt(LocalDateTime.now());
    return item;
  }

  private TaskEvidenceCitation citation(EvidenceType type) {
    TaskEvidenceCitation citation = new TaskEvidenceCitation();
    citation.setEvidenceType(type);
    return citation;
  }
}
