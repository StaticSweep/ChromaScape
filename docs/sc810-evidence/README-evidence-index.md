# SC-810 evidence index

This directory accumulated evidence under two separate, non-overlapping numbering schemes used
at different points in this engagement. Files are not renamed once referenced elsewhere (git
commits, Shortcut comments), so this index exists to keep them navigable rather than renaming
in place.

## Original delivery-verification plan (Phase 8 → 9 → 10, 2026-09-10)

Established early in this engagement; already cross-referenced by these exact filenames in git
commit messages and Shortcut comments (do not rename):

- `phase-8-evidence.md` — packaged artifact and signed Fishing bundle.
- `phase-9-evidence.md` — live verification against the running debugger API.
- `phase-10-running-artifact-gate.md` — running-artifact attestation gate investigation, fix,
  and proof (finding 1 from the accuracy review).

## "SC-810 remaining implementation and closure plan" (Phase 1 → 20, 2026-09-11)

A later, separate, larger plan given afterward, with its own independent Phase 1-20 numbering.
Evidenced in files prefixed `closure-plan-phaseN-...`:

- Phase 1 (freeze/record baseline): verified inline in conversation, no separate file.
- Phase 2 (packaged-JAR catalog-discovery blocker): `closure-plan-phase2-catalog-discovery-fix.md`
- Phases 3, 5, 6, 7, 8 (integration onto `sc-572-remediation`, pre-package verification, final
  artifact, final signed bundle, packaged-runtime readiness):
  `closure-plan-phase3-5-6-7-8-integration-and-final-artifact.md`
- Phase 4 (trust environment decision): folded into the Phase 3/5-8 file above
  (`local-development`, not `production`).
- Phase 9 (account-record migration): **on hold**, not attempted, no file.
- Phase 10 (LeeuwBot connectivity re-check): `closure-plan-phase10-leeuwbot-connectivity-check.md`
  documents the initial re-check (launcher API reachable, but the machine read offline at that
  moment) and `closure-plan-phase10b-dispatch-readiness-confirmed.md` documents the follow-up:
  querying the launcher backend directly showed the timing artifact resolved, and then, live
  through ChromaScape's own `/api/debug/builder-goals/machines` (isolated instance, port 8195),
  `CZC8518C27 (Beheerder)` (UUID `1d391d65-6c97-5b77-b676-964f157e8c73`) confirmed
  `online: true`, `runnable: true`, `builderGoalRunnable: true`, `disabledReason: null`. The
  stale `ikiddoi` registration for the same hostname correctly shows not runnable. Read-only;
  no launch/dispatch/publish call made; instance and dedicated Postgres container stopped
  afterward, data preserved.
- Repository hygiene, round 1: `micro6-sc810-package` commit `0325bc64fd` removed 45
  accidentally-tracked generated files (JVM crash dumps, tick-replay run logs, 18 stray
  root-level copies of the `microbotReleaseJar` build output) and added narrow `.gitignore`
  rules. This changed the commit/tree identity, so the Phase 8 artifact was rebuilt and
  re-signed from the new commit — `closure-plan-phase3-5-6-7-8-rebuild-20260911.md` documents
  that artifact digest (`sha256:12ef96ad055097a469f80a052b36eab7375e6a0f96f293fb9db01bb398d97f3c`)
  and bundle (`srcbundle_1f0d63ec65164d6d5c7daa62`) — **since superseded, see round 2 below**.
- Repository hygiene, round 2: a full `git ls-files` inventory
  (`closure-plan-phase2-artifact-classification-20260911.md`) found more accidentally-tracked
  generated content the first round missed: Gradle's own `.gradle/` version-catalog-accessor
  cache (53 files), 37 duplicated `build/reports/dependency-verification/` report directories
  (185 files), `build/test-golden-{fail,pass}/` (14 files — verified via `GoldenManifestTest.java`
  to be the test harness's own output directory, not a fixture; the real fixtures at
  `micro6-harness/golden/` are untouched), and 24 loose `.class` files beside `.java` source or
  orphaned entirely. Removed in commit `9d5f103399` after per-file classification; added a
  global `*.class` ignore rule.
- **Integration-lineage correction**: `closure-plan-phase4-integration-lineage-20260911.md`
  found that `sc810-package-20260910` (where every rebuild above happened) had diverged from
  `sc810-integration-20260911`, the actual `sc-572-remediation`-based delivery lineage, missing
  real content (`UniversalTaskDiscoveryService` changes, a 250-line test file). Mirrored the
  identical repository-hygiene cleanup onto the integration branch (commit `95e7bd20da`, tree
  `278a6a51f0`). **Both rebuilds above are superseded** — the final artifact must be built from
  `micro6-sc810-integration` at commit `95e7bd20da618f0c4b4919b0009ca26f1f53e5a6`.
- **Phase 5 (final native verification)**: `closure-plan-phase5-verification-20260911.md`
  (compile clean; `:client:test` — 1595 completed, 5 failed, 6 skipped, all 5 failures
  pre-existing and unrelated — schema/mirror drift and missing Lombok-processorpath Gradle
  wiring; `Sc810RealBundleVerificationTest` passed) and
  `closure-plan-phase5b-check-20260911.md` (`:client:check` FAILED — two Checkstyle parser
  errors on modern Java syntax the tool can't parse, PMD 17,503 violations but
  non-blocking by existing config, same 5 test failures; not called green, per this phase's
  own instruction).
- Phases 11+ (start ChromaScape acceptance, record a genuine Fishing run, atomic binding, live
  debugger content, negative matrix, restart persistence, cross-revision protection,
  documentation/Shortcut/Felix reconciliation, final completion gate): not reached — gated on the
  account-record migration (Phase 9, still on hold; explicit authorization required, not yet
  given). See `sc810-remaining-work-plan-20260911.md` in this directory for the full step-by-step
  plan and exactly what gate blocks each remaining step.
