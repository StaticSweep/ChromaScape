/**
 * Pure helpers for replay packet recovery. The debugger page keeps transport and DOM state in
 * debugger.js; these small decisions stay independent so pagination and recovery regressions can
 * run in the Node browser-test suite as well as in the page.
 */
(function (global) {
  'use strict';

  function finiteNumber(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  /** Subtract one inclusive recovery interval from inclusive truncated windows. */
  function subtractTruncatedWindows(windows, recoveredStart, recoveredEnd) {
    const start = finiteNumber(recoveredStart);
    const end = finiteNumber(recoveredEnd);
    if (start === null || end === null || start > end) {
      return Array.isArray(windows) ? windows.map(window => ({ ...window })) : [];
    }

    const result = [];
    for (const window of Array.isArray(windows) ? windows : []) {
      if (!window || window.start == null || window.end == null
          || window.end < start || window.start > end) {
        if (window) result.push({ ...window });
        continue;
      }
      if (window.start < start) {
        result.push({ start: window.start, end: start - 1, cursor: null });
      }
      if (window.end > end) {
        result.push({ start: end + 1, end: window.end, cursor: null });
      }
    }
    return result;
  }

  /** Select the local warning around a focused tick, or the widest warning as a fallback. */
  function chooseRecoveryWindow(windows, focusTick = null) {
    const candidates = Array.isArray(windows)
      ? windows.filter(window => window && Number.isFinite(Number(window.start))
        && Number.isFinite(Number(window.end)) && window.start <= window.end)
      : [];
    if (candidates.length === 0) return null;

    const focused = finiteNumber(focusTick) === null
      ? []
      : candidates.filter(window => window.start <= Number(focusTick)
        && Number(focusTick) <= window.end);
    const pool = focused.length > 0 ? focused : candidates;
    return pool.reduce((selected, candidate) => {
      if (!selected) return { start: candidate.start, end: candidate.end };
      const selectedSpan = selected.end - selected.start;
      const candidateSpan = candidate.end - candidate.start;
      const useCandidate = focused.length > 0
        ? candidateSpan < selectedSpan
        : candidateSpan > selectedSpan;
      return useCandidate
        ? { start: candidate.start, end: candidate.end }
        : selected;
    }, null);
  }

  /**
   * Decide whether a packet page is complete, should continue, or hit the browser safety cap.
   * `loadedPages` is the number of pages already consumed for this window.
   */
  function nextPacketPage(page, loadedPages, maxPages = 20) {
    if (!page || !page.hasMore || !page.cursor) {
      return { done: true, truncated: false, cursor: null };
    }
    const nextPages = Number(loadedPages || 0) + 1;
    return {
      done: nextPages >= maxPages,
      truncated: nextPages >= maxPages,
      cursor: page.cursor
    };
  }

  /** Load one debug session's packet pages without requiring a video recording. */
  async function collectPacketPages(fetchPage, maxPages = 20) {
    const packets = [];
    let cursor = '';
    let loadedPages = 0;
    for (;;) {
      const page = await fetchPage(cursor);
      if (!page || !Array.isArray(page.packets)) {
        throw new Error('Invalid packet replay response');
      }
      packets.push(...page.packets);
      const decision = nextPacketPage(page, loadedPages, maxPages);
      if (decision.done) {
        return { packets, truncated: decision.truncated };
      }
      cursor = decision.cursor;
      loadedPages++;
    }
  }

  /** Coalesce one in-flight promise per replay window and release it after settlement. */
  function createInFlightRegistry() {
    const inFlight = new Map();
    return {
      ensure(key, operation) {
        if (inFlight.has(key)) return inFlight.get(key);
        const promise = Promise.resolve().then(operation);
        inFlight.set(key, promise);
        const release = () => {
          if (inFlight.get(key) === promise) inFlight.delete(key);
        };
        promise.then(release, release);
        return promise;
      },
      clear() {
        inFlight.clear();
      },
      has(key) {
        return inFlight.has(key);
      }
    };
  }

  function acceptsReplayResponse(
    mode,
    capturedGeneration,
    currentGeneration,
    currentRecording,
    responseRecording,
    aborted = false
  ) {
    return !aborted
      && mode === 'replay'
      && capturedGeneration === currentGeneration
      && currentRecording === responseRecording;
  }

  function packetIdentityKey(packet) {
    if (!packet) return '';
    return `${packet.sourceSessionId ?? ''}:${packet.schema ?? 'rsprox-packet/v1'}:${packet.sequence ?? ''}`;
  }

  function packetDisplayIdentity(packet) {
    if (!packet) {
      return { observationSequence: null, captureSequence: null };
    }
    const isV2 = packet.schema === 'rsprox-packet/v2';
    return {
      observationSequence: packet.sequence ?? null,
      captureSequence: isV2 ? (packet.packetSequence ?? null) : (packet.sequence ?? null)
    };
  }

  /** Freeze the exact compiled input shown by preflight; the server rechecks its checksum. */
  function createScenarioRunRequest(preflight, idempotencyKey) {
    const input = preflight?.compiledInput;
    if (preflight?.simulationEligible !== true || !input
        || !/^sha256:[0-9a-f]{64}$/.test(input.manifestChecksum)) {
      throw new Error('Refresh preflight before starting this scenario.');
    }
    return {
      conversionMode: preflight.conversionMode,
      openViewer: true,
      stopPolicy: 'STOP_ON_FIRST_HARD',
      idempotencyKey,
      curationId: input.curationId ?? null,
      expectedManifestChecksum: input.manifestChecksum
    };
  }

  const api = {
    subtractTruncatedWindows,
    chooseRecoveryWindow,
    nextPacketPage,
    collectPacketPages,
    createInFlightRegistry,
    acceptsReplayResponse,
    packetIdentityKey,
    packetDisplayIdentity,
    createScenarioRunRequest
  };

  global.ChromaScapeReplayCore = api;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);
