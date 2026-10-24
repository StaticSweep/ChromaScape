# Implementation evidence — SC-274/SC-276 achievable slice

Date: 24 October 2026. Branch `codex/sc274-completion-20261015`, local only, not pushed.

## Changed files

ChromaScape (`C:\Dev\IdeaProjects\.worktrees\sc274-completion`):

- `src/main/java/com/chromascape/web/artifact/ImmutableArtifactStore.java` — added
  `DurabilityMode` (`STRICT` default, `DEVELOPMENT` opt-in), a persisted
  `durability.json` qualification marker with bidirectional refusal, a capability probe at
  construction, an injectable directory-force primitive, and forced persistence for object
  and reference publication, knowledge-snapshot activation, quarantine, development reset,
  temporary cleanup and newly created directories. Admission refusal happens before any
  object, reference or marker write; mid-publication failures retain unreferenced content
  and never delete shared blobs.
- `src/main/java/com/chromascape/web/artifact/ArtifactRegistryConfiguration.java` — wires
  `artifacts.durability-mode` (default `strict`).
- `src/main/java/com/chromascape/web/aiplayer/prototype/FishingDatasetBuilderService.java`
  — passes the same mode to its internally constructed store.
- `src/test/java/com/chromascape/web/artifact/ImmutableArtifactStoreTest.java` — explicit
  platform mode plus Windows refusal and POSIX strict-contract tests.
- `src/test/java/com/chromascape/web/artifact/ArtifactRegistryControllerTest.java`,
  `src/test/java/com/chromascape/web/aiplayer/prototype/FishingArtifactRegistryIntegrationTest.java`
  — positive fixtures opt in explicitly; no implicit platform downgrade.
- `src/test/java/com/chromascape/web/artifact/TestDurability.java` — new test-only mode
  selector.
- `docs/builderflow/sc-274-sc-276-continuation-20261019/remaining-implementation-plan-20261024.md`
  plus two pointer edits — the plan document.

Native owning repository (`C:\Dev\IdeaProjects\.worktrees\sc276-recovered-20261021`):

- Branch `codex/sc274-native-recovered-20261024`, commit
  `79382be38272da3d36f627c9932384692dd27873`, containing the exact 105 recovered files.
- Recovery bundle retained at
  `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\wp2-native-20261024T055412Z\native-sc274-20261024.bundle`.

Commits: `45700523` (plan), `748f2d93` (durability implementation), `e44d6506` (formatting).

## Tests

Run in an isolated checkout (`C:\Dev\IdeaProjects\.worktrees\sc274-wp-verify-20261024T055412Z`)
at `e44d6506`, JDK 17, `--offline`:

- `artifactCompletionTest`: 68 tests, 0 failures, 0 errors, 4 skipped (the POSIX-only
  strict positive tests, skipped on Windows). Includes Windows strict refusal and
  development-marker tests.
- `replayTest --tests XrspsSessionRepositoryIntegrationTest`: 3 tests, 0 failures, against
  an owned disposable `postgres:16-alpine` with role/database `test`/`sc1991_test`.
- `spotlessJavaApply` then `git diff --ignore-cr-at-eol` on the touched files: empty, so
  the touched files are Google Java Format clean. Repository-wide `spotlessJavaCheck`
  still fails on 1364 unrelated files; no mass reformat was applied.
- `node server/src/replay/worker-launcher.js --capabilities` on Windows: fails because the
  recovered dependencies are Linux-targeted (`@esbuild/linux-x64`). This is a platform
  prerequisite failure, not a test result.

## Remaining work

- Run the four POSIX-only STRICT tests on Linux; Windows only proves refusal.
- WP4: 10 worker-gated cases remain blocked by
  `AUTHORITATIVE_RUNTIME_QUALIFICATION.complete === false`; 4 further cases need a real
  client and/or configured provider.
- WP3: add the explicit Fishing method, resolve `CAST_SPELL` without a method contract,
  and keep glassblowing unadvertised until replay-level evidence exists.
- WP5: OS-level enforcement and measured budgets are unimplemented.
- WP6-WP9 remain blocked on migration authority, clock owners, SC-800/trust/genuine
  capture and human Watch.
- No acceptance row is closed.
