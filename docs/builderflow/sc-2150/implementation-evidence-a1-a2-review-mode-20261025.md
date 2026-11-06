# SC-2150 implementation evidence — A1/A2 remediation and FA12 review mode (2026-10-25)

Project: `C:\Dev\IdeaProjects\ChromaScape`. Branch: `codex/sc-2150-mouse-video-import`.
No commit, Felix write or Shortcut transition was made in this session.

## Scope

- **A1 resource guard:** the video execution scope now sets `MemorySwapMax=0` with `MemoryMax`, so the
  configured budget is a total-memory ceiling. `MouseVideoResourceLimitsTest` asserts the swap limit
  and the previously failing 256 MiB allocation under a 64 MiB budget is killed.
- **A2 imported replay:** `playExternalRecording` now hides the tick loader, shows imported-media
  wording instead of "predates tick tracking", and a native recording selection restores the
  original no-tick wording. Terminal tick-panel states share one helper.
- **FA12 Part B review mode:** review mode on the Mouse Video page with a PTS tick grid, prioritized
  queue (UNSURE, suspicious jumps, confident-still tracks, between-tick checks), seeded spot checks,
  guess box, one-second trail with 0.3 s gap breaks, extrapolated next position, keyboard labels
  (Enter/click/H/A/S/arrows/Z), cumulative autosave every 25 labels and on close, and a forced Blind
  mode for `· heldout ·` sources.

## Changed files

- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoProcessRunner.java`
  — `MemorySwapMax=0` on the systemd scope; policy comment.
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoProperties.java`
  — documents the budget as a total-memory ceiling.
- `src/main/resources/templates/replay.html` — imported-media tick notice/loader terminal state and
  native-wording restoration.
- `src/main/resources/static/js/mouse-video-review-core.js` (new) — pure tick-grid, queue, trail,
  label, cumulative-seed, blind-mode and autosave helpers.
- `src/main/resources/static/js/mouse-video.js` — review-mode state, overlay, queue navigation,
  keyboard/button labels, cumulative saves, conflict handling, frame-index paging.
- `src/main/resources/templates/mouse-video.html` — review-mode controls/status and the core script;
  asset versions bumped to `mouse-video.js?v=5`, `mouse-video-review-core.js?v=1`,
  `mouse-research.css?v=10`.
- `src/main/resources/static/css/mouse-research.css` — hides the frame table/nav in review mode and
  styles guess, trail and expected-next overlays.
- `src/test/java/.../MouseVideoResourceLimitsTest.java` — asserts `memory.swap.max == 0`.
- `src/test/js/replay-page.test.js` — imported playback terminal-state and native-wording tests.
- `src/test/js/mouse-video.test.js` — review-mode core tests (tick grid 60/30/24 fps and VFR, queue
  priority/dedupe/seeded spot checks, labels, undo, cumulative seed, blind mode, trail gaps).

## Tests

Verified on this host with JDK 21 (`JAVA_HOME=~/.gradle/jdks/jdk-21.0.12.1+1`) and
`-Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json`.

- `./gradlew test --tests "com.chromascape.web.debug.tickdebugger.video.*"` —
  **279 tests, 0 failures, 31 skipped** (PostgreSQL/opt-in gated: service race 9, scheduling 14,
  migration 5, concurrency 2, corpus harness 1).
- `MouseVideoResourceLimitsTest` — **3/3 passed**, including the allocation kill and
  `memory.swap.max == 0`.
- `./gradlew spotlessCheck` — passed.
- `node --test src/test/js/*.test.js` — **360 passed, 0 failed** (348 before this work).
- `build/sc2150-review-mode-check.mjs` (local, git-ignored Playwright harness using the real page
  scripts with stubbed APIs) — passed: review mode hides the frame table, UNSURE disables Enter,
  skip reaches the suspicious jump, Enter writes an `accepted` VISIBLE correction and cumulative
  save, H + Z undo, Blind hides guess/trail and writes a `without-suggestions` cumulative save,
  clicking the preview adds a source-pixel label, exit restores the table, a `· heldout ·` source
  forces and locks Blind, zero page errors. Screenshot: `build/sc2150-review-mode.png`.

Not run: live application/browser review against a real analysed source, PostgreSQL-backed video
tests, the full Gradle suite and its 69 known current-tree failures, corpus harness, Python suite,
Windows/native-capture checks, real-footage scoring and FA12 held-out gates.

## Remaining work

- Felix evidence linking, `in_progress -> in_review` gate and the mapped Shortcut transition are
  still outstanding; this session had no Felix/Shortcut MCP access.
- Real-footage review sessions (Part C), detector qualification (Parts A/D/E), the held-out decision
  and FA12 gates remain unqualified.
- Constrained product YouTube acquisition and owner decisions D3–D7, native-capture labels,
  Windows qualification, and broad full-suite regression reconciliation remain open.
- Live Playwright review against an analysed source is unverified; the current browser check uses
  stubbed API fixtures.
