package com.chromascape.web.aiplayer;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Optional scheduled exporter for patch-outcome datasets. */
@Service
public class AiPlayerDatasetScheduler {

  private static final Logger log = LoggerFactory.getLogger(AiPlayerDatasetScheduler.class);

  private final ImitationDatasetBuilderService datasetBuilder;

  @Value("${ai-player.dataset.patch-outcomes.scheduled-export-enabled:false}")
  private boolean scheduledExportEnabled;

  @Value("${ai-player.dataset.patch-outcomes.lookback-hours:24}")
  private long lookbackHours;

  @Value("${ai-player.dataset.patch-outcomes.export-empty:false}")
  private boolean exportEmpty;

  public AiPlayerDatasetScheduler(ImitationDatasetBuilderService datasetBuilder) {
    this.datasetBuilder = datasetBuilder;
  }

  @Scheduled(
      fixedDelayString = "${ai-player.dataset.patch-outcomes.schedule-fixed-delay-ms:3600000}",
      initialDelayString = "${ai-player.dataset.patch-outcomes.initial-delay-ms:60000}")
  public void exportScheduledPatchOutcomeDataset() {
    if (!scheduledExportEnabled || !datasetBuilder.isAiPlayerEnabled()) {
      return;
    }

    LocalDateTime endTime = LocalDateTime.now();
    LocalDateTime startTime = endTime.minusHours(Math.max(1L, lookbackHours));
    try {
      DatasetBuildResult result = datasetBuilder.buildPatchOutcomeDatasetResult(startTime, endTime);
      if (!exportEmpty && result.getRecordCount() == 0) {
        log.info(
            "Skipping empty scheduled patch-outcome export for window {} -> {}",
            startTime,
            endTime);
        return;
      }
      log.info(
          "Scheduled patch-outcome export completed: datasetId={} records={} manifest={}",
          result.getDatasetId(),
          result.getRecordCount(),
          result.getManifestPath());
    } catch (Exception e) {
      log.warn("Scheduled patch-outcome export failed: {}", e.getMessage(), e);
    }
  }

  public boolean isScheduledExportEnabled() {
    return scheduledExportEnabled;
  }
}
