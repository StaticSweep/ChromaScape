# SC-1664 — target-owned input lifecycle

> Frozen implementation record. This describes the implemented design and its acceptance gates; it
> is not a claim that host-specific verification has run.

## Objective

Replace controller-owned native handles with one platform-neutral input session bound to the exact
selected process identity and selection generation. Linux uses the audited SC-1662 RemoteInput ABI;
Windows retains KInput behind the same contract. Attachment remains opt-in and fail-closed.

## Prerequisites and scope

- Base: integration head `cc9de8c7`, which contains merged SC-1662 and SC-1663.
- In scope: attachment ownership, selection fencing, bounded native calls, client-relative geometry,
  held-input cleanup, controller lifecycle integration, Linux/Windows isolation, tests and evidence.
- Out of scope: rendered-buffer capture (SC-1665), producing or packaging a native artifact,
  automatic target switching, and claiming that releasing a target unloads injected target code.

## Implementation specification

1. `TargetInputSession` is the only input contract consumed by the controller utilities. It owns one
   exact `ProcessIdentity`, one selection generation and one client-relative coordinate space.
2. `TargetInputSessionFactory.openSelectedSession()` is the only production attachment entry point.
   It refuses missing, remote, ambiguous, replaced or stale selections before input is possible.
3. A process-wide lease is acquired before native enumeration, injection, pairing or Windows backend
   construction. A second session for the same identity fails closed.
4. Linux resolves the native artifact only through the SC-1662 resolver and loader. Attachment is
   disabled by default and requires an explicit application root or explicit library override.
5. Linux enumerates bounded native clients by exact PID, injects at most once, waits to a deadline,
   requests the exact target, validates its dimensions and serializes every native call.
6. A native timeout or caller interruption poisons the call lane. A poisoned or uncertain native
   target retains its lease so another session cannot race an operation still executing in native
   code.
7. Windows constructs KInput only after the first selection fence, applies a second fence after the
   side effect, and destroys the backend on a failed second fence. Windows classes do not enter the
   Linux runtime path.
8. Every operation revalidates exact identity and generation. Public mouse coordinates are
   client-relative and converted exactly once.
9. Close is idempotent. Held keys and buttons are released before the target/backend; the lease is
   released only after successful target/backend cleanup. A non-poisoning cleanup failure can be
   retried.
10. `Controller` initializes resources atomically and shuts them down idempotently. Partial startup
    closes every acquired resource and returns to `STOPPED`.

## Acceptance gates

- Compile and focused fake-native/unit tests pass on JDK 17.
- SC-1662 loader/ABI and SC-1663 discovery/selection regressions remain green.
- Compiled isolation checks show no Windows references on the Linux session path and no
  RemoteInput/EIOS references in the Windows adapter.
- Checkstyle introduces no warning in changed SC-1664 files; formatting and `git diff --check` pass.
- Ordinary, replay and launcher suites show no new failure identities relative to the integration
  baseline.
- A disposable live Linux client proves attach/input/cleanup and repeated lifecycle behavior before
  the feature is enabled outside the proof of concept.
- Windows runtime verification is recorded on Windows before declaring cross-platform completion.
- PR review and merge are required before the Shortcut story moves to Done.

