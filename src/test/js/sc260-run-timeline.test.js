'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');

const path = require('node:path');

const AccountTaskPlanner = require(
  path.resolve(__dirname, '../../main/resources/static/js/account-task-planner.js')
);

// --- SC-260: continuous tracking after RUNNING ---

test('SC-260 active tracking statuses include RUNNING, PAUSED, and STOPPING', () => {
  assert.equal(
    AccountTaskPlanner.BUILDER_GOAL_ACTIVE_TRACKING_PUBLISH_STATUSES.has('RUNNING'),
    true
  );
  assert.equal(
    AccountTaskPlanner.BUILDER_GOAL_ACTIVE_TRACKING_PUBLISH_STATUSES.has('PAUSED'),
    true
  );
  assert.equal(
    AccountTaskPlanner.BUILDER_GOAL_ACTIVE_TRACKING_PUBLISH_STATUSES.has('STOPPING'),
    true
  );
  assert.equal(
    AccountTaskPlanner.BUILDER_GOAL_ACTIVE_TRACKING_PUBLISH_STATUSES.has('COMPLETED'),
    false
  );
  assert.equal(
    AccountTaskPlanner.BUILDER_GOAL_ACTIVE_TRACKING_PUBLISH_STATUSES.has('FAILED'),
    false
  );
});

test('SC-260 RUNNING runs keep polling instead of stopping the tracker', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const scheduled = [];
  planner.builderGoalRunTrackerIterationId = 'bg_test01';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay =
    AccountTaskPlanner.BUILDER_GOAL_RUN_POLL_INTERVAL_MS;
  planner.builderGoalClientStatus = {
    innerHTML: '',
    classList: { add() {}, remove() {} }
  };
  planner.renderBuilderGoalClientStatus = function () {};
  planner.renderBuilderGoalRunProgress = function () {};
  planner.scheduleNextBuilderGoalRunPoll = function (delay) {
    scheduled.push(delay);
  };
  planner.maybeRefreshBuilderGoalRunTimeline = function () {};

  const run = {
    iterationId: 'bg_test01',
    publishStatus: 'RUNNING',
    taskStartConfirmed: true,
    accountName: 'tester'
  };
  planner.handleBuilderGoalRunView(run, 1);
  assert.deepEqual(scheduled, [AccountTaskPlanner.BUILDER_GOAL_RUN_POLL_INTERVAL_MS]);
});

test('SC-260 timeline refresh is throttled to the configured interval', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const loads = [];
  planner.builderGoalTimelineIterationId = 'bg_test01';
  planner.builderGoalTimelineLastLoadedAt = Date.now();
  planner.loadBuilderGoalRunTimeline = function (iterationId) {
    loads.push(iterationId);
  };

  planner.maybeRefreshBuilderGoalRunTimeline();
  assert.equal(loads.length, 0, 'must not refresh before the throttle window');

  planner.builderGoalTimelineLastLoadedAt = Date.now() -
    AccountTaskPlanner.BUILDER_GOAL_TIMELINE_REFRESH_MS - 1;
  planner.maybeRefreshBuilderGoalRunTimeline();
  assert.deepEqual(loads, ['bg_test01'], 'must refresh after the throttle window');
});

// --- SC-260: timeline event presentation ---

test('SC-260 timeline events are ordered chronologically with id tie-breaks', () => {
  const events = [
    { id: 3, timestamp: '2026-09-19T10:00:00', eventType: 'SCRIPT_FAILED' },
    { id: 1, timestamp: '2026-09-19T09:59:00', eventType: 'SCRIPT_STARTED' },
    { id: 2, timestamp: '2026-09-19T10:00:00', eventType: 'RUN_PAUSED' }
  ];
  const sorted = events.sort((a, b) =>
    AccountTaskPlanner.compareBuilderGoalTimelineEvents(a, b)
  );
  assert.deepEqual(
    sorted.map(event => event.id),
    [1, 2, 3]
  );
});

test('SC-260 timeline event types carry the SC-260 vocabulary labels', () => {
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('RUN_PAUSED'),
    'Paused'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('RUN_RESUMED'),
    'Resumed'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('RUN_STOP_REQUESTED'),
    'Stop Requested'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('RUN_STOPPED'),
    'Stopped'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('RUN_RISK_TRIGGERED'),
    'Risk Triggered'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('SCRIPT_COMPLETED'),
    'Completed'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('SCRIPT_FAILED'),
    'Failed'
  );
  assert.equal(
    AccountTaskPlanner.formatBuilderGoalTimelineEventType('SOMETHING_NEW'),
    'SOMETHING_NEW'
  );
});

test('SC-260 failure events render the durable failure contract fields', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.escapeHtml = AccountTaskPlanner.prototype.escapeHtml;
  planner.formatAbsoluteTime = () => '2026-09-19 10:00:00';
  planner.renderMultilineInline = AccountTaskPlanner.prototype.renderMultilineInline;

  const html = planner.formatBuilderGoalTimelineEvent({
    id: 7,
    iterationId: 'bg_test01',
    eventType: 'SCRIPT_FAILED',
    timestamp: '2026-09-19T10:00:00',
    message: 'Run failed',
    severity: 'ERROR',
    data: {
      iteration_id: 'bg_test01',
      task_key: 'fishing_training',
      failure_code: 'SCRIPT_EXCEPTION',
      failure_reason: 'NullPointerException at line 42 <script>'
    }
  });
  assert.ok(html.includes('SCRIPT_EXCEPTION'), 'failure code must be rendered');
  assert.ok(html.includes('fishing_training'), 'owning task key must be rendered');
  assert.ok(html.includes('NullPointerException at line 42'), 'failure reason must be rendered');
  assert.ok(!html.includes('<script>'), 'failure reason must be HTML-escaped');
});

test('SC-260 risk events render the executor risk payload', () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.escapeHtml = AccountTaskPlanner.prototype.escapeHtml;
  planner.formatAbsoluteTime = () => '2026-09-19 10:00:00';
  planner.renderMultilineInline = AccountTaskPlanner.prototype.renderMultilineInline;

  const html = planner.formatBuilderGoalTimelineEvent({
    id: 8,
    iterationId: 'bg_test01',
    eventType: 'RUN_RISK_TRIGGERED',
    timestamp: '2026-09-19T10:00:00',
    message: 'Executor reported a risk trigger',
    severity: 'WARN',
    data: { risk: { level: 'HIGH', reason: 'low hitpoints' } }
  });
  assert.ok(html.includes('low hitpoints'), 'risk payload must be rendered');
});

test('SC-260 timeline refresh throttle guarantees an update within five seconds', () => {
  // With a 2s tracker poll, the throttle must sit below two poll intervals so a refresh
  // fires on the second poll (<=4s plus latency) instead of drifting to ~6s.
  assert.ok(
    AccountTaskPlanner.BUILDER_GOAL_TIMELINE_REFRESH_MS <
      2 * AccountTaskPlanner.BUILDER_GOAL_RUN_POLL_INTERVAL_MS,
    `throttle (${AccountTaskPlanner.BUILDER_GOAL_TIMELINE_REFRESH_MS}ms) must be below two poll intervals`
  );
  assert.equal(AccountTaskPlanner.BUILDER_GOAL_TIMELINE_REFRESH_MS, 3000);
});

test('SC-260 non-ok timeline HTTP responses render a degraded state, not an empty timeline', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  planner.builderGoalTimelineIterationId = 'bg_test01';
  planner.builderGoalTimelineEvents = [];
  const rendered = [];
  planner.renderBuilderGoalRunTimeline = function () {
    rendered.push([...this.builderGoalTimelineEvents]);
  };
  const fetchCalls = [];
  planner.builderGoalRunTimeline = { innerHTML: '' };
  const originalFetch = global.fetch;
  global.fetch = async (url) => {
    fetchCalls.push(url);
    return {
      ok: false,
      status: 503,
      json: async () => ({ error: 'service unavailable' })
    };
  };
  try {
    await planner.loadBuilderGoalRunTimeline('bg_test01');
  } finally {
    if (originalFetch === undefined) {
      delete global.fetch;
    } else {
      global.fetch = originalFetch;
    }
  }
  assert.equal(fetchCalls.length, 1);
  // The error path must not have silently cleared the events into an empty "no events" render.
  assert.equal(rendered.length, 0);
  assert.match(planner.builderGoalRunTimeline.innerHTML, /Failed to load the run timeline/);
});

test('SC-260 cancelled terminal branch performs the final timeline refresh', async () => {
  const planner = Object.create(AccountTaskPlanner.prototype);
  const refreshed = [];
  planner.builderGoalRunTrackerIterationId = 'buildergoal_cancelled';
  planner.builderGoalRunTrackerPollGeneration = 1;
  planner.builderGoalRunTrackerPollNextDelay = 2000;
  planner.runBuilderGoalBtn = { innerHTML: '', disabled: true };
  planner.updateBuilderGoalRunButtonState = () => {};
  planner.stopBuilderGoalRunTracking = () => {};
  planner.scheduleNextBuilderGoalRunPoll = () => {};
  planner.refreshBuilderGoalTimelineForRunIfVisible = run => refreshed.push(run.iterationId);
  const statusCalls = [];
  planner.renderBuilderGoalClientStatus = (message, tone) => statusCalls.push([message, tone]);

  await planner.handleBuilderGoalRunView({
    iterationId: 'buildergoal_cancelled',
    publishStatus: 'CANCELLED',
    status: 'CANCELLED',
    cancelled: true
  }, 1);

  assert.deepEqual(refreshed, ['buildergoal_cancelled']);
  assert.ok(statusCalls.some(([message]) => /cancelled/i.test(message)));
});
