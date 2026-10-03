'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const ui = require('../../main/resources/static/js/ai-player-catalog.js');
const schema = JSON.parse(fs.readFileSync('src/test/resources/sc641/fishing-fields.json', 'utf8'));
const template = fs.readFileSync('src/main/resources/templates/debugger.html', 'utf8');
const task = { task_key: 'FISHING_TRAINING', display_name: 'Fishing Training', category: 'skilling', category_family: 'fishing', primary_skill: 'FISHING', membership: 'FREE', runnable: true, search_aliases: ['FISH_TRAINING'], settings_schema_hash: 'sha256:schema', args_schema: schema };
const catalog = { state: 'READY', catalog_hash: 'sha256:catalog', catalog_version: '2', tasks: [task] };
const detail = { state: 'READY', schema_state: 'AVAILABLE', catalog_hash: catalog.catalog_hash, catalog_version: '2', task, source_summary: { availability: 'UNKNOWN' }, method_facts: { state: 'UNKNOWN' } };
const clone = o => structuredClone(o);
const response = (body, ok = true) => ({ ok, json: async () => clone(body) });
function setup(handler) {
  const dom = new JSDOM(template, { url: 'http://localhost/debugger?workspace=ai-player' });
  const calls = [];
  const fetcher = async (path, init = {}) => { calls.push({ path, init }); if (handler) { const r = await handler(path, init); if (r) return r; }
    if (path === '/api/micro6/executors') return response({ executors: [{ endpoint_ref: 'fixture-ref' }] });
    if (path.endsWith('/profiles')) return response({ profiles: [] });
    if (path.includes('/catalog/tasks/')) return response(detail);
    if (path.includes('/catalog?')) return response(catalog);
    throw new Error('Unexpected ' + path);
  };
  const controller = ui.mount(dom.window.document, fetcher);
  const el = name => dom.window.document.querySelector(`[data-player-${name}]`);
  const input = key => dom.window.document.getElementById('player-setting-' + key);
  const change = (key, value) => { input(key).value = value; input(key).dispatchEvent(new dom.window.Event('change')); };
  async function browse() { await controller.activate(); el('endpoint').value = 'fixture-ref'; await controller.loadCatalog(); }
  return { dom, calls, controller, el, input, change, browse };
}
const submit = { preventDefault() {} };

test('startup lists sources without fetching settings or choosing a client', async () => {
  const view = setup(); await view.controller.activate();
  assert.equal(view.el('endpoint').value, '');
  assert.equal(view.calls.filter(c => c.path.includes('catalog')).length, 0);
  await view.browse(); assert.equal(view.calls.filter(c => c.path.includes('/catalog/tasks')).length, 0);
  view.el('search').value = 'fish training'; view.el('search').dispatchEvent(new view.dom.window.Event('input'));
  assert.equal(view.el('tasks').querySelectorAll('button').length, 1);
});

test('lazy selected task renders actual defaults and dependency-controlled settings', async () => {
  const view = setup(); await view.browse(); await view.controller.selectTask(task.task_key);
  assert.equal(view.input('supplyBudget').value, '20000'); assert.equal(view.input('bankClueBottles').checked, true);
  assert.equal(view.input('bankClueBottles').className, 'form-check-input');
  assert.equal(view.input('supplyBudget').labels.length, 1);
  assert.equal(view.input('supplyBudget').getAttribute('aria-describedby'), 'player-setting-supplyBudget-error');
  assert.equal(view.input('locationName').value, 'Lumbridge Swamp');
  view.change('targetFish', 'Trout'); assert.equal(view.input('locationName').value, 'Barbarian Village');
  view.change('stopCondition', 'Unlimited'); assert.equal(view.input('stopConditionValue').parentElement.hidden, true);
  assert.equal(view.el('save').disabled, false); assert.equal(view.el('code').hidden, true);
});

test('missing schema disables settings and every save', async () => {
  const view = setup(path => path.includes('/catalog/tasks/') ? response({ ...detail, schema_state: 'UNAVAILABLE', schema_reason: 'No applicable schema' }) : null);
  await view.browse(); await view.controller.selectTask(task.task_key);
  assert.equal(view.el('settings').disabled, true); assert.equal(view.el('settings').children.length, 0); assert.equal(view.el('save').disabled, true);
  assert.match(view.el('schema-state').textContent, /No applicable schema/);
});

test('stale and unavailable catalogs remain explicit and cannot save new versions', async () => {
  const view = setup(path => path.includes('/catalog?') ? response({ ...catalog, state: 'STALE', captured_at: '2026-01-01T00:00:00Z' }) : path.includes('/catalog/tasks/') ? response({ ...detail, state: 'STALE' }) : null);
  await view.browse(); assert.match(view.el('notice').textContent, /Stale last-known-good/);
  await view.controller.selectTask(task.task_key); assert.equal(view.el('save').disabled, true);
  view.el('endpoint').value = ''; await view.controller.loadCatalog();
  assert.equal(view.el('settings').children.length, 0); assert.equal(view.el('save').disabled, true);
});

test('native range validation surfaces field errors before sending a mutation', async () => {
  const view = setup(); await view.browse(); await view.controller.selectTask(task.task_key);
  view.change('supplyBudget', '999'); view.el('name').value = 'Fishing'; await view.controller.save(submit);
  assert.equal(view.input('supplyBudget').getAttribute('aria-invalid'), 'true');
  assert.equal(view.calls.filter(c => c.init.method === 'POST').length, 0);
});

test('server field errors are attached to the affected control', async () => {
  const view = setup((path, init) => init.method === 'POST' ? response({ message: 'Correct settings', fieldErrors: { supplyBudget: 'Outside range' } }, false) : null);
  await view.browse(); await view.controller.selectTask(task.task_key); view.el('name').value = 'Fishing'; await view.controller.save(submit);
  assert.equal(view.input('supplyBudget').getAttribute('aria-invalid'), 'true'); assert.match(view.input('supplyBudget').parentElement.textContent, /Outside range/);
});

test('save sends current identities and active settings without persisting the operator token', async () => {
  const view = setup((path, init) => init.method === 'POST' ? response({ profile_id: 'profile-1', profile_version: 1, name: 'Fishing', settings_hash: 'sha256:settings' }) : null);
  await view.browse(); await view.controller.selectTask(task.task_key); view.change('stopCondition', 'Unlimited');
  view.el('name').value = 'Fishing'; view.el('token').value = 'fixture-secret'; await view.controller.save(submit);
  const call = view.calls.find(c => c.init.method === 'POST'); const body = JSON.parse(call.init.body);
  assert.equal(body.taskKey, task.task_key); assert.equal(body.catalogHash, catalog.catalog_hash); assert.equal(body.schemaHash, task.settings_schema_hash);
  assert.equal(Object.hasOwn(body.settings, 'stopConditionValue'), false); assert.equal(body.settings.supplyBudget, 20000);
  assert.equal(call.init.headers.Authorization, 'Bearer fixture-secret'); assert.equal(call.init.body.includes('fixture-secret'), false); assert.equal(view.dom.window.localStorage.length, 0);
  assert.match(view.el('save-status').textContent, /Saved Fishing v1/);
});

test('historical versions use retained snapshots and editing loads the current schema', async () => {
  const profile = { profile_id: 'profile-1', profile_version: 1, name: 'Fishing v1', task_key: task.task_key, task_snapshot: clone(task), schema_snapshot: clone(schema), source_summary: {}, method_facts: { state: 'UNKNOWN' }, settings_json: { ...ui.defaults(schema), supplyBudget: 30000 }, catalog_hash: 'old-hash', catalog_version: 'old', settings_hash: 'old-settings' };
  const view = setup(path => path.includes('/versions/') ? response(profile) : null);
  await view.browse(); await view.controller.loadProfile('profile-1', 1);
  assert.equal(view.input('supplyBudget').value, '30000'); assert.equal(view.el('settings').disabled, true); assert.equal(view.el('save').disabled, true);
  assert.match(view.el('schema-state').textContent, /Historical immutable/);
  await view.controller.selectTask(task.task_key, 'profile-1', 'Fishing v1', profile.settings_json, task.settings_schema_hash);
  assert.equal(view.input('supplyBudget').value, '30000'); assert.equal(view.el('save').textContent, 'Save a new version');
  assert.equal(profile.settings_json.supplyBudget, 30000);
});

test('out-of-order task responses cannot overwrite the latest selection', async () => {
  let resolveFirst;
  const view = setup(path => path.includes('/catalog/tasks/FIRST') ? new Promise(resolve => { resolveFirst = resolve; }) : path.includes('/catalog/tasks/SECOND') ? response({ ...detail, task: { ...task, task_key: 'SECOND', display_name: 'Latest task' } }) : null);
  await view.browse(); const first = view.controller.selectTask('FIRST'); await view.controller.selectTask('SECOND');
  resolveFirst(response({ ...detail, task: { ...task, display_name: 'Old task' } })); await first;
  assert.equal(view.el('title').textContent, 'Latest task');
});

test('source links require exact provenance and method facts never become a score', async () => {
  assert.equal(ui.codeLink({ availability: 'AVAILABLE', recording_id: 'r', tick: 1 }), null);
  const link = ui.codeLink({ availability: 'AVAILABLE', recording_id: 'r', tick: 1, source_snapshot_id: 'srcsnap_' + 'b'.repeat(24), source_manifest_hash: 'sha256:' + 'b'.repeat(64) });
  assert.equal(link, '/debugger?workspace=code&codeRecordingId=r&codeTick=1');
  const view = setup(path => path.includes('/catalog/tasks/') ? response({ ...detail, method_facts: { state: 'UNKNOWN_STALE', throughput: { min: 100, max: 200, unit: 'xp/hour' } } }) : null);
  await view.browse(); await view.controller.selectTask(task.task_key);
  assert.match(view.el('facts').textContent, /current performance is unknown/);
  assert.equal(view.el('code').hidden, true); assert.equal(view.el('metadata').textContent.includes('score'), false);
});

test('untrusted task and source text is rendered literally', async () => {
  const payload = '<img src=x onerror=alert(1)>';
  const view = setup(path => path.includes('/catalog/tasks/') ? response({ ...detail, task: { ...task, display_name: payload }, source_summary: { entry_class: payload } }) : null);
  await view.browse(); await view.controller.selectTask(task.task_key);
  assert.equal(view.el('title').textContent, payload); assert.equal(view.el('source').querySelectorAll('img').length, 0);
});


test('exact Code links enable only when the SC-812 module is installed', async () => {
  global.ChromaScapeCodePanes = {};
  try {
    const source = { availability: 'AVAILABLE', recording_id: 'fixture-r', tick: 2, source_snapshot_id: 'srcsnap_' + 'b'.repeat(24), source_manifest_hash: 'sha256:' + 'b'.repeat(64) };
    const view = setup(path => path.includes('/catalog/tasks/') ? response({ ...detail, source_summary: source }) : null);
    await view.browse(); await view.controller.selectTask(task.task_key);
    assert.equal(view.el('code').hidden, false);
    assert.equal(view.el('code').getAttribute('href'), '/debugger?workspace=code&codeRecordingId=fixture-r&codeTick=2');
  } finally { delete global.ChromaScapeCodePanes; }
});

test('required boolean false remains valid and multi-select dependencies update options', async () => {
  const extra = clone(schema); extra.fields.push({ key: 'mode', type: 'select', default: 'A', options: ['A', 'B'] }, { key: 'flags', type: 'multi_select', default: ['X'], depends_on: 'mode', options_by_dependency: { A: ['X'], B: ['Y'] } }, { key: 'requiredFlag', type: 'boolean', default: false, required: true });
  const view = setup(path => path.includes('/catalog/tasks/') ? response({ ...detail, task: { ...task, args_schema: extra } }) : null);
  await view.browse(); await view.controller.selectTask(task.task_key);
  assert.equal(view.input('requiredFlag').required, false);
  view.change('mode', 'B');
  assert.deepEqual([...view.input('flags').options].map(o => o.value), ['Y']);
  assert.equal(view.input('flags').selectedOptions.length, 0);
});
