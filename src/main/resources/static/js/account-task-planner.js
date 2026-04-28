/**
 * Account Task Planner - Manages task plan generation, review, and execution.
 *
 * Provides UI for generating task plans from evidence, reviewing items,
 * approving/rejecting tasks, and triggering patch loop execution.
 */
class AccountTaskPlanner {
  constructor() {
    this.plans = [];
    this.selectedPlanId = null;
    this.selectedPlan = null;
    this.panelVisible = false;
    this.scriptCatalog = [];
  }

  /**
   * Initializes the task planner UI and event handlers.
   */
  init() {
    this.bindElements();
    this.bindEvents();
    this.loadPlans();
    this.loadSourceSessions();
    this.loadScriptCatalog();
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

    // Plans list
    this.plansList = document.getElementById('plansList');
    this.planCountBadge = document.getElementById('planCountBadge');

    // Plan detail
    this.planDetailSection = document.getElementById('planDetailSection');
    this.planDetailTitle = document.getElementById('planDetailTitle');
    this.planDetailStatus = document.getElementById('planDetailStatus');
    this.planDetailIntent = document.getElementById('planDetailIntent');
    this.planItemsList = document.getElementById('planItemsList');
    this.approvePlanBtn = document.getElementById('approvePlanBtn');
    this.rejectPlanBtn = document.getElementById('rejectPlanBtn');
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
      this.refreshBtn.addEventListener('click', () => this.loadPlans());
    }
    if (this.generateBtn) {
      this.generateBtn.addEventListener('click', () => this.generatePlan());
    }
    if (this.approvePlanBtn) {
      this.approvePlanBtn.addEventListener('click', () => this.approvePlan());
    }
    if (this.rejectPlanBtn) {
      this.rejectPlanBtn.addEventListener('click', () => this.rejectPlan());
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
      const response = await fetch('/api/account-tasks');
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.plans = await response.json();
      this.renderPlansList();

      // If a plan was selected, refresh its detail
      if (this.selectedPlanId) {
        await this.loadPlanDetail(this.selectedPlanId);
      }
    } catch (error) {
      console.error('Failed to load plans:', error);
      if (this.plansList) {
        this.plansList.innerHTML =
          '<div class="text-muted small">Failed to load plans. Check that the service is running.</div>';
      }
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
      // Keep the "Current session" default option
      this.sourceSessionSelect.innerHTML = '<option value="">Current session</option>';

      sessions.forEach(session => {
        const option = document.createElement('option');
        option.value = session.recordingId || session.id || '';
        let label = session.displayName || session.scriptName || session.recordingId || session.id || 'Unknown';
        // Disambiguate duplicate custom replay names with a time suffix
        if (session.replayName && session.startTime) {
          try {
            const d = new Date(session.startTime);
            const timeStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
            label = `${label} \u00B7 ${timeStr}`;
          } catch (_) { /* ignore parse errors */ }
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

    // Bind click handlers to plan cards
    this.plansList.querySelectorAll('.plan-card').forEach(card => {
      card.addEventListener('click', () => {
        const planId = card.dataset.planId;
        this.selectPlan(planId);
      });
    });
  }

  /**
   * Selects a plan and loads its detail.
   */
  async selectPlan(planId) {
    this.selectedPlanId = planId;
    this.renderPlansList(); // Update active state
    await this.loadPlanDetail(planId);
  }

  /**
   * Loads and renders plan detail with items and citations.
   */
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

  /**
   * Renders the plan detail section.
   */
  renderPlanDetail() {
    if (!this.selectedPlan || !this.planDetailSection) return;

    const plan = this.selectedPlan;

    // Show the detail section
    this.planDetailSection.style.display = 'block';

    // Update header
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

    // Update action buttons visibility
    const canApprove = ['GENERATED', 'UNDER_REVIEW'].includes(plan.status);
    const canReject = ['GENERATED', 'UNDER_REVIEW', 'PARTIALLY_APPROVED'].includes(plan.status);
    if (this.approvePlanBtn) {
      this.approvePlanBtn.style.display = canApprove ? 'inline-block' : 'none';
    }
    if (this.rejectPlanBtn) {
      this.rejectPlanBtn.style.display = canReject ? 'inline-block' : 'none';
    }

    // Render items
    this.renderPlanItems(plan.items || []);
  }

  /**
   * Renders plan items with citations and actions.
   */
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
      const selectorId = `catalogSelect_${item.itemId || index}`;
      const searchId = `catalogSearch_${item.itemId || index}`;

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
                <button class="btn btn-outline-primary btn-item-execute" data-item-id="${this.escapeAttr(item.itemId)}" data-script="${this.escapeAttr(item.scriptName || '')}" data-selector-id="${this.escapeAttr(selectorId)}" title="Execute Patch Loop">
                  <i class="bi bi-play-fill"></i>
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
          ${item.status === 'APPROVED' && !item.scriptName && this.scriptCatalog.length > 0 ? `
            <div class="mt-2">
              <label class="small text-muted mb-1 d-block">Catalog script/task</label>
              <input
                type="text"
                class="form-control form-control-sm mb-1 task-catalog-search"
                id="${this.escapeAttr(searchId)}"
                data-selector-id="${this.escapeAttr(selectorId)}"
                placeholder="Search task catalog..."
              >
              <select class="form-select form-select-sm task-catalog-select" id="${this.escapeAttr(selectorId)}">
                ${this.buildCatalogOptionsHtml()}
              </select>
            </div>
          ` : ''}
          ${item.citations && item.citations.length > 0 ? this.renderCitations(item.citations) : ''}
        </div>
      `;
    }).join('');

    // Bind item action handlers
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
        let selectedScript = btn.dataset.script;
        if ((!selectedScript || selectedScript.trim() === '') && selectorId) {
          const selector = document.getElementById(selectorId);
          if (selector && selector.value) {
            selectedScript = selector.value;
          }
        }
        this.executeItemPatchLoop(btn.dataset.itemId, selectedScript);
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
  }

  buildCatalogOptionsHtml() {
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
          return `<option value="${this.escapeAttr(taskKey)}">${this.escapeHtml(this.buildCatalogTaskLabel(task))}</option>`;
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
    return label;
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

  /**
   * Renders citation list for a plan item.
   */
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

  /**
   * Generates a new task plan.
   */
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

      // Reload plans and select the new one
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

  /**
   * Approves all items in the current plan.
   */
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

  /**
   * Rejects the current plan.
   */
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

  /**
   * Approves a single plan item.
   */
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

  /**
   * Rejects a single plan item.
   */
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

  /**
   * Executes a patch loop for a plan item.
   */
  async executeItemPatchLoop(itemId, scriptName) {
    try {
      const response = await fetch(`/api/account-tasks/items/${itemId}/execute-patch-loop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(scriptName ? { scriptName } : {})
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      const result = await response.json();

      // If patch loop manager exists, connect to it
      if (window.patchLoopManager && result.executionId) {
        window.patchLoopManager.connect(result.executionId);
      }

      // Show the patch loop panel if hidden
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

  // Utility methods

  escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }

  escapeAttr(text) {
    if (!text) return '';
    return text.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
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

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  window.accountTaskPlanner = new AccountTaskPlanner();
  window.accountTaskPlanner.init();
});
