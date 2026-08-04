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
