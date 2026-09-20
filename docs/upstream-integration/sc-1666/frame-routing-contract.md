# SC-1666 frame routing contract

## Identity

`ProcessIdentity` intentionally ignores `hostId` in equality. That is appropriate for discovery but
not for routing pixels. `FrameSourceBinding` therefore compares the process origin, host id, PID,
process start time, boot id, and selection generation.

The binding is safe diagnostic metadata: it contains no window title, account name, command line,
credential, or raw native pointer.

## Production flow

1. The Controller owns one SC-1664 session.
2. On Linux, that session implements `TargetFrameSource`; SC-1665 returns a detached
   `CapturedFrame` with identity, generation, and freshness.
3. `ScreenManagerWindowFrameCapture` converts it to the existing `FrameEvent` type without dropping
   metadata. Windows attaches the selected identity only if the generation and exact binding remain
   stable across capture.
4. `UnifiedFrameCaptureService` publishes one capture to its existing `FrameListener` consumers.
5. Recording, debugger viewport, and established stream consumers retain their existing APIs.

## Transition and cache rules

- The first bound frame establishes the active source.
- A different host/process/generation clears the cached frame before the new event is stored.
- Delivery rechecks the current binding on the dispatch thread. A queued event from a displaced
  source is dropped.
- Once a bound source exists, an anonymous/legacy event cannot bypass it.
- Stopping the final subscriber clears both cached frame and active binding, so a later subscription
  never receives the previous target's thumbnail.

## Recording rules

- Initial-frame readiness remains bounded by the existing timeout/retry configuration.
- The first valid event pins the recording source before its first frame is queued.
- A frame with a missing or unequal binding is dropped and counted as `sourceMismatchFrames`.
- Recording ID, trigger, start timestamp, frame timestamp, FPS, codec fallback, and persisted replay
  metadata retain their established formats.
- CFR duplication may repeat the last accepted frame when capture pauses; it can never introduce a
  frame from a different source.

## Diagnostics

`/api/debug/viewport/pipeline` adds source transitions, stale-source dispatches, and a sanitized
source description. Recording metrics add the pinned source and mismatch count. These additions do
not remove or rename existing fields.

## Host ownership

Target discovery resolves local processes only and stamps them with
`builder-goal.launcher.local-machine-uuid`. A remote Builder Goal launch is captured by the
ChromaScape instance on that remote host through the pre-existing routing/stream contracts; this
story does not make one ChromaScape process attach across a host or PID namespace.
