# FEDORA-03 — live recording and debugger qualification

> **FROZEN PLAN.** Implementation started on this revision. The plan below is the agreed scope and
> acceptance contract; changes require an explicit amendment in this document rather than silent
> drift.

## 1. Story

**Shortcut:** [sc-1791](https://app.shortcut.com/leeuwbot/story/1791) —
*FEDORA-03: Qualify live recording and the debugger on Fedora* (epic 1660).

**Goal:** prove the FEDORA-02 packaged ChromaScape runtime records a real host-native RuneLite/Micro6
client and serves a working debugger live view on Fedora, with fail-closed evidence.

**Base revision:** `3df14733` (`codex/fedora-packaging-certification`, the FEDORA-01/02 delivery).
**Branch:** `codex/fedora-03-live-recording-debugger`.
**Worktree:** `C:\Dev\IdeaProjects\ChromaScape-fedora-03`.

**State at freeze (from FEDORA-02):**

| Capability | FEDORA-02 result |
|---|---|
| Packaged install / start / loopback / `/debugger` | PASS |
| Upgrade / rollback / uninstall / no residue | PASS |
| Target discovery and explicit selection | NOT_RUN — no client |
| Packaged native attachment, frame capture, bounded input, release, close | NOT_RUN — no client |
| Recording and debugger live view | Not exercised |

## 2. Scope

### In scope

- Add a Fedora-03 live qualification harness with a fail-closed evidence contract and unit tests.
- Install the packaged tarball into a private prefix without sudo and wire the locally built
  RemoteInput pair into the installed release (`native/linux-x86_64`).
- Start the packaged runtime as the desktop user that owns the client with the qualification
  controller enabled and attachment enabled, loopback-only, with throwaway deployment config.
- Discover and explicitly select the real client.
- Attach, capture bounded frames, run bounded mouse/keyboard input, release every held input, close
  cleanly, and re-open after a runtime restart.
- Start and stop a manual recording, then verify: non-empty MP4, observed frame count, FINALIZED
  `recording_sessions` row, download parity, and the recorded source binding matching the selected
  target with no source-mismatch frames.
- Qualify the debugger live view: `/debugger` 200, `/ws/viewport` binary frames, viewport pipeline
  source binding, debugger tick-stream connection, recording link resolution, and session export
  containing the linked video.
- Record redacted evidence tied to the exact package revision, package hash, native pair identity
  and artifact hashes; update the runbook and rollback notes.

### Out of scope

- Shipping `libRemoteInput.so` in the package. The provenance marker stays `unresolved` and the
  licence gate is not weakened; the pair is wired into the local install only.
- RPM publication, signing, repository hosting or system installation.
- Windows behavior changes.
- Recording or debugger feature work beyond defects the live matrix exposes.
- Host security changes: no SELinux, ptrace, seccomp, firewall or namespace modification.

### Constraints

- The installed runtime must not resolve into a source checkout.
- The qualification controller stays disabled unless explicitly enabled by the harness.
- Loopback-only binding remains the default.
- No secret, username, or machine-specific mount path is retained in evidence.

## 3. Safety invariants

1. ChromaScape binds to `127.0.0.1` only.
2. The qualification controller is enabled only for the harness-owned instance and refuses
   non-loopback callers.
3. Input is bounded: coordinates inside the selected target's bounds, key holds ≤ 5000 ms, and every
   hold is followed by a release in a `finally` path.
4. A poisoned session is never reported as closed; a poisoned identity requires a runtime restart.
5. The native pair is never copied into a package; it is only placed in the local installed release.
6. No compiled `.so` and no packaged artifact is committed.
7. Evidence is fail-closed: an unexecuted case is `NOT_RUN` with its missing prerequisite, and the
   release gate refuses an incomplete matrix.
8. The harness removes its temporary prefix, its disposable Postgres container and its runtime
   process on every exit path.

## 4. Architecture

The qualification path and identity chain are recorded in the Felix architecture note and mirrored
in `qualification-runbook.md`: packaged release → runtime with qualification enabled → explicit
target selection → qualification session → bounded capture/input → recording API → debugger
viewport/tick stream → fail-closed evidence.

## 5. Check matrix

| Acceptance criterion | Check |
|---|---|
| Packaged runtime serves loopback-only `/debugger` | `debuggerEndpoint`, `runtimeRestart` |
| Client discovered and explicitly selected | `liveTargetDiscovery`, `liveExplicitSelection` |
| Attachment binds the selected identity | `qualificationAttach` |
| Bounded rendered-frame capture | `qualificationCapture` |
| Recording start/stop produces an artifact | `recordingStartStop`, `recordingArtifact` |
| Database session finalized | `recordingSessionFinalized` |
| Recording downloadable and parity-checked | `recordingDownload` |
| Frame-source binding matches the selection | `recordingSourceBinding` |
| Debugger live view streams target frames | `debuggerViewportFrames`, `debuggerSourceBinding` |
| Debugger tick stream accepts the client session | `debuggerTickSession` |
| Recording link resolves without repair | `debuggerRecordingLink` |
| Session export contains the linked video | `sessionExportVideo` |
| Bounded input and complete release | `boundedInput`, `inputRelease` |
| Clean close, no poison | `cleanClose` |
| No residue after the run | `noResidue` |

## 6. Phases

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Frozen plan and runbook | DONE |
| 2 | Fail-closed evidence contract + unit tests | DONE |
| 3 | Live harness (install, native overlay, runtime, checks) | DONE |
| 4 | Client launch and explicit selection | DONE (canonical `:client:runBuilderGoal` from `micro6-sc1618-fedora`, pid 150259) |
| 5 | Live matrix execution and evidence | DONE, partial matrix: 17 PASS, 3 NOT_RUN (no tick session), 0 FAIL |
| 6 | Automated verification and regenerated evidence | DONE (record VALID; `--require-complete` correctly refuses) |
| 7 | Review PR and Shortcut handoff | IN PROGRESS (PR #25 open; tick-session/in-game follow-ups remain) |

## 7. Definition of done

The story is **Completed — Review Required** only when the live matrix has honest PASS/NOT_RUN
evidence against the final revision, the evidence gate validates the record, the harness regressions
and focused tests pass, and the branch is pushed with a review-required PR. There is no waiver that
converts a NOT_RUN into PASS.
