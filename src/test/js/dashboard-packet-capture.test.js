const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Planner = require('../../main/resources/static/js/account-task-planner.js');

test('Packet Capture starts off and clears when changing to a remote host', () => {
  const html = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/debugger.html'), 'utf8');
  const input = html.match(/<input[^>]*id="builderGoalPacketCapture"[^>]*>/)[0];
  assert.doesNotMatch(input, /\bchecked\b/);
  const planner = Object.create(Planner.prototype);
  planner.packetCaptureCheckbox = { checked: true };
  planner.getSelectedBuilderGoalMachine = () => ({ localMachine: true });
  planner.updateBuilderGoalRunButtonState();
  assert.equal(Boolean(planner.packetCaptureCheckbox.disabled), false);
  assert.equal(planner.packetCaptureCheckbox.checked, true);
  planner.getSelectedBuilderGoalMachine = () => ({ localMachine: false });
  planner.updateBuilderGoalRunButtonState();
  assert.equal(planner.packetCaptureCheckbox.disabled, true);
  assert.equal(planner.packetCaptureCheckbox.checked, false);
});

test('Launch Account only sends capture for an explicitly selected local launch', async () => {
  const originalFetch = global.fetch;
  try {
    for (const [localMachine, checked, expected] of [[true, false, false], [true, true, true], [false, true, false]]) {
      const planner = Object.create(Planner.prototype);
      planner.packetCaptureCheckbox = { checked };
      planner.getSelectedBuilderGoalAccount = () => ({ accountId: 'a', runnable: true, launchReady: true });
      planner.getSelectedBuilderGoalMachine = () => ({ machineUuid: 'm', builderGoalRunnable: true, localMachine });
      planner.getBuilderGoalWorldValue = () => 360;
      planner.renderBuilderGoalClientStatus = () => {};
      planner.startBuilderGoalLaunchPolling = () => {};
      planner.updateBuilderGoalRunButtonState = () => {};
      let payload;
      global.fetch = async (url, options) => {
        payload = JSON.parse(options.body);
        return { ok: true, json: async () => ({ operationId: 'op', status: 'QUEUED' }) };
      };
      await planner.launchBuilderGoalAccount();
      assert.equal(payload.packetCapture, expected);
      assert.equal(payload.world, 360);
    }
  } finally { global.fetch = originalFetch; }
});
