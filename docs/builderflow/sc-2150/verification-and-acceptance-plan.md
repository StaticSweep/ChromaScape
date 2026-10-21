# SC-2150 Verification and acceptance execution plan

Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Existing Felix task: `task_1792382767281_cd8dbu69` (`shortcut_delivery`, `change:ui`).
Snapshot at planning: Felix `in_progress / draft`; Shortcut SC-2150 In Progress (`500000008`).

This is a planning-only request. No application code, tests, runtime configuration, deployment or feature flags are changed by writing this plan. No test is rerun and no acceptance gate is newly passed in this planning session.

## Goal and scope

Verify the current implementation first, then establish exactly which SC-2150 acceptance items pass, fail, lack implementation, or lack prerequisites. Cover the repaired process/lease/frame-output boundaries and the remaining upload, inspection, media mapping, correction/export, cursor, provider, browser and native regression obligations. Preserve the original scope and all previous P5–P10 and Q acceptance obligations.

The four latest review findings are corrected in code and have prior passing evidence. That does not establish that the whole feature needs no more code. Start execution without application source changes. A reproduced defect or demonstrably missing required workflow becomes a separately described remediation item; an unavailable clip, pin, identity or host capability is a prerequisite blocker. Do not silently modify code to make a verification run pass.

Excluded: automatic production enablement, deployment/restart of an existing instance, changes to Micro6, live gameplay, physical-click or native-tick inference, movement detector verdicts, and training/model admission. Windows process containment is a separate unqualified platform gate; Fedora results cannot qualify it.

## Rules and authoritative references

- `C:\Dev\IdeaProjects\AGENTS.md` and `C:\Dev\IdeaProjects\ChromaScape\AGENTS.md`.
- Existing Felix task-linked project rules; existing Architecture, ERD, API Contract, Security, UX and Observability notes remain authoritative. This plan changes no product architecture, schema or API contract.
- Completion plan `note_1792422547746_2a3a30c8` and `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\completion-plan-after-independent-review.md`.
- Latest implementation evidence `note_1792450504659_743eeeee` and `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\implementation-evidence-remaining-review-corrections.md`.
- Baseline manifest: `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\remaining-review-verification.json`.
- API/operator reference: `C:\Dev\IdeaProjects\ChromaScape\docs\mouse-video-import.md`.
- Host operations: `C:\Dev\IdeaProjects\ChromaScape\docs\CHROMASCAPE_RESTART_RUNBOOK.md`.
- Shortcut mapping: `C:\Dev\IdeaProjects\ChromaScape\.felix\shortcut-sync.json`.

Prior verified baseline: JDK 17.0.20.1+1, 123 Java tests passed including 15 PostgreSQL tests, zero skips/failures; JavaScript 6/6, Python 2/2; Spotless and Checkstyle passed with warning-level output. These are historical results, not new runs from this plan.

The pending outbox was scanned oldest-first before this plan was persisted. Its 20 records are pre-existing conflicts for other stories; none belongs to SC-2150. Preserve those conflicts and all historical evidence.

## Result and evidence rules

For each case record exactly one state:

| State | Meaning and next action |
|---|---|
| PASS | The stated result was observed against the recorded code, inputs and environment; retain evidence. |
| FAIL | The case ran and violated its expected result; retain a reproducer and propose the smallest scoped repair. |
| MISSING | A required feature or test boundary is absent; identify the missing implementation or harness. |
| BLOCKED | Required authentication, approved input, binary pin, environment or host capability is unavailable; name it precisely. |
| NOT RUN | Execution has not occurred. |

A skipped or mocked database test is not a passing PostgreSQL gate. A static browser fixture is not proof of a real authenticated browser-to-backend workflow. An installed executable is not qualification of its outputs. Do not infer safety, training eligibility or human provenance from a successful import.

Retain a separate evidence directory for each UTC run under `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\verification-runs\<UTC-run-id>\`. Create it only when execution starts. Record code revision plus hashes of changed/untracked source files, host/OS, JDK and tool identities/hashes, configuration with secrets redacted, clip/template hashes, actual commands, counts/skips/failures, HTTP results, job/source/generation IDs, process cleanup observations, relevant committed database rows and exported manifest hashes. Label generated clips synthetic. Preserve the baseline manifest rather than overwriting it.

## Given/When/Then acceptance criteria

All criteria are unexecuted in this planning session; previous results remain historical evidence.

- [ ] VA1 Given a frozen checkout and disposable database/storage, when the candidate instance starts, then its effective port, identity, profile and dependencies are recorded and the existing instance is untouched.
- [ ] VA2 Given the unchanged recorded test set and required tools, when regression commands run, then 123 Java tests including 15 PostgreSQL cases, JS 6 and Python 2 execute without skips/failures, and formatting/static checks pass with actual reports retained.
- [ ] VA3 Given valid uploaded media and identical or conflicting retries, when intake runs, then identical requests resolve one source/job and changed content conflicts without partial rows or duplicate charges.
- [ ] VA4 Given cancellation, timeout, expiry, replacement or restart, when ownership/cleanup is resolved, then stale executions cannot publish or mutate replacements, live foreign ownership survives, and uncertain cleanup remains reconciliation-required.
- [ ] VA5 Given qualified CFR/VFR media and malformed/capped output, when frames are indexed and viewed through a proxy, then correct named PTS/timebase evidence and within-one-source-frame seeking are demonstrated on qualified ranges, while partial or invalid evidence is never complete.
- [ ] VA6 Given an unauthenticated caller, foreign owner, invalid write nonce or disguised active content, when protected APIs/media are requested, then the contract's refusal and media-safety behavior occur without private-data disclosure.
- [ ] VA7 Given conflicting corrections and versioned exports, when review/export requests are repeated or new revisions are saved, then raw/history data stays immutable, conflicts are explicit and old export bytes stay identical with resolvable pinned lineage.
- [ ] VA8 Given frozen approved templates and whole-video-held-out annotations, when cursor measurements run, then declared error/recall/absent-span thresholds and coverage reporting are met without inventing hidden coordinates, physical clicks or native ticks.
- [ ] VA9 Given an approved pinned downloader and accessible approved public clip, when supported YouTube acquisition and failure/isolation cases run, then one real success retains complete provenance and unsupported or unsafe cases fail truthfully without late publication.
- [ ] VA10 Given the existing native mouse/capture/catalog/training/replay workflow, when retained automated and supported-host regression checks run, then previous behavior remains usable and imported footage does not gain training admission.
- [ ] VA11 Given retained results for every original/amended obligation, when delivery is assessed, then only evidence-backed items are checked and no review/done/rollout claim occurs while a required FAIL/MISSING/BLOCKED/NOT RUN item remains.

## Preparation and implementation decision checklist

- [ ] Preserve and fingerprint the current implementation before execution; make no source change just to begin verification.
- [ ] Resolve test-host, identity, fixture and pin prerequisites separately from product defects.
- [ ] For every FAIL or MISSING result, retain the reproducer, affected original acceptance item, current behavior, expected behavior and smallest proposed repair or missing harness.
- [ ] Treat any required source repair as an explicit remediation step with focused regression and re-verification; do not include that work in the claim that this plan was merely executed unchanged.

## Phase 0 — Freeze baseline and prepare an isolated environment

- [ ] Record the current Git revision, scoped dirty/untracked files and source hashes. Do not reset, clean or discard existing work to obtain a clean tree.
- [ ] Confirm JDK 17, Docker/Testcontainers PostgreSQL, Python, ffmpeg/ffprobe and a usable systemd user session backed by cgroup v2 on the Fedora verification host.
- [ ] Inventory each required workflow and existing test target against the authoritative story checklist. Separate absent implementation from unverified behavior; do not mark an unchecked historical item implemented solely because a related unit test exists.
- [ ] Prepare a disposable PostgreSQL database and separate media/workspace directory, a free loopback port and an authenticated test principal. Verify the effective database, storage, port and profile before uploading anything.
- [ ] Confirm owner authentication and write-nonce acquisition against the actual test instance. `fedora-development` does not by itself create a trusted video principal; the existing same-host principal bridge applies only to the sole `local-development` profile. Do not bypass authentication or mix profiles to obtain access.
- [ ] Follow the host runbook for an explicitly selected test instance. The current Fedora helper forces port 8090 and can redirect to a configured runtime checkout; verify both before using it. An isolated launch must not stop, replace or attach to the existing 8090 process. Record the effective test URL and checkout.
- [ ] Enable upload only in that isolated instance through runtime configuration when ready for manual tests. Keep YouTube off until Phase 5 prerequisites exist. Do not change the intended production instance or commit credentials/configuration.

Exit: reproducible baseline and correctly isolated/authenticated instance; unavailable prerequisites recorded as BLOCKED. No gameplay/client launch is required to qualify imported video.

## Phase 1 — Reproduce the automated regression baseline

From the native Fedora checkout of the canonical project, select the installed JDK 17 through `JAVA_HOME`, then run:

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --rerun \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  spotlessCheck checkstyleMain checkstyleTest \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain
node --test src/test/js/mouse-video.test.js
python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'
```

The Docker API override is the previously verified host workaround, not a daemon or source configuration change. If the dependency cache is unavailable, record that prerequisite; do not quietly remove required tests or substitute mock results.

- [ ] Expect the unchanged test set to contain 123 Java tests, including 15 real PostgreSQL cases, with zero skips/failures/errors; JS 6/6 and Python 2/2. Investigate any count difference against the selected code/test set.
- [ ] Inspect the HTML/XML reports rather than accepting only a successful Gradle exit. Verify the process-session/unavailability/death-assertion cases and all nine PostgreSQL service races executed.
- [ ] Verify unavailable Linux containment refuses the requested command before launch; fast-parent and session-changing children die; a deliberately surviving test process fails its death assertion before teardown.
- [ ] Verify stale reconciliation/cancellation cannot mutate replacement execution rows, healthy heartbeat/progress/finalization serialize, and startup/expiry reconciliation preserves live ownership.
- [ ] Verify invalid rational timebases, non-finite timing and fractional integer PTS never yield complete evidence; valid named timing remains intact.

Exit: baseline reproduced with retained reports, or a concrete regression/prerequisite classification. Do not reopen or edit corrected source merely to perform this phase.

## Phase 2 — Real upload, media and lifecycle acceptance

Prepare approved small clips with independently known frame counts/timestamps. Include CFR, VFR, rotation/crop/resize, scene cuts, duplicated/dropped frames, missing/unsupported cursor spans, malformed/no-video content and configured limit cases. Include cap and cap+1 cases; label synthetic fixtures.

| Case | Expected result |
|---|---|
| Small valid upload | Exactly one owner-scoped source/job, original digest/length retained, permitted media playable, proxy/thumbnail/index references resolvable. |
| Identical retry, including concurrent retry | Same source/job and request identity; no duplicate publication or quota charge. |
| Same request ID with changed bytes or metadata | Conflict; no partial source/ref/job/event/quota graph. |
| Malformed, disguised, oversized, overlong or unsupported input | Typed bounded refusal; no unsafe inline media and no complete-evidence claim. |
| Original versus proxy seeking | Known frame/time evidence matches source truth; seeking stays within one source-frame interval on qualified ranges. |
| Frame cap, truncated/read/drain failures | Partial or uncertain evidence remains explicitly incomplete or refused. Record a missing injectable test boundary as MISSING, not PASS. |
| Cancel during acquisition/probe/derivation and immediately before finalization | Exact owned execution stops; no late analysis/ref publication; CANCELLED only after cleanup proof, uncertainty stays reconciliation-required. |
| Process exit/session change and independent unrelated process | All owned containment members stop; unrelated process stays alive. |
| Long-running stage across heartbeat intervals | Lease renews independently of output; normal completion remains possible. |
| Lease expiry/replacement or database ownership outage | Stale worker loses publication rights; no replacement row corruption; uncertainty is explicit. |
| Second-instance startup and crash/restart recovery | Live foreign claims preserved; expired/uncertain jobs reconciled without assumed cleanup or duplicate publication. |
| Concurrent reservation/expiry/registration/sweep | Correct committed accounting; referenced bytes survive; no duplicate source/request winner. |

Inspect actual process liveness, bounded workspace growth, committed database rows and artifact visibility, alongside API status. Start with existing real-process/database fixtures; where a boundary is not covered, identify a missing harness or use a reproducible manual fixture without claiming automation exists.

Exit: every case has evidence or an explicit FAIL/MISSING/BLOCKED disposition. Preserve original/proxy mapping and resource-isolation gates if the tested corpus is insufficient.

## Phase 3 — Authenticated browser, review and immutable export

Use the real backend at the recorded test URL and `/mouse/video`, with two distinct authenticated owners and an unauthenticated context.

- [ ] Upload, inspect, seek, refresh, navigate catalog filters/pagination, retry failures and observe cancellation/reconciliation wording. Refresh must restore the correct source/job/request identity without resubmitting work.
- [ ] Exercise keyboard-only and narrow-screen flows, visible focus, labels, announced progress/errors and non-colour-only state. Retain screenshots plus network/status evidence.
- [ ] Verify unauthenticated calls receive 401; foreign private objects receive 404; invalid/missing write nonce and cross-origin mutations are refused. Do not accept caller owner headers as authentication.
- [ ] Verify safe inline media type, nosniff/CSP/private caching and HTTP Range 200/206/416 behavior against actual bytes, including disguised active content.
- [ ] Save point/event corrections with reviewer/reason/base-revision lineage. Reload and retrieve history. Identical request retry is stable; changed content and stale/conflicting base revisions conflict without rewriting raw predictions or saved history. Preserve unsaved browser work on conflict.
- [ ] Keep source declaration, verification, source-use request and training admission distinct. Import/review must not grant human provenance or training/control/native capabilities.
- [ ] Create an export, retain its bytes and SHA-256, then add a newer analysis/review and create a new export. The old download must stay byte-identical; each snapshot must resolve the pinned source/analysis/review/artifact lineage. A GET must not create an export or new work.

Exit: real browser-to-HTTP-to-database evidence for the complete workflow. If a control/workflow is absent, record MISSING and its required behavior; do not label it a manual-test prerequisite.

## Phase 4 — Cursor and analysis qualification

- [ ] Obtain an approved template pack and annotated owned/approved source clips with provenance, hotspot/scale definitions and hashes. Declare supported cursor/viewport cases before measurement.
- [ ] Split whole source videos before tuning; reserve held-out source videos. Include visible standard/OSRS cursors, menus/overlays, hidden/absent spans, scaling/cuts and VFR.
- [ ] Compare output to annotations on supported visible spans: median error <=4 source pixels at 1080p equivalent, p95 <=12 pixels, visible-frame recall >=90%, absent-span false detections <=1%.
- [ ] Publish sample counts, coverage, confidence intervals, per-case failures and gap/unknown reasons. Unsupported hidden positions, physical clicks and native ticks stay unavailable.
- [ ] Repeat the same media/tools/config/templates and compare normalized observations/manifests. Different toolchains retain distinct identities.

These thresholds come from the existing authoritative completion plan. Missing tracker/report functionality is MISSING; unavailable approved annotations/templates is BLOCKED. Neither permits invented measurements or relaxed thresholds.

Exit: retained held-out qualification report, or exact implementation/input blockers.

## Phase 5 — Supported pinned YouTube acquisition

- [ ] Obtain an operator-approved downloader/runtime pin and an approved accessible single-video public clip; record the expected provider identity. Keep the provider disabled if either is absent.
- [ ] In the isolated instance only, verify actual hashes, bounded acquisition, fixed flags/minimal environment, disabled ambient config/plugins/cookies/exec and the approved egress boundary. Decode remains separated from acquisition networking.
- [ ] Import successfully, inspect the resulting footage and retain canonical URL/provider ID, acquisition time, selected format, exact downloaded hash/length and tool identities.
- [ ] Test removed/private/auth-required/live/playlist/rate-limited/oversized cases, unsafe redirects/protocols/addresses and cancellation/stall outcomes; each must fail truthfully without bypass or late publication.

Exit: one genuine approved successful import plus failure/isolation evidence. A mock provider pass cannot close the live provider gate.

## Phase 6 — Native regression and evidence reconciliation

Run existing targets with JDK 17 and Docker as needed. These are planned selectors, not results from this planning session:

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --rerun \
  --tests '*MouseResearch*Test' --tests '*MouseRecording*Test' \
  --tests '*MouseCampaign*Test' --tests '*MouseTraining*Test' \
  --tests '*MouseCaptureSummaryTest' --tests '*Stabilization*Test' \
  --tests '*LocalReplayPrincipalFilterTest' --tests '*ReplayArtifactServiceReuseTest' \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain
node --test src/test/js/mouse-research.test.js src/test/js/mouse-recording.test.js src/test/js/mouse-task-recording.test.js
```

- [ ] Retain results/skips and verify selected tests actually executed. Expand coverage where the original story's capture/catalog/campaign/training/replay obligations are not represented; these selectors are not a full-repository regression claim.
- [ ] Manually confirm the existing native recording/catalog/inspection workflow remains usable on the supported host. Record separate Windows prerequisites instead of reusing Fedora evidence.
- [ ] Map every original and amended acceptance/implementation/test item to new or retained evidence, with one explicit state. Preserve existing passing evidence and unrelated task content.
- [ ] Publish Implementation Evidence with Changed files, Tests and Remaining work; publish Verification Evidence with Acceptance results, Tests, Risks and Rollback. Record no application-source changes if execution required none.
- [ ] Enter review only when the actual implementation checklist and Felix `implementation_complete` bundle pass. Enter done only when all acceptance/test obligations and validation pass. No skip_validation, waivers or fabricated evidence.
- [ ] Synchronize Shortcut only after an actual corresponding Felix transition succeeds, using the repository mapping and checking existing comments for the exact idempotency marker. A plan write alone causes no state transition or marker.

Exit: an evidence-backed disposition for the whole story. If any required gate is FAIL/MISSING/BLOCKED, keep completion open and report the remaining item and next action; do not silently shrink scope.

## Execution checklist summary

- [ ] VP0 Freeze baseline, inventory functionality and prepare isolated authenticated prerequisites.
- [ ] VP1 Reproduce the 123-test baseline, PostgreSQL execution and static checks without application changes.
- [ ] VP2 Verify upload/media/lifecycle/resource and remaining database boundaries with retained evidence.
- [ ] VP3 Verify real browser, ownership, append-only review and immutable export flows.
- [ ] VP4 Measure cursor/quality/coverage/repeatability against frozen held-out criteria.
- [ ] VP5 Qualify pinned approved live YouTube acquisition and provider failures/isolation.
- [ ] VP6 Run native regressions and reconcile full acceptance, Felix gates and conditional Shortcut synchronization.

All execution items above are initially NOT RUN in this plan. Historical passes are retained separately. Documentation preparation is complete; execution and any later scoped remediation are not performed by this request.

## Stop conditions, risks and rollback

Stop the affected verification phase if isolation/authentication is incorrect, process cleanup cannot be confirmed, a referenced object is deleted, ownership leaks, or late publication occurs. Retain the failing state/evidence and identify the responsible acceptance item. Unrelated independent phases may continue when their prerequisites are intact.

Linux requires a working systemd user scope/cgroup-v2 boundary. Windows process-tree containment and stuck user callbacks retain documented limitations. Held-out clips/templates, provider approval/pins, real identities and supported-host capability are external prerequisites, not code fixes to invent.

On test completion, stop only the test instance and its verified owned processes, disable its test intake/provider flags and clean only its disposable database/media after evidence is retained. Do not touch the existing instance or shared content-addressed bytes. Cleanup uncertainty requires reconciliation, not an assumed free worker slot. No rollout or production rollback is executed by this plan.
