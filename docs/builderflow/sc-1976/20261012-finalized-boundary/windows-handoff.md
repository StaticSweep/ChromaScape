# SC-1976 Windows code and fresh-capture handoff

## Implemented plan

Continue existing ChromaScape PR59 and Micro6 PR162 without modifying the original mixed checkouts. Preserve additive combat capture, nullable unavailable state, schema/checksum compatibility, diagnostic input fencing and deterministic coverage. V68 adds nullable combat sections and has disposable PostgreSQL migration/reload evidence. No training, promotion, wider SC-752 UI or authentication automation is included.

For this recording follow-up, serialize capture ingestion with stop, disconnect, start rollback and orphan repair using the debug-session database row lock. Keep final bounds immutable. Admit new terminal-session evidence only when both its tick and capture timestamp fit the closed recording interval. Exact matching committed receipt retries remain acknowledged without new writes, including when legacy closure metadata is incomplete. Reject invalid/out-of-interval batches with permanent BATCH_SESSION_CLOSED; retain FINALIZED_SESSION_BOUNDARY detail. Micro6 fails closed on that transport code.

Acceptance remains: published field provenance; required fields surviving a genuine combat capture and storage reload; raw input rejected as a training label; deterministic SC-752 combat coverage. The old recording_4fc844dc is diagnostic evidence only and is neither deleted nor rewritten.

## Changed files

ChromaScape: DebugSession.java, DebugSessionRepository.java, DebugSessionService.java, TickStreamIngestionService.java, TickStreamWebSocketHandler.java; ingestion, session, protocol and combat PostgreSQL regression tests. Micro6: ChromaScapeWebSocketClient.java and its transport regression test.

The PostgreSQL regression exercises the actual Spring Data pessimistic lock in two transactions: ingestion waits for closure and then observes terminal state and unchanged bounds. This proves that lock boundary, not complete live recording behavior.

## Tests

Final merged ChromaScape combat/debugger/playback suite: 300 passed, zero failures/errors/skips (4m3s), including the real PostgreSQL migration/reload and repository-lock race. The Micro6 follow-up passed 89 focused transport/packet tests. Its earlier full 1,673-test run predates this follow-up. Four Docker-only ChromaScape suites are excluded on this host. Hosted CI is still blocked by the previously reported Actions budget prerequisite; ChromaScape additionally needs CONTRACTS_READ_TOKEN with read-only Contents access to ikiddoi/contracts.

## Operator instructions

The fixes are source changes, not a deployment to the server at 192.168.1.6:8090. Do not use another recording from that old runtime as acceptance evidence.

Use the isolated paired Windows worktrees below, or deploy both existing PR branches together through the normal runbook. For the isolated setup, stop the previous SC-1976 verification server on 18090 and close its Micro6 client before rebuilding/replacing its packaged JAR. Preserve unrelated services. If another process occupies 18090 or 8098, resolve ownership before starting; do not terminate it indiscriminately.

In a PowerShell terminal, launch the updated verification server:

```powershell
Set-Location C:\Dev\IdeaProjects\.worktrees\sc1976-chromascape
& C:\Dev\IdeaProjects\.worktrees\felix-native-runtime\launch-chroma.ps1
```

Wait for startup, then in another terminal apply the explicit V68 migration to the isolated verification schema:

```powershell
Set-Location C:\Dev\IdeaProjects\.worktrees\sc1976-chromascape
& C:\Dev\IdeaProjects\.worktrees\felix-native-runtime\launch-chroma.ps1 -ApplyMigrationOnly
```

Launch the matching packaged revision-241 client:

```powershell
Set-Location C:\Dev\IdeaProjects\.worktrees\sc1976-micro6
& C:\Dev\IdeaProjects\.worktrees\felix-native-runtime\launch-client.ps1
```

These existing local helpers use loopback18090 and the disposable schema sc1976_live_20261012. They load local configuration without exposing credentials. The server disables Flyway only for this disposable entity-generated verification schema, with V68 explicitly applied; this is not proof of the production migration chain. The local replay worker is disabled because of an unrelated startup injection failure. Logs: sc1976-live-server.log and sc1976-live-client-ready.log in their respective worktrees. Rebuild/start after this follow-up has not yet been run.

1. Log in manually and open http://127.0.0.1:18090/debugger on this Windows machine. Confirm the connected client is the updated packaged build and recorder-ready; record both build identities and negotiated schema.
2. Enable the capture sections for player state, inventory/equipment, NPCs/projectiles, input diagnostics and combat environment. Ensure streamCombatEnvironment is enabled with bounded tile settings.
3. Start a new recording. Safely fight Giant spiders for about 1–2 minutes; include movement, eating and a prayer toggle if suitable. Avoid risking the account for this test.
4. Stop through the dashboard and wait for FINALIZED. Wait another 60 seconds and refresh: endTick must remain fixed. Tick count may increase only for genuinely delayed data inside the closed interval, then must settle; any row timestamp beyond endTime is a failure.
5. Send the new debugger recording link. Verification will compare payload, persisted and served combat fields, video correspondence at start/combat/end, calibrated frame ranges, label eligibility and coverage. Manual clicks remain diagnostics; accepted semantic labels require the existing supported dispatch/curation path.

## Remaining work

Fresh paired-runtime capture, real field reload and video calibration, eligible semantic actions, sanitized accepted golden fixture, coverage acceptance and hosted CI remain incomplete. Felix task task_1791760667867_s74wdsgf remains in_progress/spec_ready; Shortcut SC-1976 remains In Progress. No review/done synchronization marker is appropriate yet.

## Risks and rollback

Capture-time validation uses the existing host time-zone convention, so paired clock/time-zone alignment must be verified live. Existing contaminated recordings remain unchanged. No generic absolute NPC HP or boss phase is invented. Roll back code/collector enablement while retaining additive nullable columns and immutable recordings; reverting the boundary fix restores the contamination risk.
