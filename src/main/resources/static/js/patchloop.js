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
    this.analyses = [];
    this.evidence = [];
    this.artifactManager = null;
  }

  /**
   * Connects to the patch loop WebSocket.
   * @param {string} executionId - The execution ID to subscribe to
   */
  connect(executionId) {
    const wsBase = this.getWebSocketBase();

    // Store the executionId for later use
    if (executionId) {
      this.executionId = executionId;
    }

    // Include executionId in the WebSocket URL for server-side filtering
    const wsUrl = this.executionId
      ? `${wsBase}/ws/debug/patch-loop/${this.executionId}`
      : `${wsBase}/ws/debug/patch-loop`;

    this.ws = new WebSocket(wsUrl);

    this.ws.onopen = () => {
      console.log('PatchLoop WebSocket connected');
      this.onConnected();

      // Send subscription message if not using URL-based filtering
      if (!this.executionId && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({
          action: "subscribe",
          executionId: this.executionId
        }));
      }
    };

    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        // Filter messages by executionId if needed
        if (this.executionId && msg.executionId && msg.executionId !== this.executionId) {
          return; // Ignore messages for other executions
        }
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

/**
 * Manages artifact display and interaction for the patch loop.
 *
 * Handles loading, rendering, and updating of patch, validation, retest,
 * analysis, and evidence artifacts.
 */
class PatchLoopArtifactManager {
  constructor(executionId) {
    this.executionId = executionId;
    this.artifactsUrl = `/api/debug/patch-loop/${executionId}/artifacts`;
    this.artifacts = {
      patches: [],
      validations: [],
      retests: [],
      analyses: [],
      evidence: []
    };
  }

  /**
   * Loads all artifacts from the server.
   */
  async loadArtifacts() {
    try {
      const response = await fetch(this.artifactsUrl);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const data = await response.json();

      this.artifacts.patches = data.patches || [];
      this.artifacts.validations = data.validations || [];
      this.artifacts.retests = data.retests || [];
      this.artifacts.analyses = data.analyses || [];
      this.artifacts.evidence = data.evidence || [];

      this.renderAll();
    } catch (error) {
      console.error('Failed to load artifacts:', error);
    }
  }

  /**
   * Refreshes artifacts by reloading from the server.
   */
  async refresh() {
    await this.loadArtifacts();
  }

  /**
   * Renders all artifact tabs.
   */
  renderAll() {
    this.renderPatches();
    this.renderValidations();
    this.renderRetests();
    this.renderAnalyses();
    this.renderEvidence();
  }

  /**
   * Renders patches tab content.
   */
  renderPatches() {
    const container = document.getElementById('patchContent');
    if (!container) return;

    if (!this.artifacts.patches || this.artifacts.patches.length === 0) {
      container.innerHTML = '<p class="text-muted small">No patches generated yet</p>';
      return;
    }

    container.innerHTML = this.artifacts.patches.map(patch => `
      <div class="artifact-card mb-2 p-2 border rounded">
        <div class="d-flex justify-content-between align-items-start mb-1">
          <h6 class="mb-0">
            <i class="bi bi-code-slash me-1"></i>Patch ${patch.patchId}
          </h6>
          <span class="badge bg-${patch.confidence === 'HIGH' ? 'success' : patch.confidence === 'MEDIUM' ? 'warning' : 'secondary'}">
            ${patch.confidence || 'UNKNOWN'}
          </span>
        </div>
        ${patch.rationale ? `<p class="small text-muted mb-1">${this.escapeHtml(patch.rationale)}</p>` : ''}
        ${patch.diffText ? `
          <details class="mb-1">
            <summary class="small cursor-pointer">View diff</summary>
            <pre class="diff-view small bg-light p-2 mt-1 rounded">${this.escapeHtml(patch.diffText)}</pre>
          </details>
        ` : ''}
        <div class="small text-muted">
          <span><i class="bi bi-file-earmark-code me-1"></i>${patch.filesChanged || 0} files</span>
          <span class="ms-2"><i class="bi bi-type me-1"></i>${patch.linesChanged || 0} lines</span>
          <span class="ms-2"><i class="bi bi-git me-1"></i>${patch.gitBranch || 'N/A'}</span>
        </div>
        ${patch.validationPassed !== undefined ? `
          <div class="mt-1">
            <span class="badge bg-${patch.validationPassed ? 'success' : 'danger'}">
              ${patch.validationPassed ? 'Validated' : 'Failed Validation'}
            </span>
          </div>
        ` : ''}
      </div>
    `).join('');
  }

  /**
   * Renders validations tab content.
   */
  renderValidations() {
    const container = document.getElementById('validationContent');
    if (!container) return;

    if (!this.artifacts.validations || this.artifacts.validations.length === 0) {
      container.innerHTML = '<p class="text-muted small">No validation results yet</p>';
      return;
    }

    container.innerHTML = this.artifacts.validations.map(validation => `
      <div class="artifact-card mb-2 p-2 border rounded">
        <div class="d-flex justify-content-between align-items-center mb-1">
          <h6 class="mb-0">
            <i class="bi bi-check-circle me-1"></i>Validation
            ${validation.patchId ? `for ${validation.patchId}` : `Attempt ${validation.attemptNumber || 1}`}
          </h6>
          <span class="badge bg-${validation.passed ? 'success' : 'danger'}">
            ${validation.passed ? 'PASSED' : 'FAILED'}
          </span>
        </div>
        ${validation.output ? `
          <details class="mb-1">
            <summary class="small cursor-pointer">View output</summary>
            <pre class="small bg-light p-2 mt-1 rounded">${this.escapeHtml(validation.output)}</pre>
          </details>
        ` : ''}
        <div class="small text-muted">
          <span>Exit code: ${validation.exitCode || 'N/A'}</span>
          ${validation.durationMs ? `<span class="ms-2">Duration: ${this.formatDuration(validation.durationMs)}</span>` : ''}
        </div>
      </div>
    `).join('');
  }

  /**
   * Renders retests tab content.
   */
  renderRetests() {
    const container = document.getElementById('retestContent');
    if (!container) return;

    if (!this.artifacts.retests || this.artifacts.retests.length === 0) {
      container.innerHTML = '<p class="text-muted small">No retest results yet</p>';
      return;
    }

    container.innerHTML = this.artifacts.retests.map(retest => `
      <div class="artifact-card mb-2 p-2 border rounded">
        <div class="d-flex justify-content-between align-items-center mb-1">
          <h6 class="mb-0">
            <i class="bi bi-arrow-repeat me-1"></i>Retest Attempt ${retest.attemptNumber || 1}
          </h6>
          <span class="badge bg-${retest.success ? 'success' : 'warning'}">
            ${retest.success ? 'SUCCESS' : 'NO_CHANGE'}
          </span>
        </div>
        ${retest.comparisonSummary ? `<p class="small mb-1">${this.escapeHtml(retest.comparisonSummary)}</p>` : ''}
        ${retest.improvements && retest.improvements.length > 0 ? `
          <div class="mb-1">
            <small class="text-success"><i class="bi bi-arrow-up-right me-1"></i>Improvements:</small>
            <ul class="small mb-0 ps-3">
              ${retest.improvements.map(i => `<li>${this.escapeHtml(i)}</li>`).join('')}
            </ul>
          </div>
        ` : ''}
        ${retest.regressions && retest.regressions.length > 0 ? `
          <div class="mb-1">
            <small class="text-danger"><i class="bi bi-arrow-down-right me-1"></i>Regressions:</small>
            <ul class="small mb-0 ps-3">
              ${retest.regressions.map(r => `<li>${this.escapeHtml(r)}</li>`).join('')}
            </ul>
          </div>
        ` : ''}
      </div>
    `).join('');
  }

  /**
   * Renders analyses tab content.
   */
  renderAnalyses() {
    const container = document.getElementById('analysisContent');
    if (!container) return;

    if (!this.artifacts.analyses || this.artifacts.analyses.length === 0) {
      container.innerHTML = '<p class="text-muted small">No analysis results yet</p>';
      return;
    }

    container.innerHTML = this.artifacts.analyses.map(analysis => `
      <div class="artifact-card mb-2 p-2 border rounded">
        <div class="d-flex justify-content-between align-items-start mb-1">
          <h6 class="mb-0">
            <i class="bi bi-lightbulb me-1"></i>Analysis ${analysis.analysisId || ''}
          </h6>
          <span class="badge bg-${analysis.confidence === 'HIGH' ? 'success' : analysis.confidence === 'MEDIUM' ? 'warning' : 'secondary'}">
            ${analysis.confidence || 'UNKNOWN'}
          </span>
        </div>
        ${analysis.diagnosisSummary ? `<p class="small mb-1">${this.escapeHtml(analysis.diagnosisSummary)}</p>` : ''}
        ${analysis.rootCause ? `
          <div class="mb-1">
            <small class="text-muted">Root Cause:</small>
            <p class="small mb-0">${this.escapeHtml(analysis.rootCause)}</p>
          </div>
        ` : ''}
        ${analysis.recommendationCount !== undefined ? `
          <small class="text-muted"><i class="bi bi-list-check me-1"></i>${analysis.recommendationCount} recommendations</small>
        ` : ''}
      </div>
    `).join('');
  }

  /**
   * Renders evidence tab content (new tab to be added to UI).
   */
  renderEvidence() {
    const container = document.getElementById('evidenceContent');
    if (!container) return;

    if (!this.artifacts.evidence || this.artifacts.evidence.length === 0) {
      container.innerHTML = '<p class="text-muted small">No evidence collected yet</p>';
      return;
    }

    // Group by evidence type
    const grouped = {};
    this.artifacts.evidence.forEach(e => {
      if (!grouped[e.evidenceType]) {
        grouped[e.evidenceType] = [];
      }
      grouped[e.evidenceType].push(e);
    });

    container.innerHTML = Object.entries(grouped).map(([type, items]) => `
      <div class="artifact-card mb-2 p-2 border rounded">
        <h6 class="mb-1">
          <i class="bi bi-box-seam me-1"></i>${this.formatEvidenceType(type)}
          <span class="badge bg-secondary ms-1">${items.length}</span>
        </h6>
        ${items.map(item => `
          <div class="small mb-1">
            <span class="text-muted">Count: ${item.count || 0}</span>
            ${item.summary ? `<span class="ms-2">${this.escapeHtml(item.summary)}</span>` : ''}
          </div>
        `).join('')}
      </div>
    `).join('');
  }

  /**
   * Formats evidence type for display.
   */
  formatEvidenceType(type) {
    return type.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
  }

  /**
   * Escapes HTML to prevent XSS.
   */
  escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  /**
   * Formats duration for display.
   */
  formatDuration(ms) {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }
}

// Export for use in debugger.js or other scripts
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { PatchLoopManager, PatchLoopArtifactManager };
}
