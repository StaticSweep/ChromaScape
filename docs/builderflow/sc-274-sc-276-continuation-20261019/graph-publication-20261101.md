# SC-274 graph-bound capture prerequisite — 1 November 2026

The capture run context can only freeze a graph when the reference names a published
publication. This increment publishes the first graph bound to the immutable SC-274 Fishing
profile and fixes the offline defect that blocked the product's own draft/publish path.
**SC-274 remains In Review.** No recording was started, no gameplay was dispatched, and no
capability or certification flag changed.

## Product defect and fix

`GraphSchemaValidator.load` resolved relative `$ref` entries against each contract schema's
public `$id` (`https://contracts.leeuwbot.dev/...`). Every draft-response validation therefore
required DNS and HTTPS at runtime; on this host the hostname does not resolve, so
`POST /api/v1/ai-routine/drafts` returned `422 IDENTITY_MISMATCH` and the transaction rolled
back with no draft row. Publication validation inherits the same path.

Fix: the factory now installs a `MapSchemaLoader` that resolves sibling contract schemas from
`contracts/<filename>` on the classpath before any network loader applies. Unknown or unsafe
names fall through to the normal loader chain, so nothing is silently invented.

- `GraphSchemaValidator.java`: classpath-first sibling resolution.
- `GraphSchemaValidatorOfflineTest.java`: validates a draft response, a create request, a
  publish request and a publication response with no network; also asserts the inlined sibling
  schema is actually enforced (removing `edges` must fail).

Selected tests: **64 passed, 0 failures/errors/skips** (`GraphSchemaValidatorOfflineTest` 2,
`ScenarioManifestV2CompilerTest` 62). Touched files are google-java-format clean.

## Published graph

Created and published through the product API after the fix was deployed to the owned candidate:

| Field | Value |
|---|---|
| Graph | `sc274_human_fishing` |
| Version | `1` |
| Revision (canonical graph hash) | `sha256:741a1131036365a57d9abced9bfaf570c2d7c9385ffb335d9b5bacee86476372` |
| Publication | `publication_1` from draft `sc274_human_fishing_draft` revision 1 |
| Definition schema | `behavior-graph-definition/v2` |
| Task / profile | `FISHING_TRAINING` / `1a67835e-3c32-4d95-94ab-46b537ff5687` version `1` |
| Authority | every flag false; `control_allowed=false` |

Read-only verification against the owned `sc274_capture` database reproduced the exact
preconditions `BehaviorRunContextService.freezePublishedGraph` applies: one publication row,
v2 schema, definition passes `GraphSchemaValidator.validateDefinitionV2`, `graph_id` matches,
row hash equals definition hash, canonicalizer hash equals revision, and the referenced
profile exists with the same task key. **9/9 checks passed**, no writes.

## Deployment

The owned 8090 candidate was restarted on the same evidence runtime. Only
`GraphSchemaValidator.class` was replaced:

- Source class SHA-256 `905563733cfd404c8a32c5c994cbd34fd4e6f129737002b59b3827c843269a73`.
- The prior class is preserved at
  `verification/sc274-sc276-continuation-20261101/graph-bootstrap/GraphSchemaValidator.class.bak`.
- New owned PID `65504`; `/debugger` 200 and `/api/replay/capabilities` 200 after restart.
- No recording was active before the restart (`recording:false`), so no capture was interrupted.

## Full replay lane

`gradlew replayTest` (unfiltered): **891 tests, 875 passed, 16 failed, 43 skipped**. Every
failure is environmental and unrelated to this change:

- 13 `Offline worker isolation is unavailable` (kernel process/network/dispose suites; the
  Node worker isolation lane is not available on this host).
- 2 `AI_PLAYER_UNSAFE_TARGET` (preflight transaction suite requires a specific loopback
  `chromascape_aiplayer_dev` JDBC URL that this environment does not provide).
- 1 `ReplayMigrationIntegrationTest` expected schema version 2 but observed 4.

No failure references graph schema loading, compilation, conversion or the changed code.

## Remaining work

1. Start the next capture with a run context that includes `graphId=sc274_human_fishing`,
   `graphVersion=1`, `graphRevision=sha256:741a...`, plus live `allocationId`/`executorId`/
   `clientInstanceId` and a fresh `runId`.
2. Rebuild and sign the updated Micro6 client before that capture; the producer fixes are
   committed but not yet packaged into the signed artifact.
3. Record, finalize, publish a new held-out curation, then compile the full manifest twice.
4. Runtime registration/qualification (SC-2201), provider origin (SC-1498) and SC-276 native
   world support remain the gates for the runnable-scenario and bank/travel criteria.

## Rollback

Revert this commit and restore the preserved `GraphSchemaValidator.class` backup, then restart
the owned candidate. The publication row is additive and inert unless referenced; a rollback
simply stops referencing `sc274_human_fishing`. No recording, curation, manifest, trust root or
shared database was modified.
