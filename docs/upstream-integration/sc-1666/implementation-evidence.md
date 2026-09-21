# SC-1666 implementation evidence

Implementation checkout: `C:\Dev\IdeaProjects\ChromaScape-sc1666`.

Branch: `codex/sc-1666-linux-capture-contracts`.

Base: `c734e23d7dd6d9a0017d8262310bab679314ae18`, the merged SC-1665 integration head.

Implementation commit: `1ee492d6`.

## Implemented surface

- Added exact host-qualified `FrameSourceBinding` while preserving discovery equality semantics.
- Extended `FrameEvent` additively; its original constructor and all `FrameListener` signatures are
  intact.
- Preserved Linux `CapturedFrame` identity, generation, observation, content-change, and freshness
  metadata through the production capture provider.
- Added Windows capture-time selection fencing.
- Invalidated unified cached frames on source changes and rejected displaced queued callbacks.
- Pinned recording readiness to the first valid source and counted source mismatches.
- Added sanitized source diagnostics to recording and viewport pipeline metrics.
- Added deterministic same-PID/different-host and generation-change regressions.
- Added an actual FFmpeg MP4 start/encode/stop/finalize round-trip test.

## Compatibility

- `ScreenManager.captureWindow()` and `captureZone()` remain unchanged for callers.
- Legacy `FrameEvent(BufferedImage,long,int,int)` remains available.
- Custom `WindowFrameCapture` implementations remain source-compatible through a default method.
- Recording/replay identifiers and persisted entity fields are unchanged; no migration is required.
- No new remote-frame protocol or launcher wire field was added.

## Review status

Implementation and deterministic verification are complete. Live two-host routing and Windows
runtime qualification remain NOT RUN and require explicit execution or proof-of-concept waiver.
The branch remains review-required in [PR #10](https://github.com/ikiddoi/ChromaScape/pull/10)
until it is approved and merged.
