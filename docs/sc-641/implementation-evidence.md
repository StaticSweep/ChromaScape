# SC-641 Implementation Evidence

Felix task `task_1790966531845_nb35cf2q`; authoritative plan
`note_1790966608780_128cd1d6`; project `C:\Dev\IdeaProjects\ChromaScape`.
Isolated branch `codex/sc-641-catalog-profiles`, base SC-665 `33ba2a02` / PR #42.

## Changed files

All paths below are relative to `C:\Dev\IdeaProjects\ChromaScape`:

- `src\main\java\com\chromascape\web\aiplayer\catalog\`: six API/service/normalization/validation/native SQL storage/failure classes. Lazy schema retrieval, canonical aliases, durable stale cache, operator-authorized immutable profile versions and strict validation.
- `src\main\java\com\chromascape\web\integration\micro6\service\Micro6CanonicalHash.java`: exposes the existing canonical schema/settings hash implementation; its algorithm is unchanged.
- `src\main\resources\db\migration\V32__sc641_catalog_profiles.sql`: additive released storage migration.
- `src\main\resources\db\ai-player-current\V4__catalog_profiles.sql`, `db\ai-player-current-identity.properties`, `src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchema.java`: four-migration disposable contract, `sc641-current-v1`, reviewed fingerprint `sha256:bdff330e5287ae46f766ccc312d7bb36128a8b1b31e4d12e88be345db85ef63a`.
- `src\main\resources\templates\debugger.html`, `static\js\debugger.js`, `static\js\ai-player-catalog.js`, `static\css\ai-player-catalog.css`: AI Player workspace, filters, schema form, provenance/facts and immutable version browser.
- `src\test\java\com\chromascape\web\aiplayer\catalog\`: golden Micro6 fixtures, normalization/validation/service/controller/auth/native PostgreSQL tests.
- `src\test\resources\sc641\fishing-fields.json`: bounded test subset of statically inspected Fishing configuration fields; not a live catalog export.
- `src\test\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchemaTest.java`: four-migration expectations.
- `src\test\js\ai-player-catalog.test.js`, `debugger-workspace.test.js`: real DOM acceptance and updated workspace contract.
- `build.gradle.kts`, `package.json`, `package-lock.json`, `.gitignore`, `docs\sc-641\scoped-quality.gradle`: focused Java test lane, pinned jsdom DOM test dependency and dependency-directory exclusion.
- `docs\SC-641-DELIVERY-PLAN.md`, `SC-641-CATALOG-PROFILES.md`, `sc-641\mockup.excalidraw`, evidence files; `SC-665-CURRENT-SCHEMA.md` points to the successor contract without replacing historical evidence.

## Tests

Executed natively on Fedora with Java 17.0.20.1+1 and Node 22.23.1:

- `aiPlayerCatalogTest`: **22 passed**, 0 failed/errors/skipped. Includes real PostgreSQL append/version concurrency, immutable historical snapshots and hashes, cache persistence across store instances and released V32 Flyway application/validation.
- `aiPlayerSchemaTest`: **21 passed**, 0 failed/errors/skipped. Four-migration bootstrap/JPA validation/reset, pinned schema identity, drift/unsafe-target and recording/dataset/provenance invariants.
- `npm test`: **235 passed**, 0 failed/skipped; 13 new real jsdom tests use the shipped template/module. Covers defaults, dependencies, field errors, stale/unavailable, lazy requests, async races, source links, retained settings and history, secret non-persistence and literal untrusted text.
- `compileJava`, scoped `spotlessCheck`, scoped `checkstyleMain`: PASS; final Checkstyle has zero violations across the six new main classes.
- `git diff --check`: PASS.
- Fingerprint exporter ran only against newly created owned PostgreSQL 16 container `sc641-postgres-20261002` (loopback port 42467). No existing database was reset or migrated. Current and normal-migration test paths succeeded.

The final catalog/style command was `bash ./gradlew -I docs/sc-641/scoped-quality.gradle spotlessApply spotlessCheck checkstyleMain aiPlayerCatalogTest` with Java 17 and the guarded disposable test URL. The schema suite passed separately against the same final SQL/fingerprint. UI tests were rerun after the final edit-preservation change.

## Remaining work

No code implementation remains within SC-641's catalog/profile scope. Adoption requires reviewed migration/reset and registry/operator configuration. SC-812 integration and exact SC-811 recording evidence are required to enable real Code navigation; absent metadata/module stays explicitly unavailable. Live Micro6 transport, live source navigation, Windows and visual browser verification are unrun. Browser access to the local fixture preview was denied; no bypass was attempted. Full application deployment and gameplay were not requested. The dirty primary checkout and pre-existing Felix outbox conflicts were preserved.
