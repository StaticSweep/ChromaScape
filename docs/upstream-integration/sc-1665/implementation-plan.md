# SC-1665 — rendered-buffer capture

> Frozen implementation record. This describes the implemented design and its acceptance gates;
> it does not turn an unexecuted host/client check into a pass.

## Objective

Capture RuneLite's rendered BGRA buffer through the existing `ScreenManager` API on Linux while
preserving Windows GDI behavior, screen-space coordinates, detached image ownership and existing
consumers. Expose explicit content freshness for later stories without presenting a repeated or
paused frame as newly rendered.

## Prerequisites and scope

- Base: merged integration head `6b17b722`, containing SC-1662 through SC-1664.
- In scope: active-session buffer ownership, bounded native copying, BGRA/BGR/Mat conversion,
  resize and lifecycle fencing, freshness metadata, ScreenManager compatibility, Win32 isolation,
  deterministic tests and evidence.
- Out of scope: shipping RemoteInput in the packaged distribution, starting a client, automatic
  attachment, proving behavior on a minimized live client without one, and consuming freshness in
  SC-1666.

## Implementation specification

1. `RemoteInputSession` is the only Linux rendered-buffer owner. Capture reuses its exact native
   handle, process identity, selection generation, attachment lease, monitor and serialized native
   call lane. A second attachment is never opened for capture.
2. `Controller` publishes a `TargetFrameSource` only after session acquisition and removes exactly
   that source before session close. Partial startup removes it too. Runtime publication refuses
   silent replacement.
3. A capture validates the session fence, reads dimensions, checks positive dimensions, a 16,384
   per-axis bound and a 512 MiB byte bound, rejects a null pointer, copies into a Java byte array,
   re-reads dimensions, and rejects a resize race. Detach cannot race the synchronized copy.
4. The native shared-memory allocation is fixed for the paired target lifetime. The renderer may
   write pixels concurrently because the pinned ABI exposes no read lock; therefore the result is a
   point-in-time copy, not a claim of render-transaction atomicity.
5. BGRA input is decoded top-down to a fresh `TYPE_3BYTE_BGR` image. Alpha is intentionally ignored
   for the opaque game canvas. Every capture and crop owns a new Java allocation; no native pointer
   or prior caller image is returned.
6. `FrameImageAdapter` creates detached, continuous BGR Mats and converts supported 1/3/4-channel
   Mats back to owned Java images. `TemplateMatching` uses this adapter instead of an implicit
   JavaCV conversion.
7. Freshness is derived from a SHA-256 pixel-content fingerprint per attachment: `NEW`, `CHANGED`,
   `UNCHANGED`, then `PAUSED` after two unchanged seconds. Observation time and last-content-change
   time are reported independently.
8. `ScreenManager.captureWindow()`, `captureZone()`, `getWindowBounds()`, `getMonitorBounds()`,
   `toClientBounds()` and `toClientCoords()` retain their signatures. Zones remain screen-space,
   partial overlap is clamped, disjoint/empty zones fail, and the historical public constructor is
   retained.
9. Linux uses the selected window origin when discovery provides one, otherwise logical origin
   `(0,0)`. Dimensions follow the last successful rendered-buffer capture. Windows delegates to an
   isolated GDI class with no Win32/JNA types in the platform-neutral facade.
10. Missing sessions, stale selection, target exit/replacement, closed or poisoned sessions,
    invalid dimensions, missing buffers, resize races and native timeouts all fail bounded. None
    returns a previous successful frame.

## Acceptance gates

- Known multi-row BGRA pixels preserve color, orientation and dimensions in Java and OpenCV.
- Zero/oversized/malformed frames, null pointers and resizing during copy fail closed.
- Crop translation, partial overlap, invalid zones and detached ownership are regression-tested.
- Lifecycle publication cannot be replaced or cleared by a non-owner.
- Linux facade capture initializes with an unusable Win32 library path.
- SC-1662/1663/1664 focused regressions and existing ScreenManager consumers remain green.
- Candidate files are formatter-clean, introduce no Checkstyle warning, and pass `git diff --check`.
- Ordinary and replay suites introduce no new failure identities relative to the integration base.
- A qualified live client proves covered/minimized capture and observes `PAUSED` without claiming a
  fresh render. If no client is available, this remains explicitly NOT RUN for review.
- Windows runtime regression and PR review/merge are required before cross-platform completion.
