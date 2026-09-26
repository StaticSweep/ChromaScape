# Fedora live qualification — native attachment failure

> **SUPERSEDED (historical pre-fix evidence).** This document records the failure observed before the
> Linux window-selection patch. The final run in `live-run.md` and `attachment.log` passes. Retained
> only as the pre-fix baseline; do not read it as the current status.

**Status: FAIL (not a pass).** Live native attachment did not succeed on this host.

## Symptom

`POST /api/qualification/session/open` returned HTTP 409:

```json
{
  "status": "error",
  "error": "INJECTION_TIMEOUT",
  "message": "Injected client for the selected pid did not register before the deadline",
  "diagnostic": "INJECTION_TIMEOUT"
}
```

This reproduces with the client on both JDK 25 and JDK 17, so it is not a JVM-version issue.

## What did work

- Discovery, selection, and the harness request path are correct.
- The native library **was injected**: `/proc/<client>/maps` contained four mappings for
  `libRemoteInput.so` (r-xp/r--p/r--p/rw-p), i.e. kubo-injector attached via ptrace and `dlopen`ed the
  library into the target.

## Where it fails

`RemoteInputSession.ensureInjectedClient` calls `EIOS_Inject_PID(pid)` and then polls
`EIOS_GetClients`/`EIOS_GetClientPID` for `options.injectTimeout()` (10 s). The client never appears.

The injected library's load-time constructor (`Plugin/Plugin.cxx` `__load()`) spawns a detached thread
that calls `GetNativeReflector()` and only then constructs `ControlCenter(getpid(), ...)` and
`StartHook()`. `ControlCenter` creates the POSIX shared object `RemoteInput_<pid>` and signals
`/RemoteInput_ControlCenter_SyncSignal_<pid>`. After the failed attempts, **neither exists**:

```
$ ls /dev/shm
libpod_rootless_lock_1000
lttng-ust-wait-8-1000
```

So the injected constructor never reached `ControlCenter` initialization: `GetNativeReflector()`
returned null (or its detached thread did not complete).

## Prerequisites checked

`GetNativeReflector()` (Linux) requires a live JVM, `libawt_xawt.so`, the `awt_GetComponent` symbol, and
an X11 window whose `_NET_WM_PID` equals the target pid and whose class is one of
`jagexappletviewer` / `net-runelite-client-RuneLite`. All of these were independently confirmed present:

| Prerequisite | Observed |
|---|---|
| `DISPLAY` in the client | `:0` (XWayland; `WAYLAND_DISPLAY=wayland-0`) |
| `libjvm.so` mapped | yes |
| `libawt.so`, `libawt_xawt.so` mapped | yes |
| `awt_GetComponent` exported | `T awt_GetComponent` in `libawt_xawt.so` |
| Matching X11 window | present with `res_class=net-runelite-client-RuneLite` and `_NET_WM_PID=<pid>` |

ptrace is also not the blocker:

- `/proc/sys/kernel/yama/ptrace_scope` = `0`
- `getsebool deny_ptrace` → `off`
- `gdb -p <client>` attaches and detaches cleanly (exit 0)

SELinux is `Enforcing`. AVC denials could not be retrieved (`ausearch` requires interactive sudo), so a
SELinux denial is not excluded, but `deny_ptrace` is off and injection itself clearly succeeded.

## Remaining hypotheses

1. **Java deadline shorter than the native reflection budget.** The Java side waits 10 s, while
   `GetNativeReflector` can spend up to 5 s (JVM) + 20 s (`libawt_xawt`) + 20 s (window) before
   returning null, and `wait_for_sync` only waits 5 s. A slow-but-successful reflection would still be
   reported as `INJECTION_TIMEOUT`. However, the absence of any `RemoteInput_<pid>` object minutes later
   indicates reflection ultimately failed, not merely that it was slow.
2. **Window selection ambiguity.** Enumeration matched ~20 X windows with the correct class/pid
   (XWayland reparenting produces many children). If the library selects a non-frame window,
   `awt_GetComponent`/`Reflection::Create` fails.
3. **Injected detached thread.** The constructor's detached `std::thread` may not complete in the
   ptrace-injected context.

## Not done, and why

- No machine-wide security setting was relaxed (no `ptrace_scope`, `setcap`, SELinux, seccomp, or
  namespace change).
- No patch was written to the vendored `third-party/RemoteInput` sources: SC-1662's provenance gate
  requires any local patch to be declared with a derived identity, and the sources carry no licence.
- Inject timeout is currently hard-coded (`RemoteInputSessionOptions.DEFAULT`), so hypothesis 1 was not
  tested live. Making it a bounded, configurable value is the recommended next step.

## Recommended next steps

1. Make the inject/pair timeouts bounded configuration (`chromascape.remoteinput.*`) so a slow
   reflection can be distinguished from a failing one on a real host.
2. Capture native stderr/`strace` for the injected thread on a host with developer tooling to isolate
   the reflection step that returns null.
3. If window ambiguity is confirmed, add a documented local native patch (with patch identity) that
   selects the AWT top-level frame deterministically.
