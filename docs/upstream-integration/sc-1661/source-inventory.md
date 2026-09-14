# SC-1661 — Source Inventory and Pin Record

Story: SC-1661 "Reconcile upstream changes and pin the integration base"
Branch: `codex/sc-1661-chromascape-upstream-reconciliation`
Worktree: `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1661-reconciliation`
Provisional starting revision: `583a05b91687d156b1baa52dbda2b219604c7b39`
Inventory captured: 2026-09-14

## Pinned source revisions

All revisions below were re-fetched and re-observed on 2026-09-14. **No remote reference
moved during the refresh**: `git fetch --all --prune` left all 23 remote-tracking refs
byte-identical to their pre-fetch values. Every revision previously recorded therefore
still stands and none needed reassessment.

| Source | Full SHA | Branch / ref | Commit date | Worktree location | Tracking |
|---|---|---|---|---|---|
| Main checkout | `7ab2a807064dc1ef968d4b3f62b6038ccaa4633d` | `integration/chromascape-consolidated-20260901` | 2026-09-01 | `IdeaProjects/ChromaScape` | local only (behind `origin/`) |
| Published consolidated | `471262bfeefa0c1d0a91a367227a3fd78586814e` | `origin/integration/chromascape-consolidated-20260901` | 2026-09-07 | `IdeaProjects/ChromaScape-sc1429-verify` (detached) | remote only vs main checkout |
| Local consolidation (provisional baseline) | `583a05b91687d156b1baa52dbda2b219604c7b39` | `wip/chromascape-consolidation-20260907` | 2026-09-12 | `IdeaProjects/ChromaScape-consolidation` | local only, **not published** |
| Published durable-current | `16a3d72530e365b260a6798cab0afd4b006e3fd0` | `integration/sc1199-durable-current` | 2026-09-11 | `Worktrees/ChromaScape-sc1199-integration-current` | in sync with `origin/` |
| Fedora remote | `97284f1bc41e5cfaf10dd8650abf5f09b1cb37a1` | `codex/fedora-remote-executor` | 2026-09-08 | `IdeaProjects/ChromaScape-fedora-remote` | in sync with `origin/` |
| Durable Fedora (sc-1199 final) | `d5b2d821cdf9490426cf0572ec1dd4beb38ad53d` | `integration/sc1199-fedora-durable-20260909` | 2026-09-10 | `Worktrees/ChromaScape-sc1199-final` | not published |
| Upstream integration source | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` | `StaticSweep/main`, tag `v0.5.0` | 2026-07-10 | (bare remote ref) | remote `StaticSweep` |

Secondary refs observed for completeness:

- `origin/main` = `eb18d2d0efebcda75399ead6d43661cab9ee39e3` ("Update README.md")
- `origin/main2` = `8189e4d8fb22a926eadf0f069cfb7cd4a86d278b` ("working")
- `evoke-afk/main` = `146d83a`
- Remotes configured: `StaticSweep` (upstream), `evoke-afk`, `origin` (`ikiddoi/ChromaScape`)

Both `origin/main` and `origin/main2` are **fully contained** in the provisional baseline
(329 and 227 baseline-only commits respectively, 0 target-only).

## Ancestry with the provisional baseline

Merge bases, recomputed against `583a05b` (the earlier figures were treated as provisional
and are now verified rather than inherited):

| Comparison | Merge base | Baseline-only | Target-only |
|---|---|---|---|
| vs main checkout `7ab2a80` | `7ab2a80` itself | 77 | **0** |
| vs published consolidated `471262b` | `471262b` itself | 41 | **0** |
| vs durable-current `16a3d72` | `e5cdf81db75d76cf086a1b421282e1f5aefb25aa` | 29 | 3 |
| vs Fedora `97284f1` | `7ab2a80` | 77 | 1 |
| vs upstream `5ea3b99` | `46db1c462752d39bd0c04da630bfe5d35edc117a` | 292 | 15 |

The previously recorded divergence figures (77/1 vs Fedora, 29/3 vs durable-current, 15
upstream commits) are **confirmed unchanged**. The provisional baseline transitively
contains the main checkout and the published consolidated branch with zero commits behind.

### Newly established facts

1. `46db1c4` ("Update CONTRIBUTING.md to remove outdated sections") is the true upstream
   fork point. The provisional baseline is 292 commits ahead of it — this is the custom
   fork surface, not 15 commits of upstream delta.
2. The durable tick-batch / SC-1199 recording lineage is **already present in the provisional
   baseline**. `16a3d72` is a merge of `origin/main` (itself fully contained in the baseline)
   with `d5b2d82`, and the merge base is `e5cdf81`. Only three commits are genuinely unique
   to durable-current.
3. The Fedora launcher-routing change exists twice under different SHAs (`97284f1` and
   `ced7487`) — see `custom-change-reconciliation.md`.

## Worktree integrity

- The new story worktree was created clean: `git status --porcelain` = 0 entries at
  `583a05b`.
- No existing worktree, branch, or uncommitted change was modified, moved, or pruned.
  The five pre-existing ChromaScape worktrees retain their branches and dirty state.
- Three stale/prunable worktree entries exist under `.git/worktrees/` pointing at
  Windows `C:/w/...` paths (`chromascape`, `chromascape1`, `chromascape3`, `chromascape5`).
  These were **left untouched** and are recorded here only as an observation.
- The canonical Felix project identity for this story remains
  `C:\Dev\IdeaProjects\ChromaScape` even though the story worktree lives at a different
  filesystem path.
