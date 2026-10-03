# SC-1667 Shortcut update

SC-1667 implementation is ready for review on branch
`codex/sc-1667-fedora-host-native-preflight`, based on merged integration commit
`97482af3780277d6510e07b5f0640bd1721b92cb`.

Review PR: <https://github.com/ikiddoi/ChromaScape/pull/11>

Delivered:

- a non-executing, allowlisted `.env.fedora.local` configuration contract;
- read-only Fedora/JDK/project/native/client readiness diagnostics with stable codes;
- SC-1663-compatible eligible-client exclusions and explicit ambiguity refusal;
- same-user, PID-namespace, Flatpak/bwrap, no-new-privileges, seccomp, ptrace and SELinux facts;
- no automatic host security changes;
- an owned `start/status/stop/restart` process-group lifecycle fenced by PID, start ticks and boot ID;
- atomic process-identity state publication, lifecycle serialization and stale-PID refusal;
- a host-native operator/recovery runbook and canonical restart-runbook link;
- a retained 19-case fixture regression task.

Verification currently passes: Fedora fixture regressions 19/19, native metadata 47/47, focused
SC-1662/1663/1664 Java tests 171/0 failures/1 Windows-only skip, Bash syntax, whitespace and privacy
checks. The ordinary suite ran 2,425 tests with 100 inherited failures and 14 skips (eleven fewer
failures than the preceding integration evidence); replay retained its 280/13 baseline. Checkstyle
exits successfully with inherited repository warnings; Spotless retains 16 pre-existing Java
offenders. SC-1667 changes no Java source.

Honest qualification boundary: the real host verifies the retained native artifact pair and the
startup-before-client path. No eligible RuneLite/Micro6 client was running, so live attachment,
input, release and restart reattachment remain NOT RUN rather than PASS. A real restricted sandbox
was not modified; its refusal paths are fixture-proven.

Felix reconciliation was attempted with the canonical project path. It contains two unrelated
SC-1498 tasks and no task tagged `shortcut:sc-1667`; no duplicate Felix task was created. Shortcut
remains the delivery record.
