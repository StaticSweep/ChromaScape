/** Cursor-driven video catalog; optional tick evidence never removes video-only rows. */
(function (root) {
  'use strict';
  function query(filters, cursor) {
    const params = new URLSearchParams({ limit: '200' });
    for (const key of ['accountName', 'taskKey']) if (filters[key]) params.set(key, filters[key]);
    if (cursor) params.set('cursor', cursor);
    return params.toString();
  }
  async function page(fetcher, url, filters = {}, cursor = null) {
    const response = await fetcher(`${url}?${query(filters, cursor)}`);
    if (!response.ok) throw new Error(`Recording catalog returned HTTP ${response.status}`);
    const data = await response.json();
    if (!Array.isArray(data.entries) || typeof data.hasMore !== 'boolean' || (data.hasMore && (typeof data.nextCursor !== 'string' || !data.nextCursor || data.nextCursor === cursor))) throw new Error('Invalid recording catalog page');
    return data;
  }
  async function allPages(fetcher, url, filters = {}) {
    const entries = [], seenCursors = new Set(); let cursor = null;
    do {
      const data = await page(fetcher, url, filters, cursor); entries.push(...data.entries);
      cursor = data.hasMore ? data.nextCursor : null;
      if (cursor && seenCursors.has(cursor)) throw new Error('Repeated recording cursor');
      if (cursor) seenCursors.add(cursor);
      if (seenCursors.size > 1000) throw new Error('Recording catalog is too large; narrow the filters');
    } while (cursor);
    return entries;
  }
  function groups(entries, mode) {
    const result = new Map();
    for (const entry of entries) {
      const task = entry.taskKey || 'Unknown task'; const account = entry.accountName || 'Unknown account';
      const key = mode === 'task' ? JSON.stringify([task]) : JSON.stringify([account, task]);
      if (!result.has(key)) result.set(key, { label: mode === 'task' ? task : `${account} → ${task}`, entries: [] });
      result.get(key).entries.push(entry);
    }
    return [...result.values()];
  }
  function settingsNode(doc, entry) {
    const details = doc.createElement('details'), summary = doc.createElement('summary'), pre = doc.createElement('pre');
    summary.textContent = 'Task settings';
    pre.textContent = entry.taskArgs == null ? 'Unknown (not captured)' : JSON.stringify(entry.taskArgs, null, 2);
    const hash = doc.createElement('small'); hash.textContent = entry.settingsHash || 'Settings hash unknown';
    details.append(summary, pre, hash); return details;
  }
  const api = { page, allPages, groups, settingsNode };
  root.RecordingCatalog = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
