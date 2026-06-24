/**
 * Account Task Planner - Manages task plan generation, review, and execution.
 *
 * Provides UI for generating task plans from evidence, reviewing items,
 * approving/rejecting tasks, and triggering patch loop execution.
 */
class AccountTaskPlanner {
  static BUILDER_GOAL_BUNDLE_VERSION = '7';
  static UNASSIGNED_FILTER = '__UNASSIGNED__';
  static BUILDER_GOAL_PUBLISH_TIMEOUT_MS = 8000;

  constructor() {
    this.plans = [];
    this.allPlans = [];
    this.selectedPlanId = null;
    this.selectedPlan = null;
    this.panelVisible = false;
    this.scriptCatalog = [];
    this.itemSelectionState = new Map();
    this.selectedAccountName = localStorage.getItem('taskPlanner.selectedAccount') || '';
    this.importedBuilderGoal = null;
    this.builderGoalRuns = [];
    this.builderGoalClients = [];
    this.selectedBuilderGoalClientId = localStorage.getItem('taskPlanner.selectedBuilderGoalClient') || '';
    this.builderGoalClientRefreshHandle = null;
    this.builderGoalBundleMismatch = false;
    this.builderGoalAnalysisModal = null;
    this.builderGoalAnalysisDraft = null;
    this.builderGoalAnalysisIterationId = null;
  }

  /**
   * Initializes the task planner UI and event handlers.
   */
  init() {
    this.bindElements();
    this.bindEvents();
    this.validateBuilderGoalBundleVersion();
    console.info(
      `[AccountTaskPlanner] Initialized planner bundle v${AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION}`
    );
    this.loadPlans();
    this.loadSourceSessions();
    this.loadScriptCatalog();
    if (!this.builderGoalBundleMismatch) {
      this.loadBuilderGoalClients();
      this.loadBuilderGoalRuns();
    }
    this.loadStabilizationKpis();
    if (!this.builderGoalBundleMismatch && !this.builderGoalClientRefreshHandle) {
      this.builderGoalClientRefreshHandle = setInterval(() => {
        this.loadBuilderGoalClients();
        this.loadBuilderGoalRuns();
      }, 10000);
    }
  }

  /**
   * Binds DOM element references.
   */
  bindElements() {
    this.panel = document.getElementById('taskPlannerPanel');
    this.toggleBtn = document.getElementById('taskPlannerToggleBtn');
    this.togglePlannerBtn = document.getElementById('toggleTaskPlannerBtn');
    this.refreshBtn = document.getElementById('refreshPlansBtn');
    this.content = document.getElementById('taskPlannerContent');

    // Generate form
    this.intentInput = document.getElementById('taskPlanIntentInput');
    this.sourceSessionSelect = document.getElementById('taskPlanSourceSession');
    this.generateBtn = document.getElementById('generatePlanBtn');
    this.builderGoalRunnerSection = document.getElementById('builderGoalRunnerSection');
    this.builderGoalPayloadInput = document.getElementById('builderGoalPayloadInput');
    this.importBuilderGoalBtn = document.getElementById('importBuilderGoalBtn');
    this.runBuilderGoalBtn = document.getElementById('runBuilderGoalBtn');
    this.builderGoalClientSelect = document.getElementById('builderGoalClientSelect');
    this.builderGoalClientStatus = document.getElementById('builderGoalClientStatus');
    this.builderGoalImportResult = document.getElementById('builderGoalImportResult');
    this.builderGoalRunsList = document.getElementById('builderGoalRunsList');
    this.refreshBuilderGoalRunsBtn = document.getElementById('refreshBuilderGoalRunsBtn');
    this.builderGoalAnalysisModalElement = document.getElementById('builderGoalAnalysisModal');
    this.builderGoalAnalysisPrompt = document.getElementById('builderGoalAnalysisPrompt');
    this.builderGoalAnalysisContext = document.getElementById('builderGoalAnalysisContext');
    this.builderGoalAnalysisStatus = document.getElementById('builderGoalAnalysisStatus');
    this.builderGoalAnalysisEvidenceMeta = document.getElementById('builderGoalAnalysisEvidenceMeta');
    this.builderGoalAnalysisEvidenceGroups = document.getElementById('builderGoalAnalysisEvidenceGroups');
    this.builderGoalAnalysisResetBtn = document.getElementById('builderGoalAnalysisResetBtn');
    this.builderGoalAnalysisSelectAllBtn = document.getElementById('builderGoalAnalysisSelectAllBtn');
    this.builderGoalAnalysisClearAllBtn = document.getElementById('builderGoalAnalysisClearAllBtn');
    this.submitBuilderGoalAnalysisBtn = document.getElementById('submitBuilderGoalAnalysisBtn');

    // Plans list
    this.plansList = document.getElementById('plansList');
    this.planCountBadge = document.getElementById('planCountBadge');
    this.accountSelect = document.getElementById('taskPlanAccountSelect');

    // Plan detail
    this.planDetailSection = document.getElementById('planDetailSection');
    this.planDetailTitle = document.getElementById('planDetailTitle');
    this.planDetailStatus = document.getElementById('planDetailStatus');
    this.planDetailIntent = document.getElementById('planDetailIntent');
    this.planItemsList = document.getElementById('planItemsList');
    this.approvePlanBtn = document.getElementById('approvePlanBtn');
    this.rejectPlanBtn = document.getElementById('rejectPlanBtn');

    // Stabilization KPI panel
    this.stabilizationFamilyFilter = document.getElementById('stabilizationFamilyFilter');
    this.stabilizationHistoryLimit = document.getElementById('stabilizationHistoryLimit');
    this.stabilizationRefreshBtn = document.getElementById('stabilizationRefreshBtn');
    this.stabilizationSummary = document.getElementById('stabilizationSummary');
    this.stabilizationScripts = document.getElementById('stabilizationScripts');
    this.stabilizationHistory = document.getElementById('stabilizationHistory');
    this.stabilizationRegressions = document.getElementById('stabilizationRegressions');

    if (this.builderGoalAnalysisModalElement && window.bootstrap?.Modal) {
      this.builderGoalAnalysisModal = new bootstrap.Modal(this.builderGoalAnalysisModalElement);
    }
  }

  validateBuilderGoalBundleVersion() {
    const expectedVersion =
      this.builderGoalRunnerSection?.dataset?.plannerBundleVersion?.trim() || null;
    if (!expectedVersion || expectedVersion === AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION) {
      return;
    }

    this.builderGoalBundleMismatch = true;
    console.error(
      '[AccountTaskPlanner] Builder Goal Runner bundle version mismatch detected',
      {
        expectedVersion,
        loadedVersion: AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION
      }
    );

    if (this.builderGoalPayloadInput) {
      this.builderGoalPayloadInput.disabled = true;
    }
    if (this.builderGoalClientSelect) {
      this.builderGoalClientSelect.disabled = true;
      this.builderGoalClientSelect.innerHTML =
        '<option value="">Refresh debugger to load the latest Builder Goal Runner</option>';
    }
    if (this.importBuilderGoalBtn) {
      this.importBuilderGoalBtn.disabled = true;
    }
    if (this.runBuilderGoalBtn) {
      this.runBuilderGoalBtn.disabled = true;
    }
    if (this.refreshBuilderGoalRunsBtn) {
      this.refreshBuilderGoalRunsBtn.disabled = true;
    }

    this.renderBuilderGoalClientStatus(
      'Debugger assets are out of date. Hard refresh this page to load the latest Builder Goal Runner.',
      'danger'
    );
    this.renderBuilderGoalImportResult({
      error:
        'Builder Goal Runner is out of date. Hard refresh /debugger and try again.'
    });
  }

  /**
   * Binds event handlers to UI elements.
   */
  bindEvents() {
    if (this.toggleBtn) {
      this.toggleBtn.addEventListener('click', () => this.togglePanel());
    }
    if (this.togglePlannerBtn) {
      this.togglePlannerBtn.addEventListener('click', () => this.toggleContent());
    }
    if (this.refreshBtn) {
      this.refreshBtn.addEventListener('click', () => {
        this.loadPlans();
        this.loadStabilizationKpis();
      });
    }
    if (this.accountSelect) {
      this.accountSelect.addEventListener('change', () => {
        this.selectedAccountName = this.accountSelect.value || '';
        localStorage.setItem('taskPlanner.selectedAccount', this.selectedAccountName);
        this.selectedPlanId = null;
        this.selectedPlan = null;
        if (this.planDetailSection) {
          this.planDetailSection.style.display = 'none';
        }
        this.loadPlans();
      });
    }
    if (this.generateBtn) {
      this.generateBtn.addEventListener('click', () => this.generatePlan());
    }
    if (this.importBuilderGoalBtn) {
      this.importBuilderGoalBtn.addEventListener('click', () => this.importBuilderGoal());
    }
    if (this.runBuilderGoalBtn) {
      this.runBuilderGoalBtn.addEventListener('click', () => this.runBuilderGoal());
    }
    if (this.builderGoalClientSelect) {
      this.builderGoalClientSelect.addEventListener('change', () => {
        this.selectedBuilderGoalClientId = this.builderGoalClientSelect.value || '';
        if (this.selectedBuilderGoalClientId) {
          localStorage.setItem('taskPlanner.selectedBuilderGoalClient', this.selectedBuilderGoalClientId);
        } else {
          localStorage.removeItem('taskPlanner.selectedBuilderGoalClient');
        }
        this.renderBuilderGoalClientStatus();
        this.updateBuilderGoalRunButtonState();
      });
    }
    if (this.refreshBuilderGoalRunsBtn) {
      this.refreshBuilderGoalRunsBtn.addEventListener('click', () => {
        this.loadBuilderGoalClients();
        this.loadBuilderGoalRuns();
      });
    }
    if (this.builderGoalRunsList) {
      this.builderGoalRunsList.addEventListener('click', (event) => {
        const button = event.target.closest('[data-builder-run-action]');
        if (!button) return;
        const iterationId = button.dataset.iterationId;
        if (!iterationId) return;
        const action = button.dataset.builderRunAction;
        if (action === 'analyze') {
          this.openBuilderGoalAnalysisEditor(iterationId);
        } else if (action === 'fix') {
          this.fixAndRelaunchBuilderGoalRun(iterationId);
        }
      });
    }
    if (this.builderGoalAnalysisResetBtn) {
      this.builderGoalAnalysisResetBtn.addEventListener('click', () => this.resetBuilderGoalAnalysisPrompt());
    }
    if (this.builderGoalAnalysisSelectAllBtn) {
      this.builderGoalAnalysisSelectAllBtn.addEventListener('click', () => this.setAllBuilderGoalEvidenceSelections(true));
    }
    if (this.builderGoalAnalysisClearAllBtn) {
      this.builderGoalAnalysisClearAllBtn.addEventListener('click', () => this.setAllBuilderGoalEvidenceSelections(false));
    }
    if (this.builderGoalAnalysisEvidenceGroups) {
      this.builderGoalAnalysisEvidenceGroups.addEventListener('change', (event) => {
        if (event.target?.matches('.builder-goal-analysis-evidence-checkbox')) {
          this.updateBuilderGoalAnalysisEvidenceMeta();
        }
      });
    }
    if (this.submitBuilderGoalAnalysisBtn) {
      this.submitBuilderGoalAnalysisBtn.addEventListener('click', () => this.submitBuilderGoalAnalysis());
    }
    if (this.builderGoalAnalysisModalElement) {
      this.builderGoalAnalysisModalElement.addEventListener('hidden.bs.modal', () => {
        this.builderGoalAnalysisDraft = null;
        this.builderGoalAnalysisIterationId = null;
      });
    }
    if (this.approvePlanBtn) {
      this.approvePlanBtn.addEventListener('click', () => this.approvePlan());
    }
    if (this.rejectPlanBtn) {
      this.rejectPlanBtn.addEventListener('click', () => this.rejectPlan());
    }
    if (this.stabilizationRefreshBtn) {
      this.stabilizationRefreshBtn.addEventListener('click', () => this.loadStabilizationKpis());
    }
    if (this.stabilizationFamilyFilter) {
      this.stabilizationFamilyFilter.addEventListener('change', () => this.loadStabilizationKpis());
    }
    if (this.stabilizationHistoryLimit) {
      this.stabilizationHistoryLimit.addEventListener('change', () => this.loadStabilizationKpis());
    }
  }

  /**
   * Toggles the task planner panel visibility.
   */
  togglePanel() {
    this.panelVisible = !this.panelVisible;
    if (this.panel) {
      this.panel.style.display = this.panelVisible ? 'block' : 'none';
    }
    if (this.toggleBtn) {
      this.toggleBtn.classList.toggle('active', this.panelVisible);
    }
  }

  /**
   * Toggles the panel content (collapse/expand).
   */
  toggleContent() {
    if (!this.content || !this.togglePlannerBtn) return;
    const isHidden = this.content.style.display === 'none';
    this.content.style.display = isHidden ? 'flex' : 'none';
    const icon = this.togglePlannerBtn.querySelector('i');
    if (icon) {
      icon.className = isHidden ? 'bi bi-chevron-up' : 'bi bi-chevron-down';
    }
  }

  /**
   * Loads task plans from the API.
   */
  async loadPlans() {
    try {
      const allResponse = await fetch('/api/account-tasks');
      if (!allResponse.ok) {
        throw new Error(`HTTP ${allResponse.status}`);
      }
      this.allPlans = await allResponse.json();
      this.populateAccountOptions();

      const query = new URLSearchParams();
      if (this.selectedAccountName) {
        query.set('accountName', this.selectedAccountName);
      }
      const plansUrl = query.toString() ? `/api/account-tasks?${query.toString()}` : '/api/account-tasks';
      const response = await fetch(plansUrl);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.plans = await response.json();
      this.renderPlansList();

      const selectedStillVisible = this.selectedPlanId &&
        this.plans.some(plan => plan.planId === this.selectedPlanId);

      if (!selectedStillVisible) {
        this.selectedPlanId = this.plans.length ? this.plans[0].planId : null;
      }

      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      } else if (this.planDetailSection) {
        this.planDetailSection.style.display = 'none';
      }
    } catch (error) {
      console.error('Failed to load plans:', error);
      if (this.plansList) {
        this.plansList.innerHTML =
          '<div class="text-muted small">Failed to load plans. Check that the service is running.</div>';
      }
    }
  }

  populateAccountOptions() {
    if (!this.accountSelect) return;
    const previous = this.selectedAccountName || this.accountSelect.value || '';
    const names = Array.from(new Set(
      (this.allPlans || [])
        .map(plan => (plan?.accountName || '').toString().trim())
        .filter(Boolean)
    )).sort((a, b) => a.localeCompare(b));
    const hasUnassigned = (this.allPlans || []).some(plan => {
      const account = (plan?.accountName || '').toString().trim();
      return account.length === 0;
    });

    this.accountSelect.innerHTML = '<option value="">All accounts</option>';
    if (hasUnassigned) {
      const unknown = document.createElement('option');
      unknown.value = AccountTaskPlanner.UNASSIGNED_FILTER;
      unknown.textContent = 'Unknown/Unassigned';
      this.accountSelect.appendChild(unknown);
    }
    names.forEach(name => {
      const option = document.createElement('option');
      option.value = name;
      option.textContent = name;
      this.accountSelect.appendChild(option);
    });

    const validValues = new Set(['', AccountTaskPlanner.UNASSIGNED_FILTER, ...names]);
    if (previous && validValues.has(previous)) {
      this.accountSelect.value = previous;
      this.selectedAccountName = previous;
    } else {
      this.accountSelect.value = '';
      this.selectedAccountName = '';
      localStorage.removeItem('taskPlanner.selectedAccount');
    }
  }

  /**
   * Loads available recording sessions for the source dropdown.
   */
  async loadSourceSessions() {
    if (!this.sourceSessionSelect) return;

    try {
      const response = await fetch('/api/recordings/sessions');
      if (!response.ok) return;

      const sessions = await response.json();
      this.sourceSessionSelect.innerHTML = '<option value="">Current session</option>';

      sessions.forEach(session => {
        const option = document.createElement('option');
        option.value = session.recordingId || session.id || '';
        let label = session.displayName || session.scriptName || session.recordingId || session.id || 'Unknown';
        if (session.replayName && session.startTime) {
          try {
            const d = new Date(session.startTime);
            const timeStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            label = `${label} \u00B7 ${timeStr}`;
          } catch (_) {
            // ignore
          }
        }
        option.textContent = label;
        this.sourceSessionSelect.appendChild(option);
      });
    } catch (error) {
      console.debug('Could not load source sessions:', error);
    }
  }

  /**
   * Loads script/task catalog from Micro6 executor proxy endpoint.
   */
  async loadScriptCatalog() {
    try {
      const response = await fetch('/api/account-tasks/stabilization/catalog');
      if (!response.ok) return;
      const payload = await response.json();
      this.scriptCatalog = Array.isArray(payload.tasks) ? payload.tasks : [];
    } catch (error) {
      console.debug('Could not load script catalog:', error);
      this.scriptCatalog = [];
    }
  }

  async loadBuilderGoalRuns(options = {}) {
    if (!this.builderGoalRunsList) return;
    const throwOnError = Boolean(options.throwOnError);

    try {
      const response = await fetch('/api/debug/builder-goals/runs?limit=20');
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.builderGoalRuns = await response.json();
      this.renderBuilderGoalRuns();
    } catch (error) {
      console.error('Failed to load builder goal runs:', error);
      this.builderGoalRunsList.innerHTML =
        '<div class="text-muted small">Failed to load builder goal runs.</div>';
      if (throwOnError) {
        throw error;
      }
    }
  }

  async loadBuilderGoalClients() {
    if (!this.builderGoalClientSelect || this.builderGoalBundleMismatch) return;

    try {
      const response = await fetch('/api/debug/builder-goals/clients');
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.builderGoalClients = await response.json();
      this.renderBuilderGoalClients();
    } catch (error) {
      console.error('Failed to load builder goal clients:', error);
      this.builderGoalClients = [];
      this.renderBuilderGoalClients(error.message);
    }
  }

  renderBuilderGoalClients(loadError = null) {
    if (!this.builderGoalClientSelect || this.builderGoalBundleMismatch) return;

    const clients = Array.isArray(this.builderGoalClients) ? this.builderGoalClients : [];
    const previousSelection = this.selectedBuilderGoalClientId;
    const previousStillVisible = clients.some(client => client.clientId === previousSelection);
    const runnableClients = clients.filter(client => Boolean(client.runnable));

    if (previousStillVisible) {
      this.selectedBuilderGoalClientId = previousSelection;
    } else if (runnableClients.length === 1) {
      this.selectedBuilderGoalClientId = runnableClients[0].clientId || '';
    } else {
      this.selectedBuilderGoalClientId = '';
    }

    if (this.selectedBuilderGoalClientId) {
      localStorage.setItem('taskPlanner.selectedBuilderGoalClient', this.selectedBuilderGoalClientId);
    } else {
      localStorage.removeItem('taskPlanner.selectedBuilderGoalClient');
    }

    if (loadError) {
      this.builderGoalClientSelect.innerHTML = '<option value="">Failed to load online clients</option>';
      this.builderGoalClientSelect.disabled = true;
      this.renderBuilderGoalClientStatus(`Failed to load online clients: ${loadError}`, 'danger');
      this.updateBuilderGoalRunButtonState();
      return;
    }

    if (clients.length === 0) {
      this.builderGoalClientSelect.innerHTML = '<option value="">No online Micro6 clients</option>';
      this.builderGoalClientSelect.disabled = true;
      this.renderBuilderGoalClientStatus('No online Micro6 clients are currently available.', 'warning');
      this.updateBuilderGoalRunButtonState();
      return;
    }

    this.builderGoalClientSelect.disabled = false;
    const options = ['<option value="">Select an online Micro6 client</option>'];
    clients.forEach(client => {
      const label = this.formatBuilderGoalClientLabel(client);
      options.push(
        `<option value="${this.escapeAttr(client.clientId || '')}">${this.escapeHtml(label)}</option>`
      );
    });
    this.builderGoalClientSelect.innerHTML = options.join('');
    this.builderGoalClientSelect.value = this.selectedBuilderGoalClientId || '';

    this.renderBuilderGoalClientStatus();
    this.updateBuilderGoalRunButtonState();
  }

  formatBuilderGoalClientLabel(client) {
    const account = client.accountName || 'Unknown account';
    const status = client.status || 'UNKNOWN';
    const location = client.advertisedBaseUrl || client.executorBindAddr || client.clientId || 'unknown target';
    return `${account} • ${status} • ${location}`;
  }

  getSelectedBuilderGoalClient() {
    const selectedId = this.builderGoalClientSelect ? this.builderGoalClientSelect.value : this.selectedBuilderGoalClientId;
    return (this.builderGoalClients || []).find(client => client.clientId === selectedId) || null;
  }

  renderBuilderGoalClientStatus(message = null, tone = null) {
    if (!this.builderGoalClientStatus) return;
    if (this.builderGoalBundleMismatch) {
      this.builderGoalClientStatus.innerHTML =
        '<span class="text-danger">Debugger assets are out of date. Hard refresh this page to enable Builder Goal Runner.</span>';
      return;
    }
    if (message) {
      this.builderGoalClientStatus.innerHTML = `<span class="text-${tone || 'muted'}">${this.escapeHtml(message)}</span>`;
      return;
    }

    const selectedClient = this.getSelectedBuilderGoalClient();
    if (!selectedClient) {
      const runnableCount = (this.builderGoalClients || []).filter(client => Boolean(client.runnable)).length;
      if (runnableCount === 1) {
        this.builderGoalClientStatus.innerHTML =
          '<span class="text-success">One runnable client is online and has been preselected.</span>';
      } else if (runnableCount > 1) {
        this.builderGoalClientStatus.innerHTML =
          '<span class="text-muted">Select which online Micro6 client should receive this task.</span>';
      } else {
        this.builderGoalClientStatus.innerHTML =
          '<span class="text-warning">No runnable Micro6 clients are currently online.</span>';
      }
      return;
    }

    if (!selectedClient.runnable) {
      this.builderGoalClientStatus.innerHTML =
        '<span class="text-warning">The selected client is stale or unreachable and cannot receive tasks.</span>';
      return;
    }

    const currentTask = selectedClient.currentTask
      ? ` Current task: ${this.escapeHtml(selectedClient.currentTask)}.`
      : '';
    this.builderGoalClientStatus.innerHTML =
      `<span class="text-success">Publish target: ${this.escapeHtml(this.formatBuilderGoalClientLabel(selectedClient))}.${currentTask}</span>`;
  }

  updateBuilderGoalRunButtonState() {
    if (!this.runBuilderGoalBtn) return;
    if (this.builderGoalBundleMismatch) {
      this.runBuilderGoalBtn.disabled = true;
      return;
    }
    const importedRunnable = Boolean(this.importedBuilderGoal?.result?.runnableNow);
    const selectedClient = this.getSelectedBuilderGoalClient();
    this.runBuilderGoalBtn.disabled = !(importedRunnable && selectedClient && selectedClient.runnable);
  }

  renderBuilderGoalRuns() {
    if (!this.builderGoalRunsList) return;
    if (!Array.isArray(this.builderGoalRuns) || this.builderGoalRuns.length === 0) {
      this.builderGoalRunsList.innerHTML =
        '<div class="text-muted small fst-italic">No builder goal runs yet.</div>';
      return;
    }

    this.builderGoalRunsList.innerHTML = this.builderGoalRuns.map(run => {
      const status = (run.status || 'QUEUED').toLowerCase();
      const canAnalyze = Boolean(run.canAnalyze);
      const canFix = Boolean(run.canFixAndRelaunch);
      const actionStepLine = this.renderBuilderGoalActionStep(run);
      const blockedReasonLine = this.renderBuilderGoalBlockedReason(run);
      const analysisUsageLine = this.renderBuilderGoalAnalysisUsage(run);
      const failureLine = run.failureReason
        ? `<div class="plan-card-intent text-danger">${this.escapeHtml(run.failureReason)}</div>`
        : '';
      const diagnosisLine = run.diagnosisSummary
        ? `<div class="plan-card-intent">${this.escapeHtml(run.diagnosisSummary)}</div>`
        : '';
      const revisionLine = run.micro6RevisionId
        ? `<div class="small text-muted mt-1"><i class="bi bi-git me-1"></i>${this.escapeHtml(run.micro6RevisionId)}</div>`
        : '';
      const targetParts = [run.targetAccountName, run.targetBaseUrl].filter(Boolean);
      const targetLine = targetParts.length
        ? `<div class="small text-muted mt-1"><i class="bi bi-broadcast-pin me-1"></i>${this.escapeHtml(targetParts.join(' • '))}</div>`
        : '';
      const publishMeta = [];
      if (run.publishEntryId) {
        publishMeta.push(`entry ${run.publishEntryId}`);
      }
      if (run.publishedAt) {
        publishMeta.push(`published ${this.formatTimeAgo(run.publishedAt)}`);
      }
      const publishLine = publishMeta.length
        ? `<div class="small text-muted mt-1"><i class="bi bi-list-task me-1"></i>${this.escapeHtml(publishMeta.join(' • '))}</div>`
        : '';
      const progressText = this.describeBuilderGoalRunProgress(run);

      return `
        <div class="plan-card">
          <div class="plan-card-header">
            <span class="plan-card-title">${this.escapeHtml(run.displayName || run.taskKey || 'Builder Goal')}</span>
            <span class="plan-card-status ${status}">${this.escapeHtml(run.status || 'QUEUED')}</span>
          </div>
          <div class="small text-muted">${this.escapeHtml(run.taskKey || '')}</div>
          ${targetLine}
          ${publishLine}
          ${failureLine}
          ${diagnosisLine}
          ${revisionLine}
          ${analysisUsageLine}
          <div class="plan-card-meta">
            <span>${this.escapeHtml(progressText)}</span>
            <span>${run.createdAt ? this.escapeHtml(this.formatTimeAgo(run.createdAt)) : ''}</span>
          </div>
          ${actionStepLine}
          <div class="d-flex gap-2 mt-2">
            <button class="btn btn-sm btn-outline-primary flex-fill" data-builder-run-action="analyze" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canAnalyze ? '' : 'disabled'}>
              <i class="bi bi-lightbulb me-1"></i>Analyze Failure
            </button>
            <button class="btn btn-sm btn-outline-success flex-fill" data-builder-run-action="fix" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canFix ? '' : 'disabled'}>
              <i class="bi bi-arrow-repeat me-1"></i>Fix + Relaunch
            </button>
          </div>
          ${blockedReasonLine}
        </div>
      `;
    }).join('');
  }

  renderBuilderGoalActionStep(run) {
    const status = (run.status || '').toUpperCase();
    if (status !== 'FAILED') {
      return '';
    }
    if (!run.analysisAvailable) {
      return '<div class="small text-muted mt-2">1. Analyze failure</div>';
    }
    if (run.patchDraftAvailable) {
      return '<div class="small text-muted mt-2">2. Fix and relaunch</div>';
    }
    return '<div class="small text-muted mt-2">2. Fix and relaunch is unavailable until analysis produces a patch draft</div>';
  }

  renderBuilderGoalBlockedReason(run) {
    const reasons = [];
    if (!run.canAnalyze && run.analyzeBlockedReason) {
      reasons.push(run.analyzeBlockedReason);
    }
    if (!run.canFixAndRelaunch && run.fixBlockedReason && !reasons.includes(run.fixBlockedReason)) {
      reasons.push(run.fixBlockedReason);
    }
    if (reasons.length === 0) {
      return '';
    }
    return `<div class="small text-muted mt-2">${this.escapeHtml(reasons.join(' • '))}</div>`;
  }

  renderBuilderGoalAnalysisUsage(run) {
    const parts = [];
    if (run.analysisCustomized) {
      parts.push('Analysis used custom operator prompt');
    }
    if (Number.isFinite(run.analysisSelectedEvidenceCount) && Number.isFinite(run.analysisTotalEvidenceCount)) {
      parts.push(`Selected evidence: ${run.analysisSelectedEvidenceCount}/${run.analysisTotalEvidenceCount}`);
    }
    if (parts.length === 0) {
      return '';
    }
    return `<div class="small text-muted mt-1"><i class="bi bi-pencil-square me-1"></i>${this.escapeHtml(parts.join(' • '))}</div>`;
  }

  async importBuilderGoal() {
    if (this.builderGoalBundleMismatch) {
      this.renderBuilderGoalClientStatus(
        'Hard refresh this page to load the latest Builder Goal Runner before importing a task.',
        'danger'
      );
      return;
    }
    const raw = this.builderGoalPayloadInput ? this.builderGoalPayloadInput.value.trim() : '';
    if (!raw) {
      alert('Paste a builder payload first.');
      return;
    }

    let payload;
    try {
      payload = JSON.parse(raw);
    } catch (error) {
      alert('Invalid JSON: ' + error.message);
      return;
    }

    if (this.importBuilderGoalBtn) {
      this.importBuilderGoalBtn.disabled = true;
    }

    try {
      const response = await fetch('/api/debug/builder-goals/import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }

      this.importedBuilderGoal = { payload, result };
      this.renderBuilderGoalImportResult(result);
      this.updateBuilderGoalRunButtonState();
    } catch (error) {
      console.error('Failed to import builder payload:', error);
      this.importedBuilderGoal = null;
      this.renderBuilderGoalImportResult({ error: error.message });
      this.updateBuilderGoalRunButtonState();
    } finally {
      if (this.importBuilderGoalBtn) {
        this.importBuilderGoalBtn.disabled = false;
      }
    }
  }

  renderBuilderGoalImportResult(result) {
    if (!this.builderGoalImportResult) return;
    if (this.builderGoalBundleMismatch) {
      this.builderGoalImportResult.innerHTML =
        '<span class="text-danger">Builder Goal Runner is out of date. Hard refresh /debugger and try again.</span>';
      return;
    }
    if (!result) {
      this.builderGoalImportResult.textContent =
        'Import a builder payload to validate it against the Micro6 catalog.';
      return;
    }

    if (result.error) {
      this.builderGoalImportResult.innerHTML =
        `<span class="text-danger">${this.escapeHtml(result.error)}</span>`;
      return;
    }

    const disabledReason = result.disabledReason
      ? `, disabled: ${this.escapeHtml(result.disabledReason)}`
      : '';
    this.builderGoalImportResult.innerHTML = `
      <div><strong>${this.escapeHtml(result.displayName || result.title || result.taskKey || 'Builder Goal')}</strong></div>
      <div class="text-muted">
        task_key=${this.escapeHtml(result.taskKey || '')},
        matched ${this.escapeHtml(String(result.matchedSettingsCount ?? 0))},
        ignored ${this.escapeHtml(String(result.ignoredSettingsCount ?? 0))},
        runnable=${this.escapeHtml(String(Boolean(result.runnableNow)))}${disabledReason}
      </div>
    `;
  }

  async runBuilderGoal() {
    if (this.builderGoalBundleMismatch) {
      this.renderBuilderGoalClientStatus(
        'Hard refresh this page to load the latest Builder Goal Runner before running a task.',
        'danger'
      );
      return;
    }
    if (!this.importedBuilderGoal || !this.importedBuilderGoal.payload) {
      alert('Import a builder payload first.');
      return;
    }
    const selectedClient = this.getSelectedBuilderGoalClient();
    if (!selectedClient || !selectedClient.runnable) {
      this.renderBuilderGoalClientStatus('Select a runnable online Micro6 client before running the task.', 'warning');
      this.updateBuilderGoalRunButtonState();
      return;
    }

    if (this.runBuilderGoalBtn) {
      this.runBuilderGoalBtn.disabled = true;
      this.runBuilderGoalBtn.innerHTML = '<i class="bi bi-hourglass me-1"></i>Publishing...';
    }
    this.renderBuilderGoalClientStatus(`Publishing task to ${this.formatBuilderGoalClientLabel(selectedClient)}...`, 'info');
    console.info('[AccountTaskPlanner] Publish to Selected Client clicked', {
      taskKey: this.importedBuilderGoal?.result?.taskKey || null,
      targetClientId: selectedClient.clientId || null
    });
    const controller = new AbortController();
    const timeoutHandle = setTimeout(
      () => controller.abort(),
      AccountTaskPlanner.BUILDER_GOAL_PUBLISH_TIMEOUT_MS
    );
    let publishSucceeded = false;
    let publishWarning = null;

    try {
      const response = await fetch('/api/debug/builder-goals/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          builderPayload: this.importedBuilderGoal.payload,
          targetClientId: selectedClient.clientId
        })
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }
      if (result.published === false) {
        await this.loadBuilderGoalRuns();
        throw new Error(result.publishError || 'Publish failed');
      }
      publishSucceeded = true;
      this.renderBuilderGoalClientStatus(
        `Published to ${this.formatBuilderGoalClientLabel(selectedClient)} queue.`,
        'success'
      );
    } catch (error) {
      console.error('Failed to publish builder goal:', error);
      const timedOut = error && error.name === 'AbortError';
      const message = timedOut
        ? 'Publish timed out before Micro6 acknowledged the task.'
        : error.message;
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus(`Failed to publish task: ${message}`, 'danger');
      alert('Failed to publish task: ' + message);
    } finally {
      clearTimeout(timeoutHandle);
      if (publishSucceeded) {
        try {
          await this.loadBuilderGoalRuns({ throwOnError: true });
        } catch (error) {
          console.warn('Published successfully, but failed to refresh builder goal runs:', error);
          publishWarning = error?.message || 'Failed to refresh builder goal runs.';
        }
        if (publishWarning) {
          this.renderBuilderGoalClientStatus(
            `Published to ${this.formatBuilderGoalClientLabel(selectedClient)} queue, but failed to refresh the run list: ${publishWarning}`,
            'warning'
          );
        }
      }
      if (this.runBuilderGoalBtn) {
        this.runBuilderGoalBtn.innerHTML = '<i class="bi bi-upload me-1"></i>Publish to Selected Client';
      }
      this.updateBuilderGoalRunButtonState();
    }
  }

  describeBuilderGoalRunProgress(run) {
    const status = (run.status || '').toUpperCase();
    if (status === 'PUBLISHED') {
      return 'Published to Micro6 queue';
    }
    if (status === 'RUNNING') {
      return 'Running from Micro6 queue';
    }
    if (status === 'COMPLETED') {
      return 'Published run completed';
    }
    if (status === 'FAILED') {
      return 'Published run failed';
    }
    if (status === 'PUBLISH_FAILED') {
      return 'Publish failed';
    }
    return 'Waiting for Micro6 status';
  }

  async openBuilderGoalAnalysisEditor(iterationId) {
    if (!iterationId) {
      return;
    }
    this.builderGoalAnalysisIterationId = iterationId;
    this.builderGoalAnalysisDraft = null;
    this.renderBuilderGoalAnalysisStatus('Loading analysis draft...', 'info');
    this.setBuilderGoalAnalysisSubmitState(true, 'Loading...');
    if (this.builderGoalAnalysisEvidenceMeta) {
      this.builderGoalAnalysisEvidenceMeta.textContent = '';
    }
    if (this.builderGoalAnalysisEvidenceGroups) {
      this.builderGoalAnalysisEvidenceGroups.innerHTML =
        '<div class="text-muted small fst-italic">Loading evidence...</div>';
    }
    if (this.builderGoalAnalysisPrompt) {
      this.builderGoalAnalysisPrompt.value = '';
    }
    if (this.builderGoalAnalysisContext) {
      this.builderGoalAnalysisContext.value = '';
    }

    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/analysis-draft`, {
        method: 'POST'
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }
      this.builderGoalAnalysisDraft = result;
      if (this.builderGoalAnalysisPrompt) {
        this.builderGoalAnalysisPrompt.value = result.prompt || '';
      }
      if (this.builderGoalAnalysisContext) {
        this.builderGoalAnalysisContext.value = result.context || '';
      }
      this.renderBuilderGoalAnalysisEvidence(result);
      if (result.canAnalyze === false && result.analysisBlockedReason) {
        this.renderBuilderGoalAnalysisStatus(result.analysisBlockedReason, 'warning');
      } else {
        this.renderBuilderGoalAnalysisStatus('Edit the prompt/context, choose evidence, then run analysis.', 'muted');
      }
      this.setBuilderGoalAnalysisSubmitState(Boolean(result.canAnalyze) === false, 'Run Analysis');
      this.builderGoalAnalysisModal?.show();
    } catch (error) {
      console.error('Failed to analyze builder goal run:', error);
      this.renderBuilderGoalAnalysisStatus(`Failed to load analysis draft: ${error.message}`, 'danger');
      this.setBuilderGoalAnalysisSubmitState(true, 'Run Analysis');
      alert('Failed to load analysis draft: ' + error.message);
    }
  }

  renderBuilderGoalAnalysisEvidence(draft) {
    const groups = Array.isArray(draft?.evidenceGroups) ? draft.evidenceGroups : [];
    const selectedCount = Number(draft?.selectedEvidenceCount ?? 0);
    const totalCount = Number(draft?.totalEvidenceCount ?? 0);

    if (this.builderGoalAnalysisEvidenceMeta) {
      this.builderGoalAnalysisEvidenceMeta.textContent =
        `${selectedCount} of ${totalCount} evidence items selected`;
    }

    if (!this.builderGoalAnalysisEvidenceGroups) {
      return;
    }
    if (groups.length === 0) {
      this.builderGoalAnalysisEvidenceGroups.innerHTML =
        '<div class="text-muted small fst-italic">No evidence was collected for this failed run.</div>';
      return;
    }

    this.builderGoalAnalysisEvidenceGroups.innerHTML = groups.map(group => {
      const items = Array.isArray(group.items) ? group.items : [];
      const itemsHtml = items.length
        ? items.map(item => `
            <label class="form-check border rounded p-2 mb-2">
              <input class="form-check-input builder-goal-analysis-evidence-checkbox" type="checkbox"
                value="${this.escapeAttr(item.id || '')}" ${item.selected ? 'checked' : ''}>
              <span class="form-check-label">
                <div class="fw-semibold">${this.escapeHtml(item.title || '(untitled evidence)')}</div>
                <div class="small text-muted">${this.escapeHtml(item.subtitle || '')}</div>
                ${item.detail ? `<div class="small mt-1 font-monospace">${this.escapeHtml(item.detail)}</div>` : ''}
              </span>
            </label>
          `).join('')
        : '<div class="text-muted small fst-italic">No evidence in this group.</div>';

      return `
        <div class="border rounded p-3">
          <div class="d-flex justify-content-between align-items-center mb-2">
            <div class="fw-semibold">${this.escapeHtml(group.label || group.key || 'Evidence')}</div>
            <div class="small text-muted">${items.length} item(s)</div>
          </div>
          ${itemsHtml}
        </div>
      `;
    }).join('');
    this.updateBuilderGoalAnalysisEvidenceMeta();
  }

  resetBuilderGoalAnalysisPrompt() {
    if (!this.builderGoalAnalysisDraft) {
      return;
    }
    if (this.builderGoalAnalysisPrompt) {
      this.builderGoalAnalysisPrompt.value = this.builderGoalAnalysisDraft.prompt || '';
    }
    if (this.builderGoalAnalysisContext) {
      this.builderGoalAnalysisContext.value = this.builderGoalAnalysisDraft.context || '';
    }
  }

  setAllBuilderGoalEvidenceSelections(selected) {
    if (!this.builderGoalAnalysisEvidenceGroups) {
      return;
    }
    this.builderGoalAnalysisEvidenceGroups
      .querySelectorAll('.builder-goal-analysis-evidence-checkbox')
      .forEach(checkbox => {
        checkbox.checked = Boolean(selected);
      });
    this.updateBuilderGoalAnalysisEvidenceMeta();
  }

  updateBuilderGoalAnalysisEvidenceMeta() {
    if (!this.builderGoalAnalysisEvidenceGroups || !this.builderGoalAnalysisEvidenceMeta) {
      return;
    }
    const total = this.builderGoalAnalysisEvidenceGroups
      .querySelectorAll('.builder-goal-analysis-evidence-checkbox').length;
    const selected = this.builderGoalAnalysisEvidenceGroups
      .querySelectorAll('.builder-goal-analysis-evidence-checkbox:checked').length;
    this.builderGoalAnalysisEvidenceMeta.textContent = `${selected} of ${total} evidence items selected`;
  }

  renderBuilderGoalAnalysisStatus(message, tone = 'muted') {
    if (!this.builderGoalAnalysisStatus) {
      return;
    }
    this.builderGoalAnalysisStatus.innerHTML = `<span class="text-${tone}">${this.escapeHtml(message || '')}</span>`;
  }

  setBuilderGoalAnalysisSubmitState(disabled, label = 'Run Analysis') {
    if (!this.submitBuilderGoalAnalysisBtn) {
      return;
    }
    this.submitBuilderGoalAnalysisBtn.disabled = Boolean(disabled);
    this.submitBuilderGoalAnalysisBtn.innerHTML = `<i class="bi bi-lightbulb-fill me-1"></i>${this.escapeHtml(label)}`;
  }

  collectSelectedBuilderGoalEvidenceIds() {
    if (!this.builderGoalAnalysisEvidenceGroups) {
      return [];
    }
    return Array.from(
      this.builderGoalAnalysisEvidenceGroups.querySelectorAll('.builder-goal-analysis-evidence-checkbox:checked')
    ).map(checkbox => checkbox.value).filter(Boolean);
  }

  async submitBuilderGoalAnalysis() {
    if (!this.builderGoalAnalysisIterationId) {
      return;
    }

    const selectedEvidenceIds = this.collectSelectedBuilderGoalEvidenceIds();
    if (selectedEvidenceIds.length === 0) {
      this.renderBuilderGoalAnalysisStatus('Select at least one evidence item before running analysis.', 'warning');
      return;
    }

    this.setBuilderGoalAnalysisSubmitState(true, 'Analyzing...');
    this.renderBuilderGoalAnalysisStatus('Running analysis with your edited prompt and selected evidence...', 'info');

    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(this.builderGoalAnalysisIterationId)}/analyze`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: this.builderGoalAnalysisPrompt ? this.builderGoalAnalysisPrompt.value : '',
          context: this.builderGoalAnalysisContext ? this.builderGoalAnalysisContext.value : '',
          selectedEvidenceIds
        })
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }

      await this.loadBuilderGoalRuns();
      this.builderGoalAnalysisModal?.hide();
      if (result.patchId) {
        this.renderBuilderGoalClientStatus('Analysis completed and produced a patch draft. Fix + Relaunch is now available.', 'success');
      } else {
        this.renderBuilderGoalClientStatus('Analysis completed, but it did not produce a patch draft.', 'warning');
      }
    } catch (error) {
      console.error('Failed to analyze builder goal run:', error);
      this.renderBuilderGoalAnalysisStatus(`Failed to analyze builder goal run: ${error.message}`, 'danger');
      alert('Failed to analyze builder goal run: ' + error.message);
    } finally {
      this.setBuilderGoalAnalysisSubmitState(false, 'Run Analysis');
    }
  }

  async fixAndRelaunchBuilderGoalRun(iterationId) {
    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/fix-and-relaunch`, {
        method: 'POST'
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus('Patch applied and task republished to the selected Micro6 client.', 'success');
    } catch (error) {
      console.error('Failed to fix and relaunch builder goal run:', error);
      alert('Failed to fix and relaunch builder goal run: ' + error.message);
    }
  }

  async loadStabilizationKpis() {
    try {
      const query = new URLSearchParams();
      const family = this.stabilizationFamilyFilter?.value || '';
      const historyLimit = this.stabilizationHistoryLimit?.value || '10';
      if (family) {
        query.set('family', family);
      }
      query.set('historyLimit', historyLimit);
      const response = await fetch(`/api/account-tasks/stabilization/kpi?${query.toString()}`);
      if (!response.ok) return;
      const payload = await response.json();
      this.renderStabilizationKpis(payload);
    } catch (error) {
      console.debug('Could not load stabilization KPI data:', error);
    }
  }

  renderStabilizationKpis(payload) {
    if (!payload) return;

    const regressions = Array.isArray(payload.regressions) ? payload.regressions : [];
    const summary = [
      { label: '24h runs', value: payload.window_24h_count ?? 0 },
      { label: '7d runs', value: payload.window_7d_count ?? 0 },
      { label: 'Open triage', value: payload.open_triage_count ?? 0 },
      { label: 'Regressions', value: regressions.length }
    ];

    if (this.stabilizationSummary) {
      this.stabilizationSummary.innerHTML = summary.map(item => `
        <div class="stabilization-stat">
          <div class="small text-muted">${this.escapeHtml(item.label)}</div>
          <div class="fw-bold fs-6">${this.escapeHtml(String(item.value))}</div>
        </div>
      `).join('');
    }

    if (this.stabilizationScripts) {
      const scripts = Array.isArray(payload.scripts) ? payload.scripts : [];
      this.stabilizationScripts.innerHTML = scripts.length ? scripts.map(script => `
        <tr>
          <td>${this.escapeHtml(script.script_key || '')}</td>
          <td>${this.escapeHtml(script.family || '')}</td>
          <td>${this.escapeHtml(String(script.window_24h_count ?? 0))}</td>
          <td>${this.escapeHtml((Number(script.pass_rate_24h ?? 0) * 100).toFixed(0))}%</td>
          <td>${script.regression ? '<span class="badge bg-danger">Yes</span>' : '<span class="badge bg-success">No</span>'}</td>
          <td>${this.escapeHtml(script.top_failure_signature || '')}</td>
        </tr>
      `).join('') : '<tr><td colspan="6" class="text-muted small">No KPI data yet.</td></tr>';
    }

    if (this.stabilizationHistory) {
      const history = Array.isArray(payload.history) ? payload.history : [];
      this.stabilizationHistory.innerHTML = history.length ? history.map(row => `
        <tr>
          <td>${this.escapeHtml(row.reportId || '')}</td>
          <td>${this.escapeHtml(row.scriptKey || '')}</td>
          <td>${this.escapeHtml(row.family || '')}</td>
          <td>${this.escapeHtml(String(row.passRate24h ?? 0))}</td>
          <td>${this.escapeHtml(String(row.timeoutRate24h ?? 0))}</td>
          <td>${row.regression ? 'Yes' : 'No'}</td>
        </tr>
      `).join('') : '<tr><td colspan="6" class="text-muted small">No history yet.</td></tr>';
    }

    if (this.stabilizationRegressions) {
      this.stabilizationRegressions.innerHTML = regressions.length ? regressions.map(row => `
        <li class="list-group-item d-flex justify-content-between align-items-center">
          <span>${this.escapeHtml(row.script_key || '')} <span class="text-muted">(${this.escapeHtml(row.family || '')})</span></span>
          <span class="badge bg-danger">${this.escapeHtml(String(row.window_24h_count ?? 0))}</span>
        </li>
      `).join('') : '<li class="list-group-item text-muted small">No regressions detected.</li>';
    }
  }

  getCatalogTaskByKey(taskKey) {
    if (!taskKey) return null;
    return this.scriptCatalog.find(task => task && task.task_key === taskKey) || null;
  }

  normalizeTaskKey(taskKey) {
    return (taskKey || '')
      .toString()
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '');
  }

  findCatalogTaskByKeyFlexible(taskKey) {
    if (!taskKey) return null;
    const direct = this.getCatalogTaskByKey(taskKey);
    if (direct) return direct;

    const normalizedTarget = this.normalizeTaskKey(taskKey);
    if (!normalizedTarget) return null;
    return this.scriptCatalog.find(task => {
      if (!task?.task_key) return false;
      return this.normalizeTaskKey(task.task_key) === normalizedTarget;
    }) || null;
  }

  buildCatalogStateForItem(item, index) {
    const itemId = item?.itemId || String(index);
    const selectorId = `catalogSelect_${itemId}`;
    const searchId = `catalogSearch_${itemId}`;
    const settingsId = `catalogSettings_${itemId}`;
    const settingPrefix = `setting_${itemId}_`;
    const state = this.itemSelectionState.get(itemId) || {};
    const selectedTaskKey = state.selectedTaskKey || item?.scriptName || '';
    return { itemId, selectorId, searchId, settingsId, settingPrefix, selectedTaskKey };
  }

  /**
   * Renders the plans list.
   */
  renderPlansList() {
    if (!this.plansList) return;

    if (!this.plans || this.plans.length === 0) {
      this.plansList.innerHTML =
        '<div class="text-muted small fst-italic">No task plans yet. Generate one above.</div>';
      if (this.planCountBadge) {
        this.planCountBadge.textContent = '0 plans';
      }
      return;
    }

    if (this.planCountBadge) {
      this.planCountBadge.textContent = `${this.plans.length} plan${this.plans.length !== 1 ? 's' : ''}`;
    }

    this.plansList.innerHTML = this.plans.map(plan => {
      const status = (plan.status || 'generated').toLowerCase();
      const isActive = plan.planId === this.selectedPlanId;
      const itemsCount = plan.itemCount || 0;
      const createdAt = plan.createdAt ? this.formatTimeAgo(plan.createdAt) : '';

      return `
        <div class="plan-card${isActive ? ' active' : ''}" data-plan-id="${this.escapeAttr(plan.planId)}">
          <div class="plan-card-header">
            <span class="plan-card-title">${this.escapeHtml(plan.intent || 'Untitled Plan')}</span>
            <span class="plan-card-status ${status}">${this.escapeHtml(plan.status || 'GENERATED')}</span>
          </div>
          ${plan.intent ? `<div class="plan-card-intent">${this.escapeHtml(plan.intent)}</div>` : ''}
          <div class="plan-card-meta">
            <span>${itemsCount} item${itemsCount !== 1 ? 's' : ''}</span>
            ${createdAt ? `<span>${createdAt}</span>` : ''}
          </div>
        </div>
      `;
    }).join('');

    this.plansList.querySelectorAll('.plan-card').forEach(card => {
      card.addEventListener('click', () => {
        const planId = card.dataset.planId;
        this.selectPlan(planId);
      });
    });
  }

  async selectPlan(planId) {
    this.selectedPlanId = planId;
    this.renderPlansList();
    await this.loadPlanDetail(planId);
  }

  async loadPlanDetail(planId) {
    try {
      const response = await fetch(`/api/account-tasks/${planId}`);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.selectedPlan = await response.json();
      this.renderPlanDetail();
    } catch (error) {
      console.error('Failed to load plan detail:', error);
    }
  }

  renderPlanDetail() {
    if (!this.selectedPlan || !this.planDetailSection) return;

    const plan = this.selectedPlan;
    this.planDetailSection.style.display = 'block';

    if (this.planDetailTitle) {
      this.planDetailTitle.textContent = plan.intent || 'Untitled Plan';
    }
    if (this.planDetailIntent) {
      this.planDetailIntent.textContent = `Plan ${plan.planId}`;
    }
    if (this.planDetailStatus) {
      const status = (plan.status || 'generated').toLowerCase();
      this.planDetailStatus.className = `badge plan-card-status ${status}`;
      this.planDetailStatus.textContent = plan.status || 'GENERATED';
    }

    const canApprove = ['GENERATED', 'UNDER_REVIEW'].includes(plan.status);
    const canReject = ['GENERATED', 'UNDER_REVIEW', 'PARTIALLY_APPROVED'].includes(plan.status);
    if (this.approvePlanBtn) {
      this.approvePlanBtn.style.display = canApprove ? 'inline-block' : 'none';
    }
    if (this.rejectPlanBtn) {
      this.rejectPlanBtn.style.display = canReject ? 'inline-block' : 'none';
    }

    this.renderPlanItems(plan.items || []);
  }

  renderPlanItems(items) {
    if (!this.planItemsList) return;

    if (!items || items.length === 0) {
      this.planItemsList.innerHTML =
        '<div class="text-muted small fst-italic">No items in this plan.</div>';
      return;
    }

    this.planItemsList.innerHTML = items.map((item, index) => {
      const status = (item.status || 'pending').toLowerCase();
      const canAct = ['PENDING', 'APPROVED'].includes(item.status);
      const canExecute = item.status === 'APPROVED' && (item.scriptName || this.scriptCatalog.length > 0);
      const canQuickTest = Boolean(item.scriptName || this.scriptCatalog.length > 0);
      const catalogState = this.buildCatalogStateForItem(item, index);
      const selectedTask = this.getCatalogTaskByKey(catalogState.selectedTaskKey);

      return `
        <div class="plan-item-card" data-item-id="${this.escapeAttr(item.itemId)}">
          <div class="plan-item-header">
            <span class="plan-item-title">
              <span class="text-muted me-1">#${item.sortOrder || index + 1}</span>
              ${this.escapeHtml(item.title || 'Untitled')}
            </span>
            <div class="plan-item-actions">
              ${canAct && item.status === 'PENDING' ? `
                <button class="btn btn-outline-success btn-item-approve" data-item-id="${this.escapeAttr(item.itemId)}" title="Approve">
                  <i class="bi bi-check"></i>
                </button>
                <button class="btn btn-outline-danger btn-item-reject" data-item-id="${this.escapeAttr(item.itemId)}" title="Reject">
                  <i class="bi bi-x"></i>
                </button>
              ` : ''}
              ${canExecute ? `
                <button class="btn btn-outline-primary btn-item-execute" data-item-id="${this.escapeAttr(item.itemId)}" data-script="${this.escapeAttr(item.scriptName || '')}" data-selector-id="${this.escapeAttr(catalogState.selectorId)}" data-settings-id="${this.escapeAttr(catalogState.settingsId)}" title="Execute Patch Loop">
                  <i class="bi bi-play-fill"></i>
                </button>
              ` : ''}
              ${canQuickTest ? `
                <button class="btn btn-outline-info btn-item-quick-test" data-item-id="${this.escapeAttr(item.itemId)}" data-script="${this.escapeAttr(item.scriptName || '')}" data-selector-id="${this.escapeAttr(catalogState.selectorId)}" data-settings-id="${this.escapeAttr(catalogState.settingsId)}" title="Run quick test">
                  <i class="bi bi-lightning-charge"></i>
                </button>
              ` : ''}
              ${item.status === 'COMPLETED' ? '<i class="bi bi-check-circle-fill text-success"></i>' : ''}
              ${item.status === 'FAILED' ? '<i class="bi bi-exclamation-circle-fill text-danger"></i>' : ''}
            </div>
          </div>
          ${item.description ? `<div class="plan-item-desc">${this.escapeHtml(item.description)}</div>` : ''}
          <div class="plan-item-meta">
            <span class="plan-item-badge ${status}">${this.escapeHtml(item.status || 'PENDING')}</span>
            ${item.scriptName ? `<span><i class="bi bi-code-square me-1"></i>${this.escapeHtml(item.scriptName)}</span>` : ''}
            ${item.estimatedMinutes ? `<span><i class="bi bi-clock me-1"></i>~${item.estimatedMinutes}min</span>` : ''}
            ${item.difficulty ? `<span><i class="bi bi-bar-chart me-1"></i>${this.escapeHtml(item.difficulty)}</span>` : ''}
          </div>
          ${this.scriptCatalog.length > 0 ? `
            <div class="mt-2">
              <label class="small text-muted mb-1 d-block">Catalog script/task</label>
              <input
                type="text"
                class="form-control form-control-sm mb-1 task-catalog-search"
                id="${this.escapeAttr(catalogState.searchId)}"
                data-selector-id="${this.escapeAttr(catalogState.selectorId)}"
                placeholder="Search task catalog..."
              >
              <select class="form-select form-select-sm task-catalog-select" id="${this.escapeAttr(catalogState.selectorId)}" data-item-id="${this.escapeAttr(catalogState.itemId)}" data-settings-id="${this.escapeAttr(catalogState.settingsId)}">
                ${this.buildCatalogOptionsHtml(catalogState.selectedTaskKey)}
              </select>
              <div class="mt-2 task-settings-container" id="${this.escapeAttr(catalogState.settingsId)}">
                ${this.renderTaskSettings(selectedTask, catalogState)}
              </div>
            </div>
          ` : ''}
          ${item.citations && item.citations.length > 0 ? this.renderCitations(item.citations) : ''}
        </div>
      `;
    }).join('');

    this.planItemsList.querySelectorAll('.btn-item-approve').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.approveItem(btn.dataset.itemId);
      });
    });

    this.planItemsList.querySelectorAll('.btn-item-reject').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        this.rejectItem(btn.dataset.itemId);
      });
    });

    this.planItemsList.querySelectorAll('.btn-item-execute').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const selectorId = btn.dataset.selectorId;
        const settingsId = btn.dataset.settingsId;
        let selectedScript = btn.dataset.script;
        if ((!selectedScript || selectedScript.trim() === '') && selectorId) {
          const selector = document.getElementById(selectorId);
          if (selector && selector.value) {
            selectedScript = selector.value;
          }
        }
        this.executeItemPatchLoop(btn.dataset.itemId, selectedScript, settingsId);
      });
    });

    this.planItemsList.querySelectorAll('.btn-item-quick-test').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const selectorId = btn.dataset.selectorId;
        const settingsId = btn.dataset.settingsId;
        let selectedScript = btn.dataset.script;
        if ((!selectedScript || selectedScript.trim() === '') && selectorId) {
          const selector = document.getElementById(selectorId);
          if (selector && selector.value) {
            selectedScript = selector.value;
          }
        }
        this.executeItemQuickTest(btn.dataset.itemId, selectedScript, settingsId);
      });
    });

    this.planItemsList.querySelectorAll('.task-catalog-search').forEach(input => {
      input.addEventListener('input', () => {
        const selectorId = input.dataset.selectorId;
        if (!selectorId) return;
        const selector = document.getElementById(selectorId);
        if (!selector) return;
        this.filterCatalogSelector(selector, input.value || '');
      });
    });

    this.planItemsList.querySelectorAll('.task-catalog-select').forEach(selector => {
      selector.addEventListener('change', async () => {
        const itemId = selector.dataset.itemId;
        const settingsId = selector.dataset.settingsId;
        const selectedTask = this.getCatalogTaskByKey(selector.value);
        await this.refreshTaskSettingsUI(itemId, selectedTask, settingsId);
      });
      if (selector.value) {
        const selectedTask = this.getCatalogTaskByKey(selector.value);
        this.refreshTaskSettingsUI(selector.dataset.itemId, selectedTask, selector.dataset.settingsId);
      }
    });
  }

  buildCatalogOptionsHtml(selectedTaskKey) {
    const groups = new Map();
    this.scriptCatalog.forEach(task => {
      const category = (task?.category || 'other').toString();
      if (!groups.has(category)) {
        groups.set(category, []);
      }
      groups.get(category).push(task);
    });

    const orderedCategories = Array.from(groups.keys()).sort((a, b) => a.localeCompare(b));
    return orderedCategories.map(category => {
      const options = groups.get(category)
        .slice()
        .sort((a, b) =>
          (a?.display_name || a?.task_key || '')
            .toString()
            .localeCompare((b?.display_name || b?.task_key || '').toString())
        )
        .map(task => {
          const taskKey = task?.task_key || '';
          const selectedAttr = selectedTaskKey && selectedTaskKey === taskKey ? ' selected' : '';
          return `<option value="${this.escapeAttr(taskKey)}"${selectedAttr}>${this.escapeHtml(this.buildCatalogTaskLabel(task))}</option>`;
        })
        .join('');
      return `<optgroup label="${this.escapeAttr(category)}">${options}</optgroup>`;
    }).join('');
  }

  buildCatalogTaskLabel(task) {
    let label = task?.display_name || task?.task_key || 'unknown';
    if (task?.queue_status) {
      const prefix = task.queue_status === 'RUNNING' ? '[RUNNING' : '[QUEUED';
      const countSuffix = task.queue_count > 1 ? ` x${task.queue_count}` : '';
      label = `${prefix} #${task.queue_rank}]${countSuffix} ${label}`;
    }
    if (task?.task_key) {
      label += ` (${task.task_key})`;
    }
    if (task?.runnable === false && task?.disabled_reason) {
      label += ` [not runnable: ${task.disabled_reason}]`;
    }
    return label;
  }

  renderTaskSettings(task, catalogState) {
    if (!task) {
      return '<div class="small text-muted fst-italic">Select a task to configure settings.</div>';
    }
    const fields = Array.isArray(task?.args_schema?.fields) ? task.args_schema.fields : [];
    if (!fields.length) {
      return '<div class="small text-muted fst-italic">No configurable settings exposed for this task.</div>';
    }
    const rendered = fields.map(field => this.renderTaskSettingField(field, catalogState.settingPrefix)).join('');
    const copyBtnId = `taskConfigCopy_${catalogState.itemId}`;
    const pasteBtnId = `taskConfigPaste_${catalogState.itemId}`;
    const applyBtnId = `taskConfigApply_${catalogState.itemId}`;
    const pasteBoxId = `taskConfigPasteBox_${catalogState.itemId}`;
    const pasteInputId = `taskConfigPasteInput_${catalogState.itemId}`;
    const messageId = `taskConfigMsg_${catalogState.itemId}`;
    return `
      <div class="d-flex gap-1 mb-2">
        <button type="button" class="btn btn-outline-secondary btn-sm task-config-copy-btn"
          id="${this.escapeAttr(copyBtnId)}"
          data-item-id="${this.escapeAttr(catalogState.itemId)}">Copy Config</button>
        <button type="button" class="btn btn-outline-secondary btn-sm task-config-paste-btn"
          id="${this.escapeAttr(pasteBtnId)}"
          data-item-id="${this.escapeAttr(catalogState.itemId)}"
          data-paste-box-id="${this.escapeAttr(pasteBoxId)}">Paste Config</button>
      </div>
      <div class="mb-2" id="${this.escapeAttr(pasteBoxId)}" style="display:none;">
        <label class="small text-muted d-block mb-1">Paste task config JSON</label>
        <textarea class="form-control form-control-sm task-config-paste-input"
          id="${this.escapeAttr(pasteInputId)}"
          rows="5"
          placeholder='{"settings":{"field":"value"},"requirements":{"items":[]}}'></textarea>
        <div class="d-flex gap-1 mt-1">
          <button type="button" class="btn btn-primary btn-sm task-config-apply-btn"
            id="${this.escapeAttr(applyBtnId)}"
            data-item-id="${this.escapeAttr(catalogState.itemId)}"
            data-paste-input-id="${this.escapeAttr(pasteInputId)}">Apply Pasted Config</button>
        </div>
      </div>
      <div class="small text-muted mb-2" id="${this.escapeAttr(messageId)}"></div>
      <div class="task-settings-fields">${rendered}</div>
    `;
  }

  renderTaskSettingField(field, settingPrefix) {
    const key = field?.key || '';
    if (!key) return '';
    const fieldId = `${settingPrefix}${key}`;
    const label = this.escapeHtml(field?.label || key);
    const type = (field?.type || 'string').toLowerCase();
    const dependsOn = field?.depends_on ? ` data-depends-on="${this.escapeAttr(field.depends_on)}"` : '';
    const optionsByDep = field?.options_by_dependency
      ? ` data-options-by-dependency="${this.escapeAttr(JSON.stringify(field.options_by_dependency))}"`
      : '';
    const baseAttrs = `class="form-control form-control-sm task-setting-input" data-setting-key="${this.escapeAttr(key)}" data-setting-type="${this.escapeAttr(type)}"${dependsOn}${optionsByDep}`;
    const description = field?.description ? `<div class="form-text">${this.escapeHtml(field.description)}</div>` : '';

    if (type === 'boolean') {
      return `
        <div class="form-check mb-2">
          <input type="checkbox" id="${this.escapeAttr(fieldId)}" ${baseAttrs.replace('form-control form-control-sm', 'form-check-input')}>
          <label class="form-check-label small" for="${this.escapeAttr(fieldId)}">${label}</label>
          ${description}
        </div>
      `;
    }

    if (type === 'select' || type === 'multi_select') {
      const options = Array.isArray(field?.options) ? field.options : [];
      const multipleAttr = type === 'multi_select' ? ' multiple' : '';
      const optionHtml = options.map(opt => `<option value="${this.escapeAttr(String(opt))}">${this.escapeHtml(String(opt))}</option>`).join('');
      return `
        <div class="mb-2">
          <label class="small text-muted d-block mb-1" for="${this.escapeAttr(fieldId)}">${label}</label>
          <select id="${this.escapeAttr(fieldId)}" ${baseAttrs}${multipleAttr}>
            ${optionHtml}
          </select>
          ${description}
        </div>
      `;
    }

    const inputType = type === 'integer' || type === 'number' ? 'number' : 'text';
    const minAttr = field?.min !== undefined ? ` min="${this.escapeAttr(String(field.min))}"` : '';
    const maxAttr = field?.max !== undefined ? ` max="${this.escapeAttr(String(field.max))}"` : '';
    const stepAttr = type === 'integer' ? ' step="1"' : (type === 'number' ? ' step="any"' : '');
    return `
      <div class="mb-2">
        <label class="small text-muted d-block mb-1" for="${this.escapeAttr(fieldId)}">${label}</label>
        <input type="${inputType}" id="${this.escapeAttr(fieldId)}" ${baseAttrs}${minAttr}${maxAttr}${stepAttr}>
        ${description}
      </div>
    `;
  }

  async refreshTaskSettingsUI(itemId, selectedTask, settingsContainerId) {
    if (!itemId || !settingsContainerId) return;
    const state = this.itemSelectionState.get(itemId) || {};
    state.selectedTaskKey = selectedTask?.task_key || '';
    state.taskSettings = state.taskSettings || {};
    this.itemSelectionState.set(itemId, state);

    const container = document.getElementById(settingsContainerId);
    const catalogState = this.buildCatalogStateForItem({ itemId, scriptName: state.selectedTaskKey }, 0);
    if (container) {
      container.innerHTML = this.renderTaskSettings(selectedTask, catalogState);
    }

    if (!selectedTask) return;

    await this.prefillTaskSettings(itemId, selectedTask, settingsContainerId);
    this.bindTaskSettingsInputs(itemId, selectedTask, settingsContainerId);
  }

  bindTaskSettingsInputs(itemId, selectedTask, settingsContainerId) {
    const container = document.getElementById(settingsContainerId);
    if (!container) return;
    container.querySelectorAll('.task-setting-input').forEach(input => {
      const eventName = input.type === 'checkbox' ? 'change' : 'input';
      input.addEventListener(eventName, () => {
        this.captureTaskSettingsFromForm(itemId, selectedTask, settingsContainerId);
        this.applyDependencyRules(settingsContainerId);
      });
    });
    container.querySelectorAll('.task-config-copy-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        await this.copyTaskConfig(itemId, selectedTask, settingsContainerId);
      });
    });
    container.querySelectorAll('.task-config-paste-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const pasteBox = document.getElementById(btn.dataset.pasteBoxId);
        if (!pasteBox) return;
        const isHidden = pasteBox.style.display === 'none';
        pasteBox.style.display = isHidden ? 'block' : 'none';
      });
    });
    container.querySelectorAll('.task-config-apply-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        this.applyPastedTaskConfig(itemId, selectedTask, settingsContainerId, btn.dataset.pasteInputId);
      });
    });
    this.applyDependencyRules(settingsContainerId);
  }

  buildTaskConfigEnvelope(itemId, selectedTask, settingsContainerId) {
    const settings = this.captureTaskSettingsFromForm(itemId, selectedTask, settingsContainerId);
    const state = this.itemSelectionState.get(itemId) || {};
    const requirements = state.taskRequirements || {};
    return {
      version: 1,
      task_key: selectedTask?.task_key || '',
      display_name: selectedTask?.display_name || '',
      panel_display_name: selectedTask?.panel_display_name || '',
      settings,
      requirements
    };
  }

  async copyTaskConfig(itemId, selectedTask, settingsContainerId) {
    if (!selectedTask) return;
    try {
      const envelope = this.buildTaskConfigEnvelope(itemId, selectedTask, settingsContainerId);
      await navigator.clipboard.writeText(JSON.stringify(envelope, null, 2));
      this.showTaskConfigMessage(itemId, 'Config copied to clipboard.', 'success');
    } catch (error) {
      console.error('Failed to copy config:', error);
      this.showTaskConfigMessage(itemId, 'Failed to copy config.', 'error');
    }
  }

  normalizePastedConfig(rawParsed) {
    if (!rawParsed || typeof rawParsed !== 'object' || Array.isArray(rawParsed)) {
      return { error: 'Pasted JSON must be an object.' };
    }
    const asObject = value => (value && typeof value === 'object' && !Array.isArray(value) ? value : null);

    const builderExecution = asObject(rawParsed.builder_execution);
    const queuePreview = asObject(rawParsed.queue_payload_preview);
    const envelopeSettings = asObject(rawParsed.settings);
    const builderSettings = asObject(builderExecution?.task_metadata);
    const queueSettings = asObject(queuePreview?.task_metadata);

    const settings =
      builderSettings ||
      queueSettings ||
      envelopeSettings ||
      rawParsed;

    if (!asObject(settings)) {
      return { error: 'Could not find a valid settings object in pasted JSON.' };
    }

    const requirements = asObject(rawParsed.requirements) || {};
    const taskKey =
      builderExecution?.task_key ||
      queuePreview?.task_key ||
      rawParsed.task_key ||
      null;

    return { settings, requirements, taskKey };
  }

  applyPastedTaskConfig(itemId, selectedTask, settingsContainerId, pasteInputId) {
    const input = document.getElementById(pasteInputId);
    if (!input) return;
    const raw = (input.value || '').trim();
    if (!raw) {
      this.showTaskConfigMessage(itemId, 'Paste JSON first.', 'warning');
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (error) {
      this.showTaskConfigMessage(itemId, `Invalid JSON: ${error.message}`, 'error');
      return;
    }

    const normalized = this.normalizePastedConfig(parsed);
    if (normalized.error) {
      this.showTaskConfigMessage(itemId, normalized.error, 'error');
      return;
    }

    const state = this.itemSelectionState.get(itemId) || {};
    const resolvedTask = this.findCatalogTaskByKeyFlexible(normalized.taskKey) || selectedTask || null;
    const resolvedTaskKey = resolvedTask?.task_key || selectedTask?.task_key || state.selectedTaskKey || '';
    state.selectedTaskKey = resolvedTaskKey;
    state.taskSettings = normalized.settings || {};
    state.taskRequirements = normalized.requirements || {};
    this.itemSelectionState.set(itemId, state);

    const selector = document.getElementById(`catalogSelect_${itemId}`);
    if (selector && resolvedTask?.task_key) {
      selector.value = resolvedTask.task_key;
    }

    this.refreshTaskSettingsUI(itemId, resolvedTask, settingsContainerId).then(() => {
      this.applySettingsToForm(settingsContainerId, state.taskSettings);
    });

    const allKeys = Object.keys(state.taskSettings);
    const appliedKeys = [];
    const ignoredKeys = [];
    const container = document.getElementById(settingsContainerId);
    if (container) {
      const knownKeys = new Set(
        Array.from(container.querySelectorAll('.task-setting-input'))
          .map(el => el.dataset.settingKey)
          .filter(Boolean)
      );
      allKeys.forEach(k => {
        if (knownKeys.has(k)) appliedKeys.push(k);
        else ignoredKeys.push(k);
      });
    }
    const reqCount = Object.keys(state.taskRequirements).length;
    const taskMsg = resolvedTask?.task_key ? ` for ${resolvedTask.task_key}` : '';
    const msg = `Applied ${appliedKeys.length} setting(s)${taskMsg}` +
      (ignoredKeys.length ? `, ignored ${ignoredKeys.length} unknown key(s)` : '') +
      (reqCount ? `, stored requirements metadata (${reqCount} key(s))` : '') +
      '.';
    this.showTaskConfigMessage(itemId, msg, ignoredKeys.length ? 'warning' : 'success');
  }

  showTaskConfigMessage(itemId, message, tone) {
    const el = document.getElementById(`taskConfigMsg_${itemId}`);
    if (!el) return;
    const cls = tone === 'error'
      ? 'text-danger'
      : tone === 'warning'
        ? 'text-warning'
        : 'text-success';
    el.className = `small mb-2 ${cls}`;
    el.textContent = message;
  }

  applyDependencyRules(settingsContainerId) {
    const container = document.getElementById(settingsContainerId);
    if (!container) return;

    container.querySelectorAll('[data-depends-on]').forEach(input => {
      const dependsOnKey = input.dataset.dependsOn;
      const dependency = container.querySelector(`[data-setting-key="${dependsOnKey}"]`);
      if (!dependency) return;

      const depValue = this.readSettingInputValue(dependency);
      const mapRaw = input.dataset.optionsByDependency;
      if (mapRaw && input.tagName === 'SELECT') {
        try {
          const map = JSON.parse(mapRaw);
          const branch = Array.isArray(map?.[depValue]) ? map[depValue] : [];
          if (branch.length) {
            const prevValue = input.value;
            input.innerHTML = branch
              .map(v => `<option value="${this.escapeAttr(String(v))}">${this.escapeHtml(String(v))}</option>`)
              .join('');
            if (Array.from(input.options).some(o => o.value === prevValue)) {
              input.value = prevValue;
            }
          }
        } catch (error) {
          console.debug('Failed applying dependency options', error);
        }
      }
    });
  }

  async prefillTaskSettings(itemId, selectedTask, settingsContainerId) {
    const state = this.itemSelectionState.get(itemId) || {};
    let settings = state.taskSettings || {};
    if (!Object.keys(settings).length) {
      settings = await this.loadSavedTaskSettings(selectedTask);
      if (!Object.keys(settings).length) {
        settings = this.buildDefaultSettings(selectedTask);
      }
    }
    state.taskSettings = settings;
    this.itemSelectionState.set(itemId, state);
    this.applySettingsToForm(settingsContainerId, settings);
  }

  async loadSavedTaskSettings(selectedTask) {
    try {
      const query = new URLSearchParams();
      if (selectedTask?.task_key) query.set('taskKey', selectedTask.task_key);
      if (selectedTask?.panel_display_name) {
        query.set('displayName', selectedTask.panel_display_name);
      } else if (selectedTask?.display_name) {
        query.set('displayName', selectedTask.display_name);
      }
      const response = await fetch(`/api/account-tasks/stabilization/config?${query.toString()}`);
      if (!response.ok) return {};
      const payload = await response.json();
      if (payload && payload.success === true && payload.settings && typeof payload.settings === 'object') {
        return payload.settings;
      }
      return {};
    } catch (error) {
      console.debug('Could not load saved task settings:', error);
      return {};
    }
  }

  buildDefaultSettings(task) {
    const defaults = {};
    const fields = Array.isArray(task?.args_schema?.fields) ? task.args_schema.fields : [];
    fields.forEach(field => {
      if (field?.key && field.default !== undefined) {
        defaults[field.key] = field.default;
      }
    });
    return defaults;
  }

  applySettingsToForm(settingsContainerId, settings) {
    const container = document.getElementById(settingsContainerId);
    if (!container) return;
    container.querySelectorAll('.task-setting-input').forEach(input => {
      const key = input.dataset.settingKey;
      if (!key || settings[key] === undefined) return;
      const value = settings[key];
      if (input.type === 'checkbox') {
        input.checked = Boolean(value);
      } else if (input.multiple && Array.isArray(value)) {
        Array.from(input.options).forEach(option => {
          option.selected = value.includes(option.value);
        });
      } else if (value !== null && value !== undefined) {
        input.value = value;
      }
    });
    this.applyDependencyRules(settingsContainerId);
  }

  readSettingInputValue(input) {
    if (input.type === 'checkbox') return input.checked;
    if (input.multiple) return Array.from(input.selectedOptions).map(option => option.value);

    const raw = input.value;
    const type = input.dataset.settingType;
    if (type === 'integer') return raw === '' ? null : parseInt(raw, 10);
    if (type === 'number') return raw === '' ? null : parseFloat(raw);
    return raw;
  }

  captureTaskSettingsFromForm(itemId, selectedTask, settingsContainerId) {
    const container = document.getElementById(settingsContainerId);
    if (!container) return {};

    const settings = {};
    container.querySelectorAll('.task-setting-input').forEach(input => {
      const key = input.dataset.settingKey;
      if (!key) return;
      settings[key] = this.readSettingInputValue(input);
    });

    const state = this.itemSelectionState.get(itemId) || {};
    state.selectedTaskKey = selectedTask?.task_key || state.selectedTaskKey || '';
    state.taskSettings = settings;
    this.itemSelectionState.set(itemId, state);

    return settings;
  }

  filterCatalogSelector(selector, query) {
    const normalizedQuery = (query || '').toLowerCase().trim();
    const selectedValue = selector.value;
    let visibleCount = 0;

    selector.querySelectorAll('optgroup').forEach(group => {
      let groupVisible = 0;
      group.querySelectorAll('option').forEach(option => {
        const text = option.textContent ? option.textContent.toLowerCase() : '';
        const visible = normalizedQuery.length === 0 || text.includes(normalizedQuery);
        option.hidden = !visible;
        if (visible) {
          groupVisible++;
        }
      });
      group.hidden = groupVisible === 0;
      visibleCount += groupVisible;
    });

    if (visibleCount === 0) {
      selector.value = '';
      return;
    }

    const selectedOption = Array.from(selector.options).find(option => option.value === selectedValue);
    if (selectedOption && !selectedOption.hidden) {
      selector.value = selectedValue;
    } else {
      const firstVisible = Array.from(selector.options).find(option => !option.hidden && option.value);
      if (firstVisible) {
        selector.value = firstVisible.value;
      }
    }
  }

  renderCitations(citations) {
    if (!citations || citations.length === 0) return '';

    const citationsHtml = citations.map(c => `
      <div class="citation-item">
        <span class="citation-type">${this.escapeHtml(c.evidenceType || 'unknown')}</span>
        <span class="citation-snippet" title="${this.escapeAttr(c.snippet || '')}">
          ${this.escapeHtml(c.snippet || c.evidenceId || 'No snippet')}
        </span>
        ${c.relevanceScore ? `<span class="citation-score">${(c.relevanceScore * 100).toFixed(0)}%</span>` : ''}
      </div>
    `).join('');

    return `
      <div class="citation-list">
        <small class="text-muted"><i class="bi bi-link-45deg me-1"></i>${citations.length} citation${citations.length !== 1 ? 's' : ''}</small>
        ${citationsHtml}
      </div>
    `;
  }

  async generatePlan() {
    if (!this.intentInput) return;

    const intent = this.intentInput.value.trim();
    if (!intent) {
      this.intentInput.classList.add('is-invalid');
      return;
    }
    this.intentInput.classList.remove('is-invalid');

    const sourceSessionId = this.sourceSessionSelect ? this.sourceSessionSelect.value : null;

    if (this.generateBtn) {
      this.generateBtn.disabled = true;
      this.generateBtn.innerHTML = '<i class="bi bi-hourglass me-1"></i>Generating...';
    }

    try {
      const request = {
        intent: intent,
        sourceSessionId: sourceSessionId || null,
        sourceRecordingId: null
      };

      const response = await fetch('/api/account-tasks/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const plan = await response.json();
      this.intentInput.value = '';

      await this.loadPlans();
      if (plan.planId) {
        this.selectPlan(plan.planId);
      }
    } catch (error) {
      console.error('Failed to generate plan:', error);
      alert('Failed to generate plan: ' + error.message);
    } finally {
      if (this.generateBtn) {
        this.generateBtn.disabled = false;
        this.generateBtn.innerHTML = '<i class="bi bi-magic me-1"></i>Generate Plan';
      }
    }
  }

  async approvePlan() {
    if (!this.selectedPlanId) return;

    try {
      const response = await fetch(`/api/account-tasks/${this.selectedPlanId}/approve`, {
        method: 'POST'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      await this.loadPlanDetail(this.selectedPlanId);
      await this.loadPlans();
    } catch (error) {
      console.error('Failed to approve plan:', error);
    }
  }

  async rejectPlan() {
    if (!this.selectedPlanId) return;

    try {
      const response = await fetch(`/api/account-tasks/${this.selectedPlanId}/reject`, {
        method: 'POST'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      this.selectedPlanId = null;
      this.selectedPlan = null;
      if (this.planDetailSection) {
        this.planDetailSection.style.display = 'none';
      }
      await this.loadPlans();
    } catch (error) {
      console.error('Failed to reject plan:', error);
    }
  }

  async approveItem(itemId) {
    try {
      const response = await fetch(`/api/account-tasks/items/${itemId}/approve`, {
        method: 'POST'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      }
    } catch (error) {
      console.error('Failed to approve item:', error);
    }
  }

  async rejectItem(itemId) {
    try {
      const response = await fetch(`/api/account-tasks/items/${itemId}/reject`, {
        method: 'POST'
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      }
    } catch (error) {
      console.error('Failed to reject item:', error);
    }
  }

  async executeItemPatchLoop(itemId, scriptName, settingsId) {
    try {
      const payload = scriptName ? { scriptName } : {};
      if (settingsId && scriptName) {
        const selectedTask = this.getCatalogTaskByKey(scriptName);
        const settings = this.captureTaskSettingsFromForm(itemId, selectedTask, settingsId);
        if (selectedTask) {
          payload.taskMetadata = {
            task_key: selectedTask.task_key || '',
            task_name: selectedTask.task_key || scriptName,
            display_name: selectedTask.display_name || selectedTask.task_key || scriptName,
            panel_display_name: selectedTask.panel_display_name || selectedTask.display_name || selectedTask.task_key || scriptName,
            settings,
            persist_settings: true
          };
        }
      }

      const response = await fetch(`/api/account-tasks/items/${itemId}/execute-patch-loop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const result = await response.json();
      const executionId =
        result?.executionId ||
        result?.patchLoop?.executionId ||
        result?.patchLoop?.iterationId ||
        null;
      if (window.patchLoopManager && executionId) {
        window.patchLoopManager.connect(executionId);
      }

      const patchPanel = document.getElementById('patchLoopPanel');
      if (patchPanel && patchPanel.style.display === 'none') {
        patchPanel.style.display = 'block';
      }

      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      }
    } catch (error) {
      console.error('Failed to execute patch loop for item:', error);
      alert('Failed to execute: ' + error.message);
    }
  }

  async executeItemQuickTest(itemId, scriptName, settingsId) {
    try {
      const payload = scriptName ? { scriptName } : {};
      if (settingsId && scriptName) {
        const selectedTask = this.getCatalogTaskByKey(scriptName);
        const settings = this.captureTaskSettingsFromForm(itemId, selectedTask, settingsId);
        if (selectedTask) {
          payload.taskMetadata = {
            task_key: selectedTask.task_key || '',
            task_name: selectedTask.task_key || scriptName,
            display_name: selectedTask.display_name || selectedTask.task_key || scriptName,
            panel_display_name: selectedTask.panel_display_name || selectedTask.display_name || selectedTask.task_key || scriptName,
            settings,
            persist_settings: true,
            source: 'planner_quick_test'
          };
        }
      }

      const response = await fetch(`/api/account-tasks/items/${itemId}/quick-test`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const result = await response.json();
      const executionId =
        result?.executionId ||
        result?.patchLoop?.executionId ||
        result?.patchLoop?.iterationId ||
        null;
      if (window.patchLoopManager && executionId) {
        window.patchLoopManager.connect(executionId);
      }

      const patchPanel = document.getElementById('patchLoopPanel');
      if (patchPanel && patchPanel.style.display === 'none') {
        patchPanel.style.display = 'block';
      }

      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      }
    } catch (error) {
      console.error('Failed to run quick test for item:', error);
      alert('Failed to run quick test: ' + error.message);
    }
  }

  escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  escapeAttr(text) {
    if (!text) return '';
    return text
      .replace(/&/g, '&amp;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  formatTimeAgo(dateStr) {
    try {
      const date = new Date(dateStr);
      const now = new Date();
      const diffMs = now - date;
      const diffMins = Math.floor(diffMs / 60000);
      const diffHours = Math.floor(diffMs / 3600000);
      const diffDays = Math.floor(diffMs / 86400000);

      if (diffMins < 1) return 'just now';
      if (diffMins < 60) return `${diffMins}m ago`;
      if (diffHours < 24) return `${diffHours}h ago`;
      return `${diffDays}d ago`;
    } catch (e) {
      return '';
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.accountTaskPlanner = new AccountTaskPlanner();
  window.accountTaskPlanner.init();
});
