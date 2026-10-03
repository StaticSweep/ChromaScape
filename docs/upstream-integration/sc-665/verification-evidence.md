# SC-665 Verification Evidence

## Acceptance results

1. PASS: Empty dedicated database bootstrap applies all three current Flyway migrations and fixtures; every current entity validates with Hibernate. A Spring auto-configuration context initializes EntityManagerFactory successfully with ddl-auto=validate.
2. PASS: Repeated confirmed resets produce identical catalog fingerprints and seeded profile rows. Independent public sentinel data remains unchanged.
3. PASS: Profile/dataset, published graph, model, assistant event, source bytes/audit and registered candidate mutation/deletion rejection. Terminal jobs require a result and cannot change. Completed captures cannot reopen or have their physical evidence reopened.
4. PASS: Duplicate profile/run-session bindings and missing FK parents reject. Deferred run/client/profile/session/debug/recording identity guards reject cross-run attachments; model-to-graph lineage rejects another run. Registration cannot be claimed without final evidence fields.
5. PASS: Capture completion needs its normalized hashes and exact finalized physical recording/debug evidence, including matching final tick. Transaction rollback after interrupted finalization leaves completed=false and no persisted recording end time.
6. PASS: Added column, dropped index, disabled trigger, missing/wrong singleton and modified Flyway checksum reject validation without automatic repair. Real Spring Flyway/JPA startup rejects drift; unsafe DDL configuration fails before a sentinel application bean initializes. Error includes the documented reset instruction.
7. PASS: Unresolved/DNS/shared/remote/wrong-schema/invalid-port/extra-options/mixed-profile targets reject. Missing reset confirmation rejects. Public view dependencies cause refusal and remain intact. CLI refusal paths exit nonzero.

## Tests

- Java 17 `aiPlayerSchemaExport`: BUILD SUCCESSFUL; offline metadata export, no database connection.
- Java 17 `aiPlayerSchemaFingerprintExport`: BUILD SUCCESSFUL against the newly created PostgreSQL 16 test container; pinned fingerprint `sha256:f7ad75fccc61d0356cfade85fe488b2fede6e964f4c65efed2ff2df95081d211`.
- Java 17 Gradle `spotlessApply aiPlayerSchemaTest spotlessCheck checkstyleMain --console=plain`, with the explicit scoped quality init script: BUILD SUCCESSFUL (2m43s). JUnit XML: 21 tests, 0 failures, 0 errors, 0 skipped, timestamp 2026-10-02T17:47:47.081Z. Checkstyle: four production files, zero violations. Spotless: four production and three test/export files.
- Repeated scoped `spotlessCheck checkstyleMain` using the checked-in init script: BUILD SUCCESSFUL.
- Actual Gradle CLI smoke runs: reset with confirmation, verify, bootstrap all exit 0 with AI_PLAYER_SCHEMA_OK; reset without confirmation and remote-target reset exit nonzero with the expected refusal code. Five smoke cases PASS.
- `git diff --check` and staged diff whitespace check: clean.
- Earlier failures exposed the source @Lob/TEXT mismatch, external-view dependency resolution and finalization ordering; these were corrected before the final green run.
- Not run: the unrelated full repository test suite, Windows/native gameplay, live account/client sessions, production schema migration or reset of existing user databases.

## Risks

- This is a clean opt-in development contract based on committed df6eff61. The unrelated dirty checkout's newer entity changes were excluded and require reviewed schema regeneration before integration.
- Reset commits DROP before grouped rebuild. An interrupted rebuild can leave the disposable schema unavailable; it never reports success. Stop application instances first and retry explicit reset. It does not preserve deleted development data.
- A database administrator can bypass constraints or race the catalog check; this tool is not an administrator authorization boundary. Startup detects structural drift. SQL does not certify external file bytes or candidate artifacts.
- Native Windows/live gameplay and the unrelated full suite were not run. No existing user database or active application was reset.
- Local review/merge is still needed before adoption. Historical unrelated outbox conflicts were retained without overwriting later Felix content.

## Rollback

Revert the isolated SC-665 commit and stop using ai-player-development to roll back code. Existing default profiles and legacy migrations are unchanged. The dedicated disposable schema is intentionally reset/reseeded, not migrated or recovered. Remove only the newly created test container used by this run after verification. No production rollback is in scope.


## Current-main integration — 2026-10-03

Reconciled the SC-665 branch with SC-260 head ca06f97d (now merged through ChromaScape PR44). Resolved the build script by retaining both task groups and preserved SC-665 nullable source manifest fields with current formatting. The initial 21-test schema run exposed missing current-main entity tables/columns; added development-only V6__current_main_entities.sql from the existing canonical released DDL, including its gate, budget, packet identity, recovery and immutable-correlation constraints. Released db/migration files are unchanged. Historical development source-binding identity columns remain nullable rather than inventing provenance. V4/V5 remain reserved for stacked SC-641/SC-609 additions.

Updated the schema migration count and drift-test assertions to share MIGRATION_COUNT, and pinned the measured four-migration fingerprint sha256:35325297209c9888eab18752a5488e3e7fe7c9f32125c6747fb6043aae07d1a0. Existing development schemas fail closed and require the documented explicit disposable reset; no shared/user database was changed.

Fedora JDK17: compileJava and compileAiPlayerSchemaTestJava passed. Final aiPlayerSchemaTest passed 21/21, zero failures/errors/skips (BUILD SUCCESSFUL in 2m4s), using an agent-owned PostgreSQL16 container. Earlier failures are retained in the work log: missing table/column validation, then two old three-migration count expectations, all corrected before the final pass. Source diff checks passed. This verifies schema/transactional invariants, not physical power-loss durability. CI monitoring and Felix updates were skipped at the user's request. Native Windows qualification remains unrun.
