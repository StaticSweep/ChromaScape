# SC-810 Luna Max remaining implementation plan

Date: 2026-09-05
Projects: `C:\Dev\IdeaProjects\micro6` and `C:\Dev\IdeaProjects\ChromaScape`
Architecture contract: `C:\Dev\IdeaProjects\ChromaScape\docs\SC-810-GREENFIELD-IMPLEMENTATION-SPEC.md`

## 1. Mission

Finish SC-810 as a greenfield, fail-closed implementation. Micro6 must generate, sign, atomically
promote, load, and serve the immutable source bundle for the exact executable artifact. ChromaScape
must verify and bind that exact source identity to a run/recording and expose only authorized,
bounded debugger reads.

Nothing using the old source-manifest contract has been deployed. Do not preserve legacy source
manifests, historical decoders, unsigned modes, compatibility constructors, alias properties, or
current-checkout fallbacks.

Completion requires executable evidence, not only unit-test or static-review evidence:

- a native-Windows Micro6 build;
- a real signed and promoted `FISHING_TRAINING` bundle;
- the exact packaged artifact launched with the same digest as the bundle;
- a real ChromaScape run/recording bound to the bundle;
- successful authorized source reads through ChromaScape;
- focused and full verification results recorded truthfully.

## 2. Non-negotiable operating rules for Luna Max

1. Use Windows paths and native Windows commands for both repositories.
2. Use JDK 21. Confirm with `gradlew.bat --version` before verification.
3. Read the greenfield specification completely before editing code.
4. Treat both repositories as shared dirty worktrees. Inspect each file's status and diff before
   editing it.
5. Preserve unrelated gameplay, replay, tick-recorder, SC-967, SC-1199, SC-1429, SC-1431,
   SC-1452, SC-1618, and provider-runtime work.
6. Do not reset, checkout, clean, stash, or broadly delete the worktree.
7. Do not claim a test passed unless it ran in the current worktree and current turn.
8. Never print signing keys, bearer tokens, or `.env` values. Report only `SET` or `MISSING`.
9. Do not commit, push, open a PR, change Shortcut, or recreate Felix records unless the user
   explicitly authorizes those external/history changes.
10. Keep an implementation log containing the exact command, exit code, test totals, artifact
    digest, manifest hash, snapshot ID, and any skipped environmental tests.

## 3. Known starting state

### ChromaScape

Current observed commit at plan creation: `5e20618`.

The focused SC-810 battery last ran successfully on native Windows:

- 46 tests;
- 46 passed;
- 0 failed;
- 0 skipped.

The full suite last ran with:

- 2,139 tests;
- 50 failed;
- 17 skipped.

Most full-suite failures belong to unrelated dirty gameplay/replay/provider/PostgreSQL work. One
SC-810-adjacent failure is `CanonicalJsonHasherTest.equivalentDocumentsHaveSameHash`, caused by
changing the semantics of the shared canonicalizer.

Critical SC-810 files are still modified or untracked, including:

- `SourceManifestContract.java`;
- `TaskSourceManifestVerifier.java`;
- `Micro6SourceManifestClient.java`;
- `SourceManifestBindingService.java`;
- `SourceDebuggerReadService.java`;
- persistence entities, trust configuration, controller, audit, retention, and transaction code;
- `SourceManifestSchemaValidator.java` (untracked);
- `V36__sc810_source_provenance_hardening.sql` (untracked);
- `CaptureSourceIntegrationTest.java` and `Sc810PostgresTestConfiguration.java` (untracked);
- the shared v1 schema and SC-810 tests.

ChromaScape `.env.local` currently has the required source trust/transport/token values. Never
copy their values into source, logs, evidence, or a response.

### Micro6

Current observed commit at plan creation: `4e7ae14349`.

The earlier five SC-810 compilation errors in `FishingTaskSourceProvider` and
`TaskSourceBundleGenerator` have been repaired. The latest native-Windows attempt passed
`:client:verifyCaptureContract` but failed `:client:compileJava` because
`RsproxPacketStreamClient` could not resolve junixsocket `AFUNIXSocket` and
`AFUNIXSocketAddress`.

Most SC-810 producer and test files are still untracked. The old Fishing resource and source
objects are deleted in the worktree but remain tracked until the deletion is recorded. The
repository also contains tracked build outputs, including stale legacy generated manifests.

Unrelated gameplay-contract files are staged. They are not part of SC-810 and must not be folded
into an SC-810 change set.

Micro6 `.env` does not currently provide the source bundle, signing, verification, environment,
metadata-token, or content-token configuration. `.env.example` does not document the complete
source configuration either.

Shortcut SC-810 remains To Do/blocked. Felix has no recoverable SC-810 task.

## 4. Target architecture and definition of authoritative

Only `task-source-manifest/v1` is valid. An authoritative `AVAILABLE` manifest has:

- a clean pinned Git commit and tree;
- a stable repository ID;
- a real immutable build ID;
- the SHA-256 digest of the exact packaged JAR that is launched;
- an environment identity;
- a bounded task-specific file and dependency closure;
- deterministic file IDs, content hashes, sizes, modules, languages, classifications, and
  inclusion reasons;
- deterministic symbol IDs and exact UTF-8 byte/line/column ranges;
- explicit generated/transformed source mappings where applicable;
- parser and indexer versions and deterministic warnings;
- a canonical manifest hash and derived snapshot ID;
- an Ed25519 signature from the configured producer key;
- retention and confidentiality classifications.

The manifest is external to the executable JAR. Build and hash the JAR first, then generate and
sign the detached source bundle. Embedding the manifest in the JAR whose digest it declares would
create a circular identity.

## 5. Work package 0: establish and preserve the baseline

### Actions

1. Record branch, commit, status, staged status, and untracked SC-810 files for both repositories.
2. Record Java and Gradle versions.
3. Save targeted diffs for every SC-810 file before editing.
4. Identify all files containing changes from more than one story.
5. Build an ownership table with columns: path, SC-810 change, unrelated change, staged state,
   intended final action.
6. Do not alter unrelated staged gameplay-contract files in Micro6.

### Commands

```bat
git -C C:\Dev\IdeaProjects\micro6 status --short --untracked-files=all
git -C C:\Dev\IdeaProjects\micro6 diff
git -C C:\Dev\IdeaProjects\micro6 diff --cached
git -C C:\Dev\IdeaProjects\ChromaScape status --short --untracked-files=all
git -C C:\Dev\IdeaProjects\ChromaScape diff
git -C C:\Dev\IdeaProjects\ChromaScape diff --cached
cd /d C:\Dev\IdeaProjects\micro6 && gradlew.bat --version
cd /d C:\Dev\IdeaProjects\ChromaScape && gradlew.bat --version
```

### Exit gate

Luna can name every SC-810 file and can prove no unrelated change will be overwritten.

## 6. Work package 1: restore a compilable Micro6 baseline

### Goal

Remove the unrelated compile blocker so SC-810 tests can execute.

### Actions

1. Inspect the dependency declarations and verification metadata for junixsocket.
2. Determine whether `RsproxPacketStreamClient` needs `junixsocket-common`,
   `junixsocket-native-common`, or an existing repository alias on `implementation` or
   `compileOnly` rather than only at runtime.
3. Compare with the version and dependency pattern already used elsewhere in the repository.
4. Make the smallest dependency correction. Do not refactor the recorder client as part of
   SC-810.
5. If dependency verification blocks resolution, update verification metadata only for the exact
   resolved module and checksum; do not relax global dependency verification.
6. Run compile first, then the SC-810 tests.

### Commands

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :client:compileJava --no-daemon
```

### Exit gate

`:client:compileJava` succeeds. Any unrelated dependency change is reported separately from
SC-810.

## 7. Work package 2: finish greenfield legacy removal

### Micro6 actions

1. Preserve the current intentional deletion of:
   - `fishing-source-manifest.json`;
   - the old `micro6-contracts\fishing-source\file_*.java` objects;
   - `source-manifest-v1.schema.json`.
2. Ensure no Gradle task or runtime class regenerates `micro6-source-manifest/v1`.
3. Ensure `SourceManifestCatalog` rejects all schemas other than `task-source-manifest/v1`.
4. Keep a negative test that submits the old schema and expects `SOURCE_SCHEMA_UNSUPPORTED`; a
   rejection fixture is not legacy support.
5. Remove stale tracked generated Fishing-manifest outputs from the Git index after verifying the
   corresponding build roots are ignored. Preserve local build files unless the user explicitly
   requests physical deletion.
6. Enumerate all tracked build-root files. Remove them from the index only with explicit, narrow
   path arguments; never use a broad repository-root deletion.

### ChromaScape actions

Delete, rather than isolate:

- `LEGACY_SCHEMA` and `LEGACY_SCHEMA_V2`;
- `isLegacySchema(...)`;
- `verifyLegacyForHistoricalRead(...)`;
- `decodeLegacy(...)`;
- the `legacy` field from `TaskSourceManifestVerifier.Verified`;
- legacy parsing flags in file and symbol parsing;
- fallback support for the old `manifest_hash` wire field;
- legacy fixtures and historical-read tests;
- guards whose only purpose is checking `verified.legacy()`;
- documentation describing historical source-manifest reads.

The public verifier should perform a simple exact schema check and return
`SOURCE_SCHEMA_UNSUPPORTED` for every other value.

### Search gate

Search source, tests, resources, and generated tracked paths for:

```text
micro6-source-manifest/v1
micro6-source-manifest/v2
LEGACY_SCHEMA
allowLegacy
verifyLegacyForHistoricalRead
decodeLegacy
resolveLegacyFishing
UNVERIFIED
fishing-source-manifest
```

`UNVERIFIED` may remain in non-SC-810 launch/certification code only when it is an explicit
non-authoritative sentinel that can never pass SC-810 source publication or binding.

### Exit gate

No runtime, persistence, resource, or test helper can decode or accept a legacy source manifest.
Only explicit rejection tests may mention its schema string.

## 8. Work package 3: isolate and freeze canonical JSON semantics

### Problem

SC-810 changed the shared `CanonicalJsonHasher` so characters inside JSON strings are preserved.
That is appropriate for an identity-bearing source manifest, but it broke an existing shared
contract test that treats CRLF and LF inside strings as equivalent. Leaving a shared semantic
change unexplained risks invalidating unrelated hashes.

### Required design

1. Restore the existing `CanonicalJsonHasher` behavior required by its current consumers and
   tests unless every consumer is intentionally version-migrated.
2. Introduce an SC-810-specific canonicalizer in the source package, or make an existing source
   canonicalizer the explicit contract boundary.
3. The SC-810 canonicalizer must:
   - reject malformed UTF-8;
   - reject duplicate JSON keys;
   - reject trailing JSON documents/tokens;
   - reject unsupported floating-point/non-finite number forms;
   - recursively sort object keys;
   - preserve array order;
   - preserve characters inside JSON strings exactly;
   - emit deterministic UTF-8 with no insignificant whitespace.
4. Make `SourceManifestContract`, `TaskSourceManifestVerifier`, and signature verification use only
   this source-specific canonicalizer.
5. Make Micro6 `TaskSourceCanonicalJson` obey exactly the same rules.
6. Add byte-for-byte golden canonicalization vectors shared by both repositories. Include CRLF,
   LF, non-ASCII text, escaped characters, nested objects, arrays, duplicate keys, trailing
   values, and numeric edge cases.

### Exit gate

- `CanonicalJsonHasherTest` passes without weakening SC-810.
- Both repositories produce identical canonical bytes, manifest hashes, snapshot IDs, and signed
  bytes for every shared vector.

## 9. Work package 4: complete the manifest contract

### Schema actions

1. Keep the Micro6 and ChromaScape schema files byte-identical.
2. Require `signature` for every v1 manifest.
3. Preserve `additionalProperties: false` at every object boundary.
4. Add an explicit generated/transformed mapping structure. Each mapping must identify:
   - generated/transformed file ID;
   - authored input file ID or IDs;
   - mapping kind;
   - tool/processor identity and version;
   - deterministic mapping description or ranges when available.
5. Define whether `INDEX_PARTIAL`, `UNAVAILABLE`, `REDACTED`, `PRUNED`, and `GENERATED_ONLY`
   documents are publishable signed manifests or typed API failures. Apply one model consistently.
6. Ensure `AVAILABLE` always means clean, signed, complete entry point, non-empty symbols, complete
   closure, and real artifact digest.

### Semantic verifier actions

Verify uniqueness and referential integrity for:

- task key and entry point;
- file IDs and relative paths;
- content hashes and sizes;
- symbol IDs, file references, enclosing symbols, and byte/line/column spans;
- closure endpoints and edge kinds;
- generated mappings and referenced files;
- repository, build, artifact, environment, retention, and confidentiality identities.

Reject absolute, drive-relative, UNC, backslash, traversal, encoded-separator, non-NFC, control,
NUL, symlink, junction, and containment-race paths.

### Exit gate

Schema equality and shared golden-vector tests pass, and no consumer relies on producer-only
assumptions.

## 10. Work package 5: complete Micro6 producer fixtures

Existing tests already provide substantial evidence for dirty-tree handling, parser diagnostics,
path confinement, symlinks/junctions, artifact mutation, signing, tampering, endpoint audit
context, and atomic bundle promotion. Add the missing acceptance fixtures explicitly.

### Required fixtures

1. **Real Fishing closure**
   - `FISHING_TRAINING` resolves its real entry class;
   - TaskFactory, helpers, configuration, nested types, and relevant same-package/wildcard/static
     references are included;
   - unrelated Fishing and repository files are absent or explicitly excluded.
2. **Multi-module task**
   - entry point in one module;
   - executable helper in another module;
   - normalized module identities and a cross-module closure edge;
   - both file and symbol ranges verified.
3. **Shared helper**
   - two task manifests depend on one identical helper;
   - the helper has one content hash/object;
   - both closures explain the dependency without duplicating mutable bytes.
4. **Generated source**
   - annotation processor or generator produces a referenced type;
   - generated output remains inside isolated compiler output;
   - manifest classification and authored-input mapping are emitted;
   - processor failure results in a typed non-authoritative outcome.
5. **Parser failure**
   - malformed entry unit fails publication;
   - malformed unrelated unit does not contaminate a bounded task closure;
   - ambiguous/missing symbols fail deterministically.
6. **Dirty artifact source**
   - modified, staged, deleted, renamed, untracked relevant, and ignored relevant inputs fail;
   - harmless ignored workstation metadata remains excluded from the artifact and manifest;
   - no dirty tree can produce `AVAILABLE`.

### Focused Micro6 test command

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :client:test ^
  --tests *TaskSourceManifestGeneratorTest ^
  --tests *TaskSourceBundleGeneratorTest ^
  --tests *FishingTaskSourceProviderTest ^
  --tests *JavaCompilerTreeSourceIndexerTest ^
  --tests *SourceManifestCatalogBundleTest ^
  --tests *SourceManifestCatalogTest ^
  --tests *SourceManifestEndpointSecurityTest ^
  --tests *TaskSourceManifestSchemaEqualityTest ^
  --tests *TaskSourcePathSecurityTest ^
  --tests *TaskSourceProcessorPathTest ^
  --tests *TaskSourceGitLifecycleTest ^
  --no-daemon
```

Do not exclude `:client:verifyCaptureContract` from final evidence.

### Exit gate

All required fixture categories have named passing tests, not inferred coverage.

## 11. Work package 6: finish Micro6 runtime and configuration

### Configuration contract

Document these variables in `C:\Dev\IdeaProjects\micro6\.env.example` using blank placeholders:

```text
MICRO6_SOURCE_BUNDLE_ROOT=
MICRO6_SOURCE_ENVIRONMENT=
MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64=
MICRO6_SOURCE_SIGNING_KEY_ID=
MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64=
MICRO6_SOURCE_VERIFICATION_KEY_ID=
MICRO6_SOURCE_METADATA_TOKEN=
MICRO6_SOURCE_CONTENT_TOKEN=
MICRO6_SOURCE_REQUESTS_PER_MINUTE=
```

Never store real keys or tokens in `.env.example`.

### Runtime actions

1. Make `generateAllTaskSourceBundles` depend on the final `shadowJar` and artifact identity.
2. Hash the exact final JAR bytes immediately before bundle generation and again before promotion.
3. Require signing private key, signing key ID, environment, and bundle root for generation.
4. Validate with the configured public key before promotion.
5. Stage under the bundle root and atomically promote a complete immutable bundle.
6. Write the current pointer only after complete read-back validation.
7. Load only the promoted bundle at runtime.
8. Verify bundle environment, build ID, and artifact digest against the running packaged JAR.
9. Decide and document startup semantics. The greenfield specification requires startup failure
   when authoritative source capability is configured as required but unavailable. Do not silently
   run with a seemingly healthy source capability.
10. Keep metadata and content tokens separate and require the correct purpose header.
11. Re-hash content immediately before serving and never follow redirects or use caller-provided
    roots/URLs.

### Exit gate

Micro6 reports authoritative source capability ready only when a verified promoted bundle matches
the running packaged artifact.

## 12. Work package 7: finish ChromaScape persistence and binding

### Configuration

Keep these mandatory:

- trusted Ed25519 public key and key ID;
- exact source environment;
- trusted Micro6 source base URL;
- upstream bearer token;
- bounded actor ID;
- separate debugger metadata and content tokens.

No boolean can disable signing or authentication.

### Fetch and verification

1. Execute remote fetch and cryptographic verification with no database transaction active.
2. Strictly validate UTF-8 and JSON Schema before semantic interpretation.
3. Verify canonical hash, snapshot ID, signature, signed-bytes hash, trusted key ID, task,
   repository, commit, tree, build, artifact digest, and environment.
4. Fetch content only by snapshot ID and file ID from the signed allowlist.
5. Recheck size, content hash, UTF-8 boundaries, and symbol line/column mapping.

### Persistence

1. Re-lock and re-read the run/capture after network verification.
2. Revalidate that launch identity did not change during the fetch.
3. Persist manifest reference, source run binding, optional recording assignment, capture/run
   identity, and `SOURCE_BOUND` atomically.
4. Make identical retries idempotent and conflicting identities fail.
5. Keep success-path helpers at `REQUIRED`; do not introduce `REQUIRES_NEW` around partial success.
6. Persist `UNCERTAIN` only after the failed success transaction has rolled back.
7. Preserve monotonic pruning/redaction and immutable identity constraints.

### Migration decision

Because SC-810 is not deployed, fold the final schema and constraints into
`V21__sc810_source_provenance.sql` and remove V36, unless the user explicitly decides that an
already-shared database makes V21 immutable. Do not silently choose the latter based only on a
local applied checksum.

Do not drop or recreate any developer database automatically. Ask before destroying local data.

### Debugger API

Verify these endpoints remain bounded and run-bound:

- `GET /api/v1/debugger/source/runs/{runId}/manifest`
- `GET /api/v1/debugger/source/runs/{runId}/files/{fileId}`
- `GET /api/v1/debugger/source/runs/{runId}/symbols?fileId=...&page=0&limit=100`

Metadata credentials must not authorize content. Audit identities and outcomes without recording
source bytes, tokens, host paths, or raw upstream URLs.

### Focused ChromaScape command

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat test ^
  --tests *TaskSourceManifestVerifierTest ^
  --tests *SourceManifestBindingServiceTest ^
  --tests *Micro6SourceManifestClientTest ^
  --tests *SourceDebuggerReadServiceTest ^
  --tests *SourceAccessAndCacheTest ^
  --tests *SourceRetentionServiceTest ^
  --tests *SourceContractSchemaEqualityTest ^
  --tests *SourceRunOrchestrationIntegrationTest ^
  --tests *SourceManifestConcurrentPersistenceIntegrationTest ^
  --tests *SourceFetchTransactionSuspensionTest ^
  --tests *CaptureSourceIntegrationTest ^
  --no-daemon
```

### Exit gate

The focused suite passes from a clean compilation, including PostgreSQL transaction and
concurrency tests, with all critical SC-810 files included in the intended change set.

## 13. Work package 8: build and promote the real Fishing bundle

### Secret preparation

1. Generate or obtain an Ed25519 key pair through the approved local secret workflow.
2. Store the private key only in ignored local configuration or a secret provider.
3. Configure the matching public key/key ID in both Micro6 verification and ChromaScape trust.
4. Configure separate metadata/content tokens and a shared environment identity.
5. Verify configuration presence without printing values.

### Build and generation

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :client:shadowJar :client:generateAllTaskSourceBundles --no-daemon
```

If the generator deliberately refuses the dirty worktree, that is correct. Produce the
authoritative bundle only from a canonical clean revision containing all intended SC-810 changes.
Do not weaken cleanliness checks to accommodate uncommitted implementation.

### Bundle inspection

Record without exposing secrets:

- Git commit and tree;
- build ID;
- packaged JAR path and SHA-256;
- bundle ID and promoted root;
- Fishing manifest hash and snapshot ID;
- signing key ID;
- environment;
- number of files, closure edges, symbols, classifications, and generated mappings;
- entry class/file/symbol;
- read-back validation result.

Verify there is no absolute path, source root, token, private key, `.env` content, or unrelated
repository file in the bundle.

### Exit gate

A real signed promoted Fishing bundle exists and independently validates against the exact final
JAR.

## 14. Work package 9: end-to-end proof

### Micro6

1. Launch the exact packaged JAR used during bundle generation.
2. Confirm runtime artifact digest and build ID match the promoted bundle.
3. Confirm source capability reports ready.
4. With metadata credentials, fetch the Fishing manifest.
5. With content credentials, fetch the entry file and one shared/helper file.
6. Prove wrong token, wrong purpose, wrong environment, wrong file ID, and tampered bytes fail with
   stable source-error codes.

### ChromaScape

Use the restart runbook as the source of truth:

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
scripts\restart-chromascape.bat
```

Then:

1. Verify `http://localhost:8090/debugger`.
2. Confirm launch host `CZC8518C27` is selected and dispatch-ready.
3. Publish and run Fishing Training.
4. Confirm SC-609 launch identity contains the exact repository/build/artifact/source identities.
5. Complete a recording and confirm one immutable source binding.
6. Read manifest, entry source, helper source, and symbol pages through ChromaScape.
7. Compare returned bytes and hashes with the promoted bundle.
8. Attempt identity mismatches and prove no binding or fallback occurs.
9. Confirm audit records contain actor, environment, task, run, snapshot, file, purpose,
   correlation ID, and outcome—but no content or secrets.

### Exit gate

The debugger displays the exact Micro6 source that produced the selected Fishing run and cannot
display another revision under any tested failure.

## 15. Work package 10: full verification and regression closure

### Micro6

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat test --no-daemon
```

### ChromaScape

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat test --no-daemon
```

Classify every failure as:

- caused by SC-810;
- exposed by SC-810 but owned elsewhere;
- unrelated pre-existing dirty work;
- environmental;
- flaky and reproduced/not reproduced.

SC-810 cannot close while an SC-810-caused test fails. Unrelated failures may remain only if the
focused suites and end-to-end proof pass and the unrelated ownership/evidence is explicitly
recorded. Resolve the shared canonicalizer failure as SC-810-owned.

## 16. Work package 11: change-set and delivery hygiene

### Required inventory

Produce separate file lists for:

- Micro6 SC-810 production code;
- Micro6 SC-810 tests/resources;
- Micro6 intentional legacy deletions;
- Micro6 build-output index cleanup;
- unrelated Micro6 dependency fix;
- ChromaScape SC-810 production code;
- ChromaScape SC-810 tests/resources/migration/docs;
- unrelated ChromaScape blockers left untouched.

Run `git diff --check` on the exact intended files. Confirm all required new files are tracked in
the intended change set and no secret or generated bundle is included.

If commits are authorized, use logical repository-local commits rather than one mixed commit:

1. Micro6 dependency compile unblock, if independently required;
2. Micro6 SC-810 producer, tests, schema, legacy removal, and narrow build-index hygiene;
3. ChromaScape SC-810 consumer, persistence, migration, tests, and documentation.

Do not include unrelated staged gameplay-contract files in an SC-810 commit.

## 17. Work package 12: Shortcut and Felix closure

Only after implementation and verification evidence is complete, and only with explicit user
authorization:

1. Update Shortcut SC-810 description to remove the legacy-backfill clause and reference the
   greenfield decision.
2. Add one implementation-evidence comment containing repository commits, file inventory,
   manifest/snapshot/artifact identities, and security decisions.
3. Add one verification-evidence comment containing exact commands, totals, failures/skips, and
   end-to-end proof.
4. Recreate a Felix SC-810 task using the correct delivery workflow, or reconcile to a recovered
   record if one appears.
5. Attach the architecture/spec/API/ERD/observability notes required by the workflow.
6. Move Shortcut from To Do only when the definition of done is satisfied.

## 18. Final acceptance matrix

Luna must return a table with one row for each Shortcut criterion:

1. Real Fishing entry and closure.
2. Immutable file/symbol/repository/build/artifact identity.
3. Exact SC-609 run/recording binding and mismatch rejection.
4. Generated/vendor/test/unrelated/sensitive classification.
5. Path, symlink/junction, redirect/URL, environment, and confused-deputy protection.
6. Separate authorization, audit, export, and retention enforcement.
7. Stable unavailable/redacted/pruned outcomes without fallback.
8. Multi-module, shared-helper, generated, parser-failure, and dirty-tree fixtures.
9. Shared schema/version and final migration evidence.
10. Explicit BehaviorGraph/executable-source separation.

Each row must be `PASS`, `FAIL`, or `BLOCKED`, with concrete file/test/runtime evidence. No
criterion may be marked PASS from planned or unrun work.

## 19. Definition of done

SC-810 is done only when all are true:

- no legacy source-manifest implementation remains;
- every intended SC-810 file/deletion is captured in a reproducible revision/change set;
- no generated build output or private bundle is accidentally versioned;
- Micro6 compiles and all focused producer tests pass;
- ChromaScape focused tests pass;
- shared canonicalization vectors pass in both repositories;
- the complete fixture matrix passes;
- a real clean Micro6 build generates and promotes a signed Fishing bundle;
- Micro6 loads that bundle only for the exact packaged artifact;
- ChromaScape binds a real Fishing run/recording to the exact snapshot;
- authorized debugger source reads succeed and negative security reads fail;
- no mutable checkout, branch, tag, raw URL, path, or revision fallback exists;
- SC-810-caused full-suite regressions are zero;
- all remaining unrelated failures are truthfully documented;
- Shortcut/Felix evidence is synchronized when authorized.

## 20. Ready-to-paste Luna Max prompt

> Implement the remaining SC-810 work using
> `C:\Dev\IdeaProjects\ChromaScape\docs\SC-810-LUNA-MAX-REMAINING-IMPLEMENTATION-PLAN.md`
> and
> `C:\Dev\IdeaProjects\ChromaScape\docs\SC-810-GREENFIELD-IMPLEMENTATION-SPEC.md`
> as the controlling documents.
>
> Work through Work Packages 0-12 in order and treat every exit gate as mandatory. This is a
> greenfield implementation: delete all legacy source-manifest compatibility. Preserve unrelated
> dirty/staged work in both repositories, especially Micro6 gameplay-contract changes and
> ChromaScape gameplay/replay/tick-recorder work. Use native Windows commands and JDK 21.
>
> Begin by making Micro6 compile without broadly changing the recorder, then finish legacy removal,
> isolate SC-810 canonical JSON semantics, add the missing multi-module/shared-helper/generated
> mapping fixtures, complete mandatory configuration, and create the real signed promoted Fishing
> bundle. Finish with a real Micro6 launch, Fishing run/recording binding, debugger source reads,
> negative security checks, focused suites, and full suites.
>
> Never expose secrets, weaken clean-tree/signature/authentication checks, skip verification gates,
> or claim unrun evidence. Do not commit, push, mutate Shortcut, or recreate Felix records without
> explicit authorization. Return a file-by-file inventory, exact commands/results, artifact digest,
> manifest hash, snapshot ID, bundle evidence, and the ten-row acceptance matrix.
