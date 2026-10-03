# SC-810 Phase 9 — live verification (2026-09-10)

> **Naming note**: "Phase 9" here refers to the original delivery-verification plan's phase
> numbering, established before the later "SC-810 remaining implementation and closure plan"
> existed. That later plan's own Phase 9 (account-record migration) is a different, separate,
> not-yet-attempted item — held per explicit decision, not evidenced by this file.

## Scope decision (explicit, per your direction)
Adapted a live setup for the actual Phase 8 delivery revision (`ChromaScape-consolidation`)
rather than following the restart runbook's canonical-checkout path verbatim (that checkout
lacks the SC-810 feature entirely). Verified everything achievable without a real Fishing
recording; explicitly reported everything that depends on one as blocked, rather than
simulating it.

## Infrastructure set up
- **Database**: dedicated container `chromascape-sc810-p9-postgres` (postgres:16-alpine,
  port 15435), created fresh specifically for this exercise — never touched any shared
  database. (The original `chromascape-postgres` container had been recreated fresh by
  something outside this session since Phase 4 and could no longer bind port 5433, which
  another operator's `chromascape-sc1449-review-postgres-1` container — 9+ hours up — had
  since claimed. Left that alone entirely.)
- **Port**: 8190 (confirmed free before use; canonical ChromaScape's port 8090 was not
  running at the time and was never touched).
- **Micro6 integration path**: `C:\Dev\IdeaProjects\micro6-sc810-package` (the exact Phase 8
  packaging worktree).
- **Signing/verification env vars**: loaded via
  `. "C:\Dev\SC810\secrets\source-signing-v1\load-sc810-signing-env.ps1"` in the same
  PowerShell invocation as every launch (env vars from dot-sourcing don't persist across
  separate tool calls).

## Real config gates hit and resolved (not SC-810 defects — pre-existing operational config)
Three real startup failures, diagnosed and fixed one at a time rather than guessed around:
1. `StabilizationAuthFilter` requires non-blank auth tokens outside the `local-development`
   Spring profile. Fixed by activating `SPRING_PROFILES_ACTIVE=local-development` — the
   codebase's own documented mechanism for exactly this (tokenless loopback-only access),
   not a workaround.
2. `McpBridgeLifecycleManager` failed because `scripts\mcp-bridge` doesn't exist in this
   worktree — an unrelated AI-analysis integration feature. Disabled via
   `ANALYSIS_MCP_AUTO_START=false` (matches the setting `application-test.yml` already uses).
3. `SourceConfigurationValidator` requires `chromascape.source.environment`,
   `micro6.source.transport.base-url`, `micro6.source.transport.auth-token`,
   `chromascape.source.access.metadata-token`, and `chromascape.source.access.content-token`
   all non-blank. Set `CHROMASCAPE_SOURCE_ENVIRONMENT=production` (matching the Phase 8
   bundle's embedded environment claim) and generated local-only access tokens for this
   verification session (these are internal API bearer tokens gating the debugger's own
   read endpoints, not identity/provenance material — unlike the Ed25519 signing key, no
   provenance guarantee depends on their specific value).

## Startup succeeded
```
21:58:52.415 [main] INFO com.chromascape.web.ChromaScapeApplication - Started ChromaScapeApplication in 56.013 seconds
```
`http://localhost:8190/debugger` returned HTTP 200.

## What could NOT be done, and why (steps 2, 3, 6, 7 of the original acceptance sequence)
`GET /api/debug/builder-goals/machines` returned `ConnectException` — the Builder Goal
launcher/executor backend (LeeuwBot bridge) is not reachable from this isolated verification
environment. Combined with the runbook's own documented block ("Real Fishing publication is
currently BLOCKED... until the account-record migration is executed"), a genuine live
Fishing recording is not achievable here. I did not simulate, mock, or fabricate one — no
capture/run data exists in this instance's database at all. Everything downstream of a real
recording (atomic binding to a source snapshot, comparing a *bound run's* debugger content
against the signed bundle, restart persistence *of recorded data*, cross-revision protection
*of a specific historical run*) is reported here as **blocked by infrastructure**, not
attempted.

## What WAS verified live, against the real running service

### Debugger negative/authorization cases (`/api/v1/debugger/source/runs/{runId}/...`)
All exercised directly via HTTP against the live instance — real code paths
(`SourceAccessPolicy`, `SourceDebuggerReadService`), not simulated:

| Case | Request | Result |
|---|---|---|
| Unauthorized (no token) | no `Authorization` header | `401 SOURCE_ACCESS_DENIED` |
| Wrong/untrusted key | `Authorization: Bearer wrong-token-value` | `401 SOURCE_ACCESS_DENIED` |
| Cross-environment access | `X-Source-Environment: staging` (configured env is `production`) | `400 SOURCE_AUDIT_CONTEXT_INVALID` |
| Invalid audit context | missing `X-Correlation-ID` | `400 SOURCE_AUDIT_CONTEXT_INVALID` |
| Missing/pruned snapshot | valid auth, nonexistent `runId` | `404 SOURCE_UNAVAILABLE` — fails safely, no fallback to current checkout source |
| Unsafe path / traversal | `fileId` containing `../../../etc/passwd` (URL-encoded) | `400 Bad Request` (rejected by the servlet container before reaching application code) |

All failures returned the documented `source-error/v1` schema (except the transport-level
path-traversal rejection) and none fell back to serving any source content.

### Restart persistence (schema/service level)
Identified and stopped only our own process (PID 56768; confirmed via full command-line
inspection that PID 96460, also matching `java.exe`, belonged to a *different* operator's
`ChromaScape-sc1449-review` instance and was left untouched). Relaunched with identical
configuration.
- Flyway history compared before/after restart: identical `installed_on` timestamps for
  every migration (head V45 at `2026-09-10 21:55:19.259966`, unchanged), identical row count
  (48) — confirms no re-migration, no duplication.
- The missing-snapshot negative case (`404 SOURCE_UNAVAILABLE`) reproduced identically
  post-restart.

## Shutdown
Both `ChromaScape-consolidation` java processes (compiler + bootRun) stopped cleanly after
evidence capture; `/debugger` confirmed unreachable afterward. The dedicated
`chromascape-sc810-p9-postgres` container was left running (isolated, low resource cost,
preserves the schema state as evidence) — tell me if you'd like it torn down.

## Logs preserved
- `C:\Dev\SC810\package-20260910\chromascape-p9-stdout.log` / `-stderr.log` (first launch
  sequence, including the three config-gate failures and fixes)
- `C:\Dev\SC810\package-20260910\chromascape-p9-restart-stdout.log` / `-restart-stderr.log`
  (restart-persistence run)

## Summary
| Acceptance item | Status |
|---|---|
| 1. Launch the exact verified artifact | Done (live ChromaScape-consolidation @ c1758c7, correct SC-810 trust config) |
| 2. Record a real Fishing run | **Blocked** — launcher/executor backend unreachable + documented account-migration block |
| 3. Atomic binding to source snapshot | **Blocked** — depends on (2) |
| 4. Open the debugger | Done — HTTP 200, confirmed live |
| 5. Inspect entry/helper/file/symbol content | **Blocked** — no bound run exists to inspect (depends on (2)) |
| 6. Compare bytes/identities with signed bundle | **Blocked** — depends on (2) |
| 7. Restart persistence | Done at the schema/service level (no bound-run data existed to persist) |
| 8. Cross-revision protection | **Not tested** — needs at least one bound run to attempt overwriting |
| Negative cases | 6 of 8 verified live and fail safely (see table above); "invalid signature/untrusted key" and "altered source object" would need a real bound manifest to exercise meaningfully and were not fabricated |
