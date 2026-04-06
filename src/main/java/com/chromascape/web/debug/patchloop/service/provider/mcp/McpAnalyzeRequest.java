package com.chromascape.web.debug.patchloop.service.provider.mcp;

import com.chromascape.web.debug.patchloop.dto.AnalysisV2Request;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Transport-level request for MCP /analyze endpoint.
 *
 * <p>This is a thin wrapper that carries the V2 analysis contract to the MCP server. The actual
 * business logic is in AnalysisV2Request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpAnalyzeRequest {

  @JsonProperty("request")
  private final AnalysisV2Request analysisRequest;

  @JsonProperty("options")
  private final McpRequestOptions options;

  /** Creates a new analyze request. */
  public McpAnalyzeRequest() {
    this(null, null);
  }

  /**
   * Creates a new analyze request with the given V2 request.
   *
   * @param analysisRequest the V2 analysis request
   */
  public McpAnalyzeRequest(AnalysisV2Request analysisRequest) {
    this(analysisRequest, null);
  }

  /**
   * Creates a new analyze request with full parameters.
   *
   * @param analysisRequest the V2 analysis request
   * @param options optional MCP-specific options
   */
  public McpAnalyzeRequest(AnalysisV2Request analysisRequest, McpRequestOptions options) {
    this.analysisRequest = analysisRequest;
    this.options = options;
  }

  public AnalysisV2Request getAnalysisRequest() {
    return analysisRequest;
  }

  public McpRequestOptions getOptions() {
    return options;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    McpAnalyzeRequest that = (McpAnalyzeRequest) o;
    return Objects.equals(analysisRequest, that.analysisRequest)
        && Objects.equals(options, that.options);
  }

  @Override
  public int hashCode() {
    return Objects.hash(analysisRequest, options);
  }

  @Override
  public String toString() {
    return "McpAnalyzeRequest{"
        + "analysisRequest="
        + analysisRequest
        + ", options="
        + options
        + '}';
  }

  /** Optional MCP-specific options for the request. */
  public static class McpRequestOptions {

    @JsonProperty("timeout")
    private final Integer timeoutSeconds;

    @JsonProperty("model")
    private final String model;

    public McpRequestOptions() {
      this(null, null);
    }

    public McpRequestOptions(Integer timeoutSeconds, String model) {
      this.timeoutSeconds = timeoutSeconds;
      this.model = model;
    }

    public Integer getTimeoutSeconds() {
      return timeoutSeconds;
    }

    public String getModel() {
      return model;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      McpRequestOptions that = (McpRequestOptions) o;
      return Objects.equals(timeoutSeconds, that.timeoutSeconds)
          && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timeoutSeconds, model);
    }

    @Override
    public String toString() {
      return "McpRequestOptions{"
          + "timeoutSeconds="
          + timeoutSeconds
          + ", model='"
          + model
          + '\''
          + '}';
    }
  }
}
