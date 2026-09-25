package com.chromascape.web.debug.patchloop.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Response from executing a Mermaid-defined patch loop graph.
 *
 * <p>Contains the execution ID and compiled graph structure.
 */
public class GraphExecuteResponse {

  /** Unique execution ID */
  @JsonProperty("executionId")
  private String executionId;

  /** Associated iteration ID */
  @JsonProperty("iterationId")
  private String iterationId;

  /** Compiled graph structure */
  @JsonProperty("compiledGraph")
  private CompiledGraphInfo compiledGraph;

  /** Current execution status */
  @JsonProperty("status")
  private String status;

  /** WebSocket URL for real-time updates */
  @JsonProperty("wsUrl")
  private String wsUrl;

  /** REST API URL for this execution */
  @JsonProperty("apiUrl")
  private String apiUrl;

  /** Validation warnings (if any) */
  @JsonProperty("warnings")
  private List<String> warnings;

  public GraphExecuteResponse() {}

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String iterationId) {
    this.iterationId = iterationId;
  }

  public CompiledGraphInfo getCompiledGraph() {
    return compiledGraph;
  }

  public void setCompiledGraph(CompiledGraphInfo compiledGraph) {
    this.compiledGraph = compiledGraph;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getWsUrl() {
    return wsUrl;
  }

  public void setWsUrl(String wsUrl) {
    this.wsUrl = wsUrl;
  }

  public String getApiUrl() {
    return apiUrl;
  }

  public void setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GraphExecuteResponse that = (GraphExecuteResponse) o;
    return Objects.equals(executionId, that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId);
  }

  @Override
  public String toString() {
    return "GraphExecuteResponse{"
        + "executionId='"
        + executionId
        + '\''
        + ", iterationId='"
        + iterationId
        + '\''
        + ", status='"
        + status
        + '\''
        + '}';
  }

  /** Information about the compiled graph structure. */
  public static class CompiledGraphInfo {

    @JsonProperty("nodeCount")
    private Integer nodeCount;

    @JsonProperty("edgeCount")
    private Integer edgeCount;

    @JsonProperty("entryNodeId")
    private String entryNodeId;

    @JsonProperty("terminalNodeIds")
    private List<String> terminalNodeIds;

    @JsonProperty("nodes")
    private List<GraphNodeInfo> nodes;

    public Integer getNodeCount() {
      return nodeCount;
    }

    public void setNodeCount(Integer nodeCount) {
      this.nodeCount = nodeCount;
    }

    public Integer getEdgeCount() {
      return edgeCount;
    }

    public void setEdgeCount(Integer edgeCount) {
      this.edgeCount = edgeCount;
    }

    public String getEntryNodeId() {
      return entryNodeId;
    }

    public void setEntryNodeId(String entryNodeId) {
      this.entryNodeId = entryNodeId;
    }

    public List<String> getTerminalNodeIds() {
      return terminalNodeIds;
    }

    public void setTerminalNodeIds(List<String> terminalNodeIds) {
      this.terminalNodeIds = terminalNodeIds;
    }

    public List<GraphNodeInfo> getNodes() {
      return nodes;
    }

    public void setNodes(List<GraphNodeInfo> nodes) {
      this.nodes = nodes;
    }
  }

  /** Information about a single node in the compiled graph. */
  public static class GraphNodeInfo {

    @JsonProperty("id")
    private String id;

    @JsonProperty("type")
    private String type;

    @JsonProperty("label")
    private String label;

    @JsonProperty("config")
    private JsonNode config;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public JsonNode getConfig() {
      return config;
    }

    public void setConfig(JsonNode config) {
      this.config = config;
    }
  }
}
