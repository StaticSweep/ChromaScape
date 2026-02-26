/**
 * Patch Loop Manager - Handles autonomous BMAD loop UI and WebSocket communication.
 *
 * Provides real-time updates for graph execution, node timeline, and artifacts.
 */
class PatchLoopManager {
  constructor() {
    this.ws = null;
    this.executionId = null;
    this.iterationId = null;
    this.currentNode = null;
    this.attemptCount = 0;
    this.nodeStates = new Map();
    this.patches = [];
    this.validations = [];
    this.retests = [];
  }

  /**
   * Connects to the patch loop WebSocket.
   */
  connect() {
    const wsBase = this.getWebSocketBase();
    this.ws = new WebSocket(`${wsBase}/ws/debug/patch-loop`);

    this.ws.onopen = () => {
      console.log('PatchLoop WebSocket connected');
      this.onConnected();
    };

    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        this.handleMessage(msg);
      } catch (e) {
        console.error('Failed to parse WebSocket message:', e);
      }
    };

    this.ws.onclose = () => {
      console.log('PatchLoop WebSocket disconnected');
      this.onDisconnected();
    };

    this.ws.onerror = (error) => {
      console.error('PatchLoop WebSocket error:', error);
    };
  }

  /**
   * Disconnects the WebSocket.
   */
  disconnect() {
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  /**
   * Executes a Mermaid-defined graph.
   */
  async executeGraph(mermaid, scriptName, goalPrompt, options = {}) {
    const request = {
      mermaid: mermaid,
      scriptName: scriptName,
      goalPrompt: goalPrompt,
      context: options.context || '',
      maxAttempts: options.maxAttempts || 3,
      executionMode: options.executionMode || 'analysis_only',
      successCriteria: options.successCriteria || {},
      policy: options.policy || null,
      evidenceConfig: options.evidenceConfig || null
    };

    try {
      const response = await fetch('/api/debug/patch-loop/execute-graph', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const result = await response.json();
      this.executionId = result.executionId;
      this.iterationId = result.iterationId;

      this.onGraphExecuted(result);
      return result;

    } catch (error) {
      this.onError('Failed to execute graph: ' + error.message);
      throw error;
    }
  }

  /**
   * Gets the current graph state.
   */
  async getGraphState(iterationId) {
    try {
      const response = await fetch(`/api/debug/patch-loop/${iterationId}/graph`);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const state = await response.json();
      this.onGraphStateUpdate(state);
      return state;
    } catch (error) {
      console.error('Failed to get graph state:', error);
      throw error;
    }
  }

  /**
   * Controls execution (pause/resume/cancel).
   */
  async controlExecution(iterationId, action, reason = '') {
    try {
      const response = await fetch(`/api/debug/patch-loop/${iterationId}/control`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, reason })
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      this.onControlAcknowledged(action);
    } catch (error) {
      this.onError('Control action failed: ' + error.message);
      throw error;
    }
  }

  /**
   * Gets artifacts from the execution.
   */
  async getArtifacts(iterationId) {
    try {
      const response = await fetch(`/api/debug/patch-loop/${iterationId}/artifacts`);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const artifacts = await response.json();
      this.onArtifactsLoaded(artifacts);
      return artifacts;
    } catch (error) {
      console.error('Failed to get artifacts:', error);
      throw error;
    }
  }

  // Event handlers (override these in your UI)

  onConnected() {
    console.log('Connected to patch loop updates');
  }

  onDisconnected() {
    console.log('Disconnected from patch loop updates');
  }

  onGraphExecuted(response) {
    console.log('Graph executed:', response);
  }

  onGraphStateUpdate(state) {
    console.log('Graph state updated:', state);
  }

  onControlAcknowledged(action) {
    console.log('Control action acknowledged:', action);
  }

  onArtifactsLoaded(artifacts) {
    console.log('Artifacts loaded:', artifacts);
  }

  onError(error) {
    console.error('Patch loop error:', error);
  }

  // WebSocket message handling

  handleMessage(msg) {
    switch (msg.type) {
      case 'CONNECTED':
        this.onConnected();
        break;

      case 'NODE_STARTED':
        this.handleNodeStarted(msg.data);
        break;

      case 'NODE_COMPLETED':
        this.handleNodeCompleted(msg.data);
        break;

      case 'NODE_FAILED':
        this.handleNodeFailed(msg.data);
        break;

      case 'PATCH_GENERATED':
        this.handlePatchGenerated(msg.data);
        break;

      case 'PATCH_APPLIED':
        this.handlePatchApplied(msg.data);
        break;

      case 'VALIDATION_RESULT':
        this.handleValidationResult(msg.data);
        break;

      case 'RETEST_RESULT':
        this.handleRetestResult(msg.data);
        break;

      case 'ITERATION_COMPLETED':
        this.handleIterationCompleted(msg.data);
        break;

      case 'ITERATION_FAILED':
        this.handleIterationFailed(msg.data);
        break;

      case 'STATUS_CHANGED':
        this.handleStatusChanged(msg.data);
        break;

      default:
        console.log('Unknown message type:', msg.type);
    }
  }

  handleNodeStarted(data) {
    this.currentNode = data.nodeId;
    this.nodeStates.set(data.nodeId, { status: 'running', startedAt: data.timestamp });
    this.renderNodeTimeline();
  }

  handleNodeCompleted(data) {
    this.nodeStates.set(data.nodeId, {
      status: 'completed',
      duration: data.duration,
      outputSummary: data.outputSummary
    });
    this.renderNodeTimeline();
  }

  handleNodeFailed(data) {
    this.nodeStates.set(data.nodeId, {
      status: 'failed',
      error: data.error,
      retryCount: data.retryCount
    });
    this.renderNodeTimeline();
  }

  handlePatchGenerated(data) {
    this.patches.push({
      patchId: data.patchId,
      diffPreview: data.diffPreview,
      affectedFiles: data.affectedFiles,
      timestamp: data.timestamp
    });
    this.renderPatchPreview(data);
  }

  handlePatchApplied(data) {
    const patch = this.patches.find(p => p.patchId === data.patchId);
    if (patch) {
      patch.applied = true;
      patch.gitBranch = data.gitBranch;
      patch.filesChanged = data.filesChanged;
      patch.linesChanged = data.linesChanged;
    }
    this.renderPatchApplied(data);
  }

  handleValidationResult(data) {
    this.validations.push({
      passed: data.passed,
      outputSummary: data.outputSummary,
      timestamp: data.timestamp
    });
    this.renderValidationResult(data);
  }

  handleRetestResult(data) {
    this.retests.push({
      success: data.success,
      comparisonSummary: data.comparisonSummary,
      timestamp: data.timestamp
    });
    this.renderRetestResult(data);
  }

  handleIterationCompleted(data) {
    this.onGraphCompleted(data);
  }

  handleIterationFailed(data) {
    this.onError('Iteration failed: ' + data.error);
  }

  handleStatusChanged(data) {
    this.onStatusChanged(data.status);
  }

  // Render methods (to be overridden or customized)

  renderNodeTimeline() {
    // Override this to render the node timeline in your UI
    console.log('Node timeline:', Array.from(this.nodeStates.entries()));
  }

  renderPatchPreview(data) {
    // Override this to render the patch preview
    console.log('Patch generated:', data);
  }

  renderPatchApplied(data) {
    // Override this to show patch applied status
    console.log('Patch applied:', data);
  }

  renderValidationResult(data) {
    // Override this to show validation results
    console.log('Validation result:', data);
  }

  renderRetestResult(data) {
    // Override this to show retest results
    console.log('Retest result:', data);
  }

  onGraphCompleted(data) {
    // Override to handle completion
    console.log('Graph completed:', data);
  }

  onStatusChanged(status) {
    // Override to handle status changes
    console.log('Status changed:', status);
  }

  // Utility methods

  getWebSocketBase() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    return `${protocol}//${host}`;
  }

  formatDuration(ms) {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }
}

// Export for use in debugger.js or other scripts
if (typeof module !== 'undefined' && module.exports) {
  module.exports = PatchLoopManager;
}
