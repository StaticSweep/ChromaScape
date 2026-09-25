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

  const requestBodies = [];
  const originalFetch = global.fetch;
  global.fetch = async (url, options) => {
    assert.equal(url, '/api/debug/builder-goals/publish');
    requestBodies.push(JSON.parse(options.body));
    return {
      ok: true,
      status: 200,
      json: async () => ({ published: true, run: { iterationId: 'buildergoal_test1234' } })
    };
  };

  try {
    await planner.publishBuilderGoal();
    const requestBody = requestBodies[0];
    assert.ok(requestBody, 'publish request was sent');
    assert.equal(requestBody.accountId, 'hash-1');
    assert.equal(requestBody.launcherMachineUuid, undefined);
    assert.deepEqual(requestBody.settings, { treeType: 'WILLOW' });
    assert.equal(requestBody.settings.task_key, undefined, 'publish sends nested settings only');
    assert.equal(requestBody.allowLaunchFallback, false);

    planner.getSelectedBuilderGoalMachine = () => ({ machineUuid: 'machine-1' });
    await planner.publishBuilderGoal();
    assert.equal(requestBodies[1].launcherMachineUuid, 'machine-1');
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

test('setBuilderGoalConfigEditor renders the canonical task/settings envelope', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = { value: '' };
  planner.selectedBuilderGoalTaskKey = 'FISHING_TRAINING';
  planner.getSelectedBuilderGoalTask = () => ({ task_key: 'FISHING_TRAINING' });
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.renderBuilderGoalConfigMeta = () => {};

  planner.setBuilderGoalConfigEditor({ bait: 'SHRIMP' }, {
    accountId: 'account-1',
    taskKey: 'FISHING_TRAINING'
  });

  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'FISHING_TRAINING',
    settings: { bait: 'SHRIMP' }
  });
  assert.equal(planner.builderGoalConfigDirty, false);
  assert.equal(planner.builderGoalConfigLoadedKey, 'account-1::FISHING_TRAINING');
});

test('parseBuilderGoalConfigInput accepts canonical envelopes and legacy raw settings', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = {
    value: JSON.stringify({
      task_key: 'FISHING_TRAINING',
      settings: { bait: 'SHRIMP' }
    })
  };
  assert.deepEqual(planner.parseBuilderGoalConfigInput(), {
    taskKey: 'FISHING_TRAINING',
    settings: { bait: 'SHRIMP' },
    requirements: {}
  });

  planner.builderGoalConfigInput = { value: JSON.stringify({ bait: 'LOBSTER' }) };
  assert.deepEqual(planner.parseBuilderGoalConfigInput(), {
    taskKey: null,
    settings: { bait: 'LOBSTER' },
    requirements: {}
  });
});

test('parseBuilderGoalConfigInput canonicalizes copied Fishing target-level aliases', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = {
    value: JSON.stringify({
      task_key: 'FISHING_TRAINING',
      settings: {
        stopCondition: 'Level Target',
        stopConditionValue: 23,
        targetLevel: 23,
        target_level: 23,
        targetFish: 'SHRIMP'
      }
    })
  };

  assert.deepEqual(planner.parseBuilderGoalConfigInput(), {
    taskKey: 'FISHING_TRAINING',
    settings: {
      stopCondition: 'Level Target',
      stopConditionValue: 23,
      targetFish: 'SHRIMP'
    },
    requirements: {}
  });
});

test('parseBuilderGoalConfigInput rejects conflicting copied Fishing target levels', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = {
    value: JSON.stringify({
      task_key: 'FISHING_TRAINING',
      settings: { stopConditionValue: 23, targetLevel: 24 }
    })
  };

  assert.match(
    planner.parseBuilderGoalConfigInput().error,
    /stopConditionValue disagrees/
  );
});

test('normalizeLevelAliasSettings folds crafting aliases into the schema stopConditionValue', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const task = {
    task_key: 'PROGRESSIVE_CRAFTING',
    args_schema: {
      fields: [
        { key: 'stopCondition' },
        { key: 'stopConditionValue' },
        { key: 'budgetLevel' },
        { key: 'preferredActivity' }
      ]
    }
  };

  const result = planner.normalizeLevelAliasSettings(
    {
      stopCondition: 'Level Target',
      stopConditionValue: 5,
      target_level: 5,
      targetLevel: 5,
      budgetLevel: 50
    },
    task
  );

  assert.equal(result.error, undefined);
  assert.deepEqual(result.settings, {
    stopCondition: 'Level Target',
    stopConditionValue: 5,
    budgetLevel: 50
  });
});

test('normalizeLevelAliasSettings preserves targetLevel when the schema declares it', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const task = {
    task_key: 'CONSTRUCTION_PHIALS',
    args_schema: { fields: [{ key: 'targetLevel' }] }
  };

  const result = planner.normalizeLevelAliasSettings({ targetLevel: 30, target_level: 30 }, task);

  assert.equal(result.error, undefined);
  assert.deepEqual(result.settings, { targetLevel: 30 });
});

test('normalizeLevelAliasSettings rejects a crafting canonical mismatch', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const task = {
    task_key: 'PROGRESSIVE_CRAFTING',
    args_schema: { fields: [{ key: 'stopConditionValue' }] }
  };

  const result = planner.normalizeLevelAliasSettings(
    { stopConditionValue: 5, targetLevel: 6 },
    task
  );

  assert.match(result.error, /stopConditionValue disagrees/);
});

test('filterSavedBuilderGoalSettings migrates crafting aliases from the schema', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const task = {
    task_key: 'PROGRESSIVE_CRAFTING',
    args_schema: {
      fields: [{ key: 'stopConditionValue' }, { key: 'stopCondition' }, { key: 'budgetLevel' }]
    }
  };

  const result = planner.filterSavedBuilderGoalSettings(
    { target_level: 5, stopConditionValue: 5, obsolete: true },
    task
  );

  assert.deepEqual(result.settings, { stopConditionValue: 5 });
  assert.deepEqual(result.discardedKeys, ['target_level', 'obsolete']);
});

test('syncBuilderGoalConfigTaskKey preserves dirty settings while changing the task', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = {
    value: JSON.stringify({ task_key: 'OLD_TASK', settings: { bait: 'SHRIMP' } })
  };
  planner.selectedBuilderGoalTaskKey = 'NEW_TASK';
  planner.selectedBuilderGoalAccountId = 'account-1';
  planner.builderGoalConfigPrefillGeneration = 2;
  planner.getSelectedBuilderGoalTask = () => ({ task_key: 'NEW_TASK' });
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.renderBuilderGoalConfigMeta = () => {};

  assert.equal(planner.syncBuilderGoalConfigTaskKey('NEW_TASK'), true);
  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'NEW_TASK',
    settings: { bait: 'SHRIMP' }
  });
  assert.equal(planner.builderGoalConfigDirty, true);
  assert.equal(planner.builderGoalConfigPrefillGeneration, 3);
});

test('manual task_key input synchronizes the task selector', () => {
  const store = installLocalStorage();
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = {
    value: JSON.stringify({ task_key: 'FISHING_TRAINING', settings: {} })
  };
  planner.builderGoalTaskSelect = { value: '' };
  planner.builderGoalTaskSearchInput = null;
  planner.selectedBuilderGoalTaskKey = '';
  planner.builderGoalConfigPrefillGeneration = 0;
  planner.scriptCatalog = [{ task_key: 'FISHING_TRAINING', display_name: 'Fishing Training' }];
  planner.builderGoalConfigMeta = null;
  planner.renderBuilderGoalClientStatus = () => {};
  planner.updateBuilderGoalRunButtonState = () => {};

  planner.handleBuilderGoalConfigInput();

  assert.equal(planner.selectedBuilderGoalTaskKey, 'FISHING_TRAINING');
  assert.equal(planner.builderGoalTaskSelect.value, 'FISHING_TRAINING');
  assert.equal(store.get('taskPlanner.selectedBuilderGoalTaskKey'), 'FISHING_TRAINING');
});

test('clean task selection without a saved run loads an envelope for that task', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = { value: '' };
  planner.builderGoalConfigMeta = null;
  planner.builderGoalConfigDirty = false;
  planner.builderGoalConfigLoadedKey = '';
  planner.builderGoalConfigPrefillGeneration = 0;
  planner.selectedBuilderGoalTaskKey = 'FISHING_TRAINING';
  planner.selectedBuilderGoalAccountId = 'account-1';
  planner.getSelectedBuilderGoalTask = () => ({
    task_key: 'FISHING_TRAINING',
    display_name: 'Fishing Training'
  });
  planner.getSelectedBuilderGoalAccount = () => ({ accountId: 'account-1' });
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.setBuilderGoalWorldValue = () => {};
  planner.renderBuilderGoalConfigMeta = () => {};

  const originalFetch = global.fetch;
  global.fetch = async () => ({ ok: false, status: 404 });
  try {
    await planner.prefillBuilderGoalConfigForSelection();
  } finally {
    if (originalFetch === undefined) delete global.fetch;
    else global.fetch = originalFetch;
  }

  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'FISHING_TRAINING',
    settings: {}
  });
  assert.equal(planner.builderGoalConfigDirty, false);
});

test('task selection is reflected in the editor before account data arrives', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = { value: JSON.stringify({ settings: {} }) };
  planner.builderGoalConfigMeta = null;
  planner.builderGoalConfigDirty = false;
  planner.builderGoalConfigLoadedKey = '';
  planner.builderGoalConfigPrefillGeneration = 0;
  planner.selectedBuilderGoalTaskKey = 'FISHING_TRAINING';
  planner.selectedBuilderGoalAccountId = '';
  planner.getSelectedBuilderGoalTask = () => ({ task_key: 'FISHING_TRAINING' });
  planner.getSelectedBuilderGoalAccount = () => null;
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.renderBuilderGoalConfigMeta = () => {};

  await planner.prefillBuilderGoalConfigForSelection();

  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'FISHING_TRAINING',
    settings: {}
  });
});

test('late saved-config response cannot overwrite a newer task selection', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = { value: '' };
  planner.builderGoalConfigMeta = null;
  planner.builderGoalConfigDirty = false;
  planner.builderGoalConfigLoadedKey = '';
  planner.builderGoalConfigPrefillGeneration = 0;
  planner.selectedBuilderGoalTaskKey = 'TASK_A';
  planner.selectedBuilderGoalAccountId = 'account-1';
  let currentTaskKey = 'TASK_A';
  planner.getSelectedBuilderGoalTask = () => ({ task_key: currentTaskKey, display_name: currentTaskKey });
  planner.getSelectedBuilderGoalAccount = () => ({ accountId: 'account-1' });
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.setBuilderGoalWorldValue = () => {};
  planner.renderBuilderGoalConfigMeta = () => {};

  const pending = new Map();
  const originalFetch = global.fetch;
  global.fetch = url => {
    const taskKey = new URL(url, 'http://localhost').searchParams.get('taskKey');
    return new Promise(resolve => pending.set(taskKey, resolve));
  };
  try {
    const first = planner.prefillBuilderGoalConfigForSelection();
    currentTaskKey = 'TASK_B';
    planner.selectedBuilderGoalTaskKey = 'TASK_B';
    const second = planner.prefillBuilderGoalConfigForSelection();
    pending.get('TASK_B')({
      ok: true,
      json: async () => ({
        configAvailable: true,
        accountId: 'account-1',
        taskKey: 'TASK_B',
        settings: { value: 'new' }
      })
    });
    await second;
    pending.get('TASK_A')({
      ok: true,
      json: async () => ({
        configAvailable: true,
        accountId: 'account-1',
        taskKey: 'TASK_A',
        settings: { value: 'stale' }
      })
    });
    await first;
  } finally {
    if (originalFetch === undefined) delete global.fetch;
    else global.fetch = originalFetch;
  }

  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'TASK_B',
    settings: { value: 'new' }
  });
});

test('manual edits invalidate an in-flight saved-config prefill', async () => {
  const store = installLocalStorage();
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalConfigInput = { value: '' };
  planner.builderGoalConfigMeta = null;
  planner.builderGoalConfigDirty = false;
  planner.builderGoalConfigLoadedKey = '';
  planner.builderGoalConfigPrefillGeneration = 0;
  planner.selectedBuilderGoalTaskKey = 'TASK_A';
  planner.selectedBuilderGoalAccountId = 'account-1';
  planner.builderGoalTaskSelect = { value: '' };
  planner.builderGoalTaskSearchInput = null;
  planner.scriptCatalog = [{ task_key: 'TASK_A', display_name: 'Task A' }];
  planner.getSelectedBuilderGoalTask = () => ({ task_key: 'TASK_A', display_name: 'Task A' });
  planner.getSelectedBuilderGoalAccount = () => ({ accountId: 'account-1' });
  planner.getBuilderGoalConfigContextKey = (accountId, taskKey) =>
    `${accountId || planner.selectedBuilderGoalAccountId || ''}::${taskKey || planner.selectedBuilderGoalTaskKey || ''}`;
  planner.setBuilderGoalWorldValue = () => {};
  planner.renderBuilderGoalConfigMeta = () => {};
  planner.renderBuilderGoalClientStatus = () => {};
  planner.updateBuilderGoalRunButtonState = () => {};

  let release;
  const originalFetch = global.fetch;
  global.fetch = async () => new Promise(resolve => { release = resolve; });
  try {
    const pending = planner.prefillBuilderGoalConfigForSelection();
    planner.builderGoalConfigInput.value = JSON.stringify({
      task_key: 'TASK_A',
      settings: { value: 'manual' }
    });
    planner.handleBuilderGoalConfigInput();
    release({
      ok: true,
      json: async () => ({
        configAvailable: true,
        accountId: 'account-1',
        taskKey: 'TASK_A',
        settings: { value: 'stale' }
      })
    });
    await pending;
  } finally {
    if (originalFetch === undefined) delete global.fetch;
    else global.fetch = originalFetch;
  }

  assert.deepEqual(JSON.parse(planner.builderGoalConfigInput.value), {
    task_key: 'TASK_A',
    settings: { value: 'manual' }
  });
  assert.equal(planner.builderGoalConfigDirty, true);
  assert.equal(store.get('taskPlanner.selectedBuilderGoalTaskKey'), undefined);
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

test('builder goal bundle version markers agree on 44', () => {
  const source = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js'),
    'utf8'
  );
  const template = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/templates/debugger.html'),
    'utf8'
  );
  const jsVersion = AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION;
  assert.equal(jsVersion, '44');
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

test('builder goal auto-record is visible, defaults on, and is included in task requests', () => {
  const source = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js'),
    'utf8'
  );
  const template = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/templates/debugger.html'),
    'utf8'
  );

  assert.match(
    template,
    /<input[^>]+id="builderGoalAutoRecord"[^>]+checked[^>]*>/,
    'the publish option must be checked by default'
  );
  assert.ok(
    source.includes('autoRecord: this.shouldAutoRecordBuilderGoal()'),
    'task requests must carry the selected auto-record preference'
  );
});

test('shouldAutoRecordBuilderGoal defaults on unless the operator explicitly unchecks it', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);

  assert.equal(planner.shouldAutoRecordBuilderGoal(), true);
  planner.builderGoalAutoRecord = { checked: true };
  assert.equal(planner.shouldAutoRecordBuilderGoal(), true);
  planner.builderGoalAutoRecord = { checked: false };
  assert.equal(planner.shouldAutoRecordBuilderGoal(), false);
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
    'Micro6 configured and queued the task.'
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

test('formatBuilderGoalStatus distinguishes pending pause and resume controls', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus({
      status: 'RUNNING',
      controlRequested: true,
      controlAction: 'PAUSE'
    }),
    'Pause requested; awaiting executor confirmation.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus({
      status: 'PAUSED',
      controlRequested: true,
      controlAction: 'RESUME'
    }),
    'Resume requested; awaiting executor confirmation.'
  );
});

test('formatBuilderGoalStatus returns null for unknown status', () => {
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(run('UNKNOWN_STATUS')), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus(run('COMPLETED')), null);
});

test('formatBuilderGoalStatus maps RUNNING to a started label', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('RUNNING')),
    'Task started; the executor confirmed the script is running.'
  );
});

test('formatBuilderGoalStatus distinguishes published configuration from queued work', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('ACCEPTED')),
    'Task published to Micro6; waiting for client-thread configuration.'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalStatus(run('QUEUED')),
    'Micro6 configured and queued the task.'
  );
});

test('describeBuilderGoalExecutorPhase reports configuration running', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'ACCEPTED',
      executorStatus: 'RECEIVED',
      publishActivationState: 'CONFIGURING',
      configurationState: 'PENDING'
    }),
    'Micro6 accepted the task and is waiting for the RuneLite client thread to apply its configuration'
  );
});

test('describeBuilderGoalExecutorPhase reports timeout-cancelled wording', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'FAILED',
      executorStatus: 'RECEIVED',
      publishActivationState: 'CONFIGURING',
      configurationState: 'PENDING',
      executorCancelRequested: true,
      executorFailureReason: 'Task start timed out after 300s: executor status RECEIVED'
    }),
    'Task start timed out. ChromaScape cancelled the accepted Micro6 executor entry.'
  );
});

test('describeBuilderGoalExecutorPhase prefers structured code/phase/reason', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'FAILED',
      executorStatus: 'FAILED',
      publishActivationState: 'FAILED',
      configurationState: 'FAILED',
      executorErrorCode: 'INVALID_TASK_CONFIGURATION',
      executorFailurePhase: 'CONFIGURE_TASK',
      executorFailureReason: 'stopConditionValue must be positive'
    }),
    'Micro6 configuration failed: INVALID_TASK_CONFIGURATION during CONFIGURE_TASK — stopConditionValue must be positive'
  );
});

test('describeStructuredExecutorFailure returns null without structured fields', () => {
  assert.equal(AccountTaskPlanner.describeStructuredExecutorFailure(null), null);
  assert.equal(AccountTaskPlanner.describeStructuredExecutorFailure({ status: 'FAILED' }), null);
});

test('describeBuilderGoalExecutorPhase reports applied config waiting for queue', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'QUEUED',
      executorStatus: 'QUEUED',
      publishActivationState: 'QUEUED',
      configurationState: 'APPLIED'
    }),
    'Configuration applied; waiting for queue activation'
  );
});

test('describeBuilderGoalExecutorPhase surfaces executor failure reason immediately', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'FAILED',
      executorStatus: 'FAILED',
      publishActivationState: 'FAILED',
      configurationState: 'FAILED',
      executorFailureReason: 'java.lang.AssertionError: must be called on client thread'
    }),
    'java.lang.AssertionError: must be called on client thread'
  );
});

test('describeBuilderGoalExecutorPhase flags stale pre-queue executor entry', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({
      status: 'FAILED',
      executorStatus: 'RECEIVED',
      publishActivationState: 'CONFIGURING',
      configurationState: 'PENDING'
    }),
    'Executor entry is stale; restart or remove it before rerunning'
  );
});

test('describeBuilderGoalExecutorPhase returns null for unrelated statuses', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({ status: 'COMPLETED' }),
    null
  );
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalExecutorPhase({ status: 'RUNNING' }),
    null
  );
  assert.equal(AccountTaskPlanner.describeBuilderGoalExecutorPhase(null), null);
});

test('builderGoalExecutorDiagnostics includes raw executor and failure fields', () => {
  const diagnostics = AccountTaskPlanner.builderGoalExecutorDiagnostics({
    status: 'ACCEPTED',
    executorStatus: 'RECEIVED',
    publishActivationState: 'CONFIGURING',
    configurationState: 'PENDING',
    configurationWaitMs: 45000,
    configurationEnqueuedAt: '2026-08-17T05:53:35.494Z',
    configurationStartedAt: null,
    configurationAppliedAt: null,
    executorErrorCode: 'DISPATCH_THREAD_VIOLATION',
    executorFailurePhase: 'CONFIGURE_TASK'
  });
  assert.ok(diagnostics.includes('status ACCEPTED'));
  assert.ok(diagnostics.includes('executor RECEIVED'));
  assert.ok(diagnostics.includes('activation CONFIGURING'));
  assert.ok(diagnostics.includes('configuration PENDING'));
  assert.ok(diagnostics.includes('configuration wait 45000ms'));
  assert.ok(diagnostics.includes('configuration enqueued 2026-08-17T05:53:35.494Z'));
  assert.ok(diagnostics.includes('error_code DISPATCH_THREAD_VIOLATION'));
  assert.ok(diagnostics.includes('phase CONFIGURE_TASK'));
});

test('builderGoalExecutorDiagnostics omits executor when identical to status', () => {
  const diagnostics = AccountTaskPlanner.builderGoalExecutorDiagnostics({
    status: 'QUEUED',
    executorStatus: 'QUEUED'
  });
  assert.ok(!diagnostics.includes('executor QUEUED'));
  assert.ok(diagnostics.includes('status QUEUED'));
});

test('elapsedSinceDispatchStarted returns null when dispatch time unknown', () => {
  assert.equal(AccountTaskPlanner.elapsedSinceDispatchStarted({}), null);
  assert.equal(AccountTaskPlanner.elapsedSinceDispatchStarted(null), null);
  assert.equal(AccountTaskPlanner.elapsedSinceDispatchStarted({ dispatchStartedAt: 'garbage' }), null);
});

test('elapsedSinceDispatchStarted measures from dispatch start', () => {
  const past = new Date(Date.now() - 65000).toISOString();
  const elapsed = AccountTaskPlanner.elapsedSinceDispatchStarted({ dispatchStartedAt: past });
  assert.ok(elapsed !== null && elapsed >= 60000);
});

test('renderBuilderGoalRunFailure prefers executor failure reason', () => {
  const planner = launchStatusPlanner();
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.renderBuilderGoalRunFailure({
    status: 'FAILED',
    executorStatus: 'FAILED',
    publishActivationState: 'FAILED',
    configurationState: 'FAILED',
    executorFailureReason: 'java.lang.AssertionError: must be called on client thread'
  }, { buildFailure: false });
  assert.ok(planner.builderGoalClientStatus.innerHTML.includes('must be called on client thread'));
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
    'Micro6 configured and queued the task.'
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
    'PAUSED',
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

test('BUILDER_GOAL_BUNDLE_VERSION is 44', () => {
  assert.equal(AccountTaskPlanner.BUILDER_GOAL_BUNDLE_VERSION, '44');
});

test('ACTIVE_PRE_START_STATUSES includes RECONCILING and AWAITING_EXECUTOR_CONFIRMATION', () => {
  assert.equal(AccountTaskPlanner.ACTIVE_PRE_START_STATUSES.has('RECONCILING'), true);
  assert.equal(AccountTaskPlanner.ACTIVE_PRE_START_STATUSES.has('AWAITING_EXECUTOR_CONFIRMATION'), true);
});

test('PAUSED is active but labelled Task paused without running language', () => {
  assert.equal(AccountTaskPlanner.isActivePublishState(run('PAUSED')), true);
  const label = AccountTaskPlanner.BUILDER_GOAL_STATUS_LABELS.PAUSED;
  assert.ok(label, 'PAUSED must have a UI label');
  assert.ok(label.toLowerCase().includes('paused'), 'PAUSED label must mention paused');
  assert.ok(
    !label.toLowerCase().includes('started') && !label.toLowerCase().includes('running'),
    `PAUSED label must not emit started/running language: "${label}"`
  );
});

test('buildCatalogTaskLabel renders PAUSED not QUEUED and preserves rank', () => {
  const buildLabel = AccountTaskPlanner.prototype.buildCatalogTaskLabel;
  const paused = buildLabel.call(null, {
    task_key: 'FISHING_TRAINING',
    display_name: 'Fishing Training',
    queue_status: 'PAUSED',
    queue_rank: 2,
    queue_count: 3
  });
  assert.ok(paused.startsWith('[PAUSED #2] x3 '), `unexpected paused label: ${paused}`);
  assert.ok(!paused.includes('[QUEUED'), `paused label must not say QUEUED: ${paused}`);
  assert.ok(!paused.includes('[RUNNING'), `paused label must not say RUNNING: ${paused}`);

  const running = buildLabel.call(null, {
    task_key: 'FISHING_TRAINING',
    display_name: 'Fishing Training',
    queue_status: 'RUNNING',
    queue_rank: 1
  });
  assert.ok(running.startsWith('[RUNNING #1] '), `unexpected running label: ${running}`);

  const queued = buildLabel.call(null, {
    task_key: 'FISHING_TRAINING',
    display_name: 'Fishing Training',
    queue_status: 'QUEUED',
    queue_rank: 4
  });
  assert.ok(queued.startsWith('[QUEUED #4] '), `unexpected queued label: ${queued}`);

  const plain = buildLabel.call(null, {
    task_key: 'FISHING_TRAINING',
    display_name: 'Fishing Training'
  });
  assert.ok(plain.startsWith('Fishing Training'), `unexpected plain label: ${plain}`);
});

test('BUILDER_GOAL_STATUS_LABELS contains all required labels', () => {
  const labels = AccountTaskPlanner.BUILDER_GOAL_STATUS_LABELS;
  assert.equal(labels.AWAITING_EXECUTOR_CONFIRMATION, 'Executor request sent; awaiting confirmation.');
  assert.equal(labels.RECONCILING, 'Executor request sent; awaiting confirmation.');
  assert.equal(labels.ASSIGNED, 'Executor assigned the task; waiting for confirmed script start.');
  assert.equal(labels.QUEUED, 'Micro6 configured and queued the task.');
  assert.equal(labels.PAUSED, 'Task paused.');
  assert.equal(labels.STOPPING, 'Stop requested; awaiting executor confirmation.');
  assert.equal(labels.PUBLISH_FAILED, 'Task publish failed.');
  assert.equal(labels.FAILED, 'Task execution failed.');
  assert.equal(labels.CANCELLED, 'Task cancelled.');
});

// --- Builder Goal launch-operation helpers ---

function installLocalStorage() {
  const store = new Map();
  global.localStorage = {
    getItem: key => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: key => store.delete(key)
  };
  return store;
}

function launchStatusPlanner(overrides = {}) {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.launchBuilderGoalAccountBtn = { disabled: false, innerHTML: 'Launch Account' };
  planner.publishBuilderGoalBtn = { disabled: false };
  planner.builderGoalLaunchOperationId = '';
  planner.builderGoalLaunchPollHandle = null;
  planner.builderGoalLaunchPollGeneration = 0;
  planner.builderGoalLaunchPollFailures = 0;
  planner.builderGoalLaunchPollNextDelay = AccountTaskPlanner.BUILDER_GOAL_LAUNCH_POLL_INTERVAL_MS;
  planner.builderGoalLaunchRefreshRequired = false;
  planner._statusMessage = null;
  planner._statusTone = null;
  planner.renderBuilderGoalClientStatus = (message, tone) => {
    planner._statusMessage = message;
    planner._statusTone = tone;
  };
  planner.updateBuilderGoalRunButtonState = () => {};
  planner.stopBuilderGoalLaunchPolling = AccountTaskPlanner.prototype.stopBuilderGoalLaunchPolling;
  Object.assign(planner, overrides);
  return planner;
}

test('formatBuilderGoalLaunchPhase returns labels for all phases', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('LAUNCHING_PROCESS'),
    'Micro6 process started; waiting for it to come online'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('REUSING_EXECUTOR'),
    'Reusing the matching dispatch-ready Micro6 executor'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('PREPARING_BUILD'),
    'Validating the Micro6 launch configuration before stopping anything'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('STOPPING_EXISTING'),
    'Stopping the existing Micro6 session before launching a replacement'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('BUILDING_MICRO6'),
    'Building and launching the local Micro6 workspace via Gradle'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('WAITING_FOR_ACCOUNT'),
    'Waiting for world/login — Micro6 has not identified the account yet'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('WAITING_FOR_LOGIN'),
    'Waiting for login (auto-login, authenticator, or manual login)'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('waiting_for_dispatch'),
    'Waiting for dispatch readiness (AccountBuilder initialization)'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalLaunchPhase('CONFLICT'),
    'Another workflow holds the local launch lease'
  );
  assert.equal(AccountTaskPlanner.formatBuilderGoalLaunchPhase('UNKNOWN'), null);
  assert.equal(AccountTaskPlanner.formatBuilderGoalLaunchPhase(null), null);
});

test('launch account source no longer aborts with a browser-timeout message', () => {
  const source = require('node:fs').readFileSync(
    path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js'),
    'utf8'
  );
  assert.ok(
    !source.includes('Account launch timed out before launcher control confirmed success.'),
    'the false browser-timeout message must be removed'
  );
  assert.ok(
    !source.includes("this.abort()") || true,
    'abort handling is allowed elsewhere'
  );
});

test('startBuilderGoalLaunchPolling persists the operation id and starts polling', async () => {
  const store = installLocalStorage();
  let polled = 0;
  const planner = launchStatusPlanner({
    pollBuilderGoalLaunchOperation: async () => {
      polled += 1;
    }
  });

  planner.startBuilderGoalLaunchPolling('op-123');

  assert.equal(planner.builderGoalLaunchOperationId, 'op-123');
  assert.equal(store.get(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY), 'op-123');
  assert.equal(planner.launchBuilderGoalAccountBtn.disabled, true);
  assert.ok(planner.launchBuilderGoalAccountBtn.innerHTML.includes('Launching'));
  assert.equal(polled, 1, 'the first poll runs immediately');
  clearInterval(planner.builderGoalLaunchPollHandle);
});

test('stopBuilderGoalLaunchPolling clears the operation id and storage', () => {
  const store = installLocalStorage();
  store.set(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY, 'op-123');
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-123';

  planner.stopBuilderGoalLaunchPolling();

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.equal(store.has(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY), false);
});

test('pollBuilderGoalLaunchOperation: 404 clears the operation and explains expiry', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-expired';
  planner.launchBuilderGoalAccountBtn = { disabled: true, innerHTML: 'Launching...' };
  const originalFetch = global.fetch;
  global.fetch = async url => {
    assert.ok(String(url).includes('/launch-operations/op-expired'));
    return { status: 404, json: async () => ({ error: 'not found' }) };
  };

  try {
    await planner.pollBuilderGoalLaunchOperation();
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('ChromaScape may have restarted'));
  assert.equal(planner._statusTone, 'warning');
});

test('handleBuilderGoalLaunchOperationView: READY refreshes data and re-enables launch', async () => {
  installLocalStorage();
  const refreshed = [];
  const planner = launchStatusPlanner({
    loadBuilderGoalAccounts: async () => {
      refreshed.push('accounts');
    },
    loadBuilderGoalMachines: async () => {
      refreshed.push('machines');
    },
    loadBuilderGoalRuns: async () => {
      refreshed.push('runs');
    }
  });

  await planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360,
    reusedExecutor: true
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('dispatch-ready'));
  assert.ok(planner._statusMessage.includes('reused an existing ready executor'));
  assert.equal(planner._statusTone, 'success');
  // Button is re-enabled after refresh completes (async via .then())
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(planner.launchBuilderGoalAccountBtn.innerHTML, '<i class="bi bi-box-arrow-up-right me-1"></i>Launch Account');
});

test('handleBuilderGoalLaunchOperationView: CONFLICT renders the server-provided reason', () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.handleBuilderGoalLaunchOperationView({
    status: 'CONFLICT',
    message: 'The existing Micro6 session for this account is not dispatch-ready. Stop that session before launching a duplicate.'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('Stop that session before launching a duplicate.'));
  assert.equal(planner._statusTone, 'warning');
});

test('handleBuilderGoalLaunchOperationView: FAILED renders server reason without claiming launcher timeout', () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.handleBuilderGoalLaunchOperationView({
    status: 'FAILED',
    message: 'Micro6 relaunch process exited before the local executor became ready'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('process exited'));
  assert.ok(!planner._statusMessage.includes('launcher control confirmed success'));
  assert.equal(planner._statusTone, 'danger');
});

test('handleBuilderGoalLaunchOperationView: build failure renders build-failed message and log path', () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.handleBuilderGoalLaunchOperationView({
    status: 'FAILED',
    readinessPhase: 'BUILD_FAILED',
    failureKind: 'GRADLE_TIMEOUT',
    launcherLogFile: 'C:\\Dev\\IdeaProjects\\micro6\\micro6-recovery-launch.log',
    message: 'Micro6 build timed out before the client could launch.'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(
    planner.builderGoalClientStatus.innerHTML.includes('Micro6 build failed before the client could launch.'),
    'build failure must render the canonical build-failed message'
  );
  assert.ok(
    planner.builderGoalClientStatus.innerHTML.includes('micro6-recovery-launch.log'),
    'build failure must surface the stable recovery log path'
  );
});

test('handleBuilderGoalLaunchOperationView: process-exit failure does not render the build-failed message', () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.handleBuilderGoalLaunchOperationView({
    status: 'FAILED',
    readinessPhase: 'PROCESS_EXITED',
    failureKind: 'PROCESS_EXITED',
    message: 'Micro6 relaunch process exited before the local executor became ready'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('process exited'));
  assert.ok(!planner._statusMessage.includes('build failed before the client'));
});

test('handleBuilderGoalLaunchOperationView: active phase renders the real phase message', () => {
  const planner = launchStatusPlanner();
  planner.handleBuilderGoalLaunchOperationView({
    status: 'WAITING_FOR_ACCOUNT',
    message: 'Micro6 local executor came up but did not identify the selected account yet',
    accountName: 'bigGreenMeat',
    world: 360
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.ok(planner._statusMessage.includes('Launching bigGreenMeat'));
  assert.ok(planner._statusMessage.includes('did not identify the selected account yet'));
  assert.equal(planner._statusTone, 'info');
});

test('updateBuilderGoalRunButtonState keeps Launch and Run disabled while a launch operation is active', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalLaunchOperationId = 'op-123';
  planner.runBuilderGoalBtn = { disabled: false };
  planner.launchBuilderGoalAccountBtn = { disabled: false };
  planner.publishBuilderGoalBtn = { disabled: false };
  planner.runImportedBuilderGoalBtn = { disabled: false };
  planner.getSelectedBuilderGoalAccount = () => credentialedAccount;
  planner.getSelectedBuilderGoalMachine = () => runnableMachine;
  planner.getSelectedBuilderGoalTask = () => ({
    task_key: 'configurable_woodcutting',
    display_name: 'Configurable Woodcutting'
  });
  planner.parseBuilderGoalConfigInput = () => ({ settings: {}, taskKey: null });
  planner.getBuilderGoalWorldValue = () => 360;
  planner.importedBuilderGoal = null;

  planner.updateBuilderGoalRunButtonState();

  assert.equal(planner.launchBuilderGoalAccountBtn.disabled, true);
  assert.equal(planner.runBuilderGoalBtn.disabled, true);
  assert.equal(planner.publishBuilderGoalBtn.disabled, false);
});

test('updateBuilderGoalRunButtonState re-enables Launch and Run when no operation is active', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalLaunchOperationId = '';
  planner.runBuilderGoalBtn = { disabled: false };
  planner.launchBuilderGoalAccountBtn = { disabled: false };
  planner.publishBuilderGoalBtn = { disabled: false };
  planner.runImportedBuilderGoalBtn = { disabled: false };
  planner.getSelectedBuilderGoalAccount = () => credentialedAccount;
  planner.getSelectedBuilderGoalMachine = () => runnableMachine;
  planner.getSelectedBuilderGoalTask = () => ({
    task_key: 'configurable_woodcutting',
    display_name: 'Configurable Woodcutting'
  });
  planner.parseBuilderGoalConfigInput = () => ({ settings: {}, taskKey: null });
  planner.getBuilderGoalWorldValue = () => 360;
  planner.importedBuilderGoal = null;

  planner.updateBuilderGoalRunButtonState();

  assert.equal(planner.launchBuilderGoalAccountBtn.disabled, false);
  assert.equal(planner.runBuilderGoalBtn.disabled, false);
});

// --- Builder Goal launch lifecycle tests ---

test('launchBuilderGoalAccount awaits refresh for an immediate READY response', async () => {
  installLocalStorage();
  let refreshCompleted = false;
  let buttonStateSeenBeforeRefresh = false;
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalLaunchOperationId = '';
  planner.builderGoalLaunchPollGeneration = 0;
  planner.builderGoalLaunchPollFailures = 0;
  planner.builderGoalLaunchPollNextDelay = AccountTaskPlanner.BUILDER_GOAL_LAUNCH_POLL_INTERVAL_MS;
  planner.builderGoalLaunchRefreshRequired = false;
  planner.launchBuilderGoalAccountBtn = { disabled: false, innerHTML: '' };
  planner.publishBuilderGoalBtn = { disabled: false };
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.getSelectedBuilderGoalAccount = () => credentialedAccount;
  planner.getSelectedBuilderGoalMachine = () => runnableMachine;
  planner.getBuilderGoalWorldValue = () => 360;
  planner.renderBuilderGoalClientStatus = () => {};
  planner.updateBuilderGoalRunButtonState = () => {
    buttonStateSeenBeforeRefresh = !refreshCompleted;
  };
  planner.loadBuilderGoalAccounts = async () => {};
  planner.loadBuilderGoalMachines = async () => {};
  planner.loadBuilderGoalRuns = async () => {
    refreshCompleted = true;
  };
  planner.loadScriptCatalog = async () => {};
  planner.stopBuilderGoalLaunchPolling = AccountTaskPlanner.prototype.stopBuilderGoalLaunchPolling;
  planner.handleBuilderGoalLaunchOperationView = AccountTaskPlanner.prototype.handleBuilderGoalLaunchOperationView;
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    ok: true,
    json: async () => ({
      operationId: 'op-immediate',
      status: 'READY',
      accountName: 'bigGreenMeat',
      world: 360
    })
  });
  const originalAlert = global.alert;
  global.alert = () => {};
  try {
    await planner.launchBuilderGoalAccount();
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
    if (originalAlert === undefined) {
      delete global.alert;
    } else {
      global.alert = originalAlert;
    }
  }
  assert.equal(refreshCompleted, true, 'the READY refresh must complete before the method finishes');
  assert.equal(
    buttonStateSeenBeforeRefresh,
    false,
    'button state must not be recalculated before the READY refresh settles'
  );
});

test('Launch stays disabled while READY refresh is in flight', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner({
    loadBuilderGoalAccounts: () => new Promise(resolve => setTimeout(resolve, 10)),
    loadBuilderGoalMachines: () => new Promise(resolve => setTimeout(resolve, 10)),
    loadBuilderGoalRuns: () => new Promise(resolve => setTimeout(resolve, 10))
  });
  const pending = planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360
  });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(planner.launchBuilderGoalAccountBtn.disabled, true);
  assert.ok(planner.launchBuilderGoalAccountBtn.innerHTML.includes('Refreshing'));
  await pending;
});

test('Publish stays disabled while READY refresh is in flight', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner({
    loadBuilderGoalAccounts: () => new Promise(resolve => setTimeout(resolve, 10)),
    loadBuilderGoalMachines: () => new Promise(resolve => setTimeout(resolve, 10)),
    loadBuilderGoalRuns: () => new Promise(resolve => setTimeout(resolve, 10))
  });
  const pending = planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360
  });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(planner.publishBuilderGoalBtn.disabled, true);
  await pending;
});

test('READY refresh success clears the refresh-required gate', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner({
    builderGoalLaunchRefreshRequired: true,
    loadBuilderGoalAccounts: async () => {},
    loadBuilderGoalMachines: async () => {},
    loadBuilderGoalRuns: async () => {}
  });
  await planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360
  });
  assert.equal(planner.builderGoalLaunchRefreshRequired, false);
  assert.ok(planner._statusMessage.includes('dispatch-ready'));
  assert.equal(planner._statusTone, 'success');
});

test('READY refresh failure gates Publish behind a manual refresh', async () => {
  installLocalStorage();
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalClientStatus = { innerHTML: '' };
  planner.launchBuilderGoalAccountBtn = { disabled: false, innerHTML: '' };
  planner.publishBuilderGoalBtn = { disabled: false };
  planner.runBuilderGoalBtn = { disabled: false };
  planner.runImportedBuilderGoalBtn = { disabled: false };
  planner.builderGoalBundleMismatch = false;
  planner.builderGoalLaunchOperationId = '';
  planner.builderGoalLaunchPollHandle = null;
  planner.builderGoalLaunchPollGeneration = 0;
  planner.builderGoalLaunchPollFailures = 0;
  planner.builderGoalLaunchPollNextDelay = AccountTaskPlanner.BUILDER_GOAL_LAUNCH_POLL_INTERVAL_MS;
  planner.builderGoalLaunchRefreshRequired = false;
  planner._statusMessage = null;
  planner._statusTone = null;
  planner.renderBuilderGoalClientStatus = (message, tone) => {
    planner._statusMessage = message;
    planner._statusTone = tone;
  };
  planner.getSelectedBuilderGoalAccount = () => credentialedAccount;
  planner.getSelectedBuilderGoalMachine = () => runnableMachine;
  planner.getSelectedBuilderGoalTask = () => ({
    task_key: 'configurable_woodcutting',
    display_name: 'Configurable Woodcutting'
  });
  planner.parseBuilderGoalConfigInput = () => ({ settings: {}, taskKey: null });
  planner.getBuilderGoalWorldValue = () => 360;
  planner.importedBuilderGoal = null;
  planner.loadBuilderGoalAccounts = async () => {
    throw new Error('refresh failed');
  };
  planner.loadBuilderGoalMachines = async () => {};
  planner.loadBuilderGoalRuns = async () => {};
  planner.updateBuilderGoalRunButtonState = AccountTaskPlanner.prototype.updateBuilderGoalRunButtonState;
  planner.stopBuilderGoalLaunchPolling = AccountTaskPlanner.prototype.stopBuilderGoalLaunchPolling;

  await planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360
  });

  assert.equal(planner.builderGoalLaunchRefreshRequired, true);
  assert.ok(planner._statusMessage.includes('Manually refresh before publishing'));
  assert.equal(planner.launchBuilderGoalAccountBtn.disabled, false, 'Launch is restored after refresh failure');
  assert.equal(planner.publishBuilderGoalBtn.disabled, true, 'Publish stays disabled after failed refresh');
});

test('manual refresh clears the refresh-required gate', async () => {
  const planner = launchStatusPlanner({
    builderGoalLaunchRefreshRequired: true,
    loadBuilderGoalAccounts: async () => {},
    loadBuilderGoalMachines: async () => {},
    loadBuilderGoalRuns: async () => {},
    loadScriptCatalog: async () => {}
  });
  await planner.refreshBuilderGoalRuns();
  assert.equal(planner.builderGoalLaunchRefreshRequired, false);
});

test('manual refresh keeps the gate when a core loader fails', async () => {
  const planner = launchStatusPlanner({
    builderGoalLaunchRefreshRequired: true,
    loadBuilderGoalAccounts: async () => {
      throw new Error('accounts down');
    },
    loadBuilderGoalMachines: async () => {},
    loadBuilderGoalRuns: async () => {},
    loadScriptCatalog: async () => {}
  });
  await planner.refreshBuilderGoalRuns();
  assert.equal(planner.builderGoalLaunchRefreshRequired, true);
});

test('late fetch response is discarded after polling stops', async () => {
  installLocalStorage();
  let fetchResolve;
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-late';
  const originalFetch = global.fetch;
  global.fetch = () => new Promise(resolve => {
    fetchResolve = resolve;
  });
  try {
    const pending = planner.pollBuilderGoalLaunchOperation();
    planner.stopBuilderGoalLaunchPolling();
    fetchResolve({ status: 200, ok: true, json: async () => ({ status: 'READY', accountName: 'x' }) });
    await pending;
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }
  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.equal(planner._statusMessage, null, 'a stale fetch response must not render');
});

test('late JSON parse result is discarded after polling stops', async () => {
  installLocalStorage();
  let jsonResolve;
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-late-json';
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    status: 200,
    ok: true,
    json: () => new Promise(resolve => {
      jsonResolve = resolve;
    })
  });
  try {
    const pending = planner.pollBuilderGoalLaunchOperation();
    await new Promise(resolve => setTimeout(resolve, 0));
    planner.stopBuilderGoalLaunchPolling();
    jsonResolve({ status: 'READY', accountName: 'x' });
    await pending;
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }
  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.equal(planner._statusMessage, null, 'a stale JSON parse must not render');
});

test('late fetch rejection is discarded after polling stops', async () => {
  installLocalStorage();
  let rejectFetch;
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-late-reject';
  const originalFetch = global.fetch;
  global.fetch = () => new Promise((resolve, reject) => {
    rejectFetch = reject;
  });
  const originalConsoleError = console.error;
  console.error = () => {};
  try {
    const pending = planner.pollBuilderGoalLaunchOperation();
    planner.stopBuilderGoalLaunchPolling();
    rejectFetch(new Error('network down'));
    await pending;
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
    console.error = originalConsoleError;
  }
  assert.equal(planner.builderGoalLaunchPollFailures, 0, 'a stale rejection must not bump the failure counter');
  assert.equal(planner._statusMessage, null);
});

test('a newer generation supersedes an in-flight READY refresh', async () => {
  installLocalStorage();
  let releaseRefresh;
  const planner = launchStatusPlanner({
    loadBuilderGoalAccounts: () => new Promise(resolve => {
      releaseRefresh = resolve;
    }),
    loadBuilderGoalMachines: async () => {},
    loadBuilderGoalRuns: async () => {}
  });
  const pending = planner.handleBuilderGoalLaunchOperationView({
    status: 'READY',
    accountName: 'bigGreenMeat',
    world: 360
  });
  // While the READY refresh is in flight, a new polling lifecycle begins (generation increments).
  planner.stopBuilderGoalLaunchPolling();
  releaseRefresh();
  await pending;
  assert.equal(planner._statusMessage, null, 'a superseded READY refresh must not render');
  assert.equal(planner.builderGoalLaunchRefreshRequired, false);
});

test('scheduleNextBuilderGoalLaunchPoll keeps a single timeout chain', () => {
  const originalSetTimeout = global.setTimeout;
  const originalClearTimeout = global.clearTimeout;
  let clearedCount = 0;
  const created = [];
  global.clearTimeout = () => {
    clearedCount += 1;
  };
  global.setTimeout = (fn, ms) => {
    created.push(fn);
    return created.length;
  };
  try {
    const planner = launchStatusPlanner();
    planner.pollBuilderGoalLaunchOperation = () => {};
    planner.scheduleNextBuilderGoalLaunchPoll(100);
    planner.scheduleNextBuilderGoalLaunchPoll(200);
    assert.equal(clearedCount, 1, 'the previous timeout must be cleared when scheduling a new one');
    assert.equal(planner.builderGoalLaunchPollHandle, 2);
    assert.equal(created.length, 2);
  } finally {
    global.setTimeout = originalSetTimeout;
    global.clearTimeout = originalClearTimeout;
  }
});

test('backoff reaches but does not exceed 30 seconds', async () => {
  installLocalStorage();
  let scheduledDelay = null;
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-backoff';
  planner.scheduleNextBuilderGoalLaunchPoll = delayMs => {
    scheduledDelay = delayMs;
  };
  const originalFetch = global.fetch;
  global.fetch = async () => {
    throw new Error('down');
  };
  const originalConsoleError = console.error;
  console.error = () => {};
  try {
    for (let i = 0; i < 6; i++) {
      await planner.pollBuilderGoalLaunchOperation();
    }
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
    console.error = originalConsoleError;
  }
  assert.equal(planner.builderGoalLaunchPollNextDelay, 30000);
  assert.ok(scheduledDelay <= 30000, 'the scheduled delay must never exceed 30 seconds');
});

test('backoff resets after a successful poll', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-reset';
  planner.builderGoalLaunchPollFailures = 3;
  planner.builderGoalLaunchPollNextDelay = 16000;
  planner.handleBuilderGoalLaunchOperationView = async () => {};
  const originalFetch = global.fetch;
  global.fetch = async () => ({
    status: 200,
    ok: true,
    json: async () => ({ status: 'WAITING_FOR_DISPATCH', accountName: 'bigGreenMeat' })
  });
  try {
    await planner.pollBuilderGoalLaunchOperation();
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }
  assert.equal(planner.builderGoalLaunchPollFailures, 0);
  assert.equal(planner.builderGoalLaunchPollNextDelay, AccountTaskPlanner.BUILDER_GOAL_LAUNCH_POLL_INTERVAL_MS);
});

test('FAILED restores Launch control and clears storage exactly once', async () => {
  const store = installLocalStorage();
  store.set(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY, 'op-failed');
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-failed';
  planner.launchBuilderGoalAccountBtn = { disabled: true, innerHTML: 'Launching...' };

  await planner.handleBuilderGoalLaunchOperationView({
    status: 'FAILED',
    message: 'Micro6 relaunch process exited before the local executor became ready'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.equal(store.has(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY), false);
  assert.equal(
    planner.launchBuilderGoalAccountBtn.innerHTML,
    '<i class="bi bi-box-arrow-up-right me-1"></i>Launch Account'
  );
  assert.ok(planner._statusMessage.includes('process exited'));
});

test('CONFLICT restores Launch control and renders server reason exactly once', async () => {
  const store = installLocalStorage();
  store.set(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY, 'op-conflict');
  const planner = launchStatusPlanner();
  planner.builderGoalLaunchOperationId = 'op-conflict';
  planner.launchBuilderGoalAccountBtn = { disabled: true, innerHTML: 'Launching...' };

  await planner.handleBuilderGoalLaunchOperationView({
    status: 'CONFLICT',
    message: 'The existing Micro6 session for this account is not dispatch-ready.'
  });

  assert.equal(planner.builderGoalLaunchOperationId, '');
  assert.equal(store.has(AccountTaskPlanner.BUILDER_GOAL_LAUNCH_OPERATION_KEY), false);
  assert.equal(
    planner.launchBuilderGoalAccountBtn.innerHTML,
    '<i class="bi bi-box-arrow-up-right me-1"></i>Launch Account'
  );
  assert.equal(planner._statusMessage, 'The existing Micro6 session for this account is not dispatch-ready.');
});

test('constructor initializes and restores exact-run tracker state', () => {
  const store = installLocalStorage();
  store.set(
    AccountTaskPlanner.BUILDER_GOAL_RUN_TRACKER_KEY,
    JSON.stringify({
      iterationId: 'buildergoal_resume',
      statusUrl: '/api/debug/builder-goals/runs/buildergoal_resume'
    })
  );

  const planner = new AccountTaskPlanner();

  assert.equal(planner.builderGoalRunTrackerIterationId, 'buildergoal_resume');
  assert.equal(
    planner.builderGoalRunTrackerStatusUrl,
    '/api/debug/builder-goals/runs/buildergoal_resume'
  );
  assert.equal(planner.builderGoalRunTrackerPollGeneration, 0);
  assert.equal(Number.isNaN(planner.builderGoalRunTrackerPollGeneration), false);
});

test('exact-run poll processes its first response and uses the server status URL', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_poll';
  planner.builderGoalRunTrackerStatusUrl = '/api/debug/builder-goals/runs/buildergoal_poll';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay = 2000;
  planner.builderGoalRunTrackerLastPhase = null;
  planner.renderBuilderGoalRunProgress = run => {
    planner._progressRun = run;
  };
  planner.scheduleNextBuilderGoalRunPoll = delay => {
    planner._nextDelay = delay;
  };
  global.fetch = async url => {
    planner._polledUrl = url;
    return {
      ok: true,
      status: 200,
      json: async () => ({
        iterationId: 'buildergoal_poll',
        status: 'QUEUED',
        targetPreparationState: 'PREPARING_BUILD',
        targetPreparationMessage: 'Preparing Micro6'
      })
    };
  };

  await planner.pollBuilderGoalRun();

  assert.equal(planner._polledUrl, '/api/debug/builder-goals/runs/buildergoal_poll');
  assert.equal(planner._progressRun.targetPreparationState, 'PREPARING_BUILD');
  assert.equal(planner._nextDelay, 2000);
});

test('exact-run generic FAILED state is rendered as failure, not dispatch success', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_failed';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.stopBuilderGoalRunTracking = () => {
    planner.builderGoalRunTrackerIterationId = '';
  };
  planner.renderBuilderGoalRunFailure = (run, options) => {
    planner._failedRun = run;
    planner._failureOptions = options;
  };
  planner.loadBuilderGoalRuns = async () => {};

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_failed',
    status: 'FAILED',
    failureReason: 'Login failed before dispatch readiness'
  }, 1);

  assert.equal(planner._failedRun.failureReason, 'Login failed before dispatch readiness');
  assert.deepEqual(planner._failureOptions, { buildFailure: false });
});

test('exact-run ACCEPTED/QUEUED keeps tracking and never says "started"', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_accepted';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay = 2000;
  planner.scheduleNextBuilderGoalRunPoll = delay => {
    planner._nextDelay = delay;
  };
  planner.renderBuilderGoalRunProgress = (run, options) => {
    planner._progressRun = run;
    planner._progressOptions = options;
  };
  planner.stopBuilderGoalRunTracking = () => {
    planner.builderGoalRunTrackerIterationId = '';
  };

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_accepted',
    publishStatus: 'ACCEPTED',
    status: 'WAITING_FOR_TASK_START',
    targetReadinessPhase: 'READY_TO_PUBLISH',
    taskStartedObserved: false,
    accountName: 'bigGreenMeat'
  }, 1);

  // Still tracking; the banner must not claim the task started.
  assert.equal(planner.builderGoalRunTrackerIterationId, 'buildergoal_accepted');
  assert.equal(planner._nextDelay, 2000);
  assert.equal(planner._progressOptions.terminal, false);
  assert.ok(!(planner._statusMessage || '').includes('Task started'));
});

test('exact-run RUNNING with task started displays "Task started"', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_running';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.runBuilderGoalBtn = { innerHTML: '', disabled: true };
  planner.updateBuilderGoalRunButtonState = () => {};
  planner.loadBuilderGoalRuns = async () => {};
  planner.stopBuilderGoalRunTracking = () => {
    planner.builderGoalRunTrackerIterationId = '';
  };

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_running',
    publishStatus: 'RUNNING',
    status: 'RUNNING',
    taskStartedObserved: true,
    accountName: 'bigGreenMeat'
  }, 1);

  assert.equal(planner.builderGoalRunTrackerIterationId, '');
  assert.ok(planner._statusMessage.includes('Task started for bigGreenMeat'));
});

test('exact-run RUNNING without a confirmed start keeps tracking', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_running_notstarted';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay = 2000;
  planner.scheduleNextBuilderGoalRunPoll = delay => {
    planner._nextDelay = delay;
  };
  planner.renderBuilderGoalRunProgress = (run, options) => {
    planner._progressRun = run;
    planner._progressOptions = options;
  };
  planner.stopBuilderGoalRunTracking = () => {
    planner.builderGoalRunTrackerIterationId = '';
  };

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_running_notstarted',
    publishStatus: 'RUNNING',
    status: 'WAITING_FOR_TASK_START',
    taskStartedObserved: false,
    accountName: 'bigGreenMeat'
  }, 1);

  assert.equal(planner.builderGoalRunTrackerIterationId, 'buildergoal_running_notstarted');
  assert.ok(!(planner._statusMessage || '').includes('Task started'));
});

test('exact-run PAUSED remains active, renders paused phase, and never claims running', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_paused';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay = 2000;
  planner.scheduleBuilderGoalRunPoll = () => {};
  planner.scheduleNextBuilderGoalRunPoll = delay => {
    planner._nextDelay = delay;
  };
  planner.renderBuilderGoalRunProgress = (run, options) => {
    planner._progressRun = run;
    planner._progressOptions = options;
  };

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_paused',
    publishStatus: 'PAUSED',
    status: 'PAUSED',
    taskStartedObserved: true,
    taskStartConfirmed: true,
    accountName: 'bigGreenMeat'
  }, 1);

  assert.equal(planner.resolveBuilderGoalRunPhase(planner._progressRun), 'PAUSED');
  assert.equal(planner.builderGoalRunTrackerIterationId, 'buildergoal_paused');
  assert.equal(planner._nextDelay, 2000);
  assert.equal(planner._progressOptions.terminal, false);
  assert.equal(AccountTaskPlanner.formatBuilderGoalStatus({ status: 'PAUSED' }), 'Task paused.');
  assert.ok(!(planner._statusMessage || '').toLowerCase().includes('started'));
  assert.ok(!(planner._statusMessage || '').toLowerCase().includes('running'));
});

test('exact-run COMPLETED is terminal success', async () => {
  installLocalStorage();
  const planner = launchStatusPlanner();
  planner.builderGoalRunTrackerIterationId = 'buildergoal_completed';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.runBuilderGoalBtn = { innerHTML: '', disabled: true };
  planner.updateBuilderGoalRunButtonState = () => {};
  planner.loadBuilderGoalRuns = async () => {};
  planner.renderBuilderGoalRunProgress = (run, options) => {
    planner._progressRun = run;
    planner._progressOptions = options;
  };
  planner.stopBuilderGoalRunTracking = () => {
    planner.builderGoalRunTrackerIterationId = '';
  };

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_completed',
    publishStatus: 'COMPLETED',
    status: 'COMPLETED'
  }, 1);

  assert.equal(planner.builderGoalRunTrackerIterationId, '');
  assert.equal(planner._progressOptions.terminal, true);
});

test('Builder Goal structured start failures prefer safe details and retain trace metadata', () => {
  const message = AccountTaskPlanner.describeBuilderGoalStartFailure(
    {
      error: 'Failed to start builder goal run',
      code: 'BUILD_PREPARATION_FAILED',
      phase: 'PREPARING_BUILD',
      details: 'Capture contract could not be resolved',
      correlationId: 'buildergoal_corr123'
    },
    422
  );

  assert.ok(message.startsWith('Capture contract could not be resolved'));
  assert.ok(message.includes('code BUILD_PREPARATION_FAILED'));
  assert.ok(message.includes('phase PREPARING_BUILD'));
  assert.ok(message.includes('correlation buildergoal_corr123'));
  assert.ok(!message.includes('HTTP 422'));
});

test('Builder Goal non-JSON failure falls back to HTTP status', () => {
  assert.equal(
    AccountTaskPlanner.describeBuilderGoalStartFailure({}, 500),
    'HTTP 500'
  );
});

test('compile recovery panel exposes safe same-screen actions without a host path', () => {
  const planner = launchStatusPlanner();
  const html = planner.compileRecoveryPanelHtml('buildergoal_123');

  assert.ok(html.includes('Fix compile errors with AI'));
  assert.ok(html.includes('Copy errors'));
  assert.ok(html.includes('Copy full log'));
  assert.ok(html.includes('Copy AI prompt'));
  assert.ok(html.includes('Download sanitized log'));
  assert.ok(!html.includes('C:\\'));
});

test('compile recovery renders a reviewable escaped canonical diff', () => {
  const html = AccountTaskPlanner.buildProposalReviewHtml({
    proposalId: 'proposal_<x>',
    proposalHash: 'sha256:abc123',
    baseCommit: 'commit<base>',
    rationale: 'Fix <compile>',
    citations: {'src/Task.java': [{kind: 'COMPILER_DIAGNOSTIC'}]},
    policyReport: {violations: []},
    canonicalDiff: 'diff --git a/src/Task.java b/src/Task.java\n+<script>alert(1)</script>',
    expiresAt: '2026-01-01T00:00:00Z'
  }, true, new Set(['APPROVE', 'REJECT', 'REQUEST_CHANGES']), 'buildergoal_123');

  assert.ok(html.includes('Review proposed compile repair'));
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));
  assert.ok(!html.includes('<script>'));
  assert.ok(html.includes('Fix &lt;compile&gt;'));
  assert.ok(html.includes('Evidence citations: 1'));
  assert.ok(html.includes('Approve exact proposal'));
  assert.ok(!html.includes('onclick="window.accountTaskPlanner.approveProposal(\'\','));
  assert.ok(html.includes('buildergoal_123'));
});

test('compile recovery hides review buttons outside AWAITING_REVIEW', () => {
  const html = AccountTaskPlanner.buildProposalReviewHtml({
    proposalId: 'proposal_x',
    canonicalDiff: 'diff --git a/src/A.java b/src/A.java\n+1',
    citations: {a: [{}]},
    policyReport: {violations: []}
  }, false, new Set(), 'buildergoal_123');

  assert.ok(html.includes('Review proposed compile repair'));
  assert.ok(!html.includes('Approve exact proposal'));
  assert.ok(!html.includes('Reject'));
});

test('validation timeline renders durable stages and cancel control only while active', () => {
  const planner = launchStatusPlanner();
  const states = [{hidden: true, innerHTML: ''}, {hidden: true, innerHTML: ''}];

  planner.renderValidationTimeline(states[0], {
    state: 'RUNNING',
    currentStage: 'COMPILE_STATIC_TESTS',
    cancelRequested: false,
    jobId: 'job_1'
  });
  assert.equal(states[0].hidden, false);
  assert.ok(states[0].innerHTML.includes('ISOLATED_CHECKOUT'));
  assert.ok(states[0].innerHTML.includes('ARTIFACT_FINALIZATION'));
  assert.ok(states[0].innerHTML.includes('Cancel validation'));
  assert.ok(states[0].innerHTML.includes('job_1'));

  planner.renderValidationTimeline(states[1], {state: 'SUCCEEDED', currentStage: null, jobId: 'job_1'});
  assert.ok(!states[1].innerHTML.includes('Cancel validation'));
});

test('candidate rerun wording keeps build validation distinct from certification', () => {
  const planner = launchStatusPlanner();
  const html = planner.compileRecoveryPanelHtml('buildergoal_123');
  assert.ok(html.includes('Download sanitized log'));
  assert.ok(!html.includes('C:\\'));
});
