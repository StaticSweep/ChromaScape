package com.chromascape.web.debug.patchloop.service.provider.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Response from MCP server health check endpoint.
 *
 * <p>This DTO represents the response from the MCP server's health/ready endpoint, used to
 * determine server availability and capabilities.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpHealthResponse {

  @JsonProperty("healthy")
  private final boolean isHealthy;

  @JsonProperty("serverVersion")
  private final String serverVersion;

  @JsonProperty("metadata")
  private final Map<String, Object> metadata;

  /**
   * Creates a new healthy response with minimal information.
   *
   * @param isHealthy whether the server is healthy
   */
  public McpHealthResponse(boolean isHealthy) {
    this(isHealthy, null, Collections.emptyMap());
  }

  /**
   * Creates a new health response with full information.
   *
   * @param isHealthy whether the server is healthy
   * @param serverVersion the server version (optional)
   * @param metadata additional metadata about the server
   */
  public McpHealthResponse(boolean isHealthy, String serverVersion, Map<String, Object> metadata) {
    this.isHealthy = isHealthy;
    this.serverVersion = serverVersion;
    this.metadata = metadata != null ? metadata : Collections.emptyMap();
  }

  /**
   * Returns whether the MCP server is healthy.
   *
   * @return true if the server is healthy
   */
  public boolean isHealthy() {
    return isHealthy;
  }

  /**
   * Returns the MCP server version.
   *
   * @return the server version, or null if not provided
   */
  public String getServerVersion() {
    return serverVersion;
  }

  /**
   * Returns additional metadata about the server.
   *
   * @return metadata map, may be empty
   */
  public Map<String, Object> getMetadata() {
    return metadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    McpHealthResponse that = (McpHealthResponse) o;
    return isHealthy == that.isHealthy
        && Objects.equals(serverVersion, that.serverVersion)
        && Objects.equals(metadata, that.metadata);
  }

  @Override
  public int hashCode() {
    return Objects.hash(isHealthy, serverVersion, metadata);
  }

  @Override
  public String toString() {
    return "McpHealthResponse{"
        + "isHealthy="
        + isHealthy
        + ", serverVersion='"
        + serverVersion
        + '\''
        + ", metadata="
        + metadata
        + '}';
  }

  /**
   * Creates a builder for constructing McpHealthResponse instances.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link McpHealthResponse}. */
  public static class Builder {
    private Boolean isHealthy;
    private String serverVersion;
    private Map<String, Object> metadata;

    private Builder() {}

    /**
     * Sets the healthy status.
     *
     * @param isHealthy whether the server is healthy
     * @return this builder
     */
    public Builder isHealthy(boolean isHealthy) {
      this.isHealthy = isHealthy;
      return this;
    }

    /**
     * Sets the server version.
     *
     * @param serverVersion the server version
     * @return this builder
     */
    public Builder serverVersion(String serverVersion) {
      this.serverVersion = serverVersion;
      return this;
    }

    /**
     * Sets the metadata map.
     *
     * @param metadata the metadata
     * @return this builder
     */
    public Builder metadata(Map<String, Object> metadata) {
      this.metadata = metadata;
      return this;
    }

    /**
     * Builds the McpHealthResponse.
     *
     * @return the constructed response
     */
    public McpHealthResponse build() {
      return new McpHealthResponse(isHealthy != null ? isHealthy : false, serverVersion, metadata);
    }
  }
}
