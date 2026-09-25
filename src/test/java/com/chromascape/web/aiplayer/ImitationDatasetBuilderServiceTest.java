package com.chromascape.web.aiplayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.TaskEvidenceCitation;
import com.chromascape.web.accounttask.entity.TaskEvidenceCitation.EvidenceType;
import com.chromascape.web.accounttask.entity.TaskPlan;
import com.chromascape.web.accounttask.entity.TaskPlanItem;
import com.chromascape.web.accounttask.entity.TaskPlanItem.ItemStatus;
import com.chromascape.web.accounttask.repository.TaskEvidenceCitationRepository;
import com.chromascape.web.accounttask.repository.TaskPlanItemRepository;
import com.chromascape.web.accounttask.repository.TaskPlanRepository;
import com.chromascape.web.logs.LogEntity;
import com.chromascape.web.logs.LogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ImitationDatasetBuilderServiceTest {

  @Mock private LogRepository logRepository;
  @Mock private TaskPlanItemRepository taskPlanItemRepository;
  @Mock private TaskPlanRepository taskPlanRepository;
  @Mock private TaskEvidenceCitationRepository citationRepository;

  @TempDir Path tempDir;

  private ImitationDatasetBuilderService service;

  @BeforeEach
  void setUp() {
    service =
        new ImitationDatasetBuilderService(
            logRepository,
            taskPlanItemRepository,
            taskPlanRepository,
            citationRepository,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "aiPlayerEnabled", true);
    ReflectionTestUtils.setField(service, "shadowMode", true);
    ReflectionTestUtils.setField(service, "controlEnabled", false);
    ReflectionTestUtils.setField(service, "outputDir", tempDir.toString());
  }

  @Test
  void buildDatasetResult_writesManifestAndJsonl() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 6, 16, 10, 0);
    LogEntity log = new LogEntity(now, "INFO", "hello", "acct", "ScriptA");
    log.setTick(123);
    when(logRepository.findBySourceAndTimestampBetweenOrderByTimestampAsc(
            "acct", now.minusHours(1), now))
        .thenReturn(List.of(log));

    DatasetBuildResult result = service.buildDatasetResult("acct", now.minusHours(1), now);

    assertThat(result.getRecordCount()).isEqualTo(1);
    assertThat(result.getManifestPath()).endsWith(".manifest.json");
    assertThat(Files.readString(Path.of(result.getOutputPath()))).contains("\"tick\":123");
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        new ObjectMapper().readValue(Path.of(result.getManifestPath()).toFile(), Map.class);
    assertThat(manifest).containsEntry("dataset_kind", "logs");
    @SuppressWarnings("unchecked")
    Map<String, Object> featureFlags = (Map<String, Object>) manifest.get("feature_flags");
    assertThat(featureFlags).containsEntry("control_enabled", false);
  }

  @Test
  void buildPatchOutcomeDatasetResult_excludesItemsMissingEvidenceTriplet() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 6, 16, 12, 0);
    TaskPlanItem included = new TaskPlanItem();
    included.setPlanId("plan-1");
    included.setItemId("item-1");
    included.setScriptName("QuestScript");
    included.setTitle("Do thing");
    included.setStatus(ItemStatus.COMPLETED);
    included.setExecutionResult("SUCCESS");
    included.setCompletedAt(now.minusMinutes(10));

    TaskPlanItem excluded = new TaskPlanItem();
    excluded.setPlanId("plan-1");
    excluded.setItemId("item-2");
    excluded.setScriptName("QuestScript");
    excluded.setTitle("Skip thing");
    excluded.setStatus(ItemStatus.FAILED);
    excluded.setExecutionResult("FAILED");
    excluded.setCompletedAt(now.minusMinutes(5));

    TaskPlan plan = new TaskPlan();
    plan.setPlanId("plan-1");
    plan.setIntent("Train questing");

    when(taskPlanItemRepository.findByCompletedAtBetween(now.minusDays(1), now))
        .thenReturn(List.of(included, excluded));
    when(taskPlanRepository.findByPlanIdIn(List.of("plan-1"))).thenReturn(List.of(plan));
    when(citationRepository.findByItemId("item-1"))
        .thenReturn(
            List.of(
                citation(EvidenceType.LOG),
                citation(EvidenceType.TICK_LOG),
                citation(EvidenceType.RECORDING_SESSION)));
    when(citationRepository.findByItemId("item-2")).thenReturn(List.of(citation(EvidenceType.LOG)));

    DatasetBuildResult result = service.buildPatchOutcomeDatasetResult(now.minusDays(1), now);

    assertThat(result.getRecordCount()).isEqualTo(1);
    assertThat(result.getExcludedRecordCount()).isEqualTo(1);
    assertThat(Files.readString(Path.of(result.getOutputPath()))).contains("Train questing");
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest =
        new ObjectMapper().readValue(Path.of(result.getManifestPath()).toFile(), Map.class);
    assertThat(manifest).containsEntry("dataset_kind", "patch_outcomes");
    @SuppressWarnings("unchecked")
    Map<String, Object> evidenceTripletFilter =
        (Map<String, Object>) manifest.get("evidence_triplet_filter");
    assertThat(evidenceTripletFilter)
        .containsEntry("included_records", 1)
        .containsEntry("excluded_records", 1);
  }

  private TaskEvidenceCitation citation(EvidenceType type) {
    TaskEvidenceCitation citation = new TaskEvidenceCitation();
    citation.setEvidenceType(type);
    return citation;
  }
}
