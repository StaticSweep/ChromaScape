# SC-810 Phase 10b — dispatch-readiness confirmed through ChromaScape itself (2026-09-11)

## Scope

Step 1 of `sc810-remaining-work-plan-20260911.md`: confirm dispatch-readiness through
ChromaScape's own `/api/debug/builder-goals/machines` route, not just the raw launcher
endpoint. Read-only only — no launch/dispatch/publish call was made.

## Environment

- ChromaScape-consolidation source commit `56465cfbbe3ca89f25493e87da20df3ee1e8d0a1` (the parent
  of the commit that added this evidence file — no source changed between running the check and
  documenting it).
- Isolated `ChromaScape-consolidation` instance, dedicated port `8195`, dedicated
  `chromascape-sc810-p9-postgres` container (port `15435`, existing data preserved, never
  touched any shared database).
- `local-development` trust environment (`CHROMASCAPE_SOURCE_ENVIRONMENT=local-development`),
  matching the Phase 3-8 integration bundle's stated intent for this kind of check.
- `BUILDER_GOAL_MICRO6_PROJECT_DIR=C:\Dev\IdeaProjects\micro6-sc810-package`.
- Real Ed25519 signing/verification key (`sc810-dev-ed25519-611ab9dd95a518af`) loaded via
  `C:\Dev\SC810\secrets\source-signing-v1\load-sc810-signing-env.ps1` in the same PowerShell
  invocation as the launch (env vars from dot-sourcing do not persist across separate tool
  calls).

## Three real, previously undocumented (or drifted) startup gates hit and resolved

1. `StabilizationAuthFilter` requires `SPRING_PROFILES_ACTIVE=local-development` as the sole
   active Spring profile — a separate mechanism from `CHROMASCAPE_SOURCE_ENVIRONMENT`. Setting
   only the latter (as a first attempt did) is not sufficient; both are required together.
2. `McpBridgeLifecycleManager` auto-start is gated by `analysis.mcp.auto-start`, bound to env
   var `MCP_AUTO_START` (not `ANALYSIS_MCP_AUTO_START` as `phase-9-evidence.md` stated — that
   was a documentation drift, confirmed against `application.yml:315` and
   `McpBridgeLifecycleManager.java:36`). Disabled via `MCP_AUTO_START=false` since
   `scripts\mcp-bridge` doesn't exist in this worktree (unrelated AI-analysis feature).
3. `SourceConfigurationValidator` fail-fast requires `micro6.source.transport.base-url`,
   `micro6.source.transport.auth-token`, `chromascape.source.access.metadata-token`, and
   `chromascape.source.access.content-token` all non-blank, unconditionally at startup,
   regardless of which endpoints this check actually exercises. Supplied a syntactically valid
   placeholder transport URL (`http://127.0.0.1:8099`, not actually running — nothing in this
   check's request path touches it) and generated local-only bearer tokens. These gate the
   debugger's own read endpoints, not identity/provenance material — unlike the Ed25519 signing
   key, no provenance guarantee depends on their specific value (same characterization as
   `phase-9-evidence.md`'s equivalent finding).

## Result: `GET http://localhost:8195/api/debug/builder-goals/machines` — HTTP 200

Request timestamp `2026-09-11T13:27:22.690+02:00` (`2026-09-11T11:27:22.690Z`), confirmed
directly from ChromaScape's own DEBUG dispatcher log
(`http-nio-8195-exec-7 ... DispatcherServlet - GET "/api/debug/builder-goals/machines"`),
handled by `BuilderGoalRunnerController.listMachines()`. This is ~262ms after the returned
machine's own `last_seen` (`2026-09-11T11:27:22.428381Z`) — consistent with the heartbeat
being current at request time, not stale data served from a cache.

Raw response (full machine list, 12 entries) confirms, through ChromaScape's own dispatch
selection logic — not just the raw launcher endpoint:

```json
{
  "machineUuid": "1d391d65-6c97-5b77-b676-964f157e8c73",
  "displayName": "CZC8518C27 (Beheerder)",
  "enabled": true,
  "online": true,
  "runnable": true,
  "localMachine": true,
  "builderGoalRunnable": true,
  "disabledReason": null,
  "builderGoalDisabledReason": null
}
```

This satisfies the plan's exact success criterion: `online: true`, `runnable: true`,
`builderGoalRunnable: true`, `disabledReason: null`.

The stale `CZC8518C27 (ikiddoi)` registration (UUID `c72f5177-f22f-550a-8633-870d01f6acbb`)
correctly shows `online: false`, `runnable: false`, `builderGoalRunnable: false`,
`disabledReason: "Launcher machine is offline"` — not selected, not dispatch-ready. No other
registration shares the `CZC8518C27` hostname in an online state, so the hostname-only
`isLocalMachine()` ambiguity flagged earlier in this engagement remains latent, not active.

## What this does NOT prove yet

- No launch/dispatch/publish endpoint was called (out of scope for this step, and blocked
  regardless by the account-record migration gate — `docs\CHROMASCAPE_RESTART_RUNBOOK.md`'s
  "Real Fishing publication is currently BLOCKED" section, hash `3720a9a3b5c9f57b` vs.
  `b01c7f006bace664`, still applies unchanged).
- `catalog_state` (schema/validation catalog defect, pre-existing, out of scope per prior
  decision) was not re-checked in this instance.
- Fishing Training visibility/publishability was not exercised.

## Cleanup

Confirmed by full command-line inspection that PID `40880` (`java.exe`, started
`2026-09-11 09:49:22`, `ChromaScape-consolidation` classpath, owner of the
`LISTEN` socket on port `8195` per `Get-NetTCPConnection`) was this session's own process.
Stopped it (`Stop-Process -Force`); confirmed port `8195` no longer accepts connections
afterward. Stopped (not removed) `chromascape-sc810-p9-postgres`, preserving its data.
