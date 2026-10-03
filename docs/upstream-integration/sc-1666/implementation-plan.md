# SC-1666 implementation plan

> Frozen implementation record. This document describes the plan executed on 2026-09-18; later
> status belongs in the evidence documents.

## Objective

Carry the SC-1665 rendered-buffer provider through ChromaScape's existing recording, debugger
viewport, screen-stream, replay, and Builder Goal host-routing boundaries without replacing the
public `FrameEvent`, `FrameListener`, `ScreenManager`, or recording APIs.

Base: integration merge `c734e23d7dd6d9a0017d8262310bab679314ae18`.

## Required invariants

1. Every Linux frame retains the exact local host, process identity, and selection generation that
   owned the SC-1664 attachment.
2. Two hosts with numerically identical PIDs cannot share a frame stream or recording.
3. A selection change invalidates the cached frame and queued callbacks from the displaced target.
4. Recording readiness pins the first valid target binding; later frames from another binding are
   refused while recording identifiers, timestamps, and existing metadata remain unchanged.
5. Windows capture remains available and refuses a frame if selection changes during pixel capture.
6. Existing debugger, recording, replay, and launcher contracts remain source-compatible. No new
   remote-frame protocol is introduced.

## Implementation sequence

1. Introduce a host-qualified frame-source value distinct from `ProcessIdentity` equality.
2. Extend `FrameEvent` additively with optional binding and freshness metadata.
3. Make the production frame provider retain SC-1665 `CapturedFrame` metadata.
4. Fence unified capture caching and asynchronous delivery by exact binding.
5. Pin recording startup to the first valid binding and expose sanitized mismatch diagnostics.
6. Add debugger pipeline diagnostics without exposing titles, accounts, command lines, or secrets.
7. Cover host collision, generation change, queued-frame rejection, Windows selection races,
   readiness binding, and a real MP4 encode/finalize round trip.
8. Run focused, ordinary, replay, formatting, Checkstyle, and whitespace gates; retain honest
   NOT RUN status for live multi-host and Windows runtime qualification.

## Non-goals

- No database migration or change to replay identifiers.
- No automatic target selection after a Builder Goal launch.
- No remote pixel transport redesign.
- No system security, sandbox, ptrace, or SELinux changes.
- No claim that live Fedora multi-host or Windows runtime checks passed without execution.
