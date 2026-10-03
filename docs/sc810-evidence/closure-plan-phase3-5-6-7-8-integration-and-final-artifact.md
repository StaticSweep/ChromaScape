# SC-810 Phases 3, 5-8: integration onto sc-572-remediation, final artifact, final bundle (2026-09-11)

## Phase 3: integration

Canonical `C:\Dev\IdeaProjects\micro6` was found on branch `codex/sc-1618-micro6` with another
operator's extensive uncommitted work (build.gradle.kts, Micro6BuilderExecutorServer.java,
gameplay files, etc.) — never touched. `sc-572-remediation` (the user-selected integration
target) was not checked out in any live worktree, so a new dedicated worktree was created instead:

`C:\Dev\IdeaProjects\micro6-sc810-integration`, branch `sc810-integration-20260911`, based on
`sc-572-remediation`'s tip `c52f4e6698` (which had advanced 3 unrelated commits — ChromaScape
WebSocket client hardening — past this session's original `90f793e8` base; no file overlap with
anything changed here, confirmed via `git diff --stat`).

Cherry-picked all 6 SC-810 packaging-worktree commits in order, cleanly, no conflicts:
`e4132ff3ca`, `91d213fdc6`, `4a6d499608`, `9034445686`, `a2b58bcd33`, `bb789cc52a` → new tip
`7b3fc3b21a5c2afcfd078c93f4ffc5670aa44fc2`. `git diff --stat` against the original base confirms
exactly 4 files from this session's own commits (`build.gradle.kts`,
`UniversalTaskDiscoveryService.java`, `Sc810RealBundleVerificationTest.java`,
`UniversalTaskDiscoveryServiceJarDiscoveryTest.java`) plus the 3 pre-existing unrelated upstream
files — nothing stray, no secrets, no build outputs.

`git diff --check` flagged several "trailing whitespace" lines inside
`UniversalTaskDiscoveryService.java`; investigated directly rather than accepted at face value:
byte-for-byte comparison of the flagged lines against the pre-change blob showed them identical
(no trailing whitespace, no CRLF) — a diff-algorithm hunk-alignment artifact from a large edit in
a big, repetitive-pattern file, not a real defect. Independently confirmed the file already had
189 lines with genuine trailing whitespace before this session's changes (180 after — marginally
improved, not regressed) — a pre-existing style issue, consistent with this codebase's
already-documented Checkstyle incompatibilities, not touched further.

## Phase 5: pre-package verification (on the integration branch)

- `:client:compileJava :client:compileTestJava` — BUILD SUCCESSFUL.
- `:client:test` (1595 tests): 1590 passed, **5 failed, all pre-existing and unrelated**:
  - `TaskSourceManifestSchemaEqualityTest` (2 failures) — a schema-hash pin drift between the
    canonical `task-source-manifest-v1.schema.json` and its ChromaScape mirror; neither file was
    touched by any of the 6 integrated commits.
  - `TaskSourceProcessorPathTest` (3 failures) — its own javadoc states it depends on a
    `sc810SourceTests` Gradle task (used earlier this session in a different worktree/branch) to
    supply `micro6.source.compiler.processorpath`; that task does not exist anywhere in
    `sc-572-remediation`'s history, confirmed via full-file grep before and after the cherry-picks
    — a pre-existing gap on this branch, not something removed by this session.
  - Note: the note **task `:client:sc810SourceTests` referenced in the original plan does not
    exist on this branch at all** (confirmed via grep across the whole build.gradle.kts and via
    Gradle's own "task not found" error) — plain `:client:test` was run instead, which covers the
    same test classes since they are ordinary JUnit tests, just without that task's extra
    sysprop wiring.
- `:client:check`: **2 failures, both `checkstyleMain`/`checkstyleTest`**, both failing with
  "Unable to process files" against effectively the entire pre-existing sourceset (not files this
  session touched) — the same pre-existing Checkstyle-version/modern-Java-syntax incompatibility
  documented multiple times earlier this session. Not called green; not modified to "fix" the
  report.
- A `:client:test` run leaves `runelite-client/.micro6/gameplay-v2-reservations/gameplay-v2-reservations.snapshot.jsonl`
  modified and a `.lock` file created (test-execution side effect of the gameplay-v2-reservation
  test fixtures) — reverted (`git checkout --`) and the lock file removed before proceeding, to
  restore the clean tree `TaskSourceIdentity` requires for bundle generation.

## Phase 6: final authoritative artifact

Clean tree confirmed (`git status --porcelain=v1 -uall` empty) at commit
`7b3fc3b21a5c2afcfd078c93f4ffc5670aa44fc2`, tree `d01d0e0096f6163d9c05d60d7434b22f16e5ea69`.
Built `shadowJar` + `micro6ArtifactIdentity`:

- JAR SHA-256 (independent `sha256sum`): `67381898e199871f337af768c13a8c9ae26b7983391e84c81a16027d76d6867d`
- Manifest `Micro6-Build-Id`: `7b3fc3b21a5c2afcfd078c93f4ffc5670aa44fc2` — matches commit exactly.
- Sidecar `build_id`/`artifact_digest`/`artifact_path` all match the commit, the independent
  digest, and the exact JAR path respectively.
- A stray re-run of `shadowJar` later (during Phase 7's env-var experimentation) reproduced
  byte-identical output (same digest) — confirms the build is deterministic; the artifact
  identity guarantee holds regardless of rebuild.

## Phase 4 + 7: trust environment and final signed bundle

Per the plan's own recommendation (and consistent with the prior review's finding 7), this
acceptance run uses **`local-development`** throughout — not `production` — since the signing
key (`sc810-dev-ed25519-611ab9dd95a518af`) is a locally-generated development key never adopted
as a production trust root. New, separate bundle root:
`C:\Dev\SC810\package-20260910\integration-bundle-root` (previous bundle roots preserved
untouched as historical evidence).

Generated and promoted: bundle `srcbundle_b8cac086a3a9a54ad408edec`, manifest hash
`sha256:d6c377925ac98c262c6973da543e171faa2a3968fef062beaa7b0843f6975cf8`, snapshot
`srcsnap_d6c377925ac98c262c6973da`, environment `local-development`, entry point
`FishingExecutionTask`, `build.artifact_digest`/`build.build_id` match the artifact above exactly,
`COMPLETE` marker present, `current.json` atomically promoted. Closure measurements verified via
correct JSON parsing (not naive grep, which initially gave misleading counts): **34 files, 1716
symbols, 173 closure edges** — exactly matching the original pre-session measurement.

Verify-only re-validation, twice, against the promoted on-disk bundle: updated
`Sc810RealBundleVerificationTest`'s expected constants to this bundle (commit `c6dfbaaf15`) and
ran it twice consecutively — **5/5 passing, 0 skipped, both times**.

## Phase 8: packaged-runtime readiness

Relaunched the same disposable standalone verifier (`Sc810RunningArtifactAcceptance`, not
committed to either repo) against the final integration JAR, its own sidecar, and the new bundle
root/key/environment:

```
Micro6BuilderExecutorServer loaded from: file:/C:/Dev/IdeaProjects/micro6-sc810-integration/runelite-client/build/libs/client-1.12.39-SNAPSHOT-shaded.jar
sourceInitializationError: null
start() returned: true
lastStartError: null
GET /health -> 200
build_id/artifact_digest/source_snapshot_id all match the artifact/bundle above exactly.
```

Fishing Training confirmed discoverable in the live catalog (`task_key: FISHING_TRAINING` present
multiple times in the alias list, `count: 319`).

`catalog_state` is still `DEGRADED` — **by explicit user decision this session, this pre-existing,
unrelated defect (`schema_failed_count: 14`, `validation_error_count: 8`, `task_failed_count: 0`,
identical counts to the packaging-worktree run) is documented and intentionally not
investigated further right now.** It is not caused by, and is not evidence against, the
running-artifact-gate fix, which passed cleanly on both the packaging worktree and this final
integrated branch.

## What Phase 8 does not prove

Same caveat as every other proof this session: this used a mocked `AccountBuilderPlugin`, no live
RuneLite client, no LeeuwBot, no ChromaScape. The full managed-capture runtime path remains
unverified pending those.

## Held per explicit user decision (2026-09-11)

- Phase 9 (account-record migration): **on hold**, not attempted — real data mutation, requires
  separate authorization.
- The `schema_failed_count`/`validation_error_count` catalog defect: **documented, not
  investigated further** — out of SC-810's scope, of unknown size.

## State of both repositories

- `C:\Dev\IdeaProjects\micro6-sc810-integration` — branch `sc810-integration-20260911`, HEAD
  `c6dfbaaf15`, clean tree, 7 commits ahead of `sc-572-remediation`'s tip (`c52f4e6698`).
  **Not pushed, not merged into `sc-572-remediation` or any shared branch.**
- Canonical `C:\Dev\IdeaProjects\micro6` — untouched throughout, still on `codex/sc-1618-micro6`
  with the other operator's uncommitted work exactly as found.
