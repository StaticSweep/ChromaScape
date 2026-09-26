# Shortcut story update — SC-1668

**Status: Completed — Review Required**

Fedora RemoteInput attachment now completes end-to-end against a real RuneLite/Micro6 client.

## Root cause

On Fedora/XWayland the client owns two windows with the recognised
`net-runelite-client-RuneLite` WM_CLASS: a `SKIP_TASKBAR` splash window and the real client frame.
The pinned native `EnumWindowsProc` returned the first `_NET_CLIENT_LIST` match, so the reflection
probe was driven against a window with no AWT Canvas; `Reflection::Create` returned null and no
`ControlCenter` rendezvous was created, producing `INJECTION_TIMEOUT`.

## Change

- Linux-only native patch: collect all recognised target-owned windows, prefer non-skip-taskbar
  windows, and try each until `Reflection::Create` succeeds; add opt-in bounded stage diagnostics.
- Poisoned-session close semantics: a release timeout poisons the lane, retains the lease, and
  reports `SESSION_POISONED` with `restartRequired=true` instead of a false success.
- One total monotonic attachment deadline shared by every native enumeration/injection/poll call.
- Reproducible Fedora qualification profile and a read-only bounded diagnostic collector.

## Verification

| Check | Result |
|---|---|
| Focused Java tests | 145/145 |
| Native metadata tests | 47/47 |
| Fedora preflight regressions | 47/47 |
| Native shell/regression suites | all PASS |
| Live attachment / capture / input / release | PASS |
| Repeated attachment, target restart, resize/minimize | PASS |
| Multiple-client selection | PASS |
| Ordinary / replay suites | no new failure identities |

## Native artifact

`libRemoteInput.so` SHA-256 `65606e682f46756af8c62b15cfed555115f434604674d9facc761a9f5dd2426f`,
pair identity `a834ff8760f38991d167cfda06122d241a031c553e1709cf40ed43b36d913243`, local patch
`sha256:7af654074906652c1bff730071978e56f1e0c6846f217962eedf7196bc9661e6` — rebuilt in the pinned
Podman toolchain (repeat build byte-identical) and verified by the complete live-matrix rerun of
2026-09-19; see `verification-evidence.md`. The pre-remediation pair (`b0c8d365...`/`43b38e8f...`,
patch `sha256:a7744da1...`) is superseded history. No `.so` is committed.

## Next story

Fedora proof-of-concept packaging (relocatable tar and RPM with install, upgrade, smoke, and
uninstall verification) starts only after this story merges.

## Review

Review-required PR: https://github.com/ikiddoi/ChromaScape/pull/15
