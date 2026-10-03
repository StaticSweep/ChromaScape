# SC-810 greenfield implementation specification

Current execution plan: `SC-810-LUNA-MAX-REMAINING-IMPLEMENTATION-PLAN.md`.

Status: implementation-ready replacement plan
Scope: `C:\Dev\IdeaProjects\micro6` and `C:\Dev\IdeaProjects\ChromaScape`
Delivery model: greenfield; no deployed legacy source-manifest consumers or data must be preserved

## 1. Outcome

Micro6 publishes a signed, content-addressed `task-source-manifest/v1` bundle for each executable
task build. ChromaScape binds a run or recording to that exact bundle only when repository,
build, launched-artifact, manifest, snapshot, file, symbol, environment, and signature identities
all agree.

The debugger may then display allowlisted source from the immutable snapshot. It must never read a
developer checkout, resolve a branch or tag, accept a caller-provided filesystem root or upstream
URL, or substitute current source for the source that produced a run.

`BehaviorGraphDefinition` remains explanatory metadata. It is not executable source and cannot
satisfy source provenance or patch eligibility.

## 2. Greenfield decisions

These decisions supersede legacy/backfill language in the original SC-810 description and the
earlier ADR:

1. `task-source-manifest/v1` is the only accepted schema. Delete support for
   `micro6-source-manifest/v1`.
2. Delete the embedded legacy Fishing manifest and the Gradle task that generates it.
3. Delete `allowLegacy`, legacy alias properties, legacy verifier results, legacy Fishing parsing,
   compatibility constructors, and tests that authorize unsigned or provenance-unknown source.
4. Signature verification, environment matching, upstream authentication, and debugger
   authentication are mandatory. There is no fail-open application default.
5. A dirty repository cannot produce an authoritative bundle. Generation fails before promotion.
6. A manifest is not embedded in the launched artifact whose digest it declares. The pipeline
   builds and hashes the executable first, then creates an external signed source bundle. This
   prevents a circular digest dependency.
7. No compensating migration is needed for a deployed schema. Before first release, the SC-810
   migration may be corrected in place. A developer database that already applied it must be
   recreated or explicitly repaired; do not silently mutate a shared database.
8. Fishing is the first golden task, not a special runtime compatibility case. All producer and
   consumer code must be task-generic.

## 3. System boundaries

### Micro6 owns

- resolving catalog task keys to executable entry classes;
- discovering the bounded source closure;
- parsing Java and indexing exact symbols;
- classifying source, generated, transformed, vendor, test, unrelated, sensitive, and excluded
  files;
- recording repository, build, artifact, parser, and indexer identities;
- canonicalizing, hashing, signing, packaging, validating, and atomically promoting bundles;
- serving only promoted manifest-allowlisted files;
- enforcing metadata/content credentials, environment boundaries, request limits, and audit
  records.

### ChromaScape owns

- fetching through a configured trusted Micro6 endpoint;
- independently validating the schema, canonical hash, snapshot ID, signature, key ID,
  environment, and all expected launch identities;
- binding the verified manifest to the selected run/recording in one transaction;
- persisting immutable manifest references and content-addressed objects;
- serving bounded, authorized debugger reads;
- enforcing confidentiality, retention, redaction, audit, and patch-eligibility rules;
- returning typed stable failures without revision fallback.

### Launcher/Mule attestation owns

- the build ID and SHA-256 digest of the artifact actually launched;
- the repository/source identities passed into SC-609 when available;
- an immutable correlation from launch to run/recording.

Launcher/Mule does not enumerate source and is not a source-content server.

## 4. Authoritative artifact lifecycle

The required order is:

1. Check out a pinned Micro6 commit and verify the Git worktree and index are clean.
2. Build the executable artifact from that checkout.
3. Compute `sha256:<64 lowercase hex>` over the exact artifact that will be launched.
4. Resolve every publishable catalog task, beginning with `FISHING_TRAINING`.
5. Parse sources and compute the task-specific closure and symbol index.
6. Copy allowlisted UTF-8 source bytes into a staging bundle under content-addressed identities.
7. Create the manifest with repository commit/tree, build ID, artifact digest, environment,
   closure, file identities, symbols, classifications, warnings, retention, and confidentiality.
8. Canonicalize and calculate `source_manifest_hash`.
9. Derive `source_snapshot_id` from the manifest hash.
10. Sign the completed canonical document with Ed25519.
11. Validate the staged bundle by reading it back exactly as the runtime catalog will.
12. Atomically promote the complete directory. A partial or failed bundle is never visible.
13. Configure the Micro6 source catalog to read promoted bundles only.
14. Launch the artifact and attest the same build ID and artifact digest.
15. ChromaScape fetches, verifies, and binds the manifest to the run.

Recommended promoted layout:

```text
<bundle-root>/
  task-source-manifest-v1/
    <environment>/
      <build-id>/
        <task-key>/
          manifest.json
          files/
            <file-id>.utf8
          COMPLETE
```

`COMPLETE` is written last during staging and the task directory is moved into the promoted tree
atomically. Runtime rejects directories without `COMPLETE`, unexpected files, symlinks, or a
manifest whose declared objects do not exactly match the directory contents.

## 5. Manifest contract

The canonical schema is:

`C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\source\task-source-manifest-v1.schema.json`

Micro6 must carry a byte-identical copy and a schema-equality test. Required semantics supplement
JSON Schema validation.

### Identity fields

- `schema`: exactly `task-source-manifest/v1`.
- `task_key`: canonical catalog key such as `FISHING_TRAINING`.
- `task_family`, `catalog_version`, `settings_schema_version`: identities from the catalog entry.
- `environment`: bounded environment identifier and an exact match to both producer request
  context and ChromaScape trust configuration.
- `repository.repository_id`: stable logical repository identity, not a clone path or URL supplied
  by a caller.
- `repository.commit_id`: pinned lowercase Git commit.
- `repository.tree_id`: tree for that commit.
- `repository.clean`: must be `true` for `AVAILABLE`.
- `build.build_id`: immutable CI/local-build identity.
- `build.artifact_digest`: SHA-256 of the exact launched executable.
- `source_manifest_hash`: SHA-256 of canonical UTF-8 JSON after removing
  `source_manifest_hash`, `source_snapshot_id`, and `signature`.
- `source_snapshot_id`: `srcsnap_` plus the first 24 hex characters of the manifest hash.
- `signature`: Ed25519 over canonical UTF-8 JSON after removing only `signature`; the signed
  document already contains its manifest hash and snapshot ID.

Canonical JSON means UTF-8, recursively sorted object keys, preserved array order, no insignificant
whitespace, and no numeric/string normalization outside the contract. Micro6 and ChromaScape must
share golden canonicalization vectors.

### Files and closure

Each file has a stable `file_id`, normalized repository-relative `/` path, module, language,
content hash, byte size, classification, and human-readable inclusion reason.

Rules:

- Paths are Unicode-normalized repository-relative paths with `/` separators.
- Reject empty components, `.`, `..`, absolute/UNC/drive paths, backslashes, encoded separators,
  NUL/control characters, and paths longer than the schema bound.
- Resolve real paths during generation and reject symlink/junction escape.
- Hash exact UTF-8 bytes. Reject malformed UTF-8; do not replacement-decode.
- `file_id` is derived deterministically from the content identity plus normalized path identity.
- Shared helpers may occur in several task manifests but have one content-addressed object.
- The closure records why each included dependency is reachable from the task entry point.
- Reflection/service-loader/config references that cannot be proved statically must be provided by
  explicit provider declarations and emitted with a reason.
- Closure limits fail with a typed generation error; they never silently truncate an `AVAILABLE`
  manifest.

### Symbols

Symbols include stable ID, file ID, kind, qualified name, signature, optional enclosing symbol,
and exact zero-based byte / one-based line-column boundaries as defined by the schema.

Rules:

- Byte spans address exact UTF-8 bytes and must not split a code point.
- `0 <= start_byte < end_byte <= file size`.
- Line/column spans must resolve to the same bytes in independent consumer verification.
- Overloads are distinguished by normalized signatures.
- The entry point references an existing class symbol in its declared file.
- Symbol IDs are deterministic across repeated generation of identical source.
- Parser recovery may create `INDEX_PARTIAL`, but cannot claim `AVAILABLE` when an entry point or
  requested patch-loop symbol is missing or ambiguous.

### Classification and availability

- `SOURCE`: authored executable source eligible for display.
- `GENERATED` / `TRANSFORMED`: allowed only with an explicit mapping to authored inputs and never
  silently presented as authored source.
- `VENDOR`, `TEST`, `UNRELATED`, `SENSITIVE`, `EXCLUDED`: never returned as source content.
- `AVAILABLE`: complete, clean, signed, fully verified, and patch-eligible subject to permissions.
- `INDEX_PARTIAL`: files may be retained for diagnosis but patch eligibility is false.
- `UNAVAILABLE`, `REDACTED`, `PRUNED`, `GENERATED_ONLY`: stable terminal read states for the
  affected content; no fallback.

An `AVAILABLE` manifest always has a valid signature, a real artifact digest, a clean tree,
non-empty files and symbols, a valid entry point, and a complete bounded closure.

## 6. Micro6 producer implementation

### Remove obsolete surfaces

- Delete `runelite-client\src\main\resources\micro6-contracts\fishing-source-manifest.json`.
- Delete the Gradle generation path that emits `micro6-source-manifest/v1` or `UNVERIFIED`.
- Remove all runtime fallback to embedded resources.
- Remove APIs/types/tests that parse or serve the old schema.

### Repair the current compile blockers first

In the current Micro6 source:

- rename the two shadowing `entry` loop variables in `FishingTaskSourceProvider.resolve`;
- make the `TaskSourceBundleGenerator.validate` call use a correctly scoped instance or make the
  operation intentionally static with all dependencies explicit;
- replace the unsupported Gson `JsonParser.parseString` usage with the Gson version-compatible
  parser API already used in this repository;
- make `plain(...)` return a typed `Map<String,Object>` at the object boundary instead of casting
  an untyped `Object`.

Do not suppress these errors with raw casts or rename-only workarounds that leave ownership or
typing ambiguous.

### Generator

`TaskSourceManifestGenerator` must accept an explicit immutable context containing repository ID,
commit/tree, clean-state evidence, build ID, artifact path/digest, environment, parser/indexer
versions, task catalog entry, signing key reference, and configured limits.

The generator must:

- use the catalog/provider to resolve task entry point generically;
- discover imports, same-module references, cross-module references, nested types, annotations,
  configuration, provider-declared reflection edges, and generated/transformed mappings;
- classify before copying bytes into the bundle;
- reject sensitive patterns and paths even if reachable;
- emit deterministic ordering for files, edges, symbols, warnings, and mappings;
- return typed failures and never publish an incomplete directory.

### Bundle generator and promotion

`TaskSourceBundleGenerator` owns staging, file copying, manifest generation, signing, read-back
validation, and atomic promotion. The private key is read from a secret provider or explicit local
ignored path and is never logged, serialized into the bundle, or committed.

The public key and `key_id` are deployment configuration. Key rotation is explicit: consumers may
temporarily trust a bounded set of public keys by key ID, but a manifest must match exactly one
trusted key.

### Runtime catalog and API

`SourceManifestCatalog.Registry.loadBundle` must load promoted bundles only and fail startup when
the bundle root, trusted configuration, or required task bundle is missing.

Keep the existing trusted API shape:

- `GET /api/v1/source-manifests/{taskKey}`
- `GET /api/v1/source-snapshots/{snapshotId}/files/{fileId}`

The task-key lookup resolves to the configured launched build, not “latest”. If more than one build
can be hosted, the server-side deployment identity selects it; callers still cannot select a path,
root, branch, repository URL, or arbitrary build.

Metadata and content use separate bearer credentials. Require and validate:

- `Authorization`
- `X-Source-Environment`
- `X-Source-Actor`
- `X-Source-Purpose` (`manifest-read` or `file-read`)
- `X-Correlation-ID`

Never follow redirects. Never serve a file absent from the selected signed manifest. Verify file
size and hash again immediately before returning bytes. Responses set `Content-Type` explicitly,
disable sniffing, and contain no host paths or secret configuration.

## 7. ChromaScape consumer implementation

### Remove compatibility and make trust mandatory

- Remove `SourceTrustProperties.allowLegacy`.
- Default `requireSignature` to true or remove the switch entirely and always verify.
- Remove all `micro6.source-manifest.*` trust aliases; keep transport settings under one explicit
  transport prefix and trust settings under `chromascape.source.*`.
- Remove `TaskSourceManifestVerifier` legacy parsing/results.
- Remove `Micro6SourceManifestClient.resolveLegacyFishing` and the deprecated no-attestation
  Fishing entry point.
- Remove constructors that silently create development trust defaults.
- Make environment, public key/key ID, metadata token, and content token mandatory startup
  configuration.
- Update `.env.example` with names and safe placeholders only; never include secret values.

Tests use explicit test configuration and an ephemeral Ed25519 key pair. There is no unsigned
runtime mode hidden behind a profile.

### Verification order

Before persistence, ChromaScape validates:

1. bounded response size and strict UTF-8;
2. JSON Schema and absence of unknown properties;
3. schema version equals `task-source-manifest/v1`;
4. safe paths, unique IDs/paths, file sizes/hashes, symbol spans, entry-point references, closure
   references, classifications, and status invariants;
5. canonical manifest hash and derived snapshot ID;
6. Ed25519 algorithm, trusted key ID, signed-bytes hash, and signature;
7. environment equals configured and attested environment;
8. task key, repository ID, commit, tree, build ID, artifact digest, snapshot ID, and manifest hash
   equal the SC-609 expected identity;
9. status is eligible for the requested operation.

Any mismatch fails before database work. Error messages expose stable codes and safe context, not
raw source, tokens, URLs, host paths, or signature material.

### Transaction and persistence

- Network fetch and cryptographic verification run with no database transaction active.
- The service then locks/reloads the run or capture, revalidates its launch identity, and writes
  manifest reference, run binding, optional recording binding, and `SOURCE_BOUND` in one
  transaction.
- Helpers use `REQUIRED`; no remote call occurs within an open transaction and no helper uses
  `REQUIRES_NEW` for the success path.
- Identity columns are insert-once. A repeated identical bind is idempotent; a different identity
  is a conflict.
- Content objects are keyed by content hash and verified before insertion. Concurrent identical
  inserts converge; conflicts fail.
- Redaction/pruning is monotonic and preserves historical hashes and bindings.
- Database triggers/constraints enforce immutability in addition to service checks.

Because this is pre-deployment, make `V21__sc810_source_provenance.sql` match the final model rather
than adding a compatibility migration. Recreate only the dedicated local/test database after
explicitly confirming it contains no needed data.

### Debugger API

ChromaScape exposes:

- `GET /api/v1/debugger/source/runs/{runId}/manifest`
- `GET /api/v1/debugger/source/runs/{runId}/files/{fileId}`
- `GET /api/v1/debugger/source/runs/{runId}/symbols?fileId=...&page=0&limit=100`

Metadata and content permissions remain separate. Pagination and response sizes are bounded.
File reads use the run binding and file ID only; a request cannot inject relative paths or an
upstream location. Every outcome is audited with opaque actor, environment, run, task, snapshot,
file, purpose, correlation ID, and result code. Source bytes and credentials never enter audit
rows.

## 8. Failure contract

Use `source-error/v1` consistently in Micro6 and ChromaScape. At minimum preserve distinct stable
codes for access denied, audit-context invalid, schema unsupported, manifest invalid, signature
invalid, build mismatch, content hash mismatch, index partial, symbol/file not found, unavailable,
network unavailable, redacted, pruned, rate limited, unsafe path, dirty tree, parser failure, and
promotion failure.

Suggested HTTP mapping:

- 400: malformed selector/path/audit context;
- 401/403: authentication or authorization failure;
- 404: allowlisted identity does not exist or is redacted without disclosure;
- 409: immutable binding conflict or build identity conflict;
- 410: pruned content;
- 422: schema/signature/hash/parser/index invariant failure;
- 429: rate limit;
- 503: authoritative producer or bundle unavailable.

Never translate any failure into a read from another snapshot.

## 9. Required verification matrix

### Contract and cryptography

- Micro6 and ChromaScape schema bytes are equal.
- Golden canonical JSON and hash vectors match in both repositories.
- Valid Ed25519 signature passes; altered field, array order, file bytes, key ID, or signature
  fails.
- RSA/wrong Ed25519/malformed Base64/unknown key IDs fail startup or verification as appropriate.
- Snapshot ID is derived exactly from the manifest hash.

### Producer fixtures

- real `FISHING_TRAINING` entry class and expected helper/config closure;
- multi-module task with a cross-module edge;
- two tasks sharing the same helper/content object;
- generated source with authored-input mapping;
- transformed source mapping;
- parser failure and parser recovery resulting in the correct non-`AVAILABLE` status;
- missing or ambiguous entry symbol;
- dirty tracked file, staged file, untracked relevant file, and clean tree;
- symlink/junction escape;
- sensitive/excluded/vendor/test/unrelated classification;
- reflection/provider-declared edge;
- deterministic repeated generation on Windows;
- closure/file/symbol/byte-size limit exhaustion;
- atomic promotion failure leaves the prior complete bundle untouched.

### Consumer and API

- exact SC-609 identity binds successfully;
- every mismatch dimension is rejected independently;
- absolute, drive, UNC, traversal, backslash, encoded separator, and malformed UTF-8 inputs fail;
- redirects and raw caller target URLs are impossible/rejected;
- metadata token cannot read content and content token receives only authorized content;
- cross-environment reads fail;
- file hash/size is checked on cache miss and hit;
- redacted/pruned/unavailable/generated-only/index-partial responses never fall back;
- pagination/rate/size limits are enforced;
- audit events contain required identity but no source bytes, token, host path, or raw URL;
- identical concurrent persistence is idempotent and conflicting persistence rolls back;
- remote fetch is proven to execute outside a transaction;
- a failed bind leaves no partial manifest/run/recording/stage writes.

### Native-Windows commands

Use JDK 21 and native Windows commands only.

Micro6 focused verification:

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :client:test --tests *TaskSourceManifestGeneratorTest --tests *TaskSourceBundleGeneratorTest --tests *SourceManifestCatalogBundleTest --tests *SourceManifestCatalogTest --tests *TaskSourceManifestSchemaEqualityTest --no-daemon
```

Micro6 full verification must also run without excluding `:client:verifyCaptureContract`:

```bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat test --no-daemon
```

ChromaScape focused verification:

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat test --tests "*TaskSourceManifestVerifierTest" --tests "*SourceManifestBindingServiceTest" --tests "*Micro6SourceManifestClientTest" --tests "*SourceDebuggerReadServiceTest" --tests "*SourceAccessAndCacheTest" --tests "*SourceRetentionServiceTest" --tests "*SourceContractSchemaEqualityTest" --tests "*SourceRunOrchestrationIntegrationTest" --tests "*SourceManifestConcurrentPersistenceIntegrationTest" --tests "*SourceFetchTransactionSuspensionTest" --no-daemon
```

Then run the full ChromaScape suite:

```bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat test --no-daemon
```

The current unrelated/context blocker `TaskPlan is not a managed type` must be resolved or
isolated so the nine SC-810 Spring integration tests actually execute. Do not report those tests
as passing merely because unit tests pass or because an earlier worktree once loaded.

## 10. Implementation phases

### Phase A — establish a compilable baseline

1. Record both repository branches, commits, status, JDK, and Gradle versions.
2. Preserve all unrelated dirty ChromaScape work.
3. Fix only the five known Micro6 compilation errors and rerun `:client:compileJava`.
4. Record the separate SC-968 capture hash gate; repair it only if its expected hash is genuinely
   stale and the owning contract evidence proves the new hash.

Exit: Micro6 compiles and failures are no longer hidden behind compiler errors.

### Phase B — remove legacy and lock the contract

1. Delete legacy resource generation and consumer compatibility.
2. Make the v1 schema byte-identical in both repositories.
3. Finalize canonicalization/signature golden vectors and failure codes.
4. Make secure configuration mandatory at startup.

Exit: no code, resource, property, test, or documentation path accepts
`micro6-source-manifest/v1`, `UNVERIFIED`, unsigned manifests, or current-checkout fallback.

### Phase C — complete Micro6 production

1. Complete generic task entry resolution and closure discovery.
2. Complete exact symbol indexing and generated/transformed mappings.
3. Implement typed classification and exclusions.
4. Complete deterministic signed bundle generation and atomic promotion.
5. Make the runtime catalog serve promoted bundles only.
6. Generate the golden signed Fishing bundle from a clean build artifact.

Exit: the Micro6 fixture matrix passes and a promoted Fishing bundle contains real repository,
build, artifact, closure, file, and symbol identities.

### Phase D — complete ChromaScape consumption

1. Enforce signature/auth/environment requirements and remove compatibility constructors.
2. Verify every identity before persistence.
3. Finish immutable transactional run/recording binding.
4. Finish bounded debugger APIs, cache verification, audit, and retention behavior.
5. Correct the pre-release SC-810 migration and persistence constraints.

Exit: unit, security, transaction, concurrency, and API tests pass.

### Phase E — end-to-end proof

1. Start Micro6 against only the promoted signed bundle.
2. Launch the exact hashed artifact and capture its SC-609 attestation.
3. Publish/run Fishing and bind its recording in ChromaScape.
4. Read the manifest, entry file, helper file, and symbols through the ChromaScape debugger API.
5. Mutate each identity in negative tests and prove fail-closed behavior.
6. Prove the source shown matches the promoted bytes and no checkout path was accessed.

Exit: golden Fishing evidence satisfies all ten Shortcut acceptance criteria.

### Phase F — delivery records

1. Save commands, versions, hashes, test XML, generated manifest hash/snapshot ID, artifact digest,
   and redacted API evidence.
2. Reconstruct or replace the missing Felix SC-810 task only after implementation evidence exists.
3. Update Shortcut with implementation and verification evidence.
4. Move SC-810 out of To Do only when all definition-of-done items below are true.

## 11. Definition of done

- Both repositories compile on native Windows with JDK 21.
- Focused and full tests pass without excluded verification gates.
- No legacy schema/resource/property/fallback remains.
- A clean Micro6 build produces a real signed promoted Fishing bundle.
- Fishing has a non-empty entry point, symbols, closure, repository commit/tree, build ID, and real
  launched-artifact digest.
- ChromaScape requires signature, trusted key ID, environment, and authentication.
- A real Fishing run binds to exactly that snapshot and every mismatch fails closed.
- Required multi-module/shared-helper/generated/parser-failure/dirty-tree fixtures pass.
- Path, redirect, confidentiality, authorization, audit, retention, transaction, and concurrency
  tests pass.
- BehaviorGraph/source separation is documented.
- Felix and Shortcut contain durable implementation and verification evidence.

## 12. Luna Max execution instructions

Give Luna Max this document as the controlling specification and use the following operating
prompt:

> Implement SC-810 from
> `C:\Dev\IdeaProjects\ChromaScape\docs\SC-810-GREENFIELD-IMPLEMENTATION-SPEC.md` across
> `C:\Dev\IdeaProjects\micro6` and `C:\Dev\IdeaProjects\ChromaScape`.
>
> This is greenfield. Nothing using the old source manifest has been deployed. Remove the legacy
> schema, embedded Fishing manifest, fallback code, compatibility constructors/properties, and
> unsigned development behavior. Do not preserve `micro6-source-manifest/v1`.
>
> Work phase by phase. First make Micro6 compile, then remove legacy and lock the shared contract,
> then finish the Micro6 signed bundle, then finish ChromaScape verification/persistence/API, and
> finally run the end-to-end Fishing proof. Use native Windows paths and commands and JDK 21.
>
> Preserve unrelated dirty work in ChromaScape. Before editing each file, inspect its diff and
> distinguish SC-810 changes from other stories. Never revert or overwrite unrelated edits. Keep
> Micro6 and ChromaScape changes logically separated. Do not commit, push, change Shortcut status,
> or recreate Felix records unless explicitly authorized.
>
> Treat the specification's exit criteria as gates. After each phase, run the smallest relevant
> tests and report exact commands/results. Do not skip the full builds, do not exclude
> `verifyCaptureContract` in final evidence, and do not claim the nine Spring tests pass unless
> they execute successfully in the current worktree. If blocked by unrelated code, diagnose it,
> make only a minimal safe fix when it is required for verification, and label it separately.
>
> Finish with a file-by-file change inventory, contract/hash identities, promoted Fishing bundle
> evidence, acceptance-criteria matrix, exact test totals, remaining blockers, and confirmation
> that no mutable checkout fallback exists.

Luna Max should keep a live checklist matching Phases A-F, update it after every verified gate,
and avoid broad refactors until the contract, generator, and verifier are green. The safest work
unit is one invariant plus its tests: implement it, run focused verification, inspect the diff,
then proceed.
