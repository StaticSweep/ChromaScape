# SC-2150 — Implementation evidence: bounded read APIs, validated review contract and browser review

Date: 2026-10-20.
Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (not transitioned in this session — no Felix MCP tool was
available; see *Remaining work*).
Shortcut: sc-2150 (unchanged).
Package: W1 (contracts and read APIs) plus the W2 backend/browser review slice.

This document records what was actually implemented and executed. It does not claim a Felix or
Shortcut transition, a Windows qualification, a held-out accuracy gate or an end-to-end server run.

## Changed files

Created:

- `src/main/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoCorrectionContract.java`
  — versioned `corrections/v1` contract: declared kinds only, unknown fields rejected, finite and
  bounded coordinates, frame-ordinal membership against the frozen index, count and encoded-byte
  caps, explicit rejection of invented native/physical evidence fields, and a key-sorted canonical
  digest.
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoFrameIndexService.java`
  — read-only access to the analysis-scoped FRAME_INDEX artifact; owner/source/analysis/original
  digest/completion checks, opaque cursors pinned to the artifact content address, page cap from
  `maxObservationPageSize`, encoded-byte page bound, integer PTS and rational timebase as decimal
  strings, bounded parse cache (4 entries) keyed by checksum.
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoObservationsService.java`
  — read-only OBSERVATIONS access with cursor/time/kind filtering, artifact-pinned cursors, page and
  byte bounds, and an honest `available: false` + `unknowns` response when no observation artifact
  is published.
- `src/main/resources/db/migration/V44__sc2150_review_request_identity.sql` — additive
  `request_digest` and `contract_version` columns with historical rows left at
  `corrections/v0` and a NULL digest, plus a predecessor index. V42 and V43 are untouched.
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoCorrectionContractTest.java`
  (17 tests).
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoFrameIndexServiceTest.java`
  (10 tests, real content-addressed byte store).
- This evidence document.

Modified:

- `.../video/service/MouseVideoReviewService.java` — validates corrections before any write; stores
  a canonical request digest bound to analysis, base revision, reason and source use; a reused
  request identifier is idempotent only for an identical body and conflicting for a changed one;
  historical rows are compared through their stored semantic projection; predecessors are no longer
  mutated (`supersede()` is no longer called from this path) and superseded state is derived from
  successor lineage. Historical rows written before digests existed retain their content.
- `.../video/entity/MouseVideoReviewRevision.java` — `requestDigest` and `contractVersion` fields.
- `.../video/controller/MouseVideoController.java` — new
  `GET /sources/{sourceId}/analyses/{analysisId}/frame-index` and
  `GET /sources/{sourceId}/analyses/{analysisId}/observations` routes; both are read-only and
  owner-scoped.
- `.../video/service/MouseVideoCatalogService.java` — capability report names the new contracts and
  keeps `exactFramePreview: false` and `cursorTrackingAvailable: false`.
- `src/main/resources/static/js/mouse-video.js` — frozen-frame table and stepping (buttons plus
  Arrow/Home/End), analysis-pinned frame selection, approximate proxy seek that is labelled as
  approximate, local correction draft with server-mirrored duplicate/count rules, source-use
  decision, append-only history with lineage display, conflict banner that preserves the whole
  draft, and export pinned to the selected analysis and current review revision.
- `src/main/resources/templates/mouse-video.html` — the review section with accessible labels,
  captions and live regions.
- `src/main/resources/static/css/mouse-research.css` — review layout, conflict styling and a narrow
  screen breakpoint.
- `src/test/java/.../video/service/MouseVideoReviewServiceTest.java` (9 tests),
  `src/test/java/.../video/controller/MouseVideoControllerHttpTest.java` (14 tests),
  `src/test/java/.../video/MouseVideoMigrationTest.java` (5 tests, now targets V44),
  `src/test/js/mouse-video.test.js` (335 JS tests repository-wide).

Note: this checkout still has the whole SC-2150 video package untracked; `git status` reports it as
`??`. No commit was created.

## Tests

Commands actually executed on Fedora, this host:

```
./gradlew cleanTest
DOCKER_API_VERSION=1.44 JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
  ./gradlew test -Psc967VectorsPath=<contracts/sc967/ed25519-vectors.json> \
  --tests 'com.chromascape.web.debug.tickdebugger.video.**'
npm test
./gradlew spotlessCheck
./gradlew checkstyleMain checkstyleTest
```

Results:

- Video package: **187 tests, 0 failures, 0 errors, 0 skipped**. This includes 16 real-PostgreSQL
  Testcontainers tests (migration, admission concurrency, service races); the Docker API
  compatibility override was supplied process-locally via `DOCKER_API_VERSION` /
  `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` and is not persisted in the repository or globally.
- `MouseVideoMigrationTest` now migrates through V44 and asserts that a historical review row keeps
  `contract_version = 'corrections/v0'` with a NULL digest while a new row persists its digest.
- JavaScript: `npm test` → **335 tests, 335 pass, 0 fail, 0 skipped**, including seven new
  frame/correction-draft tests.
- `node --check src/main/resources/static/js/mouse-video.js` → valid.
- Spotless: my changed and new Java files pass; the repository check still fails on two unrelated
  pre-existing files (`aiplayer/contract/CurrentSliceIdentity.java`,
  `aiplayer/routine/service/CandidateExecutionClosureV1.java`). The repository is therefore not
  fully green and is not described as such.
- Checkstyle: `checkstyleMain` and `checkstyleTest` succeed (warnings only).

Not run in this session:

- The full repository Java suite (only the video package was executed).
- A booted application instance and real HTTP/browser exercise of the new routes; the controller
  routes and status codes are covered through MockMvc with mocked services, and the frame-index and
  observation services are covered against a real byte store, but no running server was driven.
- Static and real-backend Playwright suites.

## Remaining work

Unchanged by this increment (all still required by the plan):

1. **Exact-frame previews (W1 remainder).** `POST .../frame-previews`, `GET /frame-previews/{id}`
   and `.../media`, a durable preview job kind, preview metadata schema change, coalescing,
   sweeper protection and "pending frame" browser state are not implemented. Coordinates for a
   visible cursor point, target region and camera region stay unavailable in the browser and are
   refused rather than guessed.
2. **Observations producer (W4).** No visual worker publishes OBSERVATIONS yet, so the read API
   reports absence. Cursor tracking, pauses, cuts, action indicators, target regions and
   camera/activity detectors, their pinned Python toolchain and synthetic golden fixtures are not
   implemented.
3. **Restricted Fedora launcher (W3).** cgroup budgets beyond the existing containment, bwrap
   composition, per-job quota volume, readiness fail-closed states and the attack tests are not
   implemented.
4. **Durable analysis scheduling (W5).** `POST /sources/{id}/analyses`, identity/deduplication for
   analysis and preview work, quality artifacts and export changes beyond the existing contract.
5. **Ownership, browser and lifecycle qualification (W6).** Anonymous 401 and second-owner 404 with
   separate JVMs, real-backend Playwright video spec, cancellation/crash/restart/expiry/sweep matrix.
6. **Held-out qualification (W7) and approved YouTube acquisition (W8)** remain blocked on approved
   templates, annotated clips, downloader pins and egress provisioning.
7. **Tracker handoff.** Felix and Shortcut were not updated: this session had no Felix or Shortcut
   MCP tool available, so no transition, note or marker is claimed. The task remains
   `in_progress / draft` as last recorded, and the plan's FI/FA/FT items stay unchecked except
   where this document records actual evidence.
8. **Windows qualification** remains a separate unresolved host requirement.
