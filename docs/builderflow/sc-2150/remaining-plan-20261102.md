# SC-2150 — full remaining delivery plan, 2026-11-02

Story: [SC-2150](https://app.shortcut.com/leeuwbot/story/2150), **In Progress**.
Host: Fedora/Linux. Run commands relative to the ChromaScape repository root.
Windows implementation and qualification belong to SC-2204.

This is the current remaining-work plan after the owner's switch to automatic assisted
review. It supersedes the remaining-work/status portions of
`remaining-plan-20261031.md`; historical implementation evidence and detailed contracts in
that document remain valid references. This document records a plan, not additional test
passes, implementation, deployment or story acceptance.

## 1. Intended outcome and current decisions

Deliver a reproducible Fedora workflow that imports supported media, automatically produces
source-frame-linked cursor suggestions, displays them clearly, permits optional correction,
and exports immutable source/analysis/review evidence. Preserve uncertainty, ownership,
provenance and the separation from native telemetry and training admission.

The owner stopped the dense manual-labelling workflow and selected **“Switch to automatic
assisted review.”** Manual labelling remains paused. The current recording group has been
withdrawn from independent held-out evaluation. This decision permits assisted review of
that footage; it does **not** establish detector accuracy or waive every original story gate.

Proceed with engineering and assisted delivery while resolving the remaining acceptance
decision explicitly. A product demonstration, detector confidence and output coverage are
different evidence from independently measured accuracy.

## 2. Verified baseline

| Area | Current evidence | Consequence |
|---|---|---|
| Merged delivery | `origin/main` at `7758d867`, through PR #104 when checked | Existing merged SC-2150 work is the integration baseline |
| Review instance | `http://127.0.0.1:18191/mouse/video` returned 200 | Working local review environment; preserve its database and media |
| Normal application | `http://127.0.0.1:8090/mouse/video` was unreachable at this plan's check | Diagnose and restore; historical successful deployment is not current health proof |
| Corpus | Review database contains 33 sources | This is the review instance's corpus, not a claim about the 8090 catalog |
| Manual work | 44 labels retained in session `606b14b6-38af-4f99-a5ac-335f1b5b2416`, now `ABANDONED` | Preserve history and backups; these are not sealed independent truth |
| Withdrawal | Whole group `yt-NTe3PTTBhuU`, three sections, removed from active independent policy | Exclude the entire recording group from future independent evaluation |
| Automatic demonstration | Analysis `092bd3af-9059-4db8-92be-c65aafa41b8e` succeeded: 597 samples / 1,790 frames, 592 visible suggestions, five ambiguous | The workflow works on this clip; 592/597 is output coverage, not recall or accuracy |
| Effective assisted configuration | `visual/v1`, `cursor-template/v8`, 10 fps target, 960 px sample width, clip-specific template, 1 GiB total job storage bound | This differs from the frozen W10 profile and currently depends on local provisioning |
| UI | Generate automatic cursor track; Show automatic suggestions; explanatory guide/status; Previous/Next suggestion | Local changes are exercised on 18191 but remain uncommitted |
| Navigation check | Browser verified 0 → 3 → 6; hiding suggestions restores single-frame stepping | Browsing no longer silently lands on unsampled frames while suggestions are enabled |
| Focused tests | 39/39 JS checks passed; JS syntax and scoped diff checks passed | Narrow evidence for today's changes, not a new full-suite result |
| Blind isolation | Another held-out group still offers only `WITHOUT_SUGGESTIONS` and returns prediction HTTP 403 | Preserve this boundary in subsequent changes |
| W10 preparation | Frozen detector/scorer inputs and end-to-end nine-clip dev rehearsal, including the harness, recorded in merged evidence | Final independent evaluation remains unrun |
| YouTube product acquisition | Still disabled; D3 proposal remains unresolved | Downloaded corpus clips do not prove the product YouTube route |
| Native round trip | X11 recorder and stand-in qualification exist; no completed owner-operated W12 run is claimed | Real native capture/trace verification remains |

Sources: `assisted-review-decision-20261102.md`, the freeze/runbook documents, merged
deployment/rebuild evidence, fresh endpoint checks and the working-tree diff. Shortcut's
old comments and the October status tables lag behind the implemented work.

## 3. Decisions and dependencies

| Item | Owner contribution | Engineering preparation and result |
|---|---|---|
| Accuracy/acceptance scope | Decide whether initial acceptance is assisted-only with formal accuracy deferred, or retains full independent qualification | Prepare an exact acceptance amendment or a qualification protocol; keep unresolved criteria visibly open until decided |
| W12 session | Be present for account launch/login and confirm the fishing/drop setup | Prepare isolated capture, execute the approved task, stop it, convert the trace and verify alignment |
| D3 acquisition bundle | Select the permitted test clip and decide the concrete downloader/runtime/egress proposal | Verify candidate versions/signatures and prepare a reviewable host/configuration bundle before seeking the outstanding decisions |
| Tracker closure | Existing instruction to skip workflow transitions remains in effect until changed | Prepare evidence and checklist updates; apply transitions only under the existing authorization rules |

Already decided: D-HO expansion, keep cut-F1 at 0.90, D-RECALL at 95%, disposition of
unrelated full-suite failures, and W12 fishing/drop with purchasing/banking off. These
decisions do not need to be requested again. The old 12-section manual campaign is paused.

## 4. Execution order

```text
R1 package the assisted feature ── R2 resource/recovery verification ── R3 deploy/restart
               │                                                        │
               └────────────────── R4 real native round trip ────────────┤
                                                                         ├─ R7 final verification ─ R8 delivery
accuracy scope decision ── R5 acceptance/qualification evidence ──────────┤
D3 concrete bundle/decision ── R6 YouTube integration and qualification ───┘
```

R1–R3 can proceed without a new manual-labelling session. R4 needs the owner's login
window. R5 depends on the acceptance decision and independent truth if qualification is
retained. R6 depends on the unresolved D3 items. Preserve the working 18191 instance
while preparing a separate clean build and restoring 8090.

## R1 — Package automatic assisted review into a reproducible feature

**Owner:** agent. **Maps to:** W6/W8 follow-through, W13/W14; AC5–7 and AC12–14.

### R1.1 Isolate and integrate the source changes

Create an isolated clean checkout/worktree from current `origin/main`. Transfer only the
reviewed SC-2150 changes, preserving the shared checkout's other stories and WIP:

| Path | Change |
|---|---|
| `src/main/resources/templates/mouse-video.html` | Automatic action, toggle wording, instruction panel, suggestion status and script versions |
| `src/main/resources/static/css/mouse-video-review-guide.css` | Responsive on-screen instructions |
| `src/main/resources/static/js/mouse-video.js` | Schedule automatic analysis, follow server policy, show status and navigate predictions |
| `src/main/resources/static/js/mouse-video-review-core.js` | Policy precedence and suggestion-navigation helpers |
| `src/test/js/mouse-video.test.js` | Policy and navigation boundary coverage |
| `docs/builderflow/sc-2150/assisted-review-decision-20261102.md` | Owner decision, withdrawal and completed-run evidence |
| This plan | Current remaining work and acceptance conditions |

Review the exact diff. Include any additional configuration/packaging implementation as
explicitly scoped files or hunks. Do not stage the mixed working tree wholesale. Where
an owned file is ignored, add only that verified path explicitly.

### R1.2 Make template and analysis configuration durable

The current demonstration depends on private files under `build/sc2150-assisted/` and
direct copies into the runtime checkout. Replace those operational dependencies with a
maintained, documented provisioning path:

1. Define the effective analysis profile: algorithm/method, sample fps/width, thresholds,
   cursor template bytes/digest, hotspot, toolchain identity and storage limits.
2. Use reviewed template packs or a documented source-specific seed/crop flow. Preserve
   original media and record the exact source frame/crop and template digest. A dense
   manual-labelling campaign is not a prerequisite for this assisted workflow.
3. Resolve `templatePackId` to the actual configured pack. If pack selection is not
   implemented, expose the effective configured pack and reject unsupported choices;
   a free-form label must not imply that different template bytes were selected.
4. Normalize UI/operator requests to the same effective profile identity. The same
   source/configuration should reuse a completed analysis instead of creating expensive
   duplicates because one caller sent a pack label and another omitted it.
5. Freeze the effective template/configuration for each scheduled analysis. Later local
   setting changes must leave existing analysis identities and artifacts unchanged.
6. Keep private media, credentials and non-redistributable assets outside Git. Document
   how a fresh runtime installs/verifies required template bytes rather than assuming
   files in a temporary build directory survive.
7. Document supported cursor/viewport cases and explicit unknown outcomes. The generic
   arrow at 480 px failed to calibrate this clip; the current success must not silently
   imply that all cursor themes, scales or clips are supported.

The current source-specific template digest is
`14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`.
The original W10 frozen template files remain separate and unchanged.

### R1.3 Complete the interaction checks

- Generate automatically without an annotation attestation or manual review session.
- Repeat the same action after completion: reuse the equivalent completed analysis.
- Display loading, failed, cancelled, no-template and uncertain outcomes clearly.
- Verify the on/off toggle and source-coordinate marker alignment after resizing.
- With suggestions enabled, Previous/Next suggestion visits real visible predictions;
  with suggestions hidden, ordinary frame stepping remains available.
- Verify navigation across an initially unloaded index page, first/last boundaries,
  manually selected unsampled frames and uncertain frames such as ordinal 573.
- Show a clear message while the exact preview loads and when no marker exists.
- Switch source/analysis while requests are pending; stale responses must not move or
  annotate the new source. Keep saved/draft correction ownership intact.
- Preserve original video, previous predictions, the 44 manual labels and their lineage.
- Keep the independent held-out route guards authoritative. Assisted data must never
  become independent truth merely by hiding suggestions or changing a description.

**Deliverables:** focused feature PR, configuration/asset provisioning instructions,
updated operator help and evidence on the exact clean candidate.

**Exit:** a fresh checkout plus documented local settings reproduces the automatic
workflow; the UI needs no temporary-worktree paths or hand-copied build resources.

## R2 — Verify analysis limits, cancellation and recovery for the assisted profile

**Owner:** agent. **Maps to:** W2/W5/W13 refresh; AC3, AC4, AC9 and AC10.

**Executed:** measurements, both cancellation points, restart/lease-loss, operator reconcile,
containment and quota receipts are recorded in
[verification evidence R2](verification-evidence-r2-20261103.md).

1. Measure the effective profile's sample count, workspace bytes, peak memory, elapsed
   time and cleanup. Record the chosen budget and account for concurrent jobs.
2. Preserve hard storage limits. The former 128 MiB total budget provided only a 64 MiB
   job filesystem and was inadequate for this clip's samples. The current 1 GiB budget
   is a local working setting, not evidence that every allowed input fits.
3. Handle predictable output/quota exhaustion with the appropriate existing stable
   limit/error contract, rather than presenting every failure as malformed media.
4. Recheck decoder bounds, network/secret denial, process ownership, memory/swap,
   CPU/PID/time limits and descendant termination where the effective setup changed.
5. Exercise cancellation during sample extraction and both in-JVM tracking passes.
   Confirm cleanup before admission capacity is released; verify no late publication.
6. Exercise restart/lease-loss recovery with a disposable job/database. Keep ambiguous
   cleanup fenced for reconciliation and verify the existing operator reconcile action.
7. Record useful stage/progress information. The current long analysis can remain at
   20% through most tracking work; replace misleading apparent inactivity with truthful
   stage/elapsed status rather than invented progress percentages.
8. Benchmark the normal path. Optimize demonstrated bottlenecks if the chosen runtime
   budget is exceeded; retain identical normalized outputs when making a performance-only
   change. Record any method/configuration change as a different candidate identity.

Reuse the existing containment probes and retained W2/W3 evidence. Rerun checks affected
by today's configuration or source changes; do not describe old passes as fresh runs.

**Exit:** the shipped profile has measured, bounded execution and truthful terminal
outcomes, with cancellation/restart cleanup evidence and no safety-boundary regression.

## R3 — Restore 8090 and deploy the packaged candidate on Fedora

**Owner:** agent. **Maps to:** W13/W14; AC1, AC7–13.

Use `docs/CHROMASCAPE_RESTART_RUNBOOK.md` as the canonical lifecycle reference.

1. Diagnose why 8090 is unavailable: inspect its intended runtime checkout, launcher
   output, listener/process ownership, database connectivity and local configuration.
2. Inspect the existing Flyway/schema state and back up data before a schema-affecting
   change. The November 1 main deployment already rebuilt/migrated its database; an
   old runbook's precondition is not authorization to recreate the database again.
3. Select an isolated main-based runtime built from the reviewed candidate. Keep its
   classes/resources/dependencies self-consistent, with no temporary worktree dependency.
4. Persist machine-specific configuration in `.env.local`; document supported variable
   names/defaults in `.env.example`. Preserve unrelated local settings and secret values.
   Confirm the launcher actually forwards the required mouse-video settings.
5. Provision the approved template/profile through R1's durable mechanism. Scope any
   development-only template-cache setting to the appropriate development environment.
6. Start/restart natively on Fedora from the repository root:

   ```bash
   scripts/start-fedora-dev.sh
   ```

7. Verify `/debugger`, `/mouse/video`, capabilities, authenticated catalog/media access,
   automatic analysis, prediction toggle/navigation, correction history and export.
   Exercise writes using a disposable owned fixture, not the independent held-out set.
8. Confirm YouTube remains disabled until R6 passes. Keep actual supported/available/
   runnable/isolated/qualified states distinct in the capability report.
9. Preserve the separate 18191 corpus/review database and repeat its blind isolation check.
10. Perform one owned restart and repeat the smoke test: settings, template assets,
    analysis reuse and persisted reviews must survive without manual file copying.

**Rollback:** retain the previous runtime/configuration and a database snapshot appropriate
to any migration. Restore through the documented lifecycle; preserve media and append-only
evidence. Apply destructive schema restoration only under an actual, reviewed recovery need.

**Exit:** 8090 is healthy on the reviewed build and survives restart; 18191 data remains
intact; the deployed behavior matches the PR and documented configuration.

## R4 — Complete the real native recording/trace round trip (W12)

**Owner:** owner for login/setup; agent for execution and verification.
**Source:** `w12-native-session-runbook-20261031.md`.

1. Prepare the existing Fedora qualification kit and confirm its current artifact/logger
   versions, trace directory, staged private handoff and isolated endpoints.
2. Confirm ports 8093/8099 are available and the account is not active on another client.
   Verify the kit boundary: its client cannot reach 8090 and its capture service is
   reachable. Keep the normal 8090/8098 environment separate.
3. Check current account identity/world/dispatch gates. The canonical restart runbook
   records an unresolved account-identity migration for real Fishing publication; verify
   the intended qualification client independently. Resolve any genuine prerequisite
   through the supported account process rather than bypassing an identity mismatch.
4. Owner launches/logs in and stands at the approved fishing spot with the tool. Use
   fishing/drop, no banking or purchases, with the developer Bot Mouse overlay active.
5. Identify the actual canvas, rather than assuming the historical size:

   ```bash
   python3 scripts/sc2150/record_x11_canvas.py --list --display :0
   ```

6. Record approximately ten minutes with MP4/frame sidecar and the corresponding mouse
   trace while the approved task runs. Record the real settings and source identities.
7. Stop the dispatched run and verify it stopped; complete capture and retain raw hashes.
8. Convert with `label_from_mouse_trace.py`. Check source identity, exact ordinals,
   drops/duplicates, clock alignment and PTS/trace skew (target ≤ 50 ms).
9. Cross-check drawn cursor pixels against converted coordinates. The stand-in's earlier
   timing/pixel result is preparation, not proof for this real client.
10. Import into a disposable instance, run the declared detector profile, compare against
    trace-derived labels and report generated versus human trace provenance separately.
11. Restore temporary client/settings/service changes. Retain a sanitized verification
    record and hashes; keep media, traces and credentials in private storage.

**Exit:** actual owner-operated native capture → trace conversion → pixel/alignment check
→ import/analysis/export evidence is complete. This can provide automated reference data
for that native case; it does not alone qualify arbitrary third-party footage.

## R5 — Resolve and complete the accuracy acceptance route (W9/W10)

**Owner:** owner for scope decision; agent for protocol, tooling and evaluation.

### Route A: assisted-first release with formal qualification deferred

Prepare a concrete acceptance amendment that states which capabilities ship as assisted
suggestions, which accuracy claim remains unqualified, and which original acceptance
criteria remain deferred. If approved, record the outstanding qualification as explicit
remaining scope/follow-up, with traceable ownership. Keep qualified flags and training
admission consistent with that decision.

The existing decision to expose this recording's predictions is not itself approval to
close AC5 or remove YouTube/native obligations. Do not report a deferred gate as passed.

### Route B: retain the original independent qualification gate

1. Establish independent reference truth before predictions are exposed. Assess whether
   automatically captured native/reference telemetry covers the intended supported cases.
   Native truth can reduce manual work for those cases, but it must be independent of
   the detector and must not be generalized beyond its demonstrated coverage.
2. Exclude `yt-NTe3PTTBhuU` in full and any other exposed/tuning groups. Reassess channel/
   recording diversity, visible/absent counts, cuts and uncertainty after this withdrawal.
3. If third-party blind annotations remain necessary under the agreed protocol, leave
   that dependency open while manual labelling is paused. Do not silently restart the
   abandoned 12-section campaign or substitute model output for its labels.
4. Define the qualification candidate/profile revision. The current 960 px, 10 fps,
   clip-specific assisted profile differs from the 480 px, 30 fps W10 freeze. Version and
   freeze the actual candidate, templates, thresholds, tools, media and truth hashes.
   Apply the fresh-source requirements when changing a candidate after inspecting results.
5. Follow `w10-final-evaluation-runbook-20261101.md`: convert/verify sealed independent
   exports where applicable, assemble the corpus, produce predictions twice for
   determinism, score once and apply the recorded gate decision separately.
6. Preserve raw scorer and decided verdicts. D-RECALL uses 95%; the original frozen
   scorer's 99% verdict remains visible. Other gates remain median ≤ 4 px, p95 ≤ 12 px,
   absent false ≤ 1% and cut-F1 ≥ 0.90, with the declared qualification coordinate plane.
7. Verify live/offline parity and source-group separation. Report per-source results,
   source-clustered confidence intervals, visible/absent/ambiguous/dropped counts and
   limitations. Qualify additional automatic observation methods separately where claimed:
   their precision/recall targets remain ≥ 0.95 / ≥ 0.80, and pause-boundary p95 remains
   within two source-frame intervals. Keep unqualified methods explicitly unsupported.
8. A failed gate stays failed. Tuning belongs on development data; any later independent
   attempt needs the appropriately versioned candidate and fresh unexposed evaluation set.

**Exit:** either a recorded, approved assisted-only acceptance amendment with qualification
explicitly deferred, or an independent frozen evaluation with its actual verdict. The
current 592 suggestions and 44 retained manual labels do not meet this exit by themselves.

## R6 — Finish the bounded YouTube product route (W11 / D3)

**Owner:** agent after the outstanding concrete D3 decisions.
**Source:** `fa13-youtube-acquisition-proposal.md`.

1. Revalidate the historical downloader proposal (`yt-dlp_linux 2026.08.19`) against
   official release artifacts and its signature/key. Present exact verified pins and
   CLI compatibility; it is not a claim about the latest release or an approved install.
2. Determine whether a JavaScript runtime is needed for the chosen provider path. If
   needed, propose its exact binary/version/digest and constrained execution setup.
3. Obtain the permitted public/unlisted test clip and record source/use provenance.
   Prepare controlled unavailable/private/rate-limit cases without provoking live abuse.
4. Prepare and provision the reviewed acquisition boundary: process/resource isolation,
   private network namespace and proxy-only egress. Decoding/analysis remain networkless.
5. Enforce CONNECT/443 and reviewed host policy, connect-time IP checks and redirect/
   rebinding protection; deny private, loopback, link-local, metadata and other prohibited
   destinations. Bound bytes, connections, time and workspace use.
6. Use server-owned arguments and pinned executables. Exclude cookies, profiles, netrc,
   plugins and arbitrary runtime downloads. Verify required pins before launch.
7. Publish through the existing fenced/idempotent import path with provider ID, original
   URL, acquisition time, media metadata and content digest. Preserve explicit private,
   unavailable, unsupported and rate-limited outcomes.
8. Verify successful import/retry, conflicting requests, cancellation and confirmed
   descendant/workspace cleanup. Exercise pin drift, missing runtime, oversized/invalid
   media, timeouts and egress/filesystem/credential attack fixtures.
9. Run the required successful supported-source integration check on the approved clip;
   provider refusal alone does not satisfy AC2. Enable the product flag only after the
   declared route is proven and the deployment configuration is ready.

**Exit:** successful product-route import plus fault/boundary evidence on the approved
configuration, or an explicit approved scope deferral. Ad hoc corpus acquisition remains
separate evidence and cannot substitute for this gate.

## R7 — Freeze the integrated candidate and run final verification

**Owner:** agent. **Maps to:** W5 refresh, W13 and W14.

Freeze a clean candidate manifest containing code, resources, templates/provisioning,
configuration/toolchain identities and applicable schema versions. Retain its exact commit.

| Verification | Required evidence |
|---|---|
| Frontend | Focused JS checks plus relevant regression coverage for new UI changes; keyboard operation, status readability, preview sizing and toggle/navigation in a real browser |
| Analysis identity | Same effective request reuses completed work; changed template/config gets a different identity; immutable old outputs; stale queued jobs cannot run under changed settings |
| Browser/API isolation | Held-out route refusal and policy precedence; no pre-seal prediction leaks; delayed/stale source/analysis/preview responses remain fenced |
| Persistence/review | Manual history and original artifacts survive upgrade/restart; assisted and blind lineage remain distinct; no accidental seal of machine output |
| Resource/recovery | R2 measurements and cancellation/restart/cleanup receipts, including the effective storage budget |
| Native | R4 real recording, conversion/alignment and catalog regression receipts |
| Provider | R6 integration, failure and constrained-egress evidence |
| Accuracy | R5 actual evaluation or the explicit acceptance amendment/deferred criteria |
| Deployment | R3 smoke test and restart persistence on 8090, with review data preserved on 18191 |

Run targeted Java analysis/scheduling/identity/review/access/migration tests when those
components change; run Python checks for any maintained provisioning/conversion changes.
Use real PostgreSQL and required container prerequisites for DB/locking evidence. Broaden
to the repository's required checks on the final candidate. Preserve the existing per-failure
dispositions and accepted D6 decision for unrelated full-suite failures; fix any new
SC-2150 regression and report actual pass/fail/skip counts.

Re-use valid earlier evidence where unaffected. A test failure, skipped integration test,
provider outage or unsupported capability must remain visible in the final evidence.

**Exit:** every required check has a candidate-linked result or an explicit accepted
disposition; the tested artifact matches the feature PR and deployment.

## R8 — Delivery, evidence and story closure (W14)

1. Merge the scoped reviewed feature changes through the normal repository process and
   deploy the resulting build using R3. Record commit/build/config identities.
2. Write final implementation and verification notes: changed behavior/files, actual
   test results, supported cases, accuracy status, provider status, resource measurements,
   remaining limitations, rollout and rollback.
3. Reconcile the original story's AC1–AC14 and the later A11/W10, A12/W11, A13/W12 and
   delivery criteria against concrete receipts. Do not inherit stale unchecked October
   checklists as proof that completed work must be repeated.
4. Close required criteria only with evidence. If the owner accepts deferral, record the
   exact changed scope and outstanding ownership; do not silently drop an original gate.
5. Prepare the final handoff and tracker updates. Preserve the existing instruction to
   skip Felix/Shortcut workflow transitions until the owner changes that instruction.

**Exit:** merged, reproducible, deployed behavior; an accurate acceptance matrix; retained
evidence and rollback; no unrecorded required work presented as complete.

## 5. Acceptance reconciliation

| Original criterion | Remaining treatment |
|---|---|
| AC1 upload/source/proxy/index | Existing implementation/evidence; smoke on the deployed candidate |
| AC2 supported YouTube acquisition | R6 remains open |
| AC3 UUID/body idempotency | Retain existing import proof; verify normalized automatic-analysis reuse in R1/R7 |
| AC4 decoder/resource bounds | Refresh for the higher-resolution assisted profile in R2 |
| AC5 cursor quality/determinism | Independent accuracy remains open under R5; reproduce the effective assisted profile in R1/R7 |
| AC6 hidden/unsupported/cuts/VFR | Preserve unknowns and declared support; qualify claimed cases in R5/R7 |
| AC7 immutable manual review | Preserve existing 44-label lineage and verify integrated UI/persistence in R1/R7 |
| AC8 training admission | Preserve false-by-default admission and source/provenance gates in R3/R7 |
| AC9 cancellation/restart fencing | Existing mechanics plus R2/R7 refresh for changed settings |
| AC10 access/nondisclosure | Existing mechanics; retain held-out 403 and owner/access regression checks |
| AC11 no invented native facts | Maintain video/trace/generated/human distinctions through R4/R7 exports |
| AC12 usable catalog/browser/native regression | R1/R3/R4/R7; automatic navigation is locally demonstrated |
| AC13 exact source-frame mapping | Retain existing frame-index proof; verify changed UI and actual native alignment |
| AC14 evidence-backed claims | Keep suggestion confidence separate from calibrated accuracy and human provenance; resolve R5 |

## 6. Effort and owner time

These are planning ranges, not delivery commitments. External prerequisites and defects found
during qualification can change them.

| Package | Indicative engineering effort | Owner time/dependency |
|---|---|---|
| R1 packaging/profile/UI integration | 1–2 working days | Existing assisted-review decision is sufficient to start |
| R2/R3 limits, restore/deploy/restart | 0.5–1.5 days | Additional input only if diagnosis reveals a real unresolved dependency |
| R4 native round trip | 0.5–1 day including setup/evidence | Approximately 15–30 minutes for launch/login/setup, plus the recording window |
| R5 accuracy route | Protocol/scope preparation first; evaluation about 0.5–1 day once truth is ready | Acceptance decision; independent reference availability if qualification is retained |
| R6 YouTube | 2–4 days after prerequisites | Concrete D3 pins/runtime/clip/egress decisions and host provisioning |
| R7/R8 final verification/handoff | 0.5–1 day after integrations | Acceptance of any explicit scope amendment; existing tracker-transition instruction |

## 7. Immediate next actions

1. Prepare the clean, scoped R1 candidate and reproducible profile/template provisioning.
2. Verify the changed limits/recovery path and restore/deploy 8090 through R2/R3.
3. Prepare the W12 kit and arrange the owner's login window; complete the real capture.
4. Present the concrete R5 acceptance amendment/protocol and D3 implementation bundle.
5. Complete the remaining selected gates, freeze the integrated candidate and deliver
   against the actual acceptance matrix.

The default next action is engineering preparation, not another dense manual-labelling
session. SC-2150 remains In Progress until its remaining scope is completed or explicitly
amended and the resulting acceptance is recorded.
