# SC-274 — full Windows capture candidate restoration, 31 October 2026

The Windows diagnostic service has been replaced with the full compiler/provenance
candidate, using a separate owned database. This restores the machinery needed to
continue SC-274; it does not close the story or manufacture missing capture provenance.

## Implemented plan

1. Preserve the logged-in TrumpSniper client, diagnostic database and original media.
2. Carry the tested Windows callback, native mapping and target-cache fixes into the
   candidate without replacing its calibrated recorder implementation.
3. Bound queued image ownership by both count and retained raster bytes. Keep actual
   capture-time slotting and the finalized measured-video index.
4. Wire lightweight catalog eligibility to its real caller, retaining shared refusal rules.
5. Provision an owned PostgreSQL instance; apply migrations, initialize legacy-only
   tables, and restart with Hibernate validation. Stage on loopback 8183 first.
6. Copy the original recording's exact legacy rows through a read-only source transaction,
   compare hashes, verify staging, then replace only the identified Windows 8090 JVM.
7. Verify real viewport delivery and a bounded debugger-API recording, then retain all
   acceptance blockers whose prerequisites are absent.

## Changed files

- `WindowHandler.java`: one retained native enumeration callback with nested thread-local
  dispatch, eliminating per-enumeration JNA callback allocation.
- `WindowsGdiScreenCapture.java` and new `AutoTargetCache.java`: synchronized short-lived
  auto-target cache, process/window/selection identity checks, explicit-selection bypass,
  and W32APIOptions mapping for the real Windows native calls.
- `RecordingService.java` and new `BoundedFrameBuffer.java`: atomic count/byte admission,
  192 MiB retained-raster budget, oldest-first eviction, interruptible encoder polling,
  queue-byte metrics and throttled repetitive logging. Subimages account for their
  backing buffers. The candidate's MeasuredVideoIndex and capture-slot deduplication remain.
- `TickSnapshotRepository.java`, `RecordingIntegrityService.java`, `ReplayCatalogService.java`:
  metadata projection plus initial-snapshot hydration for catalog checks; full preflight
  and compiler evidence reads remain intact. The catalog caller now uses the lighter path.
- Regression tests: AutoTargetCacheTest, CachedCanvasIdentityTest,
  WindowHandlerEnumerationTest, BoundedFrameBufferTest, RecordingCatalogIntegrityTest,
  and RecordingServiceRoundTripTest.

Runtime/evidence scripts are outside the repository under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\full-candidate`.
They include start-owned-candidate.ps1, preserve-recording.py, check-owned-candidate.py,
probe-viewport.py and live-recording-check.py. Credentials live in an ACL-checked private
directory, outside ordinary evidence and Git. Existing registry credentials were copied
into that protected store unchanged; no shared credential was rotated.

## Acceptance results

- Staged full service on Windows 8183, then switched Windows 8090 after ownership and
  inactive-recording checks. The logged-in client was not restarted.
- All 70 Flyway migrations applied to the new owned database. An initial strict startup
  exposed a missing legacy `decisions` table: migrations do not create every historical
  JPA table. One explicit owned-only Hibernate update initialized these; subsequent
  startups used `validate`. This is not released-lineage upgrade proof.
- Owned database: `sc274_capture`, container `sc274-full-capture-postgres-20261031`,
  loopback port 15474, labeled volume retained. Shared databases are unchanged.
- Original `recording_770ef399` retained: one recording row, one linked debug-session row,
  2,783 tick rows from the read-only transfer snapshot, no action rows. Original column
  values hash-identically in the target. Later live ingestion uses a different session.
- Its original MP4 remains SHA-256
  `874c93d6767852f2d9462f51a1f42bbce40524fbc66e98c01bfdc0c721852f35`.
  Existing media drift of 11.407667 seconds is historical evidence, not silently repaired.
- Legacy provenance remains null and eligibility returns `RUN_CONTEXT_REQUIRED`.
- The loopback local-development principal bridge intentionally permits provenance reads;
  the initial probe incorrectly expected 401. That failed expectation is preserved in
  candidate-checks-8183-initial-expectation.json. Protected stabilization still returns 401.
- Both staged and 8090 viewport probes delivered 15 complete binary-protocol JPEG frames;
  the staged image was visually inspected as the actual logged-in game.
- Bounded smoke recording `recording_185d98ea` used the debugger start/stop API with the
  connected client: finalized, 379 physical frames, 13.366667 s media, no timing quality
  flags, zero dropped frames, queue high-water mark one. Approximately 0.56 CPU core and
  959 MiB peak resident memory were observed in this short small-canvas run. It is genuine
  live diagnostic evidence with no run context or authoritative-source acceptance claim.
- Replay worker consumption and automatic recording remain disabled. No game login,
  gameplay input, WSL shutdown or shared-container restart was performed.
- Final Windows JVM PID 92492 listens on 8090. Its ignored external launch metadata is
  `full-candidate\runtime-8090\launch.json`. The earlier diagnostic and staging JVMs stopped.
- The existing registry credential was missing from the new launcher initially, producing
  `registry_http_401`. After restoring that same credential through the protected store and
  restarting only the owned JVM, discovery returns an authenticated empty executor list
  without an error payload. No registry configuration or grant was changed.

## Tests

- Gradle, JDK 17, offline: 58 capture/window/catalog tests, zero failures/errors/skips.
  Includes real MP4 round trips, timing-burst regression, buffer concurrency/admission,
  native Windows enumeration/mapping, cache identity and catalog refusal parity/wiring.
- Gradle replay compiler/manifest selection: 129 tests, zero failures/errors/skips,
  including fresh-JVM compiler determinism and published curation contracts.
- Scoped Google Java Format and `git diff --check` pass. Repo-wide formatting was not run.
- Staging HTTP/database preservation checks: 12/12 after correcting the documented local
  principal expectation. 8090 preservation/HTTP checks likewise passed after cutover.
- Actual start, tick acknowledgement, recording, stop, MP4 index finalization and original
  media-hash preservation passed on Windows. Evidence includes JSON, XML and console logs.
- Final 8090 database/HTTP/registry checks: 15/15; final viewport: 15 valid frames. The
  evidence/log credential scan found zero secret-value matches in 38 then-existing files.

## Remaining work

SC-274 remains incomplete. The existing authenticated runtime registry contains no
allocated runtime. The current client is source-disabled; the approved original-trust
bundle, supported allocation identity, real saved profile/run context and approved first
provider-origin bootstrap input remain absent. Bootstrap is disabled with no operator key.
Do not assign invented IDs, relabel authored fixtures or retrofit an unverified context.

Once those prerequisites exist: capture through the full supported context-bound route,
finalize and publish actual curation, compile twice from frozen inputs, run bounded import/
privacy/refusal checks, and reconcile each original acceptance criterion and the historical
bank/travel amendment. Preserve the old recording as observation evidence meanwhile.

## Risks

- Short small-canvas CPU/memory measurements do not qualify a large-canvas soak or power-loss
  recovery. The 192 MiB limit bounds queued rasters, not every encoder/native allocation.
- Windows artifact durability is explicitly DEVELOPMENT, not POSIX STRICT qualification.
- The lightweight catalog still allocates one metadata entry per tick; it does not hydrate
  every full world snapshot. A long-catalog load test remains unrun.
- No full repository Java/native suite, released migration rehearsal, Linux isolation lane,
  genuine source-trust acceptance, Watch sign-off or story completion is claimed.
- The existing Felix task cannot be resolved; continuation notes are durable, but no task
  state transition or completion synchronization is implied.

## Rollback

Stop only the PID recorded by the owned Windows launch metadata after verifying its
command line and that no recording is active. Relaunch the preserved diagnostic build
against `chromascape_diag_step5` if needed; retain both databases, the owned volume and
all recording files. The original logged-in client remains independent. Revert the scoped
candidate changes separately; never delete evidence or replace credentials with removed
values. No shared schema rollback is needed because no shared schema changed.
