---
schema_version: 1
offline_record_id: 3a9e7f21-5c4d-4b8e-9a1f-6d2c8b4e0f57
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1431
shortcut_tag: 'shortcut:sc-1431'
felix_task_id: 'task_1788122221223_uphlga0o'
operation: create_note
target_note_id: null
title: 'SC-1431 native Windows implementation evidence (reconstruction and verification)'
note_type: documentation
stable_tags:
  - shortcut:sc-1431
  - change:runtime
  - offline-sync:3a9e7f21-5c4d-4b8e-9a1f-6d2c8b4e0f57
parent_id: null
entity_links:
  - entity_type: task
    entity_id: task_1788122221223_uphlga0o
    link_strength: primary
  - entity_type: shortcut_story
    entity_id: '1431'
created_at: '2026-09-19T15:10:00Z'
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_marker: null
---

## Task resolution

Reuse Felix parent `task_1788122221223_uphlga0o` under workflow `shortcut_delivery`, canonical project
`C:\Dev\IdeaProjects\ChromaScape`, source `shortcut_story:1431`, tags `shortcut:sc-1431` +
`change:runtime`. Never create a duplicate. Felix transport was unavailable this session (no Felix or
Shortcut MCP tools exposed), so no Felix write, transition, checklist change, or Shortcut marker is
claimed. GitHub operations used `gh` (authenticated as ikiddoi).

## Note payload

# SC-1431 native Windows implementation evidence

Verification plan: `C:\Dev\IdeaProjects\sc1431-verification\luna-verification-plan.md`.
Evidence root: `C:\Dev\IdeaProjects\sc1431-verification\evidence\`.

## Changed files

Review branch `codex/sc-1431-current-main-review-20260919` (worktree
`C:\Dev\IdeaProjects\ChromaScape-sc1431-review-main`) from `origin/main` `ce697df4`; commit
`c0ef23c2`; PR https://github.com/ikiddoi/ChromaScape/pull/16 (base `main`, head branch above).

- 14 SC-1431 production files under `src/main/java/.../orchestration/accountbuilder/`
- `src/main/resources/db/migration/V23__sc1431_accountbuilder_orchestration.sql` (SHA256
  `B2D205FB…E372`, git blob `f8dd2409`)
- `src/main/resources/db/migration/V32__sc1431_recovery_correlation.sql` (SHA256
  `0A232C1A…A701`, git blob `ee539242`)
- `src/main/resources/application.properties` (+6 SC-1431-only lines)
- 9 SC-1431 test files under `src/test/java/.../orchestration/accountbuilder/`
- `build.gradle.kts` (+1: `testImplementation("org.testcontainers:postgresql")`)
- User-authorized manifest additions (proven prerequisites absent from current main):
  `src/main/java/com/chromascape/web/gameplay/authority/HumanGameplayAuthorityService.java`
  (git hash `cf20bc22`, byte-identical to `3fad0c49`; supplies the required
  `AccountBuilderAuthorityVerifier` bean) and
  `src/test/java/com/chromascape/testsupport/IsolatedPostgresTestDatabase.java` (Testcontainers PG16
  fixture; its strict V45/recording-debug helpers are unused by SC-1431 tests).

Deliberately excluded (in mixed `3fad0c49`, not in manifest): `AccountBuilderGameplayGateway`,
`Micro6AccountBuilderGameplayGateway`, SC-1449 V40-V42 migrations, `orchestration/v2` package. Grep
proves the reconstructed package references none of them.

## Tests

Native Windows, JDK 17 toolchain, Gradle 8.14.2. Isolated ports 55450/55451 (PostgreSQL 16) and
18091 (HTTP); live 8090/8098 never touched; canonical dirty checkout untouched.

- `compileJava compileTestJava`: green.
- Focused SC-1431 suite (`--tests "com.chromascape.web.gameplay.orchestration.accountbuilder.*"`,
  rerun twice incl. post-format): **36 tests, 0 failures, 0 errors, 0 skips** (JUnit XML parsed).
- Flyway proofs: empty schema (V23+V32 → 8 `ab_*` tables) and upgrade schema (V1,V5,V22,V23,V32 →
  16 tables, 7 triggers), `ON_ERROR_STOP=1`, exit 0.
- SC-1430 handoff + SC-1449 conformance: **68 tests green** (GameplayV2 contract/provider suites +
  ProviderDecisionHandoffIntegrationTest on isolated 55450).
- Isolated HTTP matrix (18091): 401 no/wrong operator key; 200 valid key with
  `gatewayAvailable:false`, `liveDeliveryReady:false`; 400 `INVALID_CONTRACT` for unknown id and
  invalid payload; redacted bodies; disabled start `enabled:false`. `RUNTIME_DISABLED→503` mapping is
  service-level, covered in-process (ServiceTest/RestartTest/RollbackTest).
- Kill/restart: isolated app PID force-killed and restarted twice on the same isolated DB; 18091
  verified closed/reopened.
- Quality: `git diff --check` clean; added files spotless-clean after scoped google-java-format
  1.17.0 apply (32 pre-existing current-main violations reproduced on pristine `ce697df4` worktree);
  checkstyle exit 0 (warnings only); secret scan clean; forbidden-capability scan clean (no raw
  input/ProcessBuilder/8098/Micro6/reflection).

## Remaining work

- Full per-state kill/restart checkpoint matrix with deterministic fake-gateway invocation counts
  (in-process RestartTest covers conservative reconstruction; HTTP-level matrix needs a delivery
  harness excluded from this default-off review).
- Full repository test suite (focused suites green; baseline classification rule applies).
- Merge + exact merge-commit rerun (post-merge).
- Felix replay of the pending outbox records; Shortcut In Review → Done only after merge and exact
  merge-commit verification, and only when the connectors actually succeed.

## Checklist reconciliation

Do not check implementation or verification items from this record alone. Each item below now has
file-evidence: reconstruction manifest-only, forward-only V23/V32, default-off/closed gateway,
operator key enforcement, HELD/SHADOW zero-delivery (ServiceTest), one-use authority and identity
ownership (Concurrency/PostgresConcurrency), lease/fence (Store/Restart), no gateway-spanning
transaction and normalized restart (RestartTest), terminal immutability and uncertain no-retry
(RollbackTest), SC-1430 committed-only handoff (ProviderDecisionHandoffIntegrationTest), SC-1449
deterministic conformance (68 tests), HTTP auth/status/redaction (evidence `06-http`), app-level
kill/restart (evidence `07-restart`), diff/secret/capability/format (evidence `08-quality`,
`07-restart`). Remaining unchecked: full checkpoint kill/restart matrix, full-suite regression,
merge, exact-merge rerun, Felix/Shortcut synchronization.

## State reconciliation

Task remains `in_review`; `spec_ready` presumed preserved from the planning record. No Felix
transition or note-write is claimed in this record. Do not replay this note before
`20260919T134834Z--sc-1431--create-note--native-windows-reconstruction-verification-plan.md`.

## Shortcut reconciliation

No Shortcut MCP call was made and no marker was posted. GitHub PR #16 exists as the reviewable
artifact. The story stays In Review until the PR is merged, the exact merge commit is verified, and
the connectors genuinely succeed.

## Sync result

Pending. GitHub commit `c0ef23c2` pushed; PR https://github.com/ikiddoi/ChromaScape/pull/16 open.
No Felix note, transition, or Shortcut synchronization is claimed.
