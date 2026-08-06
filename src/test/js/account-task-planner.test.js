'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');

const path = require('node:path');

const AccountTaskPlanner = require(
  path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js')
);

function availability(overrides = {}) {
  return AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: null,
    machine: null,
    hasTask: true,
    hasWorld: true,
    importedRunnable: false,
    ...overrides
  });
}

const credentialedAccount = {
  accountId: 'hash-1',
  runnable: true,
  launchReady: true,
  executorOnline: true,
  online: true
};

const credentialedOfflineAccount = {
  accountId: 'hash-1',
  runnable: true,
  launchReady: true,
  executorOnline: false,
  online: false
};

const credentiallessAccount = {
  accountId: 'hash-1',
  runnable: true,
  launchReady: false,
  executorOnline: true,
  online: true,
  launchDisabledReason: 'Add a password to this account in launcher control'
};

const credentiallessOfflineAccount = {
  accountId: 'hash-1',
  runnable: true,
  launchReady: false,
  executorOnline: false,
  online: false
};

const disabledAccount = {
  accountId: 'hash-1',
  runnable: false,
  launchReady: false,
  executorOnline: true,
  online: true,
  disabledReason: 'Account is disabled in launcher control'
};

const bannedAccount = {
  accountId: 'hash-1',
  runnable: false,
  launchReady: false,
  executorOnline: true,
  online: true,
  disabledReason: 'Account is banned in launcher control'
};

const runnableMachine = {
  machineUuid: 'machine-1',
  builderGoalRunnable: true
};

const offlineMachine = {
  machineUuid: 'machine-2',
  builderGoalRunnable: false
};

const localMachine = uuid => ({
  machineUuid: uuid,
  displayName: `Local ${uuid}`,
  online: true,
  enabled: true,
  localMachine: true,
  builderGoalRunnable: true,
  defaultSelected: false
});

const remoteMachine = uuid => ({
  machineUuid: uuid,
  displayName: `Remote ${uuid}`,
  online: true,
  enabled: true,
  localMachine: false,
  builderGoalRunnable: false,
  defaultSelected: false,
  builderGoalDisabledReason: 'Builder Goal local dev launches only run on the local ChromaScape host'
});

const offlineLocalMachine = uuid => ({
  machineUuid: uuid,
  displayName: `Offline ${uuid}`,
  online: false,
  enabled: true,
  localMachine: true,
  builderGoalRunnable: false,
  defaultSelected: false,
  builderGoalDisabledReason: 'Launcher machine is offline'
});

function select(machines, previous) {
  return AccountTaskPlanner.selectBuilderGoalMachineUuid(machines, previous);
}

test('selectBuilderGoalMachineUuid: valid persisted local selection wins', () => {
  const machines = [localMachine('local-1'), remoteMachine('remote-1')];
  assert.equal(select(machines, 'local-1'), 'local-1');
});

test('selectBuilderGoalMachineUuid: stale persisted UUID is discarded', () => {
  const machines = [localMachine('local-1')];
  assert.equal(select(machines, 'gone-machine'), 'local-1');
});

test('selectBuilderGoalMachineUuid: persisted remote UUID is discarded', () => {
  const machines = [localMachine('local-1'), remoteMachine('remote-1')];
  assert.equal(select(machines, 'remote-1'), 'local-1');
});

test('selectBuilderGoalMachineUuid: runnable configured default wins', () => {
  const machines = [
    localMachine('local-1'),
    { ...localMachine('local-2'), defaultSelected: true }
  ];
  assert.equal(select(machines, ''), 'local-2');
});

test('selectBuilderGoalMachineUuid: non-runnable configured default is ignored', () => {
  const machines = [
    { ...remoteMachine('remote-1'), defaultSelected: true },
    localMachine('local-1')
  ];
  assert.equal(select(machines, ''), 'local-1');
});

test('selectBuilderGoalMachineUuid: exactly one runnable local machine is auto-selected with multiple machines', () => {
  const machines = [localMachine('local-1'), remoteMachine('remote-1')];
  assert.equal(select(machines, ''), 'local-1');
});

test('selectBuilderGoalMachineUuid: no runnable machines returns an empty string', () => {
  const machines = [remoteMachine('remote-1'), offlineLocalMachine('offline-1')];
  assert.equal(select(machines, 'remote-1'), '');
});

test('selectBuilderGoalMachineUuid: multiple runnable machines without a valid default returns an empty string', () => {
  const machines = [localMachine('local-1'), localMachine('local-2')];
  assert.equal(select(machines, ''), '');
  assert.equal(select(machines, 'stale-machine'), '');
});

test('selectBuilderGoalMachineUuid: handles empty and null input lists', () => {
  assert.equal(select([], 'machine-1'), '');
  assert.equal(select(null, 'machine-1'), '');
  assert.equal(select(undefined, undefined), '');
});

test('selectBuilderGoalMachineUuid: persisted local machine that became non-runnable is not preserved', () => {
  const machines = [offlineLocalMachine('local-1'), localMachine('local-2')];
  assert.equal(select(machines, 'local-1'), 'local-2');
});

test('availability enables Launch and Run with the selected local machine', () => {
  const result = availability({
    account: credentialedAccount,
    machine: localMachine('local-1')
  });
  assert.equal(result.canLaunchAccount, true);
  assert.equal(result.canRunTask, true);
});

test('availability: remote/offline selection cannot enable Launch or Run', () => {
  const remoteResult = availability({
    account: credentialedAccount,
    machine: remoteMachine('remote-1')
  });
  assert.equal(remoteResult.canLaunchAccount, false);
  assert.equal(remoteResult.canRunTask, false);

  const offlineResult = availability({
    account: credentialedAccount,
    machine: offlineLocalMachine('offline-1')
  });
  assert.equal(offlineResult.canLaunchAccount, false);
  assert.equal(offlineResult.canRunTask, false);
});

test('availability: Publish remains independent of machine availability', () => {
  const remoteResult = availability({
    account: credentialedAccount,
    machine: remoteMachine('remote-1')
  });
  assert.equal(remoteResult.canPublishTask, true);

  const nullResult = availability({
    account: credentialedAccount,
    machine: null
  });
  assert.equal(nullResult.canPublishTask, true);
});

test('computeBuilderGoalActionAvailability: Runnable, credentialed, Online executor, Runnable machine => all actions available', () => {
  const result = availability({
    account: credentialedAccount,
    machine: runnableMachine
  });
  assert.deepEqual(result, {
    executorOnline: true,
    machineRunnable: true,
    canLaunchAccount: true,
    canRunTask: true,
    canPublishTask: true,
    canRunImported: false
  });
});

test('computeBuilderGoalActionAvailability: Runnable, credentialed, Online executor, Offline machine => Publish only', () => {
  const result = availability({
    account: credentialedAccount,
    machine: offlineMachine
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, true);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Runnable, credentialed, Offline executor, Runnable machine => Launch and Run only', () => {
  const result = availability({
    account: credentialedOfflineAccount,
    machine: runnableMachine
  });
  assert.equal(result.canLaunchAccount, true);
  assert.equal(result.canRunTask, true);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Runnable, no credentials, Online executor, Offline machine => Publish only', () => {
  const result = availability({
    account: credentiallessAccount,
    machine: offlineMachine
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, true);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Runnable, no credentials, Offline executor, Runnable machine => no actions', () => {
  const result = availability({
    account: credentiallessOfflineAccount,
    machine: runnableMachine
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Disabled, Online executor, Runnable machine => no actions', () => {
  const result = availability({
    account: disabledAccount,
    machine: runnableMachine
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Banned, Online executor, Runnable machine => no actions', () => {
  const result = availability({
    account: bannedAccount,
    machine: runnableMachine
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Runnable, credentialed, Online executor, Runnable machine, no task => Launch only', () => {
  const result = availability({
    account: credentialedAccount,
    machine: runnableMachine,
    hasTask: false
  });
  assert.equal(result.canLaunchAccount, true);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: Runnable, credentialed, Online executor, Runnable machine, invalid world => no actions', () => {
  const result = availability({
    account: credentialedAccount,
    machine: runnableMachine,
    hasWorld: false
  });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('computeBuilderGoalActionAvailability: compatibility online=true without executorOnline => Publish available', () => {
  const legacyAccount = {
    accountId: 'hash-1',
    runnable: true,
    launchReady: false,
    online: true
  };
  const result = availability({
    account: legacyAccount,
    machine: offlineMachine
  });
  assert.equal(result.executorOnline, true);
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, true);
});

test('computeBuilderGoalActionAvailability: executorOnline=false wins over online=true when both present', () => {
  const mixedAccount = {
    accountId: 'hash-1',
    runnable: true,
    launchReady: false,
    executorOnline: false,
    online: true
  };
  const result = availability({
    account: mixedAccount,
    machine: offlineMachine
  });
  assert.equal(result.executorOnline, false);
  assert.equal(result.canPublishTask, false);
});

test('computeBuilderGoalActionAvailability: no account => no actions', () => {
  const result = availability({ account: null, machine: runnableMachine });
  assert.equal(result.canLaunchAccount, false);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('publishBuilderGoal does not reject solely because the selected machine is null', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.getSelectedBuilderGoalAccount = () => ({
    accountId: 'hash-1',
    runnable: true,
    launchReady: false,
    executorOnline: true,
    online: true
  });
  planner.getSelectedBuilderGoalMachine = () => null;
  planner.getSelectedBuilderGoalTask = () => ({
    task_key: 'configurable_woodcutting',
    display_name: 'Configurable Woodcutting'
  });
  planner.getBuilderGoalWorldValue = () => 360;
  planner.parseBuilderGoalConfigInput = () => ({ settings: { treeType: 'WILLOW' }, taskKey: null });
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalConfigDirty = true;
  planner.builderGoalConfigLoadedKey = 'old-key';
  planner.builderGoalConfigInput = { value: '{}' };
  planner.builderGoalConfigMeta = { innerHTML: '' };
  planner.importedBuilderGoal = null;
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.publishBuilderGoalBtn = { disabled: false, innerHTML: '' };
  planner.renderBuilderGoalClientStatus = () => {};
  planner.renderBuilderGoalConfigMeta = () => {};
  planner.updateBuilderGoalRunButtonState = () => {};
  planner.loadBuilderGoalRuns = async () => {};
  planner.bindBuilderGoalCodexRun = async () => ({ bound: false, reason: '' });

  let requestBody;
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    assert.equal(url, '/api/debug/builder-goals/publish');
    requestBody = JSON.parse(options.body);
    return {
      ok: true,
      status: 200,
      json: async () => ({ published: true, run: { iterationId: 'buildergoal_test1234' } })
    };
  };

  try {
    await planner.publishBuilderGoal();
    assert.ok(requestBody, 'publish request was sent');
    assert.equal(requestBody.accountId, 'hash-1');
    assert.equal(requestBody.launcherMachineUuid, undefined);
    assert.equal(requestBody.allowLaunchFallback, false);
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }
});

/**
 * Builds a status-rendering stub around renderBuilderGoalClientStatus().
 * Only the state and methods the status renderer touches are stubbed.
 */
function createStatusPlanner(overrides = {}) {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalCatalogDegraded = false;
  planner.builderGoalCatalogReason = '';
  planner.importedBuilderGoal = null;
  planner.getSelectedBuilderGoalAccount = () => null;
  planner.getSelectedBuilderGoalMachine = () => null;
  planner.getSelectedBuilderGoalTask = () => null;
  planner.getBuilderGoalWorldValue = () => 360;
  planner.parseBuilderGoalConfigInput = () => ({ settings: {}, taskKey: null, requirements: {} });
  Object.assign(planner, overrides);
  return planner;
}

function statusMessage(planner) {
  const html = planner.builderGoalClientStatus.innerHTML || '';
  return html.replace(/<[^>]*>/g, ' ');
}

test('renderBuilderGoalClientStatus: executor online, no task => publish disabled and asks for a task', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => ({
      accountId: 'hash-1',
      runnable: true,
      launchReady: true,
      executorOnline: true,
      online: true
    }),
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => null
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: false,
    hasWorld: true,
    importedRunnable: false
  });
  assert.equal(availability.canPublishTask, false);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(!message.includes('Publish Task is available now'), `unexpected: ${message}`);
  assert.ok(message.includes('Select a task'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: executor online, valid task, invalid world => publish disabled and asks for a valid world', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentialedAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    }),
    getBuilderGoalWorldValue: () => null
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: true,
    hasWorld: false,
    importedRunnable: false
  });
  assert.equal(availability.canPublishTask, false);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('valid world between 300 and 700'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: executor online, valid task and world => publish available message', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentialedAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    })
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: true,
    hasWorld: true,
    importedRunnable: false
  });
  assert.equal(availability.canPublishTask, true);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Publish Task is available'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: missing credentials, executor online, valid task/world => Run and Launch unavailable, Publish available', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentiallessAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    })
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: true,
    hasWorld: true,
    importedRunnable: false
  });
  assert.equal(availability.canRunTask, false);
  assert.equal(availability.canLaunchAccount, false);
  assert.equal(availability.canPublishTask, true);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Publish Task is available'), `unexpected: ${message}`);
  assert.ok(!message.includes('Publish Task is available now'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: offline launcher machine, executor online, valid task/world => Publish remains available', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentialedAccount,
    getSelectedBuilderGoalMachine: () => offlineMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    })
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: true,
    hasWorld: true,
    importedRunnable: false
  });
  assert.equal(availability.canRunTask, false);
  assert.equal(availability.canLaunchAccount, false);
  assert.equal(availability.canPublishTask, true);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Publish Task is available'), `unexpected: ${message}`);
});

test('computeBuilderGoalActionAvailability: invalid config blocks Run, Publish, and Imported', () => {
  const result = availability({
    account: credentialedAccount,
    machine: runnableMachine,
    hasTask: true,
    hasWorld: true,
    configValid: false,
    importedRunnable: true
  });
  assert.equal(result.canLaunchAccount, true);
  assert.equal(result.canRunTask, false);
  assert.equal(result.canPublishTask, false);
  assert.equal(result.canRunImported, false);
});

test('renderBuilderGoalClientStatus: invalid JSON settings, executor online, valid task/world => publish unavailable and invalid JSON surfaced', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentialedAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    }),
    parseBuilderGoalConfigInput: () => ({ error: 'Invalid JSON: Unexpected token' })
  });

  const availability = AccountTaskPlanner.computeBuilderGoalActionAvailability({
    account: planner.getSelectedBuilderGoalAccount(),
    machine: planner.getSelectedBuilderGoalMachine(),
    hasTask: true,
    hasWorld: true,
    configValid: false,
    importedRunnable: false
  });
  assert.equal(availability.canPublishTask, false);

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Invalid JSON'), `unexpected: ${message}`);
  assert.ok(!message.includes('Publish Task is available'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: degraded catalog warning prefixes every readiness message', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentialedAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    }),
    builderGoalCatalogDegraded: true,
    builderGoalCatalogReason: 'Catalog fetch failed; using saved task keys.'
  });

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Catalog fetch failed'), `unexpected: ${message}`);
  assert.ok(message.includes('Publish Task is available'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: degraded catalog warning is shown without an account', () => {
  const planner = createStatusPlanner({
    builderGoalCatalogDegraded: true,
    builderGoalCatalogReason: 'Catalog fetch failed; using saved task keys.'
  });

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Catalog fetch failed'), `unexpected: ${message}`);
  assert.ok(message.includes('Select an account'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: degraded catalog warning is shown for a non-runnable account', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => disabledAccount,
    builderGoalCatalogDegraded: true,
    builderGoalCatalogReason: 'Catalog fetch failed; using saved task keys.'
  });

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Catalog fetch failed'), `unexpected: ${message}`);
  assert.ok(message.includes('Account is disabled in launcher control'), `unexpected: ${message}`);
});

test('renderBuilderGoalClientStatus: invalid JSON wins over missing credentials and executor fallback messaging', () => {
  const planner = createStatusPlanner({
    getSelectedBuilderGoalAccount: () => credentiallessAccount,
    getSelectedBuilderGoalMachine: () => runnableMachine,
    getSelectedBuilderGoalTask: () => ({
      task_key: 'configurable_woodcutting',
      display_name: 'Configurable Woodcutting'
    }),
    parseBuilderGoalConfigInput: () => ({ error: 'Invalid JSON: Unexpected token' })
  });

  planner.renderBuilderGoalClientStatus();
  const message = statusMessage(planner);
  assert.ok(message.includes('Invalid JSON'), `unexpected: ${message}`);
  assert.ok(!message.includes('No dispatch-ready Micro6 executor'), `unexpected: ${message}`);
});

test('handleBuilderGoalWorldInput refreshes metadata, status, and button state', () => {
  const calls = [];
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.renderBuilderGoalConfigMeta = () => calls.push('meta');
  planner.renderBuilderGoalClientStatus = () => calls.push('status');
  planner.updateBuilderGoalRunButtonState = () => calls.push('buttons');

  planner.handleBuilderGoalWorldInput();

  assert.deepEqual(calls, ['meta', 'status', 'buttons']);
});

test('handleBuilderGoalConfigInput marks dirty and refreshes metadata, status, and button state', () => {
  const calls = [];
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.renderBuilderGoalConfigMeta = () => calls.push('meta');
  planner.renderBuilderGoalClientStatus = () => calls.push('status');
  planner.updateBuilderGoalRunButtonState = () => calls.push('buttons');

  planner.handleBuilderGoalConfigInput();

  assert.equal(planner.builderGoalConfigDirty, true);
  assert.deepEqual(calls, ['meta', 'status', 'buttons']);
});

test('refreshBuilderGoalRuns awaits all loaders before rendering and recalculating buttons', async () => {
  const order = [];
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => order.push('accounts');
  planner.loadBuilderGoalMachines = async () => order.push('machines');
  planner.loadBuilderGoalRuns = async () => order.push('runs');
  planner.loadScriptCatalog = async () => order.push('catalog');
  planner.renderBuilderGoalClientStatus = () => order.push('status');
  planner.updateBuilderGoalRunButtonState = () => order.push('buttons');

  await planner.refreshBuilderGoalRuns();

  assert.deepEqual(order, ['accounts', 'machines', 'runs', 'catalog', 'status', 'buttons']);
});

test('refreshBuilderGoalRuns starts all four loaders before final rendering', async () => {
  let started = 0;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const planner = Object.create(AccountTaskPlanner.prototype);
  const loader = async () => {
    started += 1;
    await gate;
  };
  planner.loadBuilderGoalAccounts = loader;
  planner.loadBuilderGoalMachines = loader;
  planner.loadBuilderGoalRuns = loader;
  planner.loadScriptCatalog = loader;
  let rendered = false;
  planner.renderBuilderGoalClientStatus = () => { rendered = true; };
  planner.updateBuilderGoalRunButtonState = () => {};

  const refresh = planner.refreshBuilderGoalRuns();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(started, 4, 'all four loaders must start before any render');
  assert.equal(rendered, false, 'final rendering must wait for the loaders to settle');
  release();
  await refresh;
  assert.equal(rendered, true, 'final rendering must happen after all loaders settle');
});

test('refreshBuilderGoalRuns final rendering waits for delayed loaders', async () => {
  const order = [];
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => order.push('accounts');
  planner.loadBuilderGoalMachines = async () => order.push('machines');
  let releaseRuns;
  const runsGate = new Promise(resolve => { releaseRuns = resolve; });
  planner.loadBuilderGoalRuns = async () => {
    await runsGate;
    order.push('runs');
  };
  planner.loadScriptCatalog = async () => order.push('catalog');
  planner.renderBuilderGoalClientStatus = () => order.push('status');
  planner.updateBuilderGoalRunButtonState = () => order.push('buttons');

  const refresh = planner.refreshBuilderGoalRuns();
  await Promise.resolve();
  await Promise.resolve();
  assert.deepEqual(order, ['accounts', 'machines', 'catalog']);
  releaseRuns();
  await refresh;
  assert.deepEqual(order, ['accounts', 'machines', 'catalog', 'runs', 'status', 'buttons']);
});

test('refreshBuilderGoalRuns one rejected loader does not prevent other loaders from settling', async () => {
  const order = [];
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => order.push('accounts');
  planner.loadBuilderGoalMachines = async () => {
    order.push('machines');
    throw new Error('machine fetch failed');
  };
  planner.loadBuilderGoalRuns = async () => order.push('runs');
  planner.loadScriptCatalog = async () => order.push('catalog');
  planner.renderBuilderGoalClientStatus = () => order.push('status');
  planner.updateBuilderGoalRunButtonState = () => order.push('buttons');
  const logged = [];
  const originalError = console.error;
  console.error = (...args) => logged.push(args);

  try {
    await planner.refreshBuilderGoalRuns();
  } finally {
    console.error = originalError;
  }

  assert.deepEqual(order, ['accounts', 'machines', 'runs', 'catalog', 'status', 'buttons']);
  assert.ok(
    logged.some(args => args.some(part => String(part).includes('Builder Goal refresh loader failed'))),
    'rejected loader must be logged without escaping'
  );
});

test('refreshBuilderGoalRuns one rejected loader still produces final status and button rendering', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => {};
  planner.loadBuilderGoalMachines = async () => {
    throw new Error('machine fetch failed');
  };
  planner.loadBuilderGoalRuns = async () => {};
  planner.loadScriptCatalog = async () => {};
  let statusRendered = false;
  let buttonsRendered = false;
  planner.renderBuilderGoalClientStatus = () => { statusRendered = true; };
  planner.updateBuilderGoalRunButtonState = () => { buttonsRendered = true; };
  const originalError = console.error;
  console.error = () => {};

  try {
    await planner.refreshBuilderGoalRuns();
  } finally {
    console.error = originalError;
  }

  assert.equal(statusRendered, true, 'client status must render after a rejected loader');
  assert.equal(buttonsRendered, true, 'button state must update after a rejected loader');
});

test('refreshBuilderGoalRuns multiple rejected loaders still resolve the refresh method', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => {
    throw new Error('accounts failed');
  };
  planner.loadBuilderGoalMachines = async () => {
    throw new Error('machines failed');
  };
  planner.loadBuilderGoalRuns = async () => {
    throw new Error('runs failed');
  };
  planner.loadScriptCatalog = async () => {
    throw new Error('catalog failed');
  };
  let statusRendered = false;
  planner.renderBuilderGoalClientStatus = () => { statusRendered = true; };
  planner.updateBuilderGoalRunButtonState = () => {};
  const originalError = console.error;
  console.error = () => {};

  try {
    await planner.refreshBuilderGoalRuns();
  } finally {
    console.error = originalError;
  }

  assert.equal(statusRendered, true);
});

test('refreshBuilderGoalRuns no unhandled rejection escapes the refresh method', async () => {
  const unhandled = [];
  const onUnhandled = reason => unhandled.push(reason);
  process.on('unhandledRejection', onUnhandled);
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.loadBuilderGoalAccounts = async () => {
    throw new Error('accounts failed');
  };
  planner.loadBuilderGoalMachines = async () => {};
  planner.loadBuilderGoalRuns = async () => {};
  planner.loadScriptCatalog = async () => {};
  planner.renderBuilderGoalClientStatus = () => {};
  planner.updateBuilderGoalRunButtonState = () => {};
  const originalError = console.error;
  console.error = () => {};

  try {
    await planner.refreshBuilderGoalRuns();
  } finally {
    console.error = originalError;
    process.removeListener('unhandledRejection', onUnhandled);
  }

  assert.deepEqual(unhandled, [], 'no rejection may escape refreshBuilderGoalRuns');
});

test('builder goal bundle version markers agree on 33', () => {
  const source = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js'),
    'utf8'
  );
  const template = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/templates/debugger.html'),
    'utf8'
  );
  const jsVersion = AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION;
  assert.equal(jsVersion, '33');
  assert.ok(
    source.includes(`BUILDER_GOAL_BUNDLE_VERSION = '${jsVersion}'`),
    'JS source must declare the same bundle version'
  );
  assert.ok(
    template.includes(`data-planner-bundle-version="${jsVersion}"`),
    'debugger.html must carry the same data-planner-bundle-version'
  );
  assert.ok(
    template.includes(`/js/account-task-planner.js?v=${jsVersion}`),
    'debugger.html must reference the same script version'
  );
});

// --- Status label tests ---

function run(status) {
  return { status };
}

test('formatBuilderGoalStatus returns correct labels for all statuses', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('AWAITING_EXECUTOR_CONFIRMATION')),
    'Executor request sent; awaiting confirmation.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('RECONCILING')),
    'Executor request sent; awaiting confirmation.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('ASSIGNED')),
    'Executor assigned the task; waiting for confirmed script start.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('QUEUED')),
    'Task accepted and queued by the executor.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('STOPPING')),
    'Stop requested; awaiting executor confirmation.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('PUBLISH_FAILED')),
    'Task publish failed.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('FAILED')),
    'Task execution failed.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('CANCELLED')),
    'Task cancelled.'
  );
});

test('formatBuilderGoalStatus returns null for unknown status', () => {
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(run('UNKNOWN_STATUS')), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(run('RUNNING')), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(run('COMPLETED')), null);
});

test('formatBuilderGoalStatus returns null for null/missing run', () => {
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(null), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus({}), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus({ status: null }), null);
});

test('formatBuilderGoalStatus handles case-insensitive status input', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('reconciling')),
    'Executor request sent; awaiting confirmation.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('queued')),
    'Task accepted and queued by the executor.'
  );
});

// --- Active publish state tests ---

test('isActivePublishState returns true for all active pre-start statuses', () => {
  const activeStatuses = [
    'PUBLISHING',
    'AWAITING_EXECUTOR_CONFIRMATION',
    'RECONCILING',
    'RECEIVED',
    'PENDING',
    'ACCEPTED',
    'QUEUED',
    'ASSIGNED',
    'RUNNING',
    'WAITING_FOR_DEBUG_BIND',
    'STOPPING',
  ];
  for (const status of activeStatuses) {
    assert.equal(
      AccountTaskPlanner.isActivePublishState(run(status)),
      true,
      `expected ${status} to be active`
    );
  }
});

test('isActivePublishState returns false for terminal/completed statuses', () => {
  assert.equal(AccountTaskPlanner.isActivePublishState(run('COMPLETED')), false);
  assert.equal(AccountTaskPlanner.isActivePublishState(run('FAILED')), false);
  assert.equal(AccountTaskPlanner.isActivePublishState(run('CANCELLED')), false);
  assert.equal(AccountTaskPlanner.isActivePublishState(run('PUBLISH_FAILED')), false);
});

test('isActivePublishState returns false for null/missing run', () => {
  assert.equal(AccountTaskPlanner.isActivePublishState(null), false);
  assert.equal(AccountTaskPlanner.isActivePublishState({}), false);
});

// --- Bundle marker tests ---

test('BUILDER_GOAL_BUNDLE_VERSION is 33', () => {
  assert.equal(AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION, '33');
});

test('ACTIVE_PRE_START_STATUSES includes RECONCILING and AWAITING_EXECUTOR_CONFIRMATION', () => {
  assert.equal(AccountTaskPlanner.ACTIVE_PRE_START_STATUSES.has('RECONCILING'), true);
  assert.equal(AccountTaskPlanner.ACTIVE_PRE_START_STATUSES.has('AWAITING_EXECUTOR_CONFIRMATION'), true);
});

test('BUILDER_GOAL_STATUS_LABELS contains all required labels', () => {
  const labels = AccountTaskPlanner.BUILDER_GOAL_STATUS_LABELS;
  assert.equal(labels.AWAITING_EXECUTOR_CONFIRMATION, 'Executor request sent; awaiting confirmation.');
  assert.equal(labels.RECONCILING, 'Executor request sent; awaiting confirmation.');
  assert.equal(labels.ASSIGNED, 'Executor assigned the task; waiting for confirmed script start.');
  assert.equal(labels.QUEUED, 'Task accepted and queued by the executor.');
  assert.equal(labels.STOPPING, 'Stop requested; awaiting executor confirmation.');
  assert.equal(labels.PUBLISH_FAILED, 'Task publish failed.');
  assert.equal(labels.FAILED, 'Task execution failed.');
  assert.equal(labels.CANCELLED, 'Task cancelled.');
});
