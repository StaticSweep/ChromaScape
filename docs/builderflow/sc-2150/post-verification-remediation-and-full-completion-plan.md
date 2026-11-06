# SC-2150 — Post-verification remediation and full completion plan

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69; workflow shortcut_delivery; in_progress / draft.
Shortcut: sc-2150, In Progress (500000008). Classification remains change:ui.

This is a planning amendment, not implementation or a claim of acceptance. Application code and runtime instances are not changed by writing it. It preserves prior plans, checked historical work and evidence. Newly planned items are unchecked. This amendment governs remaining work after the latest acceptance run; earlier architecture/security requirements still apply.

## Authoritative context and rules

Read repository AGENTS.md and the shared delivery contract before execution. Linked Felix rule: rule_1792384572818_kvbix29u. Retain the existing task; do not create a second task for shortcut:sc-2150.

Existing project-relative plans are docs\builderflow\sc-2150\full-plan.md, review-remediation-and-completion-plan.md and completion-plan-after-independent-review.md. Felix notes note_1792422547746_2a3a30c8, note_1792450504659_743eeeee and note_1792451949613_d39dbc8a preserve prior planning/implementation. Latest Verification Evidence: note_1792454122243_386e89be, backed by docs\builderflow\sc-2150\verification-runs\20261019T232216Z.

The pending outbox was scanned during planning: twenty conflict records concern other stories; no SC-2150 pending record was found. Preserve those conflicts. Recheck before later Felix writes. Never manually author files in felix-notes. Read .felix\shortcut-sync.json before any future external transition.

## Baseline and honest completion state

| Area | Existing evidence | Remaining gate |
|---|---|---|
| Video automated baseline | 123 Java tests / 22 suites; zero skips/failures; 15 real PostgreSQL cases using postgres:16; JS 6/6, Python 2/2; Spotless pass; Checkstyle zero errors | Reproduce after fixes; new tests increase counts |
| Named baseline gates | Nine containment, nine PostgreSQL service race, six heartbeat and 22 derivative/timebase tests | These do not establish all resource, registration, sweep, restart or multi-instance guarantees |
| Real isolated HTTP instance | Port 8190, sole local-development profile, disposable chromascape_sc2150_verify DB, /tmp/sc2150-media; now stopped | Browser evidence still required; retain existing 8090 instance untouched |
| Successful HTTP behavior | Upload/retry/conflict, nonce, bad UUID, quarantine, exact CFR index, ORIGINAL Range, append-only reviews, immutable export versions, trainingEligible=false | Preserve and rerun affected flows |
| Confirmed defects | F1 derivative 422/list omissions; F2 cancellation 500; F3 multipart 500; F4 public absolute path; F5 native uncertainty-copy regression | Fix all five and verify through affected public surfaces |
| Native regression | 204 selected Java tests, one failure; JS 26/26 pass | Zero failures required |
| Cursor | Availability false; tracker implementation and approved templates/held-out corpus absent | Implementation AND qualification required |
| YouTube | No approved downloader pin/SHA or approved clip | Operator prerequisites, adapter compatibility and acquisition qualification required |
| Authentication | One local owner exercised | Anonymous and second-owner checks are NOT RUN; isolated test identities can exercise them |
| Browser | No retained real-browser trace/screenshots | Full feature/browser acceptance remains unverified |

Frozen revision: 5a7c30f8369806be6d6a400fd1fd59a45a32b5ec on codex/sc-1991-client-replay-ai, with dirty/untracked user work preserved. Eleven previous hashes matched, but they do not freeze every newly implicated class or template. Expand the manifest before changes. Prior baseline used Adoptium 17.0.20.1+1; verification used Corretto 17.0.20.1+10-LTS. Record that difference.

Reconcile report wording: invalid-timebase parameter cases total twelve, not thirteen; replacementWinningTheLockRefusesStaleWorkerReconciliation belongs to the PostgreSQL race suite. A VFR fixture being prepared is not a completed qualification. The cancellation stack and raw leaking-job response were not retained; capture them on reproduction.

## Scope and exclusions

Deliver F1–F5, real HTTP/browser acceptance, missing review controls, cursor implementation/qualification, approved YouTube qualification, required resource/lifecycle/security guarantees and operator handoff. Fix defects discovered by these gates within this feature.

Do not change native training admission (SC-712), native tick alignment (SC-2151) or movement detection (SC-309). Do not infer physical clicks, game targets, native ticks or training eligibility from visual cursor coordinates. Do not weaken tests, accuracy thresholds, ownership or containment to achieve a green report. Preserve unrelated changes and immutable source/review/export history.

No database migration is expected for the five immediate fixes. If later implementation needs schema changes, document the actual constraint, use a forward migration and test upgrades; do not rewrite historical migrations. No production enablement occurs merely because local checks pass.

## Ordered execution and dependencies

### P0 — Freeze and reproduce

Create a new UTC evidence directory under docs\builderflow\sc-2150\verification-runs. Record revision, branch, dirty/untracked file inventory and SHA-256 for every relevant source/template/script/config file, not just the old eleven. Record Java, PostgreSQL, FFmpeg, Python, browser and downloader identities and selected test commands.

Reproduce F1–F5 on an isolated instance with explicit port/database/media configuration. Retain HTTP headers, body and status separately, database artifact/job snapshots and correlated server stacks. Keep secrets and raw private process diagnostics out of public evidence. Do not reset, clean, stash or overwrite unrelated work.

### P1 — F1: one analysis-consistent artifact set

Keep ORIGINAL source-scoped. For derivatives, choose an explicit owner/source-bound COMPLETE analysis, ordered by completedAt descending, createdAt descending and stable ID tie-break. Validate correspondence to the immutable original identity. Use the same chosen analysis for catalog listing, proxy, thumbnail, frame index and player state.

Add an optional analysisId selector to derivative media requests so playback can stay pinned to the inspected analysis. Return the selected analysis identity in source detail and artifact metadata. Validate selector ownership, source binding and completion. Never choose each role independently or silently fall back to an older analysis when the chosen analysis lacks that role. Preserve the existing typed unavailable response (422); foreign/nonexistent identities remain nondisclosing 404. Existing ORIGINAL URLs and immutable export references retain their meaning.

Test two completed analyses, pending/failed analyses, missing roles and foreign/wrong-source selectors. Merely removing IS NULL is insufficient.

### P2 — F2: truthful concurrent cancellation

Reproduce the actual controller/worker race and retain the stack before choosing the narrow repair. The suspected request-bound persistence-context interaction is not yet a proven root cause.

Read/settle cancellation from fresh locked database state at a defined transaction boundary. If a transaction fails, any recovery runs outside that failed transaction and must obtain fresh state. Do not reread a stale managed entity or broadly swallow optimistic-lock exceptions. Preserve generation, cancellation revision, lease owner and source-owner checks.

Contract: settled CANCELLED returns 200; cancellation/cleanup still pending returns truthful 202; genuine stale revision returns 409. Unconfirmed containment must remain visibly incomplete/reconciling. A worker settling first must not cause HTTP 500. Avoid an unreviewed application-wide persistence configuration change. Exercise the HTTP request persistence context with barrier-controlled PostgreSQL races, not sleeps.

### P3 — F3/F4: typed input errors and safe diagnostics

Introduce typed multipart input failures at the parser boundary. Malformed, missing, unknown, duplicate or truncated metadata maps to 400 INVALID_REQUEST; actual configured size overflow maps to 413 MEDIA_LIMIT. Genuine storage/read infrastructure failure remains sanitized 5xx. Do not classify all IOExceptions as client errors or inspect exception message substrings.

Release reservations, remove staging and avoid publishing partial graph/artifacts on every rejection. Verify chunked input and low injected limits with small fixtures.

Define stable, allowlisted bounded public messages separately from private process/probe diagnostics. Carry a diagnostic correlation reference for operators. Apply the public policy consistently to job responses, source details and exportable representations. Preserve useful raw diagnostics only in restricted server-side storage/logs. Test path styles beyond /tmp and hostile stderr; a single path regex is not the policy.

### P4 — F5: native inspector semantics

Restore “Missing targets stay unknown” and its intended behavior in mouse-recording.html. Preserve unknown target/coordinate distinctions in rendering and navigation. Repair the underlying regression without deleting or weakening its assertion. Verify the native page in a browser as well as its existing template tests.

### P5 — HTTP authentication and ownership qualification

Use real-server test fixtures with trusted server-side test principals; do not introduce production test-auth routes.

Operator check: a disposable server without the local-development bridge/principal provider must return 401 on a protected sources endpoint; public capabilities are not an auth test. For local owner B, start a separate disposable JVM with -Duser.name=sc2150-owner-b and the sole local-development profile against the same disposable DB/media. Verify A's sources, media, jobs, reviews and exports return 404, and B can own its own objects. No caller-supplied owner header substitutes for a principal.

This qualifies local bridge ownership, not a production authentication provider. Record the intended production auth acceptance separately if required for the release environment. The Fedora helper defaults to fedora-development/8090 and can redirect checkouts; explicitly verify effective runtime config. Do not assume every MOUSE_VIDEO variable is loaded from .env.local.

### P6 — Lifecycle, timing, resource and isolation gates

Preserve the existing per-execution containment, generation-bound settlement and complete-output checks. Verify crash/restart, lease expiry/replacement, reconciliation, stale completion, cancellation at each stage, artifact registration/publication, orphan sweep, disk quota and competing instances. Nine service races alone do not close this package.

Exercise CFR and VFR source timing, integer PTS/positive rational timebase, frame cap and cap+1, read/callback failure, partial output and invalid duration. Use lowered test limits rather than enormous fixtures.

Retain planned defaults: file/download 512 MiB; request 520 MiB; metadata 16 KiB; duration 20 minutes; 3840x2160; 120 fps; 150,000 decoded frames; cursor sampling at most 60 fps using exact selected PTS; one active/eight waiting in the defined admission scope; source/job disk 4 GiB; RSS 2 GiB; probe 30 seconds; acquire 10 minutes; analysis 20 minutes; shutdown five seconds. Freeze actual property names, output limits, intake/network budgets and supported-host CPU/PID settings before execution; document deviations rather than inventing values.

Verify watchdogs under silent/flooding processes, unterminated lines, stalled callbacks, saturated pipes, disk full and descendants changing sessions. Verify decoder cannot read secrets or access the network, and enforce bounded filesystem/process resources independently of output. Provider acquisition uses separate constrained egress. Linux cgroup containment does not by itself establish filesystem/network sandboxing.

Windows process-tree fallback remains unqualified. If Windows is a required release host, implement and qualify supported OS containment; otherwise document explicit release scope and keep unsupported readiness false. Do not silently drop a required host.

### P7 — Real browser and missing review controls

Implement missing correction/source-use controls in the video workbench: exact frame/point/event selection, reason, base revision, append-only history and separate source declaration/use decisions. Preserve unsaved corrections on 409 and offer an explicit reload/reapply flow. Provide keyboard access and usable narrow layouts.

Pin player/proxy/index to one analysis and show readiness, failure, cancellation, unknown cursor and immutable export versions truthfully. Test upload, validation, playback/seeking, ranges, cancellation, recovery, correction/review conflicts and export/download from a real browser against the actual backend. Existing static browser fixtures are useful regressions but do not qualify backend integration. Retain traces, screenshots, browser/version and persisted-state checks.

### P8 — Cursor implementation and qualification

Implement an explicitly versioned bounded tracker (proposed cursor/v1) through the owned runner. Pin script/runtime/tool/template identities, map results to exact source PTS, record hotspot/scale/confidence/provenance and represent absence/ambiguity explicitly. Do not accumulate unbounded extracted frames or interpolate hidden positions.

Obtain approved licensed templates and annotated clips. Split whole source videos before tuning. Keep held-out sources untouched during tuning. Synthetic fixtures establish correctness but cannot substitute for the approved held-out corpus.

Frozen acceptance: median error <=4 source pixels at 1080p equivalent; p95 <=12; visible-frame recall >=90%; absent-span false detections <=1%. Document coordinate scaling, sample counts, source diversity, coverage, uncertainty and failure breakdown. Repeated identical inputs/tool/config/template identities must reproduce normalized output hashes. Keep cursor availability false until implementation and qualification both pass.

### P9 — YouTube prerequisites and qualification

Obtain operator-approved yt-dlp executable/runtime/dependency pins and SHA-256 values plus an approved public clip and its provenance. Verify actual CLI compatibility before enabling the adapter; do not assume arbitrary latest versions accept the existing flags. No runtime downloading/updating tools.

Qualify URL/DNS/IP/redirect policy against private/loopback/link-local and unsafe protocols, constrained acquisition egress, download quotas, selected format, final media hash, timeout, cancellation, process ownership and cleanup. Decode acquired bytes without network/secrets access. No cookies, authenticated content or auth bypass. Use adapter fixtures for refusal/error branches and the approved live clip for end-to-end evidence. Keep YouTube off until both pass. Missing approved inputs are explicit blockers to this gate, not successful acceptance.

### P10 — Regression, operations and handoff

Rerun all affected automated tests, the complete native selection and real browser workflows after changes. Publish operator steps for pins, capabilities, host requirements, private diagnostics, cleanup/recovery and enable/disable. Rehearse rollback on disposable data.

Only after implementation evidence exists and all implementation items are checked, satisfy the real in_progress -> in_review gate. Only after acceptance/tests pass, satisfy in_review -> done. Current draft/spec alignment must be resolved through real Felix validation before any required transition; do not claim a planning gate succeeded here. Never use waivers or skip_validation.

## Conditional design artifacts

### Architecture and data relationships

~~~mermaid
flowchart LR
  Browser --> API[Owner-scoped HTTP API]
  API --> DB[(PostgreSQL revisions and leases)]
  API --> Intake[Bounded intake]
  Intake --> Runner[Owned restricted process runner]
  Runner --> Probe[Probe and exact frame index]
  Probe --> Derivative[Analysis-scoped derivatives and cursor]
  Derivative --> Bytes[Immutable artifact bytes]
  API --> Review[Append-only review and export revisions]
  Review --> Bytes
~~~

Existing relationships: Source has analyses and source-scoped ORIGINAL; Analysis has derivative artifact refs and analysis jobs; reviews and immutable exports retain their exact source/analysis/revision references. Catalog resolution selects one completed analysis. No new cleanup table or migration is assumed. Confirm actual entity constraints during P0 and document any necessary forward migration.

### API contract amendment

Preserve existing ownership and nonce protections. For GET /api/debug/mouse-videos/sources/{sourceId}/media/{role}, propose optional analysisId for derivative roles; validate it before resolving bytes. Source responses identify selected analysis and each artifact's analysis. ORIGINAL remains source-scoped. Preserve byte ranges (200/206/416), content type, safe disposition and immutable export behavior.

Cancel semantics: 200 settled, 202 genuinely pending, 409 stale revision, 404 inaccessible. Upload: typed 400/413 versus sanitized infrastructure 5xx. Error envelopes retain stable codes and bounded public messages; private process output is not part of the public schema. Record exact DTO/OpenAPI changes and compatibility tests before implementation.

### UX, security and observability decisions

Flow: intake -> processing -> analysis-consistent playback -> frame/cursor inspection -> correction with reason/revision -> history -> immutable export. Unknown remains visible. Conflict retains the user's unsaved work. Source permission declaration is distinct from training admission.

Trust boundaries: untrusted bytes/provider URLs, restricted worker, private diagnostics, owner-scoped database/API and public browser. Validate boundaries independently of process cleanup. No secret-bearing logs or raw absolute paths in API/export/browser.

Record correlated stage transitions, durations, cancellation/cleanup outcomes, output completeness, limits and capability failures. Avoid owner/source/job IDs as metric labels; use bounded stage/result labels and private correlation logs. Hash tool/config/template/media identities into reproducibility metadata.

## Acceptance criteria

- [ ] PA1 — Given multiple analyses, when media or source details are requested, then one owner/source-bound completed analysis supplies the derivative set without cross-analysis fallback; ORIGINAL remains source-scoped.
- [ ] PA2 — Given concurrent controller and worker cancellation, when either settles first, then HTTP is truthful and no optimistic-lock 500, stale-generation mutation or false cleanup success occurs.
- [ ] PA3 — Given malformed multipart input or configured byte overflow, when intake rejects it, then typed 400 or 413 is returned and reservations/staging are released; genuine storage failure remains a sanitized 5xx.
- [ ] PA4 — Given process or decoder failure, when any public job/source/export representation is read, then stable bounded messages disclose no internal path or private diagnostics.
- [ ] PA5 — Given unknown native targets, when the inspector renders, then uncertainty remains explicit and the native template regression passes.
- [ ] PA6 — Given anonymous and independent local owners, when protected objects are accessed, then anonymous access is 401 and cross-owner source/media/job/review/export access is 404 without disclosure.
- [ ] PA7 — Given limits, interruption, expiry, process escape, restart or competing instances, when work terminates or recovers, then ownership, containment, bounded resources and artifact publication remain correct.
- [ ] PA8 — Given CFR/VFR input and frame caps, when indexing or deriving media, then exact PTS/timebase and complete-output checks prevent partial or invalid results from being published.
- [ ] PA9 — Given a real browser and backend, when upload, playback, cancellation, review, correction, conflict and export flows execute, then the visible state and persisted revision/analysis agree.
- [ ] PA10 — Given cursor-absent or ambiguous frames, when tracking and review run, then unknown remains explicit and no physical clicks, native ticks or training eligibility are inferred.
- [ ] PA11 — Given approved held-out source-disjoint clips, when cursor qualification runs, then median error is at most 4 equivalent 1080p source pixels, p95 at most 12, visible recall at least 90% and absent-span false detections at most 1%.
- [ ] PA12 — Given identical media, tools, configuration and templates, when analysis repeats, then normalized outputs and identities are deterministic; changed inputs create distinct identities.
- [ ] PA13 — Given approved downloader pins and a public clip, when YouTube acquisition succeeds or fails, then provenance, quotas, constrained egress, cancellation and cleanup are verified without cookies or authentication bypass.
- [ ] PA14 — Given all required implementation, acceptance and test evidence, when release is considered, then required gates have no unresolved failures or blockers and unsupported hosts/capabilities are not advertised.

## Implementation checklist

- [ ] PI0 — Freeze the expanded baseline, reconcile prior evidence and retain a reproducible isolated test configuration.
- [ ] PI1 — Resolve source listing and derivative media from one explicitly selected completed analysis; preserve source-scoped ORIGINAL and immutable exports.
- [ ] PI2 — Reproduce and fix concurrent cancellation using fresh locked state across transaction boundaries; preserve execution ownership and truthful cleanup state.
- [ ] PI3 — Introduce typed multipart validation failures and preserve storage failures as sanitized server errors with complete reservation/staging cleanup.
- [ ] PI4 — Separate bounded public failure messages from private decoder/process diagnostics across all public representations.
- [ ] PI5 — Restore native inspector uncertainty semantics and repair the failing native regression without weakening its intent.
- [ ] PI6 — Add real HTTP and PostgreSQL coverage for the defects, anonymous access and independent owners.
- [ ] PI7 — Complete lifecycle, resource, isolation, admission, recovery and artifact integrity verification; fix failures found.
- [ ] PI8 — Implement browser review/correction and source-use controls with revision conflicts, history, accessibility and analysis-consistent playback.
- [ ] PI9 — Implement bounded cursor tracking with exact source timing, pinned tools and provenance-bearing templates.
- [ ] PI10 — Qualify cursor accuracy and determinism against approved source-disjoint held-out clips.
- [ ] PI11 — Pin and qualify the approved YouTube downloader, constrained acquisition and approved public-clip flow.
- [ ] PI12 — Complete real-browser and native regression runs with retained evidence and truthful capability/readiness states.
- [ ] PI13 — Complete operator documentation, rollback rehearsal and Felix/Shortcut handoff only after all applicable gates pass.

## Test checklist

- [ ] PT1 — Reproduce the frozen Java, JS, Python and style baselines; retain exact selection, toolchain, reports and expanded source hashes.
- [ ] PT2 — Verify derivative selection and listing with two completed analyses, incomplete/failed analyses, wrong-source/owner analysis IDs and missing roles through real HTTP.
- [ ] PT3 — Exercise barrier-controlled real-PostgreSQL controller/worker cancellation races through the actual HTTP persistence-context path.
- [ ] PT4 — Exercise multipart missing/unknown/duplicate/malformed/truncated fields, chunked size overflow and genuine disk failure; inspect reservation and staging cleanup.
- [ ] PT5 — Assert safe public failures across jobs, source details, reviews and exports; retain private correlated diagnostics separately.
- [ ] PT6 — Verify anonymous 401 and independent owner 404 behavior with embedded HTTP fixtures and isolated JVM operator checks.
- [ ] PT7 — Verify CFR/VFR, invalid timing, read/callback failure and frame cap/cap+1 using small fixtures and lowered test limits.
- [ ] PT8 — Verify cancellation, crash/restart, expiry/replacement/reconciliation, stale registry completion, sweep and concurrent-instance admission.
- [ ] PT9 — Verify process containment, output/disk/memory/CPU/PID budgets and decoder secret/network isolation on each intended release host.
- [ ] PT10 — Run actual-browser upload/media/review/correction/export flows, conflict preservation, keyboard and narrow-viewport checks with traces/screenshots.
- [ ] PT11 — Evaluate approved held-out cursor clips against all frozen thresholds and reproduce output hashes without tuning against held-out labels.
- [ ] PT12 — Verify approved pinned YouTube success and refusal/failure corpus, redirects/address checks, limits, timeout, cancellation and cleanup.
- [ ] PT13 — Rerun the complete native regression selection, expanded video suites, JS/Python, Spotless and Checkstyle; retain all reports and explain skips.
- [ ] PT14 — Rehearse rollback/feature-disable and complete evidence reconciliation, implementation handoff and verification handoff.

## Validation execution and evidence

Use the repository's supported JDK 17 and Gradle test selections recorded in the existing phase logs. First reproduce those selections, then add the new HTTP/PG/browser suites; do not require old exact counts after adding tests. Docker must be reachable from the Gradle JVM and container starts must appear in retained evidence. Skips do not satisfy PostgreSQL gates.

Run existing video suite, JS/Python selections, spotlessCheck and Checkstyle using the retained commands; rerun native selection that previously had 204 Java tests and the JS selection previously 26 tests. Record actual commands, counts, skip reasons and report paths. New tests named in implementation work are proposals until created; never present an invented command as already executed.

Each new run retains: full source/tool/config/input manifest; commands and exit codes; JUnit/HTML/style reports; separate HTTP headers/body/status; sanitized public responses; private correlated failure diagnostics; DB state for race outcomes; exact frame/cursor outputs; browser traces/screenshots; tool pins and clip/template provenance; explicit PASS/FAIL/NOT RUN/MISSING/BLOCKED matrix. Keep raw failed-job evidence for F4.

Acceptance requires all applicable gates to pass without waived failures, missing features or prerequisite blockers. Run code/static checks only after implementation; writing this plan does not rerun them.

## Expected changed files during implementation

Within C:\Dev\IdeaProjects\ChromaScape, inspect and change only as required:
- src\main\java\...\MouseVideoCatalogService, artifact/analysis repositories and media/source DTO/controller contracts for F1.
- MouseVideoController, MouseVideoJobService/repository and transaction-boundary code for F2.
- MouseVideoMultipartReader, intake/controller exception mapping and reservation cleanup for F3.
- MouseVideoProbeWorker, MouseVideoImportService and public error/DTO/export boundaries for F4.
- src\main\resources\templates\mouse-recording.html for F5.
- Video workbench template/JS/CSS, versioned cursor worker/config/templates and approved acquisition adapter/config for feature completion.
- Focused catalog/controller/multipart/HTTP/PG tests, existing native tests and new real-backend browser/cursor/provider qualification fixtures.
- docs\builderflow\sc-2150 plans, run evidence and operator documentation.

The Java package locations above are descriptions, not literal paths containing ellipses; resolve actual files before edits. No implementation file is authorized to be reported changed merely because listed here.

## Rollback, remaining risks and external inputs

Disable affected intake/provider/cursor capabilities on failure and stop new work; use owned cleanup and reconciliation for running executions. Preserve immutable bytes, reviews and exports. Revert only this feature's isolated changes from a recorded patch/commit, never a dirty-tree reset. Rehearse backwards compatibility and any forward migration strategy on disposable data.

External inputs still needed: approved cursor templates/held-out labels, approved downloader/runtime pins and public clip, and intended release-host scope. A permanently stuck user callback cannot be killed inside the JVM; results must remain incomplete and readiness must reflect any unmet cleanup requirement. Database unavailability intentionally loses ownership fail-closed. Local bridge tests do not establish production authentication.

Core fixes and local ownership/browser preparation can proceed while external inputs are pending. No promise of “no more code changes” is valid: five fixes, cursor tracking and browser correction controls require implementation.

## Felix and Shortcut reconciliation

This plan adds unchecked PI/PA/PT amendments to the existing canonical implementation/acceptance/test checklists and links a documentation note to the existing task. Preserve every earlier entry, checked state, note and entity link.

No state transition is part of this planning operation. Felix remains in_progress / draft; Shortcut remains In Progress. No synchronization marker is emitted.

Future Implementation Evidence must include ## Changed files, ## Tests, ## Remaining work. Future Verification Evidence must include ## Acceptance results, ## Tests, ## Risks, ## Rollback. Only after successful Felix transitions, use the reviewed .felix\shortcut-sync.json mapping and check for the existing exact marker before posting: [felix-sync:task_1792382767281_cd8dbu69:planned], [felix-sync:task_1792382767281_cd8dbu69:started], [felix-sync:task_1792382767281_cd8dbu69:review] or [felix-sync:task_1792382767281_cd8dbu69:done]. No marker may precede its actual transition. Blocked/cancelled mappings are null; report rather than invent states.

If transport fails twice, use the shared pending-outbox protocol with full payload/evidence; successful validation errors are not outages. Never report a failed note/task write or sync as successful.

