# ChromaScape branch consolidation disposition — 2026-09-08

## Consolidation target

- Branch: `wip/chromascape-consolidation-20260907`
- Baseline before final working-tree fixes: `e261f0f`
- Purpose: preserve the valid ChromaScape work from divergent local and remote branches without
  merging snapshots, generated state, or unrelated upstream forks.
- This is a semantic consolidation. A branch can be superseded even when Git does not report its
  commits as ancestors or patch-equivalent, because the retained implementation was reformatted,
  combined with later work, or hardened further.

No branch or worktree should be deleted from this document alone. No consolidation commit should be
pushed or merged until the final full test run is green and the remaining working-tree changes are
committed without absorbing concurrent work.

## Superseded: do not merge or cherry-pick

| Branch or commit | Disposition | Reason |
| --- | --- | --- |
| `backup/consolidated-pre-github-cleanup-20260901` | Superseded | Its relevant SC-968 authorization/runtime changes are retained in the consolidated SC-967/SC-968 implementation. The branch also aggregates older divergent work and is not a safe merge unit. |
| `luna-max/remediation-chromascape-20260904T223950Z` (`c672722`) | Superseded | Native-client callback isolation is present in the consolidated runtime. Do not cherry-pick `c672722`. |
| `nathangemieee/sc-1452/sc-968-follow-up-micro6_mana` | Superseded | The current acceptance scripts contain its launcher-exit detection (`835290c`), capture-contract propagation, RuneLite Gradle/source-provenance gates, bounded startup retry, and Java stderr-safe version capture, plus later hardening. Do not cherry-pick `835290c` or the branch as a whole. |
| `sc-1429-sc-1449-v2-boundary` | Superseded | The durable gameplay-v2 boundary was consolidated and subsequently hardened. Do not cherry-pick the branch tip. |
| `closeout/fishing-shadow-corrective` | Superseded | Recording/shadow compensation and rollback behavior are represented by the consolidated debugger, replay, and recovery implementation. |
| `origin/integration/8189-viewport` | Superseded | This remote points at the same fishing/shadow corrective history and adds no remaining port. |
| `replay-xrsps-final` | Superseded | Replay identity, deterministic playback, lifecycle, diagnostics, certification, and recovery behavior were consolidated and hardened further. |
| `sc-1173-compile-recovery` and `origin/sc-1173-compile-recovery` | Superseded | This is an older broad workspace consolidation, including transient local artifacts; the valid source changes are represented by the narrower consolidation commits. |
| `sc-572-runtime-certification` and `origin/sc-572-runtime-certification` | Superseded | Verified bundle ingestion, bounded streaming, conflict handling, authentication, artifact storage, and tests are present in the consolidated stabilization work. |
| `sc-584-catalog-consumer` and `origin/sc-584-catalog-consumer` | Superseded | Catalog-v2 validation and consumer tests are present, with later fail-closed identity and canonical-hash hardening. |

## Deliberately excluded

| Branch | Disposition | Reason |
| --- | --- | --- |
| `sc968-wip-snapshot` | Excluded/polluted | Explicitly identifies itself as an unreviewed dirty-tree snapshot and contains local databases, Gradle caches, metadata, logs, and other generated state. Never merge it wholesale. |
| `evoke/agility` | Excluded/unrelated | Historical third-party utility work, outside the ChromaScape consolidation scope. |
| `StaticSweep/main` (and `StaticSweep/HEAD`) | Excluded/external | External fork history, not a ChromaScape feature branch. |
| `evoke-afk/evoke/agility` and `evoke-afk/evoke/custom-script-directories` | Excluded/external | External fork branches, unrelated to this consolidation. |

## Upstream issue that must remain fail-closed

Current Micro6 producer fixtures compute mode-specific catalog hashes because
`CanonicalCatalogModel.TASK_KEYS` includes schemas. At the same time, Micro6 health and registry
surfaces expose one `catalog_hash`. ChromaScape binds catalog identity to that single value, so
accepting both mode-specific hashes would weaken the identity boundary.

ChromaScape therefore retains the schema-free stable catalog identity and intentionally rejects the
current incompatible producer fixtures with `catalog_hash_mismatch`:

- Catalog hash: `sha256:17b13670b40dba066bb869aa2d80dbaaf8f83c76113d8a53d201794ce02d5ad4`
- Settings schema hash: `sha256:95d587eacd8492a5f65e56e34fcf6085ab0c85aa2643da7f69823b9be0cf0957`
- Schema bundle hash: `sha256:93e263fa9413599010166a886f710579b056ab72487ff8fa8fab6fac7f0f073f`

Micro6 needs an upstream protocol correction that restores one stable catalog identity across
lightweight and full publication modes. That correction should be verified in Micro6 first and then
its fixtures can be refreshed in ChromaScape; it is not a reason to relax ChromaScape validation.

## Validation and closeout

- `gradlew.bat test -PtestMaxHeapSize=1g --no-daemon --no-parallel` completed with `BUILD
  SUCCESSFUL` on Windows in 16 minutes 45 seconds.
- The provider boundary class and its timeout method also passed independently after adding bounded
  process-tree cleanup.
- `checkstyleMain` and `checkstyleTest` completed successfully. The repository still reports its
  existing warning-level violations.
- Both working-tree and staged `git diff --check` gates passed.
- Only the intentional runtime/test fixes and this disposition were committed. Concurrent
  gameplay-v2, Felix, evidence, source-schema, generated, and pending-outbox changes remain
  uncommitted and must be handled by their owners.

An optional ChromaScape restart/runbook verification remains useful before deployment. Merge and
push are separate operator decisions; neither was performed as part of this consolidation.
