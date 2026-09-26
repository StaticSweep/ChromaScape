# Instructions

## Pathing

- Run ChromaScape commands from this native Fedora checkout using POSIX paths. Use the
  canonical Windows project identity only for Felix or Shortcut references when required.

## ChromaScape operations on this Fedora host

This checkout runs natively on Fedora. For FEDORA-03 work, restart ChromaScape
on this Fedora host. Do not route the restart to the Windows machine.

Use docs/CHROMASCAPE_RESTART_RUNBOOK.md and
docs/upstream-integration/sc-1667/fedora-host-native-runbook.md.

From this repository root, check `scripts/fedora/chromascape-fedora.sh status`,
then run `scripts/fedora/chromascape-fedora.sh preflight`. Resolve reported
preflight failures and run `scripts/fedora/chromascape-fedora.sh restart`.
The wrapper stops only the Fedora instance it owns and starts a replacement;
it also starts one when no owned instance exists. Verify
`http://127.0.0.1:8090/debugger` and report the wrapper's log path.

Configuration belongs in the ignored `.env.fedora.local`, copied from
`.env.fedora.example`. If ChromaScape must restart before the client exists,
set `CHROMASCAPE_FEDORA_REQUIRE_TARGET=false` for that restart, then restore
strict preflight before selecting or attaching to a client.

For FEDORA-03 release evidence, use the packaged-runtime qualification
procedure in docs/upstream-integration/fedora-03/qualification-runbook.md.
A development `bootRun` start is not packaged-runtime certification.


## Windows operations

For Windows checkouts, use `scripts\restart-chromascape.bat` and the ignored `.env.local` as documented in `docs/CHROMASCAPE_RESTART_RUNBOOK.md`. Run Windows restart commands from Windows.

The current session supplies the skill catalog. Do not pin machine-specific skill paths or skill selection rules in this repository file.
