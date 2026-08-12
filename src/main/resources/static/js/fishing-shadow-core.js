/**
 * Pure session/history merge helpers for the fishing shadow agent.
 *
 * These functions are deliberately free of DOM/network dependencies so they can be unit tested with
 * Node.js. They operate on a plain state object shaped like the `fishingShadow` panel state:
 *
 * <pre>
 * {
 *   latestTick: number,
 *   latestDecision: object|null,
 *   decisions: array,
 *   decisionMap: Map, // key = (sessionId||'') + ':' + tick
 *   sessionId: string|null,
 *   processed: number,
 *   predicted: number,
 *   abstained: number,
 *   persistenceError: string|null
 * }
 * </pre>
 *
 * Merge functions return a NEW state object and never mutate their inputs. {@code
 * mergeLiveDecisionIntoState} returns {@code {state, accepted}} so the caller can advance a live
 * revision counter only for accepted live decisions.
 */
(function (global) {
  'use strict';

  const LIVE_MESSAGE_TYPE = 'AI_PLAYER_SHADOW_DECISION';

  /** Returns a fresh, empty shadow panel state. */
  function createEmptyShadowState() {
    return {
      latestTick: -1,
      latestDecision: null,
      decisions: [],
      decisionMap: new Map(),
      sessionId: null,
      processed: 0,
      predicted: 0,
      abstained: 0,
      persistenceError: null
    };
  }

  /** Deduplication key for one (sessionId, tick) pair. */
  function decisionKey(sessionId, tick) {
    return (sessionId || '') + ':' + tick;
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
   * Every decision whose sessionId differs from the requested session is rejected, so an old
   * session's response can never pollute a newer session's map or raise its tick baseline. As a
   * second line of defense, decisions are also rejected when they do not match the session the
   * state is already tracking. History never changes live counters.
   *
   * @param {Object} state the current shadow state
   * @param {Array} history decisions from the server
   * @param {string} targetSessionId the exact session the history was requested for
   * @returns {Object} a new state with the accepted history merged in
   */
  function mergeHistoryIntoState(state, history, targetSessionId) {
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
      const ds = decision.sessionId || targetSessionId;
      if (targetSessionId && ds !== targetSessionId) continue;
      if (next.sessionId && ds !== next.sessionId) continue;
      const key = decisionKey(ds, tick);
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
   *   <li>the session does not match the active session filter;
   *   <li>the session does not match the tracked session;
   *   <li>the tick is not a valid non-negative number;
   *   <li>the tick is not strictly newer than the latest displayed tick;
   *   <li>the (sessionId, tick) pair was already seen;
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
    if (sessionFilter && sessionId !== sessionFilter) return rejected;
    if (state.sessionId && sessionId !== state.sessionId) return rejected;
    const tick = Number(data.tick);
    if (!Number.isFinite(tick) || tick < 0) return rejected;
    if (state.latestTick >= 0 && tick <= state.latestTick) return rejected;
    const key = decisionKey(sessionId, tick);
    if (state.decisionMap.has(key)) return rejected;
    const status = data.status;
    if (status !== 'PREDICTED' && status !== 'ABSTAINED') return rejected;

    const next = {
      ...state,
      sessionId,
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
   * Model readiness and persistence fields ({@code activeModelId}, {@code persistenceError}) are
   * global/current status and are always applied. Session counters and the latest decision are
   * applied only when the caller opts in (its live revision matched when the request was issued)
   * and only when the reported session matches the tracked session, so a delayed same-session
   * response can never roll live counters backward.
   *
   * @param {Object} state the current shadow state
   * @param {Object} status the status payload from the server
   * @param {string|null} targetSessionId the session the status was requested for
   * @param {boolean} applySessionCounters whether to apply session-scoped counters/latest
   * @returns {Object} a new state with the status merged in
   */
  function mergeStatusIntoState(state, status, targetSessionId, applySessionCounters) {
    const next = {
      ...state,
      status: status || null,
      decisionMap: state.decisionMap,
      decisions: state.decisions.slice()
    };
    if (status && status.activeModelId) {
      next.activeModelId = status.activeModelId;
    } else {
      next.activeModelId = null;
    }
    next.persistenceError = (status && status.persistenceError) || null;
    if (!applySessionCounters) return next;

    const statusSession = (status && status.sessionId) || null;
    const sessionMatches = !next.sessionId || (statusSession && statusSession === next.sessionId);
    if (!sessionMatches) return next;

    if (Number.isFinite(Number(status.processed))) next.processed = Number(status.processed);
    if (Number.isFinite(Number(status.predicted))) next.predicted = Number(status.predicted);
    if (Number.isFinite(Number(status.abstained))) next.abstained = Number(status.abstained);
    if (status.latestDecision && status.latestDecision.tick != null) {
      const tick = Number(status.latestDecision.tick);
      if (Number.isFinite(tick) && tick > next.latestTick) {
        next.latestTick = tick;
        next.latestDecision = status.latestDecision;
        const key = decisionKey(statusSession || targetSessionId || '', tick);
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
   * Returns a fresh state bound to a new tracked session. The single reset path used on every
   * session-change event so a previous session's ticks, decisions, counters, and errors can never
   * bleed into the new one.
   *
   * @param {string|null} sessionId the new session to track
   * @returns {Object} a fresh empty state bound to the session
   */
  function switchShadowSessionState(sessionId) {
    const fresh = createEmptyShadowState();
    fresh.sessionId = sessionId || null;
    return fresh;
  }

  const api = {
    createEmptyShadowState,
    mergeHistoryIntoState,
    mergeLiveDecisionIntoState,
    mergeStatusIntoState,
    switchShadowSessionState,
    decisionKey
  };

  global.FishingShadowCore = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : this);
