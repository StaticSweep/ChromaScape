(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MouseVideoReviewCore = api;
})(typeof window === 'undefined' ? globalThis : window, function () {
  'use strict';

  const TICK_SECONDS = 0.6;
  const AUTOSAVE_EVERY = 25;
  const MAX_CORRECTIONS = 2000;
  const CONFIDENT_MIN = 0.85;
  const JUMP_WIDTH_FRACTION = 0.25;
  const JUMP_WINDOW_SECONDS = 0.1;
  const STILL_PIXELS = 2;
  const STILL_SECONDS = 3;
  const TRAIL_SECONDS = 1;
  const TRAIL_GAP_SECONDS = 0.3;
  const GUESS_WIDTH = 24;
  const GUESS_HEIGHT = 32;
  const PROVENANCE_INDEPENDENT = 'INDEPENDENT';
  const PROVENANCE_BLIND = 'BLIND_UNVERIFIED';
  const PROVENANCE_ASSISTED = 'DETECTOR_ASSISTED';

  function finite(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function frameTime(frame) {
    if (!frame || frame.ptsTime === null || frame.ptsTime === undefined) return null;
    return finite(frame.ptsTime);
  }

  /** Deterministic PRNG so a review seed reproduces the same spot checks. */
  function seededRandom(seed) {
    let state = (Number(seed) >>> 0) || 1;
    return function () {
      state = (state + 0x6d2b79f5) | 0;
      let t = Math.imul(state ^ (state >>> 15), 1 | state);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /** Stable 32-bit seed for a source/analysis pair, so the reason names a repeatable run. */
  function seedFromString(value) {
    const text = String(value == null ? '' : value);
    let hash = 0x811c9dc5;
    for (let index = 0; index < text.length; index++) {
      hash ^= text.charCodeAt(index);
      hash = Math.imul(hash, 0x01000193);
    }
    return (hash >>> 0) % 1000000 || 1;
  }

  function orderedFrames(frames) {
    return (Array.isArray(frames) ? frames : [])
      .filter((frame) => frame && Number.isInteger(Number(frame.ordinal)))
      .slice()
      .sort((left, right) => Number(left.ordinal) - Number(right.ordinal));
  }

  function nearestFrame(frames, targetSeconds) {
    let low = 0, high = frames.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (frameTime(frames[mid]) < targetSeconds) low = mid + 1;
      else high = mid;
    }
    const right = frames[low], left = frames[low - 1];
    if (!left) return right || null;
    if (!right) return left;
    return targetSeconds - frameTime(left) <= frameTime(right) - targetSeconds + 1e-9 ? left : right;
  }

  function denseQueue(frames, startOrdinal, endOrdinal) {
    const range = orderedFrames(frames).filter(frame => frame.ordinal >= startOrdinal && frame.ordinal <= endOrdinal);
    if (!range.length) return [];
    let next = frameTime(range[0]);
    const items = [];
    range.forEach((frame, index) => {
      const time = frameTime(frame);
      if (time === null || time < next - 0.100000001) throw new Error('Invalid dense review timing');
      if (time + 1e-9 >= next || index === range.length - 1) {
        items.push({ ordinal: Number(frame.ordinal), reason: 'dense 10 Hz truth' });
        next = time + 0.1;
      }
    });
    return items;
  }

  async function completeIndex(loadPage, options) {
    const settings = options || {};
    const frames = [], seen = new Set();
    let cursor = null, checksum = null, count = null;
    do {
      if (settings.cancelled && settings.cancelled()) throw new Error('Review index loading cancelled');
      const page = await loadPage(cursor);
      if (settings.cancelled && settings.cancelled()) throw new Error('Review index loading cancelled');
      if (!page || !page.artifactChecksum || !Number.isInteger(page.frameCount) || page.frameCount < 1 || page.frameCount > 150000) throw new Error('Invalid bounded frame index');
      if (checksum && (checksum !== page.artifactChecksum || count !== page.frameCount)) throw new Error('Frame index identity changed');
      checksum = page.artifactChecksum; count = page.frameCount;
      if (!Array.isArray(page.items) || !page.items.length || page.items.length > 500) throw new Error('Empty or oversized index page');
      for (const frame of page.items) {
        if (frame.ordinal !== frames.length || frames.length >= count) throw new Error('Incomplete or repeated frame ordinal');
        frames.push(frame);
      }
      cursor = page.nextCursor || null;
      if (cursor && seen.has(cursor)) throw new Error('Repeated frame index cursor');
      if (cursor) seen.add(cursor);
    } while (cursor);
    if (frames.length !== count) throw new Error('Frame index ended before declared coverage');
    return { frames, checksum, frameCount: count };
  }

  /** The frame nearest each 0.6 s step by PTS, deduplicated and in time order. */
  function buildTickGrid(frames, options) {
    const ordered = orderedFrames(frames).filter(frame => frameTime(frame) !== null).sort((a, b) => frameTime(a) - frameTime(b) || a.ordinal - b.ordinal);
    const step = finite(options && options.tickSeconds) || TICK_SECONDS;
    let maxTime = -Infinity;
    for (const frame of ordered) {
      const time = frameTime(frame);
      if (time !== null && time > maxTime) maxTime = time;
    }
    if (!(maxTime >= 0)) return [];
    const ordinals = new Set();
    for (let target = 0; target <= maxTime + 1e-9; target += step) {
      const frame = nearestFrame(ordered, target);
      if (frame) ordinals.add(Number(frame.ordinal));
    }
    return Array.from(ordinals).sort((left, right) => left - right);
  }

  function cursorObservations(observations) {
    return (Array.isArray(observations) ? observations : [])
      .filter(
        (observation) =>
          observation &&
          observation.kind === 'CURSOR_VISIBILITY' &&
          Number.isInteger(Number(observation.frameOrdinal))
      )
      .slice()
      .sort((left, right) => Number(left.frameOrdinal) - Number(right.frameOrdinal));
  }

  function latestByOrdinal(observations) {
    const byOrdinal = new Map();
    for (const observation of cursorObservations(observations)) {
      byOrdinal.set(Number(observation.frameOrdinal), observation);
    }
    return byOrdinal;
  }

  function observationVisibility(observation) {
    return String((observation && observation.visibility) || '').toUpperCase();
  }

  function isConfidentVisible(observation) {
    if (observationVisibility(observation) !== 'VISIBLE') return false;
    const confidence = finite(observation && observation.confidence);
    return confidence === null || confidence >= CONFIDENT_MIN;
  }

  function isUnsure(observation) {
    const visibility = observationVisibility(observation);
    return visibility === 'AMBIGUOUS' || visibility === 'UNSURE';
  }

  /**
   * The detector guess for one ordinal: a VISIBLE hotspot with a box, or UNSURE. HIDDEN and
   * AMBIGUOUS samples are never upgraded to a guess.
   */
  function guessForOrdinal(observations, ordinal) {
    const observation = latestByOrdinal(observations).get(Number(ordinal));
    if (!observation || observationVisibility(observation) !== 'VISIBLE') {
      return { visibility: 'UNSURE', observation: observation || null };
    }
    const x = finite(observation.x);
    const y = finite(observation.y);
    if (x === null || y === null) return { visibility: 'UNSURE', observation: observation };
    const width = finite(observation.width) || GUESS_WIDTH;
    const height = finite(observation.height) || GUESS_HEIGHT;
    return {
      visibility: 'VISIBLE',
      x,
      y,
      width: width > 0 ? width : GUESS_WIDTH,
      height: height > 0 ? height : GUESS_HEIGHT,
      confidence: finite(observation.confidence),
      observation,
    };
  }

  function confidentStillRuns(samples, frames) {
    const byOrdinal = new Map(orderedFrames(frames).map((frame) => [Number(frame.ordinal), frame]));
    const runs = [];
    let run = null;
    for (const observation of samples) {
      if (observationVisibility(observation) !== 'VISIBLE') {
        run = null;
        continue;
      }
      const ordinal = Number(observation.frameOrdinal);
      const x = finite(observation.x);
      const y = finite(observation.y);
      const frame = byOrdinal.get(ordinal);
      const time = frame ? frameTime(frame) : null;
      if (x === null || y === null || time === null) {
        run = null;
        continue;
      }
      if (
        run &&
        time - run.end <= TRAIL_GAP_SECONDS &&
        Math.abs(x - run.x) <= STILL_PIXELS &&
        Math.abs(y - run.y) <= STILL_PIXELS
      ) {
        run.end = time;
        run.last = ordinal;
      } else {
        run = { start: time, end: time, first: ordinal, last: ordinal, x, y };
        runs.push(run);
      }
    }
    return runs.filter((item) => item.end - item.start >= STILL_SECONDS);
  }

  function suspiciousJumps(samples, frames, options) {
    const frameWidth = finite(options && options.frameWidth) || 1920;
    const threshold = frameWidth * JUMP_WIDTH_FRACTION;
    const times = new Map(
      orderedFrames(frames).map((frame) => [Number(frame.ordinal), frameTime(frame)])
    );
    const jumps = [];
    for (let index = 1; index < samples.length; index++) {
      const previous = samples[index - 1];
      const current = samples[index];
      if (
        observationVisibility(previous) !== 'VISIBLE' ||
        observationVisibility(current) !== 'VISIBLE'
      ) {
        continue;
      }
      const previousTime = times.get(Number(previous.frameOrdinal));
      const currentTime = times.get(Number(current.frameOrdinal));
      if (previousTime === null || currentTime === null) continue;
      if (currentTime - previousTime > JUMP_WINDOW_SECONDS + 1e-9) continue;
      const dx = finite(current.x);
      const dy = finite(current.y);
      const px = finite(previous.x);
      const py = finite(previous.y);
      if (dx === null || dy === null || px === null || py === null) continue;
      if (Math.hypot(dx - px, dy - py) > threshold) {
        jumps.push(Number(current.frameOrdinal));
      }
    }
    return jumps;
  }

  function sampleOrdinals(ordinals, seed, fraction) {
    const pool = ordinals.slice();
    const count = Math.max(
      0,
      Math.min(pool.length, Math.floor(pool.length * (finite(fraction) || 0.1)))
    );
    const random = seededRandom(seed);
    const chosen = [];
    for (let index = 0; index < count; index++) {
      chosen.push(pool.splice(Math.floor(random() * pool.length), 1)[0]);
    }
    return chosen.sort((left, right) => left - right);
  }

  /** Moves the sampled spot checks to seeded positions without duplicating an ordinal. */
  function interleaveSpots(items, spotSet, seed) {
    const random = seededRandom((Number(seed) >>> 0) ^ 0x9e3779b9);
    for (let position = 0; position < items.length; position++) {
      if (!spotSet.has(items[position].ordinal)) continue;
      const target = position + Math.floor(random() * (items.length - position));
      if (target !== position) {
        const moved = items[position];
        items[position] = items[target];
        items[target] = moved;
      }
    }
    return items;
  }

  function betweenTickFrames(jumpOrdinals, grid, frames) {
    const ordered = orderedFrames(frames);
    const gridFrames = grid
      .map((ordinal) => ordered.find((frame) => Number(frame.ordinal) === Number(ordinal)))
      .filter(Boolean);
    const extras = new Set();
    for (const jump of jumpOrdinals) {
      const jumpFrame = ordered.find((frame) => Number(frame.ordinal) === Number(jump));
      const jumpTime = jumpFrame ? frameTime(jumpFrame) : null;
      if (jumpTime === null) continue;
      let before = null;
      let after = null;
      for (const frame of gridFrames) {
        const time = frameTime(frame);
        if (time === null) continue;
        if (time < jumpTime && (!before || time > frameTime(before))) before = frame;
        if (time > jumpTime && (!after || time < frameTime(after))) after = frame;
      }
      for (const neighbour of [before, after]) {
        if (!neighbour) continue;
        const target = (jumpTime + frameTime(neighbour)) / 2;
        const nearest = nearestFrame(ordered, target);
        if (nearest) extras.add(Number(nearest.ordinal));
      }
    }
    return Array.from(extras).sort((left, right) => left - right);
  }

  /**
   * Review queue for one analysis. Priority items (UNSURE, suspicious jumps, confident-still
   * tracks, between-tick checks) come first, then the remaining tick grid in time order with a
   * seeded 10 % of confident grid frames moved to seeded positions as spot checks. Blind mode
   * uses only the tick grid plus a seeded 10 % of between-tick frames.
   */
  function buildReviewQueue(options) {
    const frames = orderedFrames(options && options.frames);
    const observations = cursorObservations(options && options.observations);
    const seed = (Number(options && options.seed) >>> 0) || 1;
    const blind = !!(options && options.blind);
    const grid = buildTickGrid(frames, { tickSeconds: options && options.tickSeconds });
    const byOrdinal = latestByOrdinal(observations);

    if (blind) {
      const gridSet = new Set(grid);
      const between = frames
        .map((frame) => Number(frame.ordinal))
        .filter((ordinal) => !gridSet.has(ordinal));
      const spots = new Set(sampleOrdinals(between, seed, 0.1));
      const items = frames
        .map((frame) => Number(frame.ordinal))
        .filter((ordinal) => gridSet.has(ordinal) || spots.has(ordinal))
        .sort((left, right) => left - right)
        .map((ordinal) => ({
          ordinal,
          reason: gridSet.has(ordinal) ? 'tick grid' : 'spot check',
        }));
      return items;
    }

    const priority = [];
    const seen = new Set();
    const add = (ordinal, reason) => {
      const value = Number(ordinal);
      if (!Number.isInteger(value) || seen.has(value)) return;
      if (!frames.some((frame) => Number(frame.ordinal) === value)) return;
      seen.add(value);
      priority.push({ ordinal: value, reason: reason });
    };

    for (const observation of observations) {
      if (isUnsure(observation)) add(observation.frameOrdinal, 'unsure');
    }
    const jumps = suspiciousJumps(observations, frames, options);
    for (const ordinal of jumps) add(ordinal, 'suspicious jump');
    for (const run of confidentStillRuns(observations, frames)) {
      add(run.first, 'confident still');
    }
    for (const ordinal of betweenTickFrames(jumps, grid, frames)) {
      add(ordinal, 'between-tick check');
    }

    const gridItems = grid
      .filter((ordinal) => !seen.has(ordinal))
      .map((ordinal) => ({ ordinal, reason: 'tick grid' }));
    const confident = gridItems
      .filter((item) => isConfidentVisible(byOrdinal.get(item.ordinal)))
      .map((item) => item.ordinal);
    const spotSet = new Set(sampleOrdinals(confident, seed, 0.1));
    for (const item of gridItems) {
      if (spotSet.has(item.ordinal)) item.reason = 'spot check';
    }
    interleaveSpots(gridItems, spotSet, seed);
    return priority.concat(gridItems);
  }

  /** Frame times near the target, used to decide whether the trail may continue. */
  function windowFrameInterval(frames, fromTime, toTime) {
    const deltas = [];
    let prior = null;
    for (const frame of orderedFrames(frames)) {
      const time = frameTime(frame);
      if (time === null || time < fromTime - 1e-9 || time > toTime + 1e-9) continue;
      if (prior !== null) {
        if (time > prior) deltas.push(time - prior);
        else deltas.push(0);
      }
      prior = time;
    }
    if (!deltas.length) return null;
    deltas.sort((left, right) => left - right);
    return deltas[Math.floor(deltas.length / 2)];
  }

  /**
   * Times that end a continuous trail: scene cuts, explicit non-visible cursor samples and holes in
   * the frozen frame index (dropped frames or a timing change).
   */
  function trailBarriers(observations, times, frames, fromTime, toTime) {
    const barriers = [];
    for (const observation of Array.isArray(observations) ? observations : []) {
      if (!observation) continue;
      if (observation.kind === 'SCENE_CUT') {
        const candidates = [
          times.get(Number(observation.afterFrameOrdinal)),
          times.get(Number(observation.frameOrdinal)),
          times.get(Number(observation.beforeFrameOrdinal)),
        ];
        const raw = candidates.find((value) => value !== undefined && value !== null);
        const time = finite(raw);
        if (time !== null && time >= fromTime - 1e-9 && time <= toTime + 1e-9) barriers.push(time);
        continue;
      }
      if (observation.kind !== 'CURSOR_VISIBILITY') continue;
      if (observationVisibility(observation) === 'VISIBLE') continue;
      const time = times.get(Number(observation.frameOrdinal));
      if (time === undefined || time === null) continue;
      if (time >= fromTime - 1e-9 && time <= toTime + 1e-9) barriers.push(time);
    }
    const median = windowFrameInterval(frames, fromTime, toTime);
    if (median !== null && median > 0) {
      let prior = null;
      for (const frame of orderedFrames(frames)) {
        const time = frameTime(frame);
        if (time === null || time < fromTime - 1e-9 || time > toTime + 1e-9) continue;
        if (prior !== null && (time <= prior || time - prior > median * 2.5 + 1e-9)) {
          barriers.push(time);
        }
        prior = time;
      }
    }
    return barriers.sort((left, right) => left - right);
  }

  function crossesBarrier(barriers, previousTime, time) {
    return barriers.some((barrier) => barrier > previousTime + 1e-9 && barrier <= time + 1e-9);
  }

  /**
   * Visible trail over the previous second, split at scene cuts, explicit hidden/ambiguous spans,
   * frame-index holes and gaps longer than 0.3 s, so a discontinuity is never drawn as movement.
   */
  function buildTrail(observations, ordinal, frames, options) {
    const targetOrdinal = Number(ordinal);
    const times = new Map(
      orderedFrames(frames).map((frame) => [Number(frame.ordinal), frameTime(frame)])
    );
    const targetTime = times.get(targetOrdinal);
    if (targetTime === null || targetTime === undefined) return [];
    const windowSeconds = finite(options && options.windowSeconds) || TRAIL_SECONDS;
    const gapSeconds = finite(options && options.gapSeconds) || TRAIL_GAP_SECONDS;
    const fromTime = targetTime - windowSeconds;
    const barriers = trailBarriers(observations, times, frames, fromTime, targetTime);
    const samples = [];
    for (const observation of cursorObservations(observations)) {
      if (observationVisibility(observation) !== 'VISIBLE') continue;
      const sampleOrdinal = Number(observation.frameOrdinal);
      const time = times.get(sampleOrdinal);
      if (time === null || time === undefined) continue;
      if (sampleOrdinal > targetOrdinal || time < targetTime - windowSeconds) continue;
      const x = finite(observation.x);
      const y = finite(observation.y);
      if (x === null || y === null) continue;
      samples.push({ x, y, time, ordinal: sampleOrdinal });
    }
    samples.sort((left, right) => left.time - right.time || left.ordinal - right.ordinal);
    const segments = [];
    let current = [];
    for (const sample of samples) {
      const previous = current.length ? current[current.length - 1] : null;
      if (
        previous &&
        (sample.time - previous.time > gapSeconds || crossesBarrier(barriers, previous.time, sample.time))
      ) {
        segments.push(current);
        current = [];
      }
      current.push(sample);
    }
    if (current.length) segments.push(current);
    return segments;
  }

  /**
   * Faint next-position dot extrapolated from the last two trail samples. With a target ordinal the
   * dot is only drawn when the last sample is that frame, so a hidden span is never bridged.
   */
  function expectedNextPoint(segments, options) {
    if (!Array.isArray(segments) || !segments.length) return null;
    const last = segments[segments.length - 1];
    if (last.length < 2) return null;
    const previous = last[last.length - 2];
    const current = last[last.length - 1];
    const target = options && options.targetOrdinal;
    if (target !== undefined && target !== null && Number(current.ordinal) !== Number(target)) {
      return null;
    }
    const delta = Number(current.time) - Number(previous.time);
    const nextTime = options && Object.prototype.hasOwnProperty.call(options, 'nextTime') ? finite(options.nextTime) : Number(current.time) + delta;
    if (!(delta > 0) || !Number.isFinite(delta) || nextTime === null || !Number.isFinite(nextTime) || nextTime <= Number(current.time)) return null;
    const factor = (nextTime - Number(current.time)) / delta;
    const clamp = (value, extent) => Number.isFinite(extent) && extent > 0 ? Math.max(0, Math.min(extent - 1, value)) : value;
    return {
      x: clamp(current.x + (current.x - previous.x) * factor, options && options.width),
      y: clamp(current.y + (current.y - previous.y) * factor, options && options.height),
    };
  }

  function composeQueueNote(base, queueReason) {
    const suffix = queueReason ? ' · queue:' + String(queueReason) : '';
    return (String(base) + suffix).slice(0, 280);
  }

  /**
   * Correction values for one keyboard/click label action. Enter accepts the guess, a click uses
   * the clicked source pixel, H/A record an explicit non-visible reason. Labels made while the
   * detector is hidden are independent; everything else stays detector-assisted and can never be
   * re-labelled as unassisted.
   */
  function labelValues(action, context) {
    const options = context || {};
    const queueReason = options.queueReason;
    const provenance = options.blind && !options.exposed ? PROVENANCE_BLIND : PROVENANCE_ASSISTED;
    if (action === 'accept') {
      if (options.blind) {
        throw new Error('Blind mode has no detector guess; click the cursor or label it hidden');
      }
      const guess = options.guess;
      if (!guess || guess.visibility !== 'VISIBLE' || finite(guess.x) === null || finite(guess.y) === null) {
        throw new Error('The detector guess is UNSURE; click the cursor or label it hidden');
      }
      return {
        visibility: 'VISIBLE',
        x: finite(guess.x),
        y: finite(guess.y),
        confidence: finite(guess.confidence) === null ? undefined : finite(guess.confidence),
        note: composeQueueNote('accepted', queueReason),
        provenance,
      };
    }
    if (action === 'click') {
      const point = options.point;
      if (!point || finite(point.x) === null || finite(point.y) === null) {
        throw new Error('A click label needs an exact-frame preview');
      }
      return {
        visibility: 'VISIBLE',
        x: finite(point.x),
        y: finite(point.y),
        note: composeQueueNote('corrected', queueReason),
        provenance,
      };
    }
    if (action === 'hidden') {
      return {
        visibility: 'HIDDEN',
        unknownReason: 'not visible',
        note: composeQueueNote('corrected', queueReason),
        provenance,
      };
    }
    if (action === 'ambiguous') {
      return {
        visibility: 'AMBIGUOUS',
        unknownReason: 'unsure',
        note: composeQueueNote('corrected', queueReason),
        provenance,
      };
    }
    throw new Error('Unsupported review label action: ' + action);
  }

  /** Replaces the CURSOR_VISIBILITY correction for a frame instead of duplicating it. */
  function replaceCursorLabel(draft, entry) {
    const items = Array.isArray(draft) ? draft.slice() : [];
    const ordinal = Number(entry && entry.frameOrdinal);
    const index = items.findIndex(
      (item) =>
        item &&
        item.kind === 'CURSOR_VISIBILITY' &&
        Number(item.frameOrdinal) === ordinal
    );
    if (index >= 0) {
      items[index] = entry;
      return items;
    }
    if (items.length >= MAX_CORRECTIONS) {
      throw new Error('The draft already holds the maximum ' + MAX_CORRECTIONS + ' corrections');
    }
    items.push(entry);
    return items;
  }

  function rememberDraft(history, draft, limit) {
    const stack = (Array.isArray(history) ? history : []).concat([(draft || []).slice()]);
    const cap = finite(limit) || 50;
    return stack.length > cap ? stack.slice(stack.length - cap) : stack;
  }

  function undoDraft(history) {
    if (!Array.isArray(history) || !history.length) return null;
    return {
      draft: history[history.length - 1].slice(),
      history: history.slice(0, -1),
    };
  }

  function latestRevision(history, options) {
    const analysisId = options && options.analysisId;
    const revisions = (Array.isArray(history) ? history : []).filter((revision) => {
      if (!revision) return false;
      if (analysisId === undefined || analysisId === null || analysisId === '') return true;
      return revision.analysisId !== undefined && revision.analysisId !== null &&
        String(revision.analysisId) === String(analysisId);
    });
    if (!revisions.length) return null;
    return revisions
      .slice()
      .sort((left, right) => {
        const revisionDelta =
          Number(left.expectedBaseRevision || 0) - Number(right.expectedBaseRevision || 0);
        if (revisionDelta !== 0) return revisionDelta;
        return String(left.createdAt || '').localeCompare(String(right.createdAt || ''));
      })
      .pop();
  }

  /**
   * Cumulative seed for review mode: the latest revision for the selected analysis, cursor labels
   * only, last one wins. A blind seed retains only BLIND_UNVERIFIED assertions; it never upgrades
   * detector-assisted or legacy unknown history to independent truth.
   */
  function seedDraft(history, options) {
    const settings = options || {};
    const latest = latestRevision(history, settings);
    if (!latest) return [];
    const byOrdinal = new Map();
    for (const entry of Array.isArray(latest.corrections) ? latest.corrections : []) {
      if (!entry || entry.kind !== 'CURSOR_VISIBILITY') continue;
      if (settings.blindOnly && labelProvenance(entry) !== PROVENANCE_BLIND) continue;
      byOrdinal.set(Number(entry.frameOrdinal), entry);
    }
    return Array.from(byOrdinal.values());
  }

  /** Coerces stored or submitted provenance to the closed set the server contract allows. */
  function normalizeProvenance(value) {
    if (value === PROVENANCE_BLIND) return PROVENANCE_BLIND;
    if (value === PROVENANCE_ASSISTED) return PROVENANCE_ASSISTED;
    return 'UNKNOWN';
  }

  function labelProvenance(entry) {
    if (!entry || entry.kind !== 'CURSOR_VISIBILITY') return 'UNKNOWN';
    return normalizeProvenance(entry.provenance);
  }

  /** EMPTY, BLIND_UNVERIFIED, DETECTOR_ASSISTED, UNKNOWN or MIXED for the draft's cursor labels. */
  function draftProvenance(draft) {
    const labels = (Array.isArray(draft) ? draft : []).filter(
      (entry) => entry && entry.kind === 'CURSOR_VISIBILITY'
    );
    if (!labels.length) return 'EMPTY';
    const kinds = new Set(labels.map(labelProvenance));
    if (kinds.size > 1) return 'MIXED';
    return Array.from(kinds)[0];
  }

  /** No client assertion establishes independent qualification without a trusted session. */
  function isUnassistedDraft(draft) {
    return draftProvenance(draft) === PROVENANCE_INDEPENDENT;
  }

  /**
   * Which suggestion layers may be rendered. Blind review suppresses every prediction layer,
   * including the original prediction overlay, before the first held-out frame is shown.
   */
  function showSuggestionLayer(options) {
    const settings = options || {};
    if (settings.blind) return false;
    return settings.predictions !== false;
  }

  /**
   * Acknowledge a completed save only when the submitted snapshot is still the newest draft.
   * A label added while the request was in flight stays dirty and asks for another save.
   */
  function acknowledgeSave(state) {
    const settings = state || {};
    const submitted = Number(settings.submittedVersion);
    const current = Number(settings.currentVersion);
    if (!Number.isFinite(submitted) || !Number.isFinite(current) || submitted !== current) {
      return { dirty: true, resave: true };
    }
    return { dirty: false, resave: false };
  }

  /**
   * Serializes cumulative saves. `send` returns true (done), false (stop and keep dirty state) or
   * 'again' (the draft advanced while the request was in flight).
   */
  function createSaveQueue(send) {
    let running = null;
    let pending = false;
    function drain() {
      return (async () => {
        let result = true;
        try {
          while (pending) {
            pending = false;
            const outcome = await send();
            if (outcome === 'again') pending = true;
            else if (outcome === false) {
              result = false;
              break;
            }
          }
        } finally {
          running = null;
        }
        return result;
      })();
    }
    return {
      kick() {
        // A running drain already re-checks its snapshot; joining it avoids a duplicate revision.
        if (!running) {
          pending = true;
          running = drain();
        }
        return running;
      },
      idle() {
        return running || Promise.resolve(true);
      },
      isBusy() {
        return Boolean(running);
      },
    };
  }

  function countLabels(draft) {
    const byOrdinal = new Map();
    for (const entry of Array.isArray(draft) ? draft : []) {
      if (!entry || entry.kind !== 'CURSOR_VISIBILITY') continue;
      byOrdinal.set(Number(entry.frameOrdinal), entry);
    }
    let accepted = 0;
    let corrected = 0;
    let noCursor = 0;
    for (const entry of byOrdinal.values()) {
      const note = String(entry.note || '');
      if (note.startsWith('accepted')) accepted += 1;
      else corrected += 1;
      if (String(entry.visibility || '').toUpperCase() === 'HIDDEN') noCursor += 1;
    }
    return {
      labelled: byOrdinal.size,
      accepted,
      corrected,
      noCursor,
    };
  }

  /** `· heldout ·` is the importer's context marker for a held-out source. */
  function isHeldoutContext(context) {
    return /·\s*heldout\s*·/i.test(String(context == null ? '' : context));
  }

  function reviewReason(options) {
    const seed = Number(options && options.seed) >>> 0;
    return options && options.blind && !options.exposed
      ? 'FA12 dev review · blind-unverified · seed=' + seed
      : 'FA12 dev review · detector-assisted · seed=' + seed;
  }

  function autosaveDue(labelsSinceSave) {
    return Number(labelsSinceSave) >= AUTOSAVE_EVERY;
  }

  /**
   * Whether re-inspecting a source (for example after one of its jobs finishes) must keep the
   * review state instead of resetting it. Any live review on the same source is kept: unsaved
   * edits, a pending save, review mode, or an open server session seeded from its history.
   */
  function preservesReviewOnRefresh(review, sourceId) {
    if (!review || review.sourceId !== sourceId) return false;
    return Boolean(
      review.dirty ||
        review.pendingSave ||
        review.reviewMode?.active ||
        (review.session && review.session.state === 'OPEN')
    );
  }

  const SEALED_NOTICE = 'This session is sealed and read-only.';
  // Prompts that ask for a label; on a sealed session they would invite an edit that is refused.
  const EDIT_PROMPT = /click the cursor|press H|Nothing to undo|Undid the last label/;

  /**
   * Review status text for a session state. A sealed session always leads with the read-only
   * notice, so loading or moving through the queue after a reload cannot replace it, and edit
   * prompts are dropped because sealed labels cannot change.
   */
  function reviewStatusText(message, sessionState) {
    const text = message ? String(message) : '';
    if (sessionState !== 'SEALED') return text;
    if (!text || EDIT_PROMPT.test(text)) return SEALED_NOTICE;
    if (/sealed/i.test(text)) return text;
    return SEALED_NOTICE + ' ' + text;
  }

  return {
    TICK_SECONDS,
    AUTOSAVE_EVERY,
    MAX_CORRECTIONS,
    PROVENANCE_INDEPENDENT,
    PROVENANCE_BLIND,
    PROVENANCE_ASSISTED,
    seedFromString,
    buildTickGrid,
    guessForOrdinal,
    suspiciousJumps,
    confidentStillRuns,
    buildReviewQueue,
    buildTrail,
    expectedNextPoint,
    labelValues,
    denseQueue,
    completeIndex,
    replaceCursorLabel,
    rememberDraft,
    undoDraft,
    latestRevision,
    seedDraft,
    normalizeProvenance,
    labelProvenance,
    draftProvenance,
    isUnassistedDraft,
    showSuggestionLayer,
    acknowledgeSave,
    createSaveQueue,
    countLabels,
    isHeldoutContext,
    reviewReason,
    autosaveDue,
    reviewStatusText,
    preservesReviewOnRefresh,
    sampleOrdinals,
  };
});
