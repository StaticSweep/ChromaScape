package com.chromascape.web.debug.patchloop.service.provider.mcp;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-level response from MCP /analyze endpoint.
 *
 * <p>This wraps the V2 analysis response returned by the MCP server.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpAnalyzeResponse {

  @JsonProperty("response")
  private final AnalysisV2Response analysisResponse;

  @JsonProperty("metadata")
  private final Map<String, Object> metadata;

  @JsonProperty("error")
  private final JsonNode rawError;

  /** Creates a new analyze response. */
  public McpAnalyzeResponse() {
    this(null, null, null);
  }

  /**
   * Creates a new analyze response with the V2 response.
   *
   * @param analysisResponse the V2 analysis response
   */
  public McpAnalyzeResponse(AnalysisV2Response analysisResponse) {
    this(analysisResponse, null, null);
  }

  /**
   * Creates a new analyze response with full parameters.
   *
   * @param analysisResponse the V2 analysis response
   * @param metadata optional metadata from the MCP server
   * @param error optional error message
   */
  public McpAnalyzeResponse(
      AnalysisV2Response analysisResponse, Map<String, Object> metadata, JsonNode rawError) {
    this.analysisResponse = analysisResponse;
    this.metadata = metadata != null ? metadata : Collections.emptyMap();
    this.rawError = rawError;
  }

  public AnalysisV2Response getAnalysisResponse() {
    return analysisResponse;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  @JsonProperty("error")
  public JsonNode getRawError() {
    return rawError;
  }

  @JsonIgnore
  public String getError() {
    if (rawError == null || rawError.isNull()) {
      return null;
    }
    if (rawError.isTextual()) {
      String text = rawError.asText();
      return text == null || text.isBlank() ? null : text.trim();
    }
    return rawError.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    McpAnalyzeResponse that = (McpAnalyzeResponse) o;
    return Objects.equals(analysisResponse, that.analysisResponse)
        && Objects.equals(metadata, that.metadata)
        && Objects.equals(rawError, that.rawError);
  }

  @Override
  public int hashCode() {
    return Objects.hash(analysisResponse, metadata, rawError);
  }

  @Override
  public String toString() {
    return "McpAnalyzeResponse{"
        + "analysisResponse="
        + analysisResponse
        + ", metadata="
        + metadata
        + ", rawError="
        + rawError
        + '\''
        + '}';
  }
}
