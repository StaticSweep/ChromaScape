package com.chromascape.web.aiplayer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiPlayerDatasetSchedulerTest {

  @Mock private ImitationDatasetBuilderService datasetBuilder;

  private AiPlayerDatasetScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new AiPlayerDatasetScheduler(datasetBuilder);
    ReflectionTestUtils.setField(scheduler, "lookbackHours", 24L);
  }

  @Test
  void exportScheduledPatchOutcomeDataset_noOpsWhenDisabled() {
    ReflectionTestUtils.setField(scheduler, "scheduledExportEnabled", false);

    scheduler.exportScheduledPatchOutcomeDataset();

    verify(datasetBuilder, never()).buildPatchOutcomeDatasetResult(any(LocalDateTime.class), any(LocalDateTime.class));
  }

  @Test
  void exportScheduledPatchOutcomeDataset_runsWhenEnabled() {
    ReflectionTestUtils.setField(scheduler, "scheduledExportEnabled", true);
    ReflectionTestUtils.setField(scheduler, "exportEmpty", true);
    when(datasetBuilder.isAiPlayerEnabled()).thenReturn(true);
    when(datasetBuilder.buildPatchOutcomeDatasetResult(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(
            new DatasetBuildResult(
                "ds-1",
                "patch_outcomes",
                "/tmp/out.jsonl",
                "/tmp/out.manifest.json",
                1,
                1,
                0,
                Map.of(),
                List.of(Map.of())));

    scheduler.exportScheduledPatchOutcomeDataset();

    verify(datasetBuilder).buildPatchOutcomeDatasetResult(any(LocalDateTime.class), any(LocalDateTime.class));
  }
}
