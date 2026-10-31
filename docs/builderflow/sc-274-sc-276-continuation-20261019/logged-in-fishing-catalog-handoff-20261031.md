# Logged-in Windows Fishing handoff — 31 October 2026

## Changed files

ChromaScape's Micro6CanonicalHash and Micro6CatalogV2Contract now verify the
SC-572 CanonicalCatalogModel producer alongside the legacy CatalogV2Publication
summary model. Both are recomputed from their defined fields. An arbitrary attested
hash is never accepted. The new model includes settings and uses the producer's alias
projection. Its lightweight response retains schema content, which is validated with
the full per-schema checks. Legacy lightweight requirements remain unchanged.

CatalogLiveProducerCompatibilityTest and two captured, sanitized catalog fixtures
cover the exact signed Micro6 8f5fff2d capture artifact. Runtime addresses, account name
and instance ID were removed from the fixtures; canonical hash-bearing data is intact.

The external Windows launch helpers now copy only the requested saved TrumpSniper
profile and settings into the isolated home and use the user's explicitly requested
saved legacy email/password handoff. Java expands a restricted argument file before
the main class; credentials are absent from the OS command line. Logs and handoff files
are private. No authentication dialog was automated. Free world 301 avoids the
members-world refusal observed at world 312. Automated tasks and recording stay off
until the normal manual recording API is invoked.

## Tests

Final Gradle aiPlayerCatalogTest selection: **72 tests, zero failures/errors/skips**:
CatalogServiceTest 11, live producer compatibility 5, canonical hash 11, catalog
contract 26, executor catalog service 19. Includes changed metadata/schema/aliases,
forged hash and lightweight schema-hash refusal. Touched Java files formatted using
Google Java Format. No repo-wide formatting applied.

The first attempt was preserved: it exposed the producer's schema-bearing lightweight
response and also selected CatalogPostgresTest without its required disposable JDBC
configuration. That database lane was not claimed verified; the final focused selection
does not include it.

Live before deployment: logged-in TrumpSniper identity confirmed, real fresh allocation
renewed by the Launcher client, operator-issued DEV endpoint grant active, executor
selectable, correct source artifact, recorder ready. 14/14 capture readiness checks and
two actual Linux-namespace-to-Windows bridge reads pass. The registry-proxied catalog
was then correctly refused by the old verifier; that refusal motivated this change.

Evidence: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff\
catalog-live-final-test-summary.json, catalog-live-final-junit, captured producer catalogs,
capture-readiness.json and catalog-bridge-verification.json. Initial failed attempts
remain alongside final results.

## Remaining work

Deploy to the owned Windows ChromaScape capture service only; verify authenticated
registry catalog selection; save a real Fishing profile; start a HUMAN context-bound
capture, then have the user fish. Finalize and verify real ticks, catches, XP/inventory,
source context and curation afterward. HUMAN data does not prove provider-origin or
independent oracle fidelity. No acceptance row or story is closed by setup checks.

## Risks and rollback

Compatibility covers two explicitly implemented producer projections; it does not
accept unknown hash algorithms. Source trust remains the separately authorized local
development signer. This is owned experimental capture, not production certification.
Restore the preceding immutable ChromaScape classpath snapshot to roll back; preserve
database, runtime and fence volumes. No game input is dispatched by these helpers.

## Live handoff result

Deployed the tested fix to the owned Windows 8090 capture service (PID 237596),
preserving logged-in Micro6 PID 188552 and its real renewed allocation. Authenticated
registry task detail is READY, schema AVAILABLE. Saved Fishing profile
1a67835e-3c32-4d95-94ab-46b537ff5687 version 1, target TROUT, five-resource target;
no task or gameplay action was dispatched.

The first manual start was refused with RECORDER_TICK_TIMEOUT and compensated. The
existing Micro6 transport uses its login identifier as accountName; the initial HTTP
request used the character display name, so immutable context binding differed.
The corrected normal API request uses the same saved account alias internally, after
checking its hash against the actual logged-in account. Request/raw response are kept
only in the protected private directory; public evidence retains safe IDs and outcomes.
The first failed attempt is preserved, not relabelled successful.

Normal manual start then returned 200 for recording_f370c9fb, session
efd57ea7-7663-4e35-bea2-add84a389835, run de1e464c-b9d4-4a92-a088-d92232ada335.
Selected tick schema 3 and ACK 1. A subsequent read proved lifecycle RECORDING and
committed ticks 1469–1547 with no failure code/reason. Window discovery contains the
single captureable TrumpSniper window belonging to source PID 188552; it was explicitly
selected. The user was told capture is running and to fish, then report done.

Finalization, catch/XP/inventory checks, source binding/curation and acceptance review
remain pending the user's fishing. Recording remains active intentionally. The capture
is HUMAN development evidence; no story or acceptance row was closed or pushed.

## User-requested stop and capture review — 31 October, 19:49 UTC

On arrival the video recorder was already stopped, but the debug session was still
RECORDING. The normal stop-recording API returned 200 and finalized the exact session,
including its final drain: 1,169 contiguous snapshots at ticks 1469–2637, all with
position telemetry. The API's Replayable result establishes basic tick replay only.

Measured Fishing XP rose 120999 to 121839 (+840). Inventory gained seven raw trout
(335) and seven raw salmon (331), with seven +50 and seven +70 XP transitions.
Feathers decreased by 14. These measurements agree on 14 catches; they are not
independent oracle qualification or a provider-origin claim.

The authenticated persisted-provenance API returns the original HUMAN run, exact saved
profile/version, real allocation and signed source build/artifact/snapshot identities.
It also correctly refuses eligibility with CAPTURE_INCOMPLETE. All 1,169 ticks lack
the newer capture-quality/calibration data; quality reports CAPTURE_CALIBRATION_MISSING
and CAPTURE_QUALITY_MISSING, with zero calibrated ticks. The media provenance check
reports VIDEO_INDEX_OR_MEDIA_INVALID. The MP4 exists (531,365,936 bytes), but its
.timing.json sidecar is absent; synchronized media acceptance is unverified.

The video had stopped automatically at its configured duration limit: the service log
records "Max recording duration reached, stopping" at 17:14:21 local, exactly one hour
after start. The later explicit stop repairs final recording metadata; its elapsed
duration must not be treated as a measured encoded-video duration. Raw video, ticks,
failed checks and private run context are preserved. No quality data was fabricated or
backfilled, and no story or acceptance row was closed. No product source or service
restart was needed for this stop/review.

Evidence: human-fishing-stop-result.json, human-fishing-final-check.json and
human-fishing-final-context-check.json in the Windows handoff evidence directory;
private raw ticks, quality and persisted context remain under the restricted
final-fishing-capture directory. Fix/qualify capture-quality/calibration and the timing
index finalization path before requesting another acceptance capture.
