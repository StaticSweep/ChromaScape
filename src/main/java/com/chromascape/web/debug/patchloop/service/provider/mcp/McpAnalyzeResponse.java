package com.chromascape.web.debug.patchloop.service.provider.mcp;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Response;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
  private final String error;

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
      AnalysisV2Response analysisResponse, Map<String, Object> metadata, String error) {
    this.analysisResponse = analysisResponse;
    this.metadata = metadata != null ? metadata : Collections.emptyMap();
    this.error = error;
  }

  public AnalysisV2Response getAnalysisResponse() {
    return analysisResponse;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public String getError() {
    return error;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    McpAnalyzeResponse that = (McpAnalyzeResponse) o;
    return Objects.equals(analysisResponse, that.analysisResponse)
        && Objects.equals(metadata, that.metadata)
        && Objects.equals(error, that.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(analysisResponse, metadata, error);
  }

  @Override
  public String toString() {
    return "McpAnalyzeResponse{"
        + "analysisResponse="
        + analysisResponse
        + ", metadata="
        + metadata
        + ", error='"
        + error
        + '\''
        + '}';
  }
}
