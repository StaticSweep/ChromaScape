# SC-274 continuation: startup validation, rebuild cycle and owned service

Observed 19 October 2026. Development experiment only. This supersedes process/status
claims in earlier increments, without changing their historical evidence.

## Changed files

Candidate and build mirror:

- `src/main/java/com/chromascape/web/config/SourceConfigurationValidator.java`: accept
  independently configured upstream metadata/content tokens. Each independently falls
  back to the legacy shared token; fail startup if either effective credential is missing.
  Inbound debugger credential validation remains mandatory.
- `src/test/java/com/chromascape/web/config/SourceConfigurationValidatorTest.java`:
  adjust direct construction for the added properties.
- `src/test/java/com/chromascape/web/config/SourceTransportTokenBindingTest.java`:
  seven real Spring property-binding/startup cases for legacy, split, mixed and missing
  outbound credentials.

External evidence root (V):
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019`.

- `runtime-kit/stop-dev-authoritative.ps1`: replace reserved PowerShell `$PID` variable.
- `runtime-kit/prepare-dev-source-bundle.ps1`: directly invoke the resolved Gradle wrapper
  from its worktree, avoiding broken nested command quoting; preserve Gradle exit status.
- `start-isolated-chromascape.py`: explicit serving-environment allowlist, owned loopback
  service/database, in-memory generated experiment credentials and schema-only import.
  The serving JVM receives public verification material and purpose tokens, no signing key.
- `startup-format.init.gradle`: format only the three startup-validation files.

The existing purpose-token transport implementation from the previous increment remains
uncommitted. No candidate build output or canonical configuration was rebuilt or replaced.

## Acceptance results

- Owned runtime PID 94808 was stopped after ownership checks. Preparation completed and
  serving relaunched as Java PID **171232**, loopback **8180**. Authenticated source readiness
  passed. Bundle, snapshot and artifact digest remain the recorded exact identities:
  `srcbundle_fdd4e78fcc5739645ef0727a`, `srcsnap_0585920d2af05903264627ba`,
  `sha256:96c912c9cdecfa49ccd7125c52b68394190dbd11d5ebe74042d065f3c06a71e0`.
  This was a repeat prepare/serve cycle; Gradle reused up-to-date compile/JAR outputs.
  It was **not** a forced clean recompilation of every artifact.
- Selected Micro6 build worktree remains Git-clean after relaunch.
- Full isolated ChromaScape **PID 55600**, loopback **8181**, started in 55.299 seconds.
  Source validator accepts split-only upstream credentials with development trust.
- Owned PostgreSQL container `sc274-source-trust-postgres-20261019`, loopback **55439**,
  label `sc274.owner=isolated-source-experiment`, has tmpfs data. Read only the existing
  database's public schema definitions; copied no rows or migration history entries.
  Hibernate validation passed; Flyway is disabled for this experiment.
- HTTP: `/debugger` 200; anonymous `/xrsps-sessions` 401; anonymous stabilization overview
  401; source manifest request without audit context 400 (`SOURCE_AUDIT_CONTEXT_INVALID`).
  These are observations, not a positive authenticated persisted-source test.
- Owned DB: replay_attempts=0, source_run_bindings=0, source_manifest_references=0.
- Replay enabled=false and worker=false. Capture, streaming, local launch, log shipping
  and live gameplay disabled. No game login performed. Existing 8098 remains stopped.
- Shared 8090 remains PID 195400; shared registry deployment and canonical trust unchanged.

## Tests

- Mirror Gradle focused tests: **32 passed**, zero failures/errors/skips:
  Micro6SourceManifestClientTest 10, TaskSourceManifestVerifierTest 11,
  SourceConfigurationValidatorTest 4, SourceTransportTokenBindingTest 7.
  Evidence: `V\stage-e-startup-validation-tests.log` and mirror JUnit XML.
- Scoped Spotless apply succeeded; source diff whitespace check passed. No full Checkstyle
  or full Java/native suite rerun in this increment.
- Post-rebuild live source trust harness: **15/15**, including altered content/signature,
  wrong keys/identity/environment/tokens, repeated success/rejection and original-root
  rejection. Evidence: `V\stage-e-post-rebuild-harness.log`.
- Prepare/serve: `V\stage-bcd-rebuild-cycle.log`, `micro6-rebuild-cycle.log` and err log.
- Service: `V\chromascape-full-service\launch.json`, `service.log`, `schema-restore.log`,
  `http-checks.json`; launch metadata records environment names only, not values.

## Risks

- Original trust-root gate remains blocked. Development-key success cannot close it.
- Full service startup is not fully healthy: isolated-home RuneLite profile initialization
  logs a missing profiles.json; behavior-graph schema resolution logs two failures; optional
  MCP availability probes fail on localhost:8765. No account data was copied to hide these.
  Startup also enumerates windows despite capture being disabled. Do not claim all startup
  integrations were inert: the MCP health probes and schema resolution were observed reads.
- Public-schema copy plus Hibernate validation does not qualify fresh migrations or schema
  parity from an empty database. Owned DB gets only experiment/bootstrap rows.
- Disposable DB data disappears when its container stops. Credentials are generated in
  launcher memory, passed to owned child processes and not saved for later operator access.
- Running 8090 still has its old build; source changes are not active there.
- Serving process PIDs are observations, never sufficient authorization to stop a reused PID.

## Remaining work

1. Finish stage E with authenticated full-service source-run persistence and debugger
   manifest/file/symbol reads. Use explicitly labelled owned experiment fixtures or the
   supported orchestration path; never present synthetic bindings as captured provenance.
   Cover retention, identity mismatch and persisted read behavior after service restart.
2. Investigate the three startup limitations above; distinguish optional unavailable
   integrations from failures that affect the source path. No successful startup claim
   should conceal the logged errors.
3. If a literal clean rebuild is required, force a new clean preparation in the immutable
   selected checkout, then repeat identity and affected tests. Current cycle reused outputs.
4. Stage F: first prove grants on an owned native DEV registry with loopback routing if
   supported by the exact deployed revision. Otherwise qualify a bounded container-to-host
   route before changing runtime bind addresses. Do not point Docker at its own localhost.
   Keep shared registry unchanged until its complete configuration and rollback are reviewable.
5. Grant qualification must include <=120s TTL, renewal before expiry, atomic publication,
   expiry/revocation/mismatch rejection and durable generation fencing through restart.
   Preserve fence high-water state during rollback. Require an actual executor/catalog
   identity; the stale heartbeat is not the new runtime.
6. Only after isolated end-to-end success: stage and review the 8090 rebuild/restart and
   shared registry rollout, preserving disabled replay consumption. Reconfirm scope before
   applying any shared configuration change.
7. Original approved trust material, real runtime selection/catalog/profile/capture and the
   remaining delivery-plan stages/17 acceptance rows remain open where unproved.

## Rollback

Stop only the owned Java process after checking its command line points to the trust mirror,
owned home and ChromaScapeApplication. Inspect the DB container ownership label before
stopping/removing that exact container. Retain evidence and state directories. Runtime rollback
uses the ownership-checking `runtime-kit\stop-dev-authoritative.ps1`. Revert only the scoped
source hunks if needed, preserving previous purpose-token changes and unrelated work.

Felix remains explicitly skipped. No Felix task/state change, Shortcut write or certification
claim was made. The full plan remains A-N in `delivery-plan-after-step2-review.md`; this note
records completed work and precise remaining gates, rather than marking the whole plan done.
