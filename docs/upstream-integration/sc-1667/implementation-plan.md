# SC-1667 — Fedora host-native startup and attachment preflight specification

Status: implementation record; acceptance results belong in `verification-evidence.md`.

## Objective

Provide a deterministic Fedora startup and preflight path for co-located ChromaScape and
RuneLite/Micro6. The preflight must prove what it can observe, fail closed when attachment is unsafe
or ambiguous, and give actionable diagnostics without weakening host security.

## Boundaries

- Fedora Linux x86-64 and JDK 17 are the supported host/build contract.
- ChromaScape and its target must run as the same user, in the same host PID namespace, outside
  Flatpak/bwrap isolation.
- The SC-1662 library/manifest pair is optional while attachment is disabled and mandatory when it
  is enabled. Preflight validates the pair but never loads, injects, pairs, captures, or sends input.
- Client discovery mirrors SC-1663: the RuneLite main-class marker, or the Builder Goal entry point
  plus the configured Micro6 root; Gradle daemons/workers and the remote-launcher agent are excluded.
- Multiple eligible clients require `CHROMASCAPE_FEDORA_TARGET_PID`; this value scopes preflight
  only. The application still requires an explicit identity-backed UI/API selection before use.
- The wrapper never executes `.env.fedora.local`; it parses simple allowlisted application
  `KEY=VALUE` assignments, refuses shell-control variables, and preserves existing non-empty
  environment variables.
- Unset integration, stream, launcher, executor, registry, and server endpoints default to
  loopback. Existing non-empty environment values may deliberately override them. The wrapper does
  not invent an API token or credential.
- No command changes ptrace scope, process capabilities, seccomp, SELinux, namespaces, firewall
  rules, or system package state.
- No secret, process command line, account name, environment value, or native pointer is emitted.

## Components

### Configuration

`.env.fedora.example` documents machine-local values. `.env.fedora.local` is ignored by Git. Existing
process environment values take precedence over the local file.

### Read-only preflight

`scripts/fedora/lib/preflight.sh` emits stable `PASS`, `INFO`, `WARN`, and `ERROR` records plus one
terminal `SC1667_PREFLIGHT` record. It checks:

1. Fedora, x86-64, JDK 17, project root, Gradle wrapper, and the absolute Micro6 root.
2. Attachment enablement and the configured SC-1662 library/manifest identity through
   `remoteinput_metadata.py verify-manifest`.
3. Eligible local clients without persisting or printing their command lines; the preflight process
   itself is excluded exactly as in SC-1663.
4. Explicit selection when multiple clients exist.
5. Same-user and same-PID-namespace placement.
6. Flatpak/bwrap ancestry, no-new-privileges, seccomp mode, ptrace scope/CAP_SYS_PTRACE, and observed
   SELinux mode.

Errors return exit 2. Warnings remain visible but do not pretend that an unproven restriction is a
failure. Disabled attachment is a warning: discovery remains useful but attachment is not ready.

### Owned lifecycle

`scripts/fedora/chromascape-fedora.sh` supplies `preflight`, `start`, `status`, `stop`, and `restart`.
`start` passes preflight, creates a new process group for `gradlew --no-daemon bootRun`, records PID,
procfs start ticks, and boot ID, and waits for `/debugger`. `stop` signals only that exact recorded
identity and process group. A stale, recycled, malformed, or non-group-leader identity is never
signalled; stale state is preserved with a `.stale` suffix. A successful stop removes the live state
file so a later attachment starts cleanly.

## Acceptance mapping

| Story criterion | Implementation proof |
|---|---|
| Locate runtime, native library, and client | stable JDK/project/native/client preflight records |
| Explain sandbox/PID restrictions | namespace, sandbox, no-new-privileges, seccomp, and ptrace records |
| Fresh shell/reboot and non-default checkout | script-relative project root, absolute local config, boot-ID-fenced state |
| Predictable diagnostics without secrets | stable codes; no values/command lines/account data logged |
| Clean shutdown/restart | owned process-group stop fenced by PID + start ticks + boot ID |

## Verification plan

- Bash syntax for every tracked Fedora script.
- Fixture regressions for parser safety, ready host, namespace mismatch, Flatpak, ptrace, ambiguity,
  explicit selection, exclusion rules, native pair routing, missing native input, clean stop, and
  PID-reuse refusal.
- Real-host preflight on Fedora with honest READY/BLOCKED output.
- Existing SC-1662/1663/1664 focused Java and native-metadata tests.
- `git diff --check`, changed-file formatting/static checks, and privacy scan.
- Live attachment/restart is PASS only if a real eligible client and licensed local artifact are
  deliberately enabled. Otherwise it remains NOT RUN, not inferred from fixtures.
