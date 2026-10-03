# SC-665 Implementation Evidence

Project `C:\Dev\IdeaProjects\ChromaScape`; branch `codex/sc-665-current-schema`, base `df6eff61`.

## Changed files

- `C:\Dev\IdeaProjects\ChromaScape\build.gradle.kts`
- `C:\Dev\IdeaProjects\ChromaScape\docs\SC-665-CURRENT-SCHEMA.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\SC-665-DELIVERY-PLAN.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\upstream-integration\sc-665\implementation-evidence.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\upstream-integration\sc-665\scoped-quality.gradle`
- `C:\Dev\IdeaProjects\ChromaScape\docs\upstream-integration\sc-665\verification-evidence.md`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\routine\source\persistence\SourceManifestReference.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchema.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerSchemaCli.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerSchemaConfiguration.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerSchemaTarget.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\application-ai-player-development.yml`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current-identity.properties`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current\V1__current_entities.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current\V2__current_invariants.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current\V3__deterministic_fixtures.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-external-dependencies.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-schema-fingerprint.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchemaTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\aiplayer\schema\CurrentEntityMappings.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\aiplayer\schema\SchemaFingerprintExport.java`

The isolated change includes the complete current baseline, deterministic fixture/identity, schema guard and standalone CLI, opt-in development profile, SQL immutability/lineage/finalization constraints, source manifest TEXT mapping correction, PostgreSQL tests and operating/quality documentation. Existing migration history and unrelated source changes were preserved.

## Tests

- Java 17 `aiPlayerSchemaExport`: BUILD SUCCESSFUL; offline metadata export, no database connection.
- Java 17 `aiPlayerSchemaFingerprintExport`: BUILD SUCCESSFUL against the newly created PostgreSQL 16 test container; pinned fingerprint `sha256:f7ad75fccc61d0356cfade85fe488b2fede6e964f4c65efed2ff2df95081d211`.
- Java 17 Gradle `spotlessApply aiPlayerSchemaTest spotlessCheck checkstyleMain --console=plain`, with the explicit scoped quality init script: BUILD SUCCESSFUL (2m43s). JUnit XML: 21 tests, 0 failures, 0 errors, 0 skipped, timestamp 2026-10-02T17:47:47.081Z. Checkstyle: four production files, zero violations. Spotless: four production and three test/export files.
- Repeated scoped `spotlessCheck checkstyleMain` using the checked-in init script: BUILD SUCCESSFUL.
- Actual Gradle CLI smoke runs: reset with confirmation, verify, bootstrap all exit 0 with AI_PLAYER_SCHEMA_OK; reset without confirmation and remote-target reset exit nonzero with the expected refusal code. Five smoke cases PASS.
- `git diff --check` and staged diff whitespace check: clean.
- Earlier failures exposed the source @Lob/TEXT mismatch, external-view dependency resolution and finalization ordering; these were corrected before the final green run.
- Not run: the unrelated full repository test suite, Windows/native gameplay, live account/client sessions, production schema migration or reset of existing user databases.

## Remaining work

No SC-665 implementation work remains. The local isolated branch still needs review/merge before adoption; no push, deployment or application profile activation was performed. Downstream service wiring belongs to SC-641 and later stories. Unrelated historical Felix outbox conflicts remain pending; this story uses its own existing canonical task and successfully synchronized tracker states.
