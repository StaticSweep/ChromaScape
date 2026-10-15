# SC-1976 current-main merge gate

The user authorized merging the paired PRs and completing SC-1976 if done. The accepted combat implementation remains verified, but the current-main Micro6 integration does not compile. Neither PR was merged and no Done transition was requested. This result supersedes earlier statements that only review and merge remained.

Existing work: Felix task `task_1791760667867_s74wdsgf`, ChromaScape [PR #59](https://github.com/ikiddoi/ChromaScape/pull/59), Micro6 [PR #162](https://github.com/ikiddoi/micro6/pull/162). Canonical identities: `C:\Dev\IdeaProjects\ChromaScape` and `C:\Dev\IdeaProjects\micro6`.

## Complete implemented plan

1. Publish combat-tick-row/v1 with captured/derived/deferred field provenance, semantic vocabulary mappings and diagnostic-only raw input.
2. Capture bounded combat state, prayer/special attack, NPC targets, environment, inventory/equipment and inputs in Micro6 while retaining negotiated packet/checksum behavior.
3. Persist/project additive data with deterministic coverage, immutable recording boundaries, measured physical-video alignment and per-recording client/server/build/revision/schema/media identity.
4. Require selected-scene-tile and changed-destination proof plus actual progress within four ticks for movement labels; reject unsupported, stale, competing and unproven historical movement.
5. Verify real combat end to end and retain an accepted sanitized fixture and meaningful producer/server regressions.
6. Check the actual current-main integration before merging the paired PRs; only then pass the real Felix completion gate and synchronize Shortcut Done.

Items 1-5 have the evidence in [the accepted capture report](../20261015-2b0178ae-acceptance/README.md) and [the movement fix plan](../20261015-walk-attribution-fix/README.md). Item 6 is blocked by the upstream build below. Eating and hosted Actions remain excluded by the user. Full SC-751 Fishing, wider readiness/model promotion, implementing unrelated boss plugins and production rollout are outside this delivery.

Rules: `C:\Dev\IdeaProjects\AGENTS.md` and both canonical project `AGENTS.md` files. The authoritative Felix plan retains the full scope, exclusions, Given/When/Then criteria, checklists, architecture, ERD and API contract. No duplicate task or validation waiver is used.

## Acceptance results

The previous accepted `recording_2b0178ae` result remains valid: 101 consecutive ticks, no gaps/duplicates, every required field present, zero mismatches across 101 PostgreSQL rows x 11 sections, 49 resolved NPC-target ticks, 22 eligible attack/movement/prayer labels, all 101 measured endpoints within 100 ms (maximum 37.325 ms), 1,539 decoded frames and immutable saved build/media identity and finalized data. No further gameplay was performed in this merge check.

An isolated integration worktree preserves the active client, its package and dirty runtime files. Micro6 PR head `429e32978b1027ca913660ef38f69b92d35bb3c0` merged cleanly with fetched main `5aa1b3ee7de18bedebd749d8fe00c98e8164b99f`, creating **local, unpushed** commit `5efc76965c47e084b908bf5b02b75e92866cb660`. Main contributed 12 commits; the actual integration delta is 313 files. Multiple merge bases make a triple-dot diff misleading, so inspection used the actual merged tree and two-point diffs.

The current-main build fails before unit tests. Its 42 reported compiler errors concern Fight Cave/Zulrah dependencies missing from Git, including `drofirecape.core.WaveBook`, `FcModel`, `FcSpawnPredictor`, `FcTickPrayers`, `FcPrayerUi`, `FcFrame`, `FcPredictorGate`, `ZulrahFeroxReturn`, `ZulrahDeathRecovery` and `ZulrahRecoveryBankRoute`. The affected production directories have **no difference between upstream main and the merged tree**. These are inherited upstream failures, not failures of the accepted capture regression.

Source review also finds upstream `DebugRecorderIntegrationBridge` calls to nonexistent `DebugRecorderPlugin.ensureChromaScapeSessionForTask(String, String, Map)` and `mouseCatalogControl(String, String)`. The plugin exposes the existing single-argument and provenance-map session overloads. These invalid calls also match upstream main. The failed compilation is not claimed to have independently reported these two calls: missing-type errors prevent reaching later checks.

ChromaScape main `9968880cc402f755d3e4c2f98afa9a861aea24ce` is already an ancestor of its tested PR head `882529edf077d23c87cf85e27b920879349599f0`. Both PRs were OPEN, ready for review, conflict-free and had zero reviews/review threads when checked. GitHub mergeability does not prove that the resulting tree builds.

## Changed files

This README and adjacent `aggregate.json` record the merge check. No new production source fix, fabricated missing class, disabled plugin or runtime replacement was made. The local Micro6 integration commit contains upstream main plus the unchanged SC-1976 branch; it was not pushed to PR #162. Full story source changes remain listed in the linked accepted-capture and movement reports and the existing PRs.

## Tests

- Current-main Micro6 integration: native Corretto 21 / Gradle 8.8 command `gradlew.bat :client:test :client:verifyCaptureContract :client:verifyInjectedClient :client:shadowJar -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --console=plain`, with `SC967_VECTORS_PATH=C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json`.
- **FAILED** at `:client:compileJava` after 5m11s, 42 compiler errors. No client unit tests ran and `shadowJar` did not complete. Capture contract and injected-client revision-241 SHA-256 verification passed before the failure. Private complete log: `C:\Dev\IdeaProjects\.worktrees\felix-native-runtime\sc1976-merge-micro-tests.log`.
- Earlier unchanged Micro6 PR head: 1,725 tests/capture contract/package passed. Those results do not validate the newer integrated main.
- Latest ChromaScape source: 27 focused schema/ingestion/label/coverage/real-fixture tests passed with zero failures/errors/skips. No new server source change or redundant rerun occurred in this check.
- Hosted Actions remain excluded; their jobs did not start because of the Actions budget. Four Docker debugger suites and production migration-chain certification remain unrun.

## Remaining work

1. Restore and review the missing upstream plugin sources and reconcile the bridge with the actual recorder API as a coherent upstream repair. Do not create placeholder gameplay classes, silently disable plugins, or treat other stories' incomplete code as accepted SC-1976 functionality.
2. Repeat full native client tests, capture contract, injected-client guard and packaging on the actual integrated tree; inspect any subsequent errors now hidden by the missing types.
3. Recheck current heads and review threads, then merge both existing PRs with exact-head guards.
4. Append actual merge evidence to Felix, pass `in_review -> done` only after the real gates succeed, and synchronize Shortcut Done once with the completion marker. No Done marker has been posted.

## Risks

Merging now would put a client that cannot compile on the delivery path. Prior green branch tests and accepted combat data do not erase the upstream integration failure. The missing implementations are not present in this checkout's Git history or the canonical checkout; reconstructing unrelated boss behavior is not a safe merge-only repair. Full SC-751 Fishing remains open.

## Rollback

No merge or runtime deployment occurred. Preserve the unpushed integration worktree/commit and logs for diagnosis; keep both existing PR heads intact. The logged-in client and verification/original servers were not stopped or replaced. Keep immutable recordings, saved profiles and runtime reservation/lock files.

## Tracker result

See the existing Felix task and Shortcut SC-1976 for the read-back of the blocker transition/comment. The reviewed Shortcut mapping has no blocked state (`blocked: null`), so a blocked Felix result must not be misreported as Shortcut Done or as a completed external blocked transition.
