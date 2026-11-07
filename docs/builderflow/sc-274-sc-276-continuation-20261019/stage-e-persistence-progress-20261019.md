# SC-274 continuation: stage E authenticated source-run persistence

Observed 19 October 2026. Development-trust experiment only. Supersedes the process/status
claims in [startup-validation-and-full-service-progress.md](startup-validation-and-full-service-progress.md)
without changing its historical evidence.

## Scope and labelling

- Source manifest, Ed25519 signature and file bytes are real: ChromaScape fetched them through
  its own transport from the owned 8180 runtime (`srcsnap_0585920d2af05903264627ba`, manifest
  `sha256:0585920d…c4fb900`, artifact `sha256:96c912c9…6a71e0`) and verified them with the
  **development** key only.
- Run, attempt, profile, settings, catalog, client and executor identifiers are an
  **AUTHORED_FIXTURE** (`sc274exp-authored-fixture-*`). The prerequisite SC-572 stabilization
  row was ingested through the legacy `harness-manifest/v1` route, which ChromaScape always stores
  with `verified=false`; tier and evidence mode are `AUTHORED_FIXTURE`. It is not captured
  stabilization evidence, certifies nothing, and exists only in the owned tmpfs database.
- None of this satisfies the original trust-root gate, genuine-input stage G, or registry
  selection stage F.

## Changed files

None in any repository. New owned experiment scripts under evidence root
`V = C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\stage-e-persistence`:

- `owned_service.py`: ownership-checked stop (command line must name the trust-exp mirror,
  owned home and `ChromaScapeApplication`) and relaunch of 8181. It reuses the labelled
  container without creating or removing it, and reads its password from the container config.
  Unlike the original launcher, it sets `AI_ROUTINE_CAPTURE_TOKEN`, without which
  run creation always returns 401. Generated tokens go to the session scratchpad, never `V`.
- `StabilizationFixtureHash.java`: computes the fixture `manifest_hash` with ChromaScape's own
  `StabilizationHashUtil` on the mirror classpath.
- `persistence_e2e.py`: phases `phase1`, `phase2` (after restart) and `tamper`.

## Acceptance results

Service relaunches: PID 55600 → **140964** (phase 1) → **172692** (phase 2/tamper), same
owned database `sc274-source-trust-postgres-20261019` on 55439. Ready in 33.8 s / 31.2 s.

Phase 1 (`V\phase1-results.json`, 30/31 recorded; see correction):

- Supported path `POST /api/v1/ai-routine/runs/from-stabilization/{attemptId}` produced
  `SOURCE_BOUND`, with exactly one manifest reference, run and binding. The persisted identity
  matches the served snapshot, manifest hash, artifact, commit, tree and environment.
  It also persists `VERIFIED/VERIFIED` and retention `AUDIT/INTERNAL`.
- Repeat create is idempotent. The same run ID with a changed identity is rejected with 422
  `IDENTITY_MISMATCH` and leaves the binding unchanged.
- Rejections with **zero rows written** (atomic rollback): no capture token gives 401; an
  attempt without PASS gives 422 `STABILIZATION_NOT_VERIFIED`. A wrong artifact digest, wrong
  snapshot ID or wrong manifest hash each gives 409 `SOURCE_BUILD_MISMATCH`.
- Debugger reads: manifest 200 (34 files, `VERIFIED`); symbols 200 (total 1718, page of 100).
  File reads return 200 with content SHA-256 equal to the manifest `content_hash`.
- Eleven debugger negatives behave as specified:
  - 401 `SOURCE_ACCESS_DENIED`: no bearer, or the token for the other purpose.
  - 400 `SOURCE_AUDIT_CONTEXT_INVALID`: wrong purpose, environment, correlation or actor.
  - 404: unknown run, a malformed file ID, or a well-formed file ID absent from the manifest.
  - 400: symbol limit 501.
- **Correction:** the one recorded failure was a test-scoping defect, not product behaviour.
  The audit query for ClientThread.java also returned a `SOURCE_ACCESS_DENIED`, which came
  from the metadata-token-on-content negative check (correlation `n3`). One such row came from
  an earlier aborted attempt that used the wrong stabilization auth header, and one from this
  run after the reads. Scoped to correlations `p1-file-a*`, the sequence is exactly
  `CACHE_MISS_VERIFIED`, `CACHE_HIT`. The original record is preserved as
  `phase1-results.recorded.json`. The script now scopes audit checks by correlation ID.

Phase 2 after restart (`V\phase2-console.log`, 11/11):

- Pre-restart debugger token rejected (401). Run still `SOURCE_BOUND`.
- Manifest and symbol responses are byte-identical to pre-restart (SHA-256 of the response
  documents equal).
- Previously read file served as `PERSISTED_CACHE_HIT` with no upstream fetch. A never-read
  file was fetched upstream and verified (`CACHE_MISS_VERIFIED`). Idempotent create after
  restart adds no rows.
- The script then crashed at the old tamper step with WinError 206 (a 36 KB hex literal on
  the `docker exec` command line) before any SQL ran, so `phase2-results.json` does not exist.
  Afterwards all three persisted content objects were re-hashed in-database and still matched.

Tamper (`V\tamper-results.json`, 9/9), run server-side on owned rows with backup tables:

- A same-length single-byte change to persisted Microbot.java content (not in that process's
  cache) gives 422 `SOURCE_CONTENT_HASH_MISMATCH`. The row was restored and its hash verified,
  and a re-read returned 200.
- An altered persisted manifest JSON gives 422 `SOURCE_MANIFEST_INVALID`. It was restored
  md5-identical, and a re-read returned 200. Backup tables were dropped.

Unchanged: shared 8090 (PID 195400), shared `chromascape-postgres` (not accessed this
increment), owned runtime 8180 (PID 171232). The selected Micro6 build worktree is
Git-clean. 8098 is not listening. No game login was performed.

## Tests

Live HTTP/database experiment only: 30/31 + 11/11 + 9/9 checks as above. No Gradle suite was
run in this increment and no source changed.

## Risks

- A cached object can mask durable-store tampering while it remains in the process cache. The
  cache entry is itself hash-verified, so correct bytes are served. The tamper case
  deliberately used an uncached object. Treat this as intended acceleration behaviour, not as
  durable-store integrity verification on every read.
- The fixture stabilization row exists only in the disposable DB. If anything copied this DB,
  the row is marked `verified=false` and tier `AUTHORED_FIXTURE`.
- All earlier stage E limitations remain: missing RuneLite profiles.json, behaviour-graph
  schema resolution errors, optional MCP probes, schema-copy rather than migration parity.

## Remaining work

Stage E development-trust integration is now evidenced end to end for persistence, restart and
rejection. Still open: original trust-root acceptance, stage F registry grants/routing,
genuine G inputs, H–N, and the shared 8090 rebuild/restart, which is still not deployed.

## Rollback

Stop 8181 with `owned_service.py stop <pid>`; it refuses non-owned processes. The owned
container may be stopped/removed only after its `sc274.owner=isolated-source-experiment`
label is checked; its tmpfs data is then discarded. No shared state needs rollback.

Felix remains skipped. No Felix or Shortcut write or completion claim was made.
