/**
 * Account Task Planner - Manages task plan generation, review, and execution.
 *
 * Provides UI for generating task plans from evidence, reviewing items,
 * approving/rejecting tasks, and triggering patch loop execution.
 */
class AccountTaskPlanner {
  static BUILDER_GOAL_BUNDLE_VERSION = '21';
  static BUILDER_GOAL_PRIMARY_ITEM_ID = 'builderGoalPrimary';
  static UNASSIGNED_FILTER = '__UNASSIGNED__';
  static BUILDER_GOAL_PUBLISH_TIMEOUT_MS = 240000;

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
    this.builderGoalAccounts = [];
    this.builderGoalMachines = [];
    this.builderGoalRuns = [];
    this.selectedBuilderGoalAccountId =
      localStorage.getItem('taskPlanner.selectedBuilderGoalAccountId') || '';
    this.selectedBuilderGoalMachineUuid =
      localStorage.getItem('taskPlanner.selectedBuilderGoalMachineUuid') || '';
    this.selectedBuilderGoalTaskKey =
      localStorage.getItem('taskPlanner.selectedBuilderGoalTaskKey') || '';
    this.builderGoalRefreshHandle = null;
    this.builderGoalBundleMismatch = false;
    this.builderGoalConfigDirty = false;
    this.builderGoalConfigLoadedKey = '';
    this.builderGoalAnalysisModal = null;
    this.builderGoalAnalysisDraft = null;
    this.builderGoalAnalysisIterationId = null;
    this.builderGoalCatalogAvailable = false;
    this.builderGoalCatalogDegraded = false;
    this.builderGoalCatalogReason = '';
    this.builderGoalCatalogSource = '';
    this.builderGoalLogsIterationId = null;
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
      this.loadBuilderGoalMachines();
      this.loadBuilderGoalAccounts();
      this.loadBuilderGoalRuns();
    }
    this.loadStabilizationKpis();
    if (!this.builderGoalBundleMismatch && !this.builderGoalRefreshHandle) {
      this.builderGoalRefreshHandle = setInterval(() => {
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
    this.builderGoalAccountSelect = document.getElementById('builderGoalAccountSelect');
    this.builderGoalAccountSummary = document.getElementById('builderGoalAccountSummary');
    this.builderGoalMachineSelect = document.getElementById('builderGoalMachineSelect');
    this.builderGoalMachineSummary = document.getElementById('builderGoalMachineSummary');
    this.builderGoalWorldInput = document.getElementById('builderGoalWorldInput');
    this.builderGoalTaskSearchInput = document.getElementById('builderGoalTaskSearchInput');
    this.builderGoalTaskSelect = document.getElementById('builderGoalTaskSelect');
    this.builderGoalConfigInput = document.getElementById('builderGoalConfigInput');
    this.builderGoalConfigMeta = document.getElementById('builderGoalConfigMeta');
    this.builderGoalPayloadInput = document.getElementById('builderGoalPayloadInput');
    this.importBuilderGoalBtn = document.getElementById('importBuilderGoalBtn');
    this.runBuilderGoalBtn = document.getElementById('runBuilderGoalBtn');
    this.runImportedBuilderGoalBtn = document.getElementById('runImportedBuilderGoalBtn');
    this.builderGoalClientStatus = document.getElementById('builderGoalClientStatus');
    this.builderGoalImportResult = document.getElementById('builderGoalImportResult');
    this.builderGoalRunsList = document.getElementById('builderGoalRunsList');
    this.refreshBuilderGoalRunsBtn = document.getElementById('refreshBuilderGoalRunsBtn');
    this.builderGoalLogsPanel = document.getElementById('builderGoalLogsPanel');
    this.builderGoalLogsSummary = document.getElementById('builderGoalLogsSummary');
    this.builderGoalLogsStatus = document.getElementById('builderGoalLogsStatus');
    this.builderGoalLaunchLogTail = document.getElementById('builderGoalLaunchLogTail');
    this.builderGoalHistoricalLogs = document.getElementById('builderGoalHistoricalLogs');
    this.closeBuilderGoalLogsBtn = document.getElementById('closeBuilderGoalLogsBtn');
    this.builderGoalAnalysisModalElement = document.getElementById('builderGoalAnalysisModal');
    this.builderGoalAnalysisPrompt = document.getElementById('builderGoalAnalysisPrompt');
    this.builderGoalAnalysisContext = document.getElementById('builderGoalAnalysisContext');
    this.builderGoalAnalysisStartTime = document.getElementById('builderGoalAnalysisStartTime');
    this.builderGoalAnalysisEndTime = document.getElementById('builderGoalAnalysisEndTime');
    this.builderGoalAnalysisRangeHint = document.getElementById('builderGoalAnalysisRangeHint');
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
    if (this.builderGoalAccountSelect) {
      this.builderGoalAccountSelect.disabled = true;
      this.builderGoalAccountSelect.innerHTML =
        '<option value="">Refresh debugger to load the latest Builder Goal Runner</option>';
    }
    if (this.builderGoalMachineSelect) {
      this.builderGoalMachineSelect.disabled = true;
      this.builderGoalMachineSelect.innerHTML =
        '<option value="">Refresh debugger to load the latest Builder Goal Runner</option>';
    }
    if (this.builderGoalTaskSearchInput) {
      this.builderGoalTaskSearchInput.disabled = true;
    }
    if (this.builderGoalTaskSelect) {
      this.builderGoalTaskSelect.disabled = true;
      this.builderGoalTaskSelect.innerHTML =
        '<option value="">Refresh debugger to load the latest Builder Goal Runner</option>';
    }
    if (this.builderGoalWorldInput) {
      this.builderGoalWorldInput.disabled = true;
    }
    if (this.importBuilderGoalBtn) {
      this.importBuilderGoalBtn.disabled = true;
    }
    if (this.runBuilderGoalBtn) {
      this.runBuilderGoalBtn.disabled = true;
    }
    if (this.runImportedBuilderGoalBtn) {
      this.runImportedBuilderGoalBtn.disabled = true;
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
    if (this.runImportedBuilderGoalBtn) {
      this.runImportedBuilderGoalBtn.addEventListener('click', () => this.runImportedBuilderGoal());
    }
    if (this.builderGoalAccountSelect) {
      this.builderGoalAccountSelect.addEventListener('change', async () => {
        this.selectedBuilderGoalAccountId = this.builderGoalAccountSelect.value || '';
        if (this.selectedBuilderGoalAccountId) {
          localStorage.setItem(
            'taskPlanner.selectedBuilderGoalAccountId',
            this.selectedBuilderGoalAccountId
          );
        } else {
          localStorage.removeItem('taskPlanner.selectedBuilderGoalAccountId');
        }
        this.renderBuilderGoalAccountSummary();
        await this.prefillBuilderGoalConfigForSelection();
        this.renderBuilderGoalClientStatus();
        this.updateBuilderGoalRunButtonState();
      });
    }
    if (this.builderGoalMachineSelect) {
      this.builderGoalMachineSelect.addEventListener('change', () => {
        this.selectedBuilderGoalMachineUuid = this.builderGoalMachineSelect.value || '';
        if (this.selectedBuilderGoalMachineUuid) {
          localStorage.setItem(
            'taskPlanner.selectedBuilderGoalMachineUuid',
            this.selectedBuilderGoalMachineUuid
          );
        } else {
          localStorage.removeItem('taskPlanner.selectedBuilderGoalMachineUuid');
        }
        this.renderBuilderGoalMachineSummary();
        this.renderBuilderGoalClientStatus();
        this.updateBuilderGoalRunButtonState();
      });
    }
    if (this.builderGoalTaskSearchInput) {
      this.builderGoalTaskSearchInput.addEventListener('input', () => {
        this.renderBuilderGoalTaskOptions(this.builderGoalTaskSearchInput.value || '');
      });
    }
    if (this.builderGoalTaskSelect) {
      this.builderGoalTaskSelect.addEventListener('change', async () => {
        this.selectedBuilderGoalTaskKey = this.builderGoalTaskSelect.value || '';
        if (this.selectedBuilderGoalTaskKey) {
          localStorage.setItem(
            'taskPlanner.selectedBuilderGoalTaskKey',
            this.selectedBuilderGoalTaskKey
          );
        } else {
          localStorage.removeItem('taskPlanner.selectedBuilderGoalTaskKey');
        }
        await this.prefillBuilderGoalConfigForSelection();
        this.renderBuilderGoalClientStatus();
        this.updateBuilderGoalRunButtonState();
      });
    }
    if (this.builderGoalWorldInput) {
      this.builderGoalWorldInput.addEventListener('input', () => {
        this.renderBuilderGoalConfigMeta();
        this.renderBuilderGoalClientStatus();
      });
    }
    if (this.builderGoalConfigInput) {
      this.builderGoalConfigInput.addEventListener('input', () => {
        this.builderGoalConfigDirty = true;
        this.renderBuilderGoalConfigMeta();
      });
    }
    if (this.refreshBuilderGoalRunsBtn) {
      this.refreshBuilderGoalRunsBtn.addEventListener('click', () => {
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
        } else if (action === 'load-config') {
          this.loadBuilderGoalRunConfig(iterationId);
        } else if (action === 'rerun') {
          this.rerunBuilderGoal(iterationId);
        } else if (action === 'view-logs') {
          this.loadBuilderGoalRunLogs(iterationId);
        }
      });
    }
    if (this.closeBuilderGoalLogsBtn) {
      this.closeBuilderGoalLogsBtn.addEventListener('click', () => this.closeBuilderGoalRunLogs());
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
      const response = await fetch('/api/debug/builder-goals/catalog');
      if (!response.ok) return;
      const payload = await response.json();
      this.scriptCatalog = Array.isArray(payload.tasks) ? payload.tasks : [];
      this.builderGoalCatalogAvailable = Boolean(payload.catalogAvailable);
      this.builderGoalCatalogDegraded = Boolean(payload.degraded);
      this.builderGoalCatalogReason = payload.degradedReason || '';
      this.builderGoalCatalogSource = payload.source || '';
      this.renderBuilderGoalTaskOptions(this.builderGoalTaskSearchInput?.value || '');
      if (this.selectedBuilderGoalTaskKey) {
        await this.prefillBuilderGoalConfigForSelection();
      }
      this.renderBuilderGoalClientStatus();
    } catch (error) {
      console.debug('Could not load script catalog:', error);
      this.scriptCatalog = [];
      this.builderGoalCatalogAvailable = false;
      this.builderGoalCatalogDegraded = true;
      this.builderGoalCatalogReason =
        error?.message || 'Could not load the Builder Goal catalog.';
      this.builderGoalCatalogSource = 'error';
      this.renderBuilderGoalTaskOptions(this.builderGoalTaskSearchInput?.value || '');
      this.renderBuilderGoalClientStatus();
    }
  }

  async loadBuilderGoalAccounts() {
    if (this.builderGoalBundleMismatch) return;
    try {
      const response = await fetch('/api/debug/builder-goals/accounts');
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.builderGoalAccounts = await response.json();
      this.renderBuilderGoalAccounts();
      await this.prefillBuilderGoalConfigForSelection();
    } catch (error) {
      console.error('Failed to load builder goal accounts:', error);
      this.builderGoalAccounts = [];
      this.renderBuilderGoalAccounts(error.message);
    }
  }

  renderBuilderGoalAccounts(loadError = null) {
    if (!this.builderGoalAccountSelect || this.builderGoalBundleMismatch) return;

    const accounts = Array.isArray(this.builderGoalAccounts) ? this.builderGoalAccounts : [];
    const previous = this.selectedBuilderGoalAccountId;
    const stillVisible = accounts.some(account => account.accountId === previous);
    this.selectedBuilderGoalAccountId = stillVisible ? previous : '';
    if (!this.selectedBuilderGoalAccountId && accounts.length === 1) {
      this.selectedBuilderGoalAccountId = accounts[0].accountId || '';
    }
    if (this.selectedBuilderGoalAccountId) {
      localStorage.setItem(
        'taskPlanner.selectedBuilderGoalAccountId',
        this.selectedBuilderGoalAccountId
      );
    } else {
      localStorage.removeItem('taskPlanner.selectedBuilderGoalAccountId');
    }

    if (loadError) {
      this.builderGoalAccountSelect.innerHTML =
        '<option value="">Failed to load accounts</option>';
      this.builderGoalAccountSelect.disabled = true;
      this.renderBuilderGoalAccountSummary(`Failed to load accounts: ${loadError}`, 'danger');
      this.renderBuilderGoalClientStatus();
      this.updateBuilderGoalRunButtonState();
      return;
    }

    if (accounts.length === 0) {
      this.builderGoalAccountSelect.innerHTML = '<option value="">No accounts available</option>';
      this.builderGoalAccountSelect.disabled = true;
      this.renderBuilderGoalAccountSummary(
        'No launcher accounts are currently available for Builder Goal Runner.',
        'warning'
      );
      this.renderBuilderGoalClientStatus();
      this.updateBuilderGoalRunButtonState();
      return;
    }

    this.builderGoalAccountSelect.disabled = false;
    const options = ['<option value="">Select an account</option>'];
    accounts.forEach(account => {
      const label = this.formatBuilderGoalAccountLabel(account);
      options.push(
        `<option value="${this.escapeAttr(account.accountId || '')}">${this.escapeHtml(label)}</option>`
      );
    });
    this.builderGoalAccountSelect.innerHTML = options.join('');
    this.builderGoalAccountSelect.value = this.selectedBuilderGoalAccountId || '';
    this.renderBuilderGoalAccountSummary();
    this.renderBuilderGoalConfigMeta();
    this.renderBuilderGoalClientStatus();
    this.updateBuilderGoalRunButtonState();
  }

  async loadBuilderGoalMachines() {
    if (!this.builderGoalMachineSelect || this.builderGoalBundleMismatch) return;

    try {
      const response = await fetch('/api/debug/builder-goals/machines');
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.builderGoalMachines = await response.json();
      this.renderBuilderGoalMachines();
    } catch (error) {
      console.error('Failed to load builder goal launcher machines:', error);
      this.builderGoalMachines = [];
      this.renderBuilderGoalMachines(error.message);
    }
  }

  getSelectedBuilderGoalAccount() {
    const selectedId = this.builderGoalAccountSelect
      ? this.builderGoalAccountSelect.value
      : this.selectedBuilderGoalAccountId;
    return (this.builderGoalAccounts || []).find(account => account.accountId === selectedId) || null;
  }

  renderBuilderGoalMachines(loadError = null) {
    if (!this.builderGoalMachineSelect || this.builderGoalBundleMismatch) return;

    const machines = Array.isArray(this.builderGoalMachines) ? this.builderGoalMachines : [];
    const previous = this.selectedBuilderGoalMachineUuid;
    const stillVisible = machines.some(machine => machine.machineUuid === previous);
    this.selectedBuilderGoalMachineUuid = stillVisible ? previous : '';
    if (!this.selectedBuilderGoalMachineUuid) {
      const defaultMachine = machines.find(machine => machine.defaultSelected);
      if (defaultMachine?.machineUuid) {
        this.selectedBuilderGoalMachineUuid = defaultMachine.machineUuid;
      } else if (machines.length === 1) {
        this.selectedBuilderGoalMachineUuid = machines[0].machineUuid || '';
      }
    }
    if (this.selectedBuilderGoalMachineUuid) {
      localStorage.setItem(
        'taskPlanner.selectedBuilderGoalMachineUuid',
        this.selectedBuilderGoalMachineUuid
      );
    } else {
      localStorage.removeItem('taskPlanner.selectedBuilderGoalMachineUuid');
    }

    if (loadError) {
      this.builderGoalMachineSelect.innerHTML =
        '<option value="">Failed to load machines</option>';
      this.builderGoalMachineSelect.disabled = true;
      this.renderBuilderGoalMachineSummary(`Failed to load launcher machines: ${loadError}`, 'danger');
      this.renderBuilderGoalClientStatus();
      this.updateBuilderGoalRunButtonState();
      return;
    }

    if (machines.length === 0) {
      this.builderGoalMachineSelect.innerHTML =
        '<option value="">No launcher machines available</option>';
      this.builderGoalMachineSelect.disabled = true;
      this.renderBuilderGoalMachineSummary(
        'No local launch host is currently available. Builder Goal Runner cannot launch until launcher-control reports at least one local machine.',
        'warning'
      );
      this.renderBuilderGoalClientStatus();
      this.updateBuilderGoalRunButtonState();
      return;
    }

    this.builderGoalMachineSelect.disabled = false;
    const options = ['<option value="">Select a launcher machine</option>'];
    machines.forEach(machine => {
      options.push(
        `<option value="${this.escapeAttr(machine.machineUuid || '')}">${this.escapeHtml(this.formatBuilderGoalMachineLabel(machine))}</option>`
      );
    });
    this.builderGoalMachineSelect.innerHTML = options.join('');
    this.builderGoalMachineSelect.value = this.selectedBuilderGoalMachineUuid || '';
    this.renderBuilderGoalMachineSummary();
    this.renderBuilderGoalClientStatus();
    this.updateBuilderGoalRunButtonState();
  }

  getSelectedBuilderGoalMachine() {
    const selectedUuid = this.builderGoalMachineSelect
      ? this.builderGoalMachineSelect.value
      : this.selectedBuilderGoalMachineUuid;
    return (this.builderGoalMachines || []).find(machine => machine.machineUuid === selectedUuid) || null;
  }

  renderBuilderGoalAccountSummary(message = null, tone = 'muted') {
    if (!this.builderGoalAccountSummary) return;
    if (message) {
      this.builderGoalAccountSummary.innerHTML =
        `<span class="text-${tone}">${this.escapeHtml(message)}</span>`;
      return;
    }
    const account = this.getSelectedBuilderGoalAccount();
    if (!account) {
      this.builderGoalAccountSummary.innerHTML =
        '<span class="text-muted">Select an account from launcher control to inspect launch readiness and run availability.</span>';
      return;
    }
    const parts = [];
    if (account.displayName || account.accountName) {
      parts.push(account.displayName || account.accountName);
    }
    if (account.accountHash) {
      parts.push(`hash ${account.accountHash}`);
    }
    if (account.source) {
      parts.push(this.formatBuilderGoalAccountSourceLabel(account.source));
    }
    if (account.online) {
      parts.push('launcher online');
    } else {
      parts.push('launcher offline');
    }
    if (!account.runnable && account.disabledReason) {
      parts.push(account.disabledReason);
    }
    this.builderGoalAccountSummary.innerHTML =
      `<span class="text-muted">${this.escapeHtml(parts.join(' • '))}</span>`;
  }

  renderBuilderGoalMachineSummary(message = null, tone = 'muted') {
    if (!this.builderGoalMachineSummary) return;
    if (message) {
      this.builderGoalMachineSummary.innerHTML =
        `<span class="text-${tone}">${this.escapeHtml(message)}</span>`;
      return;
    }
    const machine = this.getSelectedBuilderGoalMachine();
    if (!machine) {
      this.builderGoalMachineSummary.innerHTML =
        '<span class="text-muted">ChromaScape will auto-select the local machine for Builder Goal launches when one is available.</span>';
      return;
    }
    const parts = [];
    parts.push(machine.displayName || machine.machineUuid || 'Unknown machine');
    if (machine.machineUuid) {
      parts.push(machine.machineUuid);
    }
    if (machine.status) {
      parts.push(String(machine.status).toLowerCase());
    }
    if (machine.defaultSelected) {
      parts.push('auto-selected');
    }
    if (!machine.builderGoalRunnable && machine.builderGoalDisabledReason) {
      parts.push(machine.builderGoalDisabledReason);
    }
    this.builderGoalMachineSummary.innerHTML =
      `<span class="text-muted">${this.escapeHtml(parts.join(' • '))}</span>`;
  }

  formatBuilderGoalAccountLabel(account) {
    const label = account.displayName || account.accountName || account.email || account.accountId || 'Unknown account';
    const tags = [];
    if (account.source) {
      tags.push(this.formatBuilderGoalAccountSourceLabel(account.source));
    }
    tags.push(account.online ? 'launcher online' : 'launcher offline');
    if (!account.runnable) {
      tags.push('setup needed');
    }
    return tags.length ? `${label} • ${tags.join(' • ')}` : label;
  }

  formatBuilderGoalAccountSourceLabel(source) {
    return String(source || '')
      .split('+')
      .filter(Boolean)
      .map(part => part.replaceAll('_', ' '))
      .map(part => (part === 'launcher' ? 'launcher control' : part))
      .join(' + ');
  }

  formatBuilderGoalMachineLabel(machine) {
    const label = machine.displayName || machine.machineUuid || 'Unknown machine';
    const tags = [];
    if (machine.status) {
      tags.push(String(machine.status).toUpperCase());
    }
    tags.push(machine.localMachine ? 'local dev host' : 'remote host');
    if (machine.defaultSelected) {
      tags.push('default');
    }
    if (!machine.builderGoalRunnable) {
      tags.push('setup needed');
    }
    if (machine.machineUuid && machine.machineUuid !== label) {
      tags.push(machine.machineUuid);
    }
    return tags.length ? `${label} • ${tags.join(' • ')}` : label;
  }

  renderBuilderGoalTaskOptions(searchQuery = '') {
    if (!this.builderGoalTaskSelect) return;

    const query = (searchQuery || '').trim().toLowerCase();
    const tasks = (this.scriptCatalog || []).filter(task => {
      const haystack = [
        task?.display_name,
        task?.panel_display_name,
        task?.task_key,
        task?.category
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return !query || haystack.includes(query);
    });

    if (tasks.length === 0) {
      this.builderGoalTaskSelect.innerHTML =
        '<option value="">No Micro6 tasks match the current search</option>';
      this.builderGoalTaskSelect.disabled = true;
      this.selectedBuilderGoalTaskKey = '';
      this.renderBuilderGoalConfigMeta();
      return;
    }

    this.builderGoalTaskSelect.disabled = false;
    const options = ['<option value="">Select a Micro6 task</option>'];
    const groups = new Map();
    tasks.forEach(task => {
      const category = (task?.category || 'other').toString();
      if (!groups.has(category)) groups.set(category, []);
      groups.get(category).push(task);
    });
    Array.from(groups.keys())
      .sort((a, b) => a.localeCompare(b))
      .forEach(category => {
        const optionHtml = groups
          .get(category)
          .slice()
          .sort((a, b) =>
            (a?.display_name || a?.task_key || '')
              .toString()
              .localeCompare((b?.display_name || b?.task_key || '').toString())
          )
          .map(task => {
            const selected =
              this.selectedBuilderGoalTaskKey &&
              this.selectedBuilderGoalTaskKey === task?.task_key
                ? ' selected'
                : '';
            return `<option value="${this.escapeAttr(task?.task_key || '')}"${selected}>${this.escapeHtml(this.buildCatalogTaskLabel(task))}</option>`;
          })
          .join('');
        options.push(`<optgroup label="${this.escapeAttr(category)}">${optionHtml}</optgroup>`);
      });
    this.builderGoalTaskSelect.innerHTML = options.join('');
    this.builderGoalTaskSelect.value = this.selectedBuilderGoalTaskKey || '';
    this.renderBuilderGoalConfigMeta();
  }

  getSelectedBuilderGoalTask() {
    const selectedKey = this.builderGoalTaskSelect
      ? this.builderGoalTaskSelect.value
      : this.selectedBuilderGoalTaskKey;
    return this.getCatalogTaskByKey(selectedKey);
  }

  buildBuilderGoalTaskPlaceholder(taskKey, options = {}) {
    const resolvedTaskKey = (taskKey || '').toString().trim();
    if (!resolvedTaskKey) return null;
    const displayName =
      options.displayName ||
      options.panelDisplayName ||
      resolvedTaskKey;
    return {
      client_type: 'micro6',
      execution_mode: 'queue',
      queueable: true,
      source: options.source || 'imported_payload',
      task_key: resolvedTaskKey,
      display_name: displayName,
      panel_display_name: options.panelDisplayName || displayName,
      category: options.category || 'saved_imported',
      runnable: options.runnable !== false,
      placeholder: true,
      supports_task_metadata: false,
      settings_unavailable_reason:
        options.settingsUnavailableReason || 'catalog_unavailable'
    };
  }

  ensureBuilderGoalTaskPlaceholder(taskKey, options = {}) {
    const existing = this.findCatalogTaskByKeyFlexible(taskKey);
    if (existing) {
      return existing;
    }
    const placeholder = this.buildBuilderGoalTaskPlaceholder(taskKey, options);
    if (!placeholder) {
      return null;
    }
    this.scriptCatalog = [placeholder, ...(Array.isArray(this.scriptCatalog) ? this.scriptCatalog : [])];
    this.renderBuilderGoalTaskOptions(this.builderGoalTaskSearchInput?.value || '');
    return placeholder;
  }

  selectBuilderGoalTask(taskKey) {
    const resolvedTask =
      this.findCatalogTaskByKeyFlexible(taskKey) ||
      this.ensureBuilderGoalTaskPlaceholder(taskKey);
    if (!resolvedTask?.task_key) {
      return null;
    }
    this.selectedBuilderGoalTaskKey = resolvedTask.task_key;
    localStorage.setItem('taskPlanner.selectedBuilderGoalTaskKey', this.selectedBuilderGoalTaskKey);
    if (this.builderGoalTaskSearchInput && this.builderGoalTaskSearchInput.value) {
      this.builderGoalTaskSearchInput.value = '';
      this.renderBuilderGoalTaskOptions('');
    }
    if (this.builderGoalTaskSelect) {
      this.builderGoalTaskSelect.value = resolvedTask.task_key;
    }
    return resolvedTask;
  }

  getBuilderGoalConfigContextKey(accountId = null, taskKey = null) {
    const resolvedAccountId = accountId || this.selectedBuilderGoalAccountId || '';
    const resolvedTaskKey = taskKey || this.selectedBuilderGoalTaskKey || '';
    return `${resolvedAccountId}::${resolvedTaskKey}`;
  }

  async prefillBuilderGoalConfigForSelection(options = {}) {
    const force = Boolean(options.force);
    const selectedTask = this.getSelectedBuilderGoalTask();
    const selectedAccount = this.getSelectedBuilderGoalAccount();
    if (!this.builderGoalConfigInput) {
      return;
    }

    if (!selectedTask || !selectedAccount) {
      if (!this.builderGoalConfigInput.value.trim() || force) {
        this.builderGoalConfigInput.value = '{}';
        this.builderGoalConfigDirty = false;
        this.builderGoalConfigLoadedKey = this.getBuilderGoalConfigContextKey();
      }
      this.renderBuilderGoalConfigMeta();
      return;
    }

    const nextKey = this.getBuilderGoalConfigContextKey(selectedAccount.accountId, selectedTask.task_key);
    if (!force && this.builderGoalConfigDirty) {
      this.renderBuilderGoalConfigMeta();
      return;
    }
    if (!force && this.builderGoalConfigLoadedKey === nextKey && this.builderGoalConfigInput.value.trim()) {
      this.renderBuilderGoalConfigMeta();
      return;
    }

    try {
      const query = new URLSearchParams({
        accountId: selectedAccount.accountId || '',
        taskKey: selectedTask.task_key || ''
      });
      const response = await fetch(`/api/debug/builder-goals/config/last?${query.toString()}`);
      if (response.ok) {
        const payload = await response.json();
        if (payload?.configAvailable && payload.settings && typeof payload.settings === 'object') {
          this.setBuilderGoalWorldValue(payload.world);
          this.setBuilderGoalConfigEditor(payload.settings, {
            accountId: payload.accountId,
            taskKey: payload.taskKey,
            displayName: payload.displayName,
            dirty: false
          });
          return;
        }
      }
    } catch (error) {
      console.debug('Could not load latest builder goal config:', error);
    }

    this.setBuilderGoalWorldValue(360);
    this.setBuilderGoalConfigEditor({}, {
      accountId: selectedAccount.accountId,
      taskKey: selectedTask.task_key,
      displayName: selectedTask.display_name || selectedTask.panel_display_name || selectedTask.task_key,
      dirty: false
    });
  }

  setBuilderGoalConfigEditor(settings, options = {}) {
    if (!this.builderGoalConfigInput) return;
    const configObject =
      settings && typeof settings === 'object' && !Array.isArray(settings) ? settings : {};
    this.builderGoalConfigInput.value = JSON.stringify(configObject, null, 2);
    this.builderGoalConfigDirty = Boolean(options.dirty);
    this.builderGoalConfigLoadedKey = this.getBuilderGoalConfigContextKey(
      options.accountId,
      options.taskKey
    );
    this.renderBuilderGoalConfigMeta({
      savedDisplayName: options.displayName || null,
      loadedFromRun: options.loadedFromRun || false,
      loadedRunLabel: options.loadedRunLabel || null
    });
  }

  renderBuilderGoalConfigMeta(extra = {}) {
    if (!this.builderGoalConfigMeta) return;
    const selectedTask = this.getSelectedBuilderGoalTask();
    const selectedAccount = this.getSelectedBuilderGoalAccount();
    const parts = [];
    if (extra.loadedFromRun && extra.loadedRunLabel) {
      parts.push(`Loaded saved config from ${extra.loadedRunLabel}.`);
    } else if (extra.savedDisplayName || selectedTask?.display_name || selectedTask?.task_key) {
      parts.push(
        `Editing config for ${extra.savedDisplayName || selectedTask?.display_name || selectedTask?.task_key}.`
      );
    } else {
      parts.push('Paste a raw settings object, a {"settings": {...}} envelope, or a full builder payload.');
    }
    if (selectedAccount?.displayName || selectedAccount?.accountName) {
      parts.push(`Account: ${selectedAccount.displayName || selectedAccount.accountName}.`);
    }
    const world = this.getBuilderGoalWorldValue();
    if (world) {
      parts.push(`World: ${world}.`);
    }
    if (this.builderGoalConfigDirty) {
      parts.push('Unsaved draft changes are being kept in this session.');
    } else {
      parts.push('The editor will prefill from the last saved run for this account and task when available.');
    }
    parts.push('If the JSON includes task_key, ChromaScape will auto-select that task.');
    this.builderGoalConfigMeta.innerHTML = `<span class="text-muted">${this.escapeHtml(parts.join(' '))}</span>`;
  }

  parseBuilderGoalConfigInput() {
    const raw = this.builderGoalConfigInput ? this.builderGoalConfigInput.value.trim() : '';
    if (!raw) {
      return { settings: {}, taskKey: null, requirements: {} };
    }

    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (error) {
      return { error: `Invalid JSON: ${error.message}` };
    }

    const normalized = this.normalizePastedConfig(parsed);
    if (normalized.error) {
      return normalized;
    }
    return normalized;
  }

  getBuilderGoalWorldValue() {
    if (!this.builderGoalWorldInput) {
      return 360;
    }
    const raw = String(this.builderGoalWorldInput.value || '').trim();
    if (!raw) {
      return 360;
    }
    const parsed = Number.parseInt(raw, 10);
    if (!Number.isFinite(parsed) || parsed < 300 || parsed > 700) {
      return null;
    }
    return parsed;
  }

  setBuilderGoalWorldValue(world) {
    if (!this.builderGoalWorldInput) return;
    const numericWorld = Number.parseInt(world, 10);
    this.builderGoalWorldInput.value = String(
      Number.isFinite(numericWorld) && numericWorld >= 300 && numericWorld <= 700
        ? numericWorld
        : 360
    );
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
      if (this.builderGoalLogsIterationId) {
        this.loadBuilderGoalRunLogs(this.builderGoalLogsIterationId, { silent: true });
      }
    } catch (error) {
      console.error('Failed to load builder goal runs:', error);
      this.builderGoalRunsList.innerHTML =
        '<div class="text-muted small">Failed to load builder goal runs.</div>';
      if (throwOnError) {
        throw error;
      }
    }
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

    const selectedAccount = this.getSelectedBuilderGoalAccount();
    const selectedMachine = this.getSelectedBuilderGoalMachine();
    const selectedTask = this.getSelectedBuilderGoalTask();
    const world = this.getBuilderGoalWorldValue();
    if (!selectedAccount) {
      this.builderGoalClientStatus.innerHTML =
        '<span class="text-muted">Select an account, choose a task, set the task settings and world, and then run the task.</span>';
      return;
    }

    if (!selectedAccount.runnable) {
      this.builderGoalClientStatus.innerHTML =
        `<span class="text-warning">${this.escapeHtml(selectedAccount.disabledReason || 'This account is visible but not launch-ready yet.')}</span>`;
      return;
    }

    if (!selectedMachine) {
      this.builderGoalClientStatus.innerHTML =
        '<span class="text-warning">No local launch host is available for Builder Goal Runner yet.</span>';
      return;
    }

    if (!selectedMachine.builderGoalRunnable) {
      this.builderGoalClientStatus.innerHTML =
        `<span class="text-warning">${this.escapeHtml(selectedMachine.builderGoalDisabledReason || 'The selected launcher machine is not ready for local Builder Goal launches.')}</span>`;
      return;
    }

    if (!world) {
      this.builderGoalClientStatus.innerHTML =
        '<span class="text-warning">Enter a valid world between 300 and 700 before running the task.</span>';
      return;
    }

    const degradedCatalogWarning =
      this.builderGoalCatalogDegraded && this.builderGoalCatalogReason
        ? `<div class="text-warning mb-1">${this.escapeHtml(this.builderGoalCatalogReason)}</div>`
        : '';

    const taskLabel = this.escapeHtml(
      selectedTask?.display_name || selectedTask?.task_key || 'the selected task'
    );
    const accountLabel = this.escapeHtml(
      selectedAccount.displayName || selectedAccount.accountName || selectedAccount.accountId
    );
    const machineLabel = this.escapeHtml(
      selectedMachine.displayName || selectedMachine.machineUuid || 'the selected machine'
    );
    this.builderGoalClientStatus.innerHTML =
      `${degradedCatalogWarning}<span class="text-info">ChromaScape will launch or attach to the local micro6 workspace for ${accountLabel} on ${machineLabel} in world ${world}, wait for that account's local executor to become dispatch-ready, and only then publish ${taskLabel} using the JSON settings from the editor.</span>`;
  }

  updateBuilderGoalRunButtonState() {
    if (!this.runBuilderGoalBtn) return;
    if (this.builderGoalBundleMismatch) {
      this.runBuilderGoalBtn.disabled = true;
      if (this.runImportedBuilderGoalBtn) {
        this.runImportedBuilderGoalBtn.disabled = true;
      }
      return;
    }
    const selectedAccount = this.getSelectedBuilderGoalAccount();
    const selectedMachine = this.getSelectedBuilderGoalMachine();
    const selectedTask = this.getSelectedBuilderGoalTask();
    const parsedConfig = this.parseBuilderGoalConfigInput();
    const importedTaskKey =
      !parsedConfig.error && parsedConfig.taskKey ? parsedConfig.taskKey : null;
    const world = this.getBuilderGoalWorldValue();
    const canPrimaryRun = Boolean(
      selectedAccount &&
        selectedAccount.runnable &&
        selectedMachine &&
        selectedMachine.builderGoalRunnable &&
        (selectedTask || importedTaskKey) &&
        world
    );
    this.runBuilderGoalBtn.disabled = !canPrimaryRun;
    if (this.runImportedBuilderGoalBtn) {
      this.runImportedBuilderGoalBtn.disabled = !Boolean(
        selectedAccount &&
          selectedAccount.runnable &&
          selectedMachine &&
          selectedMachine.builderGoalRunnable &&
          world &&
          this.importedBuilderGoal?.result?.runnableNow
      );
    }
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
      const canReview = Boolean(run.canAnalyze);
      const canApprove = Boolean(run.approvalAvailable);
      const canRerun = Boolean(run.canRerun);
      const canLoadConfig = Boolean(run.configAvailable);
      const showingLogs = this.builderGoalLogsIterationId === run.iterationId;
      const actionStepLine = this.renderBuilderGoalActionStep(run);
      const blockedReasonLine = this.renderBuilderGoalBlockedReason(run);
      const analysisUsageLine = this.renderBuilderGoalAnalysisUsage(run);
      const failureLine = run.failureReason
        ? `<div class="plan-card-intent text-danger">${this.escapeHtml(run.failureReason)}</div>`
        : '';
      const diagnosisLine = run.diagnosisSummary
        ? `<div class="plan-card-intent">${this.escapeHtml(run.diagnosisSummary)}</div>`
        : '';
      const recoveryParts = [run.recoveryPhase, run.recoveryMessage].filter(Boolean);
      const recoveryLine = recoveryParts.length
        ? `<div class="small text-muted mt-1"><i class="bi bi-tools me-1"></i>${this.escapeHtml(recoveryParts.join(' • '))}</div>`
        : '';
      const revisionLine = run.micro6RevisionId
        ? `<div class="small text-muted mt-1"><i class="bi bi-git me-1"></i>${this.escapeHtml(run.micro6RevisionId)}</div>`
        : '';
      const targetParts = [run.targetAccountName, run.targetBaseUrl].filter(Boolean);
      const targetLine = targetParts.length
        ? `<div class="small text-muted mt-1"><i class="bi bi-broadcast-pin me-1"></i>${this.escapeHtml(targetParts.join(' • '))}</div>`
        : '';
      const launchParts = [run.launcherMachineUuid, run.launcherCommandId, run.launcherCommandStatus].filter(Boolean);
      const launchLine = launchParts.length
        ? `<div class="small text-muted mt-1"><i class="bi bi-box-arrow-up-right me-1"></i>${this.escapeHtml(launchParts.join(' • '))}</div>`
        : '';
      const worldLine = run.world
        ? `<div class="small text-muted mt-1"><i class="bi bi-globe2 me-1"></i>${this.escapeHtml(`world ${run.world}`)}</div>`
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
          ${launchLine}
          ${worldLine}
          ${publishLine}
          ${failureLine}
          ${diagnosisLine}
          ${recoveryLine}
          ${revisionLine}
          ${analysisUsageLine}
          <div class="plan-card-meta">
            <span>${this.escapeHtml(progressText)}</span>
            <span>${run.createdAt ? this.escapeHtml(this.formatTimeAgo(run.createdAt)) : ''}</span>
          </div>
          ${actionStepLine}
          <div class="d-flex gap-2 mt-2">
            <button class="btn btn-sm btn-outline-secondary flex-fill" data-builder-run-action="load-config" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canLoadConfig ? '' : 'disabled'}>
              <i class="bi bi-download me-1"></i>Load Into Editor
            </button>
            <button class="btn btn-sm btn-outline-dark flex-fill" data-builder-run-action="rerun" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canRerun ? '' : 'disabled'}>
              <i class="bi bi-play-circle me-1"></i>Rerun
            </button>
          </div>
          <div class="d-flex gap-2 mt-2">
            <button class="btn btn-sm btn-outline-info flex-fill" data-builder-run-action="view-logs" data-iteration-id="${this.escapeAttr(run.iterationId || '')}">
              <i class="bi bi-journal-text me-1"></i>${showingLogs ? 'Refresh Logs' : 'View Logs'}
            </button>
          </div>
          <div class="d-flex gap-2 mt-2">
            <button class="btn btn-sm btn-outline-primary flex-fill" data-builder-run-action="analyze" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canReview ? '' : 'disabled'}>
              <i class="bi bi-lightbulb me-1"></i>${this.escapeHtml(this.builderGoalReviewLabel(run))}
            </button>
            <button class="btn btn-sm btn-outline-success flex-fill" data-builder-run-action="fix" data-iteration-id="${this.escapeAttr(run.iterationId || '')}" ${canApprove ? '' : 'disabled'}>
              <i class="bi bi-arrow-repeat me-1"></i>${this.escapeHtml(this.builderGoalApprovalLabel(run))}
            </button>
          </div>
          ${blockedReasonLine}
        </div>
      `;
    }).join('');
  }

  renderBuilderGoalActionStep(run) {
    const runStatus = (run.status || '').toUpperCase();
    if (status !== 'FAILED') {
      return '';
    }
    if (run.recoveryPhase === 'AWAITING_OPERATOR_PROMPT') {
      return '<div class="small text-muted mt-2">1. Open the recovery workspace, describe the issue, and choose the evidence/time clip to send for patch planning.</div>';
    }
    if (run.approvalAvailable) {
      return '<div class="small text-muted mt-2">2. Review the generated patch plan and apply it to restart and republish the task.</div>';
    }
    if (run.analysisAvailable && run.patchDraftAvailable) {
      return '<div class="small text-muted mt-2">Patch plan is ready; waiting for apply/relaunch.</div>';
    }
    if (run.analysisAvailable) {
      return '<div class="small text-muted mt-2">Patch planning completed, but MCP did not produce a patch draft.</div>';
    }
    return '<div class="small text-muted mt-2">Waiting for an operator-authored patch request.</div>';
  }

  renderBuilderGoalBlockedReason(run) {
    const reasons = [];
    if (!run.canRerun && run.rerunBlockedReason) {
      reasons.push(run.rerunBlockedReason);
    }
    if (!run.canAnalyze && run.analyzeBlockedReason) {
      reasons.push(run.analyzeBlockedReason);
    }
    if (!run.approvalAvailable && run.approvalBlockedReason && !reasons.includes(run.approvalBlockedReason)) {
      reasons.push(run.approvalBlockedReason);
    }
    if (reasons.length === 0) {
      return '';
    }
    return `<div class="small text-muted mt-2">${this.escapeHtml(reasons.join(' • '))}</div>`;
  }

  renderBuilderGoalAnalysisUsage(run) {
    const parts = [];
    if (run.analysisMode === 'OPERATOR_EDITED_MCP') {
      parts.push('Patch plan generated from operator-authored recovery request');
    }
    if (run.analysisCustomized) {
      parts.push('Used custom operator prompt/context');
    }
    if (Number.isFinite(run.analysisSelectedEvidenceCount) && Number.isFinite(run.analysisTotalEvidenceCount)) {
      parts.push(`Selected evidence: ${run.analysisSelectedEvidenceCount}/${run.analysisTotalEvidenceCount}`);
    }
    if (Number.isFinite(run.analysisTimeRangeStartSeconds) && Number.isFinite(run.analysisTimeRangeEndSeconds)) {
      parts.push(`Clip: ${this.formatSecondsAsClock(run.analysisTimeRangeStartSeconds)} → ${this.formatSecondsAsClock(run.analysisTimeRangeEndSeconds)}`);
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
      let resolvedTask = this.selectBuilderGoalTask(result.taskKey);
      if (!resolvedTask && result.taskKey) {
        resolvedTask = this.ensureBuilderGoalTaskPlaceholder(result.taskKey, {
          displayName: result.displayName || result.title || result.taskKey,
          source: 'imported_payload',
          runnable: Boolean(result.runnableNow)
        });
        if (resolvedTask?.task_key) {
          this.selectBuilderGoalTask(resolvedTask.task_key);
        }
      }
      this.setBuilderGoalConfigEditor(result.settings || {}, {
        accountId: this.selectedBuilderGoalAccountId,
        taskKey: resolvedTask?.task_key || result.taskKey || '',
        displayName: result.displayName || result.title || result.taskKey || null,
        dirty: true
      });
      this.renderBuilderGoalImportResult(result);
      this.renderBuilderGoalClientStatus(
        `Imported config for ${result.displayName || result.taskKey || 'the selected task'}. Review the JSON editor and click Run Task when ready.`,
        'info'
      );
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

    const warning = result.catalogWarning
      ? `<div class="text-warning">${this.escapeHtml(result.catalogWarning)}</div>`
      : '';

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
      ${warning}
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
    const selectedAccount = this.getSelectedBuilderGoalAccount();
    const selectedMachine = this.getSelectedBuilderGoalMachine();
    if (!selectedAccount) {
      alert('Select an account first.');
      return;
    }
    if (!selectedAccount.runnable) {
      alert(selectedAccount.disabledReason || 'This account cannot be launched yet.');
      return;
    }
    if (!selectedMachine) {
      alert('Select a launcher machine first.');
      return;
    }
    if (!selectedMachine.builderGoalRunnable) {
      alert(
        selectedMachine.builderGoalDisabledReason ||
          'This launcher machine cannot run local Builder Goal launches yet.'
      );
      return;
    }

    const parsedConfig = this.parseBuilderGoalConfigInput();
    if (parsedConfig.error) {
      this.renderBuilderGoalClientStatus(parsedConfig.error, 'danger');
      alert(parsedConfig.error);
      return;
    }

    let selectedTask = this.getSelectedBuilderGoalTask();
    if (parsedConfig.taskKey) {
      selectedTask =
        this.selectBuilderGoalTask(parsedConfig.taskKey) ||
        this.ensureBuilderGoalTaskPlaceholder(parsedConfig.taskKey, {
          displayName:
            this.importedBuilderGoal?.result?.displayName ||
            this.importedBuilderGoal?.result?.title ||
            parsedConfig.taskKey,
          source: 'imported_payload',
          runnable: true
        }) ||
        selectedTask;
    }

    const world = this.getBuilderGoalWorldValue();
    if (!selectedTask) {
      const message = 'Select a Micro6 task first, or paste JSON that includes task_key.';
      this.renderBuilderGoalClientStatus(message, 'danger');
      alert(message);
      return;
    }
    if (!world) {
      const message = 'Enter a valid world between 300 and 700.';
      this.renderBuilderGoalClientStatus(message, 'danger');
      alert(message);
      return;
    }

    if (this.runBuilderGoalBtn) {
      this.runBuilderGoalBtn.disabled = true;
      this.runBuilderGoalBtn.innerHTML = '<i class="bi bi-hourglass me-1"></i>Launching...';
    }
    this.renderBuilderGoalClientStatus(
      `Launching the local micro6 workspace for ${selectedAccount.displayName || selectedAccount.accountName || selectedAccount.accountId} on ${selectedMachine.displayName || selectedMachine.machineUuid} in world ${world} and then publishing ${selectedTask.display_name || selectedTask.task_key}...`,
      'info'
    );
    console.info('[AccountTaskPlanner] Run Task clicked', {
      accountId: selectedAccount.accountId || null,
      launcherMachineUuid: selectedMachine.machineUuid || null,
      taskKey: selectedTask.task_key || null,
      world
    });
    const controller = new AbortController();
    const timeoutHandle = setTimeout(
      () => controller.abort(),
      AccountTaskPlanner.BUILDER_GOAL_PUBLISH_TIMEOUT_MS
    );
    let publishSucceeded = false;
    let publishWarning = null;

    try {
      const settings = parsedConfig.settings && typeof parsedConfig.settings === 'object'
        ? parsedConfig.settings
        : {};
      const response = await fetch('/api/debug/builder-goals/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          accountId: selectedAccount.accountId,
          launcherMachineUuid: selectedMachine.machineUuid,
          world,
          taskKey: selectedTask.task_key,
          settings,
          displayName:
            selectedTask.display_name ||
            selectedTask.panel_display_name ||
            selectedTask.task_key,
          source: 'catalog_task'
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
      this.builderGoalConfigDirty = false;
      this.builderGoalConfigLoadedKey = this.getBuilderGoalConfigContextKey(
        selectedAccount.accountId,
        selectedTask.task_key
      );
      this.renderBuilderGoalConfigMeta();
      this.renderBuilderGoalClientStatus(
        `Launch accepted for ${selectedAccount.displayName || selectedAccount.accountName || selectedAccount.accountId} in world ${world}. ChromaScape is now waiting for that account's local micro6 executor to become dispatch-ready and will publish ${selectedTask.display_name || selectedTask.task_key} automatically when it is ready.`,
        'success'
      );
    } catch (error) {
      console.error('Failed to publish builder goal task:', error);
      const timedOut = error && error.name === 'AbortError';
      const message = timedOut
        ? 'Publish timed out before Micro6 acknowledged the task.'
        : error.message;
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus(
        `Failed to run task: ${message}`,
        'danger'
      );
      alert('Failed to run task: ' + message);
    } finally {
      clearTimeout(timeoutHandle);
      if (publishSucceeded) {
        try {
          await this.loadBuilderGoalRuns({ throwOnError: true });
        } catch (error) {
          console.warn('Builder-goal launch accepted, but failed to refresh builder goal runs:', error);
          publishWarning = error?.message || 'Failed to refresh builder goal runs.';
        }
        if (publishWarning) {
          this.renderBuilderGoalClientStatus(
            `Launch was accepted, but failed to refresh the run list: ${publishWarning}`,
            'warning'
          );
        }
      }
      if (this.runBuilderGoalBtn) {
        this.runBuilderGoalBtn.innerHTML = '<i class="bi bi-play-fill me-1"></i>Run Task';
      }
      this.updateBuilderGoalRunButtonState();
    }
  }

  async runImportedBuilderGoal() {
    if (this.builderGoalBundleMismatch) {
      this.renderBuilderGoalClientStatus(
        'Hard refresh this page to load the latest Builder Goal Runner before running an imported payload.',
        'danger'
      );
      return;
    }
    const selectedAccount = this.getSelectedBuilderGoalAccount();
    const selectedMachine = this.getSelectedBuilderGoalMachine();
    const world = this.getBuilderGoalWorldValue();
    if (!selectedAccount) {
      alert('Select an account first.');
      return;
    }
    if (!selectedAccount.runnable) {
      alert(selectedAccount.disabledReason || 'This account cannot be launched yet.');
      return;
    }
    if (!selectedMachine) {
      alert('Select a launcher machine first.');
      return;
    }
    if (!selectedMachine.builderGoalRunnable) {
      alert(
        selectedMachine.builderGoalDisabledReason ||
          'This launcher machine cannot run local Builder Goal launches yet.'
      );
      return;
    }
    if (!this.importedBuilderGoal?.payload) {
      alert('Import a builder payload first.');
      return;
    }
    if (!world) {
      const message = 'Enter a valid world between 300 and 700.';
      this.renderBuilderGoalClientStatus(message, 'danger');
      alert(message);
      return;
    }

    if (this.runImportedBuilderGoalBtn) {
      this.runImportedBuilderGoalBtn.disabled = true;
      this.runImportedBuilderGoalBtn.innerHTML = '<i class="bi bi-hourglass me-1"></i>Running...';
    }
    this.renderBuilderGoalClientStatus(
      `Launching the local micro6 workspace for ${selectedAccount.displayName || selectedAccount.accountName || selectedAccount.accountId} on ${selectedMachine.displayName || selectedMachine.machineUuid} in world ${world} and then publishing the imported payload...`,
      'info'
    );
    console.info('[AccountTaskPlanner] Run Imported Payload clicked', {
      accountId: selectedAccount.accountId || null,
      launcherMachineUuid: selectedMachine.machineUuid || null,
      taskKey: this.importedBuilderGoal?.result?.taskKey || null,
      world
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
          accountId: selectedAccount.accountId,
          launcherMachineUuid: selectedMachine.machineUuid,
          world,
          builderPayload: this.importedBuilderGoal.payload
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
        `Launch accepted for ${selectedAccount.displayName || selectedAccount.accountName || selectedAccount.accountId} in world ${world}. ChromaScape is now waiting for that account's local micro6 executor to become dispatch-ready and will publish the imported payload automatically when it is ready.`,
        'success'
      );
    } catch (error) {
      console.error('Failed to run imported builder goal:', error);
      const timedOut = error && error.name === 'AbortError';
      const message = timedOut
        ? 'Publish timed out before Micro6 acknowledged the imported task.'
        : error.message;
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus(`Failed to run imported payload: ${message}`, 'danger');
      alert('Failed to run imported payload: ' + message);
    } finally {
      clearTimeout(timeoutHandle);
      if (publishSucceeded) {
        try {
          await this.loadBuilderGoalRuns({ throwOnError: true });
        } catch (error) {
          console.warn('Imported builder-goal launch accepted, but failed to refresh builder goal runs:', error);
          publishWarning = error?.message || 'Failed to refresh builder goal runs.';
        }
        if (publishWarning) {
          this.renderBuilderGoalClientStatus(
            `Launch was accepted, but failed to refresh the run list: ${publishWarning}`,
            'warning'
          );
        }
      }
      if (this.runImportedBuilderGoalBtn) {
        this.runImportedBuilderGoalBtn.innerHTML =
          '<i class="bi bi-upload me-1"></i>Run Imported Payload';
      }
      this.updateBuilderGoalRunButtonState();
    }
  }

  async loadBuilderGoalRunConfig(iterationId) {
    if (!iterationId) return;
    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/config`);
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }

      const account = (this.builderGoalAccounts || []).find(
        candidate => candidate.accountId === result.accountId
      );
      if (!account) {
        throw new Error('The saved run account is no longer available in launcher control.');
      }
      const task =
        this.selectBuilderGoalTask(result.taskKey) ||
        this.ensureBuilderGoalTaskPlaceholder(result.taskKey, {
          displayName: result.displayName || result.taskKey,
          source: 'saved_config',
          runnable: true
        });
      if (!task) {
        throw new Error(`Saved task is no longer available: ${result.taskKey}`);
      }
      if (result.launcherMachineUuid) {
        const machine = (this.builderGoalMachines || []).find(
          candidate => candidate.machineUuid === result.launcherMachineUuid
        );
        if (!machine) {
          throw new Error('The saved run machine is no longer available in launcher control.');
        }
        this.selectedBuilderGoalMachineUuid = result.launcherMachineUuid;
        localStorage.setItem(
          'taskPlanner.selectedBuilderGoalMachineUuid',
          this.selectedBuilderGoalMachineUuid
        );
        if (this.builderGoalMachineSelect) {
          this.builderGoalMachineSelect.value = this.selectedBuilderGoalMachineUuid;
        }
      }
      if (this.builderGoalWorldInput) {
        this.builderGoalWorldInput.value = String(result.world || 360);
      }

      this.selectedBuilderGoalAccountId = result.accountId || '';
      localStorage.setItem('taskPlanner.selectedBuilderGoalAccountId', this.selectedBuilderGoalAccountId);
      if (this.builderGoalAccountSelect) {
        this.builderGoalAccountSelect.value = this.selectedBuilderGoalAccountId;
      }

      this.setBuilderGoalConfigEditor(result.settings || {}, {
        accountId: result.accountId,
        taskKey: result.taskKey,
        displayName: result.displayName,
        loadedFromRun: true,
        loadedRunLabel:
          result.createdAt
            ? `${result.displayName || result.taskKey} (${this.formatTimeAgo(result.createdAt)})`
            : (result.displayName || result.taskKey || 'saved run')
      });
      this.renderBuilderGoalAccountSummary();
      this.renderBuilderGoalMachineSummary();
      this.renderBuilderGoalClientStatus(
        `Loaded the saved config for ${result.displayName || result.taskKey} on ${account.displayName || account.accountName || account.accountId} into the editor with world ${result.world || 360}. Review the JSON and click Run Task when ready.`,
        'info'
      );
      this.updateBuilderGoalRunButtonState();
    } catch (error) {
      console.error('Failed to load builder goal run config:', error);
      this.renderBuilderGoalClientStatus(`Failed to load saved config: ${error.message}`, 'danger');
      alert('Failed to load saved config: ' + error.message);
    }
  }

  async rerunBuilderGoal(iterationId) {
    if (!iterationId) return;
    this.renderBuilderGoalClientStatus(
      'Reusing the saved builder-goal config, launching the local micro6 workspace, and republishing the task...',
      'info'
    );
    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/rerun`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }
      await this.loadBuilderGoalRuns();
      const run = result.run || {};
      this.renderBuilderGoalClientStatus(
        `Rerun accepted for ${run.displayName || run.taskKey || 'the selected task'} on ${run.accountName || run.targetAccountName || 'the selected account'}. ChromaScape is waiting for that account's local micro6 executor to become dispatch-ready and will republish automatically when it is ready.`,
        'success'
      );
    } catch (error) {
      console.error('Failed to rerun builder goal:', error);
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus(`Failed to rerun task: ${error.message}`, 'danger');
      alert('Failed to rerun task: ' + error.message);
    }
  }

  closeBuilderGoalRunLogs() {
    this.builderGoalLogsIterationId = null;
    if (this.builderGoalLogsPanel) {
      this.builderGoalLogsPanel.classList.add('d-none');
    }
    if (this.builderGoalLogsSummary) {
      this.builderGoalLogsSummary.textContent = 'Select a run to inspect logs.';
    }
    if (this.builderGoalLogsStatus) {
      this.builderGoalLogsStatus.innerHTML = '<span class="text-muted">No run selected.</span>';
    }
    if (this.builderGoalLaunchLogTail) {
      this.builderGoalLaunchLogTail.textContent = 'Select a run to inspect launch logs.';
    }
    if (this.builderGoalHistoricalLogs) {
      this.builderGoalHistoricalLogs.innerHTML =
        '<div class="text-muted small fst-italic">No run selected.</div>';
    }
    this.renderBuilderGoalRuns();
  }

  async loadBuilderGoalRunLogs(iterationId, options = {}) {
    if (!iterationId) return;
    const silent = Boolean(options.silent);
    const run = Array.isArray(this.builderGoalRuns)
      ? this.builderGoalRuns.find(item => item.iterationId === iterationId)
      : null;
    this.builderGoalLogsIterationId = iterationId;
    if (this.builderGoalLogsPanel) {
      this.builderGoalLogsPanel.classList.remove('d-none');
    }
    if (this.builderGoalLogsSummary) {
      this.builderGoalLogsSummary.textContent = run
        ? `${run.displayName || run.taskKey || 'Builder Goal'} / ${run.accountName || run.targetAccountName || 'unknown account'}`
        : iterationId;
    }
    if (this.builderGoalLogsStatus && !silent) {
      this.builderGoalLogsStatus.innerHTML =
        '<span class="text-info">Loading run logs and launch output...</span>';
    }
    try {
      const response = await fetch(
        `/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/logs`
      );
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(payload?.error || `HTTP ${response.status}`);
      }
      this.renderBuilderGoalRunLogs(payload);
      this.renderBuilderGoalRuns();
    } catch (error) {
      console.error('Failed to load builder goal run logs:', error);
      if (this.builderGoalLogsStatus) {
        this.builderGoalLogsStatus.innerHTML = `<span class="text-danger">${this.escapeHtml(`Failed to load run logs: ${error.message}`)}</span>`;
      }
    }
  }

  renderBuilderGoalRunLogs(payload) {
    const run = payload?.run || {};
    const filter = payload?.filter || {};
    const launchLog = payload?.launchLog || {};
    const historicalLogs = Array.isArray(payload?.historicalLogs) ? payload.historicalLogs : [];

    if (this.builderGoalLogsPanel) {
      this.builderGoalLogsPanel.classList.remove('d-none');
    }
    if (this.builderGoalLogsSummary) {
      this.builderGoalLogsSummary.textContent = `${run.displayName || run.taskKey || 'Builder Goal'} / ${run.accountName || run.targetAccountName || 'unknown account'}`;
    }
    if (this.builderGoalLogsStatus) {
      const parts = [];
      if (run.status) parts.push(`status ${run.status}`);
      if (run.targetPreparationState) parts.push(`prep ${run.targetPreparationState}`);
      if (run.publishStatus) parts.push(`publish ${run.publishStatus}`);
      if (filter.matchMode) {
        parts.push(
          filter.matchMode === 'run_filtered'
            ? `${payload.matchedLogCount || 0} matched logs`
            : `${payload.windowLogCount || 0} window logs`
        );
      }
      if (run.failureReason) parts.push(run.failureReason);
      this.builderGoalLogsStatus.innerHTML = `<span class="text-muted">${this.escapeHtml(parts.join(' • '))}</span>`;
    }
    if (this.builderGoalLaunchLogTail) {
      if (launchLog?.available) {
        this.builderGoalLaunchLogTail.textContent = Array.isArray(launchLog.lines) && launchLog.lines.length
          ? launchLog.lines.join('\n')
          : '(launch log file is currently empty)';
      } else {
        this.builderGoalLaunchLogTail.textContent = launchLog?.message || 'Launch log is unavailable.';
      }
    }
    if (this.builderGoalHistoricalLogs) {
      if (!historicalLogs.length) {
        this.builderGoalHistoricalLogs.innerHTML =
          '<div class="text-muted small fst-italic">No persisted ChromaScape logs matched this run yet.</div>';
        return;
      }
      this.builderGoalHistoricalLogs.innerHTML = historicalLogs
        .map(log => {
          const meta = [log.timestamp ? this.formatAbsoluteTime(log.timestamp) : null, log.level, log.source]
            .filter(Boolean)
            .join(' • ');
          const structured = log.structuredData
            ? `<div class="small text-muted font-monospace mt-1">${this.escapeHtml(log.structuredData)}</div>`
            : '';
          return `
            <div class="border-bottom pb-2 mb-2">
              <div class="small text-muted">${this.escapeHtml(meta)}</div>
              <div>${this.escapeHtml(log.message || '')}</div>
              ${structured}
            </div>
          `;
        })
        .join('');
    }
  }

  describeBuilderGoalRunProgress(run) {
    const runStatus = (run.status || '').toUpperCase();
    const preparationState = (run.targetPreparationState || '').toUpperCase();
    const launcherOnline = Boolean(run.launcherOnline);
    const localExecutorReady = Boolean(run.localExecutorReady);
    const localExecutorObserved = Boolean(run.localExecutorObserved);
    if (runStatus === 'FAILED' && preparationState === 'PROCESS_EXITED') {
      return run.launchFailureDetail || run.failureReason || run.targetPreparationMessage || 'Local Micro6 relaunch process exited';
    }
    if (preparationState === 'PREPARING_TARGET') {
      return 'Preparing Micro6 target';
    }
    if (preparationState === 'LAUNCHING_PROCESS') {
      return run.targetPreparationMessage || 'Launching local micro6 workspace';
    }
    if (preparationState === 'WAITING_FOR_PORT') {
      if (launcherOnline) {
        return 'Launcher account is online; waiting for the local micro6 executor port';
      }
      return run.targetPreparationMessage || 'Waiting for local executor port';
    }
    if (preparationState === 'WAITING_FOR_HEALTH') {
      if (localExecutorObserved) {
        return run.targetPreparationMessage || 'Local executor is present; waiting for healthy /health';
      }
      return run.targetPreparationMessage || 'Waiting for local executor health';
    }
    if (preparationState === 'WAITING_FOR_ACCOUNT') {
      return run.targetPreparationMessage || 'Waiting for selected account to appear on executor health';
    }
    if (preparationState === 'WAITING_FOR_DISPATCH') {
      return run.targetPreparationMessage || 'Waiting for executor dispatch readiness';
    }
    if (preparationState === 'WAITING_FOR_AGENT') {
      return 'Waiting for Microbot Agent Server';
    }
    if (preparationState === 'LOGGING_IN') {
      return 'Logging into Micro6 profile';
    }
    if (preparationState === 'READY_TO_PUBLISH' && (run.status || '').toUpperCase() === 'READY_TO_PUBLISH') {
      return run.targetPreparationMessage || 'Target is ready to publish';
    }
    if (localExecutorReady && !run.publishEntryId && (run.status || '').toUpperCase() === 'QUEUED') {
      return 'Local executor is ready; waiting for automatic publish';
    }
    const recoveryPhase = (run.recoveryPhase || '').toUpperCase();
    if (recoveryPhase === 'AWAITING_OPERATOR_PROMPT') {
      return 'Awaiting operator patch request';
    }
    if (recoveryPhase === 'PATCH_PLAN_READY') {
      return 'Patch plan is ready for application';
    }
    if (recoveryPhase === 'APPLYING_PATCH') {
      return 'Applying approved patch to Micro6 source';
    }
    if (recoveryPhase === 'RESTARTING_TARGET') {
      return 'Restarting Micro6 target';
    }
    if (recoveryPhase === 'RELOGGING_IN') {
      return 'Logging back into Micro6 after restart';
    }
    if (recoveryPhase === 'WAITING_FOR_EXECUTOR') {
      return 'Waiting for Micro6 executor heartbeat to return';
    }
    if (recoveryPhase === 'REPUBLISHING') {
      return 'Republishing patched task';
    }
    if (recoveryPhase === 'RELAUNCHED') {
      return 'Recovery approved and task relaunched';
    }
    if (recoveryPhase === 'RECOVERY_FAILED') {
      return 'Recovery failed';
    }
    if (runStatus === 'PUBLISHED') {
      return 'Published to Micro6 queue';
    }
    if (runStatus === 'WAITING_FOR_TASK_START') {
      return 'Published to Micro6 queue and waiting for task start';
    }
    if (runStatus === 'WAITING_FOR_DEBUG_BIND') {
      return 'Task started; waiting for ChromaScape debug binding';
    }
    if (runStatus === 'RUNNING') {
      return 'Running from Micro6 queue';
    }
    if (runStatus === 'COMPLETED') {
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
    if (this.builderGoalAnalysisStartTime) {
      this.builderGoalAnalysisStartTime.value = '';
    }
    if (this.builderGoalAnalysisEndTime) {
      this.builderGoalAnalysisEndTime.value = '';
    }
    if (this.builderGoalAnalysisRangeHint) {
      this.builderGoalAnalysisRangeHint.textContent = '00:00 → 00:00';
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
      if (this.builderGoalAnalysisStartTime) {
        this.builderGoalAnalysisStartTime.value = result.timeRangeStartDisplay || '00:00';
      }
      if (this.builderGoalAnalysisEndTime) {
        this.builderGoalAnalysisEndTime.value = result.timeRangeEndDisplay || result.timeRangeMaxDisplay || '00:00';
      }
      if (this.builderGoalAnalysisRangeHint) {
        this.builderGoalAnalysisRangeHint.textContent =
          `00:00 → ${result.timeRangeMaxDisplay || '00:00'}`;
      }
      this.renderBuilderGoalAnalysisEvidence(result);
      if (result.canAnalyze === false && result.analysisBlockedReason) {
        this.renderBuilderGoalAnalysisStatus(result.analysisBlockedReason, 'warning');
      } else {
        this.renderBuilderGoalAnalysisStatus('Describe the issue, choose the exact clip/evidence, then generate a patch plan for Micro6.', 'muted');
      }
      this.setBuilderGoalAnalysisSubmitState(Boolean(result.canAnalyze) === false, 'Generate Patch Plan');
      this.builderGoalAnalysisModal?.show();
    } catch (error) {
      console.error('Failed to analyze builder goal run:', error);
      this.renderBuilderGoalAnalysisStatus(`Failed to load analysis draft: ${error.message}`, 'danger');
      this.setBuilderGoalAnalysisSubmitState(true, 'Generate Patch Plan');
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
    if (this.builderGoalAnalysisStartTime) {
      this.builderGoalAnalysisStartTime.value = this.builderGoalAnalysisDraft.timeRangeStartDisplay || '00:00';
    }
    if (this.builderGoalAnalysisEndTime) {
      this.builderGoalAnalysisEndTime.value =
        this.builderGoalAnalysisDraft.timeRangeEndDisplay || this.builderGoalAnalysisDraft.timeRangeMaxDisplay || '00:00';
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

  setBuilderGoalAnalysisSubmitState(disabled, label = 'Generate Patch Plan') {
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

    const timeRangeStartSeconds = this.parseClockToSeconds(this.builderGoalAnalysisStartTime?.value);
    const timeRangeEndSeconds = this.parseClockToSeconds(this.builderGoalAnalysisEndTime?.value);
    if (timeRangeStartSeconds == null || timeRangeEndSeconds == null) {
      this.renderBuilderGoalAnalysisStatus('Enter a valid clip range like 00:13 to 00:20.', 'warning');
      return;
    }
    if (timeRangeEndSeconds <= timeRangeStartSeconds) {
      this.renderBuilderGoalAnalysisStatus('Clip end must be after clip start.', 'warning');
      return;
    }

    this.setBuilderGoalAnalysisSubmitState(true, 'Generating...');
    this.renderBuilderGoalAnalysisStatus('Generating a Micro6 patch plan from your prompt, clip, and selected evidence...', 'info');

    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(this.builderGoalAnalysisIterationId)}/analyze`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt: this.builderGoalAnalysisPrompt ? this.builderGoalAnalysisPrompt.value : '',
          context: this.builderGoalAnalysisContext ? this.builderGoalAnalysisContext.value : '',
          selectedEvidenceIds,
          timeRangeStartSeconds,
          timeRangeEndSeconds
        })
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }

      await this.loadBuilderGoalRuns();
      this.builderGoalAnalysisModal?.hide();
      if (result.patchId) {
        this.renderBuilderGoalClientStatus('Patch plan updated. Apply Plan + Relaunch is now available.', 'success');
      } else {
        this.renderBuilderGoalClientStatus('Patch planning completed, but MCP did not produce a patch draft.', 'warning');
      }
    } catch (error) {
      console.error('Failed to analyze builder goal run:', error);
      this.renderBuilderGoalAnalysisStatus(`Failed to generate a patch plan: ${error.message}`, 'danger');
      alert('Failed to generate a patch plan: ' + error.message);
    } finally {
      this.setBuilderGoalAnalysisSubmitState(false, 'Generate Patch Plan');
    }
  }

  async fixAndRelaunchBuilderGoalRun(iterationId) {
    try {
      const response = await fetch(`/api/debug/builder-goals/runs/${encodeURIComponent(iterationId)}/approve-recovery`, {
        method: 'POST'
      });
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error || `HTTP ${response.status}`);
      }
      await this.loadBuilderGoalRuns();
      this.renderBuilderGoalClientStatus('Patch plan approved. ChromaScape is applying the patch, restarting Micro6, logging back in, and republishing the task.', 'success');
    } catch (error) {
      console.error('Failed to fix and relaunch builder goal run:', error);
      alert('Failed to approve builder goal recovery: ' + error.message);
    }
  }

  builderGoalReviewLabel(run) {
    return 'Open Recovery Workspace';
  }

  builderGoalApprovalLabel(run) {
    if (run.approvalAvailable) {
      return 'Apply Plan + Relaunch';
    }
    if ((run.recoveryPhase || '') === 'RECOVERY_RELAUNCHED') {
      return 'Relaunched';
    }
    return 'Awaiting Patch Plan';
  }

  parseClockToSeconds(value) {
    const raw = (value || '').trim();
    if (!raw) {
      return 0;
    }
    const parts = raw.split(':').map(part => part.trim());
    if (parts.length !== 2) {
      return null;
    }
    const minutes = Number(parts[0]);
    const seconds = Number(parts[1]);
    if (!Number.isFinite(minutes) || !Number.isFinite(seconds) || minutes < 0 || seconds < 0 || seconds >= 60) {
      return null;
    }
    return (minutes * 60) + seconds;
  }

  formatSecondsAsClock(totalSeconds) {
    const safeSeconds = Math.max(0, Number(totalSeconds) || 0);
    const minutes = Math.floor(safeSeconds / 60);
    const seconds = safeSeconds % 60;
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
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

  formatAbsoluteTime(dateStr) {
    try {
      const date = new Date(dateStr);
      return Number.isNaN(date.getTime()) ? '' : date.toLocaleString();
    } catch (e) {
      return '';
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.accountTaskPlanner = new AccountTaskPlanner();
  window.accountTaskPlanner.init();
});
