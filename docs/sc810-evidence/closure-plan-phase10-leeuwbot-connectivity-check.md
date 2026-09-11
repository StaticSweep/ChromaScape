# SC-810 Phase 10 — LeeuwBot connectivity re-check (2026-09-11)

## Result: partial improvement, not a full unblock

Earlier this session (Phase 9, 2026-09-10), `GET /api/debug/builder-goals/machines` returned
`ConnectException` — the launcher/bridge was entirely unreachable. Re-checked at the user's
request:

- `leeuwbot-chromascape-bridge` docker container is now **up and healthy**, bound to
  `192.168.1.6:8092` specifically (not `localhost`/`0.0.0.0`, which is why an initial
  `curl localhost:8092` still failed — `curl http://192.168.1.6:8092/health` succeeds).
- The launcher (`192.168.1.6:8084`) is network-reachable (confirmed via direct curl).
- Relaunched an isolated `ChromaScape-consolidation` instance (dedicated port 8195, restarted the
  existing dedicated `chromascape-sc810-p9-postgres` container on 15435, `local-development`
  trust environment matching the Phase 3-8 integration bundle) purely to exercise the real
  `/api/debug/builder-goals/machines` route (traced to
  `BuilderGoalRunnerController` → `BuilderGoalLauncherControlService`, default
  `http://192.168.1.6:8084`). Hit a new, previously-undocumented startup gate along the way:
  `chromascape.source.public-key-base64 is required` (`CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64`/
  `_ID` env vars) — supplied from the same real Ed25519 key already used throughout this session.
- **`GET /api/debug/builder-goals/machines` now returns HTTP 200** with a real 12-machine list —
  a genuine change from `ConnectException`.
- **However, every machine is reported `"online": false"`, `"status": "unknown"`,
  `"disabledReason": "Launcher machine is offline"`** — including this machine, `CZC8518C27`,
  listed twice (once per registered owner, `Beheerder` and `ikiddoi`). The launcher HTTP API/
  bridge layer is reachable; the actual local launcher agent/daemon that would report a machine
  as online and dispatch-ready is not running or not currently reporting in.

## Net effect on the plan

Phase 10 steps 1-4 (resolve endpoint, verify routing, call health, confirm
`/machines` succeeds) are now genuinely satisfied — this is real, verified progress, not
fabricated. Steps 5-9 (confirm `CZC8518C27` present **and dispatch-ready**, confirm the final
Micro6 executor's health identity matches, confirm no stale executor, confirm Fishing Training
publishable) are **not** satisfied: no machine reports online, so a genuine Fishing recording
(Phase 12) still cannot proceed. Starting whatever local launcher agent/service would make this
machine report online was not attempted — that's a different, more invasive action (starting a
persistent local agent/daemon) than the read-only connectivity check this phase asked for, and
wasn't part of what was authorized.

## Cleanup

Confirmed by full command-line inspection which of two running `java.exe` processes was this
session's own (PID 60240, `ChromaScape-consolidation`, port 8195) versus a different operator's
unrelated canonical-`ChromaScape` process (PID 94364, left untouched). Stopped only PID 60240;
confirmed `/debugger` unreachable afterward. Stopped (not removed) the dedicated
`chromascape-sc810-p9-postgres` container, preserving its data.
