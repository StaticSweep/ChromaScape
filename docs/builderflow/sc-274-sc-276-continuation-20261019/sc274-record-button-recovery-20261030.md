# Debugger recording incident recovery — 30 October 2026

The user reported 500s and empty-JSON errors when clicking Record on the LAN debugger.
The immediate database-backed failure is recovered. This is operational recovery,
not SC-274 capture/acceptance completion.

## Changed files and action

No product source, authentication policy, schema, data volume or application configuration
was changed. Restarted only the existing chromascape-postgres container after confirming
that no video recording was active. Container ID and all mount identities were checked
before/after. ChromaScape PID 79468 and the requested client PID 226132 were not restarted.

## Diagnosis

- Actual debugger Record invokes POST /api/debug/replay/start-recording. The current
  UI payload contains account/script/recordingEnabled, without runContext/profile/task.
  Restoring this button's service availability does not create the SC-274 source context.
- The live JVM thread dump showed database-backed HTTP threads and other callers
  waiting for Hikari connections. Connection-adder logs showed localhost:5433 failures
  ending in EOFException.
- PostgreSQL was healthy and accepting TCP inside its container, with listen_addresses=*
  and port 5432. The Windows published port accepted TCP but closed the PostgreSQL SSL
  negotiation without a response on 127.0.0.1, 127.0.0.2 and 192.168.1.6.
- Restarting the same container restored the IPv4 published-port protocol and the JVM
  pool recovered automatically. This isolates the failing host-published path; it does
  not establish a Docker/WSL internal defect. IPv6 ::1 still timed out and is not qualified.

## Acceptance results and tests

- After recovery: 20/20 fresh IPv4 PostgreSQL negotiations returned the valid N response.
- Nine repeated LAN requests to feed-status, active sessions and account-tasks: all 200
  with valid JSON. The prior database-backed 500/timeout behavior no longer reproduced.
- Local observer status is 200; LAN observer status remains 403. The stabilization KPI
  remains 401 without its credential. These protections were not weakened and those
  background panel denials are not a reason to bypass authentication for recording.
- Same container ID 84b20fc324b7b565b6e3e4f474a255bb2a7f55bba7d6b96c07b43b361ff97f88,
  same data volume/mounts, healthy. Read-only before/after fingerprints match:
  121 recordings, 106 finalized; 82 Flyway rows, zero failed migrations.
- Post-recovery replay inventory is nine terminal runs (five FAILED, four SUCCEEDED),
  with zero active attempt leases. No replay-consumer setting or run state was changed by the agent.
- The existing legacy recording checker now completes against the restored service and
  returns NOT_READY / RUN_CONTEXT_REQUIRED with zero published curations for b83c.
  This is the expected refusal, not a transport failure or a promoted historical capture.
- No start-recording POST was issued by the agent. Actual new capture remains a user
  action after context setup; record-button start/stop was not claimed end-to-end tested.
- No Java/native suite rerun: there was no product source change in this recovery.
  The earlier 175 passing tests remain separately scoped historical evidence.

## Remaining work and risks

For same-machine use, open http://127.0.0.1:8090/debugger and refresh to use local-only
panels correctly. A remote LAN client still needs the supported authentication path.
The immediate DB outage is resolved, but current context-aware capture prerequisites
remain as documented in sc274-capture-checklist-20261030.md. The basic Record payload
does not include them. No recording was started or given invented provenance.

Restarting the database reestablishes existing application's normal connectivity; unchanged
recording/migration counts are not a claim that every unrelated database row is byte-identical.
Existing background consumers/configuration were not changed. No Docker Desktop or WSL
shutdown, container recreation, volume deletion, migration, account login or gameplay occurred.

## Evidence and rollback

Raw checks/fingerprints and JVM thread diagnostics:
C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\record-button-diagnosis.
The original large service log was not modified or rotated. No configuration change needs
rollback. Preserve the working container/volume and evidence; do not reset the database,
weaken authentication or change source trust to clear a UI error.
