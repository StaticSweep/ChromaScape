# SC-2150 — Fedora implementation and qualification plan

Date: 2026-10-20.
Canonical project: C:\Dev\IdeaProjects\ChromaScape.
Felix: task_1792382767281_cd8dbu69, workflow shortcut_delivery, in_progress / draft.
Shortcut: sc-2150, In Progress (500000008). Classification remains change:ui.

This is a planning-only amendment for remaining Fedora work. It does not start implementation, change application code, enable features or claim a workflow gate passed. All new FI/FA/FT items are unchecked. Existing implementation, checklist states and evidence remain intact.

## 1. Authority, baseline and scope

Read the shared delivery contract, repository AGENTS.md, docs\CHROMASCAPE_RESTART_RUNBOOK.md and .felix\shortcut-sync.json. Linked Felix rule: rule_1792384572818_kvbix29u. Reuse this task; never create another task for shortcut:sc-2150.

Preserve docs\builderflow\sc-2150\full-plan.md and post-verification-remediation-and-full-completion-plan.md. This document elaborates the remaining implementation and qualification packages; it does not erase earlier acceptance requirements.

Completed review fixes are the starting baseline:
- F1–F5 and RV1–RV3 have recorded corrections.
- Latest run: 24 suites / 153 Java tests, no failures/errors/skips; real PostgreSQL 16 suites included.
- Five retained probes passed, including retained-context cancellation and persisted null-code failures.
- Changed-file Spotless and Checkstyle passed; repository-wide Spotless failed on five unrelated aiplayer files. Do not describe the repository as fully green.
- Latest evidence: note_1792480680506_36117475 and docs\builderflow\sc-2150\verification-runs\20261020T071154Z-rv3-fix.
- These results are previous executions, not tests run while writing this plan.

Current missing work includes browser correction/source-use controls, cursor tracking and the story's additional visual observations, not merely more tests. The story explicitly includes visible action indicators, pauses, target/camera/activity observations and scene cuts. Implement a declared bounded set of supported OSRS viewport/cursor cases; unsupported cases remain explicit.

Ownership boundaries: integrate with SC-303 catalog/inspection, SC-305 quality admission and SC-712 published curation/confirmation. Do not implement their broader products here. SC-309 owns movement findings and SC-2151 exact tick alignment. No live input, automatic model activation, causal simulation, reconstructed physical telemetry or automatic training admission.

All development and Linux qualification run natively on Fedora. Windows containment remains a separate unresolved host gate. Fedora readiness does not authorize advertising Windows support or silently removing a required Windows acceptance criterion.

The SC-2150 pending outbox was scanned before planning; no matching pending record was found. Preserve conflict records for other stories. Scan again before future Felix writes and use the shared outage protocol if transport fails twice.

## 2. Deliverables and dependency order

| Package | Deliverable | Dependencies | Can start without approved external clips? |
|---|---|---|---|
| W0 | Frozen baseline and isolated Fedora harness | Existing checkout/toolchain | Yes |
| W1 | Versioned contracts, bounded read APIs and exact-frame preview design | W0 | Yes |
| W2 | Validated append-only review backend and browser controls | W1 | Yes |
| W3 | Restricted worker launcher and enforced budgets | W0; integrates with W1 | Yes, host provisioning may be needed |
| W4 | Cursor and other bounded visual observation workers | W1, W3 | Yes, using synthetic fixtures |
| W5 | Durable analysis/publication, quality and export integration | W2, W4 | Yes |
| W6 | Ownership, real browser and lifecycle/resource qualification | Relevant W1–W5 paths | Yes |
| W7 | Approved held-out cursor/observation qualification | W4–W6 plus corpus/templates | No |
| W8 | Approved YouTube acquisition qualification | W3 plus downloader pins/clip/egress | Fixtures can start now; live gate needs inputs |
| W9 | Final regressions, operations and evidence handoff | All required gates | Documentation can start now |

Execute W0 -> W1 -> W2 for the first reviewable user flow. Establish W3 before running untrusted new workers. Build W4/W5 with synthetic fixtures while approved-input preparation proceeds. Complete W6 independently of external clip approvals. W7/W8 cannot be passed with missing prerequisites. No sub-agent delegation is assumed.

## 3. W0 — Freeze and isolate the Fedora harness

Record Git revision/branch, tracked and untracked dirty files, SHA-256 for affected source/templates/scripts/config, tool versions and binary hashes, configuration, input bytes and report selectors. Include new files as implementation grows. Never reset, clean, stash or overwrite unrelated changes.

Use separate Gradle build output and project cache, following the retained isolated init-script approach. Shared build outputs previously caused missing-class failures during concurrent builds. Store new run evidence under docs\builderflow\sc-2150\verification-runs\<UTC-run-id>.

Use a disposable PostgreSQL database and media root; the previous 8190 setup is a reference, not assumed still running. Allocate free loopback ports for owner A, owner B and anonymous instances; 8190–8192 are candidates only after checking availability. Leave the existing 8090 service untouched. Retain runtime logs and a cleanup ownership manifest.

Add a repeatable real-backend harness with the production video controllers/services/repositories/templates. Disable unrelated external pollers in the test configuration; do not replace acceptance APIs with static fixture responses. Verify the actual effective profile, URL, database and storage before loading clips.

The native Fedora restart helper defaults to fedora-development/8090 and may select another checkout. Its .env.local loader does not import every video setting. Use explicit disposable-instance configuration and the restart runbook; do not blindly invoke the default launcher for this harness.

Reproduce the 153-test and five-probe baseline with Docker reachable from the Gradle JVM. If required by the local Docker version, use the proven process-local API compatibility override; do not persist a global workaround. Classify each result as PASS, FAIL, NOT RUN, MISSING or BLOCKED.

Exit: reproducible harness, baseline reports, source/tool manifest and a tested cleanup procedure.

## 4. W1 — Contracts, read APIs and exact-frame inspection

Create versioned observation/correction JSON schemas and typed server validation. Existing correction JSON is currently permissive; frontend controls must not become the only validator.

Observation records include sourceId, analysisId, original/frame-index digests, method/config/tool/template identities, frameOrdinal, integer source PTS, positive rational timebase, visibility, confidence, evidence kind and explicit unknown reason. Transport large PTS/rational integers as decimal strings; browser Number conversion is for display only. Preserve legitimate source timestamp semantics and actual frame membership.

Coordinates use display-oriented source pixels. Record rotation/crop/resize transforms and inverse mapping to decoded pixels. Proxy/video-element coordinates are not source pixels. Never claim an HTML video seek selected an exact source frame unless verified.

Proposed additive endpoints beneath /api/debug/mouse-videos:
- GET /sources/{sourceId}/analyses/{analysisId}/frame-index: bounded pages pinned to the frozen index.
- GET /sources/{sourceId}/analyses/{analysisId}/observations: bounded cursor/time/kind filtering with a stable artifact identity.
- POST /sources/{sourceId}/analyses: explicit idempotent analysis job creation; no work from GET.
- POST /sources/{sourceId}/analyses/{analysisId}/frame-previews: idempotent bounded exact-source-frame preview work.
- GET /frame-previews/{previewId} and GET /frame-previews/{previewId}/media: metadata and already-published bytes only.
- Existing reviews, exports, media and job routes retain their contracts and analysis pinning.

Validate owner, source/analysis binding, completion and original digest before reads. Use the current maxObservationPageSize (500) as an upper page cap, plus an encoded-byte response bound. Opaque cursors include immutable artifact identity and position; reject a cursor for another analysis. GET never spawns FFmpeg or creates an export.

Exact previews are immutable cached results keyed by analysis/index digest + frame ordinal + preview method/config. Create them through the same durable queue, runner, leases and quotas; coalesce repeats. Add a forward schema change for preview metadata and job kind only after checking the next free migration number. Protect preview byte references from sweeping. Keep source/analysis/frame uniqueness, owner access and publication fencing. While loading, show the selected frame as pending; do not overlay its coordinates on an older image.

Preserve media 200/206/416 behavior, safe MIME/CSP/disposition headers, typed 400/401/403/404/409/413 failures and bounded public diagnostics, including null-coded legacy failures.

Exit: contract tests, real HTTP read/create separation, correct ownership/paging and exact-frame identity.

## 5. W2 — Review backend and browser controls

Backend:
1. Validate a versioned correction array: supported operation/kind, finite/ranged coordinates, referenced observation/frame membership, actual PTS/index identity, visibility, reason and bounded payload/count. Reject unknown fields/types and invented native/physical input fields.
2. Lock the source revision, validate analysis binding and compare expectedBaseRevision. Keep identical request retries stable; the existing requestId shortcut must also detect changed bodies.
3. Store a canonical request digest for new review writes using a forward additive migration. Historical rows retain their content; compare a reconstructible stored semantic projection for retries, otherwise return an explicit conflict requiring a new request ID. Never fabricate a historical digest.
4. Keep old revision content immutable. Derive superseded presentation from successor lineage instead of saving changes into a predecessor's content/state. Preserve existing historical rows and immutable exports.
5. Return revision number, predecessor, analysis, actor, reason and contract version. Compute effective reviewed observations separately from original predictions.

Browser:
- Frame stepping, exact-frame preview, source-pixel coordinate selection and overlays pinned to one analysis.
- Add/correct a visible point or observation, mark unknown, select a reason and save against the loaded base revision.
- Show original prediction and reviewed result separately, with append-only history and explicit revision selection.
- On 409 keep the complete unsaved draft; offer reload/reapply with a new request ID after the user reviews the changed base. A transport retry reuses its original request ID.
- Separate declared HUMAN/GENERATED/UNKNOWN origin, recorded provenance and source-use review. “Review recorded” must not mean verified human input.
- Offer INSPECTION_ONLY, TRAINING_REQUESTED and TRAINING_DENIED. TRAINING_REQUESTED is a request for downstream policy review, never permission to train. Keep trainingEligible/nativeTelemetry/exactTicks/control flags false for video-derived observations.
- Export the selected analysis and review revision. Existing export bytes/hashes remain stable after later corrections.
- Provide keyboard selection/controls, clear focus/error status, screen-reader labels, narrow-screen layout and safe text rendering.

Exit: reviewer can select an exact frame, save a correction, resolve a conflict without losing work, inspect history and export the intended immutable revision.

## 6. W3 — Restricted Fedora launcher and resources

systemd-run and bwrap are installed on this host; installation is not qualification. Verify the active user session, cgroup v2 controllers/delegation, user namespaces and SELinux compatibility.

Keep the existing per-execution systemd containment. Compose it with a fixed server-owned restricted launcher:
- Read-only approved executable/runtime/script/template/input mounts.
- Private writable scratch/output only, sanitized environment, no home/application secrets or inherited credentials.
- Decoder/analysis network namespace with no egress. Acquisition has a separate constrained policy.
- Bound cgroup memory/CPU/PIDs, wall clock, stdout/stderr, filesystem writes and process cleanup. Fail readiness closed when mandatory enforcement is unavailable.
- Proposed additional defaults to qualify: CPUQuota 200% and TasksMax 64 per execution; explicitly bound worker/decoder threads. MemoryMax enforces a 2 GiB group budget rather than an unenforced RSS observation. Document any host-specific adjustment in the configuration contract before qualification.
- Use a pre-provisioned quota-enforced per-job filesystem/work volume for the 4 GiB disk budget. Application counters alone do not constrain an untrusted child. Provisioning is a host prerequisite; do not remount disks or change privileged host policy during ordinary application development.
- Check actual effective limits in the cgroup/filesystem and attack them from child processes. Merely accepting command-line properties is not evidence.

Retain existing agreed caps: upload/download 512 MiB; request 520 MiB; metadata 16 KiB; duration 20 minutes; 3840x2160; 120 fps; indexed/decoded frames 150,000; cursor sampling <=60 fps at exact selected source PTS; one active/eight waiting research jobs in the defined cross-instance admission scope; probe 30 seconds; acquisition 10 minutes; analysis 20 minutes; child shutdown five seconds. Freeze request-read/network-idle/total-output limits in configuration too.

Audit quota/admission locking across instances and document one lock order before extending it. Job heartbeat/progress/finalization retain their generation/lease fences and PostgreSQL clock. Use small injected limits to test cap and cap+1.

Exit: secret/network/process escape attempts and independent resource exhaustion stay bounded; incomplete cleanup/output never publishes complete analysis.

## 7. W4 — Cursor and other visible observations

Use a fixed versioned Python visual worker, invoked only through the restricted owned runner. Pin its interpreter/dependencies/script and approved template manifest; no runtime package downloads or arbitrary uploaded executable templates. Freeze dependencies before tool qualification rather than choosing an unrecorded “latest” environment.

Cursor implementation:
- Deterministic bounded template matching over a declared cursor pack and finite scale/hotspot set, with a documented score and ambiguity rule.
- Stream exact indexed source frames; cap selected processing at 60 fps and record sampling gaps. Do not accumulate an unbounded extracted-frame directory.
- Emit VISIBLE, HIDDEN, AMBIGUOUS or UNSUPPORTED with confidence and reason; no guessed coordinate for unknown frames.
- Reset continuity at cuts. Never interpolate hidden positions. Crop/resize/rotation transforms are explicit inputs or qualified estimates with confidence; unknown transforms remain unsupported.
- Hash template contents/provenance, algorithm, sampling, config and toolchain into analysis identity.

Implement the story's additional observations for declared supported cases:
- Pauses: contiguous visible low-motion intervals in source video time; an initial configurable rule is <=1 equivalent 1080p pixel movement over >=250 ms. Do not bridge cuts/unknown spans.
- Scene cuts: bounded frame-difference/histogram detector; link both boundary frames and reset tracker state.
- Visible action indicators: reviewed menu/highlight/cursor-state changes only, labelled candidates, never physical button presses.
- Target regions: visible highlighted/template regions with source-pixel bounds; unknown game entity/world coordinates stay unknown.
- Camera/activity: qualified global viewport-motion and regional visual-change candidates, excluding known overlays/cursor where possible. Do not claim semantic gameplay activity without supported evidence.
- Speed/crop/overlay handling: preserve source presentation time and document declared/unknown playback-speed changes. No inferred physical velocity, polling rate or native tick alignment.

Synthetic golden fixtures must include cursor absence, ambiguity, occlusion, scaling, compression, overlays, cuts, VFR, rotation/crop, moving background and changed playback speed. Synthetic success enables development, not held-out qualification.

Exit: deterministic versioned artifacts with source-frame evidence and explicit coverage/unknowns for every method.

## 8. W5 — Durable integration, schema and exports

Add analysis scheduling and exact-preview work to existing jobs/registry/heartbeat/finalization rather than independent background threads. Persist request identities before dispatch; duplicate bodies reuse the job, changed bodies conflict. Include algorithm/template digests in config identity so different packs cannot deduplicate accidentally.

Analysis output includes immutable OBSERVATIONS, QUALITY and manifest references with complete timing/method/config/provenance. Preserve source-scoped ORIGINAL and one completed analysis for each derivative set. Publication remains atomic and generation-bound; failed/partial writes are not FINALIZED.

Quality records describe coverage, ambiguity, measured visual error and limitations. They do not grant SC-305/SC-712 admission or activate SC-309 movement diagnosis. Exports pin analysis, review revision, original/index/template/tool/config hashes and uncertainty flags. GET lists/downloads create no work.

Planned schema changes are additive: review request digest/version and exact-preview job/cache/reference metadata. Confirm migration numbering, actual constraints and retention/GC before coding. Test fresh migration and upgrade from existing source/analysis/review/export data. Do not rewrite V42/V43 or historical revisions/exports. Artifacts remain in the byte store; avoid storing frame arrays in JPA rows.

Readiness distinguishes configured, installed, runnable, isolated and qualified states. Replace unconditional capability claims only when the corresponding gate exists. Cursor/provider flags remain off until required qualification passes; installed bwrap or a downloader path alone is insufficient.

Exit: retry/cancel/restart cannot publish stale work, change prior bytes or misreport a missing capability.

## 9. W6 — Ownership, browser, lifecycle and recovery

Ownership:
- Embedded real-server tests with trusted test principals and real services/database.
- Operator anonymous instance without the local bridge/principal provider: protected sources endpoint returns 401.
- Owner B disposable JVM with -Duser.name=sc2150-owner-b and the sole local-development profile, same disposable DB/media: A's source/media/job/analysis/preview/review/export returns 404; B can own its own data.
- No caller owner headers, production test-auth routes or mixed-profile assumptions. This qualifies local bridge scoping, not an untested production auth provider.

Browser:
Create a separate real-backend Playwright configuration and video spec. Existing playwright.config.cjs starts a static fixture server; preserve it for its own regressions and do not use it as feature integration proof. Point the new config at the disposable Spring instance, retain traces/screenshots, and record browser version.
Exercise upload/validation/retry, proxy playback/seeking and Range, exact preview selection, pending/failure/reconciliation states, cancellation, recovery, corrections/history/conflicts/source use, immutable export download, keyboard and narrow viewport. Check persisted revisions and byte hashes alongside the UI.

Lifecycle matrix:
Cancel at acquisition/probe/index/cursor/preview/publication stages; crash before/after byte publication and DB registration; expire/replace leases; recover interrupted reservations; test two instances competing for admission and publication. Verify healthy leases survive restart reconciliation and stale workers cannot settle replacements.
Verify sweeper grace, shared hashes, referenced original/analysis/preview/export bytes and abandoned staging. No orphan cleanup may delete bytes protected by current references. Retain no-late-callback, partial-drain, invalid-timebase and null-coded legacy regressions.

Exit: real HTTP/browser evidence plus DB/filesystem/process outcomes, not a summary of unit-test totals.

## 10. W7 — Approved corpus and frozen qualification

External inputs: approved licensed cursor templates, approved annotated source clips, source/use rights and annotation provenance. Include frame/PTS visibility, hotspot coordinates, regions/events/cuts/transforms and supported-case labels. Split by whole source video before tuning; derivatives of one source stay in one split. Held-out clips must never be used to tune thresholds.

Frozen existing cursor gates, at 1080p-equivalent source-pixel scale:
- Median position error <=4 pixels.
- p95 <=12 pixels.
- Visible-frame recall >=99% (owner-raised from 90% on 2026-10-23; see fa12-gate-amendment-20261023.md).
- False detections on cursor-absent spans <=1%.

Report counts, covered minutes, per-source results, occlusion/scaling/cut/compression/VFR breakdowns, confidence intervals and uncertainty. A zero-detection tracker cannot pass by omitting recall. Evaluate on the required labelled source frames so sampling gaps are visible.

Additional planned initial gates, frozen before viewing held-out results:
- Cuts: F1 >=0.90 within one indexed source-frame tolerance.
- Advertised visual-action/target-region/camera/activity candidate methods: precision >=0.95 and recall >=0.80 on their declared supported cases.
- Pause start/end timing: p95 within two selected-frame intervals on labelled supported sequences.
- Golden unsupported/unknown cases: no fabricated physical/native observations; deterministic expected unknown states.

These additional detector targets are new planned criteria, not achieved measurements. If a detector cannot meet its declared gate, record the failure and improve it or explicitly revise the supported-case specification through the durable plan; do not hide the detector or retune on held-out labels while claiming the original gate passed.

Repeat identical media/config/tools/templates and compare normalized output hashes. Different toolchains/configurations get distinct identities; no promise of bitwise equality across unqualified environments.

Exit: all required methods have approved, source-disjoint evaluation evidence. Missing corpus/rights remains an explicit blocker.

## 11. W8 — Approved YouTube acquisition

Obtain approved downloader executable/runtime/dependency pins and SHA-256 values, plus an approved public clip and source/use provenance. Check exact CLI/version compatibility: the current adapter's flags must be tested, not assumed accepted by arbitrary yt-dlp versions.

Separate acquisition from no-network decoding. Enforce egress restrictions with a qualified namespace/proxy/firewall path that the downloader cannot bypass; URL string checks alone do not stop DNS rebinding or downloader subrequests. Require public address resolution, bounded redirects, approved HTTPS/protocol behavior and private/loopback/link-local/metadata refusal. Operator egress provisioning is a prerequisite if absent.

Verify single-video selection, no playlist expansion, no cookies/plugins/auth bypass, no runtime tool updates, bounded downloads/time/disk/output, cancellation and descendant cleanup. Retain canonical URL/provider ID, acquisition time, selected format, pin/config identity and final media digest. Re-run source/codec/quarantine policy on acquired bytes.

Use adapter fixtures for unavailable/private/removed/auth-required/rate-limited/oversized/redirect/timeout/failure cases. Use the approved live clip for actual acquisition -> probe -> analysis -> browser/export evidence. Keep YouTube off until both fixture and live gates pass.

## 12. W9 — Final regression, operations and delivery

Run expanded Java video/HTTP/PostgreSQL and full native selections; five retained review probes; full npm test; Python worker/qualification tests; static and real-backend browser suites; Spotless and Checkstyle. Record actual counts and skips. New implementation increases counts; 153 is a starting baseline, not the required final total.

Preserve unrelated aiplayer work. Record global formatting failures separately from changed-file checks; do not silently waive a required repository check. Coordinate their resolution or obtain a truthful repository gate result before claiming full readiness.

Publish Fedora operator procedures for preflight, pins/approved packs, capability failures, source permissions, job/lease inspection, private diagnostics, reconciliation/sweep, retention, enable/disable and rollout. Rehearse disabling new intake, owned cleanup, restart/recovery and rollback on disposable data. Reverse only isolated changes; never reset the dirty checkout or rewrite immutable media/revisions/exports. Forward migrations need compatible rollout/rollback behavior.

Implementation Evidence requires ## Changed files, ## Tests, ## Remaining work. Verification Evidence requires ## Acceptance results, ## Tests, ## Risks, ## Rollback. Persist evidence before checking completion items. Resolve draft/spec validation honestly before transitions; no skip_validation or waivers.

No transition is part of this planning action. Later synchronize Shortcut only after a successful Felix transition and after checking the exact marker:
[felix-sync:task_1792382767281_cd8dbu69:planned]
[felix-sync:task_1792382767281_cd8dbu69:started]
[felix-sync:task_1792382767281_cd8dbu69:review]
[felix-sync:task_1792382767281_cd8dbu69:done]
Use .felix\shortcut-sync.json, never invented state IDs. Blocked/cancelled mappings are null. Windows qualification stays unresolved until its required host work is actually done.

## 13. Conditional design artifacts

~~~mermaid
flowchart LR
  UI[Browser frame review] --> API[Owner-scoped versioned API]
  API --> DB[(Sources jobs reviews previews)]
  API --> Queue[Durable admission and leases]
  Queue --> Runner[systemd scope and restricted launcher]
  Runner --> Frames[Exact frame and visual workers]
  Frames --> Store[Immutable byte artifacts]
  DB --> Export[Pinned review and analysis export]
  Store --> Export
  Provider[Approved constrained acquisition] --> Runner
~~~

Relationships: Source -> Analysis -> artifact references; Source -> immutable ReviewRevision lineage; Analysis + frameOrdinal/index identity -> immutable preview reference; Job -> execution generation/lease; Export -> fixed source/analysis/review identities. Additive migrations must preserve those ownership/reference constraints.

UX sequence: import -> durable processing -> pinned analysis playback -> exact frame preview -> visible/unknown observation -> correction/reason/source-use -> conflict-safe append-only revision -> pinned export.

Security decisions: owner scoping before work/bytes, trusted principals, nonce protection, fixed worker commands, approved immutable pins, no decoder secrets/egress, constrained acquisition and real quotas. Observability uses bounded stage/result labels, correlation IDs and private diagnostics; do not use source/owner/job IDs as unbounded metric labels.

Expected existing implementation surfaces: MouseVideoController, ReviewService/revision entity/repository, CatalogService, JobService/job entity/repository, ImportService, DerivativeWorker, ProcessRunner, QuotaService, RegistrationService, FinalizationService, OrphanSweeper, ExportService, Properties and capability reporting; mouse-video template/JS/CSS and video/native regressions.

Proposed additions (not existing files): typed observation/correction contracts, cursor/observation worker, Fedora restricted-launcher configuration, exact-preview service/entity/repository, analysis scheduler, forward migration(s), real-backend Playwright config/video spec, approved-fixture manifests and operator docs. Confirm actual package names before creating files; do not claim these proposals already exist.

## 14. Given/When/Then acceptance checklist

- [ ] FA1 — Given a Fedora verification run, when started, then isolated ports/database/storage/build outputs and a complete source/tool/input manifest make results attributable without altering the existing 8090 service.
- [ ] FA2 — Given frame-index/observation reads, when paged or analysis-pinned, then only bounded owner/source/analysis-bound immutable data is returned and GET creates no work.
- [ ] FA3 — Given a review retry, concurrent revision or invalid correction, when submitted, then identical requests are idempotent, changed bodies/stale bases conflict, and invalid frame/type/coordinate data is rejected without changing original predictions or old revision content.
- [ ] FA4 — Given browser review, when corrections/source-use decisions are saved or conflict, then exact frame/analysis lineage is retained, unsaved work survives, and training eligibility stays false until the separate admission policy authorizes it.
- [ ] FA5 — Given a restricted Fedora worker, when it attempts process escape, secret reads, network access or resource exhaustion, then enforced boundaries hold and publication/cleanup states remain truthful.
- [ ] FA6 — Given supported visible/hidden/ambiguous cursor frames, when tracked, then exact source PTS, transform, hotspot, visibility and confidence are recorded without interpolation over unknown spans or cuts.
- [ ] FA7 — Given declared supported observation cases, when analyzed, then visible action indicators, pauses, target-region/camera/activity estimates and cuts have source-frame evidence; unsupported semantics, physical inputs and native ticks remain unknown.
- [ ] FA8 — Given identical media, method/config/tool/template identities, when analysis repeats, then normalized results match; changed identities create distinct analyses and never mix derivative sets.
- [ ] FA9 — Given anonymous and independent owners, when protected HTTP objects are requested, then anonymous access is 401 and cross-owner access is nondisclosing 404 across source/media/job/analysis/review/export.
- [ ] FA10 — Given a real browser/backend, when upload/playback/cancel/recovery/review/export and keyboard/narrow-screen flows run, then browser state, persisted revisions, bytes and selected analysis agree.
- [ ] FA11 — Given crashes, expiry, competing workers, sweep or configured caps, when the lifecycle resolves, then leases fence stale work, admission is atomic, referenced bytes survive and incomplete artifacts are never published as complete.
- [ ] FA12 — Given approved source-disjoint held-out clips, when evaluated once against frozen methods, then cursor median error is at most 4 equivalent 1080p pixels, p95 at most 12, visible recall at least 99% (owner-raised from 90% on 2026-10-23) and absent-span false detections at most 1%, with the additional detector gates met.
- [ ] FA13 — Given approved downloader pins/public clip and constrained egress, when YouTube acquisition succeeds or fails, then provenance, stable outcomes, limits, cancellation and cleanup are verified with no cookies/authentication bypass.
- [ ] FA14 — Given all required evidence, when delivery is considered, then no required gate is waived, unsupported hosts/capabilities remain unavailable, rollback is rehearsed and tracker transitions reflect actual validation.

## 15. Implementation checklist

- [ ] FI0 — Freeze the Fedora baseline and run manifest; establish isolated build, database/media, browser and trusted-identity test harnesses.
- [ ] FI1 — Define versioned observation, correction and capability contracts, stable request identities and bounded owner-scoped read/analysis APIs.
- [ ] FI2 — Implement validated append-only correction/review behavior, changed-body retry conflicts and immutable revision lineage.
- [ ] FI3 — Implement browser frame inspection, correction/source-use controls, conflict recovery, history and analysis-consistent export.
- [ ] FI4 — Implement and qualify the Fedora restricted worker launcher, enforced cgroup budgets, workspace quotas and capability/readiness failures.
- [ ] FI5 — Implement versioned bounded cursor tracking with pinned tools/templates and exact source-frame/PTS mapping.
- [ ] FI6 — Implement bounded visible-action, pause, target-region, camera/activity and scene-cut observations with explicit unsupported/unknown states.
- [ ] FI7 — Integrate analysis scheduling, identity/deduplication, fenced publication and immutable observation/quality/export artifacts.
- [ ] FI8 — Verify anonymous and independent-owner HTTP behavior without introducing production test authentication.
- [ ] FI9 — Implement the real-backend browser suite and lifecycle/resource/recovery qualification matrix; fix observed failures.
- [ ] FI10 — Obtain approved corpus/template provenance, freeze detector thresholds and complete held-out accuracy/determinism qualification.
- [ ] FI11 — Verify approved YouTube pins/CLI compatibility, constrained acquisition egress, live clip provenance and failure/cleanup behavior.
- [ ] FI12 — Complete Fedora/native regressions, operator documentation, recovery and rollback rehearsal; reconcile repository-wide check failures honestly.
- [ ] FI13 — Persist final evidence and reconcile Felix/Shortcut only after actual validation gates; retain Windows qualification as a separate unresolved host requirement.

## 16. Test checklist

- [ ] FT0 — Reproduce the 153-test video/native-page baseline and five retained probes with real PostgreSQL, isolated outputs and zero unexplained skips.
- [ ] FT1 — Contract-test frame/observation paging, integer PTS transport, analysis/digest/owner binding, stable cursors and bounded JSON responses.
- [ ] FT2 — Test correction schema, finite/ranged coordinates, actual frame membership, request-body identity, stale-base conflicts and immutable revision/export history using real PostgreSQL races.
- [ ] FT3 — Test browser review/source-use workflows, unsaved conflict recovery, keyboard access, narrow viewports and analysis-consistent overlays against the real backend.
- [ ] FT4 — Test Fedora launcher filesystem/network/process isolation and configured memory/CPU/PID/disk/time/output limits with independent child attempts.
- [ ] FT5 — Test cursor exact timing, scale/hotspot transforms, absence/ambiguity/occlusion, cuts, output failure/caps and deterministic synthetic results.
- [ ] FT6 — Test visual-action/pause/target-region/camera/activity/cut detectors against golden synthetic cases and pre-registered held-out gates.
- [ ] FT7 — Test fenced analysis publication, duplicate/retry identity, cancellation at every stage, crash/restart, expiry/replacement/reconciliation and stale registry completion.
- [ ] FT8 — Test anonymous 401 and owner-B 404 through embedded real-server fixtures and isolated operator JVMs.
- [ ] FT9 — Test quotas/admission across instances, upload/download/frame cap plus one, malformed/hostile media, incomplete drain and referenced/orphan sweep behavior.
- [ ] FT10 — Evaluate approved held-out cursor/other-observation corpus with source-disjoint splits, timing/visibility labels, error distributions, coverage and reproducibility hashes.
- [ ] FT11 — Verify pinned YouTube argv/version compatibility, approved live acquisition, private-address/redirect/DNS-rebinding refusals, network restrictions and cleanup.
- [ ] FT12 — Run full native/JS/Python/browser regressions, changed-file and global style checks; retain every failure/skip and reconcile unrelated failures without overwriting user work.
- [ ] FT13 — Rehearse disable/restart/recovery/rollback and verify feature readiness and Felix/Shortcut handoff evidence.

## 17. External inputs, risks and stop conditions

External inputs: approved template/corpus rights and labels; approved downloader pins/public clip; any required quota/cgroup/egress host provisioning; intended release-host scope. Development with synthetic fixtures and fixture downloader responses can proceed while these are pending.

Risks: unqualified user namespaces/SELinux/delegation, pixel ambiguity/domain shift, imperfect browser seeking, migration/review compatibility, provider/CDN changes, and unrelated concurrent builds/formatting. Each has a named work package and observable fail-closed readiness state.

Do not enable cursor/provider capabilities, claim full browser/security readiness, transition to done or erase a required Windows gate because prerequisites are missing. This plan is complete as a plan; implementation and qualification remain unchecked.

