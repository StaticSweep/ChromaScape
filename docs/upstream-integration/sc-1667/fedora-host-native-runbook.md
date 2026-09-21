# Fedora host-native ChromaScape runbook

## 1. Requirements

- Fedora Linux x86-64.
- A JDK 17 installation selected through `JAVA_HOME`.
- Host-native ChromaScape and Micro6 checkouts. Do not launch either application through Flatpak,
  bwrap, a container, or a different PID namespace when native attachment is required.
- Python 3 when RemoteInput attachment is enabled, for manifest verification.
- A locally built/staged SC-1662 `libRemoteInput.so` and sibling
  `remoteinput-manifest.json` when attachment is enabled.

The wrapper does not install packages or change host policy.

## 2. Configure a fresh checkout

From the Fedora ChromaScape checkout:

```bash
cp .env.fedora.example .env.fedora.local
```

Set at least:

```dotenv
JAVA_HOME=/opt/jdk-17
BUILDER_GOAL_MICRO6_PROJECT_DIR=/opt/micro6
BUILDER_GOAL_LAUNCHER_LOCAL_MACHINE_UUID=<this-host-uuid>
```

To enable the proof-of-concept attachment:

```dotenv
CHROMASCAPE_REMOTEINPUT_ATTACHMENT_ENABLED=true
CHROMASCAPE_REMOTEINPUT_APPLICATION_ROOT=/opt/chromascape/build/dist
# Or use the exact file instead of the application root:
# CHROMASCAPE_REMOTEINPUT_LIBRARY=/opt/native/libRemoteInput.so
```

Use absolute paths. Existing non-empty environment variables override the local file. The parser
accepts simple allowlisted application `KEY=VALUE` entries with one optional pair of matching
quotes; it rejects shell-control variables, never executes the file, and never prints values.

When unset, the wrapper pins the HTTP server and the LeeuwBot stream, Builder Goal launcher/catalog,
Micro6 executor, gameplay bootstrap, and candidate registry endpoints to loopback. It does not set,
print, or persist credentials. Override an endpoint or token only when this Fedora host is
deliberately connected to that trusted service.

## 3. Launch the client host-native

Launch RuneLite/Micro6 directly from the Fedora user session. Do not use a Flatpak RuneLite build or
a containerized Gradle client. ChromaScape recognizes either:

- `net.runelite.client.RuneLite`; or
- `runBuilderGoal` together with the configured absolute Micro6 project root.

If more than one eligible client is running, set the diagnostic selector before preflight:

```dotenv
CHROMASCAPE_FEDORA_TARGET_PID=12345
```

This selects the process for preflight checks only. Select the same identity through ChromaScape's
window/target API or debugger before opening input or capture.

## 4. Run preflight

```bash
scripts/fedora/chromascape-fedora.sh preflight
```

Success ends with:

```text
SC1667_PREFLIGHT status=READY errors=0 warnings=<count> targetPid=<pid>
```

Important diagnostics:

| Code | Meaning and action |
|---|---|
| `JAVA_UNSUPPORTED` | Set `JAVA_HOME` to JDK 17. |
| `MICRO6_PROJECT_INVALID` | Configure an absolute existing Micro6 checkout. |
| `NATIVE_PAIR_INVALID` | Rebuild/stage SC-1662; never substitute an unverified library. |
| `TARGET_MISSING` | Start RuneLite/Micro6 host-native, then rerun preflight. |
| `TARGET_AMBIGUOUS` | Set the exact eligible `CHROMASCAPE_FEDORA_TARGET_PID`. |
| `PID_NAMESPACE_MISMATCH` | Relaunch both processes in the same host PID namespace. |
| `SANDBOX_RESTRICTED` | Replace Flatpak/bwrap/container launch with a host-native launch. |
| `TARGET_USER_MISMATCH` | Run both applications under the same Fedora user. |
| `PTRACE_POLICY_RESTRICTED` | Review the host policy with an administrator. The script will not change it. |
| `SELINUX_ENFORCING` | Informational. If injection fails, inspect relevant AVC denials; do not disable SELinux blindly. |
| `SECCOMP_ACTIVE` | Inspect the client launcher policy if injection fails. |

## 5. Start, inspect, stop, and restart

```bash
scripts/fedora/chromascape-fedora.sh start
scripts/fedora/chromascape-fedora.sh status
scripts/fedora/chromascape-fedora.sh stop
scripts/fedora/chromascape-fedora.sh restart
```

`start` requires preflight and waits for `http://127.0.0.1:8090/debugger` by default. Runtime state
and logs live under `.chromascape/fedora/`, which is ignored by Git. The state records PID, process
start ticks, and host boot ID. `stop` refuses to signal a recycled PID or a process that is not the
recorded process-group leader, sends TERM first, and uses KILL only after the configured bounded
timeout.

If ChromaScape must start before the client, explicitly set
`CHROMASCAPE_FEDORA_REQUIRE_TARGET=false`, start ChromaScape, launch the client, restore the strict
setting, and rerun `preflight` before selection/attachment.

## 6. Recovery

1. Run `status`.
2. Run `stop`; do not delete or rewrite the state file to force ownership.
3. If stop reports `no-owned-instance` or `stale-identity-not-signalled`, inspect the process
   manually. A stale record is retained as `chromascape.state.stale`; the wrapper deliberately will
   not kill a process whose identity no longer matches.
4. Correct the reported preflight condition.
5. Rerun `preflight`, then `restart` or `start`.

Never respond to a failure by automatically setting `kernel.yama.ptrace_scope`, granting
`CAP_SYS_PTRACE`, disabling SELinux, weakening seccomp, or escaping a sandbox. Those are explicit
operator/security decisions outside this story.
