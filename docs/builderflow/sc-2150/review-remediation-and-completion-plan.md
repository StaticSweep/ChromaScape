# SC-2150 — Review remediation and full completion plan

Prepared 2026-10-19. Planning only; no remediation code or new application-test pass is claimed.
Story: https://app.shortcut.com/leeuwbot/story/2150
Existing Felix task: `task_1792382767281_cd8dbu69`.
Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Workflow: `shortcut_delivery`; preserve the sole classification `change:ui`.

This amendment supersedes conflicting implementation choices in the first increment. The original `docs/builderflow/sc-2150/full-plan.md` retains the overall product scope, architecture, 14 acceptance criteria and deferred ownership. This document defines every review correction and the ordered path to finishing the story. Do not create a second task or overwrite historical evidence.

## 1. Goal and scope

Make the existing upload/probe/catalog/media/export increment correct and secure before adding cursor analysis, review and YouTube acquisition. Then finish SC-2150: import a supported file or public YouTube video, inspect qualified visible behavior and unknowns, correct annotations, and export immutable source-linked evidence.

Include all seven review findings: configuration startup, owner impersonation, active-content delivery, publication/cancellation races, content-blind idempotency, partial upload registration and one-shot export. Include targeted hardening required to prove their guarantees: streaming intake before materialization, database fencing/locking, quota reservations, process cancellation, orphan reconciliation and versioned export identity.

Exclude Micro6 changes, gameplay/login/dispatch, exact video-to-tick alignment (SC-2151), bad-movement classifier verdicts (SC-309), automatic human verification, dataset publication bypass, model fitting/activation and production promotion. Video pixels never become native telemetry, exact physical clicks or hidden game state.

## 2. Current evidence and task truth

Inspected HEAD remains `5a7c30f8369806be6d6a400fd1fd59a45a32b5ec`; SC-2150 implementation is local/untracked and sits alongside unrelated changes. Inventory and hash the owned files before coding; use task-specific build outputs/cache where concurrent builds exist.

Verified in the preceding review:

- Spring Boot `YamlPropertySourceLoader` rejected `application.yml` with `DuplicateKeyException` for the duplicate `spring.servlet` key.
- Actual `MouseVideoAccess` accepted an unauthenticated arbitrary owner header; the local replay principal filter does not include mouse-video routes.
- An isolated service reproduction accepted different upload bytes under the same request ID as the original source.
- A controlled cancellation interleaving at the first analysis save produced a CANCELLED job with two analysis saves and one finalized quality reference.
- An injected artifact-registration failure left one source and zero jobs.
- Four existing JS tests and two real-media Python probe tests passed. Those results do not cover startup, ownership, PostgreSQL races, browser active content or repeated exports.
- Source inspection established caller-controlled response MIME, publication preceding fence validation, nontransactional registration and export's second-call 409.

The controlled Java reproductions used the existing mocked repository fixture, not real PostgreSQL concurrency. No browser exploit, deployment, restart or whole Gradle suite was run during review.

At planning intake Felix returned `task_status=in_progress`, `spec_state=draft`; Shortcut is In Progress. Historical comments describe prior transitions, but do not change the currently returned state. Do not claim spec_ready or a new workflow transition. Preserve acceptance checks as unchecked. Reopen original implementation I3, I6 and I11 because their fenced/authenticated/ownership guarantees are disproved. Add separate remediation and verification checklists so historical tests remain visible without being mistaken for new fixes.

Rules: shared IdeaProjects Felix/Shortcut delivery contract; ChromaScape `AGENTS.md`; existing `sc2150:delivery-rule`; original plan's immutable evidence, bounded process/resource and no-control constraints. Twenty unrelated outbox records were scanned and remain conflict-marked; SC-2150's prior eight records are in synced. Do not overwrite those conflicts.

## 3. Delivery order

1. R1 configuration and route-scoped streaming intake.
2. R2 trusted identity, operation authorization and browser request protection.
3. R3 verified safe media delivery and quarantine.
4. R4 content-bound idempotency and R5 atomic registration/quota reservation.
5. R6 atomic claims/fenced finalization/cancellation/cleanup.
6. R7 repeatable versioned exports.
7. Prove the corrected upload increment with real HTTP, PostgreSQL and browser tests.
8. Finish media proxy/PTS, cursor/quality evidence, append-only review, pinned YouTube acquisition, observability and whole-story acceptance.

Keep both flags disabled until the corrected increment passes its safety gates. Finishing an increment does not satisfy every original acceptance criterion. No code or runtime work is authorized by this plan-writing request alone.

## 4. R1 — Startup and bounded multipart intake

Problem: duplicate YAML keys prevent startup. Simply merging the two blocks keeps `multipart.enabled=false`, so the current `MultipartFile` controller still cannot work; removing that setting would regress stabilization's authentication-before-stream-parsing behavior.

Decision:

- Keep exactly one `spring.servlet.multipart` mapping, with global eager multipart parsing disabled as required by stabilization.
- Change only the video upload endpoint to consume the servlet stream through a bounded multipart reader. Extract the proven boundary/part mechanics from `StabilizationBundleStreamReader` into a shared utility if cleanly separable; retain a stabilization adapter with identical limits and behavior. Do not enable a global multipart resolver.
- Authenticate in the video filter before calling `getInputStream`, `getParts` or reading request parameters. Parse one video part plus a bounded JSON metadata part using server-generated staging paths. Unknown/duplicate parts are rejected. Record the upload contract change and update the UI/FormData together; no mixed old/new request parser is needed in this current-build feature.
- Bound request bytes, metadata bytes, header length, part count, file count, streaming file size and socket/elapsed intake budget. Initial values: file <=512 MiB, whole request <=520 MiB, JSON metadata <=16 KiB, <=2 parts; reject declared oversize early and enforce the same limits while reading chunked bodies.
- Never use an uploaded filename as a storage path; interrupted/invalid uploads discard only their staging objects.

Tests: actual application YAML load; real embedded-server upload with global multipart disabled; unauthorized body never consumed/materialized; oversized/chunked/duplicate/malformed part rejection; stabilization streaming and original size/auth regressions unchanged. A controller mock accepting `MultipartFile` does not prove this fix.

## 5. R2 — Trusted ownership and browser authorization

Decision:

- `MouseVideoAccess` accepts only a nonblank server-established Principal and derives both owner and authoritative audit actor from it. Missing identity is 401. Delete the owner-header fallback and `local-operator` fallback. Optional caller audit labels are explicitly untrusted metadata, never actor/authorization.
- Extend the existing same-host principal bridge to `/api/debug/mouse-videos/*` only for its already restricted sole `local-development` profile and direct-peer checks. Do not widen it globally, trust forwarding headers, or use a client-supplied owner name.
- Outside that mode, require the deployment's authenticated principal provider. If none is configured, fail closed with a clear capability/setup reason; do not silently introduce a new token-to-arbitrary-owner mapping.
- Enforce owner predicates on every source/job/analysis/review/artifact/export lookup. Return 404 for inaccessible object IDs and avoid leaking private metadata. Cross-source references must resolve to the same owner and source within a transaction.
- Apply a server-issued browser request nonce bound to the resolved owner for state-changing local UI requests, including upload, cancel, review, analysis and export creation. Reject cross-origin browser requests; no permissive CORS. A read-only nonce endpoint may be added under this authenticated namespace. Nonbrowser clients need the same authenticated write contract, not header-only owner selection.
- Reject requests before streaming/staging when the feature is disabled, actor is missing, request size/quota is impossible or the operation is unauthorized.

Tests: anonymous, valid principal, blank principal, forged owner/audit headers, local profile/remote peer/mixed profiles/forwarded headers, cross-owner enumeration/media/export/cancel, missing/wrong nonce and cross-origin mutations. Use real filter registration plus MockMvc/embedded server, not only direct service tests.

## 6. R3 — Safe original and preview media

Decision:

- Store caller Content-Type only as untrusted acquisition metadata. A successful bounded probe chooses a server-side allowlisted media type from actual container/codec; do not reflect request MIME into response headers.
- An original may exist before probing but is quarantined from inline rendering. Invalid/failed/unsupported originals remain inaccessible inline. Owner-authorized download, if offered, uses `application/octet-stream`, attachment disposition with a generated safe filename and `nosniff`.
- Serve a validated supported video or generated browser proxy inline using a strict allowlist. Thumbnails are separately validated image artifacts. Never inline HTML, SVG, XML or arbitrary active content through ORIGINAL/PROXY/THUMBNAIL.
- Require FINALIZED state, verified bytes and accepted source-role qualification before serving. Preserve controlled Range behavior and ownership on full, partial and invalid-range paths. Missing or quarantined bytes return typed unavailability, not a guessed playable state.
- Defense in depth: media responses use `Content-Security-Policy: sandbox; default-src 'none'`, private cache policy and `nosniff`; these headers do not replace content qualification. Keep player-page CSP compatible with its own media fetch.

Tests: HTML/SVG uploaded as text/html, image/svg+xml and fake video/mp4; same-origin top-level navigation must not execute script. Probe failure cannot expose active content. Test a genuine supported clip, unsupported container, full/Range/invalid Range, disposition/MIME and foreign owner. Verify real browser playback for declared supported codecs.

## 7. R4/R5 — Content-bound idempotency and atomic intake

Request identity must include authenticated owner, caller UUID, source kind, exact file SHA-256 and length, normalized semantic metadata and ingestion contract version. Treat caller MIME as metadata rather than trusted content type; document which fields affect conflict identity. YouTube identity uses canonical video ID plus normalized metadata; acquired bytes and provider metadata are immutable output evidence.

For upload:

1. Validate/authenticate/authorize bounded metadata and reserve admissible intake capacity before staging. A short database reservation keyed by owner/request prevents uncontrolled duplicate disk use; do not hold a database transaction open while receiving the body.
2. Stream into an owned temporary object while hashing and enforcing hard byte/time limits. Compare the completed digest on every retry, including an already-completed request. Do not return the previous source before checking the new bytes (or a separately authenticated, verified digest protocol).
3. Publish complete immutable bytes to the generated content address. Use atomic no-replace publication. Where hard links are unavailable, copy into a sibling temporary file, fsync/verify as supported and atomically publish; never expose an in-progress final pathname. Preserve the verified existing winner on races.
4. In a short transaction under the reservation lock, compare the stored canonical request digest. Identical requests return the original source/job; conflicting bytes or metadata return 409 and create no new source/ref/job.
5. Register source, ORIGINAL ref, job, acquisition event and quota charge atomically. Unique owner/request and source-role constraints remain final defenses. Use explicit transactional services/proxies, not self-invocation of `@Transactional` methods.
6. Queue work only after commit, preferably via the existing durable QUEUED poller. Rollback or process death between commit and scheduling must not lose the job. Poll in bounded pages, not all queued jobs.

Concurrency design: use PostgreSQL row/advisory locks or reservation INSERT ON CONFLICT followed by a locked read; do not rely on JVM `synchronized`. Quota reservations and final accounting use an owner budget row locked transactionally, including in-flight reservations and all references charged by policy. Do not sum only the first 1,000 source rows. Global objects may deduplicate bytes; source ownership and logical quota remain independent.

Crash boundaries: publication before SQL commit can leave an unreferenced complete byte object; never delete shared objects immediately on rollback. A reference-aware sweep cleans expired staging/reservations and verified unreferenced objects after a safety grace period. SQL registration cannot point at partially copied bytes. Disk-full/I/O errors, quota failure, disconnect and unique conflicts have explicit recovery outcomes.

Tests: same UUID/same bytes, different bytes, changed metadata, malformed UUID, two concurrent service instances, reservation expiry, crash after publication/before commit, failure at source/ref/job/event insertion, after-commit scheduling gap and quota races. Real PostgreSQL rollback tests must prove zero partial metadata rows; mocked repositories alone cannot prove atomicity.

## 8. R6 — Job claims, fencing, cancellation and final publication

### State/lease contract

`QUEUED → RUNNING → SUCCEEDED|FAILED|TIMED_OUT`; `QUEUED → CANCELLED`; `RUNNING → CANCELLING → CANCELLED|NEEDS_RECONCILIATION`.

Use a short `SELECT ... FOR UPDATE` or conditional `UPDATE ... RETURNING` claim that requires QUEUED and returns an acquired flag plus generation. If another worker owns the job, it must not execute merely because the returned row is RUNNING. Increment generation for each claim/reclaim; bind worker identity, attempt and database-clock lease expiry. Heartbeats compare job/generation/lease owner/RUNNING status; publication requires an unexpired matching lease. Do not use application wall time as cross-process authority.

Existing terminal states cannot be transitioned by late `progress`, `fail`, `heartbeat` or `succeed`. Add optimistic row version or equivalent conditional SQL for all competing updates. Do not rely on a read-only `mayPublish()` check followed by unrelated writes.

### Worker/cancel contract

- Keep owned Futures/process handles by job/generation. A running cancel request atomically enters CANCELLING and revokes publication rights, then signals the owned worker. Never return CANCELLED while probe/decoder cleanup is still unconfirmed.
- Worker polls cancellation/lease state at bounded intervals and interrupts only its own process group/descendants. Cleanup failure becomes NEEDS_RECONCILIATION and retains the occupied slot until safe resolution; do not release capacity while a live decoder remains.
- On graceful shutdown stop accepting work, revoke/settle claims and stop owned workers/executor. Restart recovery considers only abandoned/expired claims; starting another instance cannot invalidate another live instance's unexpired lease.
- An ambiguous previous process/cleanup outcome must not auto-retry. Preserve completed original media and staged evidence; use explicit lookup/reconciliation before a fresh attempt.

### Atomic finalization

Decode/probe and prepare immutable output bytes outside SQL transactions. In a single locked/fenced finalization transaction, verify current RUNNING status, owner/generation/lease/cancellation revision and complete artifact receipts; then register the analysis/artifact refs and SUCCEEDED event/status together. Either all become visible or none do. A cancel that wins first prevents every completed analysis/ref; a finalization that wins first returns the already-terminal success to a subsequent cancel. Never retroactively turn an accepted cancellation into success.

Failures must preserve event/state consistency; do not catch and ignore database event insert failures inside a transition transaction. Checksum/content corruption and missing byte receipts block finalization. Physical unreferenced blobs are reconciled independently without becoming visible analysis evidence.

```mermaid
sequenceDiagram
  participant Worker
  participant Store
  participant DB
  participant Cancel
  Worker->>DB: Atomic claim; acquire generation and lease
  Worker->>Store: Probe and stage immutable output
  Cancel->>DB: Lock job; CANCELLING and revoke fence
  Cancel->>Worker: Signal owned process; await cleanup
  Worker->>DB: Finalize with expected generation and lease
  DB-->>Worker: Refuse stale fence; no analysis/ref registration
  Worker->>DB: Confirm owned cleanup under cancellation identity
  DB-->>Cancel: CANCELLED (or NEEDS_RECONCILIATION)
```

Tests: two independent database sessions claim once; cancellation at each stage and between every finalization statement; expiry/reclaim/late heartbeat; terminal-state mutation refusal; process cleanup timeout; kill/restart and second-instance startup. Use barriers/latches and actual PostgreSQL transactions. Assert source/analysis/artifact/event visibility, not just status strings.

## 9. R7 — Repeatable versioned export

Decision: create an immutable export snapshot explicitly and retrieve it safely by identity.

- Add POST `/sources/{id}/exports` with authenticated write nonce, request UUID and exact analysis/review IDs (or explicit `UNAVAILABLE`/no-review snapshot). Resolve “latest” at creation once, under a consistent snapshot; never leave it as a mutable read-time selector.
- Return 201 on a new export, 200 on identical retry; return 409 only when the same request key conflicts. GET `/exports/{exportId}` and GET `/exports/{exportId}/manifest` retrieve the immutable result without mutation. Update the current UI and API together; do not retain the unsafe one-shot GET side effect.
- Identity pins source/original digest, optional analysis+manifest hash, review revision, acquisition evidence, role refs and contract/toolchain/config versions. Include only dependencies, never the export itself or unrelated prior exports. Canonical sorted JSON is encoded/decoded explicitly UTF-8.
- Add an export table/identity constraint or equivalent versioned reference entity. The existing unique source-role REVIEW_EXPORT rule cannot represent multiple snapshots: replace that constraint narrowly for versioned exports while keeping uniqueness for ORIGINAL and other singleton roles. Preserve old exported bytes; backfill their recorded identity as legacy-unqualified if required, without fabricating absent lineage or mutating old manifests.
- Publishing a newer analysis/review creates a new snapshot. Repeated download of the old snapshot is byte-identical and remains owner-scoped. Concurrent identical creation yields one authoritative export.

Tests: repeated create/get/download; network retry; export before and after analysis/review; exact old/new hashes; concurrent creation; foreign owner; malformed/mismatched cross-source references; non-ASCII labels and charset; export response contains no private filesystem path.

## 10. Additive schema and migration plan

Do not edit V42 if it has been applied anywhere. Reserve the next free Flyway version against the actual target branch and migrations in use; current local numbering is not authority for a deployed host.

Expected additive changes: intake reservation/idempotency identity; owner budget/reservation accounting; job row-version/claim predicates and cancellation/cleanup metadata; export snapshots and export-specific artifact reference key. Use constraints to prevent invalid cross-source/owner references where practical. Retain capability booleans false. Index queue/lease expiry, source-owner lookups, reservation expiry and export identities.

Inventory preexisting partial sources/jobs/artifacts. Mark orphans/uncertain rows for explicit reconciliation, rather than deriving provenance or silently discarding evidence. Test clean database plus V42-upgrade paths, failure recovery and existing original/source-role constraints. Use the repository's supported PostgreSQL test path; fix Docker API compatibility or provide a real PostgreSQL-backed integration profile with the same assertions. Do not replace these with skipped tests and report green concurrency.

## 11. Remaining SC-2150 product work after remediation

### C1 — Proxy, thumbnail and presentation-time index (original I8/AC1/AC13)

Pin interpreter, ffprobe/FFmpeg binary hashes/build identity and codec policy. Probe metadata is insufficient to prove decoded frames. Produce a browser-playable proxy, thumbnail and bounded original PTS/frame index; preserve rational timebase, VFR, rotation, crop and original/proxy mapping. Use streaming/index shards and hard frame/disk/memory/time limits. Fix `probe.py` output bounding before expansion: `capture_output` followed by slicing is not a memory cap. Drain into bounded buffers/files and terminate on overflow. Local file protocols alone do not isolate filesystem access; run decoder in the planned restricted filesystem/network/process environment.

Tests: real decoded clips with VFR, cuts, rotation, missing timestamps, invalid codec/metadata and output overflow; exact source-frame seeking and proxy mapping; artifact corruption and native Playback regression.

### C2 — Cursor tracking, quality and capability manifest (I9/AC5/AC6)

Implement the original bounded template tracker for a declared reviewed OSRS cursor pack, separate visibility and tracking confidence, and gaps at cuts/occlusion. No interpolation becomes observed input. Report video-space features only, with frame/PTS/crop/scale/toolchain/source identity. Automatic action/camera detectors require their own qualified methods; unsupported features remain manual/unknown. Preserve original held-out tracking thresholds and independent source-video splits from the full plan; do not relax them after seeing results.

Tests: manually annotated owned clips, absent/obscured/unsupported cursors, overlays and scaling, independent held-out tracking error/recall/false detection/coverage, repeatability. These qualify observations, not SC-309's human/bot or movement-quality verdicts.

### C3 — Append-only review API and UI (I10/AC7)

Add owner-authenticated review create/list/get and observation corrections with expected revision and idempotency. Preserve raw observations, predecessor/reviewer/reason/time, original source frames and corrections; serialize conflicting publication and reject cross-analysis references. Source/use review never automatically confirms human provenance or enables training. Exports pin exact review identity.

Tests: immutable originals, concurrent revision conflicts, same-key retries, source/use policy, keyboard correction workflow, refreshed page/retrieved review and reference-aware deletion. SC-712 published curation remains the separate eligibility gate.

### C4 — Supported YouTube acquisition (I7/AC2)

Keep its flag false until a pinned restricted downloader is available. Normalize approved single-video forms to canonical IDs; isolate acquisition from network-disabled decoding. Forbid arbitrary URLs, playlists/live/protected/auth-required workarounds, plugins/config/cookies/exec flags and caller executable selection. Pin needed tool/runtime versions and exact egress/redirect/IP policy. Persist acquisition time/video ID/format/toolchain/exact received media hash and explicit provider outcomes. Download into the same bounded reservation/byte-registration pipeline.

Tests: deterministic fake-provider faults and real accessible approved public video on the release host; removal/private/rate limit/oversize/redirect/cancel. A mocked success or truthful failure is not the successful supported-source acceptance demonstration.

### C5 — Observability, cleanup and complete browser workflow (I12/I13/I15)

Add bounded counters/logs for intake, queue/lease, bytes/frames, quota, duration, refusals, cancellation, cleanup and corrupt artifacts; no credentials/media/private URLs in logs. Reference-aware sweeps handle reservations/staging/orphans only after safe expiry. Capability readiness verifies actual configured tool/isolation support, not only a command name.

Complete persistent UI state for upload/status/cancel, source/filter pagination, qualified player/overlay/unknowns, review/export and request-ID reuse. Prevent accidental duplicate submit; restore exact source/job/export identity after refresh. Test actual browser DOM/events, not only four formatting helpers. Verify narrow layouts, keyboard controls, failure states and playback noninterference.

## 12. API and UX changes summary

| Surface | Planned behavior |
|---|---|
| Upload POST | Auth before bounded streaming; one metadata part + file; same content replay returns same result; conflicting content 409 |
| All protected routes | Principal-only ownership, foreign IDs 404; mutations require authenticated write protection |
| Media GET/Range | Verified allowed MIME/role/state; unqualified originals never active inline content |
| Cancel POST | 202 while CANCELLING; 200 after confirmed terminal outcome; include current revision and cleanup reason |
| Job status GET | Current stage/status/progress/revision and truthful cancellation/lease/error state |
| Export POST | Create explicit immutable source/analysis/review snapshot; identical retry returns existing export |
| Export GET/download | Repeatable read, immutable bytes, safe owner access |
| Reviews | Append-only revisions; exact conflict semantics; no raw evidence rewrite |
| Capabilities | Tool/proxy/tracking/provider availability and typed unknowns; disabled features cannot silently run |

UI mockup update to existing SC-2150 mockup: player shows validated preview only; pending/failed probe shows “Preview unavailable” and no inline original; cancel shows “Stopping worker…” until cleanup settles; exports list immutable versions with repeatable download; upload/review retries retain request identity. Native ticks remain unavailable until SC-2151.

## 13. Acceptance criteria for review remediation

- [ ] RA1 Given the ordinary configuration, when the application loads it, then YAML has no duplicate key and both video intake and stabilization preserve bounded streaming behavior.
- [ ] RA2 Given no trusted principal or a forged owner header, when any protected video operation is requested, then it is refused before reading private data or upload bytes.
- [ ] RA3 Given active HTML/SVG disguised as video, when imported or navigated to, then it cannot execute on the application origin or be served as trusted inline media.
- [ ] RA4 Given identical owner/request/file/metadata under concurrent retry, when registered, then one authoritative source/job exists; different bytes or metadata return 409.
- [ ] RA5 Given a failure/crash at any registration step, when recovered, then no partial metadata graph or unsafe shared-byte deletion remains.
- [ ] RA6 Given two workers and cancellation/lease expiry at any publication point, when transactions compete, then only the valid winner can finalize the complete analysis/ref/event/job result.
- [ ] RA7 Given a running worker, when cancel returns CANCELLED, then owned process cleanup is confirmed; ambiguity is NEEDS_RECONCILIATION, not success.
- [ ] RA8 Given repeat export/download and later analysis/review revisions, when used, then old results remain repeatable and new evidence produces a distinct immutable export.
- [ ] RA9 Given invalid/failed media, when catalog/media/export is inspected, then quarantine and missing evidence are explicit and no completion/control/training flag is fabricated.
- [ ] RA10 Given the corrected increment on a real host, when the original native recording and stabilization regression flows run, then their prior behavior and limits remain intact.

The original fourteen acceptance criteria still govern whole-story completion. RA1–RA10 are additional correction gates, not replacements.

## 14. Implementation checklist

- [ ] R1 Fix configuration and implement route-scoped authenticated bounded multipart intake; preserve stabilization contract.
- [ ] R2 Enforce trusted principal, owner predicates, authoritative actor and browser write protection on the full API closure.
- [ ] R3 Quarantine originals and serve only verified safe inline media; add attachment/CSP/type policy.
- [ ] R4 Bind request identity to actual content and normalize/validate bounded metadata and UUID.
- [ ] R5 Add reservation/quota locking, atomic source/ref/job/event registration, after-commit scheduling and orphan reconciliation.
- [ ] R6 Make claims/leases/finalization atomic and cancellation/process cleanup truthful; reject all stale/terminal writes.
- [ ] R7 Replace one-shot GET export with immutable repeatable export snapshots and retrieval.
- [ ] R8 Add forward migrations and clean/V42-upgrade real PostgreSQL coverage without rewriting applied migrations.
- [ ] R9 Prove all seven corrected findings with retained regressions and actual HTTP/browser/database boundaries.
- [ ] C1 Finish qualified proxy/thumbnail/frame PTS, resource isolation and bounded probe output.
- [ ] C2 Finish reviewed cursor tracking, quality/gaps/feature capability reports and independent clip qualification.
- [ ] C3 Finish append-only review API and accessible correction/source-use UI.
- [ ] C4 Finish pinned constrained YouTube acquisition and successful supported-source evidence.
- [ ] C5 Finish observability, reference-aware cleanup, complete browser flow and supported-host smoke.
- [ ] H1 Update docs/API/operator configuration/rollback, implementation evidence and verification evidence; reconcile original checklists only when each full item is proved.

## 15. Verification checklist

- [ ] V1 Real YAML/bootstrap and embedded-server streaming upload; stabilization regression and no pre-auth body materialization.
- [ ] V2 Registered-filter principal/owner/nonce/Origin matrix for all routes, including Range, export and cancellation.
- [ ] V3 Browser active-content refusal plus genuine video MIME/playback/Range qualification.
- [ ] V4 Content retry/conflict and two-instance concurrent registration/quota tests against PostgreSQL.
- [ ] V5 Failure injection and rollback at every source/ref/job/event/publication boundary, including after-commit queue gap and orphan sweep.
- [ ] V6 Actual PostgreSQL independent-session claim/heartbeat/expiry/cancel/finalize races with barriers and visible-artifact assertions.
- [ ] V7 Real slow process cancellation, bounded cleanup, shutdown/restart and live second-instance lease preservation.
- [ ] V8 Repeated/concurrent versioned export create/get, old/new analysis/review snapshots and UTF-8 hash roundtrip.
- [ ] V9 Forward migration on clean and V42-populated databases, including existing partial rows and immutable old evidence.
- [ ] V10 Existing mouse/replay/stabilization tests, focused Java/Python/JS suites, touched Spotless/Checkstyle and scoped whitespace checks.
- [ ] V11 Real-tool PTS/proxy/quality corpus, source-video-held-out cursor qualification and no invented telemetry.
- [ ] V12 Review corrections/conflicts/admission policy, browser persistence/keyboard/narrow layout and complete catalog flow.
- [ ] V13 Fake provider faults plus an approved successful real public YouTube import and corresponding acquisition manifest.
- [ ] V14 Release-host smoke of upload/probe/preview/cancel/restart/review/repeated export and native regression with exact tested tool/code identities.

Commands to finalize at implementation time: focused `./gradlew test --tests '*MouseVideo*'` with the native SC-967 fixture property, relevant stabilization/mouse/replay-artifact selectors, real PostgreSQL integration profile, `node --test src/test/js/mouse-video*.test.js`, `python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'`, Playwright video workflow/security smoke, scoped Spotless/Checkstyle and `git diff --check`. Java tests need isolated task output/cache when other agents/builds use this checkout. Tests that cannot run remain unchecked with exact prerequisites; unrelated baseline failures receive a disposition, not a claimed green suite.

## 16. Planned files and ownership

Within `C:\Dev\IdeaProjects\ChromaScape`:

- `src\main\resources\application.yml` and video/stabilization multipart configuration/adapters.
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\` access/filter/controller, import/registration/job/finalization/export/review services, entities/repositories/config and byte store.
- Existing local principal filter registration, shared bounded multipart mechanics and stabilization tests only as required; preserve unrelated API contracts.
- New next-version migration(s) for reservation/quota/job concurrency/export identity; no edits to applied V42.
- `src\main\resources\mouse-video\` worker/code/tool manifests and reviewed cursor assets.
- `src\main\resources\templates\mouse-video.html`, `static\js\mouse-video.js` and narrowly scoped CSS/catalog wiring.
- Java/PostgreSQL/Python/JS/browser fixtures and tests; `docs\mouse-video-import.md`, `.env.example` and the plan/evidence documents.

No Micro6 source/runtime or Git-wide reset/staging. Capture hashes/file ownership before edits and review every shared-file hunk. Keep historical evidence notes and append a superseding correction record.

## 17. Rollout, rollback and remaining risks

After corrected tests, qualify in an isolated native-host instance/database without replacing the user's existing runtime. Apply additive migrations and pin tools; enable upload only after auth/streaming/media/race gates pass. Enable YouTube separately only after its acquisition/isolation smoke. A plan does not authorize deployment or restart.

Rollback: disable intake/provider flags, stop/reconcile only owned jobs, restore previous tested application/tool bundle and preserve additive tables, originals, annotations and exports. Do not drop shared objects or overwrite another owner's artifacts. An older application must not resume new-generation jobs blindly; keep the feature off until schema/worker compatibility is reviewed. Resolve cleanup ambiguity before releasing its slot or retrying.

Risks: existing exports may be incomplete; old ownership labels are untrusted and cannot be promoted into verified identities without operator reconciliation; applied migration versions may differ by branch; retained source MIME does not establish safe content; decoder/download tooling and provider behavior can change; reviewed human clips may be unavailable. Preserve inspection-only/unknown outcomes and keep qualification gates explicit.

## 18. Felix/Shortcut handoff and definition of done

Reuse existing task and linked rules/notes; attach this full amendment. Keep in_progress and the returned spec state unchanged during this planning-only turn. Reopen original I3/I6/I11 and append unchecked remediation/verification/acceptance lists. Do not mark any fix or new test complete based on this plan.

When implementation is requested, update affected architecture/ERD/API/security/observability/UX notes consistently and validate the amended specification normally. No skip_validation, waiver or fabricated evidence. Before in_review every implementation item must be checked with `Implementation Evidence` containing `## Changed files`, `## Tests`, `## Remaining work`. Before done every original and added acceptance/test item must be checked with `Verification Evidence` containing `## Acceptance results`, `## Tests`, `## Risks`, `## Rollback`.

Only synchronize actual successful state transitions using `.felix/shortcut-sync.json` and existing idempotency markers. For this plan, add a single non-state review-plan comment and keep Shortcut In Progress. Report the actual task/note IDs and successful read-back; if transport fails twice, use the project outbox rather than claiming persistence.

Planning validation only: checklist/heading/JSON integrity and scoped document whitespace checks. No code fix, new software suite, database migration, browser acceptance, gameplay, build, deployment or restart is performed by this document.
