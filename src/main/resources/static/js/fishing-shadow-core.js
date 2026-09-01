/**
 * Pure session/run merge helpers for the fishing shadow agent.
 *
 * These functions are deliberately free of DOM/network dependencies so they can be unit tested with
 * Node.js. They operate on a plain state object shaped like the `fishingShadow` panel state:
 *
 * <pre>
 * {
 *   latestTick: number,
 *   latestDecision: object|null,
 *   decisions: array,
 *   decisionMap: Map, // key = (shadowRunId||'') + ':' + tick
 *   sessionId: string|null,
 *   shadowRunId: string|null,
 *   processed: number,
 *   predicted: number,
 *   abstained: number,
 *   persistenceError: string|null,
 *   persistenceLifecycle: string|null,
 *   persistenceQueueSize: number,
 *   persistenceEnqueued: number,
 *   persistenceQueued: number,
 *   persistenceInFlight: number,
 *   persistenceCompleted: number,
 *   persistenceWritten: number,
 *   persistenceFailed: number,
 *   persistenceAbandoned: number,
 *   persistenceDropped: number
 * }
 * </pre>
 *
 * Decisions are deduplicated by the exact shadow run, never by session id and tick alone, so two
 * runs sharing a textual session id can never suppress each other's ticks. Merge functions return a
 * NEW state object and never mutate their inputs. {@code mergeLiveDecisionIntoState} returns {@code
 * {state, accepted}} so the caller can advance a live revision counter only for accepted live
 * decisions.
 */
(function (global) {
  'use strict';

  const LIVE_MESSAGE_TYPE = 'AI_PLAYER_SHADOW_DECISION';

  /** Persistence field names that reflect global (writer-wide) status, not session data. */
  const PERSISTENCE_FIELDS = [
    'persistenceLifecycle',
    'persistenceQueueSize',
    'persistenceEnqueued',
    'persistenceQueued',
    'persistenceInFlight',
    'persistenceCompleted',
    'persistenceWritten',
    'persistenceFailed',
    'persistenceAbandoned',
    'persistenceDropped'
  ];

  /** Returns a fresh, empty shadow panel state. */
  function createEmptyShadowState() {
    return {
      latestTick: -1,
      latestDecision: null,
      decisions: [],
      decisionMap: new Map(),
      sessionId: null,
      shadowRunId: null,
      processed: 0,
      predicted: 0,
      abstained: 0,
      persistenceError: null,
      persistenceLifecycle: null,
      persistenceQueueSize: 0,
      persistenceEnqueued: 0,
      persistenceQueued: 0,
      persistenceInFlight: 0,
      persistenceCompleted: 0,
      persistenceWritten: 0,
      persistenceFailed: 0,
      persistenceAbandoned: 0,
      persistenceDropped: 0
    };
  }

  /** Deduplication key for one (shadowRunId, tick) pair. */
  function decisionKey(shadowRunId, tick) {
    return (shadowRunId || '') + ':' + tick;
  }

  /** Sorts the decision map newest-first and keeps the latest 20 for the rolling table. */
  function rollingDecisions(decisionMap) {
    return Array.from(decisionMap.values())
      .sort((a, b) => Number(b.tick) - Number(a.tick))
      .slice(0, 20);
  }

  /**
   * Merges an array of persisted history decisions (newest-first as returned by the server) into
   * the state.
   *
   * A decision is accepted only when it carries the exact requested session id and shadow run id and
   * the state is already tracking (or adopts) that exact session/run, so a different run's response
   * can never pollute the tracked run's map or raise its tick baseline. Legacy history (no run id)
   * is never accepted into a run-keyed state. History never changes live counters.
   *
   * @param {Object} state the current shadow state
   * @param {Array} history decisions from the server
   * @param {string} targetSessionId the exact session the history was requested for
   * @param {string|null} targetShadowRunId the exact run requested, or null for legacy history
   * @returns {Object} a new state with the accepted history merged in
   */
  function mergeHistoryIntoState(state, history, targetSessionId, targetShadowRunId) {
    const next = {
      ...state,
      decisionMap: new Map(state.decisionMap),
      decisions: state.decisions.slice()
    };
    let newestTick = next.latestTick;
    let newest = next.latestDecision;
    for (const decision of history || []) {
      if (!decision || decision.tick == null) continue;
      const tick = Number(decision.tick);
      if (!Number.isFinite(tick)) continue;
      if (decision.sessionId !== targetSessionId) continue;
      if (targetShadowRunId && decision.shadowRunId !== targetShadowRunId) continue;
      if (next.sessionId && next.sessionId !== targetSessionId) continue;
      if (next.shadowRunId && next.shadowRunId !== targetShadowRunId) continue;
      const key = decisionKey(targetShadowRunId, tick);
      if (!next.decisionMap.has(key)) {
        next.decisionMap.set(key, decision);
      }
      if (tick > newestTick) {
        newestTick = tick;
        newest = decision;
      }
    }
    if (newestTick > next.latestTick) {
      next.latestTick = newestTick;
      next.latestDecision = newest;
    }
    next.sessionId = next.sessionId || targetSessionId || null;
    next.shadowRunId = next.shadowRunId || targetShadowRunId || null;
    next.decisions = rollingDecisions(next.decisionMap);
    return next;
  }

  /**
   * Merges one live WebSocket decision into the state.
   *
   * The decision is rejected (accepted=false, state returned unchanged) when any of these hold:
   *
   * <ul>
   *   <li>the message is missing;
   *   <li>{@code data.type} is not {@code AI_PLAYER_SHADOW_DECISION};
   *   <li>{@code sessionId} is missing or blank;
   *   <li>{@code shadowRunId} is missing or blank;
   *   <li>the session does not match the active session filter;
   *   <li>the session does not match the tracked session;
   *   <li>the shadow run does not match the tracked shadow run;
   *   <li>the tick is not a valid non-negative number;
   *   <li>the tick is not strictly newer than the latest displayed tick;
   *   <li>the (shadowRunId, tick) pair was already seen;
   *   <li>the status is neither {@code PREDICTED} nor {@code ABSTAINED}.
   * </ul>
   *
   * {@code abstained} advances only for {@code ABSTAINED}, never for an unknown status. The
   * structured {@code {state, accepted}} output lets the caller advance a live revision counter
   * only for accepted decisions.
   *
   * @param {Object} state the current shadow state
   * @param {Object} data the live decision message
   * @param {string|null} sessionFilter an extra session constraint (e.g. currentDebugSessionId)
   * @returns {{state: Object, accepted: boolean}}
   */
  function mergeLiveDecisionIntoState(state, data, sessionFilter) {
    const rejected = { state, accepted: false };
    if (!data) return rejected;
    if (data.type !== LIVE_MESSAGE_TYPE) return rejected;
    const sessionId = data.sessionId;
    if (sessionId == null || String(sessionId).trim() === '') return rejected;
    const runId = data.shadowRunId;
    if (runId == null || String(runId).trim() === '') return rejected;
    if (sessionFilter && sessionId !== sessionFilter) return rejected;
    if (state.sessionId && sessionId !== state.sessionId) return rejected;
    if (state.shadowRunId && runId !== state.shadowRunId) return rejected;
    const tick = Number(data.tick);
    if (!Number.isFinite(tick) || tick < 0) return rejected;
    if (state.latestTick >= 0 && tick <= state.latestTick) return rejected;
    const key = decisionKey(runId, tick);
    if (state.decisionMap.has(key)) return rejected;
    const status = data.status;
    if (status !== 'PREDICTED' && status !== 'ABSTAINED') return rejected;

    const next = {
      ...state,
      sessionId,
      shadowRunId: runId,
      decisionMap: new Map(state.decisionMap),
      decisions: state.decisions.slice(),
      processed: state.processed + 1,
      predicted: state.predicted + (status === 'PREDICTED' ? 1 : 0),
      abstained: state.abstained + (status === 'ABSTAINED' ? 1 : 0)
    };
    next.decisionMap.set(key, data);
    next.latestTick = tick;
    next.latestDecision = data;
    next.decisions = rollingDecisions(next.decisionMap);
    return { state: next, accepted: true };
  }

  /**
   * Applies a shadow status payload to the state.
   *
   * Global/current fields (active model, readiness, activation state/error, and every persistence
   * lifecycle/counter field) are always applied, because they describe the agent and its writer,
   * not any single session or run. Session-scoped fields (counters and the latest decision) are
   * applied only when every condition holds: the caller opted in with an unchanged live revision,
   * a target session AND shadow run were named, the backend reported that exact target session and
   * run, and the state is already tracking that exact session and run. This guarantees a generic
   * status response can never hydrate or bind session data, and a delayed same-run response can
   * never roll live counters backward. {@code state.sessionId} and {@code state.shadowRunId} are
   * never bound from a status response.
   *
   * @param {Object} state the current shadow state
   * @param {Object} status the status payload from the server
   * @param {string|null} targetSessionId the session the status was requested for
   * @param {string|null} targetShadowRunId the run the status was requested for
   * @param {boolean} applySessionCounters whether to apply session-scoped counters/latest
   * @returns {Object} a new state with the status merged in
   */
  function mergeStatusIntoState(
    state,
    status,
    targetSessionId,
    targetShadowRunId,
    applySessionCounters
  ) {
    const next = {
      ...state,
      status: status || null,
      decisionMap: state.decisionMap,
      decisions: state.decisions.slice()
    };
    if (status) {
      next.activeModelId = status.activeModelId || null;
      if (typeof status.readyForInference !== 'undefined') {
        next.readyForInference = status.readyForInference;
      }
      if (typeof status.activationState !== 'undefined') {
        next.activationState = status.activationState;
      }
      if (typeof status.activationLoadError !== 'undefined') {
        next.activationLoadError = status.activationLoadError;
      }
      if (typeof status.inactiveReason !== 'undefined') {
        next.inactiveReason = status.inactiveReason;
      }
      if (typeof status.enabled !== 'undefined') next.enabled = status.enabled;
      if (typeof status.shadowMode !== 'undefined') next.shadowMode = status.shadowMode;
      if (typeof status.controlEnabled !== 'undefined') next.controlEnabled = status.controlEnabled;
      if (typeof status.operational !== 'undefined') next.operational = status.operational;
      next.persistenceError = status.persistenceError || null;
      for (const key of PERSISTENCE_FIELDS) {
        if (typeof status[key] !== 'undefined') {
          next[key] = status[key];
        }
      }
    } else {
      next.activeModelId = null;
      next.persistenceError = null;
    }

    // Session-scoped fields apply only when the target session and run are both named, the backend
    // and the state both name that exact session and run, and the caller opted in (live revision
    // unchanged).
    const sessionScoped =
      !!targetSessionId &&
      !!targetShadowRunId &&
      applySessionCounters &&
      !!status &&
      status.sessionId === targetSessionId &&
      status.shadowRunId === targetShadowRunId &&
      next.sessionId === targetSessionId &&
      next.shadowRunId === targetShadowRunId;

    if (!sessionScoped) return next;

    if (Number.isFinite(Number(status.processed))) next.processed = Number(status.processed);
    if (Number.isFinite(Number(status.predicted))) next.predicted = Number(status.predicted);
    if (Number.isFinite(Number(status.abstained))) next.abstained = Number(status.abstained);
    if (status.latestDecision && status.latestDecision.tick != null) {
      const tick = Number(status.latestDecision.tick);
      if (Number.isFinite(tick) && tick > next.latestTick) {
        next.latestTick = tick;
        next.latestDecision = status.latestDecision;
        const key = decisionKey(targetShadowRunId, tick);
        if (!next.decisionMap.has(key)) {
          next.decisionMap = new Map(next.decisionMap);
          next.decisionMap.set(key, status.latestDecision);
          next.decisions = rollingDecisions(next.decisionMap);
        }
      }
    }
    return next;
  }

  /**
   * Returns a fresh state bound to a new tracked session and shadow run. The single reset path used
   * on every session/run-change event so a previous run's ticks, decisions, counters, and errors
   * can never bleed into the new one.
   *
   * @param {string|null} sessionId the new session to track
   * @param {string|null} shadowRunId the new run to track
   * @returns {Object} a fresh empty state bound to the session and run
   */
  function switchShadowSessionState(sessionId, shadowRunId) {
    const fresh = createEmptyShadowState();
    fresh.sessionId = sessionId || null;
    fresh.shadowRunId = shadowRunId || null;
    return fresh;
  }

  /**
   * Selects the active shadow run from a {@code /runs} discovery payload.
   *
   * <p>A run is auto-selected only when it is explicitly marked {@code active} and is a real
   * schema-v2 run ({@code shadowRunId} present, not the legacy file). An empty run list, a list with
   * no active run, or a list containing only legacy history returns {@code null} so the caller shows
   * the run selector and never silently loads legacy history.
   *
   * @param {Array} runs the {@code runs} array from the server
   * @returns {Object|null} the single active run, or null when there is none
   */
  function selectActiveRun(runs) {
    if (!Array.isArray(runs)) return null;
    const active = runs.find(r => r && r.active && !r.legacy && r.shadowRunId);
    return active || null;
  }

  /**
   * Computes the run-list fields after a session/run switch.
   *
   * <p>When the new session differs from the previously tracked session (or either is unknown),
   * the run list is cleared. When the session is unchanged (a run change within the same session),
   * the discovered run list is preserved so the run selector stays populated.
   *
   * @param {Array} currentRuns the current runs array
   * @param {string|null} currentRunsSessionId the session the current runs belong to
   * @param {string|null} previousSessionId the session before the switch
   * @param {string|null} newSessionId the session after the switch
   * @returns {{runs: Array, runsSessionId: string|null}}
   */
  function resetRunsOnSessionSwitch(
    currentRuns,
    currentRunsSessionId,
    previousSessionId,
    newSessionId
  ) {
    if (newSessionId && previousSessionId && newSessionId === previousSessionId) {
      return { runs: currentRuns || [], runsSessionId: currentRunsSessionId || null };
    }
    return { runs: [], runsSessionId: null };
  }

  /**
   * Applies a {@code /runs} discovery payload, keyed by session.
   *
   * <p>The discovery applies only when it targets the currently tracked session; otherwise the
   * current run list is returned unchanged so a stale, aborted, or malformed discovery can never
   * restore a previous session's runs. A successful discovery stores the (possibly empty) run list
   * for the target session.
   *
   * @param {Array} currentRuns the current runs array
   * @param {string|null} currentRunsSessionId the session the current runs belong to
   * @param {string|null} trackedSessionId the currently tracked session
   * @param {string} targetSessionId the session the discovery was requested for
   * @param {Array} discoveredRuns the runs array from the server
   * @returns {{runs: Array, runsSessionId: string|null}}
   */
  function applyRunsDiscovery(
    currentRuns,
    currentRunsSessionId,
    trackedSessionId,
    targetSessionId,
    discoveredRuns
  ) {
    if (trackedSessionId && trackedSessionId !== targetSessionId) {
      // Stale/aborted discovery for a non-tracked session: ignore.
      return { runs: currentRuns || [], runsSessionId: currentRunsSessionId || null };
    }
    if (!Array.isArray(discoveredRuns)) {
      // Malformed discovery cannot overwrite current state.
      return { runs: currentRuns || [], runsSessionId: currentRunsSessionId || null };
    }
    return { runs: discoveredRuns, runsSessionId: targetSessionId };
  }

  const api = {
    createEmptyShadowState,
    mergeHistoryIntoState,
    mergeLiveDecisionIntoState,
    mergeStatusIntoState,
    switchShadowSessionState,
    selectActiveRun,
    resetRunsOnSessionSwitch,
    applyRunsDiscovery,
    decisionKey
  };

  global.FishingShadowCore = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : this);
