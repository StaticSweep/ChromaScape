package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Successful training response for the fishing prototype.
 *
 * @param status always {@code TRAINED}
 * @param modelId the persisted model artifact ID
 * @param datasetId the persisted dataset ID
 * @param trainingRecordingId the training recording
 * @param sessionId the training debug session
 * @param exampleCount the number of accepted state/action examples
 * @param actionVocabulary the canonical action vocabulary
 * @param datasetPath the JSONL dataset path
 * @param manifestPath the dataset manifest path
 * @param modelPath the model artifact path
 */
public record TrainingResult(
    @JsonProperty("status") String status,
    @JsonProperty("modelId") String modelId,
    @JsonProperty("datasetId") String datasetId,
    @JsonProperty("trainingRecordingId") String trainingRecordingId,
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("exampleCount") int exampleCount,
    @JsonProperty("actionVocabulary") List<SemanticAction> actionVocabulary,
    @JsonProperty("datasetPath") String datasetPath,
    @JsonProperty("manifestPath") String manifestPath,
    @JsonProperty("modelPath") String modelPath) {}
