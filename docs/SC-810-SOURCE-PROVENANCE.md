# SC-810 source provenance and immutable snapshots

SC-810 treats the Micro6 source manifest as an attested, read-only build artifact. A
BehaviorGraph explains a decision, but it is not executable source and cannot replace the
manifest or its snapshot.

## Trust boundary

Micro6 publishes a versioned task-source manifest and serves only file IDs allowlisted by that
manifest. ChromaScape accepts the response only after validating the task, schema, canonical
manifest hash, snapshot ID, repository commit/tree, build ID, launched artifact digest, file
metadata, symbol ranges, and (when present) the configured Ed25519 signature. No developer
checkout, branch, tag, host path, or caller-provided URL is used as a source fallback.

The ChromaScape source read model stores the verified manifest reference and content-addressed
source objects. The in-memory cache is only an acceleration layer. A cache miss fetches the exact
snapshot/file identity again; it never resolves a current branch. Run and recording bindings are
one-way identities and cannot be replaced after binding.

## Read API

The authorized, read-only endpoints are:

- `GET /api/v1/debugger/source/runs/{runId}/manifest`
- `GET /api/v1/debugger/source/runs/{runId}/files/{fileId}`
- `GET /api/v1/debugger/source/runs/{runId}/symbols?fileId=...&page=0&limit=100`

Metadata and content use separate bearer credentials. Every request is bounded and audited with
opaque identities, operation, outcome, environment, and optional `X-Correlation-ID`. Responses
never contain credentials, host paths, raw upstream URLs, or source bytes in audit records.

Sensitive and excluded files are denied at the ChromaScape content boundary even when they are
present in a signed closure. `GENERATED`, `TRANSFORMED`, `VENDOR`, `TEST`, and `UNRELATED` are
explicit manifest classifications; a provider must not silently present them as ordinary source.

## Failure and retention behavior

Failures use the `source-error/v1` contract. In particular, `SOURCE_BUILD_MISMATCH`,
`SOURCE_CONTENT_HASH_MISMATCH`, `SOURCE_REDACTED`, and `SOURCE_PRUNED` are not converted into a
current-source response. Legacy manifests may be retained for graph/replay display as
`sourceProvenance=UNKNOWN`, but they are not eligible for the patch loop.

Retention marks are monotonic. Pruning or redaction preserves the manifest/content hash and
historical binding while preventing later content reads. Database triggers protect immutable
identity columns and permit only the documented null-to-recording assignment.

## Configuration and rollout

Production should set `CHROMASCAPE_SOURCE_REQUIRE_SIGNATURE=true`, configure the trusted public
key and key ID, set a non-empty source environment, and provide separate metadata/content tokens.
The unsigned development mode is intentionally stored as `UNSIGNED` and remains blocked from
patch provenance. Enable task providers one at a time, beginning with Fishing, and verify exact
run/artifact/snapshot identity before exposing source navigation.

## Canonical trust configuration (SC-810 completion)

`chromascape.source.*` is the single canonical manifest trust configuration
(`SourceTrustProperties`). `SourceManifestBindingService`, `SourceDebuggerReadService` and
`Micro6SourceManifestClient` all consume that bean; transport settings (base URL, bearer token,
actor, timeouts) stay separate. Legacy trust values under `micro6.source-manifest.*` are aliases
only: services no longer read them, and `SourceConfigurationValidator` fails startup when an
explicitly configured alias disagrees with the canonical value.

Startup validation (`SourceConfigurationValidator`) fails closed on:

- `require-signature=true` without a trusted public key and key ID;
- a configured key that is not valid Base64 X.509 Ed25519 (malformed Base64 and RSA or other
  non-Ed25519 keys are both rejected);
- a blank or unsafe key ID, or an environment token that is not a safe bounded value;
- legacy alias disagreement with the canonical trust configuration;
- `micro6.source-manifest.auth-required=true` without `micro6.source-manifest.auth-token`;
- `chromascape.source.require-auth=true` without both metadata and content tokens.

Development defaults keep signature verification off, legacy compatibility on, upstream
authentication off unless explicitly enabled, and debugger authentication off unless explicitly
enabled. Production must explicitly enable signature verification and authentication.

## Transaction boundary (SC-810 completion)

The remote Micro6 fetch and verification run in `SourceManifestFetchService` with no database
transaction active. `SourceRunOrchestrationService` then commits the manifest reference, run,
source binding and `SOURCE_BOUND` stage in exactly one transaction. The capture-completion flow
in `CaptureOperationResultService` fetches/verifies first (outside any transaction), then re-locks
the operation/capture, revalidates that the capture identity did not change, and commits the
manifest/recording binding together with the capture, run identity and operation state in one
transaction. Persistence helpers use default `REQUIRED` propagation and never `REQUIRES_NEW`.
Failures roll back every write; reconcilable `UNCERTAIN` state is recorded afterwards in its own
short transaction.

## Audit context across the service boundary

Every ChromaScape source request sends `Authorization` (when configured), `X-Source-Environment`,
`X-Source-Actor` (default `chromascape-web`), `X-Source-Purpose` (`manifest-read` / `file-read`)
and `X-Correlation-ID` (propagated from an inbound debugger correlation, otherwise a fresh UUID).
Micro6 validates and audits these values and rejects authoritative reads that omit them with
`SOURCE_AUDIT_CONTEXT_INVALID`.
