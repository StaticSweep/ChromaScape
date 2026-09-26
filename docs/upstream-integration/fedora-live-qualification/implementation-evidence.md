# Fedora RemoteInput attachment completion — implementation evidence

This document records what was implemented and why, against the frozen plan in
`implementation-plan.md`. It is descriptive; acceptance status lives in `verification-evidence.md`.

## Java: poisoned release and total deadline

- `RemoteInputSession.close()` now poisons the lane on a held-input or target-release timeout,
  retains the attachment lease, and throws the original `RELEASE_TIMEOUT`. A second close on a
  poisoned session throws `SESSION_POISONED`; a normal close stays idempotent. `calls.close()` and
  `lease.close()` run only after every native release succeeds.
- `NativeCallExecutor` distinguishes a deliberately closed lane from a poisoned one (a closed lane
  is not reported as poisoned) and uses nanosecond-precision call deadlines.
- Attachment uses one monotonic deadline. `EIOS_GetClients`, every `EIOS_GetClientPID`,
  `EIOS_Inject_PID`, and every poll receive `min(nativeCallTimeout, remaining)`; a small minimum
  budget prevents a sub-scheduling-granularity remainder from poisoning the lane.
- `TargetQualificationController` returns 200 only after native release, preserves a failed session
  reference, reports `SESSION_POISONED` with `restartRequired=true` and `sessionOpen=false`, and
  refuses to discard a poisoned session on `/session/open`.

## Fedora profile

- The wrapper defaults `SPRING_PROFILES_ACTIVE=local-development`,
  `CHROMASCAPE_QUALIFICATION_ENABLED=false`, and the capture directory only when unset; an
  operator-supplied value is never overwritten.
- `.env.fedora.example` documents the keys, and the preflight regressions cover default profile,
  preservation, disabled-by-default, duplicate-key rejection, and the empty-value fallback.

## Native diagnostics

- `scripts/native/diagnose-remoteinput-linux.sh` is a read-only, bounded collector (pid ownership,
  ELF identity, narrow output directory; DISPLAY only; filtered maps; X11 inventory; `/dev/shm`;
  SELinux mode and bounded AVCs; library `ldd`/`readelf`; redacted timeline; stage summary).
- A regression suite proves validation, read-only behaviour, DISPLAY-only extraction, redaction, and
  stage-summary accuracy against synthetic fixtures.

## Native patch

The failure was identified on the live host: a RuneLite client owns two windows that share the
recognised `net-runelite-client-RuneLite` WM_CLASS — a `SKIP_TASKBAR` splash window (600x304) and the
real client frame (826x540, focused). The pinned `EnumWindowsProc` returned the first
`_NET_CLIENT_LIST` match, so `awt_GetComponent`/`Reflection::Create` could be driven against the
splash window, the reflection returned null, and no `ControlCenter` rendezvous was created.

The Linux-only patch (`Platform_Linux.cxx`, patch identity
`sha256:7af654074906652c1bff730071978e56f1e0c6846f217962eedf7196bc9661e6`, regenerated through the
metadata workflow after the remediation's local-reference cleanup) collects every recognised
target-owned window, orders non-skip-taskbar windows first, tries each candidate until
`Reflection::Create` succeeds, releases the JNI local reference of every rejected candidate before
the next retry, and adds opt-in bounded stage diagnostics on the detached constructor
thread (`REMOTEINPUT_ATTACH_DIAGNOSTICS=1`). Windows behaviour is untouched. A source-level
regression guards the corrected decision logic, and `PROVENANCE.md` records the patch. The live
run below was executed with the earlier patch `sha256:a7744da1...`; the native pair for the new
identity has not been rebuilt yet (see the NOT RUN table in `verification-evidence.md`).

## Build

The patched pair was built in the pinned Fedora toolchain container with `--repeat-build`; both
builds were byte-identical. `nativeMetadataTests` (47), `stageRemoteInputLinux`, and
`verifyRemoteInputLinux` all pass. No `.so` is tracked by git.
