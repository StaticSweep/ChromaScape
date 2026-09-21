# SC-1665 rendered-buffer contract

## Coordinates and ownership

- RemoteInput pixels and `TargetFrameSource.targetBounds()` are client-relative and top-down.
- Public `ScreenManager` rectangles remain screen-space. Linux uses the selected discovery bounds'
  origin when present and logical `(0,0)` otherwise.
- `captureWindow()`, `captureFrame()` and `captureZone()` return caller-owned images. Mutating one
  result cannot mutate native memory or a later/earlier result.
- `captureZone()` clamps a partial intersection and rejects null, empty or disjoint rectangles.

## Native safety

- Capture shares the SC-1664 session monitor and bounded native lane with input and close.
- Dimensions are checked with overflow-safe multiplication before dereferencing the image pointer.
- The pointer is valid only while the paired session and fixed native mapping remain alive.
- Dimensions are sampled again after the copy; a change discards the copy.
- The pinned ABI provides no renderer read lock. Pixel writes may overlap the copy, so a frame is a
  bounded point-in-time observation, not a render-transaction snapshot.

## Freshness

`CapturedFrame` reports observation and last-content-change timestamps plus one status:

- `NEW`: first successful frame from this attachment.
- `CHANGED`: content fingerprint differs from the prior successful frame.
- `UNCHANGED`: content repeats for less than two seconds.
- `PAUSED`: content repeats for at least two seconds.

Freshness describes observed pixel content only. It does not infer client health, tick progress or
visibility. Failed captures never update freshness and never return the prior frame.

## Platform behavior

- Linux: rendered-buffer capture from the active RemoteInput session, independent of desktop
  occlusion in principle.
- Windows: existing GDI `BitBlt` behavior, moved behind `WindowsGdiScreenCapture`.
- Unsupported/no active session: bounded failure; no fallback to a desktop screenshot or a stale
  frame.
