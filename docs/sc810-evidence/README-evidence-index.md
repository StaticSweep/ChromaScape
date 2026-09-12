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
- **Phase 6 (final artifact)**: `closure-plan-phase6-final-artifact-20260911.md` — found the
  build inheriting a dirty tracked runtime-state file
  (`.micro6/gameplay-v2-reservations/gameplay-v2-reservations.snapshot.jsonl`, mutated by
  Phase 5's test suite), fixed by untracking it and both copies (root + `runelite-client/`)
  and adding a `**/`-anchored `.gitignore` rule, then rebuilt clean. Final identity: commit
  `6e271ce40cbccbde224fffe73a86a3f4df61a40c`, artifact digest
  `sha256:8db7a4747f6f6fe3ef9a9d959af44570d7f975ef0c80339507687208f2ef09ee` — supersedes every
  earlier digest recorded this session.
- **Phase 7 (final signed bundle)**: `closure-plan-phase7-final-bundle-20260911.md` — bundle
  `srcbundle_0a49cff0d280eef221c929b9`, bound to the Phase 6 artifact digest, promoted clean.
  Rerun produced an identical bundle_id, confirming deterministic regeneration and successful
  re-validation of the promoted bundle from disk (no dedicated verify-only CLI mode exists).
  **Superseded by Phase 8's environment correction below.**
- **Phase 8 (packaged-runtime gate)**: `closure-plan-phase8-packaged-runtime-gate-20260911.md` —
  regenerated the bundle with `MICRO6_SOURCE_ENVIRONMENT=local-development` set (Phase 7's run
  had defaulted to `production`), giving current bundle `srcbundle_073725b58580eeb7d80e27cd`.
  Ran a from-scratch disposable acceptance harness (the prior session's was never committed,
  didn't survive) with the real shaded JAR on the classpath: `start()` true, `/health` 200,
  build_id/artifact_digest/source_snapshot_id all match, Fishing Training discoverable,
  `catalog_state: DEGRADED` (same pre-existing out-of-scope defect, documented not fixed). 8 of
  the full matrix of startup-time negative cases (wrong environment, untrusted key, missing
  metadata/content token, missing bundle root, tampered artifact digest, wrong build_id,
  missing/pruned bundle root) all fail closed correctly. The remaining cases are mostly live,
  bound-run debugger-API negatives gated on the account migration.
- **Phase 9 (ChromaScape reconfirm)**: `closure-plan-phase9-chromascape-reconfirm-20260911.md` —
  re-ran the dispatch-readiness check pointed at `micro6-sc810-integration` (not the superseded
  packaging worktree): `/debugger` 200, machines 200, Beheerder online/runnable, ikiddoi
  correctly excluded — unchanged from Phase 10b. `GET /api/debug/builder-goals/catalog` shows
  `catalogAvailable: false` (no live Micro6 client connected to this instance) — Fishing
  Training visibility *through ChromaScape itself* needs a live-launched client, a distinct
  runtime action not attempted here; Phase 8 already proved this independently against the
  executor's own `/health`.
- **Account-record migration (2026-09-11)**: `closure-plan-phase9-account-migration.md` — the
  account migration is **complete**. `3720a9a3b5c9f57b` → `b01c7f006bace664`, executed via a
  temporary Go test reusing the existing tested internal migration functions (not handwritten
  SQL), after the entire `leeuwbot-*` Docker stack had to be restored first (found fully
  removed, unrelated to this session). Verified directly against the live database: exactly one
  canonical `launcher_accounts` row, `account_bank_data` moved with no collision, the
  `builder_queues` collision resolved (`id=1` retained, `id=2` removed), both migration and
  dedupe audit rows present, no active-assignment collision. Full row-level CSV backup at
  `C:\Dev\SC810\session-scratch\account-migration-backup\`.
- **Phases 4-5 of the newer 16-phase closure plan (isolated instance + identity reconfirm)**:
  `closure-plan-phase5-identity-reconfirm-and-credential-blocker.md` — started the isolated
  ChromaScape instance on port 8195 with the corrected `local-development` trust environment
  (Flyway schema 45, up to date; `/debugger` 200); reconfirmed the migrated account through
  ChromaScape's own `/api/debug/builder-goals/accounts` and `/machines` endpoints (not just raw
  DB/launcher queries): old hash `3720a9a3b5c9f57b` has zero occurrences anywhere in the catalog,
  new hash `b01c7f006bace664` (`udasuAvuco`) present exactly once, Beheerder machine
  online/runnable/builderGoalRunnable, `ikiddoi` machines correctly excluded.
- **Blocker found and resolved: the migrated account had no launcher-managed password**
  (pre-existing, not caused by this session's migration — verified via direct DB query, empty
  `launcher_account_secrets`, and the pre-migration backup CSV already showing blank credentials
  as of 2026-08-28, two weeks before the migration ran). User authorized restoring the real
  password (option 1: restore, not fabricate/reset, not switch accounts) and supplied it
  directly. `closure-plan-phase7-credential-restore.md` documents the restore via the real
  launcher-control API (`PUT /api/v1/accounts/{hash}/secrets`, revision-gated, not a raw SQL
  write) and the resulting verification: `hasPassword`/`launchReady` both flipped `false` →
  `true`, `launchDisabledReason` cleared, confirmed read-only through ChromaScape's own
  `/api/debug/builder-goals/accounts`. Credential value itself was never stored in any evidence
  file or echoed back.
- **Heartbeat fix, artifact re-issue, and account substitution (2026-09-12)**:
  `closure-plan-heartbeat-fix-and-account-substitution.md` — fixed a real defect where the
  executor's ChromaScape heartbeat was never scheduled when `plugin.getConfig()` was unavailable
  at startup (permanently suppressing client registration), added `/health` heartbeat
  diagnostics and 7 tests (95 tests, 0 failures), then rebuilt and re-signed: new build_id
  `04718fe3bf0f86a27f8eb9d18d769f721748727d`, artifact digest
  `sha256:9c5fe625ff57807f4d059afbe756e0727c2daf323707c8891b6f754f686b8319`, bundle
  `srcbundle_7c5226066eb527205273c425` — **supersedes `sha256:8db7a474...` /
  `srcbundle_073725b58580eeb7d80e27cd`**. Also records the **explicit account substitution**
  (`udasuAvuco`/`b01c7f006bace664` → `TheSomaliMan`/`649bd3950515bd2f`, same character renamed,
  confirmed by live login and hash computation, user-authorized), the discovery that this fork
  reads config from `~/.runelite/microbot-profiles/` (not `profiles2/`), the manual packaged-JAR
  launch deviation with self-generated `launch-attempt-id`, and verified dispatch readiness
  through ChromaScape (`dispatchReady: true`, `selectedAccountEligible: true`). `catalog_state`
  is no longer DEGRADED once a live client connects.
- Phases 7+ (genuine Fishing recording, atomic binding, debugger byte verification,
  negative-matrix completion, restart/cross-revision proofs, final Felix/Shortcut closure): now
  unblocked, in progress. See `sc810-remaining-work-plan-20260911.md` in this directory for the
  full step-by-step plan.
