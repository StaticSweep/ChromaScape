(function (global) {
  'use strict';
  const ROOT = '/api/v1/ai-routine';

  function options(field, values) {
    return field.options_by_dependency ? (field.options_by_dependency[String(values[field.depends_on])] || []) : (field.options || []);
  }
  function visible(field, values) { return !field.options_by_dependency || options(field, values).length > 0; }
  function valueOf(option) { return typeof option === 'object' ? option.value : option; }
  function defaults(schema) { return Object.fromEntries(schema.fields.filter(f => Object.hasOwn(f, 'default')).map(f => [f.key, structuredClone(f.default)])); }
  function filterTasks(tasks, category, family, search) {
    const q = String(search || '').toLowerCase().replace(/[\s-]+/g, '_');
    return tasks.filter(t => (!category || t.category === category) && (!family || t.category_family === family)
      && (!q || [t.task_key, t.display_name, ...(t.search_aliases || [])].some(s => String(s).toLowerCase().replace(/[\s-]+/g, '_').includes(q))));
  }
  function codeLink(source) {
    if (!/^sha256:[a-f0-9]{64}$/.test(source.source_manifest_hash || '') || source.source_snapshot_id !== 'srcsnap_' + source.source_manifest_hash.slice(7, 31)
      || !source.recording_id || !Number.isSafeInteger(source.tick) || source.tick < 0 || source.availability !== 'AVAILABLE') return null;
    return '/debugger?' + new URLSearchParams({ workspace: 'code', codeRecordingId: source.recording_id, codeTick: source.tick });
  }

  function mount(document, fetcher) {
    const panel = document.getElementById('aiPlayerCatalogPanel');
    if (!panel) return null;
    const el = name => panel.querySelector(`[data-player-${name}]`);
    let catalog = { tasks: [] }, detail = null, values = {}, profileId = null, savedProfile = null;
    let epoch = 0, saving = false, activated = false, fields = new Map();
    const renderText = (name, value) => { el(name).textContent = value || ''; };
    const notice = value => renderText('notice', value);
    async function request(path, init = {}) {
      const response = await fetcher(path, init);
      const payload = await response.json();
      if (!response.ok) { const error = new Error(payload.message || 'Request failed'); error.payload = payload; throw error; }
      return payload;
    }
    function selectOptions(select, names, blank, previous) {
      select.replaceChildren();
      const add = (value, label) => { const option = document.createElement('option'); option.value = value; option.textContent = label; select.append(option); };
      add('', blank); names.forEach(n => add(n, n));
      select.value = names.includes(previous) ? previous : '';
    }
    function renderTasks() {
      const list = el('tasks'); list.replaceChildren();
      const tasks = filterTasks(catalog.tasks || [], el('category').value, el('family').value, el('search').value);
      for (const task of tasks) {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = task.display_name;
        button.setAttribute('aria-pressed', String(task.task_key === detail?.task?.task_key)); button.dataset.taskKey = task.task_key;
        const small = document.createElement('small'); small.textContent = `${task.task_key} · ${task.primary_skill || task.category_family} · ${task.membership || 'Membership unknown'}`; button.append(small);
        button.addEventListener('click', () => selectTask(task.task_key)); list.append(button);
      }
      if (!tasks.length) list.textContent = 'No canonical tasks match these filters.';
    }
    function metadata(node, data) {
      node.replaceChildren();
      for (const [key, value] of Object.entries(data)) {
        const term = document.createElement('dt'); term.textContent = key.replaceAll('_', ' ');
        const content = document.createElement('dd'); content.textContent = value == null ? 'Unknown' : typeof value === 'object' ? JSON.stringify(value) : String(value);
        node.append(term, content);
      }
    }
    function updateSave() {
      el('save').disabled = saving || !detail || detail.schema_state !== 'AVAILABLE' || detail.state !== 'READY' || Boolean(savedProfile);
      el('save').textContent = profileId ? 'Save a new version' : 'Save new profile';
    }
    function readValue(field, input) {
      if (field.type === 'boolean') return input.checked;
      if (field.type === 'multi_select') return [...input.selectedOptions].map(o => o.value);
      if (['number', 'integer'].includes(field.type)) return input.value === '' ? null : Number(input.value);
      return input.value;
    }
    function refreshDependencies(changed) {
      for (const field of detail.task.args_schema.fields) {
        const view = fields.get(field.key); if (!view) continue;
        const active = visible(field, values); view.row.hidden = !active; view.input.disabled = !active || Boolean(savedProfile);
        if (['select', 'multi_select'].includes(field.type) && field.depends_on === changed) {
          const choices = options(field, values); view.input.replaceChildren();
          for (const option of choices) { const o = document.createElement('option'); o.value = valueOf(option); o.textContent = option.label || String(valueOf(option)); view.input.append(o); }
          const previous = JSON.stringify(values[field.key]);
          if (field.type === 'multi_select') {
            values[field.key] = (values[field.key] || []).filter(v => choices.some(o => valueOf(o) === v));
            [...view.input.options].forEach(o => { o.selected = values[field.key].includes(o.value); });
          } else {
            if (!choices.some(o => valueOf(o) === values[field.key])) values[field.key] = choices.length ? valueOf(choices[0]) : null;
            view.input.value = values[field.key] ?? '';
          }
          if (previous !== JSON.stringify(values[field.key])) refreshDependencies(field.key);
        }
      }
    }
    function renderSettings() {
      const container = el('settings'); container.replaceChildren(); fields = new Map();
      if (detail.schema_state !== 'AVAILABLE') { container.disabled = true; renderText('schema-state', detail.schema_reason || 'Settings unavailable'); updateSave(); return; }
      container.disabled = Boolean(savedProfile); renderText('schema-state', savedProfile ? 'Historical immutable schema and settings' : 'Settings schema available');
      for (const field of detail.task.args_schema.fields) {
        const row = document.createElement('div'); row.className = 'player-field';
        const label = document.createElement('label'); const id = 'player-setting-' + field.key; label.htmlFor = id; label.textContent = field.label || field.key;
        const select = ['select', 'multi_select'].includes(field.type);
        const input = document.createElement(select ? 'select' : 'input'); input.id = id; input.name = field.key; input.className = select ? 'form-select' : field.type === 'boolean' ? 'form-check-input' : 'form-control';
        if (select) {
          input.multiple = field.type === 'multi_select';
          for (const option of options(field, values)) { const o = document.createElement('option'); o.value = valueOf(option); o.textContent = option.label || String(valueOf(option)); input.append(o); }
        } else input.type = field.type === 'boolean' ? 'checkbox' : ['number', 'integer'].includes(field.type) ? 'number' : 'text';
        if (field.type === 'boolean') input.checked = Boolean(values[field.key]);
        else if (field.type === 'multi_select') [...input.options].forEach(o => { o.selected = (values[field.key] || []).includes(o.value); });
        else input.value = values[field.key] ?? '';
        if (field.min != null) input.min = field.min; if (field.max != null) input.max = field.max;
        if (field.type === 'integer') input.step = '1'; else if (field.type === 'number') input.step = 'any';
        input.required = Boolean(field.required) && field.type !== 'boolean';
        const help = document.createElement('small'); help.textContent = field.description || field.section || '';
        const error = document.createElement('span'); error.className = 'player-field-error'; error.id = id + '-error'; error.setAttribute('role', 'alert');
        input.setAttribute('aria-describedby', error.id); input.addEventListener('change', () => { values[field.key] = readValue(field, input); error.textContent = ''; input.removeAttribute('aria-invalid'); refreshDependencies(field.key); });
        row.append(label, input, help, error); container.append(row); fields.set(field.key, { row, input, error });
      }
      refreshDependencies(); updateSave();
    }
    function renderDetail() {
      renderText('title', detail.task.display_name); renderText('description', detail.task.description);
      metadata(el('metadata'), { task: detail.task.task_key, catalog_version: detail.catalog_version, membership: detail.task.membership,
        catalog_runnable: detail.task.runnable, live_eligibility: 'Unknown — checked separately', requirements: detail.task.requirements || 'Unknown' });
      metadata(el('source'), detail.source_summary || { availability: 'UNKNOWN' });
      const link = global.ChromaScapeCodePanes ? codeLink(detail.source_summary || {}) : null; el('code').hidden = !link;
      if (link) el('code').href = link;
      renderText('code-reason', link ? 'Uses exact recording evidence and the authorized SC-812 Code workspace.' : 'Code mapping needs the SC-812 workspace, an exact source snapshot, and recorded SC-811 evidence.');
      const facts = detail.method_facts || { state: 'UNKNOWN' };
      renderText('facts', facts.state === 'CITED' ? 'Cited planning facts; choose the method yourself.' : facts.state === 'UNKNOWN_STALE' ? 'Facts are stale; current performance is unknown.' : 'Method facts are unknown.');
      el('facts-json').hidden = facts.state === 'UNKNOWN'; renderText('facts-json', JSON.stringify(facts, null, 2));
      renderSettings(); renderTasks();
    }
    function clearDetail() {
      el('settings').replaceChildren(); el('settings').disabled = true; fields = new Map();
      renderText('title', 'Choose a canonical task'); renderText('description', '');
      el('metadata').replaceChildren(); el('source').replaceChildren(); el('code').hidden = true;
      renderText('code-reason', ''); renderText('facts', 'Method facts are unknown.');
      el('facts-json').hidden = true; el('edit').disabled = true;
    }
    async function loadCatalog(refresh = false) {
      const generation = ++epoch, endpoint = el('endpoint').value;
      detail = null; savedProfile = null; updateSave(); clearDetail(); catalog = { tasks: [] }; renderTasks();
      if (!endpoint) { renderText('state', 'Choose a catalog source'); return; }
      renderText('state', 'LOADING'); notice('Loading the lightweight task catalog…');
      try {
        const result = await request(`${ROOT}/catalog?` + new URLSearchParams({ endpointRef: endpoint, refresh }));
        if (generation !== epoch) return;
        catalog = result; renderText('state', result.state);
        notice(result.state === 'STALE' ? `Stale last-known-good catalog from ${result.captured_at || 'an unknown time'}. New saves are disabled until refreshed.` : result.reason || (result.state === 'READY' ? '' : 'Catalog is degraded or unavailable.'));
        selectOptions(el('category'), [...new Set((catalog.tasks || []).map(t => t.category))].sort(), 'All categories', el('category').value);
        selectOptions(el('family'), [...new Set((catalog.tasks || []).map(t => t.category_family))].sort(), 'All families', el('family').value);
        renderTasks();
      } catch (error) { if (generation === epoch) { renderText('state', 'UNAVAILABLE'); notice(error.message); catalog = { tasks: [] }; renderTasks(); } }
    }
    async function selectTask(key, editId = null, editName = '', retainedSettings = null, retainedSchemaHash = null) {
      const generation = ++epoch, endpoint = el('endpoint').value;
      detail = null; savedProfile = null; profileId = editId; values = {}; updateSave(); clearDetail();
      renderText('save-status', ''); renderText('schema-state', 'Loading task settings…'); el('name').value = editName;
      try {
        const result = await request(`${ROOT}/catalog/tasks/${encodeURIComponent(key)}?` + new URLSearchParams({ endpointRef: endpoint, catalogHash: catalog.catalog_hash || '' }));
        if (generation !== epoch) return;
        detail = result; values = result.schema_state === 'AVAILABLE' ? defaults(result.task.args_schema) : {};
        if (retainedSettings && result.schema_state === 'AVAILABLE') {
          if (retainedSchemaHash === result.task.settings_schema_hash) Object.assign(values, structuredClone(retainedSettings));
          else notice('The schema changed. Review current defaults before saving a new version.');
        }
        renderDetail();
        if (result.state === 'STALE') notice('Task schema is stale; refresh before saving a profile.');
      } catch (error) { if (generation === epoch) { renderText('schema-state', error.message); notice(error.message); } }
    }
    async function loadProfiles() {
      try {
        const data = await request(`${ROOT}/profiles`); const select = el('profiles'); select.replaceChildren();
        const empty = document.createElement('option'); empty.value = ''; empty.textContent = 'Select a saved version'; select.append(empty);
        for (const p of data.profiles) { const option = document.createElement('option'); option.value = `${p.profile_id}/${p.profile_version}`; option.textContent = `${p.name} · v${p.profile_version} · ${p.task_key}`; select.append(option); }
      } catch (error) { renderText('save-status', error.message); }
    }
    async function loadProfile(id, version) {
      const generation = ++epoch; detail = null; updateSave(); clearDetail();
      try {
        const p = await request(`${ROOT}/profiles/${encodeURIComponent(id)}/versions/${version}`);
        if (generation !== epoch) return;
        savedProfile = p; profileId = id; el('edit').disabled = false; el('name').value = p.name;
        detail = { task: p.task_snapshot, catalog_hash: p.catalog_hash, catalog_version: p.catalog_version, schema_state: 'AVAILABLE', state: 'HISTORICAL', source_summary: p.source_summary, method_facts: p.method_facts };
        detail.task.args_schema = p.schema_snapshot; values = p.settings_json; renderDetail();
        renderText('save-status', `Immutable ${p.name} v${p.profile_version} · ${p.settings_hash}`);
      } catch (error) { if (generation === epoch) renderText('save-status', error.message); }
    }
    async function save(event) {
      event.preventDefault(); if (!detail || savedProfile || saving || el('save').disabled) return;
      fields.forEach(view => { view.error.textContent = ''; view.input.removeAttribute('aria-invalid'); });
      let invalid = false;
      for (const field of detail.task.args_schema.fields) {
        const view = fields.get(field.key);
        if (!view || !visible(field, values)) continue;
        values[field.key] = readValue(field, view.input);
        if (!view.input.checkValidity() || (['integer', 'number'].includes(field.type) && values[field.key] != null && !Number.isFinite(values[field.key]))) {
          view.error.textContent = 'Enter a valid value within the permitted range.'; view.input.setAttribute('aria-invalid', 'true'); invalid = true;
        }
      }
      if (invalid) { renderText('save-status', 'Correct the highlighted settings.'); return; }
      const settings = {};
      for (const field of detail.task.args_schema.fields) if (visible(field, values) && values[field.key] != null) settings[field.key] = values[field.key];
      const requestEpoch = epoch;
      saving = true; updateSave(); renderText('save-status', 'Saving an immutable profile version…');
      try {
        const p = await request(`${ROOT}/profiles`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${el('token').value}` },
          body: JSON.stringify({ profileId, name: el('name').value, taskKey: detail.task.task_key, endpointRef: el('endpoint').value,
            catalogHash: detail.catalog_hash, schemaHash: detail.task.settings_schema_hash, settings }) });
        if (requestEpoch !== epoch) return;
        profileId = p.profile_id; renderText('save-status', `Saved ${p.name} v${p.profile_version} · ${p.settings_hash}`); await loadProfiles();
      } catch (error) {
        if (requestEpoch !== epoch) return;
        renderText('save-status', error.message);
        for (const [key, message] of Object.entries(error.payload?.fieldErrors || {})) { const view = fields.get(key); if (view) { view.error.textContent = message; view.input.setAttribute('aria-invalid', 'true'); } }
      } finally { saving = false; updateSave(); }
    }
    async function activate() {
      if (activated) return; activated = true;
      renderText('state', 'Loading catalog sources…');
      try {
        const data = await request('/api/micro6/executors');
        selectOptions(el('endpoint'), (data.executors || []).map(e => e.endpoint_ref).filter(Boolean), 'Choose a catalog source', '');
        renderText('state', data.executors?.length ? 'Choose a catalog source' : 'No catalog sources are available');
      } catch (error) { notice(error.message); }
      await loadProfiles();
    }
    el('endpoint').addEventListener('change', () => loadCatalog()); el('refresh').addEventListener('click', () => loadCatalog(true));
    for (const name of ['category', 'family', 'search']) el(name).addEventListener(name === 'search' ? 'input' : 'change', renderTasks);
    el('profiles').addEventListener('change', () => { const [id, version] = el('profiles').value.split('/'); if (id) loadProfile(id, version); });
    el('edit').addEventListener('click', () => { if (savedProfile) selectTask(savedProfile.task_key, savedProfile.profile_id, savedProfile.name, savedProfile.settings_json, savedProfile.schema_hash || savedProfile.task_snapshot.settings_schema_hash); });
    el('new').addEventListener('click', () => { profileId = null; savedProfile = null; el('name').value = ''; if (detail) selectTask(detail.task.task_key); });
    el('form').addEventListener('submit', save);
    document.addEventListener('debugger:workspace-changed', e => { if (e.detail?.workspace === 'ai-player') activate(); });
    return { activate, loadCatalog, selectTask, loadProfiles, loadProfile, save };
  }
  const api = { options, visible, defaults, filterTasks, codeLink, mount };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  global.AiPlayerCatalog = api;
  if (typeof document !== 'undefined') {
    const initialize = () => { const view = mount(document, global.fetch.bind(global)); if (document.body.dataset.workspace === 'ai-player') view?.activate(); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize); else initialize();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
