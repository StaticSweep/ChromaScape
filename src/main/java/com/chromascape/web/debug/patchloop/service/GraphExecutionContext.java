package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Context for graph execution.
 *
 * <p>Contains configuration, policy, and runtime data for a graph execution.
 */
public class GraphExecutionContext {

  /** Execution ID */
  private final String executionId;

  /** Associated iteration ID */
  private final String iterationId;

  /** Script name to execute */
  private final String scriptName;

  /** Goal prompt */
  private final String goalPrompt;

  /** Additional context */
  private final String context;

  /** Maximum number of attempts */
  private final Integer maxAttempts;

  /** Success criteria (JSON) */
  private final JsonNode successCriteria;

  /** Safety policy */
  private final GraphExecuteRequest.PatchPolicyConfig policy;

  /** Execution mode */
  private final String executionMode;

  /** Evidence collection configuration */
  private final EvidenceConfigDto evidenceConfig;

  /** Runtime data that can be updated during execution */
  private final Map<String, Object> runtimeData;

  private GraphExecutionContext(Builder builder) {
    this.executionId = builder.executionId;
    this.iterationId = builder.iterationId;
    this.scriptName = builder.scriptName;
    this.goalPrompt = builder.goalPrompt;
    this.context = builder.context;
    this.maxAttempts = builder.maxAttempts;
    this.successCriteria = builder.successCriteria;
    this.policy = builder.policy;
    this.executionMode = builder.executionMode;
    this.evidenceConfig = builder.evidenceConfig;
    this.runtimeData = builder.runtimeData;
  }

  public String getExecutionId() {
    return executionId;
  }

  public String getIterationId() {
    return iterationId;
  }

  public String getScriptName() {
    return scriptName;
  }

  public String getGoalPrompt() {
    return goalPrompt;
  }

  public String getContext() {
    return context;
  }

  public Integer getMaxAttempts() {
    return maxAttempts;
  }

  public JsonNode getSuccessCriteria() {
    return successCriteria;
  }

  public GraphExecuteRequest.PatchPolicyConfig getPolicy() {
    return policy;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public EvidenceConfigDto getEvidenceConfig() {
    return evidenceConfig;
  }

  public Map<String, Object> getRuntimeData() {
    return runtimeData;
  }

  /** Gets a runtime value by key. */
  public Object getRuntimeValue(String key) {
    return runtimeData.get(key);
  }

  /** Sets a runtime value. */
  public void setRuntimeValue(String key, Object value) {
    runtimeData.put(key, value);
  }

  /** Returns true if this is an autonomous fix loop. */
  public boolean isAutofixLoop() {
    return "autofix_loop".equals(executionMode);
  }

  /** Returns true if auto-rollback on failure is enabled. */
  public boolean isAutoRollbackEnabled() {
    return policy != null && Boolean.TRUE.equals(policy.getAutoRollbackOnFailure());
  }

  /** Creates a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for GraphExecutionContext. */
  public static class Builder {
    private String executionId;
    private String iterationId;
    private String scriptName;
    private String goalPrompt;
    private String context;
    private Integer maxAttempts;
    private JsonNode successCriteria;
    private GraphExecuteRequest.PatchPolicyConfig policy;
    private String executionMode;
    private EvidenceConfigDto evidenceConfig;
    private Map<String, Object> runtimeData;

    public Builder executionId(String executionId) {
      this.executionId = executionId;
      return this;
    }

    public Builder iterationId(String iterationId) {
      this.iterationId = iterationId;
      return this;
    }

    public Builder scriptName(String scriptName) {
      this.scriptName = scriptName;
      return this;
    }

    public Builder goalPrompt(String goalPrompt) {
      this.goalPrompt = goalPrompt;
      return this;
    }

    public Builder context(String context) {
      this.context = context;
      return this;
    }

    public Builder maxAttempts(Integer maxAttempts) {
      this.maxAttempts = maxAttempts;
      return this;
    }

    public Builder successCriteria(JsonNode successCriteria) {
      this.successCriteria = successCriteria;
      return this;
    }

    public Builder policy(GraphExecuteRequest.PatchPolicyConfig policy) {
      this.policy = policy;
      return this;
    }

    public Builder executionMode(String executionMode) {
      this.executionMode = executionMode;
      return this;
    }

    public Builder evidenceConfig(EvidenceConfigDto evidenceConfig) {
      this.evidenceConfig = evidenceConfig;
      return this;
    }

    public Builder runtimeData(Map<String, Object> runtimeData) {
      this.runtimeData = runtimeData;
      return this;
    }

    public GraphExecutionContext build() {
      if (executionId == null) {
        throw new IllegalArgumentException("executionId is required");
      }
      if (iterationId == null) {
        throw new IllegalArgumentException("iterationId is required");
      }
      if (scriptName == null) {
        throw new IllegalArgumentException("scriptName is required");
      }
      return new GraphExecutionContext(this);
    }
  }
}
