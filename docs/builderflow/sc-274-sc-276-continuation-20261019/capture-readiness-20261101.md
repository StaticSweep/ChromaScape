# SC-274 capture readiness — signed client and graph-bound reference — 1 November 2026

This increment packages the producer fixes into a signed source bundle and proves the capture
reference carries the identity the compiler requires. **No recording was started, no gameplay
was dispatched, and SC-274 remains In Review.**

## Signed client artifact

Built from the clean producer worktree (`3817133f48f99da9be785e8f09b3aa51c553810a`) with the
owner-approved development signer. The private key stayed in the build process and was never
passed to a serving process; no new key was minted.

| Field | Value |
|---|---|
| Bundle | `srcbundle_1c35a8fb95e6c84ca0bfe571` |
| Source snapshot | `srcsnap_79c8ace5b3ddbd401ed6fd13` |
| Build commit | `3817133f48f99da9be785e8f09b3aa51c553810a` |
| Artifact | `sha256:0a26e022ab65091be46e078dd1cc292cc95df7f6d4613bb03c46e6103c650c6f` (85,428,873 bytes) |
| Signer | `micro6-sc810-local-ed25519`, public DER SHA-256 `ca478f21…6e551` |
| Environment | `native-windows-local` |

Offline verification (`client-bundle/verify-bundle.py`):

- Ed25519 signature verifies over the canonical manifest minus the `signature` field.
- Artifact digest, build id and repository commit match the signed build report.
- All 34 manifest files resolve to content-addressed objects whose bytes and sizes match.
- The three changed producer files are not part of the 34-file attributed source closure; the
  artifact digest still attests the exact shaded JAR bytes that contain them. No claim is made
  that the closure enumerates them.

## Graph-bound capture reference

`capture-context/prepare-capture-context.py` builds the session-start reference from the last
verified assignment shape and injects the published graph identity. **13/13 checks pass**:

- fresh `runId` UUID; explicit `allocationId`, `executorId`, `clientInstanceId`, profile id and
  version; sha256 `semanticContractDigest`; supported `dataOrigin`; privacy policy and redacted
  field list;
- `graphId=sc274_human_fishing`, `graphVersion=1`,
  `graphRevision=sha256:741a1131…476372`, matching the verified publication and distinct from
  the semantic contract digest.

The reference intentionally does not carry `schemaVersion`, `taskKey`, `settingsHash`,
`recordingId`, `debugSessionId` or `contextHash`: `BehaviorRunContextService.snapshot` and
`bind` add them. `capture-context/VerifyRunContext.java` remains available to check a
**post-bind** context with the production `missingIdentityFields` rules.

## What is still required before the recording

1. Replace the prior assignment's allocation/executor identities with the live allocated
   executor and its recorder connection, then sign in to the game client manually.
2. Start the capture through the normal product API with the prepared reference, fish the
   bounded trout target, and stop.
3. Finalize, publish a new held-out curation, and compile the full manifest twice from
   independent clean directories.
4. Runtime registration/qualification (SC-2201), provider origin (SC-1498) and SC-276 native
   world support remain the gates for the runnable-scenario and bank/travel criteria.

## Tests actually run

- Micro6 focused debugrecorder lane: 44 tests, 0 failures/errors/skips (earlier this session).
- ChromaScape `ScenarioManifestV2CompilerTest` 62 and `GraphSchemaValidatorOfflineTest` 2:
  64 tests, 0 failures/errors/skips.
- ChromaScape full `replayTest`: 891 tests, 875 passed, 16 failed, 43 skipped; every failure is
  environmental (offline worker isolation, a specific AI-player JDBC target, one migration
  version expectation) and unrelated to the changed code.
- Signed bundle build exit 0; bundle verification passed; capture-reference validation 13/13.

## Risks

The bundle is built and signed but the running game client was not replaced with it. The
capture reference reuses the prior session's allocation/executor identifiers as a shape
example only; starting a capture with them would be rejected or misattributed, so they must be
refreshed from the live executor. `graphVersion=1` is immutable; a later graph change needs a
new publication, not an edit.

## Rollback

Delete or stop referencing the new bundle and the `sc274_human_fishing` publication; both are
inert until used. No recording, curation, manifest, trust root, credential or shared database
was modified by this increment.
