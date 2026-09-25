package com.chromascape.web.aiplayer;

import java.util.List;
import java.util.Map;

/** Result metadata for a dataset build, including manifest details. */
public class DatasetBuildResult {

  private final String datasetId;
  private final String datasetKind;
  private final String outputPath;
  private final String manifestPath;
  private final int recordCount;
  private final int sourceRecordCount;
  private final int excludedRecordCount;
  private final Map<String, Object> manifest;
  private final List<Map<String, Object>> records;

  public DatasetBuildResult(
      String datasetId,
      String datasetKind,
      String outputPath,
      String manifestPath,
      int recordCount,
      int sourceRecordCount,
      int excludedRecordCount,
      Map<String, Object> manifest,
      List<Map<String, Object>> records) {
    this.datasetId = datasetId;
    this.datasetKind = datasetKind;
    this.outputPath = outputPath;
    this.manifestPath = manifestPath;
    this.recordCount = recordCount;
    this.sourceRecordCount = sourceRecordCount;
    this.excludedRecordCount = excludedRecordCount;
    this.manifest = manifest;
    this.records = records;
  }

  public String getDatasetId() {
    return datasetId;
  }

  public String getDatasetKind() {
    return datasetKind;
  }

  public String getOutputPath() {
    return outputPath;
  }

  public String getManifestPath() {
    return manifestPath;
  }

  public int getRecordCount() {
    return recordCount;
  }

  public int getSourceRecordCount() {
    return sourceRecordCount;
  }

  public int getExcludedRecordCount() {
    return excludedRecordCount;
  }

  public Map<String, Object> getManifest() {
    return manifest;
  }

  public List<Map<String, Object>> getRecords() {
    return records;
  }
}
