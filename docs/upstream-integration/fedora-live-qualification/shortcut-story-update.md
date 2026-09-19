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

`libRemoteInput.so` SHA-256 `b0c8d3655876dd23e931a9df48efc87b96f936758e5a18a27988d9ab49a5fefd`,
pair identity `43b38e8f95d8f2dcf2d0ee4dd3ffb86d3cffd2fa975cca4db27970c4d1b03e9d`, local patch
`sha256:a7744da1c54a5646b975d9b7adce5891832394b1d84104bb8009ade551107040`. No `.so` is committed.

## Next story

Fedora proof-of-concept packaging (relocatable tar and RPM with install, upgrade, smoke, and
uninstall verification) starts only after this story merges.
