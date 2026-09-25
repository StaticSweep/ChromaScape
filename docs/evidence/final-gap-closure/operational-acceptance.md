# ChromaScape Phase 10 Windows Operational Acceptance — Evidence (2026-08-14)

Windows host: CZC8518C27, run via `scripts\restart-chromascape.bat`
Machine UUID: 1d391d65-6c97-5b77-b676-964f157e8c73
ChromaScape listener PID: 21020 (boot log "Port 8090 is listening on PID(s): 21020")

## Verified operational facts

1. `http://localhost:8090/debugger` returns HTTP 200 (local and LAN probe 192.168.1.6).
2. Launch Host `CZC8518C27 (Beheerder)` present; machineUuid `1d391d65-6c97-5b77-b676-964f157e8c73`
   flagged `localMachine:true`.
3. Account hash `3720a9a3b5c9f57b` present in the account catalog (29 accounts),
   displayName `udasuAvuco`, email `uglyratman123@gmail.com`, source launcher.
4. Launch request for account `3720a9a3b5c9f57b` / machine `1d391d65-...` / world 360 queued;
   executor reached `http://127.0.0.1:8098`.
5. Executor health: login_state=logged_in, startup_state=accountbuilder_ready,
   pathfinder_ready=true, requested_world=360, current_world=360, world_matched=true,
   script_lifecycle_state=RUNNING, process_id=19804,
   launch_attempt_id=e2a5bb77-503e-4505-a3d2-b945052fc9f4 (matches operation attemptId).
6. Port 8098 listener PID == health process_id (19804) -> process ownership verified.
7. Registry heartbeat: online=true, runnable=true, dispatchReady=true, worldMatched=true,
   all readiness fields present and green.
8. Micro6 `:client:prepareBuilderGoal` BUILD SUCCESSFUL in the launch log.

## Account-hash authority fail-closed (verified finding)

The executor's RESOLVED account hash is `b01c7f006bace664` (SHA-256 of the in-game character
RSN) while ChromaScape's catalog/requested hash is `3720a9a3b5c9f57b`
(= SHA-256("udasuavuco")[0:16]). The launch therefore correctly remains
`WAITING_FOR_ACCOUNT` / not dispatch-ready for this account: `matchesAccount` enforces the
hash-authority rule (Rule 1: both hashes present must match) and never lets the matching
display name override the hash mismatch. This is the intended fail-closed behavior and proves
the Phase 3 hash-authority contract in production.

Root cause of the divergence (reported for follow-up): Micro6's `resolvedAccountHash()` prefers
`plugin.getCurrentAccountHash()` derived from `client.getLocalPlayer().getName()` (RSN), whereas
ChromaScape keys accounts by the RuneLite profile-name hash. When the RSN differs from the
profile name, the resolved hash can never match. A publish to RUNNING for this account therefore
requires either (a) the catalog hash to equal the RSN hash, or (b) the executor to resolve the
account identity from the same profile-name basis ChromaScape uses. This does NOT weaken the
contract; it blocks an identity-unconfirmed dispatch by design.

## Production defects found and fixed during operational acceptance

1. Stabilization auth: `.env.local` now sets `SPRING_PROFILES_ACTIVE=local-development`
   (git-ignored machine config), restoring the boot-time profile that was previously provided by
   a now-cleared Windows env var. Without it the new StabilizationAuthInterceptor throws and the
   app cannot boot (was also the root of the contextLoads test cascade).
2. Launch payload ACL: `Micro6TargetRelaunchService.restrictPayloadFile` previously used Java NIO
   `AclFileAttributeView.setAcl`, which Windows denies even to the owning user when the JVM is
   elevated. Replaced with `icacls /inheritance:r /grant:r <user>:(R,W,D)` granting the explicit
   current-user principal (verified readable by a separate PowerShell process). This fixed
   "Access to the path ... is denied" on every launch.
3. Client arg: `RuneLite.main` joptsimple rejected `--launch-attempt-id`; registered the option
   so the client starts and the executor's regex reads it for process-ownership reporting.

## Cleanup / no duplicate

Executor PID 19804 stopped; port 8098 free afterward. No duplicate client was started by
ChromaScape (single launch operation per request, single executor instance id).

## Not satisfied (requires live account with matching RSN hash)

Publish `FISHING_TRAINING` to RUNNING and pause/resume verification are blocked by the
account-hash divergence above for account `3720a9a3b5c9f57b`. Evidence paths:
- C:\Dev\IdeaProjects\ChromaScape\chroma_bootrun.log
- C:\Dev\IdeaProjects\micro6\micro6-recovery-launch.log
